package moe.ditto.halo.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which section the wide Settings rail lights as the form scrolls. */
class SettingsRailTest {

    private val anchor = 200f

    @Test
    fun theLastSectionToReachTheAnchorIsLit() {
        assertEquals(0, activeSettingsSection(listOf(0f, 500f, 900f), anchor, atEnd = false))
        assertEquals(1, activeSettingsSection(listOf(-600f, 150f, 700f), anchor, atEnd = false))
        assertEquals(1, activeSettingsSection(listOf(-600f, 200f, 700f), anchor, atEnd = false))
    }

    @Test
    fun aSectionScrolledPastStaysLitUntilTheNextArrives() {
        assertEquals(1, activeSettingsSection(listOf(Float.NEGATIVE_INFINITY, -50f, 260f), anchor, atEnd = false))
    }

    @Test
    fun theEndOfTheFormLightsTheLastSection() {
        // The last section is short and never reaches the anchor.
        assertEquals(2, activeSettingsSection(listOf(-900f, -100f, 600f), anchor, atEnd = true))
    }

    @Test
    fun noSectionsLightTheFirstEntry() {
        assertEquals(0, activeSettingsSection(emptyList(), anchor, atEnd = false))
    }
}
