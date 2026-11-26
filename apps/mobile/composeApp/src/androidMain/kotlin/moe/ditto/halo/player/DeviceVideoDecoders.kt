package moe.ditto.halo.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import moe.ditto.halo.browse.HardwareVideoDecoder
import moe.ditto.halo.browse.VideoCodecFamily
import moe.ditto.halo.browse.VideoDecoderSupport

/**
 * This device's hardware video decoders, read from the platform's codec list
 * once per process: the list does not change while the app runs, and building
 * it queries every codec, which is not free. Read it off the main thread.
 *
 * Only hardware decoders count. Software ones decode anything given time,
 * which is exactly what a TV's processor does not have. When the list cannot
 * be read, the answer is [VideoDecoderSupport.Unrestricted], so an error never
 * hides a source.
 */
internal object DeviceVideoDecoders {
    val current: VideoDecoderSupport by lazy { read() }

    private fun read(): VideoDecoderSupport = runCatching { readHardware() }
        .getOrElse { failure ->
            Log.w(LOG_TAG, "video decoder list unreadable; treating every format as playable", failure)
            VideoDecoderSupport.Unrestricted
        }

    private fun readHardware(): VideoDecoderSupport {
        val found = HashMap<VideoCodecFamily, HardwareVideoDecoder>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder || !isHardware(info)) continue
            for (type in info.supportedTypes) {
                val family = FamilyByMime[type.lowercase()] ?: continue
                // Vendor codecs throw from their capability queries often
                // enough that one bad entry must not cost the whole list.
                val decoder = runCatching { describe(info, type, family) }.getOrNull() ?: continue
                found[family] = found[family]?.let { merge(it, decoder) } ?: decoder
            }
        }
        Log.i(LOG_TAG, "hardware video decoders: $found")
        return VideoDecoderSupport(found)
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || ".sw." in name)
    }

    private fun describe(info: MediaCodecInfo, type: String, family: VideoCodecFamily): HardwareVideoDecoder? {
        val capabilities = info.getCapabilitiesForType(type)
        val video = capabilities.videoCapabilities ?: return null
        // Some decoders state their width and height ranges for either
        // orientation, so the upper bounds alone can claim a size the decoder
        // refuses. The largest standard size it accepts is the honest answer.
        val standard = StandardSizes.firstOrNull { (width, height) -> video.isSizeSupported(width, height) }
        val (maxWidth, maxHeight) = standard ?: (video.supportedWidths.upper to video.supportedHeights.upper)
        val tenBit = capabilities.profileLevels.any { it.profile in TenBitProfiles.getValue(family) }
        return HardwareVideoDecoder(maxWidth = maxWidth, maxHeight = maxHeight, tenBit = tenBit)
    }

    /**
     * Two decoders of one family: the larger picture and either one's 10-bit.
     * Slightly generous when only the smaller of two decoders does 10-bit,
     * which errs towards listing a source rather than hiding it.
     */
    private fun merge(a: HardwareVideoDecoder, b: HardwareVideoDecoder): HardwareVideoDecoder {
        val larger = if (b.maxWidth.toLong() * b.maxHeight > a.maxWidth.toLong() * a.maxHeight) b else a
        return larger.copy(tenBit = a.tenBit || b.tenBit)
    }

    private const val LOG_TAG = "HALO_MPV"

    private val FamilyByMime = mapOf(
        "video/avc" to VideoCodecFamily.H264,
        "video/hevc" to VideoCodecFamily.Hevc,
        "video/av01" to VideoCodecFamily.Av1,
        "video/mp4v-es" to VideoCodecFamily.Mpeg4,
    )

    /** Largest first. */
    private val StandardSizes = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720)

    // The platform's profile constants by value: the AV1 ones and the HDR10+
    // HEVC one exist only from API 29, and minSdk is lower.
    private const val AvcProfileHigh10 = 0x10
    private const val HevcProfileMain10 = 0x2
    private const val HevcProfileMain10Hdr10 = 0x1000
    private const val HevcProfileMain10Hdr10Plus = 0x2000
    private const val Av1ProfileMain10 = 0x2
    private const val Av1ProfileMain10Hdr10 = 0x1000
    private const val Av1ProfileMain10Hdr10Plus = 0x2000

    private val TenBitProfiles = mapOf(
        VideoCodecFamily.H264 to setOf(AvcProfileHigh10),
        VideoCodecFamily.Hevc to setOf(HevcProfileMain10, HevcProfileMain10Hdr10, HevcProfileMain10Hdr10Plus),
        VideoCodecFamily.Av1 to setOf(Av1ProfileMain10, Av1ProfileMain10Hdr10, Av1ProfileMain10Hdr10Plus),
        VideoCodecFamily.Mpeg4 to emptySet(),
    )
}
