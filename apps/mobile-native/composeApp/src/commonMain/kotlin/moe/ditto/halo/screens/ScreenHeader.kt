package moe.ditto.halo.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.SearchFieldButton
import moe.ditto.halo.ui.Segmented

/**
 * The top of a browse screen: title, search, filter — in that order, with the
 * same spacing everywhere.
 *
 * One component rather than a per-screen arrangement, so the tabs cannot drift
 * apart by a few dp at a time. Both affordances below the title are optional;
 * omitting one closes the gap it occupied instead of leaving a hole.
 *
 * It owns the status-bar inset, so content scrolls under the status bar and the
 * caller's list needs no top padding. It deliberately does NOT own horizontal
 * padding: inside a grid that comes from the grid's own content padding, and
 * adding it twice would inset the title past the posters it sits above.
 */
@Composable
internal fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onOpenSearch: (() -> Unit)? = null,
    /** Shown only when the screen has something to filter; null leaves it out. */
    filter: MediaTypeFilter? = null,
    onFilterChange: (MediaTypeFilter) -> Unit = {},
) {
    Column(
        modifier.padding(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HaloSpacing.Xs,
            bottom = HaloSpacing.Md,
        ),
    ) {
        Text(text = title, style = HaloType.LargeTitle)
        if (onOpenSearch != null) {
            // A button, not a field: search is its own screen, so tapping here
            // navigates rather than raising a keyboard over a browse surface.
            SearchFieldButton(
                onClick = onOpenSearch,
                modifier = Modifier.padding(top = ControlGap),
            )
        }
        if (filter != null) {
            Segmented(
                options = MediaTypeFilter.labels,
                value = filter.label,
                onChange = { label -> MediaTypeFilter.byLabel(label)?.let(onFilterChange) },
                modifier = Modifier.padding(top = ControlGap),
            )
        }
    }
}

/** Gap between the title and each control under it. */
private val ControlGap = HaloSpacing.Sm + 4.dp
