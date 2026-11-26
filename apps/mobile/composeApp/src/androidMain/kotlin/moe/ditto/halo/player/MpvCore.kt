package moe.ditto.halo.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Owned thin adapter over libmpv's Android JNI ([MPVLib], mpv-android lineage) —
 * the direct parallel to iOS's `MPVCore.swift`. The prebuilt AAR is only the
 * binary provider; MPVLib types never leave this file, so the boundary the
 * common shell sees is the platform-neutral player contract, and the binary
 * provider stays replaceable (production ships an owned reproducible build).
 *
 * mpv's own thread delivers property/events through [MPVLib.EventObserver]; this
 * class translates them to a small owned [Listener] using seconds and the
 * neutral [PlayerTracks] schema. It never surfaces an mpv event id, format int,
 * or property name upward.
 */
internal class MpvCore private constructor(
    private val mpv: MPVLib,
    val id: String,
    private val callbackExecutor: Executor?,
    /**
     * The engine options this core was created with. [MpvProfile.vo] is the
     * video output switched on when a surface attaches; on a profile with
     * [MpvProfile.directVideo], the current [VideoPath] picks it instead.
     */
    private val profile: MpvProfile,
) {
    interface Listener {
        fun onReady(durationSeconds: Double?)
        fun onPosition(positionSeconds: Double)
        fun onPauseChanged(paused: Boolean)
        fun onTracks(tracks: PlayerTracks)
        /** Null once the cache stops stalling playback. */
        fun onBuffering(buffering: PlayerBuffering?)
        fun onBufferedPosition(positionSeconds: Double)
        fun onVideoFormat(format: VideoFormat)
        fun onEnded()
        /** The file left its end because the viewer sought back. */
        fun onEndCleared()
        fun onError(message: String)

        /**
         * The video path changed, on a profile with [MpvProfile.directVideo]
         * only. True for [VideoPath.Direct], where the app sizes the view.
         */
        fun onVideoPath(direct: Boolean) {}

        /**
         * The subtitle text the app should draw now, empty for none. Only on
         * the direct path; it is cleared when that path ends.
         */
        fun onSubtitleText(text: String) {}
    }

    @Volatile private var listener: Listener? = null
    @Volatile private var destroyed = false
    // mpv fires time-pos many times per second; forward only whole-second
    // changes. The shell displays seconds, and flooding recompositions would
    // otherwise starve Compose's idle sync (and waste work).
    @Volatile private var lastPositionSecond = Long.MIN_VALUE
    // Same reasoning for the cache's reach: the transport bar draws it as a
    // fraction of a whole timeline, so sub-second updates buy nothing visible.
    @Volatile private var lastBufferedSecond = Long.MIN_VALUE
    // Whether the cache is currently stalling playback, and how full mpv
    // considers it. Held because the two arrive as separate observations and
    // the overlay needs both at once.
    @Volatile private var pausedForCache = false
    @Volatile private var cacheFillPercent: Int? = null
    // The decoded picture, assembled from four separate observations and only
    // touched on the callback executor. Reported whole, and only when it moves.
    private var videoFormat = VideoFormat()
    private var containerFps: Double? = null
    private var estimatedFps: Double? = null
    // END_FILE's reason is hidden by this JNI binding. The small state machine
    // distinguishes a real failure from the END_FILE that loadfile emits for
    // the file it replaced, and serializes the caller, event and log threads.
    private val fileLifecycle = MpvFileLifecycle()

    // The direct/composed choice, null on a profile that offers only the
    // composed path, which then behaves exactly as before it existed. It and
    // the fields below are touched only on the callback executor: every
    // public call arrives there from the host, and every mpv callback is
    // dispatched there.
    private val videoPaths: VideoPathTracker? = if (profile.directVideo) VideoPathTracker(directAllowed = true) else null
    private var surfaceAttached = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    // mpv's own starting `vid=auto`; [setVideoEnabled] keeps it current so a
    // path switch never turns on video that the host turned off.
    private var videoEnabled = true

    // The subtitle debounce. The main looper only times it: the callback is
    // dispatched onto the executor, and the token makes a callback that was
    // already on its way when the debounce was cancelled do nothing.
    private val settleHandler: Handler? = if (profile.directVideo) Handler(Looper.getMainLooper()) else null
    private var settleTask: Runnable? = null
    private var settleToken = 0L

    private val eventObserver = object : MPVLib.EventObserver {
        /**
         * Value-less observations. `sid`/`aid` are observed for the fact that
         * they changed, not for what they changed to: which track is current is
         * read back off `track-list` like every other track fact, so a
         * selection mpv made on its own reports the same way an app-driven one
         * does. Without this the panel keeps highlighting the previous row
         * after a switch, because the track *count* has not moved.
         */
        override fun eventProperty(property: String) {
            dispatch { handleProperty(property) }
        }

        override fun eventProperty(property: String, value: Long) {
            dispatch { handleProperty(property, value) }
        }

        override fun eventProperty(property: String, value: Double) {
            // These observations are already values from mpv, not property
            // reads. Filter them before queueing so a high-frequency time-pos
            // stream cannot starve serialized commands behind the executor.
            when (property) {
                "time-pos" -> {
                    val second = value.toLong()
                    if (second != lastPositionSecond) {
                        lastPositionSecond = second
                        dispatch { listener?.onPosition(value) }
                    }
                }
                "demuxer-cache-time" -> {
                    val second = value.toLong()
                    if (second != lastBufferedSecond) {
                        lastBufferedSecond = second
                        dispatch { listener?.onBufferedPosition(value) }
                    }
                }
                ContainerFps, EstimatedFps -> dispatch { handleFrameRate(property, value) }
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            dispatch { handleProperty(property, value) }
        }

        override fun eventProperty(property: String, value: String) {
            dispatch { handleProperty(property, value) }
        }

        override fun event(eventId: Int) {
            dispatch { handleEvent(eventId) }
        }
    }

    private fun dispatch(block: () -> Unit) {
        val executor = callbackExecutor
        if (executor == null) {
            if (!destroyed) block()
            return
        }
        try {
            executor.execute {
                if (!destroyed) block()
            }
        } catch (_: RejectedExecutionException) {
            // Activity shutdown can close the executor while mpv is still
            // draining callbacks. The core is being destroyed, so dropping
            // those stale callbacks is the safe outcome.
        }
    }

    private fun handleProperty(property: String) {
        when (property) {
            "sid", "aid" -> emitTracks()
            // Value-less: the decoder is gone, or mpv dropped the video track.
            // The check reads both back rather than trusting this event.
            HwdecCurrent, "vid" -> checkDirectVideo()
            SubText -> onSubtitleText("")
            // A value-less observation of the picture means mpv has none right
            // now (between files, or audio only), so that part of it is gone.
            VideoWidth -> updateVideoFormat(videoFormat.copy(width = 0))
            VideoHeight -> updateVideoFormat(videoFormat.copy(height = 0))
            VideoGamma -> updateVideoFormat(videoFormat.copy(gamma = null))
            VideoColorMatrix -> updateVideoFormat(videoFormat.copy(dolbyVision = false))
            ContainerFps, EstimatedFps -> handleFrameRate(property, null)
        }
    }

    /**
     * The file's own frame rate when it states one; otherwise the first rate
     * decoding measures. The measured one wanders by small amounts while
     * playing, so it is taken once per file rather than followed.
     */
    private fun handleFrameRate(property: String, value: Double?) {
        val usable = value?.takeIf { it.isFinite() && it > 0 }
        when (property) {
            ContainerFps -> containerFps = usable
            EstimatedFps -> if (usable == null || estimatedFps == null) estimatedFps = usable
        }
        updateVideoFormat(videoFormat.copy(frameRate = containerFps ?: estimatedFps))
    }

    private fun handleProperty(property: String, value: String) {
        when (property) {
            VideoGamma -> updateVideoFormat(videoFormat.copy(gamma = value.ifEmpty { null }))
            // mpv names a Dolby Vision stream through its colour matrix; there
            // is no profile property to read on this path.
            VideoColorMatrix -> updateVideoFormat(videoFormat.copy(dolbyVision = value == "dolbyvision"))
            HwdecCurrent -> checkDirectVideo()
            SubText -> onSubtitleText(value)
        }
    }

    private fun updateVideoFormat(next: VideoFormat) {
        if (next == videoFormat) return
        videoFormat = next
        listener?.onVideoFormat(next)
    }

    private fun handleProperty(property: String, value: Long) {
        when (property) {
            "track-list/count" -> emitTracks()
            VideoWidth -> updateVideoFormat(videoFormat.copy(width = value.toInt().coerceAtLeast(0)))
            VideoHeight -> updateVideoFormat(videoFormat.copy(height = value.toInt().coerceAtLeast(0)))
            "cache-buffering-state" -> {
                cacheFillPercent = value.toInt()
                if (pausedForCache) emitBuffering()
            }
        }
    }

    private fun handleProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> listener?.onPauseChanged(value)
            "eof-reached" -> if (value) {
                if (fileLifecycle.markEofReached()) reportEnd()
            } else if (fileLifecycle.clearEofReached()) {
                // A seek back from the end: the next end has to count again.
                listener?.onEndCleared()
            }
            "paused-for-cache" -> {
                pausedForCache = value
                emitBuffering()
            }
        }
    }

    private fun handleEvent(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> fileLifecycle.onFileStarted()
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> onFileLoaded()
            MPVLib.MpvEvent.MPV_EVENT_END_FILE ->
                fileLifecycle.endFileFailure()?.let { listener?.onError(it) }
            MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> listener?.onEnded()
        }
    }

    // MPVLib.LogObserver is a plain Kotlin interface (not `fun interface`), so no
    // SAM lambda — an explicit object is required.
    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            val message = text.trim()
            // Primary diagnostic channel: mpv's own log lines land in logcat under
            // a greppable tag (Configuration/vo/hwdec/subtitle-track selection).
            // Links are cut to their host first: they carry debrid tokens.
            Log.i(LOG_TAG, "[$id][$prefix] ${redactLinks(message)}")
            // Preserve exactly what mpv said. No URL, HTTP status or host is
            // added by this layer; the error card prints this string verbatim.
            if (level in MPVLib.MpvLogLevel.MPV_LOG_LEVEL_FATAL..MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR &&
                message.isNotEmpty()
            ) {
                fileLifecycle.recordError(message)
            }
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Read only by Android instrumentation after [MPVLib.init] has completed. */
    fun isMutedForTest(): Boolean? = if (destroyed) null else mpv.getPropertyBoolean("mute")

    /**
     * Attach the render surface and turn the video output on (mpv-android
     * order). On a direct-video profile the current [VideoPath] names the
     * output and its decoder, so a path chosen while no surface was attached
     * takes effect here; nothing else decides them.
     */
    fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (destroyed) return
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("android-surface-size", "${width}x$height")
        surfaceAttached = true
        surfaceWidth = width
        surfaceHeight = height
        val paths = videoPaths
        if (paths == null) {
            mpv.setOptionString("vo", profile.vo)
            return
        }
        mpv.setOptionString("hwdec", hwdecFor(paths.path))
        mpv.setOptionString("vo", videoOutputFor(paths.path))
        if (paths.path == VideoPath.Direct) onSubtitleText(mpv.getPropertyString(SubText).orEmpty())
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (destroyed || width <= 0 || height <= 0) return
        surfaceWidth = width
        surfaceHeight = height
        mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    /**
     * Selects or drops the video track.
     *
     * Dropping it is how the decoder is destroyed on demand: mpv reinitialises
     * the video chain synchronously here, so when this returns no MediaCodec
     * instance is left holding buffers. Audio is untouched.
     */
    fun setVideoEnabled(enabled: Boolean) {
        if (destroyed) return
        videoEnabled = enabled
        mpv.setPropertyString("vid", if (enabled) "auto" else "no")
    }

    /** Turn video output off before releasing the surface (mpv-android order). */
    fun detachSurface() {
        if (destroyed) return
        surfaceAttached = false
        // Nothing is on screen to caption any more.
        if (videoPaths != null) listener?.onSubtitleText("")
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    /**
     * [headerLines] are the source's own request headers, one `Name: value`
     * each. They are replaced for every file: the core outlives a file, so the
     * last source's would otherwise go to this one's host. Each is appended as
     * one item, which takes the value as it is; setting the whole list would
     * split a value at its commas.
     */
    fun load(url: String, headerLines: List<String> = emptyList()) {
        if (destroyed) return
        // A chosen audio track is a track number, and mpv keeps it as its
        // setting: the number picked for the last file would select the same
        // number in this one, whatever language that is. Back to automatic,
        // where the preferred languages (below) decide.
        mpv.setPropertyString("aid", "auto")
        mpv.setPropertyString("http-header-fields", "")
        headerLines.forEach { line ->
            mpv.command(arrayOf("change-list", "http-header-fields", "append", line))
        }
        startFileOnDirectPath()
        fileLifecycle.onLoadRequested()
        mpv.command(arrayOf("loadfile", url))
    }

    fun stop() {
        if (destroyed) return
        if (videoPaths != null) {
            cancelSubtitleSettle()
            videoPaths.onStopped()
            listener?.onSubtitleText("")
        }
        fileLifecycle.onStopRequested()
        mpv.command(arrayOf("stop"))
    }

    fun setPaused(paused: Boolean) {
        if (destroyed) return
        mpv.setPropertyBoolean("pause", paused)
    }

    fun seekTo(positionSeconds: Double) {
        if (destroyed) return
        mpv.command(arrayOf("seek", positionSeconds.toString(), "absolute"))
    }

    /** mpv's `alang`: the languages to prefer when a file's audio track is picked automatically. */
    fun setPreferredAudioLanguages(codes: List<String>) {
        if (destroyed) return
        mpv.setPropertyString("alang", codes.joinToString(","))
    }

    fun selectAudioTrack(id: String?) {
        if (destroyed) return
        mpv.setPropertyString("aid", id ?: "no")
    }

    fun selectSubtitleTrack(id: String?) {
        if (destroyed) return
        if (videoPaths != null) {
            videoPaths.onSubtitleSelectedByApp()
            onSubtitleInputChanged()
        }
        mpv.setPropertyString("sid", id ?: "no")
    }

    fun setPlaybackRate(rate: Double) {
        if (destroyed) return
        mpv.setPropertyDouble("speed", rate)
    }

    /**
     * `panscan` is a 0..1 blend between fitting the picture and cropping it to
     * the screen, and the two ends are the only settings the design offers.
     */
    fun setVideoFillsScreen(fills: Boolean) {
        if (destroyed) return
        mpv.setPropertyDouble("panscan", if (fills) 1.0 else 0.0)
    }

    fun setAudioDelay(seconds: Double) {
        if (destroyed) return
        mpv.setPropertyDouble("audio-delay", seconds)
    }

    /**
     * The compressed formats sent untouched to the receiver, as mpv's
     * `audio-spdif` list (`ac3,eac3`), or null to decode everything here.
     */
    fun setAudioPassthrough(codecs: String?) {
        if (destroyed) return
        mpv.setPropertyString("audio-spdif", codecs.orEmpty())
    }

    // Live subtitle controls — the exact capability libVLC lacked on mobile:
    // these apply to the running core with no recreation.
    fun setSubtitleDelay(seconds: Double) {
        if (destroyed) return
        mpv.setPropertyDouble("sub-delay", seconds)
    }

    fun setSubtitleScale(scale: Double) {
        if (destroyed) return
        mpv.setPropertyDouble("sub-scale", scale)
    }

    fun setSubtitleFont(font: String?) {
        if (destroyed) return
        mpv.setPropertyString("sub-font", font ?: DefaultSubtitleFont)
    }

    /**
     * `scale` keeps a script's own fonts and positions but still applies the
     * size and the lift above the controls; `force` replaces its styling with
     * the player's. Not `no`: mpv ignores `sub-scale` and `sub-pos` for a
     * script under it, so the size slider did nothing on a styled track. An
     * mpv older than `scale` (0.37) rejects it and leaves the value as it was,
     * which the read-back catches; `yes` there still applies the lift.
     */
    fun setSubtitleTrackStyling(keepScript: Boolean) {
        if (destroyed) return
        if (videoPaths?.onTrackStyling(keepScript) == true) onSubtitleInputChanged()
        if (!keepScript) {
            mpv.setPropertyString("sub-ass-override", "force")
            return
        }
        mpv.setPropertyString("sub-ass-override", "scale")
        if (mpv.getPropertyString("sub-ass-override") != "scale") {
            mpv.setPropertyString("sub-ass-override", "yes")
        }
    }

    fun setSubtitleOutline(widthPixels: Double) {
        if (destroyed) return
        mpv.setPropertyDouble("sub-border-size", widthPixels)
    }

    fun setSubtitleShadow(offsetPixels: Double) {
        if (destroyed) return
        mpv.setPropertyDouble("sub-shadow-offset", offsetPixels)
    }

    /**
     * mpv counts `sub-pos` downwards from the top, so a lift is a subtraction.
     * 100 is the resting place at the bottom edge.
     */
    fun setSubtitleLift(percent: Int) {
        if (destroyed) return
        mpv.setPropertyInt("sub-pos", 100 - percent)
    }

    /**
     * `cached` rather than `select`: it re-selects a URL already added instead
     * of adding it a second time. Restoring a remembered subtitle and then
     * tapping the same row otherwise leaves two identical tracks in the list,
     * which is visible on device and cannot be undone from the UI.
     *
     * [title] names the track; without one mpv uses the file name, which for
     * a cached addon subtitle is a digest. See [subAddArguments].
     */
    fun addSubtitle(url: String, title: String?, lang: String?) {
        if (destroyed) return
        if (videoPaths != null) {
            videoPaths.onSubtitleAdded()
            onSubtitleInputChanged()
        }
        mpv.command(subAddArguments(url, title, lang).toTypedArray())
    }

    /**
     * Deterministic, idempotent teardown (mirrors the iOS core's contract):
     * detach observers first so no event races the native destroy, then
     * terminate. Safe to call twice.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        listener = null
        cancelSubtitleSettle()
        runCatching { mpv.removeObserver(eventObserver) }
        runCatching { mpv.removeLogObserver(logObserver) }
        runCatching { mpv.destroy() }
    }

    /**
     * The end of the file, or a lost connection that mpv took for one: when a
     * network stream drops and ffmpeg's reconnects give up, mpv plays out what
     * it had cached and reports the end. Treated as an end, that would raise
     * the next episode mid-film, so an end well short of the length is a
     * failure the error card can retry from the saved place.
     */
    private fun reportEnd() {
        val position = mpv.getPropertyDouble("time-pos")
        val duration = mpv.getPropertyDouble("duration")
        if (endedShortOfDuration(position, duration)) {
            listener?.onError(SourceLostMessage)
            return
        }
        listener?.onEnded()
    }

    private fun onFileLoaded() {
        val duration = mpv.getPropertyDouble("duration")?.takeIf { it.isFinite() && it >= 0.0 }
        listener?.onReady(duration)
        emitTracks()
        // Tracks are selected and the video chain is set up by now. A decoder
        // report that arrived before this event was skipped, and mpv will not
        // repeat an unchanged value, so the direct path is judged here too.
        videoPaths?.onFileLoaded()
        checkDirectVideo()
    }

    // --- Video path: direct (decoder onto the surface) or composed (mpv draws) ---

    private fun videoOutputFor(path: VideoPath): String = when (path) {
        VideoPath.Direct -> MpvProfile.DirectVo
        VideoPath.Composed -> profile.vo
    }

    private fun hwdecFor(path: VideoPath): String = when (path) {
        VideoPath.Direct -> MpvProfile.DirectHwdec
        VideoPath.Composed -> profile.hwdec
    }

    /** Every file starts direct; called just before `loadfile`. */
    private fun startFileOnDirectPath() {
        val paths = videoPaths ?: return
        cancelSubtitleSettle()
        listener?.onSubtitleText("")
        applyVideoPath(paths.onLoadRequested())
    }

    /**
     * When the direct path cannot show this file, it goes composed at once,
     * not debounced. Judged only where a judgement means something: on
     * the direct path, with a surface, with video wanted and the file's tracks
     * selected. The decoder and track state are read back here rather than
     * taken from the event that triggered this, because an event queued
     * before the last switch can describe the chain that switch replaced.
     */
    private fun checkDirectVideo() {
        val paths = videoPaths ?: return
        if (paths.path != VideoPath.Direct || !paths.fileLoaded || !surfaceAttached || !videoEnabled) return
        val hwdec = mpv.getPropertyString(HwdecCurrent)
        val failure = if (hwdec != null) {
            VideoPathPolicy.directFailure(hwdec, videoSelected = true, hasVideoTrack = true)
        } else {
            VideoPathPolicy.directFailure(
                hwdecCurrent = null,
                videoSelected = mpv.getPropertyString("vid").let { it != null && it != "no" },
                hasVideoTrack = hasVideoTrack(),
            )
        } ?: return
        if (!paths.onDirectFailed(failure)) return
        cancelSubtitleSettle()
        applyVideoPath(paths.decision())
    }

    private fun hasVideoTrack(): Boolean {
        val count = mpv.getPropertyInt("track-list/count") ?: return false
        return (0 until count).any { mpv.getPropertyString("track-list/$it/type") == "video" }
    }

    /**
     * A subtitle input changed (the selected track, or the styling setting).
     * The switch waits until the inputs have held still for the settle the
     * tracker names ([ProvisionalSubtitleSettleMs] for mpv's own pick,
     * [DeliberateSubtitleSettleMs] otherwise); any further change restarts the
     * wait, and a change back to what the current path already suits simply
     * cancels it. Every subtitle call from the app restarts it as well, so a
     * wait timed for the selection before that call never fires early.
     */
    private fun onSubtitleInputChanged() {
        val paths = videoPaths ?: return
        val handler = settleHandler ?: return
        cancelSubtitleSettle()
        if (!paths.wantsSwitch()) return
        val delayMs = when (paths.settle()) {
            SubtitleSettle.Provisional -> ProvisionalSubtitleSettleMs
            SubtitleSettle.Deliberate -> DeliberateSubtitleSettleMs
        }
        val token = settleToken
        val task = Runnable { dispatch { onSubtitleSettled(token) } }
        settleTask = task
        handler.postDelayed(task, delayMs)
    }

    private fun onSubtitleSettled(token: Long) {
        if (destroyed || token != settleToken) return
        settleTask = null
        val paths = videoPaths ?: return
        applyVideoPath(paths.decision())
    }

    /** Safe from any executor task; the token outlives a removal that came too late. */
    private fun cancelSubtitleSettle() {
        settleToken += 1
        val task = settleTask ?: return
        settleTask = null
        settleHandler?.removeCallbacks(task)
    }

    /**
     * [switchVideoPath], with a failure logged rather than thrown: the callers
     * are mpv callbacks and the debounce, and a load must still go ahead.
     */
    private fun applyVideoPath(decision: VideoPathDecision) {
        runCatching { switchVideoPath(decision) }
            .onFailure { Log.w(LOG_TAG, "[$id] video path switch to ${decision.describe()} failed", it) }
    }

    /**
     * Moves to [decision]'s path, in one executor task. Without a surface it
     * only records the path, which [attachSurface] then uses.
     *
     * The order is what keeps one producer on the surface. `vid=no` destroys
     * the decoder synchronously, which releases a MediaCodec rendering onto
     * the surface. Setting `vo` then uninitialises the old output before the
     * new one opens, which releases the GPU output's EGL surface (kept alive
     * without video by `force-window`). `hwdec` is set while no decoder
     * exists, so it applies to the next one rather than reinitialising this
     * one, and `vid=auto` creates that decoder last, under the new output. mpv
     * keeps the playback position across the `vid` cycle and seeks the new
     * decoder to it itself.
     */
    private fun switchVideoPath(decision: VideoPathDecision) {
        val paths = videoPaths ?: return
        if (decision.path == paths.path) return
        paths.adopt(decision.path)
        val pending = if (surfaceAttached) "" else ", from the next surface"
        Log.i(LOG_TAG, "[$id] video path: ${decision.describe()}$pending")
        if (decision.path == VideoPath.Composed) listener?.onSubtitleText("")
        if (surfaceAttached) {
            if (videoEnabled) mpv.setPropertyString("vid", "no")
            mpv.setOptionString("hwdec", hwdecFor(decision.path))
            mpv.setOptionString("vo", videoOutputFor(decision.path))
            // The view filled the box only while composed; the GPU output
            // needs the size of the surface as it is now.
            if (decision.path == VideoPath.Composed && surfaceWidth > 0 && surfaceHeight > 0) {
                mpv.setPropertyString("android-surface-size", "${surfaceWidth}x$surfaceHeight")
            }
            if (videoEnabled) mpv.setPropertyString("vid", "auto")
        }
        listener?.onVideoPath(decision.path == VideoPath.Direct)
        if (decision.path == VideoPath.Direct && surfaceAttached) {
            onSubtitleText(mpv.getPropertyString(SubText).orEmpty())
        }
    }

    /** Forwards `sub-text` while the app draws subtitles, which is only on the direct path with a surface. */
    private fun onSubtitleText(text: String) {
        val paths = videoPaths ?: return
        if (paths.path != VideoPath.Direct || !surfaceAttached) return
        listener?.onSubtitleText(text)
    }

    /**
     * Reports the stall, reading the rate and depth only while one is happening.
     *
     * `paused-for-cache` is the gate rather than `cache-buffering-state`,
     * because the latter sits below 100 through perfectly healthy streaming and
     * would flash the overlay over a picture that never stopped moving. The
     * two figures are read here instead of observed: they only matter while the
     * overlay is up, and mpv revises the fill percentage as it refills, so this
     * runs again for each revision.
     */
    private fun emitBuffering() {
        if (destroyed) return
        if (!pausedForCache) {
            listener?.onBuffering(null)
            return
        }
        listener?.onBuffering(
            PlayerBuffering(
                percent = cacheFillPercent,
                // mpv types cache-speed as int64; the JNI hands back an Int,
                // which cannot hold a rate a phone will ever see anyway.
                bytesPerSecond = mpv.getPropertyInt("cache-speed")?.toLong(),
                cachedSeconds = mpv.getPropertyDouble("demuxer-cache-duration"),
            ),
        )
    }

    private fun emitTracks() {
        if (destroyed) return
        val count = mpv.getPropertyInt("track-list/count") ?: return
        val audio = mutableListOf<PlayerTrack>()
        val subs = mutableListOf<PlayerTrack>()
        var selectedAudio: String? = null
        var selectedSub: String? = null
        var selectedSubCodec: String? = null
        for (i in 0 until count) {
            val type = mpv.getPropertyString("track-list/$i/type") ?: continue
            val trackId = mpv.getPropertyInt("track-list/$i/id")?.toString() ?: continue
            val lang = mpv.getPropertyString("track-list/$i/lang")
            val title = mpv.getPropertyString("track-list/$i/title")
            val selected = mpv.getPropertyBoolean("track-list/$i/selected") ?: false
            val codec = mpv.getPropertyString("track-list/$i/codec")
            val channels = mpv.getPropertyInt("track-list/$i/demux-channel-count")
            val sampleRateHz = mpv.getPropertyInt("track-list/$i/demux-samplerate")
            val external = mpv.getPropertyBoolean("track-list/$i/external") ?: false
            val label = title ?: lang ?: "$type $trackId"
            when (type) {
                "audio" -> {
                    audio += PlayerTrack(trackId, label, lang, codec, channels, sampleRateHz, external)
                    if (selected) selectedAudio = trackId
                }
                "sub" -> {
                    subs += PlayerTrack(trackId, label, lang, codec = codec, external = external)
                    if (selected) {
                        selectedSub = trackId
                        selectedSubCodec = codec
                    }
                }
            }
        }
        if (videoPaths?.onSubtitleSelected(selected = selectedSub != null, codec = selectedSubCodec) == true) {
            onSubtitleInputChanged()
        }
        listener?.onTracks(
            PlayerTracks(
                audio = audio,
                subtitles = subs,
                selectedAudioId = selectedAudio,
                selectedSubtitleId = selectedSub,
            ),
        )
    }

    companion object {
        const val LOG_TAG = "HALO_MPV"

        /** What "no particular font" means to the renderer. */
        private const val DefaultSubtitleFont = "sans-serif"

        /** Create + configure + initialize a fresh core with a stable [id]. */
        fun create(context: Context, id: String, profile: MpvProfile, callbackExecutor: Executor? = null): MpvCore {
            // The direct path switches outputs from mpv callbacks and a timer,
            // which must run on the caller's executor, never on mpv's thread.
            require(!profile.directVideo || callbackExecutor != null) { "direct video needs a callback executor" }
            val mpv = MPVLib.create(context) ?: error("MPVLib.create returned null")
            // Pre-init options (mpv-android's proven Android render/decode stack).
            mpv.setOptionString("config", "no")
            mpv.setOptionString("vo", "null") // no output until a surface attaches
            mpv.setOptionString("gpu-context", "android")
            mpv.setOptionString("opengl-es", "yes")
            mpv.setOptionString("hwdec", profile.hwdec) // falls back to software
            profile.demuxerMaxBytes?.let { mpv.setOptionString("demuxer-max-bytes", it) }
            profile.demuxerMaxBackBytes?.let { mpv.setOptionString("demuxer-max-back-bytes", it) }
            // The profile first, so the options after it override what it sets.
            profile.renderProfile?.let { mpv.setOptionString("profile", it) }
            if (!profile.hdrComputePeak) mpv.setOptionString("hdr-compute-peak", "no")
            profile.eglOutputFormat?.let { mpv.setOptionString("egl-output-format", it) }
            mpv.setOptionString("ao", "audiotrack")
            mpv.setOptionString("keep-open", "yes") // so eof-reached fires
            // mpv's default is to hand a URL it could not open to youtube-dl.
            // There is no such binary on Android and never will be, so every
            // failed open would otherwise spawn a subprocess, fail, and bury the
            // real diagnostic under its own error. The error card prints the
            // engine's message verbatim, so that noise is not free.
            mpv.setOptionString("ytdl", "no")
            // Subtitle auto-select + rendering; embedded fonts make ASS render
            // without depending on Android system fonts.
            mpv.setOptionString("slang", "eng,en")
            mpv.setOptionString("subs-fallback", "yes")
            mpv.setOptionString("embeddedfonts", "yes")
            // Fonts the app ships, so a chosen family resolves to that family
            // rather than to whichever system face fontconfig settles on.
            // Pre-init: libass builds its font provider during initialisation.
            SubtitleFontLibrary.prepare(context)?.let { mpv.setOptionString("sub-fonts-dir", it) }

            val core = MpvCore(mpv, id, callbackExecutor, profile)
            mpv.addLogObserver(core.logObserver)
            mpv.addObserver(core.eventObserver)
            mpv.init()

            // Observe the neutral property set the boundary needs.
            mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            // Selection, which the count cannot report. Same pair the iOS core
            // observes, so both platforms answer "which track is playing" from
            // the engine rather than from what the UI last asked for.
            mpv.observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_NONE)
            mpv.observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_NONE)
            mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("cache-buffering-state", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            mpv.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            // The decoded picture, for the quality medal: the same four
            // properties the desktop player reads.
            mpv.observeProperty(VideoWidth, MPVLib.MpvFormat.MPV_FORMAT_INT64)
            mpv.observeProperty(VideoHeight, MPVLib.MpvFormat.MPV_FORMAT_INT64)
            mpv.observeProperty(VideoGamma, MPVLib.MpvFormat.MPV_FORMAT_STRING)
            mpv.observeProperty(VideoColorMatrix, MPVLib.MpvFormat.MPV_FORMAT_STRING)
            // The frame rate, for matching the display's refresh rate on a TV.
            mpv.observeProperty(ContainerFps, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty(EstimatedFps, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            if (profile.directVideo) {
                // Whether the direct path took: the decoder in use, and the
                // video track mpv drops when its chain fails.
                mpv.observeProperty(HwdecCurrent, MPVLib.MpvFormat.MPV_FORMAT_STRING)
                mpv.observeProperty("vid", MPVLib.MpvFormat.MPV_FORMAT_NONE)
                // The cue the app draws on the direct path, where mpv draws none.
                mpv.observeProperty(SubText, MPVLib.MpvFormat.MPV_FORMAT_STRING)
            }
            return core
        }
    }
}

/**
 * Attributes mpv file-ending events when the Android binding exposes only the
 * event id, not `mpv_end_file_reason`.
 *
 * `loadfile` ends the old file before starting its replacement. That expected
 * END_FILE must not fail the new item. The counter, rather than a Boolean,
 * also handles two rapid replacements before mpv's event thread catches up.
 */
internal class MpvFileLifecycle {
    private val lock = Any()
    private var fileActive = false
    private var expectedReplacementEndFiles = 0
    private var lastErrorMessage: String? = null
    private var eofReached = false

    fun onLoadRequested() = synchronized(lock) {
        if (fileActive) expectedReplacementEndFiles += 1
        fileActive = true
        lastErrorMessage = null
        eofReached = false
    }

    fun onStopRequested() = synchronized(lock) {
        if (!fileActive) return@synchronized
        expectedReplacementEndFiles += 1
        fileActive = false
    }

    /**
     * START_FILE separates diagnostics from the replaced connection and the
     * new one. Harmless TLS close noise from the old stream must not be shown
     * if the new stream later has a genuine failure.
     */
    fun onFileStarted() = synchronized(lock) {
        lastErrorMessage = null
        eofReached = false
    }

    /** Keeps the first useful diagnostic because later hooks are often generic. */
    fun recordError(message: String) = synchronized(lock) {
        if (lastErrorMessage == null) lastErrorMessage = message
    }

    /** True only for the first EOF observation for the current file. */
    fun markEofReached(): Boolean = synchronized(lock) {
        if (eofReached) return@synchronized false
        eofReached = true
        true
    }

    /** True when the current file had reached its end and now has not. */
    fun clearEofReached(): Boolean = synchronized(lock) {
        if (!eofReached) return@synchronized false
        eofReached = false
        true
    }

    /** A real current-file failure, or null for replacement and natural endings. */
    fun endFileFailure(): String? = synchronized(lock) {
        if (expectedReplacementEndFiles > 0) {
            expectedReplacementEndFiles -= 1
            return@synchronized null
        }
        fileActive = false
        if (eofReached) return@synchronized null
        lastErrorMessage ?: "Playback ended unexpectedly."
    }
}

/**
 * True when playback stopped well before the file's stated length. The
 * allowance covers files whose length is stated a little long (audio that
 * outlasts the picture, rounding in the container), scaled for long films.
 * Without a usable length there is nothing to compare, so it is an end.
 */
internal fun endedShortOfDuration(positionSeconds: Double?, durationSeconds: Double?): Boolean {
    if (positionSeconds == null || durationSeconds == null) return false
    if (!positionSeconds.isFinite() || !durationSeconds.isFinite() || durationSeconds <= 0.0) return false
    val allowance = maxOf(EndAllowanceSeconds, durationSeconds * EndAllowanceFraction)
    return durationSeconds - positionSeconds > allowance
}

private const val EndAllowanceSeconds = 30.0
private const val EndAllowanceFraction = 0.02
private const val SourceLostMessage = "The connection to this source was lost."

private const val VideoWidth = "video-params/dw"
private const val VideoHeight = "video-params/dh"
private const val VideoGamma = "video-params/gamma"
private const val VideoColorMatrix = "video-params/colormatrix"
private const val ContainerFps = "container-fps"
private const val EstimatedFps = "estimated-vf-fps"
private const val HwdecCurrent = "hwdec-current"
private const val SubText = "sub-text"

/**
 * How long a subtitle track mpv selected on its own for a fresh file must
 * hold before it may switch the video path. A switch rebuilds the decoder and
 * re-seeks, and at file start mpv often picks an embedded picture track that
 * the app then replaces with its own choice. Measured on a TV box, that choice
 * arrived 2.4 s after the file loaded; it waits on a subtitle fetched over the
 * network, so the gap varies, and this is set well past it. It is only the
 * fallback for a file whose default picture subtitle really is what the
 * viewer gets: the app's choice normally ends the wait first.
 */
private const val ProvisionalSubtitleSettleMs = 6_000L

/**
 * How long a deliberate selection (the app's or the viewer's) or a styling
 * change must hold before it may switch the video path. It only absorbs the
 * track-list churn of one choice. An added subtitle counts as chosen only once
 * track-list reports a new selection: until then the long settle applies,
 * because measured on a TV box the added track became selected 1.2 s after
 * `sub-add` while the file was still opening.
 */
private const val DeliberateSubtitleSettleMs = 500L
