package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.browse.MinSearchTermLength
import moe.ditto.halo.browse.catalogRows
import moe.ditto.halo.browse.interleaveTitles
import moe.ditto.halo.browse.savedSearchMatches
import moe.ditto.halo.browse.suggestedSearches
import moe.ditto.halo.browse.titleKey
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloLayout
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.PosterCard
import moe.ditto.halo.ui.ResponsiveInfo
import moe.ditto.halo.ui.SearchField
import moe.ditto.halo.ui.Segmented
import moe.ditto.halo.ui.longPressClickable
import moe.ditto.halo.ui.monoStyle
import moe.ditto.halo.ui.rememberResponsive

/*
 * Search as the desktop app lays it out, for a tablet held sideways: one grid
 * of results under the box rather than a row per addon, the viewer's own
 * library first, a compact best guess above it, past searches suggested while
 * typing, and recent and popular titles before a search. The phone keeps its
 * row per addon, where a grid of tiny posters would not read.
 */

/** Whether Search gets the desktop's layout instead of the phone's rows. */
internal fun usesWideSearch(responsive: ResponsiveInfo): Boolean = responsive.isTablet && responsive.isLandscape

/** Short enough to feel live while typing, long enough not to ask every addon per keystroke. */
private const val WideDebounceMs = 250L

/** At most this many recent searches, and only as many as fit on one line. */
private const val RecentShown = 6

/** Enough popular titles to browse without a search, short of a whole catalog. */
private const val PopularShown = 24

private val CardShape = RoundedCornerShape(8.dp)
private val PillShape = RoundedCornerShape(16.dp)

