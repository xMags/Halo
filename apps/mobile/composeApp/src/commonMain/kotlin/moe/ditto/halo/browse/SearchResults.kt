package moe.ditto.halo.browse

import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.api.MetaPreview

/*
 * The text and artwork rules Search ranks by (SearchBoard.kt): folding a name,
 * how well it answers a query, whether an answer has a poster, a year in a
 * name, and a rating that is one. Ports of `@halo/core`'s search helpers;
 * keep the two in step, down to the numbers, so both apps answer a query alike.
 */

/** The categories a decomposed accent falls in: what JavaScript's `\p{M}` matches. */
private val MarkCategories = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)

/**
 * Lower case without accents, so "Amélie" is found by "amelie": Unicode's
 * canonical decomposition (NFD), its marks dropped, then lower case, as
 * `@halo/core`'s `foldedText` does. Letters that do not decompose (ø, ł, ı,
 * đ) stay as they are on both.
 */
fun foldedText(text: String): String =
    canonicalDecomposition(text).filterNot { it.category in MarkCategories }.lowercase()

/** Short forms titles and people use for the same word; "Dr. Strange" is "Doctor Strange". */
private val ShortForms = mapOf("dr" to "doctor", "mr" to "mister", "vs" to "versus")

/** A name or query as plain words: folded, punctuation as spaces, short forms spelled out. */
private fun plainWords(text: String): List<String> =
    foldedText(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.map { ShortForms[it] ?: it }

private const val ExactName = 0

/** The relevance of a name that begins with what was typed, mid-word. */
private const val BeginsWithQuery = 2

/** The relevance of a name that shares no word with the query. */
private const val UnrelatedName = 6

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

/** The TMDB addon's link built from a missing image: `…/t/p/w500null`. */
private val TmdbMissingImage = Regex("/t/p/[a-z0-9]+null$", RegexOption.IGNORE_CASE)

/**
 * MediaFusion's poster for one of its own `mf…` entries: not artwork but a
 * card it draws with the entry's name on it, since it has no picture for a
 * title it only knows from a file name.
 */
private val GeneratedPoster = Regex("/poster/[a-z]+/mf[0-9]+\\.[a-z]+(\\?.*)?$", RegexOption.IGNORE_CASE)

/** MediaFusion's id for an entry it made from a file name, which is all it has to draw that card from. */
private val FileNameEntry = Regex("""^mf[0-9]+$""")

/**
 * Whether an addon's answer already says the title has no poster: none sent,
 * a link built from a missing image, or a card the addon draws in its place.
 * An entry made from a file name is known by its id as well as by the link,
 * since the link's shape is the addon's to change.
 */
fun hasNoPoster(meta: MetaCard): Boolean {
    if (FileNameEntry.matches(meta.id)) return true
    val poster = meta.poster
    // Only an empty link is none: one of spaces is still a link, however poor.
    return poster.isNullOrEmpty() || TmdbMissingImage.containsMatchIn(poster) || GeneratedPoster.containsMatchIn(poster)
}

private val ReleaseYearDigits = Regex("""[0-9]{4}""")

private val YearInName = Regex("""^(.*${ScriptNonSpaceClass})${ScriptSpaceClass}*\(([0-9]{4})\)$""")

/**
 * A title whose name carries its year, as "Doctor Strange (2016)" does on
 * entries an addon made from file names (MediaFusion's `mf…` ones): the name
 * without it, and the year as the release line when there was none. That is
 * what lets such an entry be recognised as the copy of a real title it is.
 */
fun MetaPreview.withYearOutOfName(): MetaPreview {
    val match = YearInName.find(name) ?: return this
    val dated = releaseInfo?.let(ReleaseYearDigits::containsMatchIn) == true
    return copy(name = match.groupValues[1], releaseInfo = if (dated) releaseInfo else match.groupValues[2])
}

/**
 * A rating that is one: its number, or null. Addons send "0.0" (or nothing a
 * number can be read from) for a title nobody has rated, and "★ 0.0" reads as
 * a verdict on a film that simply has none.
 */
fun ratedValue(imdbRating: String?): String? {
    val value = imdbRating?.scriptTrim()?.takeIf { it.isNotEmpty() } ?: return null
    val rating = value.toDoubleOrNull() ?: return null
    return value.takeIf { rating.isFinite() && rating > 0.0 }
}
