package moe.ditto.halo.browse

import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.api.MetaPreview

/*
 * How a search across several addons' catalogs becomes a list worth reading:
 * which title leads, what order the rest take and which are not worth showing.
 * A port of `@halo/core`'s `search/results.ts`, which the desktop runs; keep
 * the two in step, down to the numbers, so both apps answer a query alike.
 */

/** Latin letters with their accents, each followed by the plain letter it folds to. */
private const val AccentFolds =
    "àaáaâaãaäaåaāaăaąaçcćcčcďdèeéeêeëeēeėeęeěeğgìiíiîiïiīiįiıiłlñnńnňnòoóoôoõoöoøoōoőoŕrřrśsšsşsťtùuúuûuüuūuůuűuųuýyÿyźzżzžz"

private val FoldedLetters: Map<Char, Char> =
    AccentFolds.chunked(2).associate { pair -> pair[0] to pair[1] }

/** Lower case without accents, so "Amélie" is found by "amelie". */
fun foldedText(text: String): String =
    buildString(text.length) { text.lowercase().forEach { append(FoldedLetters[it] ?: it) } }

/** A name or query as plain words: folded, punctuation as spaces. */
private fun plainWords(text: String): List<String> =
    foldedText(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

private const val ExactName = 0

/** The worst relevance that still means the name begins with what was typed. */
private const val BeginsWithQuery = 2

/** The relevance of a name that shares no word with the query. */
private const val UnrelatedName = 6

/** Results with a year and artwork that make the ones without artwork not worth showing. */
private const val EnoughComplete = 8

/**
 * How well a title's name answers the query, lower is better: the name is the
 * query (0), starts with its words (1), starts with it mid-word, as "Doctor
 * Stranger" does for "doctor strange" and any title does while its name is
 * still being typed (2), holds every word of it (3), holds every word inside
 * longer words (4), holds some of them (5), or none (6). Addons answer from
 * descriptions and alternative titles too, which is how a search for one film
 * returns titles that share no word with it.
 */
fun nameRelevance(name: String, query: String): Int {
    val wanted = plainWords(query)
    if (wanted.isEmpty()) return ExactName
    val words = plainWords(name)
    val joined = words.joinToString(" ")
    val phrase = wanted.joinToString(" ")
    return when {
        joined == phrase -> ExactName
        joined.startsWith("$phrase ") -> 1
        joined.startsWith(phrase) -> BeginsWithQuery
        wanted.all { it in words } -> 3
        wanted.all { joined.contains(it) } -> 4
        wanted.any { joined.contains(it) } -> 5
        else -> UnrelatedName
    }
}

private val TmdbMissingImage = Regex("/t/p/[a-z0-9]+null$", RegexOption.IGNORE_CASE)

/**
 * Whether an addon's answer already says the title has no poster: none sent,
 * or the TMDB addon's link built from a missing image, `…/t/p/w500null`.
 */
fun hasNoPoster(meta: MetaCard): Boolean {
    val poster = meta.poster
    return poster.isNullOrBlank() || TmdbMissingImage.containsMatchIn(poster)
}

private val ReleaseYearDigits = Regex("""\d{4}""")
private val ImdbTitleId = Regex("""^tt\d+$""")

/**
 * The results as the screen shows them: what was asked for first, and without
 * what nobody asked for.
 *
 * - A title sharing no word with the query is left out, as long as some
 *   result does name it (when none does, as with an addon answering in
 *   another language, everything stays).
 * - A title with neither a release year nor artwork is left out: there is
 *   nothing to show for it and nothing to play.
 * - The rest are ordered by name relevance, with the ones missing a year or
 *   artwork after all that have both.
 * - Once there is a screenful with both ([EnoughComplete]), the ones without
 *   artwork are left out too.
 *
 * Artwork is judged by what the answer says ([hasNoPoster]): a title Stremio
 * can picture by its IMDb id is taken to have some. [missingArt] holds the
 * keys ([titleKey]) of titles whose artwork is known not to load.
 */
fun <T : MetaCard> refineResults(metas: List<T>, query: String, missingArt: Set<String> = emptySet()): List<T> {
    data class Entry(val meta: T, val relevance: Int, val undated: Boolean, val artless: Boolean) {
        val incomplete: Boolean get() = undated || artless
    }
    val entries = metas.map { meta ->
        val knownMissing = titleKey(meta) in missingArt
        Entry(
            meta = meta,
            relevance = nameRelevance(meta.name, query),
            undated = meta.releaseInfo?.let(ReleaseYearDigits::containsMatchIn) != true,
            artless = if (hasNoPoster(meta)) !ImdbTitleId.matches(meta.id) || knownMissing else knownMissing,
        )
    }
    val someNamed = entries.any { it.relevance < UnrelatedName }
    val named = entries.filterNot { someNamed && it.relevance == UnrelatedName }
    val plenty = named.count { !it.incomplete } >= EnoughComplete
    return named
        .filterNot { it.artless && (it.undated || plenty) }
        // sortedWith is stable, so the addons' own ranking holds within a level.
        .sortedWith(compareBy<Entry> { it.incomplete }.thenBy { it.relevance })
        .map { it.meta }
}

/**
 * The best guess for the query. Each catalog's own first title is a
 * candidate. The first of them, in addon order, whose name is or begins with
 * what was typed wins: a search for a series must not be topped by a film only
 * because films are asked first. Failing any, the best-named candidate wins,
 * the earlier catalog on a tie.
 */
fun searchTopMatch(groups: List<SearchResultGroup>, query: String): MetaPreview? {
    val candidates = groups.mapNotNull { it.metas.firstOrNull() }
    val ranked = candidates.map { it to nameRelevance(it.name, query) }
    return ranked.firstOrNull { it.second <= BeginsWithQuery }?.first
        ?: ranked.minByOrNull { it.second }?.first
}

/** Stremio's own poster host, which a card tries by IMDb id anyway. */
private fun isMetahubPoster(poster: String?): Boolean =
    poster != null && poster.startsWith("https://images.metahub.space/")

/**
 * The better-ranked copy of a title, with what only the other copy knew.
 * Cinemeta ranks well but sends a name, a year and a poster link that is often
 * dead; the TMDB addon sends a rating and a poster that loads. The id stays
 * the kept copy's, since that is the one streams are found by.
 */
fun MetaPreview.withDetailsFrom(other: MetaPreview): MetaPreview = copy(
    poster = if ((hasNoPoster(this) || isMetahubPoster(poster)) && !hasNoPoster(other)) other.poster else poster,
    imdbRating = imdbRating?.takeIf { it.isNotBlank() } ?: other.imdbRating,
    releaseInfo = releaseInfo?.takeIf { it.isNotBlank() } ?: other.releaseInfo,
    description = description?.takeIf { it.isNotBlank() } ?: other.description,
)

/**
 * A rating that is one: its number, or null. Addons send "0.0" (or nothing a
 * number can be read from) for a title nobody has rated, and "★ 0.0" reads as
 * a verdict on a film that simply has none.
 */
fun ratedValue(imdbRating: String?): String? {
    val value = imdbRating?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val rating = value.toDoubleOrNull() ?: return null
    return value.takeIf { rating.isFinite() && rating > 0.0 }
}
