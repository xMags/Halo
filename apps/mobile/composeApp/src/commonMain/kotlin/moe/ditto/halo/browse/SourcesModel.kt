package moe.ditto.halo.browse

import moe.ditto.halo.api.AddonError
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.Stream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The sources page's model, ported from the desktop's `sourcesModel.ts` and
 * `sourcePresentation.ts`: which sources exist, how they rank, how the list
 * groups them, and what the page says about each one. Pure, so the page feeds
 * it the query result and renders what comes back.
 *
 * The desktop also lists finished downloads as sources "on this device". The
 * phone does not: a download plays from the Downloads tab, so here its state
 * is shown on the row it was saved from instead.
 */

/** How the page talks about picture: coarser than the parsed resolution. */
enum class QualityTier(val label: String) {
    UltraHd("4K"),
    FullHd("Full HD"),
    Hd("HD"),
    Lower("Lower"),
}

/** The only thing a viewer has to decide between. */
enum class StartSpeed { Immediate, ShortWait, NeedsDownload }

/**
 * `Caching` is kept for parity with the desktop palette, but no addon reports
 * it: they say cached, not cached, or nothing.
 */
enum class SourceStatus(val label: String) {
    Instant("Plays instantly"),
    Caching("Ready in about a minute"),
    Uncached("Downloads before it plays"),

    // Not the same report as "not cached", but the same wait for the viewer.
    Unknown("May download before it plays"),
    ;

    val speed: StartSpeed
        get() = when (this) {
            Instant -> StartSpeed.Immediate
            Caching -> StartSpeed.ShortWait
            Uncached, Unknown -> StartSpeed.NeedsDownload
        }
}

enum class SourceFilter(val label: String, val tier: QualityTier?) {
    All("All", null),
    UltraHd("4K", QualityTier.UltraHd),
    FullHd("1080p", QualityTier.FullHd),
    Hd("720p", QualityTier.Hd),
}

enum class SourceSort(val label: String) {
    Recommended("Recommended"),
    BestPicture("Best picture"),
    SmallestFile("Smallest file"),
    FastestStart("Fastest start"),
}

/** One playable source with every decision about it already made. */
data class SourceEntry(
    /** `<addon id>:<index>`: stable for one answer, unique across addons. */
    val key: String,
    val addon: AddonSource,
    val stream: Stream,
    val info: SourceInfo,
    val status: SourceStatus,
    val tier: QualityTier,
    /** Position in the whole answer's best-first order. */
    val rank: Int,
    /** Megabits per second the file needs; zero when size or runtime is unknown. */
    val neededMbps: Double,
) {
    val speed: StartSpeed get() = status.speed
    val sizeBytes: Long get() = info.sizeBytes ?: 0L
}

data class ProviderRow(val name: String, val value: String, val answered: Boolean)

data class SourcesResolve(
    /** Every playable source, best first. */
    val pool: List<SourceEntry>,
    val providers: List<ProviderRow>,
    /** `54 sources · 3 addons`, or `6 sources · 1 of 2 addons` when one failed. */
    val summary: String,
)

fun tierOf(quality: SourceQuality?): QualityTier = when (quality) {
    SourceQuality.P2160 -> QualityTier.UltraHd
    SourceQuality.P1440, SourceQuality.P1080 -> QualityTier.FullHd
    SourceQuality.P720 -> QualityTier.Hd
    SourceQuality.P480, SourceQuality.SD, null -> QualityTier.Lower
}

private fun statusOf(info: SourceInfo): SourceStatus = when (info.cached) {
    null -> SourceStatus.Unknown
    true -> SourceStatus.Instant
    false -> SourceStatus.Uncached
}

/**
 * Ranks the whole answer at once, so the order runs across addons: the best
 * source is the best one anyone offered, not the first addon's first.
 *
 * @param runtimeSeconds the video's length, for what each file needs from the
 *   line; zero or less when unknown.
 */
