package moe.ditto.halo.player

import kotlin.math.abs

/**
 * The second engine's facts in the shapes the rest of the app already reads
 * from mpv: codec names as mpv's `track-list` spells them, the colour
 * transfer as mpv's `video-params/gamma` names it, and which failures mean a
 * picture this device cannot play. Pure, so every rule is unit tested; the
 * host passes Media3's values in.
 */
internal object Media3Mapping {

    /** Media3's MIME type for subtitles it parsed while reading the file; the original type is then in `codecs`. */
    const val ParsedCuesMime = "application/x-media3-cues"

    /** The subtitle formats whose cues are text, which the app draws itself. */
    private val TextSubtitleCodecs = mapOf(
        "application/x-subrip" to "subrip",
        "text/x-ssa" to "ass",
        "text/vtt" to "webvtt",
        "application/x-mp4-vtt" to "webvtt",
        "application/ttml+xml" to "ttml",
        "application/x-quicktime-tx3g" to "mov_text",
        "application/cea-608" to "eia_608",
        "application/cea-708" to "eia_708",
    )

    /** Picture formats: Media3 decodes them to bitmaps, which the app's text overlay cannot draw. */
    private val PictureSubtitleCodecs = mapOf(
        "application/pgs" to "hdmv_pgs_subtitle",
        "application/vobsub" to "dvd_subtitle",
        "application/dvbsubs" to "dvb_subtitle",
    )

    private val AudioCodecs = mapOf(
        "audio/ac3" to "ac3",
        "audio/eac3" to "eac3",
        "audio/eac3-joc" to "eac3",
        "audio/ac4" to "ac4",
        "audio/true-hd" to "truehd",
        "audio/vnd.dts" to "dts",
        "audio/vnd.dts.hd" to "dts",
        "audio/vnd.dts.hd;profile=lbr" to "dts",
        "audio/mp4a-latm" to "aac",
        "audio/opus" to "opus",
        "audio/vorbis" to "vorbis",
        "audio/flac" to "flac",
        "audio/mpeg" to "mp3",
        "audio/raw" to "pcm",
    )

    /** A subtitle track's own format, looking through Media3's parsed-cues wrapper. */
    fun subtitleMime(sampleMime: String?, codecs: String?): String? =
        if (sampleMime == ParsedCuesMime) codecs else sampleMime

    /** mpv's name for a subtitle format, or the MIME type itself for one this does not know. */
    fun subtitleCodec(mime: String?): String? {
        val key = mime?.lowercase() ?: return null
        return TextSubtitleCodecs[key] ?: PictureSubtitleCodecs[key] ?: key
    }

    /** True when the app can draw this format's cues: text, not pictures, not unknown. */
    fun subtitleShowable(mime: String?): Boolean = mime?.lowercase() in TextSubtitleCodecs

    /** mpv's name for an audio format, or the MIME type itself for one this does not know. */
    fun audioCodec(mime: String?): String? {
        val key = mime?.lowercase() ?: return null
        return AudioCodecs[key] ?: key
    }

    /** The MIME type of a subtitle file the app adds, by its name; null when it is not one Media3 parses. */
    fun sideloadedSubtitleMime(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        "ttml", "dfxp", "xml" -> "application/ttml+xml"
        else -> null
    }

    /** Media3's `C.COLOR_TRANSFER_*` values this app names. */
    const val TransferSdr = 3
    const val TransferSt2084 = 6
    const val TransferHlg = 7

    /** mpv's name for a colour transfer, as the quality badge reads it. */
    fun gamma(colorTransfer: Int?): String? = when (colorTransfer) {
        TransferSt2084 -> "pq"
        TransferHlg -> "hlg"
        TransferSdr -> "bt.1886"
        else -> null
    }

    /**
     * The Dolby Vision profile from a codecs string such as `dvhe.05.06` or
     * `dvh1.08.03`; null when it names none.
     */
    fun dolbyVisionProfile(codecs: String?): Int? {
        val parts = codecs?.trim()?.lowercase()?.split('.') ?: return null
        if (parts.size < 2 || parts[0] !in DolbyVisionCodecTags) return null
        return parts[1].toIntOrNull()?.takeIf { it in 0..20 }
    }

    private val DolbyVisionCodecTags = setOf("dvhe", "dvh1", "dvav", "dva1", "dav1")

    /** Media3's `PlaybackException.ERROR_CODE_DECODER_INIT_FAILED` up to `ERROR_CODE_DECODING_RESOURCES_RECLAIMED`. */
    private val DecodingErrorCodes = 4001..4006

    /**
     * What kind of failure an error is. A video decoder that will not take
     * or keep the picture is one this device cannot play, so the source
     * fallback takes over; everything else (the network, the container, the
     * sound) is the source's, and the error card offers a retry.
     */
    fun failure(errorCode: Int, videoRenderer: Boolean): PlaybackFailure =
        if (videoRenderer && errorCode in DecodingErrorCodes) PlaybackFailure.UnsupportedVideo else PlaybackFailure.Source

    /** The rates films and video are made at, which a measured rate is taken to be. */
    val StandardFrameRates = listOf(24_000.0 / 1001, 24.0, 25.0, 30_000.0 / 1001, 30.0, 48.0, 50.0, 60_000.0 / 1001, 60.0)

    /**
     * Frames a second from an unbroken run of frames' presentation times in
     * microseconds, for a track that states no rate. Container timestamps are
     * often whole milliseconds, so one frame's gap (41 or 42 ms at 24) says
     * little; the whole run's span does: over [MinMeasuredFrames] frames
     * (five seconds at 24) the error is at most 0.02%, against 0.1% between
     * 23.976 and 24. The result is the nearest [StandardFrameRates] entry,
     * or null when the run is too short, is broken (a seek inside it), or
     * fits no standard rate within [SnapTolerance].
     */
    fun measuredFrameRate(presentationTimesUs: List<Long>): Double? {
        if (presentationTimesUs.size < MinMeasuredFrames) return null
        if (presentationTimesUs.zipWithNext { a, b -> b - a }.any { it !in 1..MaxFrameGapUs }) return null
        val span = presentationTimesUs.last() - presentationTimesUs.first()
        if (span <= 0) return null
        return snapFrameRate((presentationTimesUs.size - 1) * 1_000_000.0 / span)
    }

    /** The standard rate [raw] is, within [SnapTolerance]; null for none. */
    fun snapFrameRate(raw: Double): Double? {
        if (!raw.isFinite() || raw <= 0) return null
        val nearest = StandardFrameRates.minBy { abs(it - raw) / it }
        return nearest.takeIf { abs(nearest - raw) / nearest <= SnapTolerance }
    }

    const val MinMeasuredFrames = 121
    private const val MaxFrameGapUs = 200_000L

    /** 0.5%: far wider than the measurement's error, far narrower than the gap between any two standard rates but 23.976 and 24, which the error tells apart. */
    private const val SnapTolerance = 0.005

    /** The width a picture shows at: its stored width stretched by its pixel aspect ratio. */
    fun displayWidth(width: Int, pixelWidthHeightRatio: Float): Int {
        if (width <= 0) return 0
        val ratio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        return Math.round(width * ratio)
    }
}
