package moe.ditto.halo.browse

import moe.ditto.halo.api.AddonError
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamBehaviorHints
import moe.ditto.halo.api.Subtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop's `streamInfo`, `sourcesModel` and `sourcePresentation` rules, so both pickers rank and badge alike. */
class SourcesModelTest {
    private fun stream(title: String, name: String? = null, hints: StreamBehaviorHints? = null) =
        Stream(url = "https://cdn.test/${title.hashCode()}", name = name, title = title, behaviorHints = hints)

    private fun group(id: String, name: String, vararg streams: Stream) =
        AddonStreams(AddonSource(id, name), streams.toList())

    @Test
    fun parsesTheFactsAReleaseNameStates() {
        val info = parseSourceInfo(
            stream("Show.S01E03.2160p.WEB-DL.DV.HDR10.DDP5.1.Atmos.HEVC.10bit-GRP.mkv\n👤 42 💾 14.8 GB ⚙️ [RD+]"),
        )
        assertEquals(SourceQuality.P2160, info.quality)
        assertEquals("DV", info.dynamicRange)
        assertEquals("HEVC 10-BIT", info.codec)
        assertEquals("ATMOS", info.audio)
        assertEquals(true, info.cached)
        assertEquals((14.8 * 1024 * 1024 * 1024).toLong(), info.sizeBytes)
        assertEquals("Show.S01E03.2160p.WEB-DL.DV.HDR10.DDP5.1.Atmos.HEVC.10bit-GRP.mkv", info.filename)
    }

    @Test
    fun claimsOnlyUnambiguousTokens() {
        val info = parseSourceInfo(stream("Some.Release.dv.group.mkv"))
        // Lower-case "dv" names a group as often as Dolby Vision; it is not claimed.
        assertNull(info.dynamicRange)
        assertNull(info.quality)
        assertNull(info.codec)
        assertNull(info.cached)
        assertEquals(emptyList(), info.languages)
    }

    @Test
    fun readsChannelsGluedToTheCodecAndTheExplicitUncachedTag() {
        val info = parseSourceInfo(stream("Film.1080p.x264.DDP5.1 [RD download] 3,5 GB ENG ITA"))
        assertEquals("DDP 5.1", info.audio)
        assertEquals("H.264", info.codec)
        assertEquals(false, info.cached)
        assertEquals((3.5 * 1024 * 1024 * 1024).toLong(), info.sizeBytes)
        assertEquals(listOf("ENG", "ITA"), info.languages)
    }

    @Test
    fun audioCodecsReadWithTheirChannelCountGluedOn() {
        fun audio(text: String) = parseSourceInfo(stream(text)).audio
        assertEquals("DDP 5.1", audio("Show.S01E01.1080p.WEB-DL.DDP5.1.H.264"))
        assertEquals("DDP 5.1", audio("Film.2160p.DD+5.1.HEVC"))
        assertEquals("DDP", audio("Film.1080p.DD+.x264"))
        assertEquals("DD 5.1", audio("Film.720p.DD5.1.x264"))
        assertEquals("AAC 2.0", audio("Film.720p.WEB.AAC2.0.H.264"))
        assertEquals("DTS-HD", audio("Film.2160p.DTS-HD.MA7.1"))
        assertEquals("TRUEHD", audio("Film.2160p.TrueHD7.1.HEVC"))
        // A letter after the name is another word, not this codec.
        assertNull(audio("Film.1080p.Ddplus.x264"))
    }

    @Test
    fun theHintedSizeAndFileNameWinOverTheText() {
        val info = parseSourceInfo(
            stream("720p 9 GB", hints = StreamBehaviorHints(filename = "Real.Name.mkv", videoSize = 1_000_000)),
        )
        assertEquals("Real.Name.mkv", info.filename)
        assertEquals(1_000_000L, info.sizeBytes)
    }

    @Test
    fun aNamelessStreamGetsThePlaceholder() {
        assertEquals(UnnamedSource, parseSourceInfo(Stream(url = "https://cdn.test/x")).filename)
    }

    @Test
    fun ranksCachedFirstThenResolutionThenSizeAcrossAddons() {
        val result = resolveSources(
            groups = listOf(
                group("a", "Alpha", stream("A.2160p [RD download] 20 GB"), stream("A.720p [RD+] 1 GB")),
                group("b", "Beta", stream("B.1080p [RD+] 4 GB"), stream("B.1080p [RD+] 6 GB")),
            ),
            errors = emptyList(),
            runtimeSeconds = 0.0,
        )
        assertEquals(listOf("b:1", "b:0", "a:1", "a:0"), result.pool.map { it.key })
        assertEquals("4 sources · 2 addons", result.summary)
    }

    @Test
    fun theListingLiftsThePickGroupsTheRestAndHidesSourcesThatMustDownloadFirst() {
        val result = resolveSources(
            groups = listOf(
                group("a", "Alpha", stream("A.2160p [RD+] 20 GB"), stream("A.720p 1 GB")),
                group("b", "Beta", stream("B.1080p [RD+] 6 GB"), stream("B.1080p [RD download] 4 GB")),
            ),
            errors = emptyList(),
            runtimeSeconds = 0.0,
        )
        val listing = buildListing(result, SourceFilter.All, SourceSort.Recommended, revealAll = false)
        assertEquals("a:0", listing.pick?.key)
        assertEquals(listOf("Beta" to listOf("b:0")), listing.groups.map { g -> g.name to g.entries.map { it.key } })
        assertEquals(2, listing.hiddenCount)

        val revealed = buildListing(result, SourceFilter.All, SourceSort.Recommended, revealAll = true)
        assertEquals(listOf("Beta" to 2, "Alpha" to 1), revealed.groups.map { it.name to it.entries.size })
        assertEquals(0, revealed.hiddenCount)

        // Any other order drops the pick and shows everything it names.
        val bySize = buildListing(result, SourceFilter.All, SourceSort.SmallestFile, revealAll = false)
        assertNull(bySize.pick)
        // Grouped by addon in the order each addon's smallest file appears.
        assertEquals(
            listOf("Alpha" to listOf("a:1", "a:0"), "Beta" to listOf("b:1", "b:0")),
            bySize.groups.map { g -> g.name to g.entries.map { it.key } },
        )
        assertEquals(
            mapOf(SourceFilter.All to 4, SourceFilter.UltraHd to 1, SourceFilter.FullHd to 2, SourceFilter.Hd to 1),
            filterCounts(result.pool),
        )
    }

