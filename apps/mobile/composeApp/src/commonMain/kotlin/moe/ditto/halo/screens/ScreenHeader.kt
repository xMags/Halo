package moe.ditto.halo.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ditto.halo.ui.BrowseHeaderLayout
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloLayout
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.SearchFieldButton
import moe.ditto.halo.ui.Segmented
import moe.ditto.halo.ui.rememberResponsive

/**
 * The top of a browse screen: back, title, subtitle, search, filter — with the
 * same spacing everywhere.
 *
 * One component rather than a per-screen arrangement, so the tabs cannot drift
 * apart by a few dp at a time. Both affordances below the title are optional;
 * omitting one closes the gap it occupied instead of leaving a hole.
 *
 * The three land in a column on a phone, one control per line. Given a tablet's
 * width they move onto the title's own line, which reclaims most of a hundred dp
 * of vertical space on every browse screen — and where that is still too narrow
 * for all three, the pair sits on one row under the title instead. See
 * [BrowseHeaderLayout].
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
    /**
     * Names what the screen is about when the title names only what it does —
     * "Sources" over an episode, rather than the episode over nothing.
     */
    subtitle: String? = null,
    /** Back affordance above the title. Null on a tab, which has nowhere to go. */
    onBack: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    /** Shown only when the screen has something to filter; null leaves it out. */
    filter: MediaTypeFilter? = null,
    onFilterChange: (MediaTypeFilter) -> Unit = {},
) {
    val responsive = rememberResponsive()
    // A header with nothing under the title has nothing to rearrange.
    val layout = if (onOpenSearch == null && filter == null) {
        BrowseHeaderLayout.Stacked
    } else {
        responsive.browseHeaderLayout
    }

    val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
        Row(
            modifier = controlsModifier,
            horizontalArrangement = Arrangement.spacedBy(ControlRowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onOpenSearch != null) {
                SearchFieldButton(onClick = onOpenSearch, modifier = Modifier.weight(1f))
            }
            if (filter != null) {
                // Sized to its three labels rather than stretched: a segmented
                // control as wide as the screen reads as three buttons.
                FilterSegments(filter, onFilterChange, Modifier.width(IntrinsicSize.Min))
            }
        }
    }

    Column(
        modifier.padding(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                responsive.pick(HaloSpacing.Xs, HaloSpacing.Sm),
            bottom = responsive.pick(HaloSpacing.Md, 22.dp),
        ),
    ) {
        if (onBack != null) {
            // Left of and above the title, where a platform back button sits, and
            // offset back out so the glyph lines up with the title's left edge
            // rather than its own padding.
            Icon(
                imageVector = HaloIcons.ChevronLeft,
                contentDescription = "Back",
                tint = HaloColors.Text,
                modifier = Modifier
                    .offset(x = -HaloSpacing.Sm)
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(HaloSpacing.Sm)
                    .size(24.dp),
            )
        }

        if (layout == BrowseHeaderLayout.Beside) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Lg),
                // Sat on the title's baseline rather than centred on it: the
                // title is twice the height of either control.
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = title,
                    style = HaloType.LargeTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The controls keep to the trailing edge and stop growing at
                // their stated width; the box is what holds them there.
                Box(Modifier.weight(2f), contentAlignment = Alignment.BottomEnd) {
                    controls(Modifier.widthIn(max = HaloLayout.BrowseHeaderControlsWidth))
                }
            }
            return@Column
        }

        Text(text = title, style = HaloType.LargeTitle)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = HaloType.Body.copy(color = HaloColors.TextDim),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (layout == BrowseHeaderLayout.Below) {
            controls(Modifier.fillMaxWidth().padding(top = ControlGap))
            return@Column
        }

        if (onOpenSearch != null) {
            // A button, not a field: search is its own screen, so tapping here
            // navigates rather than raising a keyboard over a browse surface.
            SearchFieldButton(
                onClick = onOpenSearch,
                modifier = Modifier.padding(top = ControlGap),
            )
        }
        if (filter != null) {
            FilterSegments(filter, onFilterChange, Modifier.padding(top = ControlGap))
        }
    }
}

@Composable
private fun FilterSegments(
    filter: MediaTypeFilter,
    onFilterChange: (MediaTypeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Segmented(
        options = MediaTypeFilter.labels,
        value = filter.label,
        onChange = { label -> MediaTypeFilter.byLabel(label)?.let(onFilterChange) },
        modifier = modifier,
    )
}

/** Gap between the title and each control under it. */
private val ControlGap = HaloSpacing.Sm + 4.dp

/** Gap between the search field and the filter, once they share a row. */
private val ControlRowGap = 14.dp
