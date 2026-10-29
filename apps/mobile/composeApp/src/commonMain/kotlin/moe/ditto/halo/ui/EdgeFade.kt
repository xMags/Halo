package moe.ditto.halo.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How far a row's hidden edge fades, the desktop shelf's 12px. */
private val EdgeFadeWidth = 12.dp

/**
 * Fades whichever ends of a horizontal row still hide cards, so a card cut off
 * at the row's edge reads as "more this way" rather than as clipped. An end
 * already in view stays sharp. The desktop shelf's mask.
 */
fun Modifier.fadeHiddenEdges(state: LazyListState, width: Dp = EdgeFadeWidth): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val start = state.canScrollBackward
        val end = state.canScrollForward
        if (!start && !end) return@drawWithContent
        val fraction = (width.toPx() / size.width).coerceIn(0f, 0.5f)
        drawRect(
            brush = Brush.horizontalGradient(
                0f to if (start) Color.Transparent else Color.Black,
                fraction to Color.Black,
                1f - fraction to Color.Black,
                1f to if (end) Color.Transparent else Color.Black,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
