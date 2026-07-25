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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import moe.ditto.halo.SignedInGraph
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloDimensions
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.PosterGrid
import moe.ditto.halo.ui.rememberResponsive

/**
 * Saved titles as a poster grid.
 *
 * Read-only: adding and removing both happen on a title's own screen, where
 * there is something to add. Nothing here writes.
 */
@Composable
internal fun LibraryScreen(
    graph: SignedInGraph,
    onOpenSearch: () -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
    var filter by remember { mutableStateOf(MediaTypeFilter.All) }

    // Tombstones are sync bookkeeping, not content, so this is the active view.
    val library by remember(graph) { graph.library.observeActive() }.collectAsState(QueryState())
    val saved = library.value

    // Newest first. The API returns library rows unordered, and Home's library
    // shelf already sorts by addedAt — leaving this to the server's row order
    // would show one library in two different orders on two screens, and would
    // change silently the first time that query grows an index.
    val shown = remember(saved, filter) {
        saved.orEmpty()
            .filter { filter.type == null || it.type == filter.type }
            .sortedByDescending { it.addedAt }
            .map { it.posterItem() }
    }

    Box(modifier.fillMaxSize().background(HaloColors.Background)) {
        when {
            // The three empty-ish states keep the title and search, so the screen
            // still names itself — and can still reach search — while it has
            // nothing of its own to show.
            saved == null && library.error != null ->
                LibraryPlaceholder(onOpenSearch) { CenterMessage("Could not reach your Halo server.") }
            saved == null -> LibraryPlaceholder(onOpenSearch) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HaloColors.Accent)
                }
            }
            saved.isEmpty() -> LibraryPlaceholder(onOpenSearch) {
                CenterMessage("Nothing saved yet. Open a title and tap “Add to library”.")
            }
            else -> PosterGrid(
                items = shown,
                columns = responsive.posterColumns,
                onItemClick = { onOpenDetail(it.metaRef()) },
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = HaloSpacing.Md,
                    end = HaloSpacing.Md,
                    // The tab bar floats over this screen, so the last row has to
                    // stop short of it or it sits behind the glass.
                    bottom = HaloDimensions.TabBarSpace,
                ),
                header = {
                    ScreenHeader(
                        title = LibraryTitle,
                        onOpenSearch = onOpenSearch,
                        // The filter appears only once something is saved: three
                        // segments over an empty grid control nothing.
                        filter = filter,
                        onFilterChange = { filter = it },
                    )
                },
            )
        }
    }
}

private const val LibraryTitle = "Library"

/**
 * Header over whatever stands in for the grid. Carries the horizontal padding the
 * grid would otherwise supply through its content padding.
 */
@Composable
private fun LibraryPlaceholder(onOpenSearch: () -> Unit, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = HaloSpacing.Md)) {
        ScreenHeader(title = LibraryTitle, onOpenSearch = onOpenSearch)
        Box(Modifier.weight(1f)) { body() }
    }
}
