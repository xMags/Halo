package moe.ditto.halo.downloads

/**
 * How fast a transfer is going, from the progress reports it already makes.
 *
 * Smoothed rather than instantaneous. Bytes arrive in bursts as buffers drain,
 * so the rate between two samples swings by a factor of several while the
 * connection is perfectly steady, and a readout that does that is read as the
 * download misbehaving. The weighting keeps roughly the last few seconds, which
 * is short enough to notice a stall and long enough not to flicker.
 *
 * Not thread-safe: the coordinator is the only caller and holds its lock.
 */
internal class TransferRate(
    /** Weight given to the newest sample. Lower is smoother and slower to react. */
    private val smoothing: Double = 0.35,
) {
    private var videoId: String? = null
    private var lastBytes: Long = 0
    private var lastAtMs: Long = 0
    private var rate: Double = 0.0

    /**
     * Records a sample and returns the current rate in bytes per second, or
     * zero while there is nothing to compare against.
     *
     * A sample for a different download starts over: the number belongs to one
     * transfer, and only one runs at a time.
     */
    fun sample(videoId: String, downloadedBytes: Long, atMs: Long): Long {
        if (this.videoId != videoId || downloadedBytes < lastBytes) {
            reset(videoId, downloadedBytes, atMs)
            return 0
        }
        val elapsedMs = atMs - lastAtMs
        // Two reports inside the same millisecond divide by nothing; keep the
        // rate already held rather than inventing an infinite one.
        if (elapsedMs <= 0) return rate.toLong()

        val instant = (downloadedBytes - lastBytes) * 1000.0 / elapsedMs
        lastBytes = downloadedBytes
        lastAtMs = atMs
        rate = if (rate <= 0.0) instant else rate + smoothing * (instant - rate)
        return rate.toLong()
    }

    /** Forgets the transfer being measured, so the next one starts clean. */
    fun clear() {
        videoId = null
        lastBytes = 0
        lastAtMs = 0
        rate = 0.0
    }

    private fun reset(videoId: String, downloadedBytes: Long, atMs: Long) {
        this.videoId = videoId
        lastBytes = downloadedBytes
        lastAtMs = atMs
        rate = 0.0
    }
}
