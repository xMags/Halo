package moe.ditto.halo.browse

import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.MetaPreview

/*
 * How one query's answers become the results Search shows, and how they
 * change afterwards. The catalogs' answers are ranked once, when the results
 * are revealed; from then on a late answer only fills in the details of a
 * title already shown or appends titles after the last one, judged by the
 * thresholds the reveal was judged by. Nothing shown moves or goes until the
 * next query. A port of `@halo/core`'s `search/board.ts`; both are held to
 * the shared vectors in `packages/core/test/vectors/search-board.json`.
 */

/** Short enough to feel live while typing, long enough not to ask every addon per keystroke. */
const val SearchDebounceMs = 250L
/** No title is longer, and the server caps a catalog's search text well above it. */
const val SearchMaximumQuery = 120
/** At most this many recent searches, as many as fit on one line. */
const val SearchRecentShown = 6
const val SearchSuggestionLimit = 5
/** No later than this after asking, once any catalog has answered with titles. */
const val SearchRevealDeadlineMs = 1_200L
/** A person's films are waited for this long after asking, so they rank among the results. */
const val SearchCreditsDeadlineMs = 2_000L
/** The first titles' poster links are checked before the reveal: this many, for no longer than the deadline. */
const val SearchPosterCheckLimit = 24
const val SearchPosterCheckDeadlineMs = 2_000L

/** One searchable catalog: an addon's film or series catalog that takes a `search` extra and needs nothing else. */
data class BoardTarget(
    val key: String,
    val addonId: String,
    val addonName: String,
    val type: String,
    val catalogId: String,
    val title: String,
    /** Where its addon stands in the effective order. */
    val addonRank: Int,
)

/** One catalog's answer, with where its addon stands in the effective order. */
data class SearchAnswer(val key: String, val titles: List<MetaPreview>, val addonRank: Int)

/** What was typed, trimmed, with its name and year read apart: "dune 2021" is "dune" from 2021. */
data class SearchQueryParts(val full: String, val name: String, val year: Int?)

/** The first film was shot in 1888; a year beyond the next two is part of a name. */
private const val EarliestYear = 1888
private const val YearsAhead = 2
private val WhitespaceRun = Regex("""${ScriptSpaceClass}+""")
private val FourDigits = Regex("""^[0-9]{4}$""")

/**
 * A trailing four-digit number from 1888 to [currentYear] + 2 is the year, and
 * the name is the query without it. It needs a word before it, so a query that
 * is only a year ("1917") keeps it as the name.
 */
fun parseSearchQuery(query: String, currentYear: Int): SearchQueryParts {
    val full = query.scriptTrim()
    val plain = SearchQueryParts(full, full, null)
    val words = full.split(WhitespaceRun).filter { it.isNotEmpty() }
    if (words.size < 2) return plain
    val last = words.last()
    if (!FourDigits.matches(last)) return plain
    val year = last.toInt()
    if (year < EarliestYear || year > currentYear + YearsAhead) return plain
    val name = full.substring(0, full.length - last.length).scriptTrim()
    if (name.isEmpty()) return plain
    return SearchQueryParts(full, name, year)
}

/** At most [SearchMaximumQuery] UTF-16 units, never splitting a surrogate pair. */
fun cappedQuery(text: String): String {
    if (text.length <= SearchMaximumQuery) return text
    var length = SearchMaximumQuery
    if (text[length - 1].isHighSurrogate()) length -= 1
    return text.substring(0, length)
}

/**
 * Below this a query matches almost everything, so asking every catalog costs
 * one request each and returns noise.
 */
const val MinSearchTermLength = 2

/** Whether a query, trimmed, is long enough to search. */
fun isSearchableQuery(query: String): Boolean = query.scriptTrim().length >= MinSearchTermLength

/** Whether a catalog of this kind is searched: films and series, never TV channels or debrid file lists. */
fun isSearchedCatalogType(type: String): Boolean = type == "movie" || type == "series"

