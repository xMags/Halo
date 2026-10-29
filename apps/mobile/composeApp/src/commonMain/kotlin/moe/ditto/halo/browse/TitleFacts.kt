package moe.ditto.halo.browse

import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.ManifestCatalog
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.Stream
import moe.ditto.halo.screens.player.streamBadges
import moe.ditto.halo.sync.runtimeMinutes

/*
 * What the title page says about a title beyond its art: the cast row, the
 * About card, the trailer, what the addons offer to play, where "More like
 * this" comes from, and how a long season is paged. Ports of the desktop
 * app's `browse/detail/detailFacts.ts`, `browse/similarTitles.ts` and
 * `browse/detail/episodePages.ts`; keep them in step.
 */

/** A person on the cast row: the part they play, or their crew role. */
data class Credit(val name: String, val role: String?, val photo: String?)

/**
 * The cast row: the addon's pictured cast when it sends one (TMDB), else the
 * plain names every addon has; the director(s) close the row either way. A
 * name appears once, at its first credit.
 */
fun creditsOf(meta: MetaDetail): List<Credit> {
    val pictured = meta.appExtras?.cast.orEmpty()
    val cast = if (pictured.isNotEmpty()) {
        pictured.map { Credit(it.name, it.character?.ifBlank { null }, it.photo?.ifBlank { null }) }
    } else {
        meta.cast.map { Credit(it, null, null) }
    }
    val crew = meta.director.map { Credit(it, "Director", null) }
    val seen = mutableSetOf<String>()
    return (cast + crew).filter { credit ->
        val key = credit.name.trim().lowercase()
        key.isNotEmpty() && seen.add(key)
    }
}

/**
 * Where a cast entry leads: IMDb's people search for the name. Addons send no
 * person id, so a search is the honest link; IMDb because its ratings are the
 * ones the page shows.
 */
fun personSearchUrl(name: String): String = "https://www.imdb.com/find/?q=${encodeQuery(name.trim())}&s=nm"

/** A YouTube video id as YouTube issues them; anything else is not linked to. */
private val YouTubeId = Regex("^[A-Za-z0-9_-]{6,20}$")

/**
 * The title's trailer on YouTube, from the addon's trailer streams or its
 * older trailer list; null when it sends none. The id comes from an addon, so
 * it is checked before it becomes a link the system opens.
 */
fun trailerUrl(meta: MetaDetail): String? {
    val candidates = meta.trailerStreams.map { it.ytId } +
        meta.trailers.filter { it.type == null || it.type.lowercase() == "trailer" }.map { it.source }
    val id = candidates.firstOrNull { it != null && YouTubeId.matches(it) } ?: return null
    return "https://www.youtube.com/watch?v=$id"
}

/** `2 h 30 min` from an addon's `150 min`; the addon's own text when it is not minutes. */
fun formatRuntime(runtime: String?): String? {
    if (runtime.isNullOrBlank()) return null
    val minutes = runtimeMinutes(runtime)?.takeIf { it > 0 } ?: return runtime
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours h"
        else -> "$hours h $rest min"
    }
}

/** `18 July 2025` from an ISO date; null when there is none or it does not parse. */
fun formatReleaseDate(released: String?): String? {
    val match = released?.let { IsoDate.find(it) } ?: return null
    val (year, month, day) = match.destructured
    val monthName = MonthNames.getOrNull(month.toInt() - 1) ?: return null
    val dayNumber = day.toInt().takeIf { it in 1..31 } ?: return null
    return "$dayNumber $monthName $year"
}

/** The About card's rows, in reading order; facts the addon does not send are left out. */
fun aboutRows(meta: MetaDetail, isSeries: Boolean): List<Pair<String, String>> = listOfNotNull(
    meta.director.joinToString(", ").ifBlank { null }?.let { "Director" to it },
    meta.writer.joinToString(", ").ifBlank { null }?.let { "Writers" to it },
    formatReleaseDate(meta.released)?.let { "Released" to it },
    // A series' runtime is an episode's, which its rows already show.
    if (isSeries) null else formatRuntime(meta.runtime)?.let { "Runtime" to it },
    meta.genres.take(3).joinToString(", ").ifBlank { null }?.let { "Genres" to it },
    meta.country?.trim()?.ifBlank { null }?.let { "Country" to it },
    meta.language?.trim()?.ifBlank { null }?.let { "Language" to it },
    meta.awards?.trim()?.ifBlank { null }?.let { "Awards" to it },
)

/** One quality the addons offer, as the source picker names it: `4K`, `FULL HD`, `HD`, `SD`, `OTHER`. */
data class QualityGroup(val label: String, val count: Int, val rank: Int)

