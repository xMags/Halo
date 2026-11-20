package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.browse.SearchDebounceMs
import moe.ditto.halo.browse.SearchInput
import moe.ditto.halo.browse.SearchRecentShown
import moe.ditto.halo.browse.cappedQuery
import moe.ditto.halo.browse.classifySearchInput
import moe.ditto.halo.browse.hasNoPoster
import moe.ditto.halo.browse.isSearchableQuery
import moe.ditto.halo.browse.searchSuggestions
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.people.PersonSummary
import moe.ditto.halo.people.TmdbKeyStatus
import moe.ditto.halo.shell.PersonRoute
import moe.ditto.halo.shell.PlayerRoute
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloDimensions
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloLayout
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSkeleton
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.PosterCard
import moe.ditto.halo.ui.PosterItem
import moe.ditto.halo.ui.SearchField
import moe.ditto.halo.ui.rememberResponsive

/*
 * Search: a field with Cancel, quiet kind chips under it and one status line.
 * Before a query, the recent searches and a legend of what the field accepts.
 * During one: the people the words name, one slot (the top match, or the
 * action card for a pasted link), what the library already holds, then the
 * results, which once shown never move (SearchEngine). One layout for phones
 * and tablets; the columns follow the width. The desktop's Search is the same
 * page (`search/Search.tsx`).
 */

/** The field holds the whole text (a long pasted link is read whole); words are cut to the search's length. */
private const val FieldMaxLength = 2048
private const val ResultsSkeletonCount = 12
private const val StaleAlpha = 0.45f

/** Where Search's taps lead. */
internal class SearchNavigation(
    val openDetail: (MetaRef) -> Unit,
    val openPerson: (PersonRoute) -> Unit,
    val play: (PlayerRoute) -> Unit,
    val openSettings: () -> Unit,
    val close: () -> Unit,
)

