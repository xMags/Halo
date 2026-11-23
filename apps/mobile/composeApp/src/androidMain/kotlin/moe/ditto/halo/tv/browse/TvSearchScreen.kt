package moe.ditto.halo.tv.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import moe.ditto.halo.browse.isSearchableQuery
import moe.ditto.halo.browse.scriptTrim
import moe.ditto.halo.people.PersonSummary
import moe.ditto.halo.screens.LinkActionNavigation
import moe.ditto.halo.screens.MetaRef
import moe.ditto.halo.screens.SearchFilter
import moe.ditto.halo.screens.TitleMenus
import moe.ditto.halo.screens.rememberLinkAction
import moe.ditto.halo.screens.rememberSearchView
import moe.ditto.halo.shell.PersonRoute
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvFocusMemory
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvPosterCard
import moe.ditto.halo.tv.kit.TvRow
import moe.ditto.halo.tv.kit.TvRowSurface
import moe.ditto.halo.tv.kit.TvSurface
import moe.ditto.halo.tv.kit.TvSurfaceColors
import moe.ditto.halo.tv.kit.TvTextField
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.tv.kit.defaultFocus
import moe.ditto.halo.tv.kit.rememberFocus
import moe.ditto.halo.tv.kit.rememberTvFocusMemory
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.metahubPosterUrl

private val ResultPosterWidth = 112.dp
private const val ResultColumns = 6
private const val FieldMaxLength = 2048

/**
 * Search on TV: one field (select opens the keyboard), recent searches while
 * it is empty, then the same results the phone shows from the same engine
 * ([rememberSearchView]): a top result, people, matches in the library, and
 * every other title, revealed once and never reshuffled. An IMDb or TVDB id
 * typed in is looked up as on the phone.
 */
