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

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}
