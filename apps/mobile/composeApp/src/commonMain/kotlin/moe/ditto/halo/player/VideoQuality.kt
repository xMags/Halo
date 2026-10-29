package moe.ditto.halo.player

import moe.ditto.halo.browse.Medal
import moe.ditto.halo.browse.MedalFinish

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
