package moe.ditto.halo.tv.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSelectPressTest {
    @Test
    fun aShortPressClicksOnRelease() {
        val press = TvSelectPress()
        assertFalse(press.onDown(repeatCount = 0, longPress = false))
        assertTrue(press.onUp())
    }

    @Test
    fun aHoldOpensTheMenuOnceAndDoesNotClick() {
        val press = TvSelectPress()
        press.onDown(repeatCount = 0, longPress = false)
        assertTrue(press.onDown(repeatCount = 1, longPress = true))
        assertFalse(press.onDown(repeatCount = 2, longPress = false))
        assertFalse(press.onUp())
    }

    @Test
    fun aReleaseWithoutItsPressDoesNothing() {
        // The press that opened this screen ends here; it must not click.
        val press = TvSelectPress()
        assertFalse(press.onUp())
        assertFalse(press.onDown(repeatCount = 3, longPress = true))
        assertFalse(press.onUp())
    }

    @Test
    fun losingFocusMidPressForgetsIt() {
        val press = TvSelectPress()
        press.onDown(repeatCount = 0, longPress = false)
        press.reset()
        assertFalse(press.onUp())
    }
}

class TvPivotBringIntoViewSpecTest {
    private val spec = TvPivotBringIntoViewSpec(pivotPx = 100f)

    @Test
    fun theFocusedItemsStartLandsOnThePivot() {
        assertEquals(400f, spec.calculateScrollDistance(offset = 500f, size = 120f, containerSize = 1000f))
        assertEquals(-60f, spec.calculateScrollDistance(offset = 40f, size = 120f, containerSize = 1000f))
    }

    @Test
    fun aPageLeavesAnItemAlreadyInViewWhereItIs() {
        val page = TvPivotBringIntoViewSpec(pivotPx = 100f, stayWhenVisible = true)
        assertEquals(0f, page.calculateScrollDistance(offset = 300f, size = 120f, containerSize = 1000f))
        assertEquals(850f, page.calculateScrollDistance(offset = 950f, size = 120f, containerSize = 1000f))
        assertEquals(-160f, page.calculateScrollDistance(offset = -60f, size = 120f, containerSize = 1000f))
    }

    @Test
    fun anItemWiderThanTheListShowsItsStart() {
        assertEquals(500f, spec.calculateScrollDistance(offset = 500f, size = 1200f, containerSize = 1000f))
    }
}
