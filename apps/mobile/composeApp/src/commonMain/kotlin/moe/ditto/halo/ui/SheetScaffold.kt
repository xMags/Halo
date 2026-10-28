package moe.ditto.halo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

enum class SheetPresentation {
    Bottom,
    Side,
}

internal val SheetCorner = HaloRadius.Xl + 4.dp

/**
 * Heavier than the tab bar's frost on purpose. A sheet is a modal surface that
 * covers half a screen of dense rows, so the blur has to destroy their structure
 * outright — at the chrome radius the text underneath stays traceable and the
 * card reads as a transparent pane rather than as glass.
 */
private val SheetBlur = 44.dp

/**
 * The frosted card every in-tree sheet is built on: the scrim that dismisses it,
 * the slide, the glass, and the grab handle.
 *
 * These are in-tree overlays rather than platform dialogs on purpose. They have
 * to work inside the orientation-locked player, where they must inherit that
 * screen's own metrics and sit inside its layout — a separate window would not.
 * Render one as the LAST child of a screen's root Box, never nested inside
 * scrolling content, so it draws above everything else.
 *
 * @param bottomClearance room to leave under the content for chrome that draws
 *   over this overlay. The tab bar is a sibling of the navigation host and draws
 *   after it, so on a tabbed screen an overlay's last row lands underneath the
 *   bar unless it is pushed clear: those callers pass
 *   [HaloDimensions.TabBarSpace]. Screens that hide the bar pass nothing.
 */
@Composable
internal fun SheetScaffold(
    visible: Boolean,
    onDismiss: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    presentation: SheetPresentation = SheetPresentation.Bottom,
    bottomClearance: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val responsive = rememberResponsive()
    val side = presentation == SheetPresentation.Side

    // The backdrop fades with the container while the card slides on top of it;
    // sliding the backdrop too would drag the dimming in from off-screen.
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(durationMillis = 220)),
        exit = fadeOut(tween(durationMillis = 180)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )

            val drag = rememberSheetDrag(visible = visible, onDismiss = onDismiss)

            val cardModifier = if (side) {
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(min(responsive.width.value * 0.48f, 440f).dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = SheetCorner, bottomStart = SheetCorner))
                    .animateEnterExit(
                        enter = slideInHorizontally(tween(220)) { it },
                        exit = slideOutHorizontally(tween(180)) { it },
                    )
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    // A full-width sheet floats unanchored on a tablet; cap and
                    // centre it to roughly the iOS form-sheet width instead.
                    .then(
                        if (responsive.isTablet) {
                            Modifier.width(min(responsive.width.value * 0.72f, 640f).dp)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                    .heightIn(max = responsive.height * 0.7f)
                    // A layout offset rather than a graphics translation, so
                    // the frost samples what is behind the card where it is.
                    .offset { IntOffset(0, drag.offset.roundToInt()) }
                    .clip(RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner))
                    .animateEnterExit(
                        enter = slideInVertically(tween(220)) { it },
                        exit = slideOutVertically(tween(180)) { it },
                    )
                    .onSizeChanged { drag.height = it.height.toFloat() }
                    .nestedScroll(drag.connection)
                    .draggable(
                        state = drag.draggable,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> drag.settle(velocity) },
                    )
            }

            Column(
                modifier = cardModifier
                    .glassSurface(HaloColors.SheetTint, blurRadius = SheetBlur)
                    .border(1.dp, HaloColors.GlassBorder, sheetShape(side))
                    .padding(top = HaloSpacing.Sm, bottom = bottomPadding + bottomClearance),
            ) {
                if (!side) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = HaloSpacing.Sm)
                            .width(38.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(HaloRadius.Pill))
                            .background(Color.White.copy(alpha = 0.25f)),
                    )
                }
                content()
            }
        }
    }
}

/**
 * Drag-to-dismiss for a bottom sheet. The card follows the finger down from its
 * resting place (never up past it), from anywhere on the card and from a list
 * inside it once that list is scrolled to its top. On release it closes when it
 * has travelled a quarter of its height or was flicked down, and springs back
 * otherwise.
 */
@Stable
private class SheetDrag(
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit,
    private val dismissVelocity: Float,
) {
    var offset by mutableFloatStateOf(0f)
        private set
    var height = 0f
    private var springBack: Job? = null

    /** Moves the card by [delta], clamped at its resting place; returns what it used. */
    private fun dragBy(delta: Float): Float {
        springBack?.cancel()
        val target = (offset + delta).coerceAtLeast(0f)
        val consumed = target - offset
        offset = target
        return consumed
    }

    val draggable = DraggableState { delta -> dragBy(delta) }

    fun settle(velocity: Float) {
        if (offset <= 0f) return
        if (velocity > dismissVelocity || (height > 0f && offset > height * DismissFraction)) {
            onDismiss()
            return
        }
        springBack?.cancel()
        springBack = scope.launch {
            animate(offset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                offset = value
            }
        }
    }

    fun reset() {
        springBack?.cancel()
        offset = 0f
    }

    val connection = object : NestedScrollConnection {
        // Pushing a dragged-down card back up moves the card before the list.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y >= 0f || offset <= 0f) return Offset.Zero
            return Offset(0f, dragBy(available.y))
        }

        // What a list at its top could not use pulls the card down.
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            return Offset(0f, dragBy(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset <= 0f) return Velocity.Zero
            settle(available.y)
            return available
        }
    }

    private companion object {
        const val DismissFraction = 0.25f
    }
}

@Composable
private fun rememberSheetDrag(visible: Boolean, onDismiss: () -> Unit): SheetDrag {
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val dismissVelocity = with(LocalDensity.current) { 900.dp.toPx() }
    val drag = remember(scope, dismissVelocity) {
        SheetDrag(scope, onDismiss = { currentOnDismiss() }, dismissVelocity = dismissVelocity)
    }
    // A sheet reopened after a drag-dismiss starts from its resting place.
    LaunchedEffect(visible) { if (visible) drag.reset() }
    return drag
}

private fun sheetShape(side: Boolean) = if (side) {
    RoundedCornerShape(topStart = SheetCorner, bottomStart = SheetCorner)
} else {
    RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner)
}
