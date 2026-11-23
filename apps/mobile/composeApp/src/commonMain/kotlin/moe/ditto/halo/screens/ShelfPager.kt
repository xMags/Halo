package moe.ditto.halo.screens

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.browse.CatalogPaging

/**
 * One "See all" grid's paging: the titles so far, and the state of the page
 * being asked for. Lives as long as the screen's snapshot; read and written on
 * the main thread only.
 */
@Stable
internal class ShelfPager(private val graph: SessionGraph, first: List<MetaCard>, from: ShelfPaging) {
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
