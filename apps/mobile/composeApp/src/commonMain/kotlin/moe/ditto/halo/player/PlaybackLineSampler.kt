package moe.ditto.halo.player

/**
 * Measures the line while a network stream plays, from what mpv reports;
 * ported from the desktop player's `lineSampler.ts`. The player feeds it mpv's
 * `cache-speed` (bytes per second), whether the cache is idle
 * (`demuxer-cache-idle`), stalls (`paused-for-cache`), seeks and the start of
 * playback, and calls [tick] once a second. No timers, threads or I/O here;
 * the caller serializes every call.
 *
 * Only seconds in which the cache was actually filling count: once the cache
 * holds what it wants, the download pauses and the speed says nothing about
 * the line. The reading is the best average over a few consecutive filling
 * seconds, so a single burst is not taken for the line's speed.
 */
internal class PlaybackLineSampler {
    private var speedBytesPerSecond = 0.0

    /** Until mpv says otherwise there is no filling cache to measure. */
    private var cacheIdle = true
    private var started = false
    private var lastSeekAtMs: Long? = null
    private val window = ArrayDeque<Double>(LineSampleWindow + 1)
    private var peakMbps = 0.0
    private var stalled = false

    /** mpv's `cache-speed`; anything but a positive finite number counts as 0. */
    fun cacheSpeed(bytesPerSecond: Double?) {
        speedBytesPerSecond = if (bytesPerSecond != null && bytesPerSecond.isFinite() && bytesPerSecond > 0) bytesPerSecond else 0.0
    }

    /** mpv's `demuxer-cache-idle`; an idle cache breaks the run of samples. */
    fun cacheIdleChanged(idle: Boolean) {
        cacheIdle = idle
        if (idle) window.clear()
    }

    /** The first frame has played (mpv's first `playback-restart`). */
    fun playbackStarted() {
        started = true
    }

    fun seeked(nowMs: Long) {
        lastSeekAtMs = nowMs
    }

    /** mpv's `paused-for-cache`. */
    fun pausedForCache(paused: Boolean, nowMs: Long) {
        if (!paused || !started) return
        val seekAt = lastSeekAtMs
        if (seekAt != null && nowMs - seekAt < SeekStallGraceMs) return
        stalled = true
    }

    /** One second has passed: takes a sample when the cache is filling. */
    fun tick() {
        if (cacheIdle) {
            window.clear()
            return
        }
        window.addLast(speedBytesPerSecond * 8 / 1_000_000)
        if (window.size > LineSampleWindow) window.removeFirst()
        if (window.size < LineSampleWindow) return
        val average = window.sum() / LineSampleWindow
        if (average > peakMbps) peakMbps = average
    }

    fun session(): LineSession = LineSession(peakMbps, stalled)

    companion object {
        /** One-second samples averaged into one reading. */
        const val LineSampleWindow = 5

        /** A stall this soon after a seek is the seek's own refill, not a slow line. */
        const val SeekStallGraceMs = 3_000L
    }
}

internal data class LineSession(
    /** The best averaged reading of this session in megabits per second; 0 when none was taken. */
    val peakMbps: Double,
    /** Playback stopped to buffer after it had started, and not because of a seek. */
    val stalled: Boolean,
)
