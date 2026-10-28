package moe.ditto.halo.screens.signin

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.monoStyle

/** How long each backdrop holds before the next fades in. */
private const val BackdropDwellMillis = 9_000L
private const val BackdropFadeMillis = 1_600

/** The design's ease, shared with the card's entrance. */
internal val SignInEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * The window's ground: a real backdrop, blurred and dimmed under a shade that
 * deepens towards the card, crossfading through [SignInArtwork.backdrops].
 * Without blur support (Android before 12) the shade alone keeps it quiet.
 */
@Composable
internal fun SignInBackdrop(modifier: Modifier = Modifier) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BackdropDwellMillis)
            index = (index + 1) % SignInArtwork.backdrops.size
        }
    }
    Box(modifier.fillMaxSize().background(HaloColors.Background)) {
        Crossfade(
            targetState = index,
            animationSpec = tween(BackdropFadeMillis),
            label = "sign-in-backdrop",
        ) { shown ->
            HaloAsyncImage(
                url = SignInArtwork.backdrops[shown],
                contentDescription = null,
                // Scaled past the edges so the blur has no hard border to soften.
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.15f
                        scaleY = 1.15f
                        alpha = 0.5f
                    }
                    .blur(26.dp),
                placeholder = {},
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to HaloColors.Background.copy(alpha = 0.35f),
                    0.3f to HaloColors.Background.copy(alpha = 0.25f),
                    0.62f to HaloColors.Background.copy(alpha = 0.85f),
                    1f to HaloColors.Background.copy(alpha = 0.97f),
                ),
            ),
        )
    }
}

/** One poster's place in the 390 × 270 dp design frame. */
private data class FanSlot(val x: Dp, val y: Dp, val width: Dp, val height: Dp, val rotation: Float, val delayMillis: Int)

/**
 * Mirror-image about the centre poster (x 195): the neighbours sit 69 dp out
 * and the outer pair 133 dp, each pair at one height and one angle.
 */
private val FanSlots = listOf(
    FanSlot(6.dp, 102.dp, 112.dp, 168.dp, -13f, 600),
    FanSlot(70.dp, 70.dp, 112.dp, 168.dp, -6f, 480),
    FanSlot(125.dp, 42.dp, 140.dp, 210.dp, 0f, 360),
    FanSlot(208.dp, 70.dp, 112.dp, 168.dp, 6f, 480),
    FanSlot(272.dp, 102.dp, 112.dp, 168.dp, 13f, 600),
)

private val DesignWidth = 390.dp
private val DesignHeight = 270.dp

/** Back to front: the outer pair, the neighbours, then the centre poster. */
private val PaintOrder = listOf(0, 4, 1, 3, 2)

/**
 * The object behind the card: the halo ring with five posters rising from
 * behind the card into a fan. Laid out in the design's own frame and scaled
 * to the width it is given, so the fan keeps its proportions on any phone.
 */
@Composable
internal fun SignInPosterFan(modifier: Modifier = Modifier) {
    val ring = remember { Animatable(0f) }
    val rises = remember { FanSlots.map { Animatable(0f) } }
    LaunchedEffect(Unit) {
        launch {
            delay(550)
            ring.animateTo(1f, tween(1500, easing = SignInEase))
        }
        FanSlots.forEachIndexed { index, slot ->
            launch {
                delay(slot.delayMillis.toLong())
                rises[index].animateTo(1f, tween(1100, easing = SignInEase))
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().height(DesignHeight), contentAlignment = Alignment.TopCenter) {
        val scale = (maxWidth / DesignWidth).coerceIn(0.8f, 1.25f)
        Box(
            Modifier
                .requiredSize(DesignWidth, DesignHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        ) {
            HaloRing(
                progress = ring.value,
                modifier = Modifier.offset(8.dp, 120.dp).size(374.dp, 118.dp),
            )
            for (index in PaintOrder) {
                FanPoster(
                    poster = SignInArtwork.posters[index],
                    slot = FanSlots[index],
                    progress = rises[index].value,
                )
            }
        }
    }
}

/** The tilted halo ring: a thin accent line inside two soft glows. */
@Composable
private fun HaloRing(progress: Float, modifier: Modifier = Modifier) {
    Canvas(
        modifier.graphicsLayer {
            alpha = progress
            rotationZ = -24f + 14f * progress
            val grow = 0.7f + 0.3f * progress
            scaleX = grow
            scaleY = grow
        },
    ) {
        fun ring(color: Color, width: Float) {
            drawOval(
                color = color,
                topLeft = Offset(width / 2, width / 2),
                size = Size(size.width - width, size.height - width),
                style = Stroke(width),
            )
        }
        ring(HaloColors.AccentLight.copy(alpha = 0.06f), 16.dp.toPx())
        ring(HaloColors.AccentLight.copy(alpha = 0.14f), 6.dp.toPx())
        ring(HaloColors.AccentLight.copy(alpha = 0.55f), 2.dp.toPx())
    }
}

@Composable
private fun FanPoster(poster: SignInArtwork.Poster, slot: FanSlot, progress: Float) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .offset(slot.x, slot.y)
            .size(slot.width, slot.height)
            .graphicsLayer {
                // Rises out from behind the card, turning into its place in the fan.
                translationY = (1f - progress) * 170.dp.toPx()
                alpha = progress
                rotationZ = slot.rotation * progress
            }
            .shadow(20.dp, shape)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape),
    ) {
        HaloAsyncImage(
            url = poster.url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            placeholder = {},
        )
        // The glass highlight across the top-left corner.
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.14f),
                    0.38f to Color.Transparent,
                ),
            ),
        )
        PosterBadge(poster.badge, Modifier.padding(8.dp))
    }
}

@Composable
private fun PosterBadge(badge: SignInArtwork.Badge, modifier: Modifier = Modifier) {
    val (label, color) = when (badge) {
        SignInArtwork.Badge.None -> return
        SignInArtwork.Badge.Offline -> "OFFLINE" to Color(0xFF7FDCFF)
        SignInArtwork.Badge.Quality -> "4K · HDR" to Color(0xFFF4D27A)
    }
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (badge == SignInArtwork.Badge.Offline) {
            Icon(HaloIcons.Check, contentDescription = null, tint = color, modifier = Modifier.size(10.dp))
        }
        Text(label, style = monoStyle(9.5.sp, FontWeight.Bold, color, 0.6.sp))
    }
}
