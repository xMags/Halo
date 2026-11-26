package moe.ditto.halo.browse

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

/**
 * Which source plays best on this device: the one rule every Halo client and
 * the Halo API order sources by, ported rule for rule from `@halo/core`'s
 * `sources/ranking.ts`. Pure, with no I/O, so the server's ranking and the
 * app's own ranking of the same streams cannot differ.
 *
 * It still prefers a file that plays at once, but only ranks the biggest
 * picture first when the line can carry it and the screen can show it, so the
 * pick on a 4K title is no longer the heaviest remux by default.
 *
 * Both copies are held to the cases in `packages/core/test/vectors/
 * source-ranking.json`. Change the rule in both, with a case.
 */

/**
 * The line speed assumed before anything has measured it, in megabits per
 * second: an ordinary broadband line, so a first run neither trusts every
 * remux nor writes off every 4K file.
 */
const val AssumedLineMbps = 40.0

/** The share of the line a stream may use and still be called safe. */
const val LineHeadroom = 0.7

/** How far a picture may outgrow the screen before its extra lines count as wasted. */
const val ScreenMargin = 1.25

/** Bounds of a kept line reading, in megabits per second. */
const val MinLineMbps = 0.5
const val MaxLineMbps = 100_000.0

/** Largest accepted ranking inputs; anything above is treated as unknown. */
const val MaxScreenHeight = 100_000.0

/** A week: longer than any title, short enough to reject nonsense. */
const val MaxRuntimeSeconds = 604_800.0

/** The largest whole number a JSON reader on every platform holds exactly. */
private const val MaxSafeInteger = 9_007_199_254_740_991L

/** Nominal picture height of each quality, in pixels. */
private fun qualityHeight(quality: SourceQuality): Double = when (quality) {
    SourceQuality.P2160 -> 2160.0
    SourceQuality.P1440 -> 1440.0
    SourceQuality.P1080 -> 1080.0
    SourceQuality.P720 -> 720.0
    SourceQuality.P480, SourceQuality.SD -> 480.0
}

/**
 * How a source sits on this device: it fits, its picture is far larger than
 * the screen, or it needs more than the line's safe share (and may buffer). A
 * source whose bitrate is unknown fits. Declared in rank order: the ordinal
 * is the tier.
 */
enum class SourceFit(val wire: String) {
    Fits("fits"),
    ExceedsScreen("exceedsScreen"),
    OverBudget("overBudget"),
    ;

    companion object {
        fun fromWire(value: String): SourceFit? = entries.firstOrNull { it.wire == value }
    }
}

/** What the device says about itself. Zero means unknown in both fields. */
data class RankingDevice(
    /** Measured line speed in megabits per second; 0 = not measured yet. */
    val lineMbps: Double = 0.0,
    /** Physical pixels of the screen's shorter side; 0 = unknown, so no cap. */
    val screenHeight: Double = 0.0,
) {
    companion object {
        val Unknown = RankingDevice()
    }
}

/** A device plus the title's runtime, which turns file sizes into bitrates. Zero is unknown. */
data class StreamRankingInput(
    val lineMbps: Double = 0.0,
    val screenHeight: Double = 0.0,
    /** Runtime of the video in seconds; 0 = unknown, so every bitrate is unknown. */
    val runtimeSeconds: Double = 0.0,
) {
    val device: RankingDevice get() = RankingDevice(lineMbps, screenHeight)

    companion object {
        val Unknown = StreamRankingInput()
    }
}

/** The facts of one source the rule reads. */
data class RankableSource(
    /** True when already on the debrid; false and null rank the same. */
    val cached: Boolean?,
    val quality: SourceQuality?,
    val sizeBytes: Long?,
    /** Megabits per second the file needs; 0 when its size or runtime is unknown. */
    val neededMbps: Double,
)

/** A source's place in the best-first order (0 is the pick) and its fit. */
data class SourceRank(val rank: Long, val fit: SourceFit)

/** The share of the line, in megabits per second, a source may need and still fit. */
fun budgetMbps(lineMbps: Double): Double = (if (lineMbps > 0) lineMbps else AssumedLineMbps) * LineHeadroom

/**
 * Megabits per second a file of this size needs to play at its runtime; 0 when
 * either is unknown. Computed in the same order as the TypeScript rule, so the
 * same inputs give the same number.
 */
fun neededMbps(sizeBytes: Long?, runtimeSeconds: Double): Double {
    if (sizeBytes == null || sizeBytes <= 0 || !(runtimeSeconds > 0)) return 0.0
    return sizeBytes.toDouble() * 8 / runtimeSeconds / 1_000_000
}

fun sourceFit(source: RankableSource, device: RankingDevice): SourceFit {
    if (source.neededMbps > 0 && source.neededMbps > budgetMbps(device.lineMbps)) return SourceFit.OverBudget
    val quality = source.quality
    if (device.screenHeight > 0 && quality != null && qualityHeight(quality) > device.screenHeight * ScreenMargin) {
        return SourceFit.ExceedsScreen
    }
    return SourceFit.Fits
}

private class Ranked(val index: Int, val source: RankableSource, val fit: SourceFit)

