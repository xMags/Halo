package moe.ditto.halo.browse

/** The video formats a source's name can claim, as hardware decoders are grouped. */
enum class VideoCodecFamily { H264, Hevc, Av1, Mpeg4 }

/** What one family's hardware decoder on this device can take. */
data class HardwareVideoDecoder(
    /** The largest picture it decodes, in pixels. */
    val maxWidth: Int,
    val maxHeight: Int,
    /** Whether it decodes 10-bit pictures, which every HDR format needs. */
    val tenBit: Boolean,
)

/**
 * The device's hardware video decoders, per family; a family missing from
 * [hardware] has none.
 *
 * Null [hardware] means the decoders are not what decides playback (a device
 * that decodes in software, or one whose decoders could not be read), and
 * every source counts as playable: hiding a source that would have played is
 * worse than listing one that might not.
 */
data class VideoDecoderSupport(val hardware: Map<VideoCodecFamily, HardwareVideoDecoder>?) {
    companion object {
        val Unrestricted = VideoDecoderSupport(hardware = null)
    }
}

/**
 * Whether a source can play on a device with [decoders], read from what its
 * name says. Deliberately conservative: a source is hidden only for something
 * its name states and the device is known not to do, so a name that says
 * nothing about the codec keeps the source.
 *
 * XVID is MPEG-4 Part 2, which exists at standard-definition sizes and is
 * cheap enough to decode in software on any device, so a missing MPEG-4
 * hardware decoder does not hide it.
 */
fun isPlayableOn(info: SourceInfo, decoders: VideoDecoderSupport): Boolean {
    val hardware = decoders.hardware ?: return true
    val family = codecFamily(info.codec) ?: return true
    if (family == VideoCodecFamily.Mpeg4) return true
    val decoder = hardware[family] ?: return false
    val size = pictureSize(info.quality)
    if (size != null && (size.first > decoder.maxWidth || size.second > decoder.maxHeight)) return false
    if (isTenBit(info) && !decoder.tenBit) return false
    return true
}

/** The family a parsed codec label belongs to, or null for a label this rule does not know. */
fun codecFamily(codec: String?): VideoCodecFamily? = when (codec) {
    "HEVC", "HEVC 10-BIT" -> VideoCodecFamily.Hevc
    "AV1", "AV1 10-BIT" -> VideoCodecFamily.Av1
    "H.264" -> VideoCodecFamily.H264
    "XVID" -> VideoCodecFamily.Mpeg4
    else -> null
}

/** Every HDR format is 10-bit or more, so an advertised range says so as much as the label does. */
private fun isTenBit(info: SourceInfo): Boolean =
    info.codec?.endsWith("10-BIT") == true || info.dynamicRange != null

/** Width and height a quality stands for; the lower ones never exceed a decoder. */
private fun pictureSize(quality: SourceQuality?): Pair<Int, Int>? = when (quality) {
    SourceQuality.P2160 -> 3840 to 2160
    SourceQuality.P1440 -> 2560 to 1440
    SourceQuality.P1080 -> 1920 to 1080
    SourceQuality.P720 -> 1280 to 720
    SourceQuality.P480, SourceQuality.SD, null -> null
}
