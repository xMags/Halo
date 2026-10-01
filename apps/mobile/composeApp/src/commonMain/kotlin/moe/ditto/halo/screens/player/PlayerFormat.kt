package moe.ditto.halo.screens.player

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Timecodes for the transport row and the scrub preview.
 *
 * Minutes are padded only past an hour (`4:07`, then `1:04:07`), which is what
 * every player does and what keeps the common case narrow. Anything not finite
 * or not yet known reads as zero rather than as an error: a duration arrives a
 * moment after a load, and a dash or a blank there flickers on every start.
 */
internal fun formatTimecode(totalSeconds: Double): String {
    if (!totalSeconds.isFinite() || totalSeconds <= 0.0) return "0:00"

    val total = floor(totalSeconds).toLong()
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600

    val paddedSeconds = seconds.toString().padStart(2, '0')
    if (hours <= 0) return "$minutes:$paddedSeconds"
    return "$hours:${minutes.toString().padStart(2, '0')}:$paddedSeconds"
}

/**
 * The "how much is left" readout, or null while the duration is unknown, in
 * which case the label is dropped rather than shown counting down from zero.
 */
internal fun formatRemaining(positionSeconds: Double, durationSeconds: Double?): String? {
    val duration = durationSeconds ?: return null
    if (!duration.isFinite() || duration <= 0.0) return null
    return "${formatTimecode(duration - positionSeconds)} left"
}

/**
 * A track delay, in milliseconds with an explicit sign.
 *
 * The sign is what the control is for: the reader needs to know which way the
 * track has been pushed, not just by how much. Zero carries no sign, and the
 * minus is a real minus sign rather than a hyphen so it lines up with the plus
 * in a monospaced column.
 */
internal fun formatDelay(seconds: Double): String {
    if (!seconds.isFinite()) return "0 ms"
    val millis = (seconds * 1_000.0).roundToInt()
    if (millis == 0) return "0 ms"
    val sign = if (millis > 0) "+" else "−"
    return "$sign${abs(millis)} ms"
}

/**
 * A track delay the steppers can actually express. Both steppers share the
 * limit, and both send it straight to the engine, so clamping is the boundary
 * between "shift the track" and "ask for a track that is not there".
 */
internal fun clampedDelay(seconds: Double): Double {
    if (!seconds.isFinite()) return 0.0
    return seconds.coerceIn(-MaxDelaySeconds, MaxDelaySeconds)
}

/** A subtitle scale as the percentage the slider is labelled in. */
internal fun formatScalePercent(scale: Double): String {
    if (!scale.isFinite() || scale <= 0.0) return "100%"
    return "${(scale * 100.0).roundToInt()}%"
}

/**
 * Playback rate, written the way the design labels it: whole rates lose their
 * decimal so the common case reads `1×` rather than `1.0×`.
 */
internal fun formatRate(rate: Double): String {
    if (!rate.isFinite() || rate <= 0.0) return "1×"
    val rounded = (rate * 100.0).roundToInt()
    val text = when {
        rounded % 100 == 0 -> (rounded / 100).toString()
        rounded % 10 == 0 -> "${rounded / 100}.${(rounded % 100) / 10}"
        else -> "${rounded / 100}.${(rounded % 100).toString().padStart(2, '0')}"
    }
    return "$text×"
}

/**
 * The rate the cache is filling at, or null when the engine did not say, in
 * which case the caller drops the figure rather than printing a zero.
 *
 * Units step at 1024 and are labelled KB/s and MB/s, which is what every
 * download readout on both platforms does; the pedantically correct KiB/s would
 * be the only place in the app that spelled it that way. One decimal below
 * 10 MB/s, none above, so the line does not change width while it counts.
 */
internal fun formatThroughput(bytesPerSecond: Long?): String? {
    val bytes = bytesPerSecond ?: return null
    if (bytes < 0L) return null
    if (bytes < 1_024L) return "$bytes B/s"

    val kilobytes = bytes.toDouble() / 1_024.0
    if (kilobytes < 1_024.0) return "${kilobytes.roundToInt()} KB/s"

    val megabytes = kilobytes / 1_024.0
    if (megabytes < 10.0) return "${formatOneDecimal(megabytes)} MB/s"
    return "${megabytes.roundToInt()} MB/s"
}

/**
 * How much media the cache holds ahead of the playhead, or null when unknown.
 * This is the figure that separates "slow but recovering" from "stuck", so it
 * is worth its own line even though the percentage sits right above it.
 */
internal fun formatCachedAhead(seconds: Double?): String? {
    val cached = seconds ?: return null
    if (!cached.isFinite() || cached < 0.0) return null
    if (cached < 60.0) return "${cached.roundToInt()} s cached"
    return "${(cached / 60.0).roundToInt()} min cached"
}

private fun formatOneDecimal(value: Double): String {
    val tenths = (value * 10.0).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * Playback progress as a 0..1 fraction, or 0 while the duration is unknown.
 * Callers draw a track from this, so it must never be NaN.
 */
internal fun progressFraction(positionSeconds: Double, durationSeconds: Double?): Float {
    val duration = durationSeconds ?: return 0f
    if (!duration.isFinite() || duration <= 0.0) return 0f
    if (!positionSeconds.isFinite()) return 0f
    return (positionSeconds / duration).coerceIn(0.0, 1.0).toFloat()
}
