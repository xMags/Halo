package moe.ditto.halo.player

import moe.ditto.halo.browse.Medal
import moe.ditto.halo.browse.MedalFinish
import moe.ditto.halo.browse.SourceInfo
import moe.ditto.halo.browse.medalFor

private class QualityStep(val minimumPixels: Long, val medal: Medal)

private val QualitySteps = listOf(
    QualityStep(5_500_000, Medal("4K", "UHD", MedalFinish.Gold)),
    QualityStep(3_000_000, Medal("1440p", "QHD", MedalFinish.Silver)),
    QualityStep(1_400_000, Medal("1080p", "FHD", MedalFinish.Silver)),
    QualityStep(600_000, Medal("720p", "HD", MedalFinish.Bronze)),
)

/**
 * The player's medal for the decoded picture, ported from the desktop's
 * `classifyVideoQuality`. Classifies on pixel count, so scope framing keeps its
 * tier: a 2.39:1 film mastered at 4K is 3840×1600, which a height rule would
 * call 1080p. Dynamic range replaces the tier's tag rather than joining it.
 * Null when there is no picture to describe yet.
 */
fun classifyVideoQuality(format: VideoFormat): Medal? {
    if (format.width <= 0 || format.height <= 0) return null
    val pixels = format.width.toLong() * format.height.toLong()
    val medal = QualitySteps.firstOrNull { pixels >= it.minimumPixels }?.medal
        ?: Medal("SD", null, MedalFinish.Plain)
    // Dolby Vision outranks the transfer: its base layer is usually PQ.
    return when {
        format.dolbyVision -> medal.copy(tag = "DV")
        format.gamma == "pq" -> medal.copy(tag = "HDR")
        format.gamma == "hlg" -> medal.copy(tag = "HLG")
        else -> medal
    }
}

/** Tags [classifyVideoQuality] gives a picture whose dynamic range it recognised. */
private val DecodedRangeTags = setOf("DV", "HDR", "HLG")

/**
 * The medal for what is playing, never blank while anything is known.
 *
 * Until the decoder reports a picture it is the medal the sources list gave
 * the chosen [source], from its name; with no resolution in the name there is
 * nothing to show, rather than a plate with no number. Once the picture is
 * decoded its resolution wins. Its dynamic range wins too when it found one;
 * when it found none the name's still counts, since a picture can be HDR in
 * ways the decoder does not report (a Dolby Vision base layer marked as SDR).
 */
fun playerQualityMedal(format: VideoFormat, source: SourceInfo?): Medal? {
    val decoded = classifyVideoQuality(format)
    if (decoded == null) return source?.takeIf { it.quality != null }?.let(::medalFor)
    if (decoded.tag in DecodedRangeTags) return decoded
    val range = source?.dynamicRange ?: return decoded
    return decoded.copy(tag = rangeTag(range))
}

/** A named range as the player's tag: the transfer's own name for HLG, `HDR` for the rest. */
private fun rangeTag(range: String): String = when (range) {
    "DV" -> "DV"
    "HLG" -> "HLG"
    else -> "HDR"
}
