package moe.ditto.halo.ui

import kotlin.math.ceil
import kotlin.math.roundToLong

private const val Kib = 1024.0
private const val Mib = Kib * 1024
private const val Gib = Mib * 1024

/**
 * `2_476_202_311` → `"2.3 GB"`, the way a file size is read rather than
 * computed: one decimal below 10 GB, none above it, whole megabytes, and
 * kilobytes rounded up so a small file never reads as nothing.
 *
 * A missing or nonsensical size returns an empty string rather than `"0 B"` —
 * plenty of addons publish no size at all, and a blank column says that
 * honestly where a zero would claim the file is empty.
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""

    val gb = bytes / Gib
    if (gb >= 1) return if (gb >= 10) "${gb.roundToLong()} GB" else "${oneDecimal(gb)} GB"

    val mb = bytes / Mib
    if (mb >= 1) return "${mb.roundToLong()} MB"

    return "${ceil(bytes / Kib).toLong()} KB"
}

/**
 * `12_400_000` → `"11.8 MB/s"`, or null when nothing has been measured yet.
 *
 * Spelled like a size, with one exception: megabytes keep a decimal below
 * 10 MB/s. A size rounds to whole megabytes because nobody cares whether a file
 * is 1 GB or 1.02 GB, but a link running at 1.4 MB/s reported as "1 MB/s" is
 * understating it by nearly half, and this is the number someone watches to
 * decide whether to wait.
 */
fun formatSpeed(bytesPerSecond: Long): String? {
    if (bytesPerSecond <= 0) return null
    val mb = bytesPerSecond / Mib
    if (mb >= 1 && mb < 10) return "${oneDecimal(mb)} MB/s"
    val size = formatBytes(bytesPerSecond)
    return if (size.isEmpty()) null else "$size/s"
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}