/**
 * The catalogs a search asks, in addon order: films and series that take only
 * the `search` extra. A catalog that insists on another extra (a genre) cannot
 * answer the query alone and is left out rather than reported as failing.
 */
fun boardTargets(addons: List<AddonEntry>): List<BoardTarget> = addons.flatMapIndexed { rank, addon ->
    addon.manifest.catalogs
        .filter { isSearchedCatalogType(it.type) && it.supportsSearch && it.needsOnlySearch }
        .map { catalog ->
            BoardTarget(
                key = "${addon.id}/${catalog.type}/${catalog.id}",
                addonId = addon.id,
                addonName = addon.manifest.name,
                type = catalog.type,
                catalogId = catalog.id,
                title = catalog.name ?: addon.manifest.name,
                addonRank = rank,
            )
        }
}

/** One title of the merged answers and what it is ranked by. */
class RankedTitle internal constructor(
    var title: MetaPreview,
    /** The better of its name's relevance to the full query and to the name alone; lower is better. */
    var tier: Int,
    /** The query has a year and the title's release line has the same one. */
    var yearMatches: Boolean,
    /** A release year and a poster that can be drawn. */
    var complete: Boolean,
    /** How many catalogs listed it. */
    var copies: Int,
    /** The best index it had in any catalog's own list. */
    var bestPosition: Int,
    /** The best rank of any addon that listed it. */
    var addonRank: Int,
    /** Every identity any of its copies is known by. */
    val identities: MutableList<String>,
)

/** The thresholds the revealed list was judged by, kept so later answers are judged by the same. */
data class RevealCutoffs(
    /** Some title shares a word with the query, so those sharing none go. */
    val dropUnrelated: Boolean = false,
    /** Enough titles hold every word, so those holding only some go. */
    val dropSomeWords: Boolean = false,
    /** Enough complete titles, so those without a poster go. */
    val dropArtless: Boolean = false,
    /** The query is a person's name: their complete titles stay although their names share no word with it. */
    val keepCredited: Boolean = false,
)

/** What the words typed stand for: a title's name (or nothing known), or exactly some person's name. */
enum class SearchQueryMeaning { Title, Person }

private const val ExactNameTier = 0
private const val SomeWordsTier = 5
private const val UnrelatedTier = 6
/** Titles holding every word that make the ones holding only some not worth showing. */
private const val EnoughNamedTitles = 8
/** Titles with a year and artwork that make the ones without artwork not worth showing. */
private const val EnoughCompleteTitles = 8

private val ImdbTitle = Regex("""^tt[0-9]+$""")
private val YearDigits = Regex("""[0-9]{4}""")
// JavaScript's `\b` after the digits: its word characters are ASCII only.
private val ProductCode = Regex("""^[A-Z]{2,6}-[0-9]{3,5}(?![A-Za-z0-9_])""")
private val MetahubPoster = Regex("""^https://[a-z0-9-]+\.metahub\.space/""")

fun isImdbId(id: String): Boolean = ImdbTitle.matches(id)

/** The first four-digit year of a release line: `2021`, `2016–2025` and `2024-` all give their start. */
fun releaseYear(releaseInfo: String?): String? = releaseInfo?.let { YearDigits.find(it)?.value }

/** A name that opens with a studio's product code, `ABCD-123 …`: how adult video is catalogued. */
fun isProductCodeName(name: String): Boolean = ProductCode.containsMatchIn(name)

/** Whether a title has no poster worth drawing: none or a stand-in unless it has an IMDb id (metahub draws those), or a link known to lead nowhere. */
fun isArtless(title: MetaPreview): Boolean = title.posterMissing || (hasNoPoster(title) && !isImdbId(title.id))

/**
 * The better-ranked copy of a title, with what only the other copy knew: a
 * poster that loads where it has none (or only metahub's stand-in, or one
 * known to lead nowhere), a rating, release line or description. Id, kind and
 * name stay the kept copy's.
 */