@Composable
internal fun TvSearchScreen(graph: SessionGraph, navigation: TvBrowseNavigation, menus: TitleMenus) {
    val memory = rememberTvFocusMemory()
    val capabilitiesState by graph.capabilities.state.collectAsState()
    val capabilities = capabilitiesState.capabilities
    var term by rememberSaveable { mutableStateOf("") }
    var activeQuery by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.All) }
    var attempt by remember { mutableIntStateOf(0) }
    val history by graph.searchHistory.terms.collectAsState()

    // What the server offers is asked again on arrival when it could not be had.
    LaunchedEffect(graph) { graph.capabilities.refreshIfFailed() }

    val input = remember(term) { classifySearchInput(term.scriptTrim()) }
    val textMode = input is SearchInput.Text
    val query = if (textMode) activeQuery.scriptTrim() else ""
    val active = textMode && isSearchableQuery(query)
    val view = rememberSearchView(graph, query, active, filter, attempt)
    val action = rememberLinkAction(
        graph = graph,
        input = input,
        capabilities = capabilitiesState,
        navigation = LinkActionNavigation(navigation.openDetail, navigation.openPerson, navigation.play),
    )

    // Words wait for a pause in typing, as on the phone.
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

    /** Only words are remembered, never an id or a link. */
    fun record(value: String) {
        if (classifySearchInput(value.scriptTrim()) is SearchInput.Text && isSearchableQuery(value)) graph.searchHistory.add(value.scriptTrim())
    }
    fun searchNow(value: String) {
        val capped = cappedQuery(value)
        term = capped
        activeQuery = capped
        record(capped)
    }
    fun openTitle(meta: MetaPreview) {
        record(query)
        navigation.openDetail(MetaRef(meta.type, meta.id))
    }
    fun openPerson(person: PersonSummary) {
        if (!capabilities.people || person.tmdbId <= 0) return
        record(query)
        navigation.openPerson(PersonRoute(person.tmdbId, person.name, person.photo))
    }

    Column(Modifier.fillMaxSize().padding(top = TvMetrics.VerticalMargin)) {
        Box(Modifier.padding(start = TvMetrics.ContentStart, end = TvMetrics.SideMargin).width(620.dp)) {
            TvTextField(
                value = term,
                onValueChange = { term = it.take(FieldMaxLength) },
                label = "Search",
                placeholder = if (capabilities.people) "Films, series or people" else "Films or series",
                imeAction = ImeAction.Search,
                onSubmit = {
                    if (!textMode) action?.primary?.run?.invoke() else if (isSearchableQuery(term)) searchNow(term)
                },
                modifier = Modifier.defaultFocus(memory, "field"),
            )
        }
        ProvideVerticalPivot(pivot = 48.dp) {
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 16.dp, bottom = TvMetrics.VerticalMargin + 60.dp),
                verticalArrangement = Arrangement.spacedBy(TvMetrics.RowGap),
            ) {
                if (!textMode) {
                    action?.let { card ->
                        item(key = "link") {
                            TvRowSurface(
                                title = card.title,
                                hint = listOf(card.kicker, card.secondary).filter { it.isNotBlank() }.joinToString(" · "),
                                onClick = { card.primary?.run?.invoke() },
                                modifier = Modifier
                                    .padding(start = TvMetrics.ContentStart, end = TvMetrics.SideMargin)
                                    .width(620.dp)
                                    .rememberFocus(memory, "link"),
                                trailing = { card.primary?.let { Text(it.label, style = TvType.Label.copy(color = HaloColors.AccentLight)) } },
                            )
                        }
                    }
                    return@LazyColumn
                }
                if (!active) {
                    val recent = history.take(SearchRecentShown)
                    if (recent.isNotEmpty()) {
                        item(key = "recent") {
                            TvRow(title = "Recent searches") {
                                items(recent, key = { it }) { past ->
                                    Chip(past, onClick = { searchNow(past) }, modifier = Modifier.rememberFocus(memory, "recent/$past"))
                                }
                                item(key = "clear") {
                                    TvButton("Clear", onClick = { graph.searchHistory.clear() }, style = TvButtonStyle.Danger)
                                }
                            }
                        }
                    }
                    return@LazyColumn
                }
                item(key = "filters") {
                    Row(Modifier.padding(start = TvMetrics.ContentStart), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SearchFilter.entries.forEach { option ->
                            Chip(
                                option.label,
                                selected = option == filter,
                                onClick = { filter = option },
                                modifier = Modifier.rememberFocus(memory, "filter/${option.name}"),
                            )
                        }
                        view.status?.takeIf { it.text.isNotEmpty() }?.let { status ->
                            Text(
                                status.text,
                                style = TvType.Caption.copy(color = if (status.failed) HaloColors.Gold else HaloColors.TextDim),
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
                when {
                    view.skeleton -> item(key = "loading") { TvSkeletonRow(6, ResultPosterWidth, ResultPosterWidth * 1.5f) }
                    view.noAddons -> item(key = "no-addons") {
                        TvBodyMessage(
                            "None of your addons can search. Add one with a search catalog in Settings.",
                            retryModifier = Modifier.rememberFocus(memory, "settings"),
                            onRetry = navigation.openSettings,
                        )
                    }
                    view.addonsUnreachable -> item(key = "unreachable") {
                        TvBodyMessage(
                            "Could not reach your addons.",
                            retryModifier = Modifier.rememberFocus(memory, "retry"),
                            onRetry = { attempt += 1 },
                        )
                    }
                    view.nothingFound -> item(key = "nothing") { TvBodyMessage("Nothing found for “${view.revealedQuery}”.") }
                    view.filteredEmpty -> item(key = "filtered") { TvBodyMessage("No ${filter.label.lowercase()} found for “${view.revealedQuery}”.") }
                }
                view.topMatch?.let { top ->
                    item(key = "top") { TopMatch(top, onClick = { openTitle(top) }, onMenu = { menus.openPoster(top) }, modifier = Modifier.rememberFocus(memory, "top")) }
                }
                if (view.people.isNotEmpty()) {
                    item(key = "people") {
                        TvRow(title = "People") {
                            items(view.people, key = { it.tmdbId }) { person ->
                                PersonCard(person, onClick = { openPerson(person) }, modifier = Modifier.rememberFocus(memory, "person/${person.tmdbId}"))
                            }
                        }
                    }
                }
                if (view.library.isNotEmpty()) {
                    item(key = "library") { TitlesRow("In your library", view.library, memory, menus, ::openTitle) }
                }
                if (view.results.isNotEmpty()) {
                    item(key = "results-title") {
                        Text("Movies and series", style = TvType.Heading, modifier = Modifier.padding(start = TvMetrics.ContentStart))
                    }
                    items(view.results.chunked(ResultColumns), key = { row -> row.joinToString { "${it.type}:${it.id}" } }) { row ->
                        Row(Modifier.padding(start = TvMetrics.ContentStart), horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap)) {
                            row.forEach { title ->
                                TvPosterCard(
                                    title = title.name,
                                    posterUrl = title.poster,
                                    fallbackUrl = metahubPosterUrl(title.id),
                                    subtitle = title.releaseInfo,
                                    width = ResultPosterWidth,
                                    onClick = { openTitle(title) },
                                    onMenu = { menus.openPoster(title) },
                                    modifier = Modifier.rememberFocus(memory, "result/${title.type}:${title.id}"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    TvSurface(
        onClick = onClick,
        modifier = modifier.height(40.dp).semantics { contentDescription = label },
        cornerRadius = 20.dp,
        colors = if (selected) TvSurfaceColors(HaloColors.Text, HaloColors.Primary) else TvSurfaceColors(HaloColors.FieldFill, HaloColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) HaloColors.OnPrimary else HaloColors.TextMeta,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

@Composable
private fun TopMatch(meta: MetaPreview, onClick: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.padding(start = TvMetrics.ContentStart), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("TOP RESULT", style = TvType.Overline)
        TvSurface(
            onClick = onClick,
            onMenu = onMenu,
            modifier = modifier.width(560.dp).height(132.dp).semantics { contentDescription = meta.name },
            cornerRadius = 16.dp,
            focusScale = 1.03f,
        ) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                HaloAsyncImage(
                    url = meta.poster,
                    fallbackUrl = metahubPosterUrl(meta.id),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp, 108.dp).clip(RoundedCornerShape(8.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (meta.type == SeriesType) "SERIES" else "MOVIE", style = TvType.Overline)
                    Text(meta.name, style = TvType.Title.copy(fontSize = 24.sp, lineHeight = 30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TvMetaLine(meta)
                }
            }
        }
    }
}

@Composable
private fun PersonCard(person: PersonSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvSurface(
            onClick = onClick,
            modifier = modifier.size(96.dp).semantics { contentDescription = person.name },
            cornerRadius = 48.dp,
            contentAlignment = Alignment.Center,
        ) {
            HaloAsyncImage(
                url = person.photo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                placeholder = {
                    Text(
                        person.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                        color = HaloColors.TextMeta,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                },
            )
        }
        Text(person.name, color = HaloColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TitlesRow(title: String, titles: List<MetaPreview>, memory: TvFocusMemory, menus: TitleMenus, onOpen: (MetaPreview) -> Unit) {
    TvRow(title = title) {
        items(titles, key = { "${it.type}:${it.id}" }) { meta ->
            TvPosterCard(
                title = meta.name,
                posterUrl = meta.poster,
                fallbackUrl = metahubPosterUrl(meta.id),
                width = ResultPosterWidth,
                onClick = { onOpen(meta) },
                onMenu = { menus.openPoster(meta) },
                modifier = Modifier.rememberFocus(memory, "lib/${meta.type}:${meta.id}"),
            )
        }
    }
}
