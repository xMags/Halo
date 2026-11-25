package moe.ditto.halo.tv.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.TimeZone
import moe.ditto.halo.auth.SystemEpochClock
import moe.ditto.halo.browse.buildHistory
import moe.ditto.halo.browse.groupHistory
import moe.ditto.halo.browse.HistoryEntry
import moe.ditto.halo.browse.historyTime
import moe.ditto.halo.browse.historyVideoTag
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.screens.MediaTypeFilter
import moe.ditto.halo.screens.metaPreview
import moe.ditto.halo.screens.MetaRef
import moe.ditto.halo.screens.resumeRoute
import moe.ditto.halo.screens.TitleMenus
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.tv.kit.defaultFocus
import moe.ditto.halo.tv.kit.rememberFocus
import moe.ditto.halo.tv.kit.rememberTvFocusMemory
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvFocusMemory
import moe.ditto.halo.tv.kit.TvGrid
import moe.ditto.halo.tv.kit.TvIconButton
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvPosterCard
import moe.ditto.halo.tv.kit.TvProgressBar
import moe.ditto.halo.tv.kit.TvRowSurface
import moe.ditto.halo.tv.kit.TvSidePanel
import moe.ditto.halo.tv.kit.TvSurface
import moe.ditto.halo.tv.kit.TvSurfaceColors
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.metahubPosterUrl

/**
 * Library on TV: the saved titles as a poster grid, and the watch history
 * grouped by day (Today, Yesterday, then dates). Both views share the
 * All / Movies / Series filter. A history row resumes what was left partway
 * and opens the title page of anything finished, as on the phone.
 */
