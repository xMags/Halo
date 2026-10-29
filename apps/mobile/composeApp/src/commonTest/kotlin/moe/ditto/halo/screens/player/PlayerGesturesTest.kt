package moe.ditto.halo.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun whichThirdTheGestureStartedOnDecidesWhatItChanges() {
        fun at(x: Float, y: Float = 500f) = verticalDragTarget(startX = x, startY = y, widthPx = 900f, heightPx = 1_000f)

        assertEquals(VerticalDragTarget.Brightness, at(10f))
        assertEquals(VerticalDragTarget.Brightness, at(300f))
        // The middle third adjusts nothing.
        assertNull(at(301f))
        assertNull(at(450f))
        assertNull(at(599f))
        assertEquals(VerticalDragTarget.Volume, at(600f))
        assertEquals(VerticalDragTarget.Volume, at(890f))
        // An unmeasured surface has no zones; brightness is the safer default,
        // since it changes nothing outside this window.
        assertEquals(VerticalDragTarget.Brightness, verticalDragTarget(startX = 10f, startY = 0f, widthPx = 0f, heightPx = 0f))
    }

    @Test
    fun theTopAndBottomBandsAdjustNothing() {
        fun at(y: Float) = verticalDragTarget(startX = 10f, startY = y, widthPx = 900f, heightPx = 1_000f)

        assertNull(at(0f))
        assertNull(at(149f))
        assertEquals(VerticalDragTarget.Brightness, at(150f))
        assertEquals(VerticalDragTarget.Brightness, at(850f))
        assertNull(at(851f))
        assertNull(at(999f))
    }

    @Test
    fun oneFingerDragStartsPastTheSlopAndCountsFromItsEdge() {
        val drag = OneFingerVerticalDrag(slopPx = 10f)
        assertEquals(OneFingerDragStep.None, drag.advance(1, pressed = true, y = 108f, downY = 100f, claimed = false))
        assertEquals(OneFingerDragStep.Start(5f), drag.advance(1, pressed = true, y = 115f, downY = 100f, claimed = false))
        assertEquals(OneFingerDragStep.Move(-20f), drag.advance(1, pressed = true, y = 90f, downY = 100f, claimed = false))
        assertEquals(OneFingerDragStep.End, drag.advance(0, pressed = false, y = 90f, downY = 100f, claimed = false))
    }

    @Test
    fun aPinchIsNeverABrightnessOrVolumeDrag() {
        // Both fingers down before the first one has moved far: not a drag at
        // all, however far the fingers then travel.
        val pinch = OneFingerVerticalDrag(slopPx = 10f)
        assertEquals(OneFingerDragStep.None, pinch.advance(2, pressed = true, y = 104f, downY = 100f, claimed = false))
        assertEquals(OneFingerDragStep.None, pinch.advance(1, pressed = true, y = 300f, downY = 100f, claimed = false))
        assertTrue(pinch.isOver)

        // The second finger lands after the drag started: the drag is undone.
        val late = OneFingerVerticalDrag(slopPx = 10f)
        assertEquals(OneFingerDragStep.Start(30f), late.advance(1, pressed = true, y = 140f, downY = 100f, claimed = false))
        assertEquals(OneFingerDragStep.Cancel, late.advance(2, pressed = true, y = 160f, downY = 100f, claimed = false))
        assertEquals(OneFingerDragStep.None, late.advance(1, pressed = true, y = 400f, downY = 100f, claimed = false))
    }

    @Test
    fun aTouchClaimedElsewhereCancelsTheDrag() {
        val drag = OneFingerVerticalDrag(slopPx = 10f)
        drag.advance(1, pressed = true, y = 140f, downY = 100f, claimed = false)
        assertEquals(OneFingerDragStep.Cancel, drag.advance(1, pressed = true, y = 150f, downY = 100f, claimed = true))
        assertFalse(drag.isDragging)
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
