package moe.ditto.halo.player

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as Media3Item

/**
 * The TV's second engine: one Media3 player at a time, for the files the TV
 * hands it (see the TV's engine router), behind the same [PlayerPort]
 * contract and [PlayerEvent]s as the mpv host, so the session, the controls
 * and everything else work on it unchanged.
 *
 * Threading: everything here runs on the main thread. Media3 calls its
 * listeners on the looper the player was built on, which is the main one,
 * and every public call moves there first ([onMain]). The [loadGeneration]
 * goes up with every load, stop and release; each player's listener carries
 * the generation it was made for and does nothing once it is stale, so a
 * late callback from a released player never reaches a newer file.
 *
 * Ownership: [load] builds the file's player after releasing the previous
 * one; [teardown] and [close] release it. [release] blocks until Media3 has
 * let go of its decoders (bounded by Media3's own release timeout), which is
 * what lets the router start the other engine right after.
 */
@OptIn(UnstableApi::class)
internal class Media3PlayerHost(private val appContext: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val channel = Channel<PlayerEvent>(Channel.UNLIMITED)
    val playerEvents: Flow<PlayerEvent> = channel.receiveAsFlow()

    // What the surface shows: always the app-sized, app-captioned way, since
    // Media3 draws nothing over the picture either.
    private val frame = MutableStateFlow(DirectVideoFrame(direct = true))
    private val subtitles = MutableStateFlow(AppSubtitleState())
    val videoFrame: StateFlow<DirectVideoFrame> = frame.asStateFlow()
    val subtitleOverlay: StateFlow<AppSubtitleState> = subtitles.asStateFlow()

    private var player: ExoPlayer? = null
    private var file: FileState? = null
    private var surfaceView: SurfaceView? = null
    private var closed = false

    /** Goes up with every load, stop and release; see the class comment. */
    var loadGeneration = 0L
        private set

    // Settings that outlive a file, as mpv keeps its core properties.
    private var preferredAudioLanguages: List<String> = emptyList()
    private var playbackRate = 1.0

    // This device's Dolby Vision decoders, read once on first use.
    private val dolbyVisionDecoders: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(DolbyVisionMime, ignoreCase = true) } }
                .mapTo(HashSet()) { it.name }
        }.getOrDefault(emptySet())
    }

    /** One file's player and what has been reported about it. */
    private class FileState(
        val generation: Long,
        val item: MediaItem,
        var media: Media3Item,
        val sideloaded: MutableMap<String, Media3Item.SubtitleConfiguration> = LinkedHashMap(),
    ) {
        var ready = false
        var ended = false
        var buffering = false
        var firstFrame = false
        var lastSecond = Long.MIN_VALUE
        var lastBufferedSecond = Long.MIN_VALUE
        var videoDecoder: String? = null
        var format = VideoFormat()
        var pendingSubtitleId: String? = null
        var failed = false
        var hasVideo = false
        var firstReadyAt = 0L
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    // --- The contract, through [Media3PlayerPort] ---

    suspend fun load(item: MediaItem) = onMain {
        if (closed) return@onMain
        releasePlayer()
        val generation = ++loadGeneration
        val media = Media3Item.fromUri(item.url)
        val state = FileState(generation, item, media)
        file = state
        updateFormat(state, VideoFormat(), always = true)
        subtitles.update { it.copy(text = "") }
        val built = buildPlayer(item.headers)
        player = built
        val listener = FileListener(state)
        built.addListener(listener)
        built.addAnalyticsListener(FileAnalytics(state))
        built.setVideoFrameMetadataListener(OutputLog(generation))
        built.trackSelectionParameters = built.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray())
            // mpv's own pick before the app chooses: an English track if any.
            .setPreferredTextLanguages(*DefaultTextLanguages)
            .build()
        built.setPlaybackSpeed(playbackRate.toFloat())
        surfaceView?.let { built.setVideoSurfaceView(it) }
        Log.i(LogTag, "second engine: opening ${hostOf(item.url)} with ${item.headers.size} request headers")
        built.setMediaItem(media)
        built.prepare()
        scheduleTick(generation)
    }

    suspend fun setPaused(paused: Boolean) = onMain { player?.playWhenReady = !paused }

    suspend fun seekTo(positionSeconds: Double) = onMain<Unit> {
        player?.seekTo((positionSeconds * 1000).toLong().coerceAtLeast(0L))
    }

    suspend fun setPlaybackRate(rate: Double) = onMain<Unit> {
        playbackRate = rate
        player?.setPlaybackSpeed(rate.toFloat())
    }

    suspend fun setPreferredAudioLanguages(codes: List<String>) = onMain {
        preferredAudioLanguages = codes
        val current = player ?: return@onMain
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*codes.toTypedArray())
            .build()
    }

    /** [id] as [PlayerTrack.id] reported it; null turns the sound off, as mpv's `aid=no`. */
    suspend fun selectAudioTrack(id: String?) = onMain { selectTrack(C.TRACK_TYPE_AUDIO, id) }

    /** [id] as [PlayerTrack.id] reported it; null turns subtitles off. Only listed (showable) tracks have ids. */
    suspend fun selectSubtitleTrack(id: String?) = onMain {
        file?.pendingSubtitleId = null
        selectTrack(C.TRACK_TYPE_TEXT, id)
        if (id == null) subtitles.update { it.copy(text = "") }
    }

    suspend fun setVideoFillsScreen(fills: Boolean) = onMain { frame.update { it.copy(fillsScreen = fills) } }

    // The caption's look, applied by the app's own overlay exactly as on
    // mpv's direct path; Media3 only supplies the cue text.
    suspend fun setSubtitleScale(scale: Double) = onMain { subtitles.update { it.copy(scale = scale) } }
    suspend fun setSubtitleFont(font: String?) = onMain { subtitles.update { it.copy(font = font) } }
    suspend fun setSubtitleOutline(widthPixels: Double) = onMain { subtitles.update { it.copy(outlineUnits = widthPixels) } }
    suspend fun setSubtitleShadow(offsetPixels: Double) = onMain { subtitles.update { it.copy(shadowUnits = offsetPixels) } }
    suspend fun setSubtitleLift(percent: Int) = onMain { subtitles.update { it.copy(position = 100 - percent) } }

    /**
     * A subtitle file beside the stream, selected once it is read. Media3
     * takes such files only with the media item, so the item is set again
     * with it, keeping the place; that re-reads the stream from there. The
     * same file added again is only selected, as mpv's `cached` mode does.
     */
    suspend fun addSubtitle(url: String, title: String?, lang: String?) = onMain {
        val state = file ?: return@onMain
        val current = player ?: return@onMain
        val existing = state.sideloaded[url]
        if (existing != null) {
            state.pendingSubtitleId = existing.id
            selectPendingSubtitle(state, current.currentTracks)
            return@onMain
        }
        val mime = Media3Mapping.sideloadedSubtitleMime(url) ?: run {
            Log.i(LogTag, "second engine: subtitle file type not read here, not added")
            return@onMain
        }
        val id = "sub-${state.sideloaded.size + 1}"
        val uri = if (url.contains("://")) Uri.parse(url) else Uri.fromFile(File(url))
        val config = Media3Item.SubtitleConfiguration.Builder(uri)
            .setId(id)
            .setMimeType(mime)
            .setLabel(title)
            .setLanguage(lang)
            .build()
        state.sideloaded[url] = config
        state.pendingSubtitleId = id
        state.media = state.media.buildUpon().setSubtitleConfigurations(state.sideloaded.values.toList()).build()
        current.setMediaItem(state.media, /* resetPosition= */ false)
        current.prepare()
    }

    /**
     * Before the surface goes: stops the file, which disables the renderers
     * and so frees the decoders. The next [load] builds a new player.
     */
    suspend fun releaseVideoOutput() = onMain {
        val current = player ?: return@onMain
        loadGeneration += 1
        current.stop()
    }

    suspend fun teardown() = onMain { releasePlayer() }

    /** Activity shutdown: releases the player and refuses everything after. */
    fun close() {
        main.post {
            closed = true
            releasePlayer()
            surfaceView = null
        }
    }

    // --- The surface, owned here so it outlives recomposition, as mpv's is ---

    fun composeSurface(): SurfaceView {
        surfaceView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            return view
        }
        val view = SurfaceView(appContext)
        surfaceView = view
        player?.setVideoSurfaceView(view)
        return view
    }

    // --- Inside ---

    private fun buildPlayer(headers: Map<String, String>): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        // Files on the device too: the app's cached addon subtitles.
        val data = DefaultDataSource.Factory(appContext, http)
        val renderers = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    // Nothing compressed goes to the output: every track is
                    // decoded here, to as many channels as its decoder gives.
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .build()
        }
        return ExoPlayer.Builder(appContext, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .setBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(appContext))
            .setLooper(Looper.getMainLooper())
            .build()
    }

    private fun releasePlayer() {
        val current = player ?: return
        player = null
        file = null
        loadGeneration += 1
        main.removeCallbacksAndMessages(TickToken)
        current.clearVideoSurface()
        // Blocks until the decoders are let go, within Media3's own bound.
        current.release()
        subtitles.update { it.copy(text = "") }
        frame.update { it.copy(videoWidth = 0, videoHeight = 0) }
        Log.i(LogTag, "second engine: released")
    }

    private fun current(state: FileState): Boolean = file === state && state.generation == loadGeneration && !closed

    private fun emit(state: FileState, event: PlayerEvent) {
        if (!current(state)) return
        channel.trySend(event)
    }

    private fun updateFormat(state: FileState, next: VideoFormat, always: Boolean = false) {
        if (next == state.format && !always) return
        state.format = next
        frame.update { it.copy(videoWidth = next.width, videoHeight = next.height) }
        emit(state, PlayerEvent.VideoFormatChanged(next))
    }

    private fun selectTrack(type: Int, id: String?) {
        val current = player ?: return
        val builder = current.trackSelectionParameters.buildUpon()
        if (id == null) {
            current.trackSelectionParameters = builder.setTrackTypeDisabled(type, true).build()
            return
        }
        val target = findTrack(current.currentTracks, type, id) ?: return
        current.trackSelectionParameters = builder
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(target.first.mediaTrackGroup, target.second))
            .build()
    }

    private fun findTrack(tracks: Tracks, type: Int, id: String): Pair<Tracks.Group, Int>? {
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (index in 0 until group.length) {
                if (trackId(group.getTrackFormat(index), groupIndex, index) == id) return group to index
            }
        }
        return null
    }

    private fun trackId(format: Format, groupIndex: Int, index: Int): String = format.id ?: "g$groupIndex-$index"

    private fun selectPendingSubtitle(state: FileState, tracks: Tracks) {
        val id = state.pendingSubtitleId ?: return
        val target = findTrack(tracks, C.TRACK_TYPE_TEXT, id) ?: return
        state.pendingSubtitleId = null
        val current = player ?: return
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(target.first.mediaTrackGroup, target.second))
            .build()
    }

    /** The tracks as the contract lists them: every audio track, and the subtitles the app can show. */
    private fun reportTracks(state: FileState, tracks: Tracks) {
        val audio = mutableListOf<PlayerTrack>()
        val subs = mutableListOf<PlayerTrack>()
        var selectedAudio: String? = null
        var selectedSub: String? = null
        var unshowableSelected = false
        tracks.groups.forEachIndexed { groupIndex, group ->
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val id = trackId(format, groupIndex, index)
                val selected = group.isTrackSelected(index)
                val lang = format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> {
                        audio += PlayerTrack(
                            id = id,
                            label = format.label ?: lang ?: "audio $id",
                            language = lang,
                            codec = Media3Mapping.audioCodec(format.sampleMimeType),
                            channels = format.channelCount.takeIf { it > 0 },
                            sampleRateHz = format.sampleRate.takeIf { it > 0 },
                        )
                        if (selected) selectedAudio = id
                    }
                    C.TRACK_TYPE_TEXT -> {
                        val mime = Media3Mapping.subtitleMime(format.sampleMimeType, format.codecs)
                        if (!Media3Mapping.subtitleShowable(mime)) {
                            if (selected) unshowableSelected = true
                            continue
                        }
                        subs += PlayerTrack(
                            id = id,
                            label = format.label ?: lang ?: "sub $id",
                            language = lang,
                            codec = Media3Mapping.subtitleCodec(mime),
                            external = state.sideloaded.values.any { it.id == id },
                        )
                        if (selected) selectedSub = id
                    }
                }
            }
        }
        // A picture subtitle the file marks as default would show nothing
        // here; it is not listed, so it is not left selected either.
        if (unshowableSelected && selectedSub == null) {
            player?.let { current ->
                current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }
        emit(state, PlayerEvent.TracksChanged(PlayerTracks(audio, subs, selectedAudio, selectedSub)))
    }

    private fun checkVideoTrack(state: FileState, tracks: Tracks) {
        val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        if (video.isEmpty()) return
        if (video.any { group -> (0 until group.length).any { group.isTrackSupported(it, /* allowExceedsCapabilities= */ true) } }) return
        fail(state, "second engine: no decoder here takes this picture", PlaybackFailure.UnsupportedVideo)
    }

    private fun fail(state: FileState, message: String, failure: PlaybackFailure) {
        if (state.failed || !current(state)) return
        state.failed = true
        Log.i(LogTag, "$message; ${if (failure == PlaybackFailure.UnsupportedVideo) "the picture cannot play here" else "source error"}")
        emit(state, PlayerEvent.BufferingChanged(active = false))
        emit(state, PlayerEvent.Error(message.removePrefix("second engine: "), failure))
    }

    private fun scheduleTick(generation: Long) {
        main.postAtTime({ tick(generation) }, TickToken, SystemClock.uptimeMillis() + TickMs)
    }

    /** Twice a second: the position and the buffered reach in whole seconds, and the stall's figures while one lasts. */
    private fun tick(generation: Long) {
        val state = file ?: return
        val current = player ?: return
        if (state.generation != generation || !current(state)) return
        val position = current.currentPosition
        val second = position / 1000
        if (second != state.lastSecond) {
            state.lastSecond = second
            emit(state, PlayerEvent.PositionChanged(position / 1000.0))
        }
        val buffered = current.bufferedPosition
        val bufferedSecond = buffered / 1000
        if (bufferedSecond != state.lastBufferedSecond) {
            state.lastBufferedSecond = bufferedSecond
            emit(state, PlayerEvent.BufferedPositionChanged(buffered / 1000.0))
        }
        if (state.buffering) emitBuffering(state, current)
        if (state.hasVideo && !state.firstFrame && state.ready && current.playWhenReady && current.playbackState == Player.STATE_READY) {
            if (SystemClock.uptimeMillis() - state.firstReadyAt > NoPictureTimeoutMs) {
                fail(state, "second engine: no picture came from the decoder", PlaybackFailure.UnsupportedVideo)
            }
        }
        scheduleTick(generation)
    }

    private fun emitBuffering(state: FileState, current: ExoPlayer) {
        val aheadMs = (current.bufferedPosition - current.currentPosition).coerceAtLeast(0L)
        val bitsPerSecond = DefaultBandwidthMeter.getSingletonInstance(appContext).bitrateEstimate
        emit(
            state,
            PlayerEvent.BufferingChanged(
                active = true,
                // Against what playback waits for after a stall before it goes on.
                percent = ((aheadMs * 100) / DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).toInt().coerceIn(0, 100),
                bytesPerSecond = bitsPerSecond.takeIf { it > 0 }?.div(8),
                cachedSeconds = aheadMs / 1000.0,
            ),
        )
    }

    private fun takesDolbyVision(decoderName: String): Boolean = decoderName in dolbyVisionDecoders

    /** One file's callbacks; every one checks the file is still current. */
    private inner class FileListener(private val state: FileState) : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!current(state)) return
            val current = player ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    if (!state.ready) {
                        state.ready = true
                        state.firstReadyAt = SystemClock.uptimeMillis()
                        val duration = current.duration.takeIf { it != C.TIME_UNSET && it >= 0 }?.div(1000.0)
                        emit(state, PlayerEvent.Ready(duration))
                    }
                    if (state.buffering) {
                        state.buffering = false
                        emit(state, PlayerEvent.BufferingChanged(active = false))
                    }
                    if (state.ended) {
                        state.ended = false
                        emit(state, PlayerEvent.EndCleared)
                    }
                }
                Player.STATE_BUFFERING -> {
                    if (state.ended) {
                        state.ended = false
                        emit(state, PlayerEvent.EndCleared)
                    }
                    // A stall only once playing has begun; the first fill is the load.
                    if (state.ready && !state.buffering) {
                        state.buffering = true
                        emitBuffering(state, current)
                    }
                }
                Player.STATE_ENDED -> {
                    if (state.ended) return
                    state.ended = true
                    state.buffering = false
                    emit(state, PlayerEvent.BufferingChanged(active = false))
                    val position = current.currentPosition / 1000.0
                    val duration = current.duration.takeIf { it != C.TIME_UNSET }?.div(1000.0)
                    // As mpv's end: a lost connection that reads as the end is a failure.
                    if (endedShortOfDuration(position, duration)) {
                        emit(state, PlayerEvent.Error(SourceLostMessage))
                    } else {
                        emit(state, PlayerEvent.NaturalEnd)
                    }
                }
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            emit(state, PlayerEvent.PauseChanged(!playWhenReady))
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (!current(state)) return
            state.hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            checkVideoTrack(state, tracks)
            selectPendingSubtitle(state, tracks)
            reportTracks(state, tracks)
        }

        override fun onCues(cueGroup: CueGroup) {
            if (!current(state)) return
            // Text only: a picture cue has no text, and such tracks are not offered.
            val text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf { line -> line.isNotEmpty() } }.joinToString("\n")
            subtitles.update { it.copy(text = text) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (!current(state) || videoSize.width <= 0 || videoSize.height <= 0) return
            val width = Media3Mapping.displayWidth(videoSize.width, videoSize.pixelWidthHeightRatio)
            Log.i(LogTag, "second engine: decoded size ${videoSize.width}x${videoSize.height}")
            updateFormat(state, state.format.copy(width = width, height = videoSize.height))
        }

        override fun onRenderedFirstFrame() {
            if (!current(state) || state.firstFrame) return
            state.firstFrame = true
            val decoder = state.videoDecoder
            val profile = state.format.dolbyVisionProfile
            // Seen working: the picture came from a decoder that takes Dolby
            // Vision itself, so the processing is the device's own.
            val processed = if (VideoPathPolicy.needsDolbyVisionProcessing(profile)) decoder != null && takesDolbyVision(decoder) else null
            Log.i(LogTag, "second engine: first picture from $decoder${if (processed == true) ", Dolby Vision processed by the device" else ""}")
            if (processed == false) {
                fail(state, "second engine: Dolby Vision picture came from a decoder that does not process it", PlaybackFailure.UnsupportedVideo)
                return
            }
            updateFormat(state, state.format.copy(dolbyVisionProcessed = processed))
        }

        override fun onPlayerError(error: PlaybackException) {
            val videoRenderer = (error as? ExoPlaybackException)?.let {
                it.type == ExoPlaybackException.TYPE_RENDERER && it.rendererFormat?.sampleMimeType?.startsWith("video/") == true
            } == true
            val failure = Media3Mapping.failure(error.errorCode, videoRenderer)
            Log.w(LogTag, "second engine: error ${error.errorCodeName}: ${error.message}", error)
            var cause = error.cause
            while (cause != null) {
                Log.w(LogTag, "second engine: caused by ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
            }
            fail(state, error.message ?: error.errorCodeName, failure)
        }

    }

    /** The decoder facts only the analytics callbacks carry; same rule as [FileListener]. */
    private inner class FileAnalytics(private val state: FileState) : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            if (!current(state)) return
            state.videoDecoder = decoderName
            val note = if (takesDolbyVision(decoderName)) "takes $DolbyVisionMime" else "plain decoder"
            Log.i(LogTag, "second engine: video decoder $decoderName ($note), ready in $initializationDurationMs ms")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            if (!current(state)) return
            Log.i(LogTag, "second engine: video ${format.sampleMimeType} ${format.codecs} ${format.width}x${format.height} colour ${format.colorInfo}")
            val profile = Media3Mapping.dolbyVisionProfile(format.codecs)
            updateFormat(
                state,
                state.format.copy(
                    gamma = Media3Mapping.gamma(format.colorInfo?.colorTransfer?.takeIf { it != Format.NO_VALUE }),
                    dolbyVision = format.sampleMimeType == DolbyVisionMime || profile != null,
                    dolbyVisionProfile = profile,
                    frameRate = format.frameRate.toDouble().takeIf { it.isFinite() && it > 0 },
                ),
            )
        }
    }

    /** The decoder's output colour, logged when it changes: it says what the device turned the picture into. */
    private inner class OutputLog(private val generation: Long) : VideoFrameMetadataListener {
        @Volatile private var last: String? = null

        override fun onVideoFrameAboutToBeRendered(
            presentationTimeUs: Long,
            releaseTimeNs: Long,
            format: Format,
            mediaFormat: MediaFormat?,
        ) {
            val output = mediaFormat ?: return
            fun int(key: String): Int? = if (output.containsKey(key)) runCatching { output.getInteger(key) }.getOrNull() else null
            val described = "colour standard ${int(MediaFormat.KEY_COLOR_STANDARD)}, " +
                "transfer ${int(MediaFormat.KEY_COLOR_TRANSFER)}, range ${int(MediaFormat.KEY_COLOR_RANGE)}"
            if (described == last) return
            last = described
            // Called on the playback thread; only logs.
            if (generation == loadGeneration) Log.i(LogTag, "second engine: decoder output $described")
        }
    }

    private companion object {
        const val LogTag = "HALO_TV"
        const val DolbyVisionMime = "video/dolby-vision"
        const val TickMs = 500L
        const val NoPictureTimeoutMs = 10_000L
        val TickToken = Any()
        val DefaultTextLanguages = arrayOf("en")
        const val SourceLostMessage = "The connection to this source was lost."

        /** The host alone, for the log: the rest of a source URL carries account keys. */
        fun hostOf(url: String): String = runCatching { Uri.parse(url).host }.getOrNull() ?: "a source"
    }
}

