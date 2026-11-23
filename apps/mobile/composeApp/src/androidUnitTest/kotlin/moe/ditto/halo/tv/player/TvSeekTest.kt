package moe.ditto.halo.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvSeekTest {
    @Test
    fun aTapSeeksTenSecondsAndAHoldGrowsTheStep() {
        assertEquals(10.0, TvSeekSteps.stepFor(0))
        assertEquals(10.0, TvSeekSteps.stepFor(7))
        assertEquals(30.0, TvSeekSteps.stepFor(8))
        assertEquals(30.0, TvSeekSteps.stepFor(23))
        assertEquals(60.0, TvSeekSteps.stepFor(24))
        assertEquals(60.0, TvSeekSteps.stepFor(500))
    }

    @Test
    fun pressesBuildOneTargetFromThePlayhead() {
        val seek = TvSeekTarget()
        assertEquals(110.0, seek.nudge(position = 100.0, delta = 10.0, duration = 600.0))
        // The playhead has moved meanwhile; the target keeps building from itself.
        assertEquals(140.0, seek.nudge(position = 101.0, delta = 30.0, duration = 600.0))
        assertEquals(130.0, seek.nudge(position = 102.0, delta = -10.0, duration = 600.0))
        assertEquals(130.0, seek.commit())
        assertNull(seek.target)
        assertNull(seek.commit())
    }

    @Test
    fun theTargetStaysInsideTheVideo() {
        val seek = TvSeekTarget()
        assertEquals(0.0, seek.nudge(position = 5.0, delta = -60.0, duration = 600.0))
        seek.cancel()
        assertEquals(600.0, seek.nudge(position = 590.0, delta = 60.0, duration = 600.0))
        seek.cancel()
        // A stream of unknown length has no end to stop at.
        assertEquals(650.0, seek.nudge(position = 590.0, delta = 60.0, duration = null))
    }
}