fun resolveSources(
    groups: List<AddonStreams>,
    errors: List<AddonError>,
    runtimeSeconds: Double,
): SourcesResolve {
    class Candidate(val key: String, val addon: AddonSource, val stream: Stream, val info: SourceInfo)

    val kept = groups.map { group ->
        group.streams.mapIndexedNotNull { index, stream ->
            if (!stream.isPlayable) return@mapIndexedNotNull null
            Candidate("${group.addon.id}:$index", group.addon, stream, parseSourceInfo(stream))
        }
    }
    val pool = kept.flatten()
        .sortedWith(compareBy(SourceInfoOrder) { it.info })
        .mapIndexed { rank, candidate ->
            val size = candidate.info.sizeBytes ?: 0L
            SourceEntry(
                key = candidate.key,
                addon = candidate.addon,
                stream = candidate.stream,
                info = candidate.info,
                status = statusOf(candidate.info),
                tier = tierOf(candidate.info.quality),
                rank = rank,
                neededMbps = if (size > 0 && runtimeSeconds > 0) size * 8.0 / runtimeSeconds / 1_000_000.0 else 0.0,
            )
        }

    val providers = buildList {
        groups.forEachIndexed { index, group ->
            add(ProviderRow(sanitizeAddonName(group.addon.name), countLabel(kept[index].size, "source", "sources"), true))
        }
        errors.forEach { add(ProviderRow(sanitizeAddonName(it.name), addonFailureCopy(it.code), false)) }
    }
    return SourcesResolve(pool, providers, resolveSummary(pool.size, groups.size, errors.size))
}

/** The ratio form ("1 of 2 addons") only appears when somebody failed. */
fun resolveSummary(sourceCount: Int, answered: Int, failed: Int): String {
    val addons = answered + failed
    val asked = if (failed == 0) countLabel(addons, "addon", "addons") else "$answered of ${countLabel(addons, "addon", "addons")}"
    return "${countLabel(sourceCount, "source", "sources")} · $asked"
}

fun matchesFilter(entry: SourceEntry, filter: SourceFilter): Boolean =
    filter.tier == null || entry.tier == filter.tier

/** Stable, so equal keys keep their rank order. */
fun sortEntries(entries: List<SourceEntry>, sort: SourceSort): List<SourceEntry> = when (sort) {
    SourceSort.Recommended -> entries.sortedBy { it.rank }
    SourceSort.BestPicture -> entries.sortedWith(compareBy<SourceEntry> { it.tier.ordinal }.thenByDescending { it.sizeBytes })
    // A source that never reported a size cannot claim to be the smallest.
    SourceSort.SmallestFile -> entries.sortedWith(compareBy<SourceEntry> { it.sizeBytes == 0L }.thenBy { it.sizeBytes })
    SourceSort.FastestStart -> entries.sortedWith(compareBy<SourceEntry> { it.speed.ordinal }.thenBy { it.rank })
}

data class SourceGroup(val addon: AddonSource, val name: String, val entries: List<SourceEntry>)

data class SourcesListing(
    /** The recommended source, shown on its own card and not repeated below. */
    val pick: SourceEntry?,
    /** The rest, grouped by addon in the order their best source appears. */
    val groups: List<SourceGroup>,
    /** Sources that must download first, waiting behind one "more" row. */
    val hiddenCount: Int,
    /** True when the filter left nothing at all, pick included. */
    val empty: Boolean,
)

/**
 * Under Recommended the top source becomes the pick and sources that must
 * download first wait behind one row until [revealAll]; any other order is the
 * plain list it names, since a pick or a hidden tail would contradict it.
 */
fun buildListing(
    resolve: SourcesResolve,
    filter: SourceFilter,
    sort: SourceSort,
    revealAll: Boolean,
): SourcesListing {
    val listed = sortEntries(resolve.pool.filter { matchesFilter(it, filter) }, sort)
    val top = resolve.pool.firstOrNull()
    val pick = top?.takeIf { sort == SourceSort.Recommended && matchesFilter(it, filter) }

    val buckets = LinkedHashMap<String, MutableList<SourceEntry>>()
    var hidden = 0
    for (entry in listed) {
        if (entry.key == pick?.key) continue
        if (sort == SourceSort.Recommended && !revealAll && entry.speed == StartSpeed.NeedsDownload) {
            hidden += 1
            continue
        }
        buckets.getOrPut(entry.addon.id) { mutableListOf() } += entry
    }
    val groups = buckets.values.map { entries ->
        val addon = entries.first().addon
        SourceGroup(addon, sanitizeAddonName(addon.name), entries)
    }
    return SourcesListing(pick, groups, hidden, empty = listed.isEmpty())
}

/** How many sources each filter pill would show. */
fun filterCounts(pool: List<SourceEntry>): Map<SourceFilter, Int> =
    SourceFilter.entries.associateWith { filter -> pool.count { matchesFilter(it, filter) } }

data class MixRow(val tier: QualityTier, val count: Int, val share: Float)

