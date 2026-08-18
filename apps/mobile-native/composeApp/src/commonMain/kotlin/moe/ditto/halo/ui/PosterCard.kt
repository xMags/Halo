package moe.ditto.halo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement

/**
 * One poster's worth of display data.
 *
 * Catalog metas, library rows, and watch-state rows are three different API
 * shapes that all render as this same card, so the card is typed against this
 * rather than against any one of them; screens map their own data in.
 */
data class PosterItem(
    val key: String,
    val title: String,
    val posterUrl: String? = null,
    /** 0..1 watch progress; null for anything never played. */
    val progress: Float? = null,
)

/** Below this, the progress bar is a sliver that reads as a rendering artifact. */
private const val MinVisibleProgress = 0.02f

private val PosterShape = RoundedCornerShape(HaloRadius.Md)

/**
 * Poster-forward card: art only unless [showLabel] is set.
 *
 * Size comes from the caller's [modifier] — `Modifier.width(...)` inside a row,
 * or nothing at all inside a grid cell, which already arrives at a fixed width.
 * The art holds the poster aspect ratio either way, so a card never has to be
 * told both dimensions.
 */
@Composable
fun PosterCard(
    item: PosterItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    Column(modifier.clickable(onClick = onClick)) {
        HaloAsyncImage(
            url = item.posterUrl,
            contentDescription = item.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f / HaloDimensions.PosterRatio)
                .clip(PosterShape),
        )
        val progress = item.progress
        if (progress != null && progress > MinVisibleProgress) {
            Box(
                Modifier
                    .padding(top = 7.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction = progress.coerceAtMost(1f))
                        .fillMaxSize()
                        .background(HaloColors.Accent),
                )
            }
        }
        if (showLabel) {
            Text(
                text = item.title,
                color = HaloColors.TextDim,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Fixed-column poster grid.
 *
 * [GridCells.Fixed] gives every cell an identical width and lets a partial final
 * row keep that width instead of stretching to fill, which is what the layout
 * wants — stretched trailing cells are glaring at high column counts.
 */
@Composable
fun PosterGrid(
    items: List<PosterItem>,
    columns: Int,
    onItemClick: (PosterItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = HaloSpacing.Md),
    showLabels: Boolean = false,
    /** Cell spacing. Rows sit further apart than columns: a poster is half again
     *  as tall as it is wide, so equal gaps read as a tighter vertical rhythm. */
    horizontalGap: Dp = HaloLayout.PhonePosterGridGap,
    verticalGap: Dp = HaloLayout.PhonePosterGridRowGap,
    /**
     * Screen chrome above the first row, spanning the full width and scrolling
     * with the grid. A title and filter fixed above the grid instead would cost
     * the posters that height permanently, on the screen that has the least of
     * it to spare.
     */
    header: @Composable (() -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(horizontalGap),
        verticalArrangement = Arrangement.spacedBy(verticalGap),
    ) {
        if (header != null) {
            item(key = "grid-header", span = { GridItemSpan(maxLineSpan) }) { header() }
        }
        items(items = items, key = { it.key }) { item ->
            PosterCard(item = item, onClick = { onItemClick(item) }, showLabel = showLabels)
        }
    }
}
