package moe.ditto.halo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Placeholder tiles shown while a catalog loads — roughly a phone screen's worth. */
private const val SkeletonTileCount = 5

/**
 * A horizontally scrolling strip of posters under a heading.
 *
 * A catalog that finishes loading with nothing in it removes itself entirely
 * rather than leaving a heading stranded over empty space. Addons fail and
 * return empty results routinely, so this is the common path, not an edge case:
 * callers hand over whatever they got and let the row decide whether to exist.
 */
@Composable
fun CatalogRow(
    title: String,
    items: List<PosterItem>,
    onItemClick: (PosterItem) -> Unit,
    posterWidth: Dp,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    /**
     * Names under the art. Off for browse rows, where the poster is the point
     * and a caption under every card is noise; on for search results, where the
     * titles are unfamiliar by definition.
     */
    showLabels: Boolean = false,
    /** The screen's gutter: where the heading starts and the strip scrolls from. */
    gutter: Dp = HaloSpacing.Md,
    /** Space between cards, which grows with them. */
    gap: Dp = HaloLayout.PhoneCatalogRowGap,
    bottomPadding: Dp = HaloSpacing.Lg,
    /**
     * A short mono line beside the heading, as on the desktop app: where a
     * catalog comes from ("CINEMETA") or what a shelf counts ("2 IN PROGRESS").
     */
    source: String? = null,
    /** Opens a card's long-press menu; null leaves long presses doing nothing. */
    itemMenu: ((PosterItem) -> Unit)? = null,
    /** The heading's "See all", which opens everything the row holds; null leaves it out. */
    onSeeAll: (() -> Unit)? = null,
    /**
     * Stops the strip at the gutter instead of running it to the screen's
     * edge, fading whichever end still hides cards, as the desktop's shelves
     * do. For a screen wide enough that a margin reads as the page's edge.
     */
    insetStrip: Boolean = false,
) {
    val stripState = rememberLazyListState()
    if (!isLoading && items.isEmpty()) return

    Column(modifier.padding(bottom = bottomPadding)) {
        RowHeading(title = title, source = source, gutter = gutter, onSeeAll = onSeeAll.takeIf { !isLoading })
        LazyRow(
            state = stripState,
            modifier = if (insetStrip) Modifier.padding(horizontal = gutter).fadeHiddenEdges(stripState) else Modifier,
            contentPadding = PaddingValues(horizontal = if (insetStrip) 0.dp else gutter),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (isLoading) {
                // Skeleton tiles rather than a spinner: the row occupies its
                // final height immediately, so arriving posters do not shove
                // the rest of the screen downwards.
                items(count = SkeletonTileCount) {
                    HaloSkeleton(
                        Modifier
                            .width(posterWidth)
                            .aspectRatio(1f / HaloDimensions.PosterRatio)
                            .clip(RoundedCornerShape(HaloRadius.Md)),
                    )
                }
                return@LazyRow
            }
            items(items = items, key = { it.key }) { item ->
                PosterCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.width(posterWidth),
                    showLabel = showLabels,
                    onLongPress = itemMenu?.let { open -> { open(item) } },
                )
            }
        }
    }
}

/**
 * A row's heading: its title, the short mono line beside it, and "See all" at
 * the end. Shared by every kind of row, so poster rows and Continue watching's
 * wide cards cannot drift apart.
 */
@Composable
fun RowHeading(title: String, source: String?, gutter: Dp, onSeeAll: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = gutter, end = gutter, bottom = HaloSpacing.Sm + 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
    ) {
        // The heading and its source line take whatever "See all" leaves.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
        ) {
            Text(
                text = title,
                style = HaloType.Heading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            source?.let {
                Text(
                    text = it,
                    style = monoStyle(11.sp, color = HaloColors.TextDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Sits on the heading's baseline rather than its box bottom.
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        if (onSeeAll != null) {
            Text(
                text = "See all",
                color = HaloColors.AccentLight,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(HaloRadius.Sm))
                    .clickable(role = Role.Button, onClick = onSeeAll)
                    .padding(horizontal = HaloSpacing.Xs, vertical = 2.dp),
            )
        }
    }
}
