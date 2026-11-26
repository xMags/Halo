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
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.io.File
import java.util.Locale
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

    /** Decides each file's pass-through; null (the default) decodes every track. Set once by the TV activity. */
    @Volatile var soundPolicy: Media3SoundPolicy? = null

    // The sound sent on untouched for the file playing; null while decoded here.
    private val sound = MutableStateFlow<Media3SoundState?>(null)
    val soundPassthrough: StateFlow<Media3SoundState?> = sound.asStateFlow()

    private var player: ExoPlayer? = null
    private var gate: PassthroughGate? = null
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

    /** Reads this device's Dolby Vision decoders now; blocking, so off the main thread, before a file loads. */
    fun prefetchDecoders() {
        dolbyVisionDecoders
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

        // What this file may send on untouched, and what it is sending now.
        var allowedSound: Set<Int> = emptySet()
        var soundEncoding: Int? = null
        var soundStarts = 0
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    // --- The contract, through [Media3PlayerPort] ---

    /**
     * [knownFrameRate] is the file's rate as already seen (mpv's, for a
     * hand-over, or as this TV remembered it): reported at once, so the
     * display mode is right before the first picture, and never replaced by
     * one measured here.
     */
    suspend fun load(item: MediaItem, startSeconds: Double? = null, knownFrameRate: Double? = null) = onMain {
        if (closed) return@onMain
        releasePlayer()
        val generation = ++loadGeneration
        val media = Media3Item.fromUri(item.url)
        val state = FileState(generation, item, media)
        file = state
        updateFormat(state, VideoFormat(frameRate = knownFrameRate?.takeIf { it.isFinite() && it > 0 }), always = true)
        if (knownFrameRate != null) Log.i(LogTag, "second engine: frame rate $knownFrameRate as already seen for this file")
        subtitles.update { it.copy(text = "") }
        sound.value = null
        // Sound sent on untouched cannot be sped up, so another speed decodes it.
        state.allowedSound = if (playbackRate == 1.0) soundPolicy?.passthroughEncodings().orEmpty() else emptySet()
        val built = buildPlayer(item.headers)
        gate?.allowed = state.allowedSound
        player = built
        val listener = FileListener(state)
        built.addListener(listener)
        built.addAnalyticsListener(FileAnalytics(state))
        built.setVideoFrameMetadataListener(OutputLog(state))
        built.trackSelectionParameters = built.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray())
            // mpv's own pick before the app chooses: an English track if any.
            .setPreferredTextLanguages(*DefaultTextLanguages)
            .build()
        built.setPlaybackSpeed(playbackRate.toFloat())
        surfaceView?.let { built.setVideoSurfaceView(it) }
        Log.i(LogTag, "second engine: opening ${hostOf(item.url)} with ${item.headers.size} request headers")
        // Opened at the place it starts from, so nothing is decoded from the top first.
        val startMs = startSeconds?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000).toLong() }
        if (startMs != null) built.setMediaItem(media, startMs) else built.setMediaItem(media)
        built.prepare()
        scheduleTick(generation)
    }

    suspend fun setPaused(paused: Boolean) = onMain { player?.playWhenReady = !paused }

    suspend fun seekTo(positionSeconds: Double) = onMain<Unit> {
        val current = player ?: return@onMain
        current.setSeekParameters(SeekParameters.EXACT)
        current.seekTo((positionSeconds * 1000).toLong().coerceAtLeast(0L))
    }

    /** To the nearest keyframe at or past [positionSeconds] the way the seek goes; see [PlayerPort.seekNearKeyframe]. */
    suspend fun seekNearKeyframe(positionSeconds: Double, forward: Boolean) = onMain<Unit> {
        val current = player ?: return@onMain
        current.setSeekParameters(if (forward) SeekParameters.NEXT_SYNC else SeekParameters.PREVIOUS_SYNC)
        current.seekTo((positionSeconds * 1000).toLong().coerceAtLeast(0L))
    }

    suspend fun setPlaybackRate(rate: Double) = onMain<Unit> {
        playbackRate = rate
        val state = file
        if (rate != 1.0 && state != null && state.allowedSound.isNotEmpty() && current(state)) {
            Log.i(LogTag, "second engine: speed $rate cannot apply to sound sent on untouched; decoding it here")
            decodeSoundHere(state)
        }
        player?.setPlaybackSpeed(rate.toFloat())
    }

    /** This file's sound right now, for the TV's pass-through watch; null once the player is gone. */
    suspend fun audioStartSample(): AudioStartSample? = onMain {
        val state = file ?: return@onMain null
        val current = player ?: return@onMain null
        if (!current(state)) return@onMain null
        // Media3 keeps time by the audio output while there is sound, so the
        // position moves only while that output takes the stream.
        val position = current.currentPosition / 1000.0
        AudioStartSample(
            PlaybackFile(core = 0, load = state.generation),
            AudioStartReading(
                paused = !current.playWhenReady,
                pausedForCache = state.buffering || current.playbackState == Player.STATE_BUFFERING,
                pictureReady = state.firstFrame || !state.hasVideo,
                audioPts = position.takeIf { state.soundEncoding != null },
                positionSeconds = position,
                cachedAheadSeconds = ((current.bufferedPosition - current.currentPosition).coerceAtLeast(0L)) / 1000.0,
                audioOutput = state.soundEncoding?.let { "pass-through encoding $it" },
            ),
        )
    }

    /**
     * Decodes [file]'s sound here from now on, keeping the picture going,
     * while it is still the file playing. True when it changed anything.
     */
    suspend fun decodeAudioHere(file: PlaybackFile): Boolean = onMain {
        val state = this.file ?: return@onMain false
        if (state.generation != file.load || !current(state) || state.allowedSound.isEmpty()) return@onMain false
        decodeSoundHere(state)
        true
    }

    /**
     * No more pass-through for this file: the gate refuses compressed sound
     * and the sound renderer is set up again (off and on), which reopens the
     * output decoded while the picture plays on. Costs a short gap in the sound.
     */
    private fun decodeSoundHere(state: FileState) {
        state.allowedSound = emptySet()
        state.soundEncoding = null
        gate?.allowed = emptySet()
        sound.value = null
        val current = player ?: return
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        // Back on once the player has acted on the first change; set at once,
        // the two could be taken as one, which reopens nothing.
        main.postDelayed({
            if (!current(state)) return@postDelayed
            val again = player ?: return@postDelayed
            again.trackSelectionParameters = again.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        }, SoundReopenMs)
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
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                // Media3's own reading of the output, kept up to date by it as
                // the output changes; the gate lets through only what this
                // device was allowed for the file. Everything else is decoded
                // here, to as many channels as its decoder gives.
                val sink = PassthroughGate(DefaultAudioSink.Builder(context).build())
                gate = sink
                return sink
            }
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
        gate = null
        sound.value = null
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
            // The output would not open or take the stream sent on untouched:
            // decoded here from the same place instead, remembered for the output.
            if (current(state) && error.errorCode in SoundOutputErrors && state.allowedSound.isNotEmpty()) {
                val encoding = state.soundEncoding ?: state.allowedSound.first()
                Log.i(LogTag, "second engine: sound output failed (${error.errorCodeName}) sending encoding $encoding on; decoding it here")
                soundPolicy?.onPassthroughFailed(encoding)
                state.allowedSound = emptySet()
                state.soundEncoding = null
                gate?.allowed = emptySet()
                sound.value = null
                // The error stopped the player; preparing again resumes at its place, picture included.
                player?.prepare()
                return
            }
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
        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            if (!current(state)) return
            val encoding = audioTrackConfig.encoding
            if (Media3Encodings.isPassthrough(encoding)) {
                // Every opening counts as a new start: an HDMI re-plug opens
                // the output again, and only the latest one is watched.
                state.soundEncoding = encoding
                state.soundStarts += 1
                sound.value = Media3SoundState(state.item, encoding, state.soundStarts)
                Log.i(LogTag, "second engine: sound sent on untouched, output opened for encoding $encoding at ${audioTrackConfig.sampleRate} Hz")
            } else {
                state.soundEncoding = null
                sound.value = null
                Log.i(LogTag, "second engine: sound decoded here, output opened for PCM encoding $encoding at ${audioTrackConfig.sampleRate} Hz, channel mask ${audioTrackConfig.channelConfig}")
            }
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            if (!current(state)) return
            // Media3 retries these itself; the watch decides if sound never comes.
            Log.w(LogTag, "second engine: sound output error ${audioSinkError.javaClass.simpleName}: ${audioSinkError.message}")
        }

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
                    // The track's own rate, else the one measured from the frames ([OutputLog]).
                    frameRate = format.frameRate.toDouble().takeIf { it.isFinite() && it > 0 } ?: state.format.frameRate,
                ),
            )
        }
    }

    /**
     * Each frame about to be shown, on the playback thread: the decoder's
     * output colour, logged when it changes (it says what the device turned
     * the picture into), and the frame rate measured from the first frames'
     * times, for a track that states none, so the display can match it.
     */
    private inner class OutputLog(private val state: FileState) : VideoFrameMetadataListener {
        private val generation = state.generation
        @Volatile private var last: String? = null
        private val frameTimes = ArrayList<Long>(MeasuredFrames)

        override fun onVideoFrameAboutToBeRendered(
            presentationTimeUs: Long,
            releaseTimeNs: Long,
            format: Format,
            mediaFormat: MediaFormat?,
        ) {
            measureRate(presentationTimeUs)
            val output = mediaFormat ?: return
            fun int(key: String): Int? = if (output.containsKey(key)) runCatching { output.getInteger(key) }.getOrNull() else null
            val described = "colour standard ${int(MediaFormat.KEY_COLOR_STANDARD)}, " +
                "transfer ${int(MediaFormat.KEY_COLOR_TRANSFER)}, range ${int(MediaFormat.KEY_COLOR_RANGE)}"
            if (described == last) return
            last = described
            // Called on the playback thread; only logs.
            if (generation == loadGeneration) Log.i(LogTag, "second engine: decoder output $described")
        }

        @Volatile private var measured = false

        /**
         * Collects the frames' times until [Media3Mapping.measuredFrameRate]
         * can tell the rate, starting over after any jump (a seek), and posts
         * it once. Skipped while the file already has a rate.
         */
        private fun measureRate(presentationTimeUs: Long) {
            if (measured || state.format.frameRate != null) return
            val previous = frameTimes.lastOrNull()
            if (previous != null && presentationTimeUs - previous !in 1..MaxFrameGapUs) frameTimes.clear()
            frameTimes += presentationTimeUs
            if (frameTimes.size < MeasuredFrames) return
            measured = true
            val fps = Media3Mapping.measuredFrameRate(frameTimes)
            if (fps == null) {
                Log.i(LogTag, "second engine: frame rate not measurable from the frames; the display mode stays as it is")
                return
            }
            main.post {
                if (!current(state) || state.format.frameRate != null) return@post
                Log.i(LogTag, "second engine: frame rate ${"%.3f".format(Locale.ROOT, fps)} measured from the frames")
                updateFormat(state, state.format.copy(frameRate = fps))
            }
        }
    }

    private companion object {
        const val LogTag = "HALO_TV"
        const val DolbyVisionMime = "video/dolby-vision"
        const val TickMs = 500L
        const val NoPictureTimeoutMs = 10_000L
        const val SoundReopenMs = 300L

        /**
         * About five seconds of a film's frames, unbroken: with timestamps in
         * whole milliseconds the span is off by at most 1 ms in 5 s (0.02%),
         * a fifth of the gap between 23.976 and 24.
         */
        const val MeasuredFrames = Media3Mapping.MinMeasuredFrames

        /** Wider than any frame gap: a larger one, or a step back, is a seek and starts the count again. */
        const val MaxFrameGapUs = 200_000L

        /** Media3's `PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED` and `ERROR_CODE_AUDIO_TRACK_WRITE_FAILED`. */
        val SoundOutputErrors = setOf(5001, 5002)
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
    override val opensAtStartPosition: Boolean get() = true
    override suspend fun load(item: MediaItem, startPositionSeconds: Double?) = host.load(item, startPositionSeconds)
    override suspend fun setPreferredAudioLanguages(codes: List<String>) = host.setPreferredAudioLanguages(codes)
    override suspend fun setPaused(paused: Boolean) = host.setPaused(paused)
    override suspend fun seekTo(positionSeconds: Double) = host.seekTo(positionSeconds)
    override suspend fun seekNearKeyframe(positionSeconds: Double, forward: Boolean) = host.seekNearKeyframe(positionSeconds, forward)
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
