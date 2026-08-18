package moe.ditto.halo.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

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
                    .clip(RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner))
                    .animateEnterExit(
                        enter = slideInVertically(tween(220)) { it },
                        exit = slideOutVertically(tween(180)) { it },
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

private fun sheetShape(side: Boolean) = if (side) {
    RoundedCornerShape(topStart = SheetCorner, bottomStart = SheetCorner)
} else {
    RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner)
}
