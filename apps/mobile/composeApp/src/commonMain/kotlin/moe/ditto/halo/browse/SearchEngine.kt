package moe.ditto.halo.browse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.api.HaloBackend
import moe.ditto.halo.api.ManifestCatalog
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.auth.EpochClock
import moe.ditto.halo.people.Capabilities
import moe.ditto.halo.people.PeopleSearch
import moe.ditto.halo.people.PersonCredit
import moe.ditto.halo.people.PersonSummary
import moe.ditto.halo.people.choosePeopleRow
import moe.ditto.halo.people.creditTitles
import moe.ditto.halo.people.exactNamePerson
import moe.ditto.halo.people.bestTitleTier

/*
 * One query's search, from the catalogs' answers to what the screen shows.
 * Every film and series catalog that can search is asked at once (with one
 * immediate second try for a catalog that fails, none after a 429), the
 * people named so are asked beside them, and the results are revealed once:
 * when every catalog has answered, or 1.2 s after the query with something
 * to show. From then on late answers only fill in cards or add them after the
 * last ([SearchBoard]), so nothing a viewer is looking at moves. A person's
 * exact name keeps the films the addons found for them, and their own credits
 * are folded in as one more answer. The desktop runs the same steps
 * (`search/useSearchSession.ts`).
 */

/** What a query's search shows at one moment. */
data class SearchSnapshot(
    val query: String,
    /** The results are laid out; until then the screen keeps the previous query's (dimmed) or placeholders. */
    val revealed: Boolean,
    val topMatch: MetaPreview?,
    /** In screen order, without the top match. */
    val titles: List<MetaPreview>,
    /** The people row: at most three, chosen at the reveal's best title tier. */
    val people: List<PersonSummary>,
    /** The name a misspelling was read as, when the people row shows that person. */
    val corrected: String?,
    val pending: Int,
    val total: Int,
    /** Addon names of catalogs that did not answer, each once per catalog. */
    val failedAddons: List<String>,
    /** The addon list itself could not be read: nothing was asked. */
    val addonsFailed: Boolean,
    /** How many catalogs answered with titles; a filter that empties the page says so only then. */
    val answeredWithItems: Int,
)

/** How often an unrevealed query checks whether its reveal is due. */
private const val RevealCheckMs = 100L
/** The named person's films, kept apart from every catalog's key. */
private const val CreditsKey = "people/credits"

/** What one search asks. Every call may throw; a failure reads as no answer. */
internal interface SearchSources {
    suspend fun addons(): List<AddonEntry>
    suspend fun catalog(target: BoardTarget, query: String): List<MetaPreview>
    /** Whether people search is on for this session right now. */
    suspend fun peopleOn(): Boolean
    suspend fun people(query: String): PeopleSearch
    suspend fun credits(tmdbId: Long): List<PersonCredit>
    /** Whether a poster link leads to an image; null when that could not be found out. */
    suspend fun posterLoads(url: String): Boolean?
}

/** The sources of a session's backend: its catalogs, people and the poster checks of [posterProbe]. */
internal class BackendSearchSources(
    private val backend: HaloBackend,
    private val addons: suspend () -> List<AddonEntry>,
    private val capabilities: suspend () -> Capabilities,
    private val posterProbe: PosterProbe,
) : SearchSources {
    override suspend fun addons() = addons.invoke()
    override suspend fun catalog(target: BoardTarget, query: String) =
        backend.getCatalog(target.addonId, target.type, target.catalogId, mapOf(ManifestCatalog.SearchExtra to query)).metas
    override suspend fun peopleOn() = capabilities().people
    override suspend fun people(query: String) = backend.searchPeople(query)
    override suspend fun credits(tmdbId: Long) = backend.getPerson(tmdbId).credits
    override suspend fun posterLoads(url: String) = posterProbe.linkLoads(url)
}