@Composable
internal fun WideSearchScreen(
    graph: SessionGraph,
    onOpenDetail: (MetaRef) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    var term by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(MediaTypeFilter.All) }
    var fieldFocused by remember { mutableStateOf(false) }

    // Restarting the effect on every keystroke cancels the pending delay, which
    // is the whole debounce.
    LaunchedEffect(term) {
        delay(WideDebounceMs)
        debounced = term
    }

    val history by graph.searchHistory.terms.collectAsState()
    val results by remember(graph, debounced) { graph.browse.search(debounced) }.collectAsState(QueryState())
    val library by remember(graph) { graph.library.observe() }.collectAsState(QueryState())
    val popular = rememberPopular(graph, filter)

    val trimmed = debounced.trim()
    val searching = trimmed.length >= MinSearchTermLength
    val matchesFilter = { meta: MetaPreview -> filter.type == null || meta.type == filter.type }

    // History records deliberate acts only: submitting, re-running a past
    // term, or opening a result. The debounced keystroke stream is not intent.
    val record = { value: String -> graph.searchHistory.add(value); Unit }
    val runAgain = { value: String ->
        term = value
        debounced = value
        record(value)
        focusManager.clearFocus()
    }
    val open = { meta: MetaPreview ->
        if (searching) record(trimmed)
        onOpenDetail(MetaRef(meta.type, meta.id))
    }

    val groups = results.value
    // The best guess for the query itself: the first title of the first
    // catalog, picked before the kind filter so the filter does not move it.
    val topMatch = groups?.firstOrNull()?.metas?.firstOrNull()?.takeIf { searching }
    val fromLibrary = remember(library.value, trimmed, filter) {
        if (!searching) emptyList() else savedSearchMatches(library.value, trimmed).map { it.metaPreview() }.filter(matchesFilter)
    }
    val merged = remember(groups, fromLibrary, topMatch, filter) {
        val shownAbove = fromLibrary + listOfNotNull(topMatch)
        interleaveTitles(groups.orEmpty().map { group -> group.metas.filter(matchesFilter) }, shownAbove)
    }
    val suggestions = if (fieldFocused) suggestedSearches(history, term) else emptyList()

    val gutter = responsive.gutter
    val gridState = rememberLazyGridState()
    // Dragging the results puts the keyboard away; otherwise it covers half of them.
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) focusManager.clearFocus()
    }
    // The library's matches show first and the addons' answer lands above
    // them with the top match, while the grid keeps what was on screen in
    // place, which would leave the top match scrolled out of sight. So the
    // grid goes back to the top when it arrives, unless the viewer has
    // scrolled this search themselves.
    var scrolledByHand by remember(trimmed) { mutableStateOf(false) }
    LaunchedEffect(gridState, trimmed) {
        gridState.interactionSource.interactions.collect { if (it is DragInteraction.Start) scrolledByHand = true }
    }
    val topMatchKey = topMatch?.let(::titleKey)
    LaunchedEffect(topMatchKey) {
        if (topMatchKey != null && !scrolledByHand) gridState.scrollToItem(0)
    }

    var screen by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var fieldBounds by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier
            .fillMaxSize()
            .background(HaloColors.Background)
            .onGloballyPositioned { screen = it },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = responsive.contentInsetStart)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HaloSpacing.Sm),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = gutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
            ) {
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = HaloLayout.SearchFieldMaxWidth)
                        .onFocusChanged { fieldFocused = it.hasFocus }
                        .onGloballyPositioned { field ->
                            fieldBounds = screen?.takeIf { it.isAttached }?.localBoundingBoxOf(field)
                        },
                ) {
                    SearchField(
                        value = term,
                        onValueChange = { term = it },
                        onClear = { term = "" },
                        onSubmit = {
                            if (term.trim().length >= MinSearchTermLength) {
                                debounced = term
                                record(term)
                            }
                        },
                        autoFocus = true,
                    )
                }
                Text(
                    text = "Cancel",
                    color = HaloColors.Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onClose).padding(vertical = HaloSpacing.Xs),
                )
            }
            Segmented(
                options = MediaTypeFilter.labels,
                value = filter.label,
                onChange = { label -> MediaTypeFilter.byLabel(label)?.let { filter = it } },
                // Sized to its three labels, as on Home: stretched across the
                // screen it reads as three unrelated buttons.
                modifier = Modifier.padding(start = gutter, top = HaloSpacing.Md).width(IntrinsicSize.Min),
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(responsive.searchPosterWidth),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = gutter, end = gutter, top = HaloSpacing.Sm, bottom = HaloSpacing.Xl),
                horizontalArrangement = Arrangement.spacedBy(responsive.posterGridGap),
                verticalArrangement = Arrangement.spacedBy(responsive.posterGridRowGap),
            ) {
                if (!searching) {
                    beforeSearch(
                        recent = history.take(RecentShown),
                        popular = popular,
                        onRunAgain = runAgain,
                        onRemove = { graph.searchHistory.remove(it) },
                        onClear = { graph.searchHistory.clear() },
                        onOpen = open,
                    )
                } else {
                    duringSearch(
                        query = trimmed,
                        filter = filter,
                        topMatch = topMatch,
                        topMatchInLibrary = topMatch != null && library.value.orEmpty().any {
                            it.id == "${topMatch.type}:${topMatch.id}" && !it.isRemoved
                        },
                        fromLibrary = fromLibrary,
                        merged = merged,
                        pending = groups == null && results.error == null,
                        failed = groups == null && results.error != null,
                        anyAnswers = groups.orEmpty().isNotEmpty(),
                        onShowAll = { filter = MediaTypeFilter.All },
                        onOpen = open,
                    )
                }
            }
        }

        // Past searches hang from the box, over whatever is below it.
        val bounds = fieldBounds
        if (suggestions.isNotEmpty() && bounds != null) {
            Suggestions(
                suggestions = suggestions,
                onPick = runAgain,
                modifier = Modifier
                    .offset { IntOffset(bounds.left.toInt(), (bounds.bottom + with(density) { 4.dp.toPx() }).toInt()) }
                    .width(with(density) { bounds.width.toDp() }),
            )
        }
    }
}

