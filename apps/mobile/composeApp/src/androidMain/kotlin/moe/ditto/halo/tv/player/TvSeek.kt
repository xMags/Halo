package moe.ditto.halo.tv.player

/**
 * How far one press of Left or Right seeks: 10 seconds for a tap, then larger
 * steps the longer the key is held (the key repeats about 20 times a second),
 * so a long hold crosses a film without a long wait.
 */
internal object TvSeekSteps {
    fun stepFor(repeatCount: Int): Double = when {
        repeatCount < SlowRepeats -> 10.0
        repeatCount < MediumRepeats -> 30.0
        else -> 60.0
    }

    private const val SlowRepeats = 8
    private const val MediumRepeats = 24
}

/**
 * A seek target being built up by presses before it is committed: the
 * playhead stays where it is while the bubble shows where it will go, and one
 * seek is made at the end instead of one per key repeat.
 */
internal class TvSeekTarget {
    /** Seconds; null when no seek is being built. */
    var target: Double? = null
        private set

    /**
     * Moves the target by [delta] seconds from where it is, or from [position]
     * when no seek is being built, kept inside the video.
     */
    fun nudge(position: Double, delta: Double, duration: Double?): Double {
        val from = target ?: position
        val upper = duration?.takeIf { it.isFinite() && it > 0 }
        val moved = (from + delta).coerceAtLeast(0.0).let { if (upper != null) it.coerceAtMost(upper) else it }
        target = moved
        return moved
    }

    /** The target to seek to, and the build-up ended; null when nothing was built. */
    fun commit(): Double? {
        val value = target
        target = null
        return value
    }

    fun cancel() {
        target = null
    }
}
