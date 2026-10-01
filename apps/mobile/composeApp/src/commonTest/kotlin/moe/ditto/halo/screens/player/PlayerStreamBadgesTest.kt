package moe.ditto.halo.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The naming below is verbatim from real addon responses (the Torrentio payload
 * kept in `RealAddonPayloads`, and the dev fixture addon), because release
 * naming is exactly the kind of input that looks tidy until it is real.
 */
class PlayerStreamBadgesTest {

    @Test
    fun readsResolutionAndCodecFromARealTorrentioResult() {
        val badges = streamBadges(
            filename = "Game of Thrones S01E01 MULTi VFI 2160p 10bit 4KLight HDR BluRay TrueHD Atmos 7.1 x265-QTZ.mkv",
            title = "Game of Thrones - Integrale MULTI HDTV 2160p x265 2011-2019",
            name = "[TB+] Torrentio\n4k HDR",
        )

        assertEquals(listOf("2160p", "HEVC 10-bit"), badges)
    }

    @Test
    fun readsDottedReleaseNaming() {
        val badges = streamBadges(
            filename = "Game.of.Thrones.S01E01.2160p.DoVi.HDR.BluRay.REMUX.HEVC.DTS-HD.MA.TrueHD.7.1.Atmos-PB69.mkv",
            title = null,
            name = "[TB+] Torrentio\n4k DV | HDR",
        )

        assertEquals(listOf("2160p", "HEVC"), badges)
    }

    @Test
    fun readsTheFileRatherThanThePackItCameIn() {
        // The title names a 445 GB complete-series pack; the file is one episode
        // of it, and the file is what plays.
        val badges = streamBadges(
            filename = "Game of Thrones (2010) - S01E01 - Winter Is Coming (1080p BluRay x265 FreetheFish).mkv",
            title = "Game of Thrones - Stagioni 1-8 (2010-2019) [COMPLETA] (2160p x265 AAC 7.1 MULTI) [445GB]",
            name = "[TB+] Torrentio\n1080p",
        )

        assertEquals(listOf("1080p", "HEVC"), badges)
    }

    @Test
    fun readsTheDevFixtureAddonsSources() {
        assertEquals(
            listOf("2160p", "HEVC"),
            streamBadges(
                filename = "tt0944947.1.1.2160p.WEB-DL.DDP5.1.HDR.HEVC.mkv",
                title = "📺 2160p • HEVC • DDP5.1\n💾 34.2 GB • ⚡ Cached",
                name = "Fixture\n4K HDR",
            ),
        )
        // The dev fixture builds this entry's filename from its 2160p sibling's
        // and swaps only the resolution, so the filename says HEVC where the
        // title says H.264. The filename wins, by the rule above: it is the file
        // that plays. Asserting the contradiction rather than papering over it,
        // since it is the same disagreement a real season pack produces.
        assertEquals(
            listOf("1080p", "HEVC"),
            streamBadges(
                filename = "tt0944947.1.1.1080p.WEB-DL.DDP5.1.HDR.HEVC.mp4",
                title = "📺 1080p • H.264 • AAC\n💾 4.1 GB",
                name = "Fixture\n1080p",
            ),
        )
    }

    @Test
    fun fallsBackThroughTheSourcesItWasGiven() {
        // No filename hint at all, which is the common case for HTTP addons.
        assertEquals(
            listOf("1080p", "H.264"),
            streamBadges(filename = null, title = "1080p H.264 · English", name = null),
        )
        // Nothing but the name, which is all some addons send.
        assertEquals(
            listOf("720p"),
            streamBadges(filename = null, title = null, name = "Fixture 720p"),
        )
    }

    @Test
    fun namesNothingItWasNotAskedTo() {
        // The rule the design states outright: a badge is never a provider, a
        // tracker or an account. Every one of these is a real-shaped source
        // naming with no resolution or codec in it.
        listOf(
            "[TB+] Torrentio",
            "RealDebrid | premium@example.com",
            "AllDebrid\nCached",
            "⚙️ TorrentGalaxy 👤 13",
        ).forEach { naming ->
            assertEquals(emptyList<String>(), streamBadges(filename = null, title = null, name = naming))
        }
    }

    @Test
    fun ignoresNumbersAndWordsThatMerelyContainABadge() {
        // Neither of these is a resolution or a codec, and a looser matcher
        // would read both as one.
        val badges = streamBadges(
            filename = "The.4400.S01E01.h264ish.Group21600p.mkv",
            title = null,
            name = null,
        )

        assertTrue(badges.isEmpty(), "expected no badges, got $badges")
    }

    @Test
    fun handlesSourcesWithNoNamingAtAll() {
        assertEquals(emptyList<String>(), streamBadges(filename = null, title = null, name = null))
        assertEquals(emptyList<String>(), streamBadges(filename = "", title = "  ", name = null))
    }

    @Test
    fun readsAnimeStyleBitDepthNaming() {
        val badges = streamBadges(
            filename = "[Group] Show - 01 (BD 1080p Hi10P AAC) [ABCD1234].mkv",
            title = null,
            name = null,
        )

        assertEquals(listOf("1080p", "H.264 10-bit"), badges)
    }
}
