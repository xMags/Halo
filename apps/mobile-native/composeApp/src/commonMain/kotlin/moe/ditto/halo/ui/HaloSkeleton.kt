package moe.ditto.halo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * Loading placeholder: a highlight band sweeping across a surface tile. Sized
 * and shaped entirely by the caller's [modifier] — clip it to match whatever it
 * stands in for, so the skeleton occupies the exact frame the real content will.
 *
 * The sweep travels two widths so the band clears the frame completely before
 * restarting; a one-width travel makes it reappear the instant it exits, which
 * reads as a stutter rather than a pulse.
 *
 * Note for tests: this is an infinite animation, so Compose never reports idle
 * while one is on screen. Compose UI tests that can see a skeleton must drive
 * the clock manually (`mainClock.autoAdvance = false`) instead of awaiting idle.
 */
@Composable
fun HaloSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeleton-sweep",
    )
    Box(
        modifier.drawBehind {
            drawRect(HaloColors.Surface)
            val band = size.width
            val startX = -band + sweep * band * 2f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(HaloColors.Surface, HaloColors.SurfaceHigh, HaloColors.Surface),
                    start = Offset(startX, 0f),
                    end = Offset(startX + band, 0f),
                ),
            )
        },
    )
}
