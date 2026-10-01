package moe.ditto.halo.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Timecodes are read constantly and are the one place a player looks broken
 * immediately. The cases that matter are the boundaries: the hour mark, and the
 * moments before a duration is known, where a stream reports nothing useful.
 */
class PlayerFormatTest {

    @Test
    fun minutesAreUnpaddedUnderAnHour() {
        assertEquals("0:00", formatTimecode(0.0))
        assertEquals("0:07", formatTimecode(7.4))
        assertEquals("4:07", formatTimecode(247.0))
        assertEquals("59:59", formatTimecode(3_599.0))
    }

    @Test
    fun minutesArePaddedOnceThereAreHours() {
        assertEquals("1:00:00", formatTimecode(3_600.0))
        assertEquals("1:04:07", formatTimecode(3_847.0))
        assertEquals("12:00:00", formatTimecode(43_200.0))
    }

    @Test
    fun secondsTruncateRatherThanRound() {
        // Rounding would let a 3:59.7 position display as 4:00 while the
        // engine is still in the previous second.
        assertEquals("3:59", formatTimecode(239.9))
    }

    @Test
    fun unusableInputReadsAsZero() {
        assertEquals("0:00", formatTimecode(-12.0))
        assertEquals("0:00", formatTimecode(Double.NaN))
        assertEquals("0:00", formatTimecode(Double.POSITIVE_INFINITY))
    }

    @Test
    fun remainingCountsDownFromTheDuration() {
        assertEquals("40:52 left", formatRemaining(positionSeconds = 400.0, durationSeconds = 2_852.0))
        assertEquals("0:00 left", formatRemaining(positionSeconds = 2_852.0, durationSeconds = 2_852.0))
    }

    @Test
    fun remainingIsDroppedWhileTheDurationIsUnknown() {
        assertNull(formatRemaining(positionSeconds = 12.0, durationSeconds = null))
        assertNull(formatRemaining(positionSeconds = 12.0, durationSeconds = 0.0))
        assertNull(formatRemaining(positionSeconds = 12.0, durationSeconds = Double.NaN))
    }

    @Test
    fun progressIsAFractionAndNeverNaN() {
        assertEquals(0.5f, progressFraction(50.0, 100.0))
        assertEquals(0f, progressFraction(50.0, null))
        assertEquals(0f, progressFraction(50.0, 0.0))
        assertEquals(0f, progressFraction(Double.NaN, 100.0))
    }

    @Test
    fun delayCarriesItsSignAndUnit() {
        assertEquals("0 ms", formatDelay(0.0))
        assertEquals("+150 ms", formatDelay(0.15))
        assertEquals("−50 ms", formatDelay(-0.05))
        assertEquals("+5000 ms", formatDelay(5.0))
    }

    @Test
    fun delayUsesAMinusSignRatherThanAHyphen() {
        // The stepper draws it in a monospaced column beside a plus, where a
        // hyphen sits at the wrong height and the wrong width.
        assertEquals('−', formatDelay(-0.05).first())
    }

    @Test
    fun scaleReadsAsAWholePercentage() {
        assertEquals("100%", formatScalePercent(1.0))
        assertEquals("50%", formatScalePercent(0.5))
        assertEquals("200%", formatScalePercent(2.0))
        assertEquals("125%", formatScalePercent(1.25))
        assertEquals("100%", formatScalePercent(Double.NaN))
    }

    @Test
    fun wholeRatesDropTheirDecimal() {
        assertEquals("1×", formatRate(1.0))
        assertEquals("2×", formatRate(2.0))
        assertEquals("0.5×", formatRate(0.5))
        assertEquals("0.75×", formatRate(0.75))
        assertEquals("1.25×", formatRate(1.25))
        assertEquals("1.5×", formatRate(1.5))
    }

    @Test
    fun throughputStepsUnitsAndKeepsOneDecimalOnlyWhereItFits() {
        assertEquals("0 B/s", formatThroughput(0))
        assertEquals("900 B/s", formatThroughput(900))
        assertEquals("1 KB/s", formatThroughput(1_024))
        assertEquals("820 KB/s", formatThroughput(839_680))
        assertEquals("1.8 MB/s", formatThroughput(1_887_437))
        // Past 10 MB/s the decimal is dropped so the pill stops changing width
        // while the figure jitters.
        assertEquals("24 MB/s", formatThroughput(25_165_824))
    }

    @Test
    fun throughputAndCacheDepthAreAbsentRatherThanZeroWhenUnreported() {
        // A host that cannot report these must not be made to say "0 B/s",
        // which describes a stall that is downloading nothing.
        assertNull(formatThroughput(null))
        assertNull(formatThroughput(-1))
        assertNull(formatCachedAhead(null))
        assertNull(formatCachedAhead(Double.NaN))
        assertNull(formatCachedAhead(-2.0))
    }

    @Test
    fun cacheDepthReadsInSecondsThenMinutes() {
        assertEquals("0 s cached", formatCachedAhead(0.0))
        assertEquals("12 s cached", formatCachedAhead(12.4))
        assertEquals("59 s cached", formatCachedAhead(59.0))
        assertEquals("2 min cached", formatCachedAhead(94.0))
    }

    @Test
    fun delaysAreClampedToWhatTheStepperCanExpress() {
        assertEquals(MaxDelaySeconds, clampedDelay(9.0))
        assertEquals(-MaxDelaySeconds, clampedDelay(-9.0))
        assertEquals(0.25, clampedDelay(0.25))
        // Not a shift anyone asked for, so it means no shift at all.
        assertEquals(0.0, clampedDelay(Double.NaN))
    }

    @Test
    fun progressIsClampedWhenPositionOverrunsDuration() {
        // Live edges and rounding both produce a position past the reported
        // duration; a fraction over 1 would draw the played fill past the track.
        assertEquals(1f, progressFraction(120.0, 100.0))
    }
}
