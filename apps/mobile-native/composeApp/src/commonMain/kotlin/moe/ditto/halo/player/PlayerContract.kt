package moe.ditto.halo.player

data class MediaItem(
    val id: String,
    val title: String,
    val url: String,
)

data class PlayerTrack(
    val id: String,
    val label: String,
    val language: String? = null,
)

data class PlayerTracks(
    val audio: List<PlayerTrack> = emptyList(),
    val subtitles: List<PlayerTrack> = emptyList(),
    val selectedAudioId: String? = null,
    val selectedSubtitleId: String? = null,
)

sealed interface PlayerEvent {
    data class Ready(val durationSeconds: Double?) : PlayerEvent
    data class PositionChanged(val positionSeconds: Double) : PlayerEvent
    data class PauseChanged(val paused: Boolean) : PlayerEvent
    data class TracksChanged(val tracks: PlayerTracks) : PlayerEvent
    data object NaturalEnd : PlayerEvent
    data class Error(val message: String) : PlayerEvent
    data object Teardown : PlayerEvent
}

interface PlayerPort {
    suspend fun load(item: MediaItem)
    suspend fun setPaused(paused: Boolean)
    suspend fun seekTo(positionSeconds: Double)
    suspend fun selectAudioTrack(id: String?)
    suspend fun selectSubtitleTrack(id: String?)

    // Live subtitle controls: these must apply to the running core without
    // recreating it — the exact capability libVLC lacked on mobile.
    suspend fun setSubtitleDelay(seconds: Double)
    suspend fun setSubtitleScale(scale: Double)
    suspend fun setSubtitleFont(font: String?)
    suspend fun addSubtitle(url: String)

    /**
     * Tears the video decode chain down and returns once it is gone, so the
     * render surface can be taken away without the core still using it.
     *
     * This has to be awaited before the surface disappears, never afterwards.
     * A hardware decoder mid-frame cannot answer a request to give up its
     * surface, and the surface's owner is waiting on the main thread for
     * exactly that answer — which is a deadlock, not a slow frame. The next
     * [load] restores video.
     *
     * Platforms whose surface outlives the screens that show it have nothing
     * to release, and say so by leaving this alone.
     */
    suspend fun releaseVideoOutput() = Unit

    suspend fun teardown()
}