fun fillMissingDetails(kept: MetaPreview, other: MetaPreview): MetaPreview {
    // A copy whose poster is known not to load has no poster at all.
    val otherHasNone = hasNoPoster(other) || other.posterMissing
    val keptPosterWeak = hasNoPoster(kept) || (kept.poster != null && MetahubPoster.containsMatchIn(kept.poster)) || kept.posterMissing
    val poster = if (keptPosterWeak) (if (otherHasNone) kept.poster else other.poster) else kept.poster
    return kept.copy(
        poster = if (poster.isNullOrEmpty()) kept.poster else poster,
        imdbRating = if (!kept.imdbRating.isNullOrEmpty() || other.imdbRating.isNullOrEmpty()) kept.imdbRating else other.imdbRating,
        releaseInfo = if (!kept.releaseInfo.isNullOrEmpty() || other.releaseInfo.isNullOrEmpty()) kept.releaseInfo else other.releaseInfo,
        description = if (!kept.description.isNullOrEmpty() || other.description.isNullOrEmpty()) kept.description else other.description,
        posterMissing = kept.posterMissing && otherHasNone,
    )
}

/**
 * Two copies of one title as one. The better-ranked copy leads, unless only
 * the other is named by its IMDb id: that is the id every addon can find
 * streams and details by.
 */
fun mergeCopies(kept: MetaPreview, other: MetaPreview): MetaPreview =
    if (isImdbId(kept.id) || !isImdbId(other.id)) fillMissingDetails(kept, other) else fillMissingDetails(other, kept)

private fun score(ranked: RankedTitle, query: SearchQueryParts) {
    var tier = nameRelevance(ranked.title.name, query.full)
    if (query.name != query.full) tier = minOf(tier, nameRelevance(ranked.title.name, query.name))
    ranked.tier = tier
    ranked.yearMatches = query.year != null && releaseYear(ranked.title.releaseInfo)?.toInt() == query.year
    ranked.complete = releaseYear(ranked.title.releaseInfo) != null && !isArtless(ranked.title)
}

/** A single title ranked against the query, as one copy at [position] of an addon at [addonRank]. */
fun rankOne(title: MetaPreview, query: SearchQueryParts, position: Int, addonRank: Int): RankedTitle {
    val ranked = RankedTitle(title, UnrelatedTier, false, false, 1, position, addonRank, titleIdentities(title).toMutableList())
    score(ranked, query)
    return ranked
}

/** Whether the title's name is exactly the query. */
fun namesQueryExactly(title: RankedTitle): Boolean = title.tier == ExactNameTier

/**
 * The thresholds for a reveal. A person's name counts as one only while no
 * title is named exactly that; then the person's work stays and titles holding
 * only some of the name's words go, however few hold all of them.
 */
fun computeCutoffs(ranked: List<RankedTitle>, meaning: SearchQueryMeaning = SearchQueryMeaning.Title): RevealCutoffs {
    val person = meaning == SearchQueryMeaning.Person && ranked.none(::namesQueryExactly)
    val dropUnrelated = ranked.any { it.tier < UnrelatedTier }
    val dropSomeWords = ranked.count { it.tier < SomeWordsTier } >= EnoughNamedTitles || person
    // Counted among what the name rules keep, as the screen would list it.
    val byName = RevealCutoffs(dropUnrelated, dropSomeWords, dropArtless = false, keepCredited = person)
    val dropArtless = ranked.count { it.complete && passesCutoffs(it, byName) } >= EnoughCompleteTitles
    return RevealCutoffs(dropUnrelated, dropSomeWords, dropArtless, person)
}

/** Whether a title is shown under these thresholds. One with neither a poster nor a year never is. */
fun passesCutoffs(title: RankedTitle, cutoffs: RevealCutoffs): Boolean {
    if (cutoffs.dropUnrelated && title.tier >= UnrelatedTier && !(cutoffs.keepCredited && title.complete)) return false
    if (cutoffs.dropSomeWords && title.tier == SomeWordsTier) return false
    if (!isArtless(title.title)) return true
    return releaseYear(title.title.releaseInfo) != null && !cutoffs.dropArtless
}

