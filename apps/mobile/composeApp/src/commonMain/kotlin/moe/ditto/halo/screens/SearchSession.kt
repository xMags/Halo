package moe.ditto.halo.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.browse.SearchSnapshot
import moe.ditto.halo.browse.SearchSuggestionLimit
import moe.ditto.halo.browse.foldedText
import moe.ditto.halo.browse.libraryMatches
import moe.ditto.halo.browse.searchFailureNote
import moe.ditto.halo.browse.searchStillAskingNote
import moe.ditto.halo.browse.titleIdentities
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.people.PersonSummary

/*
 * What Search shows for one query, from the engine's snapshots
 * ([moe.ditto.halo.browse.SearchEngine]): the previous query's results stay
 * up, dimmed, until the new ones are revealed; the library row is the one
 * matched at the reveal; and the status line, the empty states and the kind
 * chips are decided here. The desktop does the same in
 * `search/useSearchSession.ts`.
 */

/** The kind chips under the field. */
internal enum class SearchFilter(val type: String?, val label: String) {
    All(null, "All"),
    Movies("movie", "Movies"),
    Series("series", "Series"),
}

internal data class SearchStatus(val text: String, val spinner: Boolean, val failed: Boolean)

internal data class SearchView(
    /** No query is being searched. */
    val idle: Boolean,
    /** The previous query's results, shown dimmed until the new ones are revealed. */
    val stale: Boolean,
    /** Nothing revealed yet on this visit: placeholders stand in. */
    val skeleton: Boolean,
    val people: List<PersonSummary>,
    val topMatch: MetaPreview?,
    val library: List<MetaPreview>,
    val results: List<MetaPreview>,
    val status: SearchStatus?,
    val noAddons: Boolean,
    val addonsUnreachable: Boolean,
    val nothingFound: Boolean,
    val filteredEmpty: Boolean,
    val revealedQuery: String,
    private val current: SearchSnapshot?,
) {
    /**
     * Names to suggest beside the history: only what was found for the text in
     * the box, or for its beginning while more of it is typed.
     */
    fun foundNames(typed: String): List<String> {
        val snapshot = current ?: return emptyList()
        val typedKey = foldedText(typed.trim())
        val revealedKey = foldedText(snapshot.query)
        if (!snapshot.revealed || revealedKey.isEmpty() || !typedKey.startsWith(revealedKey)) return emptyList()
        val names = snapshot.people.map { it.name }.toMutableList()
        snapshot.topMatch?.let { names += it.name }
        for (title in snapshot.titles) {
            if (names.size >= SearchSuggestionLimit * 2) break
            names += title.name
        }
        return names
    }

    companion object {
        val Idle = SearchView(
            idle = true, stale = false, skeleton = false, people = emptyList(), topMatch = null, library = emptyList(),
            results = emptyList(), status = null, noAddons = false, addonsUnreachable = false, nothingFound = false,
            filteredEmpty = false, revealedQuery = "", current = null,
        )
    }
}

private const val SearchingNote = "Searching…"

/**
 * The search for [query] while [active], asked anew whenever [attempt] changes
 * (Try again). [query] is trimmed and capped by the caller.
 */
@Composable
internal fun rememberSearchView(graph: SessionGraph, query: String, active: Boolean, filter: SearchFilter, attempt: Int): SearchView {
    var current by remember(graph) { mutableStateOf<SearchSnapshot?>(null) }
    /** The last revealed snapshot, of this query or an earlier one. */
    var shown by remember(graph) { mutableStateOf<SearchSnapshot?>(null) }
    LaunchedEffect(graph, query, active, attempt) {
        if (!active) {
            current = null
            return@LaunchedEffect
        }
        current = null
        graph.searchEngine.run(query).collect { snapshot ->
            current = snapshot
            if (snapshot.revealed) shown = snapshot
        }
    }

    val libraryState by remember(graph) { graph.library.observe() }.collectAsState(QueryState())
    // Matched once per revealed query, so a title saved meanwhile does not push the rows down.
    val libraryLoaded = libraryState.value != null
    val revealedLibrary = remember(shown?.query, libraryLoaded) { shown?.let { libraryMatches(libraryState.value, it.query) }.orEmpty() }

    if (!active) return SearchView.Idle
    // Before the engine's first snapshot the query is simply under way.
    val session = current?.takeIf { it.query == query } ?: SearchSnapshot(
        query = query, revealed = false, topMatch = null, titles = emptyList(), people = emptyList(), corrected = null,
        pending = 0, total = 0, failedAddons = emptyList(), addonsFailed = false, answeredWithItems = 0,
    )
    val visible = if (session.revealed) session else shown
    val ofKind = { titles: List<MetaPreview> -> if (filter.type == null) titles else titles.filter { it.type == filter.type } }
    val library = if (visible == null) emptyList() else ofKind(revealedLibrary)
    val libraryIdentities = revealedLibrary.flatMap(::titleIdentities).toSet()
    val results = if (visible == null) {
        emptyList()
    } else {
        ofKind(visible.titles).filterNot { title -> titleIdentities(title).any { it in libraryIdentities } }
    }
    // People are not films or series, so a kind chip leaves them out. The row
    // belongs to the reveal it answers.
    val peopleForShown = if (visible != null && visible.query == query) visible.people else emptyList()
    val people = if (filter == SearchFilter.All) peopleForShown else emptyList()
    val topMatch = visible?.topMatch

    var addonsUnreachable = false
    var nothingFound = false
    var filteredEmpty = false
    val noAddons = session.revealed && !session.addonsFailed && session.total == 0
    val status: SearchStatus
    if (!session.revealed) {
        // Until the reveal the line says only that the search is under way.
        status = SearchStatus(SearchingNote, spinner = true, failed = false)
    } else {
        val settledEmpty = session.pending == 0 && library.isEmpty() && results.isEmpty()
        val failed = session.failedAddons
        filteredEmpty = settledEmpty && filter != SearchFilter.All && session.answeredWithItems > 0
        nothingFound = settledEmpty && topMatch == null && filter == SearchFilter.All && session.total > 0 &&
            failed.size < session.total && peopleForShown.isEmpty()
        val everyOneFailed = session.pending == 0 && session.total > 0 && failed.size >= session.total
        addonsUnreachable = session.addonsFailed || (everyOneFailed && settledEmpty && topMatch == null)
        status = when {
            addonsUnreachable -> SearchStatus("", spinner = false, failed = false)
            session.pending > 0 -> SearchStatus(searchStillAskingNote(session.pending), spinner = true, failed = false)
            else -> {
                val note = searchFailureNote(failed, session.total)
                when {
                    note.isNotEmpty() -> SearchStatus(note, spinner = false, failed = true)
                    session.corrected != null && peopleForShown.isNotEmpty() ->
                        SearchStatus("Showing results for ${session.corrected}", spinner = false, failed = false)
                    else -> SearchStatus("", spinner = false, failed = false)
                }
            }
        }
    }

    return SearchView(
        idle = false,
        stale = !session.revealed && visible != null,
        skeleton = !session.revealed && visible == null,
        people = people,
        topMatch = topMatch,
        library = library,
        results = results,
        status = status,
        noAddons = noAddons,
        addonsUnreachable = addonsUnreachable,
        nothingFound = nothingFound,
        filteredEmpty = filteredEmpty,
        revealedQuery = visible?.query.orEmpty(),
        current = session,
    )
}
