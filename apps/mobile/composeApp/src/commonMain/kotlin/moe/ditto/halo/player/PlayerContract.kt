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
    /** mpv/FFmpeg codec name, for example `aac`, `ass` or `hdmv_pgs_subtitle`. */
    val codec: String? = null,
    /** Decoded audio channel count. Null for subtitles and unreported audio. */
    val channels: Int? = null,
    /** Decoded audio sample rate in hertz. Null for subtitles and unreported audio. */
    val sampleRateHz: Int? = null,
)

data class PlayerTracks(
    val audio: List<PlayerTrack> = emptyList(),
    val subtitles: List<PlayerTrack> = emptyList(),
    val selectedAudioId: String? = null,
    val selectedSubtitleId: String? = null,
)

/**
 * What the engine is doing while it refills its cache, reported only while
 * playback is actually stalled on it.
 *
 * Every figure is separately optional because a host can know it is stalled
 * without knowing how fast it is recovering, and a zero would say something
 * different and wrong: "stalled, downloading nothing".
 */
data class PlayerBuffering(
    /** How full the cache is against the engine's own refill goal, 0..100. */
    val percent: Int? = null,
    val bytesPerSecond: Long? = null,
    /** Media the cache holds ahead of the playhead. */
    val cachedSeconds: Double? = null,
)

/**
 * How captions are drawn, as one value because the four settings are stored,
 * restored and applied together: applying them one at a time on load would
 * repaint the caption in three intermediate looks first.
 */
data class SubtitleStyle(
    val scale: Double = 1.0,
    val font: String? = null,
    val outlineWidthPixels: Double = DefaultOutlineWidthPixels,
    val shadowOffsetPixels: Double = 0.0,
) {
    companion object {
        /** The renderer's own outline weight, which is what "normal" means. */
        const val DefaultOutlineWidthPixels = 3.0
    }
}

sealed interface PlayerEvent {
    data class Ready(val durationSeconds: Double?) : PlayerEvent
    data class PositionChanged(val positionSeconds: Double) : PlayerEvent
    data class PauseChanged(val paused: Boolean) : PlayerEvent
    data class TracksChanged(val tracks: PlayerTracks) : PlayerEvent

    /**
     * The cache started or stopped stalling playback. The figures are only
     * meaningful while [active]; when it clears they are absent rather than
     * frozen at their last values, so nothing can display a stale rate.
     */
    data class BufferingChanged(
        val active: Boolean,
        val percent: Int? = null,
        val bytesPerSecond: Long? = null,
        val cachedSeconds: Double? = null,
    ) : PlayerEvent

    /**
     * How far into the media the cache now reaches, in seconds from the start,
     * which is what the transport bar draws its buffered fill from. Separate
     * from [BufferingChanged] because it moves the whole time a stream plays,
     * not only while it stalls.
     */
    data class BufferedPositionChanged(val positionSeconds: Double) : PlayerEvent

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

    /** Applies to the running core; one second of media takes `1 / rate` seconds. */
    suspend fun setPlaybackRate(rate: Double)

    /** Shifts the sound against the picture. Positive plays the sound later. */
    suspend fun setAudioDelay(seconds: Double)

    /**
     * Whether the picture fills the screen and loses its edges, or fits inside
     * it and leaves bars. Everything between the two is a compromise nobody
     * asked for, which is why this is a choice of two rather than a zoom.
     */
    suspend fun setVideoFillsScreen(fills: Boolean)

    // Live subtitle controls: these must apply to the running core without
    // recreating it — the exact capability libVLC lacked on mobile.
    suspend fun setSubtitleDelay(seconds: Double)
    suspend fun setSubtitleScale(scale: Double)
    suspend fun setSubtitleFont(font: String?)

    /**
     * Whether a styled script keeps its own fonts and positions, or is
     * overridden with the app's styling. Only script formats (ASS/SSA) carry
     * styling to keep; for everything else this is inert by construction.
     */
    suspend fun setSubtitleTrackStyling(keepScript: Boolean)

    /**
     * Outline and drop shadow, in the same pixel units the renderer draws them
     * in. Named in pixels rather than as a preset because the engine has no
     * opinion about what "thick" means; the screen owns that mapping.
     */
    suspend fun setSubtitleOutline(widthPixels: Double)
    suspend fun setSubtitleShadow(offsetPixels: Double)

    /**
     * Lifts the caption off the bottom edge by [percent] of the video height,
     * so the chrome's bottom bar does not land on top of it. Zero is the
     * renderer's own resting place.
     */
    suspend fun setSubtitleLift(percent: Int)

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
