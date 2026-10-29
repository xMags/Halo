package moe.ditto.halo.screens.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.toSize

/**
 * The bands along the window's edges where the system's own gestures start
 * (back from the sides, home from the bottom, the shade from the top), in
 * pixels of the window the insets are measured against.
 */
internal data class SystemGestureEdges(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Whether [point], in the same window pixels, lies in one of the bands. */
    fun contain(point: Offset, window: Size): Boolean {
        if (window.width <= 0f || window.height <= 0f) return false
        return point.x < left ||
            point.y < top ||
            point.x >= window.width - right ||
            point.y >= window.height - bottom
    }

    companion object {
        val None = SystemGestureEdges(0f, 0f, 0f, 0f)
    }
}

/**
 * Tells whether a touch on one element started in a system gesture band.
 *
 * The system only claims an edge swipe once it has recognised it, so the
 * element under the finger sees the first part of it as its own: a swipe home
 * across the timeline seeks, and a swipe back on the video's left or right
 * half moves brightness or volume. Positions are tracked against the root, which
 * is the window the player fills, because that is what the insets describe.
 */
@Stable
internal class SystemEdgeTouchGuard {
    internal var edges: SystemGestureEdges = SystemGestureEdges.None
    private var origin = Offset.Zero
    private var window = Size.Zero

    fun place(coordinates: LayoutCoordinates) {
        origin = coordinates.positionInRoot()
        window = coordinates.findRootCoordinates().size.toSize()
    }

    fun startsAtSystemEdge(local: Offset): Boolean = edges.contain(origin + local, window)
}

@Composable
internal fun rememberSystemEdgeTouchGuard(): SystemEdgeTouchGuard {
    // Reported whether or not the bars are showing, which is what makes them
    // usable while the player is immersive.
    val insets = WindowInsets.systemGestures
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val edges = SystemGestureEdges(
        left = insets.getLeft(density, layoutDirection).toFloat(),
        top = insets.getTop(density).toFloat(),
        right = insets.getRight(density, layoutDirection).toFloat(),
        bottom = insets.getBottom(density).toFloat(),
    )
    val guard = remember { SystemEdgeTouchGuard() }
    SideEffect { guard.edges = edges }
    return guard
}

/**
 * Keeps touches that start in a system gesture band away from the gestures
 * declared after this modifier on the same element.
 *
 * It claims such a touch's down and every move of it in the initial pass, which
 * runs before theirs: Compose's tap detector waits for an unclaimed down and
 * its drag detectors give up on a claimed move, so none of them act on it. A
 * touch that starts anywhere else is left untouched. Only the elements that use
 * this are affected; buttons elsewhere near an edge keep working.
 */
internal fun Modifier.ignoreTouchesFromSystemEdges(guard: SystemEdgeTouchGuard): Modifier = this
    .onGloballyPositioned(guard::place)
    .pointerInput(guard) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!guard.startsAtSystemEdge(down.position)) return@awaitEachGesture
            down.consume()
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial)
                    .changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
            }
        }
    }