@Composable
internal fun TvLibraryScreen(
    graph: SessionGraph,
    navigation: TvBrowseNavigation,
    menus: TitleMenus,
    /** Opens on History when set (Home's Recently watched "See all"); taken once. */
    openHistory: Boolean,
    onHistoryTaken: () -> Unit,
) {
    val memory = rememberTvFocusMemory()
    var history by rememberSaveable { mutableStateOf(openHistory) }
    LaunchedEffect(openHistory) { if (openHistory) onHistoryTaken() }
    var filter by rememberSaveable { mutableStateOf(MediaTypeFilter.All) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RecentlyAdded) }
    var sortOpen by remember { mutableStateOf(false) }
    // History only: what was left partway, whatever its type.
    var inProgress by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = TvMetrics.VerticalMargin)) {
            Row(
                Modifier.padding(start = TvMetrics.ContentStart, end = TvMetrics.SideMargin, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Library", style = TvType.Title, modifier = Modifier.padding(end = 14.dp))
                Pill("Saved", selected = !history, onClick = { history = false }, modifier = Modifier.defaultFocus(memory, "tab/saved"))
                Pill("History", selected = history, onClick = { history = true }, modifier = Modifier.rememberFocus(memory, "tab/history"))
                Box(Modifier.width(18.dp))
                MediaTypeFilter.entries.forEach { option ->
                    Pill(
                        option.label,
                        selected = option == filter && !(history && inProgress),
                        onClick = {
                            filter = option
                            inProgress = false
                        },
                        modifier = Modifier.rememberFocus(memory, "filter/${option.name}"),
                    )
                }
                if (history) {
                    Pill(
                        "In progress",
                        selected = inProgress,
                        onClick = {
                            filter = MediaTypeFilter.All
                            inProgress = true
                        },
                        modifier = Modifier.rememberFocus(memory, "filter/in-progress"),
                    )
                    Box(Modifier.weight(1f))
                    // An icon beside the four filters, where the words no longer fit;
                // it asks before clearing anything.
                TvIconButton(
                    icon = HaloIcons.Trash,
                    contentDescription = "Clear history",
                    onClick = menus::clearHistory,
                    style = TvButtonStyle.Danger,
                    modifier = Modifier.rememberFocus(memory, "clear"),
                )
                } else {
                    Box(Modifier.weight(1f))
                    TvButton(
                        // The order's own name beside the sort icon; "Sort:" in
                        // front would not fit beside the filters.
                        sort.label,
                        icon = HaloIcons.Sort,
                        onClick = { sortOpen = true },
                        modifier = Modifier.rememberFocus(memory, "sort"),
                    )
                }
            }
            if (history) {
                HistoryList(graph, filter, inProgress, memory, navigation, menus)
            } else {
                SavedGrid(graph, filter, sort, memory, navigation, menus)
            }
        }
        if (sortOpen) {
            TvSidePanel(title = "Sort by", onDismiss = { sortOpen = false }, width = 400.dp) {
                LibrarySort.entries.forEach { option ->
                    TvRowSurface(
                        title = option.label,
                        onClick = {
                            sort = option
                            sortOpen = false
                        },
                        modifier = Modifier.semantics { if (option == sort) stateDescription = "Selected" },
                        trailing = { if (option == sort) Icon(HaloIcons.Check, contentDescription = null, tint = HaloColors.AccentLight) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvSurface(
        onClick = onClick,
        modifier = modifier.height(36.dp).semantics { contentDescription = label },
        cornerRadius = 18.dp,
        colors = if (selected) TvSurfaceColors(HaloColors.Text, HaloColors.Primary) else TvSurfaceColors(HaloColors.FieldFill, HaloColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) HaloColors.OnPrimary else HaloColors.TextMeta,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun SavedGrid(
    graph: SessionGraph,
    filter: MediaTypeFilter,
    sort: LibrarySort,
    memory: TvFocusMemory,
    navigation: TvBrowseNavigation,
    menus: TitleMenus,
) {
    val library by remember(graph) { graph.library.observeActive() }.collectAsState(QueryState())
    val watchStates by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())
    val items = remember(library.value, filter, sort, watchStates.value) {
        library.value?.filter { filter.type == null || it.type == filter.type }?.let { sortLibrary(it, sort, watchStates.value) }
    }
    when {
        items == null && library.error != null -> TvBodyMessage("Could not load your library.")
        items == null -> TvBodyLoading()
        items.isEmpty() -> TvBodyMessage(
            if (filter == MediaTypeFilter.All) "Nothing saved yet. Add titles to your library from their page or with Menu on a poster."
            else "No ${filter.label.lowercase()} saved yet.",
        )
        else -> TvGrid(columns = 6) {
            items(items, key = { it.id }) { item ->
                val preview = remember(item) { item.metaPreview() }
                TvPosterCard(
                    title = item.name,
                    type = item.type,
                    posterUrl = item.poster,
                    fallbackUrl = metahubPosterUrl(preview.id),
                    width = GridPosterWidth,
                    onClick = { navigation.openDetail(MetaRef(item.type, preview.id)) },
                    onMenu = { menus.openPoster(preview) },
                    modifier = Modifier.rememberFocus(memory, "saved/${item.id}"),
                )
            }
        }
    }
}

@Composable
private fun HistoryList(
    graph: SessionGraph,
    filter: MediaTypeFilter,
    inProgress: Boolean,
    memory: TvFocusMemory,
    navigation: TvBrowseNavigation,
    menus: TitleMenus,
) {
    val watchStates by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())
    // Tombstones included: a removed title's name still names its history rows.
    val library by remember(graph) { graph.library.observe() }.collectAsState(QueryState())
    val zone = remember { TimeZone.currentSystemDefault() }
    val now = SystemEpochClock.nowMs()
    val groups = remember(watchStates.value, library.value, filter, inProgress) {
        watchStates.value?.let { states ->
            val entries = buildHistory(states, library.value, filter.type).let { all -> if (inProgress) all.filter { !it.finished } else all }
            groupHistory(entries, now, zone)
        }
    }
    when {
        groups == null && watchStates.error != null -> TvBodyMessage("Could not load your history.")
        groups == null -> TvBodyLoading()
        groups.isEmpty() && inProgress -> TvBodyMessage("Nothing left partway. Titles you stop before the end show up here.")
        groups.isEmpty() -> TvBodyMessage("Nothing watched yet. What you play shows up here.")
        else -> ProvideVerticalPivot(pivot = 40.dp) {
            LazyColumn(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(start = TvMetrics.ContentStart, end = TvMetrics.SideMargin + 200.dp, bottom = TvMetrics.VerticalMargin + 40.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { group ->
                    item(key = "label/${group.label}") {
                        Text(group.label.uppercase(), style = TvType.Overline, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                    items(group.entries, key = { "row/${it.videoId}" }) { entry ->
                        HistoryRow(
                            entry = entry,
                            time = historyTime(entry.updatedAt, now, zone),
                            onClick = {
                                if (entry.finished) {
                                    navigation.openDetail(MetaRef(entry.type, entry.metaId))
                                } else {
                                    navigation.openSources(resumeRoute(entry.type, entry.metaId, entry.videoId, entry.name, entry.poster))
                                }
                            },
                            onMenu = { menus.openHistory(entry) },
                            modifier = Modifier.rememberFocus(memory, "history/${entry.videoId}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, time: String, onClick: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val tag = historyVideoTag(entry.videoId, entry.metaId)
    TvSurface(
        onClick = onClick,
        onMenu = onMenu,
        modifier = modifier.fillMaxWidth().height(76.dp).semantics { contentDescription = "${entry.name}, $tag" },
        cornerRadius = 14.dp,
        focusScale = TvMetrics.RowFocusScale,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HaloAsyncImage(
                url = entry.poster,
                contentDescription = null,
                modifier = Modifier.size(40.dp, 60.dp).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.name, color = HaloColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (entry.finished) "$tag · Watched" else tag,
                    style = TvType.Caption.copy(color = if (entry.finished) HaloColors.Success else HaloColors.TextDim),
                )
                if (!entry.finished && entry.durationSec > 0) {
                    TvProgressBar((entry.positionSec / entry.durationSec).toFloat(), Modifier.width(220.dp))
                }
            }
            Text(time, style = TvType.Caption)
        }
    }
}

private val GridPosterWidth = 116.dp
