package moe.ditto.halo.tv.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Scrolls so the focused item's leading edge sits [pivotPx] from the start of
 * the list, the way a TV row keeps its focused card in one place while the
 * row slides under it. The list clamps at its ends on its own, so the first
 * cards never pull away from the edge.
 *
 * With [stayWhenVisible] an item already wholly in view does not move the
 * list, which suits a page that opens on its top: focusing its first button
 * must not scroll the title above it away. An item reaching into the last
 * [endMarginPx] of the list (a TV's overscan band at the screen's bottom)
 * does not count as in view.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class TvPivotBringIntoViewSpec(
    private val pivotPx: Float,
    private val stayWhenVisible: Boolean = false,
    private val endMarginPx: Float = 0f,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Something larger than the list cannot be pinned; show its start.
        if (size >= containerSize) return offset
        if (stayWhenVisible && offset >= 0f && offset + size <= containerSize - endMarginPx) return 0f
        return offset - pivotPx
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProvidePivot(pivot: Dp, content: @Composable () -> Unit) {
    val pivotPx = with(LocalDensity.current) { pivot.toPx() }
    val spec = remember(pivotPx) { TvPivotBringIntoViewSpec(pivotPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

private val RowHeadingGap = 14.dp
private val RowCardInset = 8.dp

/**
 * How far a titled [TvRow]'s cards sit below the row's top: its heading, the
 * gap and the cards' inset. A vertical list that scrolls a focused card to
 * this distance from its top shows the whole heading above it.
 */
@Composable
internal fun tvRowHeadingRoom(): Dp =
    with(LocalDensity.current) { TvType.Heading.lineHeight.toDp() } + RowHeadingGap + RowCardInset

/**
 * A titled horizontal row of cards. Focus returns to the card that last had it
 * when the row is entered again, and the focused card stays at the row's start
 * padding while the row scrolls.
 */
@Composable
internal fun TvRow(
    modifier: Modifier = Modifier,
    title: String? = null,
    state: LazyListState = rememberLazyListState(),
    startPadding: Dp = TvMetrics.ContentStart,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(RowHeadingGap)) {
        if (title != null) Text(title, style = TvType.Heading, modifier = Modifier.padding(start = startPadding))
        ProvidePivot(startPadding) {
            LazyRow(
                state = state,
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(start = startPadding, end = TvMetrics.SideMargin, top = RowCardInset, bottom = RowCardInset),
                horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap),
                content = content,
            )
        }
    }
}

/**
 * A vertical grid of cards with focus memory, scrolling so the focused row
 * keeps a fixed distance from the top.
 */
@Composable
internal fun TvGrid(
    columns: Int,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(
        start = TvMetrics.ContentStart,
        end = TvMetrics.SideMargin,
        top = 8.dp,
        bottom = TvMetrics.VerticalMargin,
    ),
    pivot: Dp = 24.dp,
    content: LazyGridScope.() -> Unit,
) {
    ProvidePivot(pivot) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = modifier.focusRestorer(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap),
            verticalArrangement = Arrangement.spacedBy(TvMetrics.RowGap),
            content = content,
        )
    }
}
