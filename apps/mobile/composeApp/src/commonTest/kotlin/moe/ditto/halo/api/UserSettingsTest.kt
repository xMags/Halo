package moe.ditto.halo.api

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserSettingsTest {
    @Test
    fun readsKnownPreferences() {
        val payload = HaloJson.decodeFromString<SettingsPayload>(
            """
            {"value":{"preferredAudioLang":"eng","subtitleScalePercent":140,
            "videoFitMode":"contain","subtitleOutline":"thick","subtitleShadow":false,
            "playbackRate":1.5,"autoplayNextEpisode":true},"updatedAt":1730000000000}
            """.trimIndent(),
        )

        val settings = payload.value
        assertEquals(1730000000000L, payload.updatedAt)
        assertEquals("eng", settings.preferredAudioLang)
        assertEquals(140, settings.subtitleScalePercent)
        assertEquals(VideoFitMode.Contain, settings.videoFitMode)
        assertEquals(SubtitleOutline.Thick, settings.subtitleOutline)
        assertEquals(false, settings.subtitleShadow)
        assertEquals(1.5, settings.playbackRate)
        assertEquals(true, settings.autoplayNextEpisode)
        assertNull(settings.preferredSubtitleLang)
    }

    @Test
    fun preservesUnknownPreferencesAcrossReadPatchWrite() {
        // The server stores this blob with unknown keys intact so an older
        // client cannot delete a newer one's settings. Dropping them here
        // would defeat that from the client side instead.
        val stored = """{"value":{"preferredAudioLang":"eng","desktopOnlySetting":"keep-me",
            "futureNested":{"a":1}},"updatedAt":1730000000000}""".trimIndent()

        val decoded = HaloJson.decodeFromString<SettingsPayload>(stored)
        val patched = decoded.copy(value = decoded.value.withSubtitleScalePercent(160))
        val encoded = HaloJson.encodeToString(patched)

        assertContains(encoded, "\"desktopOnlySetting\":\"keep-me\"")
        assertContains(encoded, "\"futureNested\":{\"a\":1}")
        assertContains(encoded, "\"subtitleScalePercent\":160")
        assertContains(encoded, "\"preferredAudioLang\":\"eng\"")
    }

    @Test
    fun clearingAPreferenceRemovesItsKeyOnly() {
        val settings = UserSettings.Empty
            .withPreferredAudioLang("eng")
            .withSubtitleFontFamily("Inter")

        val cleared = settings.withPreferredAudioLang(null)

        assertNull(cleared.preferredAudioLang)
        assertTrue("preferredAudioLang" !in cleared.raw)
        assertEquals("Inter", cleared.subtitleFontFamily)
    }

    @Test
    fun patchesAreIndependentOfEachOther() {
        // Two rapid toggles must build on each other rather than one dropping
        // the other's field.
        val merged = UserSettings.Empty
            .withSubtitleShadow(true)
            .withPlaybackRate(2.0)

        assertEquals(true, merged.subtitleShadow)
        assertEquals(2.0, merged.playbackRate)
    }

    @Test
    fun readsWronglyTypedValuesAsAbsent() {
        // A value of an unexpected type falls back to the caller's default
        // instead of failing the whole settings load.
        val settings = HaloJson.decodeFromString<SettingsPayload>(
            """{"value":{"subtitleScalePercent":"very big","subtitleShadow":{"nested":true}},
               "updatedAt":1}""".trimIndent(),
        ).value

        assertNull(settings.subtitleScalePercent)
        assertNull(settings.subtitleShadow)
    }

    @Test
    fun readsUnknownEnumValuesAsAbsent() {
        val settings = HaloJson.decodeFromString<SettingsPayload>(
            """{"value":{"videoFitMode":"stretch","subtitleOutline":"extra-thick"},"updatedAt":1}""",
        ).value

        assertNull(settings.videoFitMode)
        assertNull(settings.subtitleOutline)
    }

    @Test
    fun missingSettingsBlobReadsAsEmpty() {
        // A user who has never saved settings gets {"value":{},"updatedAt":0}.
        val payload = HaloJson.decodeFromString<SettingsPayload>("""{"value":{},"updatedAt":0}""")

        assertEquals(UserSettings.Empty, payload.value)
        assertNull(payload.value.playbackRate)
    }
}