/**
 * Every answer as one list: product codes dropped, years read out of names,
 * copies of one title merged, then ordered by tier, a matching year (when the
 * query has one), completeness, more copies, a better position in a catalog's
 * own list, and a better addon rank. Copies are merged in turns by position
 * and then addon rank, so the result does not depend on the order the answers
 * are passed in.
 */
fun rankForReveal(answers: List<SearchAnswer>, query: SearchQueryParts): List<RankedTitle> {
    // By addon rank, the order passed in breaking a tie (two catalogs of one addon); sortedBy is stable.
    val order = answers.indices.sortedBy { answers[it].addonRank }
    val longest = answers.maxOfOrNull { it.titles.size } ?: 0
    val merged = mutableListOf<RankedTitle>()
    /** Which answers listed each merged title, to count copies once per catalog. */
    val listedBy = mutableListOf<MutableSet<Int>>()
    val placed = mutableMapOf<String, Int>()
    fun claim(index: Int, identities: List<String>) {
        for (identity in identities) {
            if (identity in placed) continue
            placed[identity] = index
            merged[index].identities += identity
        }
    }
    for (position in 0 until longest) {
        for (answerIndex in order) {
            val answer = answers[answerIndex]
            val listed = answer.titles.getOrNull(position) ?: continue
            if (isProductCodeName(listed.name)) continue
            val title = listed.withYearOutOfName()
            val identities = titleIdentities(title)
            val kept = identities.firstNotNullOfOrNull { placed[it] }
            if (kept == null) {
                val index = merged.size
                merged += rankOne(title, query, position, answer.addonRank).also { it.identities.clear() }
                listedBy += mutableSetOf(answerIndex)
                claim(index, identities)
                continue
            }
            val entry = merged[kept]
            entry.title = mergeCopies(entry.title, title)
            if (listedBy[kept].add(answerIndex)) entry.copies += 1
            entry.bestPosition = minOf(entry.bestPosition, position)
            entry.addonRank = minOf(entry.addonRank, answer.addonRank)
            claim(kept, identities)
            // A copy that brought a year makes the merged title known by its name and year too.
            claim(kept, titleIdentities(entry.title))
        }
    }
    merged.forEach { score(it, query) }
    val hasYear = query.year != null
    return merged.sortedWith(
        compareBy<RankedTitle> { it.tier }
            .thenBy { if (hasYear && !it.yearMatches) 1 else 0 }
            .thenBy { if (it.complete) 0 else 1 }
            .thenByDescending { it.copies }
            .thenBy { it.bestPosition }
            .thenBy { it.addonRank },
    )
}

/** The first title when it is named by the query (tier 0 or 1) and, when the query has a year, from that year. */
fun chooseRevealTopMatch(ranked: List<RankedTitle>, query: SearchQueryParts): Int? {
    val first = ranked.firstOrNull() ?: return null
    if (first.tier > 1) return null
    if (query.year != null && !first.yearMatches) return null
    return 0
}

/**
 * The poster links of the first [limit] titles a reveal of these answers would
 * show, in screen order: the ones worth knowing to be missing before the
 * results are laid out.
 */
fun revealPosterLinks(answers: List<SearchAnswer>, query: SearchQueryParts, meaning: SearchQueryMeaning, limit: Int): List<String> {
    val ranked = rankForReveal(answers, query)
    val cutoffs = computeCutoffs(ranked, meaning)
    val links = mutableListOf<String>()
    var shown = 0
    for (entry in ranked) {
        if (shown == limit) break
        if (!passesCutoffs(entry, cutoffs)) continue
        shown += 1
        val poster = entry.title.poster
        if (!hasNoPoster(entry.title) && poster != null) links += poster
    }
    return links
}

