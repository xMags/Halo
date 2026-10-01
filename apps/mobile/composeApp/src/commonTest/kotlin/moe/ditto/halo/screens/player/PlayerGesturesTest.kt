package moe.ditto.halo.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The drag rules, which decide how much of a screen a viewer has to cross to
 * change something and how many calls that costs the system on the way.
 */
class PlayerGesturesTest {

    @Test
    fun sevenTenthsOfTheHeightCoversTheWholeRange() {
        // 1000px tall, so 700px of travel is the full sweep. Starting at the
        // bottom of the range, dragging up by all of it reaches the top.
        val adjustment = VerticalDragAdjustment(baseline = 0f, heightPx = 1_000f)

        assertEquals(1f, adjustment.advance(-700f))
    }

    @Test
    fun draggingUpIncreasesAndDownDecreases() {
        val up = VerticalDragAdjustment(baseline = 0.5f, heightPx = 1_000f)
        assertEquals(0.7f, up.advance(-140f))

        val down = VerticalDragAdjustment(baseline = 0.5f, heightPx = 1_000f)
        assertEquals(0.3f, down.advance(140f))
    }

    @Test
    fun onlyWholePercentsAreReported() {
        val adjustment = VerticalDragAdjustment(baseline = 0.5f, heightPx = 1_000f)

        // Seven pixels is one percent of the 700px sweep; anything less is a
        // frame of movement nobody could see and no call worth making.
        assertNull(adjustment.advance(-1f))
        assertNull(adjustment.advance(-3f))
        assertEquals(0.51f, adjustment.advance(-7f))
        assertNull(adjustment.advance(-8f))
        assertEquals(0.52f, adjustment.advance(-11f))
    }

    @Test
    fun theRangeIsClampedRatherThanOverrun() {
        val adjustment = VerticalDragAdjustment(baseline = 0.9f, heightPx = 1_000f)

        assertEquals(1f, adjustment.advance(-500f))
        // Still off the end: no further change, so no further calls.
        assertNull(adjustment.advance(-900f))
    }

    @Test
    fun aGestureThatNeverMovesChangesNothing() {
        val adjustment = VerticalDragAdjustment(baseline = 0.42f, heightPx = 1_000f)

        assertNull(adjustment.advance(0f))
    }

    @Test
    fun anUnmeasuredSurfaceIsDeclinedRatherThanDividedBy() {
        val adjustment = VerticalDragAdjustment(baseline = 0.5f, heightPx = 0f)

        assertNull(adjustment.advance(-100f))
    }

    @Test
    fun aPinchDecidesOnceAndOnlyPastTheThreshold() {
        val pinch = PinchCommit()

        // Small movement is a hand settling on the screen, not a decision.
        assertNull(pinch.advance(1.05f))
        assertNull(pinch.advance(1.03f))
        // 1.05 * 1.03 * 1.05 is past 1.12, so this is the one that decides.
        assertEquals(true, pinch.advance(1.05f))
        // And it decides once: a pinch that keeps going does not keep flipping.
        assertNull(pinch.advance(2f))
        assertNull(pinch.advance(0.2f))
    }

    @Test
    fun pinchingInwardsFitsThePictureInsteadOfFillingTheScreen() {
        val pinch = PinchCommit()

        assertNull(pinch.advance(0.95f))
        assertEquals(false, pinch.advance(0.9f))
    }

    @Test
    fun aPinchThatReportsNothingUsableIsIgnored() {
        val pinch = PinchCommit()

        assertNull(pinch.advance(Float.NaN))
        assertNull(pinch.advance(0f))
        assertNull(pinch.advance(-1f))
        // Still undecided, so a real pinch afterwards still works.
        assertEquals(true, pinch.advance(1.2f))
    }

    @Test
    fun whichHalfTheGestureStartedOnDecidesWhatItChanges() {
        assertEquals(VerticalDragTarget.Brightness, verticalDragTarget(startX = 10f, widthPx = 1_000f))
        assertEquals(VerticalDragTarget.Brightness, verticalDragTarget(startX = 500f, widthPx = 1_000f))
        assertEquals(VerticalDragTarget.Volume, verticalDragTarget(startX = 501f, widthPx = 1_000f))
        // An unmeasured surface has no halves; brightness is the safer default,
        // since it changes nothing outside this window.
        assertEquals(VerticalDragTarget.Brightness, verticalDragTarget(startX = 10f, widthPx = 0f))
    }

    @Test
    fun exactlyTwoHundredEightyMillisecondsIsADoubleTap() {
        val taps = PlayerTapRecognizer()

        assertEquals(emptyList(), taps.record(atMillis = 1_000, onRightHalf = false))
        assertEquals(
            listOf(PlayerTapDecision.DoubleRight),
            taps.record(atMillis = 1_280, onRightHalf = true),
        )
        assertEquals(false, taps.hasPendingTap)
    }

    @Test
    fun laterTapsBecomeSeparateSingles() {
        val taps = PlayerTapRecognizer()

        taps.record(atMillis = 1_000, onRightHalf = false)
        assertEquals(
            listOf(PlayerTapDecision.Single),
            taps.record(atMillis = 1_281, onRightHalf = true),
        )
        assertEquals(emptyList(), taps.expire(atMillis = 1_561))
        assertEquals(listOf(PlayerTapDecision.Single), taps.expire(atMillis = 1_562))
    }
}
