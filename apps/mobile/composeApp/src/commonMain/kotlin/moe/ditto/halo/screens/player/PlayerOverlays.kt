package moe.ditto.halo.screens.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloPlayerColors
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.monoStyle

private val OverlayPillFill = Color(red = 5f / 255f, green = 7f / 255f, blue = 12f / 255f, alpha = 0.82f)
private val PillBorder = Color.White.copy(alpha = 0.14f)
private val HudBorder = Color.White.copy(alpha = 0.18f)
private val LockPillBorder = Color.White.copy(alpha = 0.20f)
private val RingTrack = Color.White.copy(alpha = 0.12f)
private val MeterTrack = Color.White.copy(alpha = 0.22f)
private val GlassFill = Color.White.copy(alpha = 0.07f)
private val GlassBorder = Color.White.copy(alpha = 0.11f)
private val LockedScrim = Color(red = 4f / 255f, green = 5f / 255f, blue = 8f / 255f, alpha = 0.28f)
private val ErrorBackground = Color(0xFF05070C)
private val ErrorCardFill = Color(red = 20f / 255f, green = 22f / 255f, blue = 30f / 255f, alpha = 0.92f)
    .compositeOver(ErrorBackground)
private val ErrorCardBorder = Color.White.copy(alpha = 0.09f)
private val ErrorRule = Color.White.copy(alpha = 0.07f)

/**
 * How wide the failure card is allowed to get. It holds four short lines and two
 * buttons, so a landscape phone's full width would set the headline on one third
 * of a line and stretch the primary action into a banner.
 */
private val ErrorCardMaxWidth = 392.dp
private val UpNextCardFill = HaloPlayerColors.DrawerFill.compositeOver(HaloColors.Background)
private val UpNextCardBorder = Color.White.copy(alpha = 0.12f)
private val CountdownTrack = Color.White.copy(alpha = 0.14f)

/**
 * Filling the buffer.
 *
 * The percentage is the whole point of this overlay. A spinner alone cannot
 * distinguish a seek that is making progress from a stream that has stalled, and
 * those two need completely different reactions from the viewer. It is still
 * nullable: an engine that stalls without reporting a fill level gets the bare
 * ring, which is honest, rather than a `0%` that would read as no progress.
 */