enum class RevealDecision { Wait, Reveal }

/**
 * Whether a query's results are shown yet. Every catalog settled (or none to
 * ask) reveals at once; otherwise the deadline reveals once some answer has
 * titles, and with none the page waits for the first one or for every
 * catalog to settle.
 */
fun decideReveal(pending: Int, total: Int, answeredWithItems: Int, sinceAskedMs: Long): RevealDecision {
    if (total == 0 || pending == 0) return RevealDecision.Reveal
    if (sinceAskedMs >= SearchRevealDeadlineMs && answeredWithItems > 0) return RevealDecision.Reveal
    return RevealDecision.Wait
}

/** What one query's results showed when they were revealed. */
data class RevealedSet(val titles: List<MetaPreview>, val topMatch: MetaPreview?, val cutoffs: RevealCutoffs)

/** What a later answer changed: titles already shown that gained details, in place, and new titles after the last. */
data class BoardDiff(
    /** Board index and the title as it now reads (same id, kind and name). */
    val updates: List<Pair<Int, MetaPreview>>,
    /** In order; the first lands at the index after the board's last title before this answer. */
    val appends: List<MetaPreview>,
    /** The top match as it now reads, when a later copy filled it in. */
    val topMatchUpdate: MetaPreview?,
) {
    val isEmpty: Boolean get() = updates.isEmpty() && appends.isEmpty() && topMatchUpdate == null
}

/** Where an identity's title sits: a board index, or the top match. */
private const val TopMatchSlot = -1

/** The fields [fillMissingDetails] can change, so an answer that changes none of them is no update. */
private fun sameDetails(a: MetaPreview, b: MetaPreview): Boolean =
    a.poster == b.poster && a.posterMissing == b.posterMissing && a.imdbRating == b.imdbRating &&
        a.releaseInfo == b.releaseInfo && a.description == b.description

/**
 * One query's revealed results, which only ever grow at the end once shown. A
 * later answer fills in the details of a title already shown (never its id,
 * kind, name or place) and appends titles that pass the reveal's thresholds.
 * Only a new board (a new query) lays the list out again. Not thread-safe:
 * one coroutine owns a board.
 */
class SearchBoard {
    var revealed: Boolean = false
        private set
    private var query = SearchQueryParts("", "", null)
    var cutoffs: RevealCutoffs = RevealCutoffs()
        private set
    private val titleList = mutableListOf<MetaPreview>()
    val titles: List<MetaPreview> get() = titleList
    var topMatch: MetaPreview? = null
        private set
    private val slots = mutableMapOf<String, Int>()

    /** Ranks every answer so far and keeps the result, replacing any earlier reveal on this board. */
    fun reveal(answers: List<SearchAnswer>, query: SearchQueryParts, meaning: SearchQueryMeaning = SearchQueryMeaning.Title): RevealedSet {
        this.query = query
        val ranked = rankForReveal(answers, query)
        cutoffs = computeCutoffs(ranked, meaning)
        val kept = ranked.filter { passesCutoffs(it, cutoffs) }
        val top = chooseRevealTopMatch(kept, query)
        titleList.clear()
        slots.clear()
        topMatch = null
        kept.forEachIndexed { index, entry ->
            if (index == top) {
                topMatch = entry.title
                claim(TopMatchSlot, entry.identities)
                return@forEachIndexed
            }
            claim(titleList.size, entry.identities)
            titleList += entry.title
        }
        revealed = true
        return RevealedSet(titleList.toList(), topMatch, cutoffs)
    }

