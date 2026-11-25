package moe.ditto.halo.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonOrderTest {
    private val list = listOf("a", "b", "c")

    @Test
    fun movesOnePlaceEarlierOrLater() {
        assertEquals(listOf("b", "a", "c"), addonMoved(list, "b", earlier = true))
        assertEquals(listOf("a", "c", "b"), addonMoved(list, "b", earlier = false))
    }

    @Test
    fun staysPutAtEitherEnd() {
        assertEquals(list, addonMoved(list, "a", earlier = true))
        assertEquals(list, addonMoved(list, "c", earlier = false))
    }

    @Test
    fun anAddonNoLongerListedChangesNothing() {
        assertEquals(list, addonMoved(list, "gone", earlier = true))
    }
}