/** The second engine behind the shared contract; the TV's engine router hands it the files it takes. */
internal class Media3PlayerPort(private val host: Media3PlayerHost) : PlayerPort {
    override suspend fun load(item: MediaItem) = host.load(item)
    override suspend fun setPreferredAudioLanguages(codes: List<String>) = host.setPreferredAudioLanguages(codes)
    override suspend fun setPaused(paused: Boolean) = host.setPaused(paused)
    override suspend fun seekTo(positionSeconds: Double) = host.seekTo(positionSeconds)
    override suspend fun selectAudioTrack(id: String?) = host.selectAudioTrack(id)
    override suspend fun selectSubtitleTrack(id: String?) = host.selectSubtitleTrack(id)
    override suspend fun setPlaybackRate(rate: Double) = host.setPlaybackRate(rate)

    /** Media3 has no sound delay; the TV hides the control while this engine plays. */
    override suspend fun setAudioDelay(seconds: Double) = Unit
    override suspend fun setVideoFillsScreen(fills: Boolean) = host.setVideoFillsScreen(fills)

    /** No cue delay either; hidden the same way. */
    override suspend fun setSubtitleDelay(seconds: Double) = Unit
    override suspend fun setSubtitleScale(scale: Double) = host.setSubtitleScale(scale)
    override suspend fun setSubtitleFont(font: String?) = host.setSubtitleFont(font)

    /** Cues are drawn as plain text by the app, so there is no track styling to keep. */
    override suspend fun setSubtitleTrackStyling(keepScript: Boolean) = Unit
    override suspend fun setSubtitleOutline(widthPixels: Double) = host.setSubtitleOutline(widthPixels)
    override suspend fun setSubtitleShadow(offsetPixels: Double) = host.setSubtitleShadow(offsetPixels)
    override suspend fun setSubtitleLift(percent: Int) = host.setSubtitleLift(percent)
    override suspend fun addSubtitle(url: String, title: String?, lang: String?) = host.addSubtitle(url, title, lang)
    override suspend fun releaseVideoOutput() = host.releaseVideoOutput()
    override suspend fun teardown() = host.teardown()
}
