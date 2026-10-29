package moe.ditto.halo.screens

import androidx.compose.ui.unit.dp
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

/** Where the wide Settings layout puts its rail and form. */
class WideSettingsPlacementTest {

    @Test
    fun theFormIsCentredOnTheScreenWithTheRailToItsLeft() {
        val placement = wideSettingsPlacement(screenWidth = 1691.dp, leadingInset = 80.dp)
        assertEquals(800.dp, placement.formWidth)
        // The form's middle is the screen's middle.
        assertEquals(1691.dp / 2, placement.railStart + 260.dp + 44.dp + placement.formWidth / 2)
    }

    @Test
    fun aNarrowScreenKeepsTheRailClearOfTheNavigationRail() {
        val placement = wideSettingsPlacement(screenWidth = 1200.dp, leadingInset = 80.dp)
        assertEquals(104.dp, placement.railStart)
        assertEquals(1200.dp - 104.dp - 260.dp - 44.dp - 24.dp, placement.formWidth)
    }
}
