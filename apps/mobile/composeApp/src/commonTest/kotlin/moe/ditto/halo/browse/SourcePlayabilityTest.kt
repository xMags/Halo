package moe.ditto.halo.browse

import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The TV's rule for which sources its decoders can play, and how the sources page applies it. */
class SourcePlayabilityTest {
    private fun stream(title: String) = Stream(url = "https://cdn.test/${title.hashCode()}", title = title)

    private fun info(title: String) = parseSourceInfo(stream(title))

    private val uhd10 = HardwareVideoDecoder(maxWidth = 3840, maxHeight = 2160, tenBit = true)
    private val fullHd8 = HardwareVideoDecoder(maxWidth = 1920, maxHeight = 1080, tenBit = false)

    /** The measured TV box: H.264 and HEVC in hardware, no AV1, no MPEG-4. */
    private val noAv1 = VideoDecoderSupport(mapOf(VideoCodecFamily.H264 to uhd10, VideoCodecFamily.Hevc to uhd10))

    @Test
    fun av1IsHiddenWithoutAnAv1Decoder() {
        assertFalse(isPlayableOn(info("Film.2160p.AV1.10bit.mkv"), noAv1))
        assertFalse(isPlayableOn(info("Film.1080p.AV1.mkv"), noAv1))
    }

    @Test
    fun av1IsKeptWithAnAv1Decoder() {
        val withAv1 = VideoDecoderSupport(noAv1.hardware!! + (VideoCodecFamily.Av1 to uhd10))
        assertTrue(isPlayableOn(info("Film.2160p.AV1.10bit.mkv"), withAv1))
    }

    @Test
    fun aNameThatDoesNotSayTheCodecIsKept() {
        assertTrue(isPlayableOn(info("Film.2160p.WEB-DL.mkv"), VideoDecoderSupport(emptyMap())))
    }

    @Test
    fun aPictureLargerThanTheDecoderIsHidden() {
        val fullHdHevc = VideoDecoderSupport(mapOf(VideoCodecFamily.Hevc to uhd10.copy(maxWidth = 1920, maxHeight = 1080)))
        assertFalse(isPlayableOn(info("Film.2160p.HEVC.mkv"), fullHdHevc))
        assertTrue(isPlayableOn(info("Film.1080p.HEVC.mkv"), fullHdHevc))
        // Quality unknown or small never exceeds a decoder.
        assertTrue(isPlayableOn(info("Film.HEVC.mkv"), fullHdHevc))
        assertTrue(isPlayableOn(info("Film.480p.HEVC.mkv"), fullHdHevc))
    }

    @Test
    fun tenBitAndHdrAreHiddenWithoutTenBitSupport() {
        val eightBit = VideoDecoderSupport(mapOf(VideoCodecFamily.Hevc to fullHd8, VideoCodecFamily.H264 to fullHd8))
        assertFalse(isPlayableOn(info("Film.1080p.HEVC.10bit.mkv"), eightBit))
        assertFalse(isPlayableOn(info("Film.1080p.HDR.HEVC.mkv"), eightBit))
        assertFalse(isPlayableOn(info("Film.1080p.DV.HEVC.mkv"), eightBit))
        assertTrue(isPlayableOn(info("Film.1080p.HEVC.mkv"), eightBit))
    }

    @Test
    fun xvidIsKeptWithoutAnMpeg4Decoder() {
        assertTrue(isPlayableOn(info("Film.DVDRip.XviD.avi"), noAv1))
    }

    @Test
    fun anUnrestrictedDeviceKeepsEverything() {
        assertTrue(isPlayableOn(info("Film.2160p.AV1.10bit.DV.mkv"), VideoDecoderSupport.Unrestricted))
    }

    private val groups = listOf(
        AddonStreams(
            AddonSource("a", "Addon A"),
            listOf(stream("Film.2160p.AV1 [RD+]"), stream("Film.1080p.HEVC [RD+]"), stream("Film.720p.x264")),
        ),
        AddonStreams(
            AddonSource("b", "Addon B"),
            listOf(stream("Film.2160p.AV1.10bit [RD+]"), stream("Film.2160p.HEVC [RD+]")),
        ),
    )

    @Test
    fun resolveDropsWhatCannotPlayAndRanksTheRestAsBefore() {
        val all = resolveSources(groups, emptyList(), runtimeSeconds = 0.0)
        val tv = resolveSources(groups, emptyList(), runtimeSeconds = 0.0, decoders = noAv1)

        assertEquals(2, tv.unplayableCount)
        assertEquals(3, tv.pool.size)
        assertTrue(tv.pool.none { it.info.codec?.startsWith("AV1") == true })
        // The kept sources keep the order they had among all of them.
        val expected = all.pool.filter { it.info.codec?.startsWith("AV1") != true }.map { it.key }
        assertEquals(expected, tv.pool.map { it.key })
        assertEquals(listOf(0, 1, 2), tv.pool.map { it.rank })
        // Provider rows and the summary count only the sources shown.
        assertEquals(listOf("2 sources", "1 source"), tv.providers.map { it.value })
        assertEquals("3 sources · 2 addons", tv.summary)
    }

    @Test
    fun theDefaultKeepsEverySource() {
        val resolve = resolveSources(groups, emptyList(), runtimeSeconds = 0.0)
        assertEquals(0, resolve.unplayableCount)
        assertEquals(5, resolve.pool.size)
        assertEquals(listOf("3 sources", "2 sources"), resolve.providers.map { it.value })
    }
}
