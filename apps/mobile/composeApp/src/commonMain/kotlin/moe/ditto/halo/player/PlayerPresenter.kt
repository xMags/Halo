package moe.ditto.halo.player

enum class PlaybackStatus {
    Idle,
    Loading,
    Playing,
    Paused,
    Ended,
    Failed,
    Released,
}

data class PlayerState(
    val current: MediaItem? = null,
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double? = null,
    val tracks: PlayerTracks = PlayerTracks(),
    /** Non-null only while the cache is stalling playback. */
    val buffering: PlayerBuffering? = null,
    /** How far into the media the cache reaches, or null before the engine says. */
    val bufferedPositionSeconds: Double? = null,
    val error: String? = null,
    // Local echoes of the last requested live controls. The core is the source
    // of truth; these exist so the shell can display what it asked for without
    // waiting for a property observation that some platform hosts cannot emit.
    val playbackRate: Double = 1.0,
    /** True when the picture is cropped to fill the screen rather than fitted inside it. */
    val videoFillsScreen: Boolean = false,
    val audioDelaySeconds: Double = 0.0,
    val subtitleDelaySeconds: Double = 0.0,
    val subtitleScale: Double = 1.0,
    val subtitleFont: String? = null,
    /** True while a styled script keeps its own fonts and positions. */
    val subtitleTrackStyling: Boolean = true,
)