@Composable
internal fun SearchScreen(
    graph: SessionGraph,
    navigation: SearchNavigation,
    modifier: Modifier = Modifier,
    /** The field's modifier, for the glide from Home's search button. */
    fieldModifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
    val focusManager = LocalFocusManager.current
    val capabilitiesState by graph.capabilities.state.collectAsState()
    val capabilities = capabilitiesState.capabilities
    val keyStatus by graph.tmdbKeys.status.collectAsState()
    val needsTmdbKey = !graph.signedIn && keyStatus != TmdbKeyStatus.Working

    var term by rememberSaveable { mutableStateOf("") }
    var activeQuery by rememberSaveable { mutableStateOf("") }
    var chosenFilter by rememberSaveable { mutableStateOf(SearchFilter.All) }
    // Only a tablet can choose one (see the chips below); a phone always shows everything.
    val filter = if (responsive.isTablet) chosenFilter else SearchFilter.All
    var focused by remember { mutableStateOf(false) }
    var suggestionsDismissed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val history by graph.searchHistory.terms.collectAsState()

    // What the server offers is asked again on arrival when it could not be had.
    LaunchedEffect(graph) { graph.capabilities.refreshIfFailed() }

    val input = remember(term) { classifySearchInput(term.trim()) }
    val textMode = input is SearchInput.Text
    // No catalog is asked about a link; what an earlier query asked is dropped.
    val query = if (textMode) activeQuery.trim() else ""
    val active = textMode && isSearchableQuery(query)
    val view = rememberSearchView(graph, query, active, filter, attempt)
    val action = rememberLinkAction(
        graph = graph,
        input = input,
        capabilities = capabilitiesState,
        navigation = LinkActionNavigation(navigation.openDetail, navigation.openPerson, navigation.play),
    )

    // Words wait for a pause in typing; too short to search goes back to the empty page at once.
    LaunchedEffect(term, textMode) {
        if (!textMode) return@LaunchedEffect
        val capped = cappedQuery(term)
        if (!isSearchableQuery(capped)) {
            activeQuery = capped
            return@LaunchedEffect
        }
        delay(SearchDebounceMs)
        activeQuery = capped
    }

    /** Only words are remembered, never a pasted link. */
    val record = { value: String ->
        if (classifySearchInput(value.trim()) is SearchInput.Text && isSearchableQuery(value)) graph.searchHistory.add(value.trim())
        Unit
    }
    val recordQuery = { record(query) }
    val change = { value: String ->
        suggestionsDismissed = false
        term = value.take(FieldMaxLength)
    }
    /** A past search or a suggestion: searched at once, no pause. */
    val searchAgain = { value: String ->
        val capped = cappedQuery(value)
        suggestionsDismissed = true
        term = capped
        activeQuery = capped
        record(capped)
    }
    val openTitle = { meta: MetaPreview ->
        recordQuery()
        navigation.openDetail(MetaRef(meta.type, meta.id))
    }
    val openPerson = { person: PersonSummary ->
        if (capabilities.people && person.tmdbId > 0) {
            recordQuery()
            navigation.openPerson(PersonRoute(person.tmdbId, person.name, person.photo))
        }
    }
    val submit = {
        if (!textMode) {
            action?.primary?.run?.invoke()
        } else if (isSearchableQuery(term)) {
            suggestionsDismissed = true
            val capped = cappedQuery(term)
            val top = view.topMatch
            // The top match of exactly what the field holds: the Search key opens
            // it. Text not searched yet is searched instead.
            if (top != null && !view.stale && capped == activeQuery) {
                openTitle(top)
            } else {
                activeQuery = capped
                record(capped)
                focusManager.clearFocus()
            }
        }
    }

    // Only while typing: with the keyboard put away the field may keep focus, but the list is in the way.
    val typing = focused && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val suggestions = if (typing && !suggestionsDismissed && textMode) {
        searchSuggestions(history, cappedQuery(term), view.foundNames(cappedQuery(term)))
    } else {
        emptyList()
    }
    val idle = !active && textMode
    val placeholder = if (capabilities.people) "Films, series, people or a link" else "Films, series or a link"
    val library by remember(graph) { graph.library.observe() }.collectAsState(QueryState())
    val savedIds = remember(library.value) { library.value.orEmpty().filterNot { it.isRemoved }.map { it.id }.toSet() }

    val gutter = responsive.gutter
    val gridState = rememberLazyGridState()
    // Dragging the results puts the keyboard away; otherwise it covers half of them.
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) focusManager.clearFocus()
    }

    Column(
        modifier
            .fillMaxSize()
            .background(HaloColors.Background)
            .padding(start = responsive.contentInsetStart)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + responsive.pick(HaloSpacing.Xs, HaloSpacing.Sm)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
        ) {
            Box(
                fieldModifier
                    .weight(1f, fill = false)
                    .widthIn(max = HaloLayout.SearchFieldMaxWidth)
                    .onFocusChanged { focused = it.hasFocus },
            ) {
                SearchField(
                    value = term,
                    onValueChange = change,
                    onClear = { change("") },
                    onSubmit = { submit() },
                    autoFocus = true,
                    placeholder = placeholder,
                )
            }
            Text(
                text = "Cancel",
                color = HaloColors.Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(role = Role.Button, onClick = navigation.close).padding(vertical = HaloSpacing.Xs),
            )
        }

        // The suggestions float over the page under the field rather than pushing it down.
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        // A phone has no kind chips: its results are short enough to scan, and the
        // chips cost a row of a small screen. A tablet shows them once there is a query.
        if (responsive.isTablet && textMode && !idle) {
            FilterChips(filter, onPick = { chosenFilter = it }, modifier = Modifier.padding(horizontal = gutter).padding(top = HaloSpacing.Sm))
        }

        val status = view.status
        if (active && status != null && (status.text.isNotEmpty() || status.spinner)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = gutter).padding(top = HaloSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
            ) {
                if (status.spinner) CircularProgressIndicator(color = HaloColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
                Text(
                    text = status.text,
                    color = if (status.failed) HaloColors.Gold else HaloColors.TextDim,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(responsive.posterColumns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = HaloSpacing.Md, bottom = HaloSpacing.Xl),
            horizontalArrangement = Arrangement.spacedBy(responsive.posterGridGap),
            verticalArrangement = Arrangement.spacedBy(responsive.posterGridRowGap),
        ) {
            if (idle) {
                val recent = history.take(SearchRecentShown)
                if (recent.isNotEmpty()) {
                    full("recent") {
                        RecentSearches(
                            terms = recent,
                            asRows = !responsive.isTablet,
                            onRunAgain = { searchAgain(it); focusManager.clearFocus() },
                            onRemove = { graph.searchHistory.remove(it) },
                            onClear = { graph.searchHistory.clear() },
                        )
                    }
                }
                full("hints") {
                    SearchHints(
                        people = capabilities.people,
                        lookup = capabilities.lookup,
                        magnets = capabilities.magnets,
                        needsTmdbKey = needsTmdbKey,
                        onOpenSettings = navigation.openSettings,
                        modifier = Modifier.padding(top = HaloSpacing.Lg),
                    )
                }
            }

            if (!textMode && action != null) {
                full("action") { ActionCard(action) }
            }

            if (active) {
                val dim = if (view.stale) Modifier.alpha(StaleAlpha) else Modifier
                if (view.people.isNotEmpty()) {
                    sectionTitle("people", "People")
                    items(view.people, key = { "person:${it.tmdbId}" }) { person ->
                        PersonCard(person, onOpen = { openPerson(person) }, modifier = dim)
                    }
                }
                view.topMatch?.let { top ->
                    full("top") { TopMatchCard(top, inLibrary = "${top.type}:${top.id}" in savedIds, onOpen = { openTitle(top) }, modifier = dim) }
                }
                if (view.skeleton) full("slot-skeleton") { SlotSkeleton() }
                if (view.library.isNotEmpty()) {
                    sectionTitle("library", "In your library")
                    titles("library", view.library, filter, dim, openTitle)
                }
                if (view.results.isNotEmpty()) {
                    sectionTitle("results", "Results", count = view.results.size)
                    titles("result", view.results, filter, dim, openTitle)
                }
                if (view.skeleton) {
                    sectionTitle("skeleton-title", "Results")
                    items(ResultsSkeletonCount, key = { "skeleton:$it" }) {
                        HaloSkeleton(Modifier.fillMaxWidth().aspectRatio(1f / HaloDimensions.PosterRatio).clip(RoundedCornerShape(HaloRadius.Md)))
                    }
                }
                when {
                    view.filteredEmpty -> full("filtered-empty") {
                        SearchEmptyState(
                            glyph = HaloIcons.Filter,
                            text = "No ${if (filter == SearchFilter.Movies) "movies" else "series"} match “${view.revealedQuery}”.",
                            action = "Show all results" to { chosenFilter = SearchFilter.All },
                        )
                    }
                    view.nothingFound -> full("nothing") {
                        SearchEmptyState(HaloIcons.Search, title = "No matching titles", text = "Check the spelling, or try fewer words.")
                    }
                    view.addonsUnreachable -> full("unreachable") {
                        SearchEmptyState(
                            glyph = HaloIcons.CloudOff,
                            title = "Search could not reach your addons",
                            text = "Check your connection and try again.",
                            action = "Try again" to { attempt += 1 },
                        )
                    }
                    view.noAddons -> full("no-addons") {
                        SearchEmptyState(
                            glyph = HaloIcons.Extension,
                            title = "None of your addons can search",
                            text = "Add one that can, such as Cinemeta, in Settings.",
                            action = "Open Settings" to navigation.openSettings,
                        )
                    }
                }
            }
        }
        }
        if (suggestions.isNotEmpty()) {
            Suggestions(
                suggestions,
                onPick = { picked -> searchAgain(picked); focusManager.clearFocus() },
                modifier = Modifier.padding(horizontal = gutter).zIndex(1f),
            )
        }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.sectionTitle(key: String, title: String, count: Int? = null) {
    full("title:$key") {
        Row(Modifier.padding(top = HaloSpacing.Sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
            Text(title, color = HaloColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            count?.let { Text("$it", color = HaloColors.TextFaint, fontSize = 13.sp) }
        }
    }
}

private fun LazyGridScope.titles(prefix: String, titles: List<MetaPreview>, filter: SearchFilter, dim: Modifier, onOpen: (MetaPreview) -> Unit) {
    items(titles, key = { "$prefix:${it.type}:${it.id}" }) { meta ->
        val menus = LocalTitleMenus.current
        PosterCard(
            item = meta.searchPosterItem(showKind = filter == SearchFilter.All),
            onClick = { onOpen(meta) },
            modifier = dim,
            showLabel = true,
            onLongPress = menus?.let { { it.openPoster(meta) } },
        )
    }
}

/** A result as a card: the year (and, under All, the kind) beneath its name; no art shows its name instead. */
private fun MetaPreview.searchPosterItem(showKind: Boolean): PosterItem = PosterItem(
    key = "$type:$id",
    title = name,
    posterUrl = poster.takeUnless { posterMissing || hasNoPoster(this) },
    subtitle = listOfNotNull(
        if (showKind) (if (type == "series") "Series" else "Movie") else null,
        releaseInfo?.takeIf { it.isNotBlank() },
    ).joinToString(" · "),
    softPlaceholder = true,
)

@Composable
private fun FilterChips(selected: SearchFilter, onPick: (SearchFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
        SearchFilter.entries.forEach { option ->
            val on = option == selected
            Text(
                text = option.label,
                color = if (on) HaloColors.Text else HaloColors.TextDim,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(HaloRadius.Pill))
                    .background(if (on) HaloColors.FieldFill else HaloColors.Background)
                    .border(1.dp, if (on) HaloColors.GlassBorder else HaloColors.Hairline, RoundedCornerShape(HaloRadius.Pill))
                    .clickable(role = Role.RadioButton, onClick = { onPick(option) })
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Suggestions(suggestions: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(top = HaloSpacing.Xs)
            .widthIn(max = HaloLayout.SearchFieldMaxWidth)
            .fillMaxWidth()
            .clip(RoundedCornerShape(HaloRadius.Md))
            .background(HaloColors.SurfaceHigh)
            .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(HaloRadius.Md)),
    ) {
        suggestions.forEach { suggestion ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = { onPick(suggestion) })
                    .padding(horizontal = HaloSpacing.Md, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
            ) {
                Icon(HaloIcons.Search, contentDescription = null, tint = HaloColors.TextDim, modifier = Modifier.size(15.dp))
                Text(suggestion, color = HaloColors.Text, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
/**
 * The recent searches: a list of rows on a phone, where a full-width row is
 * easy to hit and a long term has room; pills on a tablet, where a narrow list
 * down a wide page is mostly empty space.
 */
private fun RecentSearches(terms: List<String>, asRows: Boolean, onRunAgain: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(if (asRows) 0.dp else HaloSpacing.Sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Recent", color = HaloColors.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            // Clearing the history is destructive, so it is red.
            Text(
                text = "Clear",
                color = HaloColors.Danger,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(role = Role.Button, onClick = onClear).padding(HaloSpacing.Xs),
            )
        }
        if (asRows) {
            terms.forEach { entry -> RecentRow(entry, onRunAgain = { onRunAgain(entry) }, onRemove = { onRemove(entry) }) }
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm), verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
            terms.forEach { entry ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(HaloRadius.Pill))
                        .background(HaloColors.Glass)
                        .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(HaloRadius.Pill)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry,
                        color = HaloColors.TextMeta,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .clickable(role = Role.Button, onClick = { onRunAgain(entry) })
                            .padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                    )
                    Icon(
                        imageVector = HaloIcons.Close,
                        contentDescription = "Remove $entry from recent searches",
                        tint = HaloColors.TextDim,
                        modifier = Modifier
                            .clickable(role = Role.Button, onClick = { onRemove(entry) })
                            .padding(start = 2.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
                            .size(12.dp),
                    )
                }
            }
        }
    }
}

/** One past search as a row: the clock, the term (tap to search it again) and a separate remove target. */
@Composable
private fun RecentRow(term: String, onRunAgain: () -> Unit, onRemove: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClick = onRunAgain)
                    .padding(vertical = HaloSpacing.Sm + 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm + 2.dp),
            ) {
                Icon(HaloIcons.Clock, contentDescription = null, tint = HaloColors.TextDim, modifier = Modifier.size(17.dp))
                Text(term, color = HaloColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                imageVector = HaloIcons.Close,
                contentDescription = "Remove $term from recent searches",
                tint = HaloColors.TextDim,
                modifier = Modifier.clickable(role = Role.Button, onClick = onRemove).padding(HaloSpacing.Sm).size(15.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Hairline))
    }
}
