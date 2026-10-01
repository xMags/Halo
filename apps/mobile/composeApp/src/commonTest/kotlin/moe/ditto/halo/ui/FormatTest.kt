package moe.ditto.halo.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pinned against the shipping client's `formatBytes`, since both render the
 * same stream lists and a size that reads differently between the two apps is
 * the kind of drift nobody reports and everybody notices.
 */
class FormatTest {
    @Test
    fun missingOrNonsensicalSizesRenderNothing() {
        assertEquals("", formatBytes(0))
        assertEquals("", formatBytes(-1))
    }

    @Test
    fun gigabytesKeepOneDecimalUntilTen() {
        assertEquals("1.0 GB", formatBytes(1L shl 30))
        assertEquals("4.1 GB", formatBytes(4_402_341_478))
        assertEquals("9.9 GB", formatBytes((9.94 * (1L shl 30)).toLong()))
    }

    @Test
    fun tenGigabytesAndUpDropTheDecimal() {
        assertEquals("10 GB", formatBytes(10L shl 30))
        // The 34 GB listing that forced videoSize to be a Long in the first place.
        assertEquals("32 GB", formatBytes(34_179_869_184))
    }

    @Test
    fun megabytesAreWhole() {
        assertEquals("1 MB", formatBytes(1L shl 20))
        assertEquals("45 MB", formatBytes(46_767_266))
        // Just under a gigabyte stays in megabytes rather than reading "1.0 GB".
        assertEquals("1024 MB", formatBytes((1L shl 30) - 1))
    }

    @Test
    fun kilobytesRoundUpSoNothingReadsAsEmpty() {
        assertEquals("1 KB", formatBytes(1))
        assertEquals("2 KB", formatBytes(1_500))
        assertEquals("1024 KB", formatBytes((1L shl 20) - 1))
    }

    @Test
    fun aSpeedIsSpelledLikeASizeExceptWhereThatWouldUnderstateIt() {
        // Megabytes keep a decimal below 10 MB/s: "1 MB/s" for a link running
        // at 1.4 understates it by nearly half, and this is the number someone
        // watches to decide whether to wait.
        assertEquals("1.4 MB/s", formatSpeed(1_500_000))
        assertEquals("9.5 MB/s", formatSpeed(10_000_000))
        assertEquals("12 MB/s", formatSpeed(12_400_000))
        assertEquals("977 KB/s", formatSpeed(1_000_000))
        assertEquals("1.0 GB/s", formatSpeed(1_073_741_824))
        assertEquals(null, formatSpeed(0))
        assertEquals(null, formatSpeed(-1))
    }
}