@Composable
internal fun BufferingOverlay(
    percent: Int?,
    throughput: String?,
    cached: String?,
    modifier: Modifier = Modifier,
) {
    val spin = rememberInfiniteTransition(label = "bufferingSpin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bufferingAngle",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(64.dp)) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = RingTrack,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = HaloColors.Accent,
                    startAngle = angle,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
            }
            if (percent != null) {
                Text(
                    text = "$percent%",
                    style = monoStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                )
            }
        }

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(OverlayPillFill)
                .border(1.dp, PillBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Filling the buffer",
                color = HaloColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            // Naming the source here would put the debrid account on screen.
            val detail = listOfNotNull(throughput, cached).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = monoStyle(fontSize = 11.sp, color = HaloColors.TextDim),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * The brightness and volume readout, plus a wash over whichever half of the
 * screen is being dragged. The wash is the part that answers "why did the
 * brightness change when I meant the volume", which is the only mistake this
 * gesture pair invites.
 */
@Composable
internal fun GestureHudOverlay(hud: GestureHudValue, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(if (hud.kind == GestureHudKind.Brightness) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.5f)
                .background(
                    Brush.horizontalGradient(
                        if (hud.kind == GestureHudKind.Brightness) {
                            listOf(HaloColors.Accent.copy(alpha = 0.10f), Color.Transparent)
                        } else {
                            listOf(Color.Transparent, HaloColors.Accent.copy(alpha = 0.10f))
                        },
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(16.dp))
                .background(OverlayPillFill)
                .border(1.dp, HudBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (hud.kind == GestureHudKind.Brightness) HaloIcons.Brightness else HaloIcons.VolumeUp,
                contentDescription = if (hud.kind == GestureHudKind.Brightness) "Brightness" else "Volume",
                tint = HaloColors.Text,
                modifier = Modifier.size(26.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "${(hud.value * 100f).roundToInt()}%",
                    style = monoStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
                Box(
                    Modifier
                        .width(132.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(HaloRadius.Pill))
                        .background(MeterTrack),
                ) {
                    if (hud.value > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(hud.value)
                                .fillMaxHeight()
                                .background(HaloColors.Accent),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The locked state: a scrim that swallows every touch, and an unlock pill that
 * comes back on any tap. Tapping is deliberately the only thing that works,
 * because a pocket cannot tap deliberately.
 */
@Composable
internal fun LockedOverlay(
    metrics: PlayerMetrics,
    pillVisible: Boolean,
    onScrimTap: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LockedScrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onScrimTap,
            ),
    ) {
        if (pillVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = metrics.hPad)
                    .clip(RoundedCornerShape(HaloRadius.Pill))
                    .background(OverlayPillFill)
                    .border(1.dp, LockPillBorder, RoundedCornerShape(HaloRadius.Pill))
                    .clickable(role = Role.Button, onClick = onUnlock)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HaloIcons.Lock,
                    contentDescription = null,
                    tint = HaloColors.Text,
                    modifier = Modifier.size(19.dp),
                )
                Text(text = "Unlock", color = HaloColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The next episode, offered without covering the one still playing.
 *
 * The old player blacked the video out for this. Keeping the picture is what
 * makes ignoring the card a real option, which matters because the countdown
 * acting on its own is the default.
 */
@Composable
internal fun UpNextCard(
    metrics: PlayerMetrics,
    episodeTag: String,
    episodeName: String,
    episodeThumbnail: String?,
    secondsRemaining: Int,
    totalSeconds: Int,
    onCancel: () -> Unit,
    onPlayNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(end = metrics.hPad, bottom = metrics.chromeBottomPadding)
            .width(metrics.upNextCardWidth)
            .clip(RoundedCornerShape(18.dp))
            .background(UpNextCardFill)
            .border(1.dp, UpNextCardBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "UP NEXT",
                color = HaloColors.Accent,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.1.sp,
            )
            Text(
                text = "in $secondsRemaining s",
                style = monoStyle(fontSize = 11.sp, color = HaloColors.TextDim),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = 104.dp, height = 59.dp)
                    .clip(RoundedCornerShape(HaloRadius.Sm))
                    .placeholderStripes(),
            ) {
                HaloAsyncImage(
                    url = episodeThumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = { Box(Modifier.fillMaxSize().placeholderStripes()) },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episodeTag,
                    color = HaloColors.Text,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = episodeName,
                    color = HaloColors.TextDim,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .background(CountdownTrack),
        ) {
            val progress = if (totalSeconds <= 0) 0f else secondsRemaining.toFloat() / totalSeconds
            if (progress > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(HaloColors.Accent),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Cancel",
                color = HaloColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(HaloRadius.Md))
                    .background(GlassFill)
                    .border(1.dp, GlassBorder, RoundedCornerShape(HaloRadius.Md))
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(vertical = 10.dp),
            )
            Row(
                modifier = Modifier
                    .weight(1.2f)
                    .clip(RoundedCornerShape(HaloRadius.Md))
                    .background(HaloColors.Primary)
                    .clickable(role = Role.Button, onClick = onPlayNow)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HaloIcons.Play,
                    contentDescription = null,
                    tint = HaloColors.OnPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Play now",
                    color = HaloColors.OnPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * A source that will not play.
 *
 * The engine's own message is shown verbatim and nothing is added to it. HTTP
 * status codes and host names would name the debrid provider, and the viewer's
 * actual decision is only ever "try again or try another one", which the two
 * actions cover.
 */
@Composable
internal fun PlaybackErrorCard(
    engineMessage: String?,
    onRetry: () -> Unit,
    onPickAnotherSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ErrorBackground)
            // Keeps the card off the edges on a window narrower than the cap.
            .padding(horizontal = HaloSpacing.Lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // The cap has to come first. A width fraction fixes the incoming
            // minimum as well as the maximum, and widthIn cannot shrink below a
            // minimum it is handed — so capping after filling silently did
            // nothing and the card ran to 70% of a landscape screen.
            modifier = Modifier
                .widthIn(max = ErrorCardMaxWidth)
                .fillMaxWidth()
                .clip(RoundedCornerShape(HaloRadius.Lg))
                .background(ErrorCardFill)
                .border(1.dp, ErrorCardBorder, RoundedCornerShape(HaloRadius.Lg))
                .padding(20.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(HaloRadius.Pill))
                        .background(HaloColors.Danger),
                )
                Text(
                    text = "PLAYBACK FAILED",
                    color = HaloColors.Danger,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp,
                )
            }

            Text(
                text = "This source could not be played.",
                color = HaloColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                text = "Others for this episode are usually fine.",
                color = HaloColors.TextDim,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 5.dp),
            )

            if (!engineMessage.isNullOrBlank()) {
                Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(1.dp).background(ErrorRule))
                Text(
                    text = engineMessage,
                    style = monoStyle(fontSize = 10.5.sp, color = HaloPlayerColors.DiagnosticText),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Retry",
                    color = HaloColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(HaloRadius.Md))
                        .background(GlassFill)
                        .border(1.dp, GlassBorder, RoundedCornerShape(HaloRadius.Md))
                        .clickable(role = Role.Button, onClick = onRetry)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Text(
                    text = "Pick another source",
                    color = HaloColors.OnPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(HaloRadius.Md))
                        .background(HaloColors.Primary)
                        .clickable(role = Role.Button, onClick = onPickAnotherSource)
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}
