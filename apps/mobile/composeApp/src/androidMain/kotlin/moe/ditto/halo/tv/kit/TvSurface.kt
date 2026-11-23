package moe.ditto.halo.tv.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ditto.halo.ui.HaloColors

/**
 * Remembers which TV control had focus last, so an overlay can give focus
 * back to whatever opened it. Provided once at the TV root.
 */
internal class TvFocusTracker {
    var last: FocusRequester? = null
}

internal val LocalTvFocusTracker = staticCompositionLocalOf { TvFocusTracker() }

/** A surface's fill at rest and while focused. */
internal data class TvSurfaceColors(val rest: Color, val focused: Color)

/**
 * Tracks one press of the select key, so a hold opens the menu and a short
 * press clicks. A select that was already down when focus arrived (the press
 * that opened this screen) is ignored, so it can never click twice.
 */
internal class TvSelectPress {
    private var down = false
    private var held = false

    /** Returns true when the press became a hold just now. */
    fun onDown(repeatCount: Int, longPress: Boolean): Boolean {
        if (repeatCount == 0 && !longPress) {
            down = true
            held = false
            return false
        }
        if (!down || held) return false
        held = true
        return true
    }

    /** Returns true when this release completes a short press. */
    fun onUp(): Boolean {
        val click = down && !held
        down = false
        held = false
        return click
    }

    fun reset() {
        down = false
        held = false
    }
}

/**
 * Handles the remote's select and menu keys for a focusable: a short select
 * press calls [onClick], holding select or pressing Menu calls [onMenu].
 * Place it before the `focusable` it serves.
 */
internal fun Modifier.tvSelectKeys(
    press: TvSelectPress,
    onClick: () -> Unit,
    onMenu: (() -> Unit)?,
): Modifier = onKeyEvent { event -> handleSelectKey(event, press, onClick, onMenu) }

private fun handleSelectKey(
    event: KeyEvent,
    press: TvSelectPress,
    onClick: () -> Unit,
    onMenu: (() -> Unit)?,
): Boolean {
    val native = event.nativeKeyEvent
    return when (TvKeyMap.actionFor(native.keyCode)) {
        TvAction.Select -> {
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (press.onDown(native.repeatCount, native.isLongPress)) onMenu?.invoke()
                }
                KeyEventType.KeyUp -> if (press.onUp()) onClick()
            }
            true
        }
        TvAction.Menu -> {
            if (onMenu == null) return false
            if (event.type == KeyEventType.KeyUp) onMenu()
            true
        }
        else -> false
    }
}

/**
 * The base of every focusable TV control: a rounded surface that grows, gets a
 * brighter fill and an accent ring while focused, and answers the remote's
 * select and menu keys (and a tap, which helps when testing on a touch
 * screen). [content] receives whether the surface is focused.
 */
@Composable
internal fun TvSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    enabled: Boolean = true,
    cornerRadius: Dp = 12.dp,
    colors: TvSurfaceColors = TvSurfaceColors(rest = HaloColors.Surface, focused = HaloColors.SurfaceHigh),
    focusScale: Float = TvMetrics.FocusScale,
    ringBackground: Color = HaloColors.Background,
    contentAlignment: Alignment = Alignment.TopStart,
    onFocusChange: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val press = remember { TvSelectPress() }
    val self = remember { FocusRequester() }
    val tracker = LocalTvFocusTracker.current
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnMenu by rememberUpdatedState(onMenu)
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(DurationMs), label = "tv-focus-scale")
    val fill by animateColorAsState(if (focused) colors.focused else colors.rest, tween(DurationMs), label = "tv-focus-fill")
    val shape = RoundedCornerShape(cornerRadius)
    val menu = currentOnMenu?.let { { it() } }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawWithContent {
                drawContent()
                if (!focused) return@drawWithContent
                val ring = TvMetrics.FocusRingWidth.toPx()
                val gap = TvMetrics.FocusRingGap.toPx()
                val outset = gap + ring / 2f
                val radius = cornerRadius.toPx() + outset
                drawRoundRect(
                    color = ringBackground,
                    topLeft = Offset(-gap / 2f, -gap / 2f),
                    size = Size(size.width + gap, size.height + gap),
                    cornerRadius = CornerRadius(cornerRadius.toPx() + gap / 2f),
                    style = Stroke(width = gap),
                )
                drawRoundRect(
                    color = HaloColors.Accent,
                    topLeft = Offset(-outset, -outset),
                    size = Size(size.width + outset * 2f, size.height + outset * 2f),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = ring),
                )
            }
            .clip(shape)
            .background(fill)
            .onFocusChanged { state ->
                if (state.isFocused != focused) {
                    focused = state.isFocused
                    if (state.isFocused) tracker.last = self
                    if (!state.isFocused) press.reset()
                    onFocusChange?.invoke(state.isFocused)
                }
            }
            .focusRequester(self)
            .focusProperties { canFocus = enabled }
            .tvSelectKeys(press, onClick = { if (enabled) currentOnClick() }, onMenu = menu)
            .focusable(enabled)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { currentOnClick() },
                    onLongPress = { currentOnMenu?.invoke() },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick {
                    currentOnClick()
                    true
                }
                if (menu != null) {
                    onLongClick {
                        menu()
                        true
                    }
                }
            },
        contentAlignment = contentAlignment,
    ) {
        content(focused)
    }
}

private const val DurationMs = 140
