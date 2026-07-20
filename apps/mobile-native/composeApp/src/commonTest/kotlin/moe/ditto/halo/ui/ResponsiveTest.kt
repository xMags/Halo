package moe.ditto.halo.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResponsiveTest {
    @Test
    fun classifiesBoundaryShortestEdges() {
        assertEquals(DeviceClass.Phone, classifyWindow(599f, 1_024f).deviceClass)
        assertEquals(DeviceClass.Tablet, classifyWindow(600f, 1_024f).deviceClass)
        assertEquals(DeviceClass.Tablet, classifyWindow(767f, 1_024f).deviceClass)
        assertEquals(DeviceClass.LargeTablet, classifyWindow(768f, 1_024f).deviceClass)
    }

    @Test
    fun rotationDoesNotChangeDeviceClass() {
        assertEquals(
            classifyWindow(834f, 1_194f).deviceClass,
            classifyWindow(1_194f, 834f).deviceClass,
        )
    }

    @Test
    fun loginWidthUsesTheTabletReadingCap() {
        assertEquals(480.dp, HaloDimensions.LoginMaxWidth)
    }

    @Test
    fun rejectsNegativeDimensions() {
        assertFailsWith<IllegalArgumentException> { classifyWindow(-1f, 800f) }
    }
}