/** Popular titles: the first films and series catalogs Home shows, taking turns, narrowed by the filter. */
@Composable
private fun rememberPopular(graph: SessionGraph, filter: MediaTypeFilter): PopularTitles {
    val addons by remember(graph) { graph.addons.observeEffective() }.collectAsState(QueryState())
    val rows = remember(addons.value) { catalogRows(addons.value.orEmpty()).filter { it.languageChoices == null } }
    val movies = rows.firstOrNull { it.type == "movie" }
    val series = rows.firstOrNull { it.type == "series" }
    val movieState by remember(graph, movies?.key, filter) {
        graph.browse.catalog(
            addonId = movies?.addonId.orEmpty(),
            type = "movie",
            catalogId = movies?.catalogId.orEmpty(),
            enabled = movies != null && filter != MediaTypeFilter.Series,
        )
    }.collectAsState(QueryState())
    val seriesState by remember(graph, series?.key, filter) {
        graph.browse.catalog(
            addonId = series?.addonId.orEmpty(),
            type = "series",
            catalogId = series?.catalogId.orEmpty(),
            enabled = series != null && filter != MediaTypeFilter.Movies,
        )
    }.collectAsState(QueryState())
    val lists = listOf(
        if (filter == MediaTypeFilter.Series) emptyList() else movieState.value.orEmpty(),
        if (filter == MediaTypeFilter.Movies) emptyList() else seriesState.value.orEmpty(),
    )
    return PopularTitles(
        titles = remember(lists) { interleaveTitles(lists).take(PopularShown) },
        loading = movieState.isFetching || seriesState.isFetching,
    )
}

private class PopularTitles(val titles: List<MetaPreview>, val loading: Boolean)

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.posters(keyPrefix: String, titles: List<MetaPreview>, onOpen: (MetaPreview) -> Unit) {
    items(items = titles, key = { "$keyPrefix:${titleKey(it)}" }) { meta ->
        val menus = LocalTitleMenus.current
        PosterCard(
            item = meta.posterItem(),
            onClick = { onOpen(meta) },
            showLabel = true,
            onLongPress = menus?.let { { it.openPoster(meta) } },
        )
    }
}

private fun LazyGridScope.beforeSearch(
    recent: List<String>,
    popular: PopularTitles,
    onRunAgain: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onOpen: (MetaPreview) -> Unit,
) {
    if (recent.isNotEmpty()) {
        fullWidth("recent") {
            Column(Modifier.padding(top = HaloSpacing.Md), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Kicker("RECENT", Modifier.weight(1f))
                    Text(
                        text = "Clear",
                        color = HaloColors.Accent,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(role = Role.Button, onClick = onClear).padding(HaloSpacing.Xs),
                    )
                }
                OneLine(gap = 8.dp) {
                    recent.forEach { entry ->
                        RecentPill(entry, onRunAgain = { onRunAgain(entry) }, onRemove = { onRemove(entry) })
                    }
                }
            }
        }
    }
    if (popular.titles.isEmpty() && !popular.loading) return
    fullWidth("popular-head") { Kicker("POPULAR RIGHT NOW", Modifier.padding(top = HaloSpacing.Md)) }
    if (popular.titles.isEmpty()) {
        fullWidth("popular-loading") { Spinner() }
        return
    }
    posters("popular", popular.titles, onOpen)
}

private fun LazyGridScope.duringSearch(
    query: String,
    filter: MediaTypeFilter,
    topMatch: MetaPreview?,
    topMatchInLibrary: Boolean,
    fromLibrary: List<MetaPreview>,
    merged: List<MetaPreview>,
    pending: Boolean,
    failed: Boolean,
    anyAnswers: Boolean,
    onShowAll: () -> Unit,
    onOpen: (MetaPreview) -> Unit,
) {
    topMatch?.let { meta ->
        fullWidth("top-head") { Kicker("TOP MATCH", Modifier.padding(top = HaloSpacing.Md)) }
        fullWidth("top") { TopMatchCard(meta, topMatchInLibrary, onClick = { onOpen(meta) }) }
    }
    if (fromLibrary.isNotEmpty()) {
        fullWidth("library-head") { Kicker("IN YOUR LIBRARY", Modifier.padding(top = HaloSpacing.Md)) }
        posters("library", fromLibrary, onOpen)
    }
    if (merged.isNotEmpty()) {
        fullWidth("results-head") { Kicker("RESULTS  ${merged.size}", Modifier.padding(top = HaloSpacing.Md)) }
        posters("results", merged, onOpen)
    }
    when {
        // The addon answers arrive together, after the library's.
        pending -> fullWidth("pending") { Spinner() }
        // An error here means the addon list itself could not be read, which
        // is not the query's fault.
        failed && fromLibrary.isEmpty() -> fullWidth("failed") { Message("Could not reach your Halo server.") }
        topMatch == null && fromLibrary.isEmpty() && merged.isEmpty() -> fullWidth("none") {
            Message("No results for “$query”.")
        }
        filter != MediaTypeFilter.All && anyAnswers && fromLibrary.isEmpty() && merged.isEmpty() -> fullWidth("filtered") {
            val kind = if (filter == MediaTypeFilter.Movies) "movies" else "series"
            Column(Modifier.fillMaxWidth().padding(top = HaloSpacing.Lg), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No $kind match “$query”.", color = HaloColors.TextDim, fontSize = 14.sp)
                Text(
                    text = "Show all results",
                    color = HaloColors.Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onShowAll).padding(HaloSpacing.Xs),
                )
            }
        }
    }
}

