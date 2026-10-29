package moe.ditto.halo.player

import moe.ditto.halo.browse.Medal
import moe.ditto.halo.browse.MedalFinish
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
}