    @Test
    fun anEmptyFilterSaysSoAndKeepsNoPick() {
        val result = resolveSources(listOf(group("a", "Alpha", stream("A.1080p [RD+]"))), emptyList(), 0.0)
        val listing = buildListing(result, SourceFilter.UltraHd, SourceSort.Recommended, revealAll = false)
        assertTrue(listing.empty)
        assertNull(listing.pick)
    }

    @Test
    fun failedAddonsAreCountedAndNamedSafely() {
        val result = resolveSources(
            groups = listOf(group("a", "Alpha", stream("A.1080p"))),
            errors = listOf(
                AddonError(id = "x", message = "raw", name = "Torrentio", code = "timeout"),
                AddonError(id = "y", message = "raw", name = "https://addon.test/abc/manifest.json", code = "upstream_http"),
            ),
            runtimeSeconds = 0.0,
        )
        assertEquals("1 source · 1 of 3 addons", result.summary)
        assertEquals(
            listOf(
                ProviderRow("Alpha", "1 source", true),
                ProviderRow("Torrentio", "did not answer in time", false),
                ProviderRow("An addon", "returned an error", false),
            ),
            result.providers,
        )
    }

    @Test
    fun medalsGoldSilverBronzeAndAPlainTaglessBadgeBelowHd() {
        fun medal(text: String) = medalFor(parseSourceInfo(stream(text)))
        assertEquals(Medal("4K", "HDR", MedalFinish.Gold), medal("x.2160p.HDR10"))
        assertEquals(Medal("4K", "UHD", MedalFinish.Gold), medal("x.2160p"))
        assertEquals(Medal("1080p", "DV", MedalFinish.Silver), medal("x.1080p.DoVi"))
        assertEquals(Medal("1440p", "QHD", MedalFinish.Silver), medal("x.1440p"))
        assertEquals(Medal("720p", "HD", MedalFinish.Bronze), medal("x.720p"))
        assertEquals(Medal("480p", null, MedalFinish.Plain), medal("x.480p"))
        assertEquals(Medal("—", null, MedalFinish.Plain), medal("x.release"))
    }

    @Test
    fun theMetaLineListsOnlyWhatIsKnown() {
        assertEquals("HEVC · DDP 5.1 · 6.2 GB", metaLine(parseSourceInfo(stream("x.HEVC.DDP.5.1 6.2 GB"))))
        assertEquals("820 MB", metaLine(parseSourceInfo(stream("x 820 MB"))))
        assertEquals("", metaLine(parseSourceInfo(stream("x"))))
    }

    @Test
    fun detailsSpellOutWhatIsAndIsNotKnown() {
        val gib = 1024L * 1024 * 1024
        val result = resolveSources(
            groups = listOf(
                group(
                    "a",
                    "Alpha",
                    Stream(
                        url = "https://cdn.test/a",
                        title = "x.2160p.DV.HEVC.Atmos [RD+] ENG JPN",
                        subtitles = listOf(Subtitle(id = "1", url = "https://cdn.test/s", lang = "eng")),
                        behaviorHints = StreamBehaviorHints(videoSize = 9 * gib),
                    ),
                    stream("y.720p"),
                ),
            ),
            errors = emptyList(),
            runtimeSeconds = 3600.0,
        )
        val best = result.pool.first()
        val details = detailsFor(best, heaviestMbps = best.neededMbps)
        assertEquals("3840 × 2160", details.resolution)
        assertEquals(listOf(DetailChip("DOLBY VISION"), DetailChip("H.265")), details.pictureChips)
        assertEquals("Dolby Atmos", details.sound)
        assertEquals(listOf(DetailChip("ENGLISH"), DetailChip("JAPANESE")), details.audioLanguages)
        assertEquals(listOf(DetailChip("ENGLISH")), details.subtitles)
        assertEquals("Cached", details.cacheLabel)
        // 9 GiB over an hour.
        assertEquals("21 Mbps", details.mbpsLabel)
        assertEquals(1f, details.meterFraction)

        val bare = detailsFor(result.pool.last(), heaviestMbps = best.neededMbps)
        assertEquals("1280 × 720", bare.resolution)
        assertEquals(listOf(DetailChip("NOT LISTED", muted = true)), bare.audioLanguages)
        assertEquals(listOf(DetailChip("NONE INCLUDED", muted = true)), bare.subtitles)
        assertEquals("Not available", bare.mbpsLabel)
        assertEquals("Cache not reported", bare.cacheLabel)
    }

    @Test
    fun addonNamesThatLookLikeUrlsPathsOrTokensAreWithheld() {
        assertEquals("An addon", sanitizeAddonName("https://x.test"))
        assertEquals("An addon", sanitizeAddonName("a/b"))
        assertEquals("An addon", sanitizeAddonName("x".repeat(40)))
        assertEquals("An addon", sanitizeAddonName(null))
        assertEquals("Torrentio", sanitizeAddonName(" Torrentio\u0007 "))
    }
}
