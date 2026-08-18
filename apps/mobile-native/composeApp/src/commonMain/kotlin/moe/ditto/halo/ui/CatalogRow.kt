package moe.ditto.halo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
) {
    if (!isLoading && items.isEmpty()) return

    Column(modifier.padding(bottom = bottomPadding)) {
        Text(
            text = title,
            style = HaloType.Heading,
            modifier = Modifier.padding(
                start = gutter,
                end = gutter,
                bottom = HaloSpacing.Sm + 2.dp,
            ),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter),
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
                )
            }
        }
    }
}
