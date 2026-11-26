package moe.ditto.halo.player

import moe.ditto.halo.api.Stream
import moe.ditto.halo.browse.Medal
import moe.ditto.halo.browse.MedalFinish
import moe.ditto.halo.browse.medalFor
import moe.ditto.halo.browse.parseSourceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The desktop's `classifyVideoQuality` cases, so both players stamp a picture alike. */
class VideoQualityTest {
    private fun video(width: Int, height: Int, gamma: String? = null, dolbyVision: Boolean = false) =
        classifyVideoQuality(VideoFormat(width, height, gamma, dolbyVision))

    @Test
    fun classifiesByPixelsAndLetsDynamicRangeReplaceTheTag() {
        // Scope framing keeps its tier.
        assertEquals(Medal("4K", "UHD", MedalFinish.Gold), video(3840, 1600))
        assertEquals(Medal("1080p", "FHD", MedalFinish.Silver), video(1920, 800))
        assertEquals(Medal("1440p", "QHD", MedalFinish.Silver), video(2560, 1440))
        assertEquals(Medal("720p", "HDR", MedalFinish.Bronze), video(1280, 720, "pq"))
        assertEquals(Medal("4K", "DV", MedalFinish.Gold), video(3840, 2160, "pq", dolbyVision = true))
        assertEquals(Medal("SD", "HLG", MedalFinish.Plain), video(720, 480, "hlg"))
        assertEquals(Medal("SD", null, MedalFinish.Plain), video(720, 480))
    }

    @Test
    fun noPictureMeansNoMedal() {
        assertNull(video(0, 0))
        assertNull(video(1920, 0))
    }

    private fun source(title: String) = parseSourceInfo(Stream(url = "https://cdn.test/a", title = title))

    @Test
    fun beforeDecodingTheChosenSourcesOwnMedalShows() {
        val info = source("Show.S01E01.2160p.DV.HEVC.mkv")
        assertEquals(medalFor(info), playerQualityMedal(VideoFormat(), info))
        assertEquals(Medal("4K", "DV", MedalFinish.Gold), playerQualityMedal(VideoFormat(), info))
    }

    @Test
    fun aSourceThatNamesNoResolutionShowsNothingBeforeDecoding() {
        assertNull(playerQualityMedal(VideoFormat(), source("Some.Film.HDR.mkv")))
        assertNull(playerQualityMedal(VideoFormat(), null))
    }

    @Test
    fun theDecodedResolutionWins() {
        val named4k = source("Film.2160p.WEB.mkv")
        assertEquals(Medal("1080p", "FHD", MedalFinish.Silver), playerQualityMedal(VideoFormat(1920, 1080), named4k))
    }

    @Test
    fun aDecodedRangeWinsAndTheNamedOneFillsInWhenTheDecoderFoundNone() {
        val namedHdr = source("Film.2160p.HDR10.mkv")
        assertEquals(Medal("4K", "DV", MedalFinish.Gold), playerQualityMedal(VideoFormat(3840, 2160, "pq", dolbyVision = true), namedHdr))
        assertEquals(Medal("4K", "HDR", MedalFinish.Gold), playerQualityMedal(VideoFormat(3840, 2160), namedHdr))
        assertEquals(Medal("4K", "DV", MedalFinish.Gold), playerQualityMedal(VideoFormat(3840, 2160), source("Film.2160p.DV.mkv")))
        assertEquals(Medal("1080p", "HLG", MedalFinish.Silver), playerQualityMedal(VideoFormat(1920, 1080), source("Film.1080p.HLG.mkv")))
        // A name that says nothing about range leaves the decoded tier's tag.
        assertEquals(Medal("4K", "UHD", MedalFinish.Gold), playerQualityMedal(VideoFormat(3840, 2160), source("Film.2160p.mkv")))
    }
}