data class Availability(val groups: List<QualityGroup>, val total: Int) {
    /** The best quality on offer, when anything is known about any source. */
    val best: QualityGroup? get() = groups.firstOrNull { it.label != OtherTier }
}

private const val OtherTier = "OTHER"

/** Resolution badge → tier label, best first; the index is the rank. */
private val Tiers = listOf(
    "2160p" to "4K",
    "1440p" to "2K",
    "1080p" to "FULL HD",
    "720p" to "HD",
    "576p" to "SD",
    "480p" to "SD",
)

/**
 * What the addons offer for one video, grouped the way the picker shows it.
 * Tiers that share a label (576p and 480p both read `SD`) count together, at
 * the better of their ranks; a source whose naming states no resolution is
 * `OTHER`, last.
 */
fun summarizeAvailability(streams: List<Stream>): Availability {
    val groups = LinkedHashMap<String, QualityGroup>()
    for (stream in streams) {
        val resolution = streamBadges(stream.behaviorHints?.filename, stream.title ?: stream.description, stream.name)
            .firstOrNull { badge -> Tiers.any { it.first == badge } }
        val rank = Tiers.indexOfFirst { it.first == resolution }.takeIf { it >= 0 } ?: Tiers.size
        val label = Tiers.getOrNull(rank)?.second ?: OtherTier
        val current = groups[label]
        groups[label] = if (current == null) QualityGroup(label, 1, rank) else current.copy(count = current.count + 1, rank = minOf(current.rank, rank))
    }
    return Availability(groups.values.sortedBy { it.rank }, streams.size)
}

/** Where a title page's "More like this" row comes from. */
data class GenreCatalog(val addonId: String, val catalogId: String, val genre: String)

/** Whether a catalog can be asked for this one genre with nothing else required. */
private fun acceptsGenreAlone(catalog: ManifestCatalog, genre: String): Boolean {
    val declared = catalog.extra.firstOrNull { it.name == "genre" }
    if (declared == null && "genre" !in catalog.extraSupported) return false
    // A catalog that lists its genres only answers for those.
    val options = declared?.options.orEmpty()
    if (options.isNotEmpty() && genre !in options) return false
    val required = catalog.extra.filter { it.isRequired }.map { it.name } + catalog.extraRequired
    return required.all { it == "genre" }
}

/**
 * The first catalog, in resolution order, that lists titles of this type by
 * the title's own genre: its leading genre if any addon can filter by it,
 * else the next one. Addons whose catalogs are hidden from Home come back
 * with none, so they are not used here either.
 */
fun pickGenreCatalog(addons: List<AddonEntry>, type: String, genres: List<String>): GenreCatalog? {
    for (genre in genres) {
        for (addon in addons) {
            val catalog = addon.manifest.catalogs.firstOrNull { it.type == type && acceptsGenreAlone(it, genre) }
            if (catalog != null) return GenreCatalog(addon.id, catalog.id, genre)
        }
    }
    return null
}

/** Above this many episodes a season is shown a page at a time. */
const val EpisodePageSize = 25

/** "Episodes 1–25", by the episodes' own numbers where they have them. */
data class EpisodePage(val label: String, val episodes: List<MetaVideo>)

/**
 * A season's episodes in pages of [size], in order. A season of up to [size]
 * episodes is one page, which the screen shows without a picker: only very
 * long seasons (daily soaps, long-running anime with every episode in one
 * season) need paging.
 */
fun episodePages(episodes: List<MetaVideo>, size: Int = EpisodePageSize): List<EpisodePage> =
    episodes.chunked(size).mapIndexed { index, chunk ->
        val start = index * size
        val first = chunk.first().episode ?: (start + 1)
        val last = chunk.last().episode ?: (start + chunk.size)
        EpisodePage(if (first == last) "Episode $first" else "Episodes $first–$last", chunk)
    }

/** The page holding [videoId], or the first page when no page does. */
fun pageContaining(pages: List<EpisodePage>, videoId: String?): Int {
    if (videoId == null) return 0
    return pages.indexOfFirst { page -> page.episodes.any { it.id == videoId } }.coerceAtLeast(0)
}

private val IsoDate = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")

private val MonthNames = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/** Percent-encodes everything but unreserved characters, as `encodeURIComponent` does for a query value. */
private fun encodeQuery(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val char = byte.toInt().toChar()
        if (char.isLetterOrDigit() && byte >= 0 || char in "-_.!~*'()") {
            append(char)
        } else {
            append('%')
            append(HexDigits[(byte.toInt() shr 4) and 0xF])
            append(HexDigits[byte.toInt() and 0xF])
        }
    }
}

private const val HexDigits = "0123456789ABCDEF"
