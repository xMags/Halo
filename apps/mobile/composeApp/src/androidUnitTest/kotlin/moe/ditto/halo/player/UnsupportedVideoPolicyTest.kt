package moe.ditto.halo.player

import moe.ditto.halo.browse.HardwareVideoDecoder
import moe.ditto.halo.browse.VideoCodecFamily
import moe.ditto.halo.browse.VideoDecoderSupport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnsupportedVideoPolicyTest {

    private val uhd = HardwareVideoDecoder(maxWidth = 3840, maxHeight = 2160, tenBit = true)

    /** The measured box: H.264 and HEVC in hardware, no AV1. */
    private val noAv1 = VideoDecoderSupport(mapOf(VideoCodecFamily.H264 to uhd, VideoCodecFamily.Hevc to uhd))

    private fun unsupported(
        hwdec: String? = "no",
        codec: String? = "av1",
        width: Int = 3840,
        height: Int = 1608,
        decoders: VideoDecoderSupport = noAv1,
    ) = UnsupportedVideoPolicy.isUnsupported(hwdec, codec, width, height, decoders)

    @Test
    fun aLargeSoftwareDecodedPictureWithNoHardwareDecoderIsUnsupported() {
        assertTrue(unsupported())
        assertTrue(unsupported(width = 1920, height = 800))
        assertTrue(unsupported(width = 1280, height = 721))
    }

    @Test
    fun aPictureNoLargerThan720pKeepsPlayingInSoftware() {
        assertFalse(unsupported(width = 1280, height = 720))
        assertFalse(unsupported(width = 854, height = 480))
    }

    @Test
    fun aCodecWithAHardwareDecoderThatFellToSoftwareKeepsTheExistingFallback() {
        assertFalse(unsupported(codec = "hevc"))
        assertFalse(unsupported(codec = "h264"))
    }

    @Test
    fun onlySoftwareDecodingCounts() {
        assertFalse(unsupported(hwdec = "mediacodec"))
        assertFalse(unsupported(hwdec = "mediacodec-copy"))
        assertFalse(unsupported(hwdec = null))
    }

    @Test
    fun anUnknownCodecSizeOrDecoderListNeverCounts() {
        assertFalse(unsupported(codec = null))
        assertFalse(unsupported(codec = "mpeg4"))
        assertFalse(unsupported(width = 0, height = 0))
        assertFalse(unsupported(decoders = VideoDecoderSupport.Unrestricted))
    }

    @Test
    fun hevcWithoutAHevcDecoderIsUnsupportedToo() {
        val h264Only = VideoDecoderSupport(mapOf(VideoCodecFamily.H264 to uhd))
        assertTrue(unsupported(codec = "hevc", width = 1920, height = 1080, decoders = h264Only))
    }
}
