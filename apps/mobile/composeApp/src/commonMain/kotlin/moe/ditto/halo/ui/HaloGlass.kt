package moe.ditto.halo.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * The blur source for translucent chrome — the tab bar and sheets read this to
 * sample whatever is behind them.
 *
 * Null means no screen has offered its content up, in which case frosted
 * surfaces fall back to a flat fill. That fallback is not merely a nicety: a
 * translucent panel with nothing blurred behind it leaves the content underneath
 * sharp and legible straight through the panel, which reads as a rendering bug
 * rather than as glass. Opacity alone lowers contrast; only blur destroys the
 * structure your eye is picking out.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Marks this content as the backdrop that frosted chrome above it samples.
 *
 * **A frosted surface must not be a descendant of the source it samples.** A blur
 * cannot read content it is itself part of, and the failure is not a graceful
 * one: the tint is applied inside the render effect, so an effect that samples
 * nothing draws nothing at all and the surface comes out clear. The shell marks
 * the whole navigation host, which serves chrome outside it — the tab bar. A
 * screen that renders its own overlay (a sheet) must therefore mark only its
 * content and provide [LocalHazeState] for its own subtree, keeping the overlay
 * a sibling of the source rather than a child.
 */
fun Modifier.glassSource(state: HazeState): Modifier = hazeSource(state)

/**
 * Frosted translucent fill for chrome that floats over content.
 *
 * Falls back to the tint flattened onto the app background when no source is
 * registered, so the surface stays opaque and readable instead of showing sharp
 * content through itself.
 */
@Composable
fun Modifier.glassSurface(tint: Color, blurRadius: androidx.compose.ui.unit.Dp = 24.dp): Modifier {
    val state = LocalHazeState.current ?: return background(tint.compositeOver(HaloColors.Background))
    return hazeEffect(state) {
        this.blurRadius = blurRadius
        backgroundColor = HaloColors.Background
        tints = listOf(HazeTint(tint))
    }
}
