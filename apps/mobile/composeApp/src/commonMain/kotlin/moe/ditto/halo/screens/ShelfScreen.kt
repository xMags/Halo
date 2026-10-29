package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.PosterGrid
import moe.ditto.halo.ui.rememberResponsive

/** What a Home row held when its "See all" was pressed. */
data class ShelfSnapshot(
    val title: String,
    /** The row's mono line ("TMDB", "3 IN PROGRESS"), if it had one. */
    val source: String?,
    val items: List<MetaCard>,
)

/**
 * The rows "See all" has opened, by key, for [ShelfRoute][moe.ditto.halo.shell.ShelfRoute].
 * A snapshot, as on the desktop app, so the grid shows exactly what the row
 * held. Kept in memory for the shell's lifetime; a handful of rows per session
 * is nothing worth evicting.
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
 */
@Composable
internal fun ShelfScreen(
    snapshot: ShelfSnapshot?,
    onBack: () -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
    val items = snapshot?.items.orEmpty()
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
            // This screen covers the tab bar, so only the system bar is cleared.
            contentPadding = PaddingValues(
                start = responsive.gutter,
                end = responsive.gutter,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + HaloSpacing.Lg,
            ),
            horizontalGap = responsive.posterGridGap,
            verticalGap = responsive.posterGridRowGap,
            showLabels = true,
            header = header,
        )
    }
}
