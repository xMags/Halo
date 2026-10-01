package moe.ditto.halo.screens.player

import moe.ditto.halo.api.SubtitleOutline
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.player.SubtitleStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The settings document is shared with the desktop client and stored with
 * unknown fields intact, so anything read out of it is another program's
 * output, not this one's. These cases are the readings that would otherwise
 * reach the renderer as a caption nobody can see.
 */
class PlayerSubtitleStyleTest {

    @Test
    fun emptySettingsProduceTheRenderersOwnAppearance() {
        val style = subtitleStyleOf(UserSettings.Empty)

        assertEquals(1.0, style.scale)
        assertNull(style.font)
        assertEquals(SubtitleStyle.DefaultOutlineWidthPixels, style.outlineWidthPixels)
        assertEquals(0.0, style.shadowOffsetPixels)
    }

    @Test
    fun storedPreferencesAreTranslatedIntoRendererUnits() {
        val settings = UserSettings.Empty
            .withSubtitleScalePercent(150)
            .withSubtitleFontFamily("JetBrains Mono")
            .withSubtitleOutline(SubtitleOutline.Thin)
            .withSubtitleShadow(true)

        val style = subtitleStyleOf(settings)

        assertEquals(1.5, style.scale)
        assertEquals("JetBrains Mono", style.font)
        assertEquals(1.0, style.outlineWidthPixels)
        assertEquals(2.0, style.shadowOffsetPixels)
    }

    @Test
    fun outOfRangeScalesAreClampedToWhatTheSliderCanExpress() {
        assertEquals(2.0, subtitleStyleOf(UserSettings.Empty.withSubtitleScalePercent(4_000)).scale)
        assertEquals(0.5, subtitleStyleOf(UserSettings.Empty.withSubtitleScalePercent(-30)).scale)
    }

    @Test
    fun aBlankFontFamilyIsNoChoiceRatherThanAFontNamedNothing() {
        assertNull(subtitleStyleOf(UserSettings.Empty.withSubtitleFontFamily("   ")).font)
    }

    @Test
    fun noOutlineAndAnUnsetOutlineAreDifferentThings() {
        // "None" is a decision to draw no outline; absent means nobody decided,
        // which has to leave the renderer's own weight alone.
        assertEquals(0.0, outlineWidthPixels(SubtitleOutline.None))
        assertEquals(SubtitleStyle.DefaultOutlineWidthPixels, outlineWidthPixels(null))
    }

    @Test
    fun writeBackTouchesOnlyTheTwoTheRailCanEdit() {
        val stored = UserSettings.Empty
            .withSubtitleOutline(SubtitleOutline.Thick)
            .withSubtitleShadow(true)
            .withPreferredSubtitleLang("jpn")

        val written = stored.withSubtitlePreference(SubtitlePreference(scale = 1.25, font = null))

        assertEquals(125, written.subtitleScalePercent)
        assertNull(written.subtitleFontFamily)
        // Everything the player never showed has to survive its write.
        assertEquals(SubtitleOutline.Thick, written.subtitleOutline)
        assertEquals(true, written.subtitleShadow)
        assertEquals("jpn", written.preferredSubtitleLang)
    }
}
