package moe.ditto.halo.player

import android.content.Context
import android.util.Log
import android.view.Surface
import dev.jdtech.mpv.MPVLib

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
) {
    interface Listener {
        fun onReady(durationSeconds: Double?)
        fun onPosition(positionSeconds: Double)
        fun onPauseChanged(paused: Boolean)
        fun onTracks(tracks: PlayerTracks)
        fun onEnded()
        fun onError(message: String)
    }

    @Volatile private var listener: Listener? = null
    @Volatile private var destroyed = false
    // mpv fires time-pos many times per second; forward only whole-second
    // changes. The shell displays seconds, and flooding recompositions would
    // otherwise starve Compose's idle sync (and waste work).
    @Volatile private var lastPositionSecond = Long.MIN_VALUE

    private val eventObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) { /* NODE/none formats: ignored */ }

        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "track-list/count" -> emitTracks()
            }
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> {
                    val second = value.toLong()
                    if (second != lastPositionSecond) {
                        lastPositionSecond = second
                        listener?.onPosition(value)
                    }
                }
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> listener?.onPauseChanged(value)
                "eof-reached" -> if (value) listener?.onEnded()
            }
        }

        override fun eventProperty(property: String, value: String) { /* unused */ }

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> onFileLoaded()
                MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> listener?.onEnded()
            }
        }
    }

    // MPVLib.LogObserver is a plain Kotlin interface (not `fun interface`), so no
    // SAM lambda — an explicit object is required.
    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            // Primary diagnostic channel: mpv's own log lines land in logcat under
            // a greppable tag (Configuration/vo/hwdec/subtitle-track selection).
            Log.i(LOG_TAG, "[$id][$prefix] ${text.trimEnd()}")
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Attach the render surface and turn the GPU video output on (mpv-android order). */
    fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (destroyed) return
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("android-surface-size", "${width}x$height")
        mpv.setOptionString("vo", VO)
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (destroyed || width <= 0 || height <= 0) return
        mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    /** Turn video output off before releasing the surface (mpv-android order). */
    fun detachSurface() {
        if (destroyed) return
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    fun load(url: String) {
        if (destroyed) return
        mpv.command(arrayOf("loadfile", url))
    }

    fun stop() {
        if (destroyed) return
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

    fun selectAudioTrack(id: String?) {
        if (destroyed) return
        mpv.setPropertyString("aid", id ?: "no")
    }

    fun selectSubtitleTrack(id: String?) {
        if (destroyed) return
        mpv.setPropertyString("sid", id ?: "no")
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
        mpv.setPropertyString("sub-font", font ?: "sans-serif")
    }

    fun addSubtitle(url: String) {
        if (destroyed) return
        mpv.command(arrayOf("sub-add", url, "select"))
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
        runCatching { mpv.removeObserver(eventObserver) }
        runCatching { mpv.removeLogObserver(logObserver) }
        runCatching { mpv.destroy() }
    }

    private fun onFileLoaded() {
        val duration = mpv.getPropertyDouble("duration")?.takeIf { it.isFinite() && it >= 0.0 }
        listener?.onReady(duration)
        emitTracks()
    }

    private fun emitTracks() {
        if (destroyed) return
        val count = mpv.getPropertyInt("track-list/count") ?: return
        val audio = mutableListOf<PlayerTrack>()
        val subs = mutableListOf<PlayerTrack>()
        var selectedAudio: String? = null
        var selectedSub: String? = null
        for (i in 0 until count) {
            val type = mpv.getPropertyString("track-list/$i/type") ?: continue
            val trackId = mpv.getPropertyInt("track-list/$i/id")?.toString() ?: continue
            val lang = mpv.getPropertyString("track-list/$i/lang")
            val title = mpv.getPropertyString("track-list/$i/title")
            val selected = mpv.getPropertyBoolean("track-list/$i/selected") ?: false
            val label = title ?: lang ?: "$type $trackId"
            when (type) {
                "audio" -> {
                    audio += PlayerTrack(trackId, label, lang)
                    if (selected) selectedAudio = trackId
                }
                "sub" -> {
                    subs += PlayerTrack(trackId, label, lang)
                    if (selected) selectedSub = trackId
                }
            }
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
        private const val VO = "gpu"

        /** Create + configure + initialize a fresh core with a stable [id]. */
        fun create(context: Context, id: String): MpvCore {
            val mpv = MPVLib.create(context) ?: error("MPVLib.create returned null")
            // Pre-init options (mpv-android's proven Android render/decode stack).
            mpv.setOptionString("config", "no")
            mpv.setOptionString("vo", "null") // no output until a surface attaches
            mpv.setOptionString("gpu-context", "android")
            mpv.setOptionString("opengl-es", "yes")
            mpv.setOptionString("hwdec", "mediacodec-copy") // auto-falls back to sw
            mpv.setOptionString("ao", "audiotrack")
            mpv.setOptionString("mute", "yes") // test playback is always silent
            mpv.setOptionString("keep-open", "yes") // so eof-reached fires
            // Subtitle auto-select + rendering; embedded fonts make ASS render
            // without depending on Android system fonts.
            mpv.setOptionString("slang", "eng,en")
            mpv.setOptionString("subs-fallback", "yes")
            mpv.setOptionString("embeddedfonts", "yes")

            val core = MpvCore(mpv, id)
            mpv.addLogObserver(core.logObserver)
            mpv.addObserver(core.eventObserver)
            mpv.init()

            // Observe the neutral property set the boundary needs.
            mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            return core
        }
    }
}
