package moe.ditto.halo.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvFrameRateTest {
    private fun mode(id: Int, refresh: Float, width: Int = 3840, height: Int = 2160) =
        DisplayModeOption(id, width, height, refresh)

    /** A typical 4K TV: the film rates, the PAL rates and the NTSC ones. */
    private val tv = listOf(
        mode(1, 23.976f), mode(2, 24f), mode(3, 25f), mode(4, 29.97f), mode(5, 30f),
        mode(6, 50f), mode(7, 59.94f), mode(8, 60f),
        mode(9, 60f, width = 1920, height = 1080), mode(10, 24f, width = 1920, height = 1080),
    )
    private val at60 = tv.first { it.id == 8 }

    @Test
    fun filmGetsItsOwnRate() {
        assertEquals(1, pickDisplayMode(tv, at60, 23.976)?.id)
        assertEquals(2, pickDisplayMode(tv, at60, 24.0)?.id)
    }

    @Test
    fun palGetsFiftyRatherThanTwentyFive() {
        // Both show every frame evenly; fifty keeps the menus smooth.
        assertEquals(6, pickDisplayMode(tv, at60, 25.0)?.id)
        assertEquals(6, pickDisplayMode(tv, at60, 50.0)?.id)
    }

    @Test
    fun aModeThatAlreadyFitsIsKept() {
        // Thirty on sixty: each frame twice, so no switch and no blank screen.
        assertEquals(8, pickDisplayMode(tv, at60, 30.0)?.id)
        assertEquals(7, pickDisplayMode(tv, at60.copy(id = 7, refreshRate = 59.94f), 29.97)?.id)
        assertEquals(7, pickDisplayMode(tv, at60, 59.94)?.id)
    }

    @Test
    fun theResolutionStaysAsItIs() {
        val fullHd = tv.first { it.id == 9 }
        assertEquals(10, pickDisplayMode(tv, fullHd, 24.0)?.id)
        // No 23.976 at 1080p here: 24 is close enough.
        assertEquals(10, pickDisplayMode(tv, fullHd, 23.976)?.id)
    }

    @Test
    fun aDisplayWithOneModeHasNothingBetter() {
        val single = listOf(mode(1, 60f))
        assertEquals(1, pickDisplayMode(single, single[0], 30.0)?.id)
        assertNull(pickDisplayMode(single, single[0], 23.976))
    }

    @Test
    fun anUnknownRateChangesNothing() {
        assertNull(pickDisplayMode(tv, at60, 0.0))
        assertNull(pickDisplayMode(tv, at60, Double.NaN))
    }
}
