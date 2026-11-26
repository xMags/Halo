package moe.ditto.halo.player

import moe.ditto.halo.browse.VideoCodecFamily
import moe.ditto.halo.browse.VideoDecoderSupport

/**
 * Whether an opened file's picture is one this TV cannot play, for a source
 * whose name did not say its codec and so was listed anyway. Pure, so every
 * row is unit tested.
 */
internal object UnsupportedVideoPolicy {

    /** What mpv's `hwdec-current` reads while the picture is decoded in software. */
    const val SoftwareDecoder = "no"

    /**
     * True when the picture fell to software decoding because its codec has no
     * hardware decoder here, and it is larger than [MaxSoftwareWidth] by
     * [MaxSoftwareHeight]: a TV's processor plays such a picture as a
     * slideshow. A smaller picture plays in software well enough, and a codec
     * this device does have a decoder for fell to software through a decoder
     * error, which the video path's own fallback already handles; both stay.
     *
     * [codec] is ffmpeg's name as mpv's `track-list` reports it; one this rule
     * does not know never counts. [width] and [height] are zero when unknown,
     * which never counts either.
     */
    fun isUnsupported(
        hwdecCurrent: String?,
        codec: String?,
        width: Int,
        height: Int,
        decoders: VideoDecoderSupport,
    ): Boolean {
        if (hwdecCurrent != SoftwareDecoder) return false
        val hardware = decoders.hardware ?: return false
        val family = familyOf(codec) ?: return false
        if (hardware.containsKey(family)) return false
        return width > MaxSoftwareWidth || height > MaxSoftwareHeight
    }

    private fun familyOf(codec: String?): VideoCodecFamily? = when (codec?.lowercase()) {
        "av1" -> VideoCodecFamily.Av1
        "hevc" -> VideoCodecFamily.Hevc
        "h264" -> VideoCodecFamily.H264
        else -> null
    }

    private const val MaxSoftwareWidth = 1280
    private const val MaxSoftwareHeight = 720
}
