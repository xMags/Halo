package moe.ditto.halo.tv.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.screens.MetaRef
import moe.ditto.halo.screens.ShelfPager
import moe.ditto.halo.screens.ShelfSnapshot
import moe.ditto.halo.screens.TitleMenus
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvGrid
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvPosterCard
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.tv.kit.defaultFocus
import moe.ditto.halo.tv.kit.rememberFocus
import moe.ditto.halo.tv.kit.rememberTvFocusMemory
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloSkeleton
import moe.ditto.halo.ui.metahubPosterUrl

/**
 * A row's "See all" on TV: everything the row held as a poster grid, going on
 * page by page for a catalog that pages ([ShelfPager], shared with the phone).
 * A page that fails says so under the grid with a Retry.
 */
@Composable
internal fun TvShelfScreen(graph: SessionGraph, snapshot: ShelfSnapshot?, navigation: TvBrowseNavigation, menus: TitleMenus) {
    val memory = rememberTvFocusMemory()
    val pager = remember(graph, snapshot) { snapshot?.paging?.let { ShelfPager(graph, snapshot.items, it) } }
    val items: List<MetaCard> = pager?.paging?.items ?: snapshot?.items.orEmpty()
    val gridState = rememberLazyGridState()
    if (pager != null) {
        // Keyed by the pager, so leaving the screen cancels a page in flight.
        LaunchedEffect(pager, gridState) { pager.loadWhenNearEnd(gridState) }
    }
    val count = "${items.size} ${if (items.size == 1) "title" else "titles"}"

    Column(Modifier.fillMaxSize().padding(top = TvMetrics.VerticalMargin)) {
        Column(Modifier.padding(start = TvMetrics.SideMargin + 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(snapshot?.title ?: "Catalog", style = TvType.Title)
            Text(listOfNotNull(snapshot?.source, count).joinToString(" · "), style = TvType.Meta.copy(color = HaloColors.TextDim))
        }
        if (items.isEmpty()) {
            TvBodyMessage(
                "Nothing to show here any more. Go back to see this row again.",
                retryModifier = Modifier.defaultFocus(memory, "back"),
                onRetry = navigation.back,
                actionLabel = "Go back",
            )
            return@Column
        }
        TvGrid(
            columns = 7,
            state = gridState,
            contentPadding = PaddingValues(
                start = TvMetrics.SideMargin + 8.dp,
                end = TvMetrics.SideMargin,
                top = 8.dp,
                bottom = TvMetrics.VerticalMargin + 24.dp,
            ),
        ) {
            items(items, key = { "${it.type}:${it.id}" }) { meta ->
                val key = "shelf/${meta.type}:${meta.id}"
                TvPosterCard(
                    title = meta.name,
                    posterUrl = meta.poster,
                    fallbackUrl = metahubPosterUrl(meta.id),
                    subtitle = meta.releaseInfo,
                    width = 112.dp,
                    onClick = { navigation.openDetail(MetaRef(meta.type, meta.id)) },
                    onMenu = { menus.openPoster(meta) },
                    modifier = if (meta == items.first()) Modifier.defaultFocus(memory, key) else Modifier.rememberFocus(memory, key),
                )
            }
            if (pager != null && (pager.loading || pager.failed)) {
                item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pager.failed) {
                            Text("Couldn't load more.", style = TvType.Body)
                            TvButton("Retry", onClick = pager::retry, modifier = Modifier.rememberFocus(memory, "retry"))
                        } else {
                            HaloSkeleton(Modifier.padding(vertical = 4.dp).fillMaxWidth(0.3f))
                        }
                    }
                }
            }
        }
    }
}
