package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import kotlinx.datetime.TimeZone
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.auth.SystemEpochClock
import moe.ditto.halo.browse.buildHistory
import moe.ditto.halo.browse.groupHistory
import moe.ditto.halo.browse.matchesTypeFilter
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.shell.StreamsRoute
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.PosterGrid
import moe.ditto.halo.ui.Segmented
import moe.ditto.halo.ui.rememberResponsive

/**
 * Saved titles as a poster grid, and beside them, behind a Saved / History
 * switch, everything played (see [HistoryList]). History is not part of the
 * library: it is read from the watch history alone.
 *
 * Read-only: adding and removing both happen on a title's own screen, where
 * there is something to add. Nothing here writes.
 */
@Composable
internal fun LibraryScreen(
    graph: SessionGraph,
    onOpenSearch: () -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    onOpenSources: (StreamsRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
    var filter by remember { mutableStateOf(MediaTypeFilter.All) }
    // Saved by name, so the view chosen survives opening a title and coming back.
    var view by rememberSaveable { mutableStateOf(SavedView) }
    val views: @Composable () -> Unit = {
        Segmented(options = listOf(SavedView, HistoryView), value = view, onChange = { view = it })
    }

    // Tombstones are sync bookkeeping, not content, so this is the active view.
    val library by remember(graph) { graph.library.observeActive() }.collectAsState(QueryState())
    val saved = library.value

    // Newest first. The API returns library rows unordered, and Home's library
    // shelf already sorts by addedAt — leaving this to the server's row order
    // would show one library in two different orders on two screens, and would
    // change silently the first time that query grows an index.
    val shown = remember(saved, filter) {
        saved.orEmpty()
            .filter { matchesTypeFilter(filter.type, it.type) }
            .sortedByDescending { it.addedAt }
            .map { it.posterItem() }
    }

    val gutter = responsive.gutter

    // The navigation rail floats over the leading edge, so the grid begins
    // beside it and its gutter is measured from there.
    Box(
        modifier
            .fillMaxSize()
            .background(HaloColors.Background)
            .padding(start = responsive.contentInsetStart),
    ) {
        if (view == HistoryView) {
            HistoryView(graph, filter, { filter = it }, views, onOpenSearch, onOpenSources, onOpenDetail)
            return@Box
        }
        when {
            // The three empty-ish states keep the title and search, so the screen
            // still names itself — and can still reach search — while it has
            // nothing of its own to show.
            saved == null && library.error != null ->
                LibraryPlaceholder(gutter, onOpenSearch, views) { CenterMessage("Could not reach your Halo server.") }
            saved == null -> LibraryPlaceholder(gutter, onOpenSearch, views) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HaloColors.Accent)
                }
            }
            saved.isEmpty() -> LibraryPlaceholder(gutter, onOpenSearch, views) {
                CenterMessage("Nothing saved yet. Open a title and tap “Add to library”.")
            }
            else -> PosterGrid(
                items = shown,
                columns = responsive.posterColumns,
                onItemClick = { onOpenDetail(it.metaRef()) },
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = gutter,
                    end = gutter,
                    // Chrome that floats over this screen — the tab bar, where
                    // there is one — has to be cleared or the last row sits
                    // behind the glass.
                    bottom = responsive.bottomContentPadding,
                ),
                horizontalGap = responsive.posterGridGap,
                verticalGap = responsive.posterGridRowGap,
                header = {
                    ScreenHeader(
                        title = LibraryTitle,
                        onOpenSearch = onOpenSearch,
                        // The filter appears only once something is saved: three
                        // segments over an empty grid control nothing.
                        filter = filter,
                        onFilterChange = { filter = it },
                        views = views,
                    )
                },
            )
        }
    }
}

/** The History side of the switch, under the same header, filter included. */
@Composable
private fun HistoryView(
    graph: SessionGraph,
    filter: MediaTypeFilter,
    onFilterChange: (MediaTypeFilter) -> Unit,
    views: @Composable () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSources: (StreamsRoute) -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
) {
    val responsive = rememberResponsive()
    val watchStates by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())
    // Tombstones included: a removed title's name still names its history rows.
    val library by remember(graph) { graph.library.observe() }.collectAsState(QueryState())
    val zone = remember { TimeZone.currentSystemDefault() }
    val groups = remember(watchStates.value, library.value, filter) {
        watchStates.value?.let { states ->
            groupHistory(buildHistory(states, library.value, filter.type), SystemEpochClock.nowMs(), zone)
        }
    }
    HistoryList(
        groups = groups,
        failed = watchStates.error != null,
        hasAny = !watchStates.value.isNullOrEmpty(),
        header = {
            ScreenHeader(
                title = LibraryTitle,
                onOpenSearch = onOpenSearch,
                filter = filter,
                onFilterChange = onFilterChange,
                views = views,
            )
        },
        gutter = responsive.gutter,
        bottomPadding = responsive.bottomContentPadding,
        onOpenSources = onOpenSources,
        onOpenDetail = onOpenDetail,
    )
}

private const val LibraryTitle = "Library"
private const val SavedView = "Saved"
private const val HistoryView = "History"

/**
 * Header over whatever stands in for the grid. Carries the horizontal padding the
 * grid would otherwise supply through its content padding.
 */
@Composable
private fun LibraryPlaceholder(
    gutter: Dp,
    onOpenSearch: () -> Unit,
    views: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(start = gutter, end = gutter)) {
        ScreenHeader(title = LibraryTitle, onOpenSearch = onOpenSearch, views = views)
        Box(Modifier.weight(1f)) { body() }
    }
}