/** The footer's quality mix: always the three tiers, even when one is empty. */
fun qualityMix(pool: List<SourceEntry>): List<MixRow> =
    listOf(QualityTier.UltraHd, QualityTier.FullHd, QualityTier.Hd).map { tier ->
        val count = pool.count { it.tier == tier }
        MixRow(tier, count, if (pool.isEmpty()) 0f else count.toFloat() / pool.size)
    }

/** The three medal finishes; gold is spent only on the top tier, so it still means something. */
enum class MedalFinish { Gold, Silver, Bronze, Plain }

/**
 * A source's quality badge: the resolution on the dark plate, and a tag beside
 * it naming the dynamic range or, without one, the tier. Lower tiers carry no
 * tag at all, so their plate fills the badge instead of leaving an empty box.
 */
data class Medal(val plate: String, val tag: String?, val finish: MedalFinish)

fun medalFor(info: SourceInfo): Medal {
    val tier = tierOf(info.quality)
    val plate = when (info.quality) {
        SourceQuality.P2160 -> "4K"
        null -> "—"
        else -> info.quality.label
    }
    val tag = when {
        info.dynamicRange == "DV" -> "DV"
        info.dynamicRange != null -> "HDR"
        info.quality == SourceQuality.P1440 -> "QHD"
        else -> when (tier) {
            QualityTier.UltraHd -> "UHD"
            QualityTier.FullHd -> "FHD"
            QualityTier.Hd -> "HD"
            QualityTier.Lower -> null
        }
    }
    val finish = when (tier) {
        QualityTier.UltraHd -> MedalFinish.Gold
        QualityTier.FullHd -> MedalFinish.Silver
        QualityTier.Hd -> MedalFinish.Bronze
        QualityTier.Lower -> MedalFinish.Plain
    }
    return Medal(plate, tag, finish)
}

/** The quiet line under a row's status: codec, sound and size, each only when known. */
fun metaLine(info: SourceInfo): String =
    listOfNotNull(info.codec, info.audio, info.sizeBytes?.let(::formatSourceSize)).joinToString(" · ")

/** `6.2 GB` from a gigabyte up, whole megabytes below. */
fun formatSourceSize(bytes: Long): String {
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gib >= 1) {
        val tenths = (gib * 10).roundToLong()
        return "${tenths / 10}.${tenths % 10} GB"
    }
    return "${(bytes / (1024.0 * 1024.0)).roundToLong()} MB"
}

data class DetailChip(val label: String, val muted: Boolean = false)

/** The details sheet's read-out, tile for tile as the desktop draws it. */
data class SourceDetails(
    val resolution: String,
    val pictureChips: List<DetailChip>,
    val sound: String,
    val audioLanguages: List<DetailChip>,
    val subtitles: List<DetailChip>,
    val provider: String,
    val cacheLabel: String,
    val cacheGood: Boolean,
    val mbpsLabel: String,
    val heaviestLabel: String,
    val bandwidthNote: String,
    val meterFraction: Float,
)

/**
 * @param heaviestMbps the most any listed source needs, which scales the meter
 *   so the bar compares this file with the others rather than with nothing.
 */
fun detailsFor(entry: SourceEntry, heaviestMbps: Double): SourceDetails {
    val info = entry.info
    val range = info.dynamicRange
    val pictureChips = listOfNotNull(
        DetailChip(if (range == null) "SDR" else rangeName(range).uppercase()),
        info.codec?.let { DetailChip(codecName(it)) },
    )
    val audioLanguages = info.languages.map { DetailChip(languageName(it).uppercase()) }
        // An empty tile reads as a rendering fault, so absence is said out loud.
        .ifEmpty { listOf(DetailChip("NOT LISTED", muted = true)) }
    val subtitles = entry.stream.subtitles.map { it.lang }.filter { it.isNotBlank() }
        .map { DetailChip(languageName(it).uppercase()) }
        .ifEmpty { listOf(DetailChip("NONE INCLUDED", muted = true)) }
    val heaviest = if (heaviestMbps > 0) heaviestMbps else entry.neededMbps
    val measured = entry.neededMbps > 0
    return SourceDetails(
        resolution = dimensions(info.quality) ?: "Not listed",
        pictureChips = pictureChips,
        sound = soundName(info.audio),
        audioLanguages = audioLanguages,
        subtitles = subtitles,
        provider = sanitizeAddonName(entry.addon.name),
        cacheLabel = when (entry.status) {
            SourceStatus.Instant -> "Cached"
            SourceStatus.Caching -> "Caching now"
            SourceStatus.Uncached -> "Not cached yet"
            SourceStatus.Unknown -> "Cache not reported"
        },
        cacheGood = entry.status == SourceStatus.Instant,
        mbpsLabel = if (measured) "${entry.neededMbps.roundToInt()} Mbps" else "Not available",
        heaviestLabel = if (heaviest > 0) "HEAVIEST ${heaviest.roundToInt()} MBPS" else "",
        // The phone has no line-speed measurement, so the note says what the
        // number rests on rather than comparing it with an unknown line.
        bandwidthNote = if (measured) "Averaged over the runtime" else "File size or runtime is not listed",
        meterFraction = if (measured && heaviest > 0) (entry.neededMbps / heaviest).toFloat().coerceIn(0f, 1f) else 0f,
    )
}