internal class SearchEngine(
    private val sources: SearchSources,
    private val clock: EpochClock,
    private val currentYear: () -> Int,
) {
    private sealed interface Event {
        data class Catalog(val index: Int, val titles: List<MetaPreview>?) : Event
        data class People(val answer: PeopleSearch?) : Event
        data class Credits(val titles: List<MetaPreview>) : Event
        data class Poster(val link: String, val loads: Boolean?) : Event
        data object Tick : Event
    }

    /** The search for [query] (trimmed, searchable), as snapshots after each thing that changes it. */
    fun run(query: String): Flow<SearchSnapshot> = channelFlow {
        val askedAt = clock.nowMs()
        val events = Channel<Event>(Channel.UNLIMITED)
        val targets = try {
            boardTargets(sources.addons())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        val board = SearchBoard()
        val answers = arrayOfNulls<List<MetaPreview>>(targets?.size ?: 0)
        val settled = BooleanArray(targets?.size ?: 0)
        val absorbed = mutableSetOf<String>()
        /** Poster links asked about, and those answered (null: not found out, read as loading). Touched only by the event loop. */
        val probed = mutableMapOf<String, Boolean?>()
        val checked = mutableSetOf<String>()
        var peopleAsked = false
        var peopleSettled = false
        var people: PeopleSearch? = null
        var credits: List<MetaPreview>? = null
        var creditsAsked = false
        var personAdmitted = false
        var bestTier: Int? = null

        targets?.forEachIndexed { index, target ->
            launch { events.send(Event.Catalog(index, askCatalog(target, query))) }
        }
        if (askOrNull { sources.peopleOn() } == true) {
            peopleAsked = true
            launch {
                events.send(Event.People(askOrNull { sources.people(query) }))
            }
        }
        // Stopped by the event loop once the results are revealed.
        val ticks = launch {
            while (true) {
                delay(RevealCheckMs)
                events.send(Event.Tick)
            }
        }

        fun marked(titles: List<MetaPreview>) = titles.map { title ->
            val poster = title.poster
            if (poster != null && probed[poster] == false) title.copy(posterMissing = true) else title
        }

        fun collect(onlyNew: Boolean): List<SearchAnswer> {
            val list = mutableListOf<SearchAnswer>()
            targets?.forEachIndexed { index, target ->
                if (!settled[index]) return@forEachIndexed
                val fresh = absorbed.add(target.key)
                val titles = answers[index]
                if ((onlyNew && !fresh) || titles.isNullOrEmpty()) return@forEachIndexed
                list += SearchAnswer(target.key, marked(titles), target.addonRank)
            }
            val films = credits
            if (!films.isNullOrEmpty()) {
                val fresh = absorbed.add(CreditsKey)
                if (!onlyNew || fresh) list += SearchAnswer(CreditsKey, marked(films), targets?.size ?: 0)
            }
            return list
        }

        val peopleName = { people?.corrected ?: query }
        val named = { people?.let { exactNamePerson(it.people, peopleName()) } }

        fun snapshot(): SearchSnapshot {
            val row = if (board.revealed && people != null) choosePeopleRow(people!!.people, peopleName(), bestTier) else emptyList()
            return SearchSnapshot(
                query = query,
                revealed = board.revealed,
                topMatch = board.topMatch,
                titles = board.titles.toList(),
                people = row,
                corrected = people?.corrected?.takeIf { row.isNotEmpty() },
                pending = settled.count { !it },
                total = targets?.size ?: 0,
                failedAddons = targets.orEmpty().filterIndexed { index, _ -> settled[index] && answers[index] == null }.map { it.addonName },
                addonsFailed = targets == null,
                answeredWithItems = answers.count { !it.isNullOrEmpty() },
            )
        }

        suspend fun evaluate() {
            if (!board.revealed) {
                val sinceAsked = clock.nowMs() - askedAt
                val parts = parseSearchQuery(query, currentYear())
                val meaning = if (named() != null) SearchQueryMeaning.Person else SearchQueryMeaning.Title
                val links = revealPosterLinks(collect(onlyNew = false).also { absorbed.clear() }, parts, meaning, SearchPosterCheckLimit)
                for (link in links) {
                    if (link in probed) continue
                    probed[link] = null
                    launch { events.send(Event.Poster(link, askOrNull { sources.posterLoads(link) })) }
                }
                val postersSettled = sinceAsked >= SearchPosterCheckDeadlineMs || links.all { it in checked }
                // Whether the words are a person's name decides which titles stay, so
                // the people answer is waited for like a catalog; their films a little longer.
                if (peopleAsked && !peopleSettled && sinceAsked < SearchRevealDeadlineMs) return
                if (named() != null && credits == null && sinceAsked < SearchCreditsDeadlineMs) return
                val pending = settled.count { !it }
                val withItems = answers.count { !it.isNullOrEmpty() }
                if (targets != null) {
                    if (decideReveal(pending, targets.size, withItems, sinceAsked) != RevealDecision.Reveal || !postersSettled) return
                }
                board.reveal(collect(onlyNew = false), parts, meaning)
                personAdmitted = meaning == SearchQueryMeaning.Person
                val top = board.topMatch
                bestTier = bestTitleTier((board.titles + listOfNotNull(top)).map { it.name }, query, currentYear())
                return
            }
            // Known to be a person only after the reveal: their work the reveal
            // left out is offered again, and lands after the last card.
            if (named() != null && !personAdmitted) {
                personAdmitted = true
                if (board.admitPersonWork()) collect(onlyNew = false).forEach { board.absorb(it) }
            }
            collect(onlyNew = true).forEach { board.absorb(it) }
        }

        send(snapshot())
        for (event in events) {
            when (event) {
                is Event.Catalog -> {
                    answers[event.index] = event.titles
                    settled[event.index] = true
                }
                is Event.People -> {
                    people = event.answer
                    peopleSettled = true
                    val person = named()
                    if (person != null && !creditsAsked) {
                        creditsAsked = true
                        launch {
                            // No answer reads as no films: the addons' results stand as they are.
                            val films = askOrNull { creditTitles(sources.credits(person.tmdbId)) }.orEmpty()
                            events.send(Event.Credits(films))
                        }
                    }
                }
                is Event.Credits -> credits = event.titles
                is Event.Poster -> {
                    probed[event.link] = event.loads
                    checked += event.link
                }
                Event.Tick -> Unit
            }
            val wasRevealed = board.revealed
            evaluate()
            if (board.revealed) ticks.cancel()
            if (event != Event.Tick || board.revealed != wasRevealed) send(snapshot())
        }
    }

    /** One catalog asked, and asked once more at once when it fails, unless it said to slow down. Null when it did not answer. */
    private suspend fun askCatalog(target: BoardTarget, query: String): List<MetaPreview>? {
        val ask = suspend { sources.catalog(target, query).distinctBy { titleKey(it) } }
        return try {
            ask()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (failure is HaloApiException && failure.status == 429) return null
            try {
                ask()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** [block]'s answer, or null when it failed. Cancellation still cancels. */
private suspend fun <T> askOrNull(block: suspend () -> T): T? = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    null
}