private val RankedOrder = Comparator<Ranked> { a, b ->
    val cached = (if (a.source.cached == true) 0 else 1) - (if (b.source.cached == true) 0 else 1)
    if (cached != 0) return@Comparator cached
    val tier = a.fit.ordinal - b.fit.ordinal
    if (tier != 0) return@Comparator tier
    if (a.fit == SourceFit.OverBudget) {
        // Nothing in this tier plays smoothly: the lightest file buffers least.
        val lighter = a.source.neededMbps.compareTo(b.source.neededMbps)
        if (lighter != 0) return@Comparator lighter
        return@Comparator qualityRank(a.source.quality) - qualityRank(b.source.quality)
    }
    // The larger file at equal resolution, since bitrate is the only quality
    // signal left once the labels match.
    val quality = qualityRank(a.source.quality) - qualityRank(b.source.quality)
    if (quality != 0) return@Comparator quality
    (b.source.sizeBytes ?: 0L).compareTo(a.source.sizeBytes ?: 0L)
}

/**
 * Ranks sources best-first for this device. The answer is in input order:
 * entry `i` says where source `i` ranks and how it fits. Full ties keep their
 * input order.
 */
fun rankSources(sources: List<RankableSource>, device: RankingDevice): List<SourceRank> {
    val ranked = sources.mapIndexed { index, source -> Ranked(index, source, sourceFit(source, device)) }
        .sortedWith(RankedOrder.thenBy { it.index })
    val answer = arrayOfNulls<SourceRank>(sources.size)
    ranked.forEachIndexed { position, entry -> answer[entry.index] = SourceRank(position.toLong(), entry.fit) }
    return answer.map { checkNotNull(it) }
}

private fun clampLine(mbps: Double): Double = mbps.coerceIn(MinLineMbps, MaxLineMbps)

private fun isReading(mbps: Double): Boolean = mbps.isFinite() && mbps > 0

/**
 * The line speed to keep after a playback session, from the reading kept so
 * far and the session's peak. A faster peak replaces it. A slower peak
 * replaces it only when playback stalled for buffering, since a file that
 * never needed the full line says nothing about the line getting slower. A
 * session that measured nothing changes nothing, and "not measured yet" (0)
 * stays 0 rather than becoming the lower bound.
 */
fun foldLineSpeed(previousMbps: Double, sessionPeakMbps: Double, stalled: Boolean): Double {
    if (!isReading(sessionPeakMbps)) return if (isReading(previousMbps)) clampLine(previousMbps) else 0.0
    if (!isReading(previousMbps)) return clampLine(sessionPeakMbps)
    if (sessionPeakMbps > previousMbps) return clampLine(sessionPeakMbps)
    if (stalled) return clampLine(sessionPeakMbps)
    return clampLine(previousMbps)
}

private fun boundedOrZero(value: Double, max: Double): Double =
    if (!value.isFinite() || value <= 0 || value > max) 0.0 else value

/** Rounds half up, as JavaScript's `Math.round` does, so both rules round alike. */
private fun roundHalfUp(value: Double): Double = floor(value + 0.5)

/**
 * The ranking inputs as every caller must use them: anything not a finite
 * number, negative or out of bounds is unknown (0); the line speed is kept to
 * two decimals and the others to whole numbers.
 */
fun normalizeRankingInput(input: StreamRankingInput): StreamRankingInput = StreamRankingInput(
    lineMbps = roundHalfUp(boundedOrZero(input.lineMbps, MaxLineMbps) * 100) / 100,
    screenHeight = roundHalfUp(boundedOrZero(input.screenHeight, MaxScreenHeight)),
    runtimeSeconds = roundHalfUp(boundedOrZero(input.runtimeSeconds, MaxRuntimeSeconds)),
)

/** Whether two ranking inputs would rank the same streams the same way. */
fun sameRankingInput(a: StreamRankingInput, b: StreamRankingInput): Boolean =
    a.lineMbps == b.lineMbps && a.screenHeight == b.screenHeight && a.runtimeSeconds == b.runtimeSeconds

/** The rule's facts of one parsed source. */
fun rankableSource(info: SourceInfo, runtimeSeconds: Double): RankableSource = RankableSource(
    cached = info.cached,
    quality = info.quality,
    sizeBytes = info.sizeBytes,
    neededMbps = neededMbps(info.sizeBytes, runtimeSeconds),
)

/**
 * A `halo` annotation as the app may trust it: a whole, non-negative rank and
 * a known fit. Anything else (an older server sends none; a server is still
 * untrusted input) reads as null.
 */
fun readSourceRank(value: JsonElement?): SourceRank? {
    val fields = value as? JsonObject ?: return null
    val rank = wholeNumber(fields["rank"]) ?: return null
    val fit = (fields["fit"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(SourceFit::fromWire) ?: return null
    return SourceRank(rank, fit)
}

/** A JSON number that is a safe, non-negative whole number; strings never count. */
private fun wholeNumber(value: JsonElement?): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.content.toDoubleOrNull() ?: return null
    if (!number.isFinite() || number < 0 || number != floor(number) || number > MaxSafeInteger.toDouble()) return null
    return number.toLong()
}

/**
 * A normalized value as the server's query reads it: plain decimal digits,
 * no exponent, at most two decimals, trailing zeros dropped (as JavaScript
 * prints a number).
 */
internal fun rankingQueryValue(value: Double): String {
    val hundredths = roundHalfUp(value * 100).toLong()
    val whole = hundredths / 100
    val fraction = hundredths % 100
    if (fraction == 0L) return whole.toString()
    val digits = fraction.toString().padStart(2, '0').trimEnd('0')
    return "$whole.$digits"
}
