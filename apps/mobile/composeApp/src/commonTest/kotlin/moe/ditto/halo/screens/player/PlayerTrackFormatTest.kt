package moe.ditto.halo.screens.player

import moe.ditto.halo.player.PlayerTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerTrackFormatTest {
    @Test
    fun mapsSubtitleCodecsToTheDesignBadges() {
        assertEquals(SubtitleFormat.Ass, subtitleFormat("ass"))
        assertEquals("ASS", subtitleBadge("ass"))
        assertEquals(SubtitleFormat.Text, subtitleFormat("subrip"))
        assertEquals("SRT", subtitleBadge("subrip"))
        assertEquals(SubtitleFormat.Bitmap, subtitleFormat("hdmv_pgs_subtitle"))
        assertEquals("PGS", subtitleBadge("hdmv_pgs_subtitle"))
    }

    @Test
    fun unknownSubtitleCodecsStayUnknownWithoutCrashing() {
        assertEquals(SubtitleFormat.Unknown, subtitleFormat(null))
        assertEquals(SubtitleFormat.Unknown, subtitleFormat("future-text-codec"))
        assertEquals(null, subtitleBadge("future-text-codec"))
    }

    @Test
    fun formatsAudioDetailsFromCodecChannelsAndSampleRate() {
        val track = PlayerTrack(
            id = "1",
            label = "English",
            language = "eng",
            codec = "eac3",
            channels = 6,
            sampleRateHz = 48_000,
        )

        assertEquals("EAC3 5.1", audioBadge(track))
        assertEquals("eng · 48 kHz", audioDetail(track))
    }

    @Test
    fun formatsAudioChannelLayoutsAndMissingValues() {
        assertEquals("AAC stereo", audioBadge(PlayerTrack("1", "English", codec = "aac", channels = 2)))
        assertEquals("AAC mono", audioBadge(PlayerTrack("1", "English", codec = "aac", channels = 1)))
        assertEquals("AAC 7.1", audioBadge(PlayerTrack("1", "English", codec = "aac", channels = 8)))
        assertEquals("AAC 10 ch", audioBadge(PlayerTrack("1", "English", codec = "aac", channels = 10)))
        assertEquals("AAC", audioBadge(PlayerTrack("1", "English", codec = "aac")))
        assertEquals(null, audioDetail(PlayerTrack("1", "English")))
    }

    @Test
    fun onlyAFontTheAppCannotSupplyIsCalledOut() {
        val bundled = setOf("JetBrains Mono")

        // The ordinary cases say nothing: no choice at all, and a choice that
        // is honoured.
        assertEquals(null, unbundledFontNotice(null, bundled))
        assertEquals(null, unbundledFontNotice("JetBrains Mono", bundled))

        assertEquals(
            "Inter is not bundled, so captions use a substitute typeface.",
            unbundledFontNotice("Inter", bundled),
        )
        // A platform that ships nothing cannot honour any name.
        assertEquals(
            "JetBrains Mono is not bundled, so captions use a substitute typeface.",
            unbundledFontNotice("JetBrains Mono", emptySet()),
        )
    }

    @Test
    fun everySubtitleChipMapsToItsComposePreviewFamily() {
        assertEquals(
            listOf(
                SubtitlePreviewFamily.Default,
                SubtitlePreviewFamily.Inter,
                SubtitlePreviewFamily.SourceSerif4,
                SubtitlePreviewFamily.JetBrainsMono,
            ),
            SubtitleFontChoices.map { subtitlePreviewFamily(it.family) },
        )
    }

    @Test
    fun subtitleDetailCombinesLanguageAndFormat() {
        assertEquals(
            "eng · ASS",
            subtitleDetail(PlayerTrack("1", "Signs", language = "eng", codec = "ass")),
        )
        assertEquals("jpn · PGS", subtitleDetail(PlayerTrack("2", "Japanese", language = "jpn", codec = "pgs")))
        assertEquals("eng", subtitleDetail(PlayerTrack("3", "English", language = "eng")))
    }
}