private fun dimensions(quality: SourceQuality?): String? = when (quality) {
    SourceQuality.P2160 -> "3840 × 2160"
    SourceQuality.P1440 -> "2560 × 1440"
    SourceQuality.P1080 -> "1920 × 1080"
    SourceQuality.P720 -> "1280 × 720"
    SourceQuality.P480 -> "854 × 480"
    SourceQuality.SD, null -> null
}

private fun rangeName(range: String): String = when (range) {
    "DV" -> "Dolby Vision"
    "HLG" -> "HLG"
    "HDR10" -> "HDR10"
    else -> "HDR"
}

private fun codecName(codec: String): String = when (codec) {
    "HEVC" -> "H.265"
    "HEVC 10-BIT" -> "H.265 10-BIT"
    else -> codec
}

private fun soundName(audio: String?): String {
    val text = audio?.uppercase() ?: return "Not listed"
    return when (text) {
        "ATMOS" -> "Dolby Atmos"
        "TRUEHD" -> "Dolby TrueHD"
        "DTS-HD", "DTS" -> text
        else -> {
            val channels = text.substringAfter(' ', missingDelimiterValue = "")
            when {
                channels.isEmpty() -> text
                channels == "2.0" -> "Stereo"
                else -> "Surround $channels"
            }
        }
    }
}

private val LanguageNames = mapOf(
    "ENG" to "English", "EN" to "English", "JPN" to "Japanese", "JA" to "Japanese",
    "GER" to "German", "DEU" to "German", "DE" to "German", "FRE" to "French", "FRA" to "French",
    "FR" to "French", "SPA" to "Spanish", "ES" to "Spanish", "ITA" to "Italian", "IT" to "Italian",
    "KOR" to "Korean", "KO" to "Korean", "CHI" to "Chinese", "ZHO" to "Chinese", "ZH" to "Chinese",
    "HIN" to "Hindi", "HI" to "Hindi", "RUS" to "Russian", "RU" to "Russian",
    "POR" to "Portuguese", "PT" to "Portuguese", "DUT" to "Dutch", "NLD" to "Dutch", "NL" to "Dutch",
    "NOR" to "Norwegian", "SWE" to "Swedish", "DAN" to "Danish", "FIN" to "Finnish",
    "POL" to "Polish", "TUR" to "Turkish", "ARA" to "Arabic", "CES" to "Czech", "CZE" to "Czech",
    "ELL" to "Greek", "GRE" to "Greek", "RON" to "Romanian", "RUM" to "Romanian",
    "SLK" to "Slovak", "SLO" to "Slovak", "MULTI" to "Several languages", "DUAL" to "Two languages",
)

/** A release or subtitle language token in words; unknown tokens pass through. */
fun languageName(code: String): String = LanguageNames[code.trim().uppercase()] ?: code

fun countLabel(count: Int, singular: String, plural: String): String =
    "$count ${if (count == 1) singular else plural}"

/** Sentence fragments completing "<addon> …" in the banner and the footer. */
fun addonFailureCopy(code: String?): String = when (code) {
    "timeout" -> "did not answer in time"
    "upstream_http" -> "returned an error"
    "blocked_target" -> "was blocked for safety"
    "invalid_response" -> "returned invalid data"
    else -> "is unavailable"
}

/**
 * An addon's name as the page may print it: control characters dropped, and
 * anything that looks like a URL, a path or a token replaced outright, since
 * a misconfigured addon can report its manifest URL or a key as its name.
 */
fun sanitizeAddonName(name: String?): String {
    val cleaned = name?.filter { it.code >= 0x20 && it.code != 0x7f }?.trim()
    if (cleaned.isNullOrEmpty() || "://" in cleaned || '/' in cleaned || '\\' in cleaned) return "An addon"
    if (CredentialLike.containsMatchIn(cleaned)) return "An addon"
    return cleaned.take(80)
}

private val CredentialLike = Regex("[A-Za-z0-9_-]{32,}")
