package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.browse.CatalogPageSource
import moe.ditto.halo.browse.CatalogPaging
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.PosterGrid
import moe.ditto.halo.ui.rememberResponsive

/** What a Home row held when its "See all" was pressed. */
data class ShelfSnapshot(
    val title: String,
    /** The row's mono line ("TMDB", "3 IN PROGRESS"), if it had one. */
    val source: String?,
    val items: List<MetaCard>,
    /** Where further pages come from, for a catalog row that pages; null keeps the grid to [items]. */
    val paging: ShelfPaging? = null,
)

/** A paging catalog's row: the catalog, and how many entries the addon sent for [ShelfSnapshot.items]. */
data class ShelfPaging(val source: CatalogPageSource, val received: Int)

/**
 * The rows "See all" has opened, by key, for [ShelfRoute][moe.ditto.halo.shell.ShelfRoute].
 * A snapshot, as on the desktop app, so the grid shows exactly what the row
 * held (a paging catalog's grid then goes on from there). Kept in memory for
 * the shell's lifetime; a handful of rows per session is nothing worth
 * evicting.
 */
@Stable
internal class ShelfSnapshots {
    private val snapshots = mutableMapOf<String, ShelfSnapshot>()
    private var next = 0

    fun put(snapshot: ShelfSnapshot): String = "shelf-${next++}".also { snapshots[it] = snapshot }

    operator fun get(key: String): ShelfSnapshot? = snapshots[key]
}

/**
 * A Home row's "See all": every title the row held, as a poster grid under the
 * row's own title, with its source and count. The desktop app's Catalog page.
 * Posters open the title page, and a long press opens their menu.
 *
 * A catalog row whose addon pages goes on from there: nearing the end of the
 * grid asks for the next page and appends it, until the addon runs out (see
 * [CatalogPaging]). A page that fails says so below the grid with a retry and
 * keeps everything already shown.
 */
@Composable
internal fun ShelfScreen(
    graph: SessionGraph,
    snapshot: ShelfSnapshot?,
    onBack: () -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
    // Only a paging catalog's grid gets a pager; every other one shows its
    // snapshot exactly as the row held it.
    val pager = remember(graph, snapshot) { snapshot?.paging?.let { ShelfPager(graph, snapshot.items, it) } }
    val items: List<MetaCard> = pager?.paging?.items ?: snapshot?.items.orEmpty()
    val gridState = rememberLazyGridState()
    if (pager != null) {
        // Keyed by the pager, so leaving the screen or a new snapshot cancels a
        // page in flight and its answer can never land on another catalog.
        LaunchedEffect(pager, gridState) { pager.loadWhenNearEnd(gridState) }
    }
    val count = "${items.size} ${if (items.size == 1) "ITEM" else "ITEMS"}"
    val subtitle = listOfNotNull(snapshot?.source, count).joinToString(" · ")
    val header: @Composable () -> Unit = {
        ScreenHeader(title = snapshot?.title ?: "Catalog", subtitle = subtitle, onBack = onBack)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(HaloColors.Background)
            .padding(start = responsive.contentInsetStart),
    ) {
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(horizontal = responsive.gutter)) {
                header()
                CenterMessage("Nothing to show here any more. Go back to Home to see this row again.")
            }
            return@Box
        }
        PosterGrid(
            items = remember(items) { items.map { it.posterItem() } },
            columns = responsive.posterColumns,
            onItemClick = { onOpenDetail(it.metaRef()) },
            itemMenu = posterMenu(items),
            modifier = Modifier.fillMaxSize(),
            // The tab bar stays up over this screen, so the last row stops
            // short of it, as on every tab.
            contentPadding = PaddingValues(
                start = responsive.gutter,
                end = responsive.gutter,
                bottom = responsive.bottomContentPadding,
            ),
            horizontalGap = responsive.posterGridGap,
            verticalGap = responsive.posterGridRowGap,
            showLabels = true,
            header = header,
            footer = pager?.takeIf { it.loading || it.failed }?.let { { PagingFooter(it) } },
            state = gridState,
        )
    }
}

/**
 * One "See all" grid's paging: the titles so far, and the state of the page
 * being asked for. Lives as long as the screen's snapshot; read and written on
 * the main thread only.
 */
@Stable
private class ShelfPager(private val graph: SessionGraph, first: List<MetaCard>, from: ShelfPaging) {
    private val source = from.source

    var paging by mutableStateOf(CatalogPaging.start(first = first, received = from.received, pageable = true))
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    /** Retry clears the failure, which lets [loadWhenNearEnd] ask for the same page again. */
    fun retry() {
        failed = false
    }

    /**
     * Asks for the next page whenever the grid's last few cells are on screen
     * and no page is in flight or failed. Collected one page at a time, so
     * scrolling while a page loads cannot ask for it twice. The offset is part
     * of what is observed: a page that leaves the end still in view triggers
     * the next one rather than reading as no change.
     */
    suspend fun loadWhenNearEnd(grid: LazyGridState) {
        snapshotFlow {
            val layout = grid.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow null
            val nearEnd = last >= layout.totalItemsCount - 1 - LoadAheadCells
            paging.received.takeIf { nearEnd && !paging.exhausted && !loading && !failed }
        }
            .filterNotNull()
            .collect { loadNext() }
    }

    private suspend fun loadNext() {
        loading = true
        try {
            val page = graph.browse.catalogNextPage(source, paging.nextExtra(source.extra))
            paging = paging.append(page)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
}

/** How close to the grid's last cell the next page is asked for: a few rows' worth on any width. */
private const val LoadAheadCells = 12

/** Below the grid while a page loads, or after one failed. */
@Composable
private fun PagingFooter(pager: ShelfPager) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = HaloSpacing.Lg),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!pager.failed) {
            CircularProgressIndicator(Modifier.size(20.dp), color = HaloColors.Accent, strokeWidth = 2.dp)
            return@Row
        }
        Text("Couldn't load more", color = HaloColors.TextDim, fontSize = 14.sp)
        Box(
            Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(HaloRadius.Sm))
                .background(HaloColors.SurfaceHigh)
                .border(1.dp, HaloColors.Border, RoundedCornerShape(HaloRadius.Sm))
                .clickable(role = Role.Button, onClick = pager::retry)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Retry", color = HaloColors.Text, fontSize = 14.sp)
        }
    }
}
