package moe.ditto.halo.screens.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which touches belong to the system. The bands are the ones a landscape phone
 * with gesture navigation reports: back on both sides (wider on the camera's
 * side), home along the bottom and the shade along the top.
 */
class SystemEdgeTouchesTest {

    private val window = Size(3_120f, 1_440f)
    private val edges = SystemGestureEdges(left = 240f, top = 113f, right = 112f, bottom = 120f)

    @Test
    fun aTouchInAnyBandIsTheSystems() {
        assertTrue(edges.contain(Offset(100f, 700f), window), "back, left")
        assertTrue(edges.contain(Offset(3_050f, 700f), window), "back, right")
        assertTrue(edges.contain(Offset(1_500f, 1_400f), window), "home")
        assertTrue(edges.contain(Offset(1_500f, 50f), window), "shade")
    }

    @Test
    fun aTouchInsideTheBandsIsThePlayers() {
        assertFalse(edges.contain(Offset(1_500f, 700f), window))
        // Just past each band.
        assertFalse(edges.contain(Offset(240f, 700f), window))
        assertFalse(edges.contain(Offset(3_007f, 700f), window))
        assertFalse(edges.contain(Offset(1_500f, 1_319f), window))
        assertFalse(edges.contain(Offset(1_500f, 113f), window))
    }

    @Test
    fun noBandsOrNoWindowClaimNothing() {
        assertFalse(SystemGestureEdges.None.contain(Offset(0f, 0f), window))
        assertFalse(SystemGestureEdges.None.contain(Offset(3_119f, 1_439f), window))
        // Before the first layout there is no window to measure against.
        assertFalse(edges.contain(Offset(10f, 10f), Size.Zero))
    }
}
