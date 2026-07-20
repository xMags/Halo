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
    val queuedNext: MediaItem? = null,
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double? = null,
    val tracks: PlayerTracks = PlayerTracks(),
    val error: String? = null,
    // Local echo of the last requested subtitle styling; the core is the
    // source of truth, these exist so the shell can display what it asked for.
    val subtitleDelaySeconds: Double = 0.0,
    val subtitleScale: Double = 1.0,
    val subtitleFont: String? = null,
)

class PlayerPresenter(
    private val player: PlayerPort,
) {
    var state: PlayerState = PlayerState()
        private set

    suspend fun start(item: MediaItem, next: MediaItem? = null) {
        if (state.status == PlaybackStatus.Released) return
        state = PlayerState(current = item, queuedNext = next, status = PlaybackStatus.Loading)
        player.load(item)
    }

    fun queueNext(item: MediaItem?) {
        if (state.status == PlaybackStatus.Released) return
        state = state.copy(queuedNext = item)
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
            PlayerEvent.NaturalEnd -> advanceOrEnd()
            is PlayerEvent.Error -> state.copy(status = PlaybackStatus.Failed, error = event.message)
            PlayerEvent.Teardown -> state.copy(status = PlaybackStatus.Released, queuedNext = null)
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

    private suspend fun advanceOrEnd(): PlayerState {
        val next = state.queuedNext ?: return state.copy(status = PlaybackStatus.Ended)
        player.load(next)
        return PlayerState(current = next, status = PlaybackStatus.Loading)
    }
}