@Composable
private fun Kicker(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = monoStyle(11.5.sp, FontWeight.Bold, HaloColors.TextDim),
        letterSpacing = 1.8.sp,
        modifier = modifier,
    )
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(vertical = HaloSpacing.Lg), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = HaloColors.Accent)
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        color = HaloColors.TextDim,
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().padding(top = HaloSpacing.Lg),
        textAlign = TextAlign.Center,
    )
}

/**
 * Lays its children in one line and leaves out whichever do not fit, so a
 * row of recent searches never wraps or runs past the page.
 */
@Composable
private fun OneLine(gap: Dp, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val placed = mutableListOf<Placeable>()
        var used = 0
        for (measurable in measurables) {
            val placeable = measurable.measure(loose)
            val needed = if (placed.isEmpty()) placeable.width else used + gapPx + placeable.width
            if (needed > constraints.maxWidth) break
            placed += placeable
            used = needed
        }
        val height = placed.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            var x = 0
            placed.forEach { placeable ->
                placeable.place(x, (height - placeable.height) / 2)
                x += placeable.width + gapPx
            }
        }
    }
}

/** One past search: the pill runs it again, its × forgets it. */
@Composable
private fun RecentPill(term: String, onRunAgain: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .widthIn(max = 280.dp)
            .height(36.dp)
            .clip(PillShape)
            .background(HaloColors.Glass)
            .border(1.dp, HaloColors.GlassBorder, PillShape)
            .clickable(role = Role.Button, onClick = onRunAgain)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(HaloIcons.Clock, contentDescription = null, tint = HaloColors.TextDim, modifier = Modifier.size(14.dp))
        Text(term, color = HaloColors.TextMeta, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Box(
            Modifier.size(26.dp).clip(PillShape).clickable(role = Role.Button, onClickLabel = "Remove $term", onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HaloIcons.Trash, contentDescription = "Remove $term", tint = HaloColors.TextDim, modifier = Modifier.size(12.dp))
        }
    }
}

/** The best guess for the query, compact: enough to recognise it, not enough to push the results down. */
@Composable
private fun TopMatchCard(meta: MetaPreview, inLibrary: Boolean, onClick: () -> Unit) {
    val menus = LocalTitleMenus.current
    val facts = listOfNotNull(
        if (meta.type == "series") "SERIES" else "MOVIE",
        meta.releaseInfo?.takeIf { it.isNotBlank() },
        meta.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
        if (inLibrary) "IN LIBRARY" else null,
    ).joinToString(" · ")
    Row(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .clip(CardShape)
            .background(HaloColors.Glass)
            .border(1.dp, HaloColors.GlassBorder, CardShape)
            .longPressClickable(onClick = onClick, onLongPress = menus?.let { { it.openPoster(meta) } })
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HaloAsyncImage(
            url = meta.poster,
            contentDescription = null,
            modifier = Modifier.width(60.dp).height(90.dp).clip(RoundedCornerShape(4.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(meta.name, color = HaloColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(facts, style = monoStyle(12.sp, FontWeight.Normal, HaloColors.TextDim), maxLines = 1)
            meta.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = HaloColors.TextMeta, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Past searches that match what is typed, hanging from the box. */
@Composable
private fun Suggestions(suggestions: List<String>, onPick: (String) -> Unit, modifier: Modifier) {
    Column(
        modifier
            .shadow(12.dp, CardShape)
            .clip(CardShape)
            .background(HaloColors.SurfaceHigh)
            .border(1.dp, HaloColors.GlassBorder, CardShape),
    ) {
        suggestions.forEachIndexed { index, suggestion ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Hairline))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPick(suggestion) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(HaloIcons.Clock, contentDescription = null, tint = HaloColors.TextDim, modifier = Modifier.size(15.dp))
                Text(suggestion, color = HaloColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