    /** A catalog that answered after the reveal. Ignored before one. */
    fun absorb(late: SearchAnswer): BoardDiff {
        if (!revealed) return BoardDiff(emptyList(), emptyList(), null)
        val updates = mutableListOf<Pair<Int, MetaPreview>>()
        val appends = mutableListOf<MetaPreview>()
        var topUpdate: MetaPreview? = null
        val firstAppend = titleList.size
        late.titles.forEachIndexed { position, listed ->
            if (isProductCodeName(listed.name)) return@forEachIndexed
            val title = listed.withYearOutOfName()
            val identities = titleIdentities(title)
            val slot = find(identities)
            if (slot != null) {
                claim(slot, identities)
                val shown = if (slot == TopMatchSlot) topMatch ?: return@forEachIndexed else titleList[slot]
                val next = fillMissingDetails(shown, title)
                if (sameDetails(next, shown)) return@forEachIndexed
                if (slot == TopMatchSlot) topMatch = next else titleList[slot] = next
                claim(slot, titleIdentities(next))
                when {
                    slot == TopMatchSlot -> topUpdate = next
                    // Appended by this same answer: still news, as it now reads.
                    slot >= firstAppend -> appends[slot - firstAppend] = next
                    else -> {
                        val at = updates.indexOfFirst { it.first == slot }
                        if (at >= 0) updates[at] = slot to next else updates += slot to next
                    }
                }
                return@forEachIndexed
            }
            val ranked = rankOne(title, query, position, late.addonRank)
            if (!passesCutoffs(ranked, cutoffs)) return@forEachIndexed
            claim(titleList.size, ranked.identities)
            appends += ranked.title
            titleList += ranked.title
        }
        return BoardDiff(updates, appends, topUpdate)
    }

    /**
     * The query turned out to be a person's name only after the reveal: from
     * now on their complete titles pass although their names share no word
     * with it. Only ever lets more in. False, changing nothing, before a
     * reveal, when the rule is already on, or when a title shown is named
     * exactly the query.
     */
    fun admitPersonWork(): Boolean {
        if (!revealed || cutoffs.keepCredited) return false
        val namedExactly = { title: MetaPreview -> namesQueryExactly(rankOne(title, query, 0, 0)) }
        if (topMatch?.let(namedExactly) == true || titleList.any(namedExactly)) return false
        cutoffs = cutoffs.copy(keepCredited = true)
        return true
    }

    /** The first title an identity named keeps it, so a later copy never moves a match to another card. */
    private fun claim(slot: Int, identities: List<String>) {
        for (identity in identities) if (identity !in slots) slots[identity] = slot
    }

    private fun find(identities: List<String>): Int? = identities.firstNotNullOfOrNull { slots[it] }
}

/**
 * Past searches holding what is typed, most recent first, then the names
 * found for it (people and titles, best first), each once and never the typed
 * text itself. [history] is newest first.
 */
fun searchSuggestions(history: List<String>, typed: String, found: List<String> = emptyList(), limit: Int = SearchSuggestionLimit): List<String> {
    val needle = foldedText(typed.scriptTrim())
    if (needle.isEmpty()) return emptyList()
    val suggestions = mutableListOf<String>()
    for (term in history) {
        if (suggestions.size == limit) break
        val candidate = foldedText(term)
        if (candidate != needle && candidate.contains(needle)) suggestions += term
    }
    val offered = suggestions.map(::foldedText).toMutableList()
    for (name in found) {
        if (suggestions.size >= limit) break
        val trimmed = name.scriptTrim()
        val key = foldedText(trimmed)
        if (key.isEmpty() || key == needle || key in offered) continue
        offered += key
        suggestions += trimmed
    }
    return suggestions
}

/** The note over a search some catalogs did not answer: every one failed, or the addons that did not, each named once. */
fun searchFailureNote(failedAddonNames: List<String>, total: Int): String {
    if (failedAddonNames.isEmpty()) return ""
    if (failedAddonNames.size == total) return "Search could not reach your addons. Check your connection and try again."
    val names = failedAddonNames.distinct()
    return "${names.joinToString(", ")} did not answer, so ${if (names.size == 1) "its" else "their"} results are missing."
}

/** "Still asking N more catalog(s)…". */
fun searchStillAskingNote(pending: Int): String = "Still asking $pending more ${if (pending == 1) "catalog" else "catalogs"}…"