class PlayerPresenter(
    private val player: PlayerPort,
) {
    var state: PlayerState = PlayerState()
        private set

    suspend fun start(item: MediaItem) {
        if (state.status == PlaybackStatus.Released) return
        state = PlayerState(
            current = item,
            status = PlaybackStatus.Loading,
            // These are core properties, not file properties. libmpv retains
            // them across loadfile, so the UI echoes must retain them too.
            playbackRate = state.playbackRate,
            videoFillsScreen = state.videoFillsScreen,
            audioDelaySeconds = state.audioDelaySeconds,
            subtitleDelaySeconds = state.subtitleDelaySeconds,
            subtitleScale = state.subtitleScale,
            subtitleFont = state.subtitleFont,
            subtitleTrackStyling = state.subtitleTrackStyling,
        )
        player.load(item)
    }

    suspend fun setPaused(paused: Boolean) {
        if (state.status == PlaybackStatus.Released) return
        player.setPaused(paused)
    }

    suspend fun seekTo(positionSeconds: Double) {
        if (state.status == PlaybackStatus.Released || !positionSeconds.isFinite()) return
        player.seekTo(positionSeconds.coerceAtLeast(0.0))
    }

    suspend fun selectAudioTrack(id: String?) {
        if (state.status == PlaybackStatus.Released) return
        player.selectAudioTrack(id)
    }

    suspend fun selectSubtitleTrack(id: String?) {
        if (state.status == PlaybackStatus.Released) return
        player.selectSubtitleTrack(id)
    }

    suspend fun setPlaybackRate(rate: Double) {
        if (state.status == PlaybackStatus.Released || !rate.isFinite() || rate <= 0.0) return
        player.setPlaybackRate(rate)
        state = state.copy(playbackRate = rate)
    }

    suspend fun setVideoFillsScreen(fills: Boolean) {
        if (state.status == PlaybackStatus.Released) return
        player.setVideoFillsScreen(fills)
        state = state.copy(videoFillsScreen = fills)
    }

    suspend fun setAudioDelay(seconds: Double) {
        if (state.status == PlaybackStatus.Released || !seconds.isFinite()) return
        player.setAudioDelay(seconds)
        state = state.copy(audioDelaySeconds = seconds)
    }

    suspend fun setSubtitleDelay(seconds: Double) {
        if (state.status == PlaybackStatus.Released || !seconds.isFinite()) return
        player.setSubtitleDelay(seconds)
        state = state.copy(subtitleDelaySeconds = seconds)
    }

    suspend fun setSubtitleScale(scale: Double) {
        if (state.status == PlaybackStatus.Released || !scale.isFinite() || scale <= 0.0) return
        player.setSubtitleScale(scale)
        state = state.copy(subtitleScale = scale)
    }

    suspend fun setSubtitleFont(font: String?) {
        if (state.status == PlaybackStatus.Released) return
        player.setSubtitleFont(font)
        state = state.copy(subtitleFont = font)
    }

    suspend fun setSubtitleTrackStyling(keepScript: Boolean) {
        if (state.status == PlaybackStatus.Released) return
        player.setSubtitleTrackStyling(keepScript)
        state = state.copy(subtitleTrackStyling = keepScript)
    }

    /**
     * Outline and shadow have no echo in [PlayerState]: nothing on screen shows
     * them back, and a field no reader consults is a second source of truth
     * waiting to disagree with the engine.
     */
    suspend fun setSubtitleOutline(widthPixels: Double) {
        if (state.status == PlaybackStatus.Released || !widthPixels.isFinite() || widthPixels < 0.0) return
        player.setSubtitleOutline(widthPixels)
    }

    suspend fun setSubtitleShadow(offsetPixels: Double) {
        if (state.status == PlaybackStatus.Released || !offsetPixels.isFinite() || offsetPixels < 0.0) return
        player.setSubtitleShadow(offsetPixels)
    }

    suspend fun setSubtitleLift(percent: Int) {
        if (state.status == PlaybackStatus.Released) return
        player.setSubtitleLift(percent.coerceIn(0, MaxSubtitleLiftPercent))
    }

    suspend fun addSubtitle(url: String) {
        if (state.status == PlaybackStatus.Released || url.isBlank()) return
        player.addSubtitle(url)
    }

    suspend fun onEvent(event: PlayerEvent) {
        // A real core emits initial property observations (pause, empty
        // track-list) at creation, before any load. Playback events without a
        // requested media item must not move the presenter out of Idle —
        // otherwise ensurePlayerStarted() sees non-Idle and never loads.
        if (state.status == PlaybackStatus.Idle && event !is PlayerEvent.TracksChanged) return

        val isTerminal = when (state.status) {
            PlaybackStatus.Ended,
            PlaybackStatus.Failed,
            PlaybackStatus.Released -> true
            else -> false
        }
        if (isTerminal && event != PlayerEvent.Teardown) return

        state = when (event) {
            is PlayerEvent.Ready -> state.copy(
                status = PlaybackStatus.Playing,
                durationSeconds = event.durationSeconds?.takeIf { it.isFinite() && it >= 0.0 },
                error = null,
            )
            is PlayerEvent.PositionChanged -> state.copy(
                positionSeconds = event.positionSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0)
                    ?: state.positionSeconds,
            )
            is PlayerEvent.PauseChanged -> state.copy(
                status = if (event.paused) PlaybackStatus.Paused else PlaybackStatus.Playing,
            )
            is PlayerEvent.TracksChanged -> state.copy(tracks = event.tracks)
            is PlayerEvent.BufferingChanged -> state.copy(buffering = event.toBuffering())
            is PlayerEvent.BufferedPositionChanged -> state.copy(
                bufferedPositionSeconds = event.positionSeconds
                    .takeIf { it.isFinite() && it >= 0.0 }
                    ?: state.bufferedPositionSeconds,
            )
            PlayerEvent.NaturalEnd -> state.copy(status = PlaybackStatus.Ended, buffering = null)
            // Nothing is being filled once playback has failed, and a stall is
            // a common way to arrive here, so the overlay must not survive
            // underneath the error card.
            is PlayerEvent.Error -> state.copy(
                status = PlaybackStatus.Failed,
                error = event.message,
                buffering = null,
            )
            PlayerEvent.Teardown -> state.copy(status = PlaybackStatus.Released)
        }
    }

    suspend fun close() {
        if (state.status == PlaybackStatus.Released) return
        try {
            player.teardown()
        } finally {
            onEvent(PlayerEvent.Teardown)
        }
    }
}

/**
 * Half the picture is as far as a caption can sensibly be pushed; past that it
 * is no longer a caption, and a bad persisted value must not be able to send it
 * off the top of the frame.
 */
private const val MaxSubtitleLiftPercent = 50

/**
 * Drops the figures a host reported as unusable, so the state never carries a
 * negative rate or a non-finite cache depth into a formatter.
 */
private fun PlayerEvent.BufferingChanged.toBuffering(): PlayerBuffering? {
    if (!active) return null
    return PlayerBuffering(
        percent = percent?.coerceIn(0, 100),
        bytesPerSecond = bytesPerSecond?.takeIf { it >= 0L },
        cachedSeconds = cachedSeconds?.takeIf { it.isFinite() && it >= 0.0 },
    )
}
