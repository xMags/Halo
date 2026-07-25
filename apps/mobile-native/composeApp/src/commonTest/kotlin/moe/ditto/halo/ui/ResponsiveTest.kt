package moe.ditto.halo.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponsiveTest {
    @Test
    fun classifiesBoundaryShortestEdges() {
        assertEquals(DeviceClass.Phone, classifyWindow(599.dp, 1_024.dp).deviceClass)
        assertEquals(DeviceClass.Tablet, classifyWindow(600.dp, 1_024.dp).deviceClass)
        assertEquals(DeviceClass.Tablet, classifyWindow(767.dp, 1_024.dp).deviceClass)
        assertEquals(DeviceClass.LargeTablet, classifyWindow(768.dp, 1_024.dp).deviceClass)
    }

    @Test
    fun rotationDoesNotChangeDeviceClass() {
        assertEquals(
            classifyWindow(834.dp, 1_194.dp).deviceClass,
            classifyWindow(1_194.dp, 834.dp).deviceClass,
        )
    }

    @Test
    fun loginWidthUsesTheTabletReadingCap() {
        assertEquals(480.dp, HaloDimensions.LoginMaxWidth)
    }

    @Test
    fun rejectsNegativeDimensions() {
        assertFailsWith<IllegalArgumentException> { classifyWindow((-1).dp, 800.dp) }
    }

    @Test
    fun posterColumnsStepAtWidthBoundaries() {
        assertEquals(3, classifyWindow(599.dp, 1_024.dp).posterColumns)
        assertEquals(4, classifyWindow(600.dp, 1_024.dp).posterColumns)
        assertEquals(4, classifyWindow(819.dp, 1_024.dp).posterColumns)
        assertEquals(5, classifyWindow(820.dp, 1_024.dp).posterColumns)
        assertEquals(5, classifyWindow(1_099.dp, 1_024.dp).posterColumns)
        assertEquals(6, classifyWindow(1_100.dp, 1_024.dp).posterColumns)
        assertEquals(6, classifyWindow(1_399.dp, 1_024.dp).posterColumns)
        assertEquals(7, classifyWindow(1_400.dp, 1_024.dp).posterColumns)
    }

    /**
     * The load-bearing asymmetry: device class is rotation-invariant so a
     * landscape phone never presents as a tablet, but column count tracks the
     * live width so the same phone does pack more posters on its side.
     */
    @Test
    fun rotationChangesColumnsButNotDeviceClass() {
        val portrait = classifyWindow(390.dp, 844.dp)
        val landscape = classifyWindow(844.dp, 390.dp)

        assertEquals(DeviceClass.Phone, portrait.deviceClass)
        assertEquals(DeviceClass.Phone, landscape.deviceClass)
        assertEquals(3, portrait.posterColumns)
        assertEquals(5, landscape.posterColumns)
        assertTrue(landscape.isLandscape)
        assertTrue(!portrait.isLandscape)
    }

    @Test
    fun contentIsFullBleedOnPhoneAndCappedOnTablet() {
        assertNull(classifyWindow(390.dp, 844.dp).contentMaxWidth)
        assertEquals(700.dp, classifyWindow(834.dp, 1_194.dp).contentMaxWidth)
    }

    @Test
    fun pickFallsBackToTheTabletValueForLargeTablets() {
        val largeTablet = classifyWindow(1_024.dp, 1_366.dp)

        assertEquals("large", largeTablet.pick(phone = "phone", tablet = "tablet", large = "large"))
        assertEquals("tablet", largeTablet.pick(phone = "phone", tablet = "tablet"))
    }

    @Test
    fun gridItemWidthSubtractsGuttersAndFloors() {
        // 390 - (16 * 2) - (11 * 2) = 336, evenly divided across three columns.
        assertEquals(112.dp, gridItemWidth(390.dp, columns = 3, horizontalPadding = 16.dp, gap = 11.dp))
        // 1024 - 32 - 44 = 948; 948 / 5 = 189.6 truncates rather than overflowing the row.
        assertEquals(189.dp, gridItemWidth(1_024.dp, columns = 5, horizontalPadding = 16.dp, gap = 11.dp))
    }

    @Test
    fun gridItemWidthRejectsAColumnlessGrid() {
        assertFailsWith<IllegalArgumentException> {
            gridItemWidth(390.dp, columns = 0, horizontalPadding = 16.dp, gap = 11.dp)
        }
    }
}
