package moe.ditto.halo.screens.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloPlayerColors
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.monoStyle

// The chrome that floats over the video: scrims, top bar, centre transport and
// bottom bar.
//
// None of it is blurred, and that is not an omission. The design asks for
// backdrop blur behind every one of these surfaces, and the module has a Haze
// setup that delivers exactly that elsewhere in the app. It cannot work here:
// Haze samples the Compose content behind a surface, and the video is not
// Compose content. It is painted by the platform into a native view that sits
// below the whole composition, so there is nothing in the Compose tree for a
// blur to read; what a blurred surface would sample here is the transparent
// space the video shows through, which comes out clear. The translucent fills
// the design specifies are therefore used on their own, which is why they are
// as dark and as opaque as they are.

// White hairlines, in the four weights the design uses. They are local because
// they are not a palette: each one is chosen against the fill it borders, and
// naming them globally would invite reuse in places that fill differently.
private val BadgeBorder = Color.White.copy(alpha = 0.16f)
private val ButtonBorder = Color.White.copy(alpha = 0.10f)
private val SeekButtonBorder = Color.White.copy(alpha = 0.18f)
private val ChromeBorder = Color.White.copy(alpha = 0.18f)
private val PlayButtonBorder = Color.White.copy(alpha = 0.22f)

private val BadgeFill = Color(red = 9f / 255f, green = 11f / 255f, blue = 16f / 255f, alpha = 0.58f)
private val TrackFill = Color.White.copy(alpha = 0.16f)
private val BufferedFill = Color.White.copy(alpha = 0.30f)
private val DurationText = Color.White.copy(alpha = 0.55f)
private val PreviewBorder = Color.White.copy(alpha = 0.14f)

private val ScrimTopHeight = 128.dp
private val ScrimBottomHeight = 186.dp

/** Non-interactive gradients that keep chrome legible over a bright frame. */
@Composable
internal fun PlayerScrims(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(ScrimTopHeight)
                .background(
                    Brush.verticalGradient(
                        listOf(HaloPlayerColors.ScrimTop, Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(ScrimBottomHeight)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, HaloPlayerColors.ScrimBottom),
                    ),
                ),
        )
    }
}

@Composable
internal fun PlayerTopBar(
    metrics: PlayerMetrics,
    showTitle: String,
    episodeTag: String?,
    episodeName: String?,
    streamBadges: List<String>,
    locked: Boolean,
    onBack: () -> Unit,
    /** Null on platforms with no native picture-in-picture; the button is then absent. */
    onPictureInPicture: (() -> Unit)?,
    onToggleFit: () -> Unit,
    onToggleLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = metrics.hPad, end = metrics.hPad, top = metrics.chromeTopPadding),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(
            icon = HaloIcons.ChevronLeft,
            contentDescription = "Back",
            size = 38.dp,
            iconSize = 22.dp,
            fill = HaloPlayerColors.CircleButtonFill,
            border = ButtonBorder,
            onClick = onBack,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = showTitle,
                color = HaloColors.Text,
                fontSize = metrics.titleSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Films have no second line; the row disappears rather than
            // holding empty space that shifts the title off centre.
            if (episodeTag != null || episodeName != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (episodeTag != null) {
                        Text(
                            text = episodeTag,
                            color = HaloColors.Accent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.4.sp,
                            maxLines = 1,
                        )
                    }
                    if (episodeName != null) {
                        Text(
                            text = episodeName,
                            color = HaloColors.TextDim,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            streamBadges.forEach { badge -> StreamBadge(badge) }
        }

        UtilityPill(
            locked = locked,
            onPictureInPicture = onPictureInPicture,
            onToggleFit = onToggleFit,
            onToggleLock = onToggleLock,
        )
    }
}

/** Resolution and codec. Never the source's provider or account. */
@Composable
private fun StreamBadge(text: String) {
    Text(
        text = text,
        style = monoStyle(fontSize = 10.sp, color = HaloColors.TextMeta, letterSpacing = 0.4.sp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BadgeFill)
            .border(1.dp, BadgeBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/**
 * Picture in picture, fit mode and lock, and nothing else. The old player's pill
 * held six controls; everything that left it is now a labelled chip on the
 * bottom bar, where its current value is readable without opening anything.
 *
 * The pill shrinks to two buttons where the platform has no native PiP, rather
 * than showing a disabled one: a control that is never available on this device
 * is not a state the viewer can do anything with.
 */
@Composable
private fun UtilityPill(
    locked: Boolean,
    onPictureInPicture: (() -> Unit)?,
    onToggleFit: () -> Unit,
    onToggleLock: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(HaloPlayerColors.UtilityPillFill)
            .border(1.dp, ChromeBorder, RoundedCornerShape(HaloRadius.Pill))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onPictureInPicture != null) {
            PillButton(HaloIcons.PictureInPicture, "Picture in picture", onPictureInPicture)
        }
        PillButton(HaloIcons.FitScreen, "Fit mode", onToggleFit)
        PillButton(
            icon = if (locked) HaloIcons.Lock else HaloIcons.LockOpen,
            contentDescription = if (locked) "Unlock controls" else "Lock controls",
            onClick = onToggleLock,
        )
    }
}

@Composable
private fun PillButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = HaloColors.Text,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CircleButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    fill: Color,
    border: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(HaloRadius.Pill))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = HaloColors.Text,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Seek by ten in both directions, either side of play/pause.
 *
 * The old player offered −10 and +30. A symmetric pair is a better target pair
 * under a thumb, and a thirty-second jump is the scrubber's job.
 */
@Composable
internal fun PlayerCentreControls(
    metrics: PlayerMetrics,
    paused: Boolean,
    onSeekBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(metrics.centreGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeekButton(HaloIcons.Replay10, "Back ten seconds", onSeekBack)

        Box(
            modifier = Modifier
                .size(metrics.playButtonSize)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .background(HaloPlayerColors.PlayButtonFill)
                .border(1.dp, PlayButtonBorder, RoundedCornerShape(HaloRadius.Pill))
                .clickable(role = Role.Button, onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (paused) HaloIcons.Play else HaloIcons.Pause,
                contentDescription = if (paused) "Play" else "Pause",
                tint = HaloColors.Text,
                modifier = Modifier.size(metrics.playIconSize),
            )
        }

        SeekButton(HaloIcons.Forward10, "Forward ten seconds", onSeekForward)
    }
}

/**
 * The glyph already carries the "10", but it is small and sits inside a circular
 * arrow; the design repeats it as text underneath so the amount is readable at a
 * glance rather than deciphered.
 */
@Composable
private fun SeekButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(HaloPlayerColors.SeekButtonFill)
            .border(1.dp, SeekButtonBorder, RoundedCornerShape(HaloRadius.Pill))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = HaloColors.Text,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "10",
                style = monoStyle(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold),
                modifier = Modifier.offset(y = (-1).dp),
            )
        }
    }
}

/** One labelled playback choice: what it is, and what it is currently set to. */
internal data class PlayerChip(
    val kicker: String,
    val value: String,
    val badge: String? = null,
    val active: Boolean,
    val onClick: () -> Unit,
    val width: Dp? = null,
)

@Composable
internal fun PlayerBottomBar(
    metrics: PlayerMetrics,
    chips: List<PlayerChip>,
    positionSeconds: Double,
    durationSeconds: Double?,
    bufferedFraction: Float,
    scrubFraction: Float?,
    /** The picture behind the scrub card; null keeps its placeholder. */
    scrubPreviewFrame: ImageBitmap? = null,
    onScrubStart: (Float) -> Unit,
    onScrubMove: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onScrubCancel: () -> Unit,
    onSeekToFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = metrics.hPad, end = metrics.hPad, bottom = metrics.chromeBottomPadding),
        verticalArrangement = Arrangement.spacedBy(metrics.bottomBarGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            chips.forEach { chip -> StateChip(chip, metrics.chipValueSize) }
            Text(
                text = formatRemaining(positionSeconds, durationSeconds).orEmpty(),
                style = monoStyle(fontSize = 11.5.sp, color = HaloColors.TextDim),
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(bottom = 3.dp),
            )
        }

        TransportRow(
            metrics = metrics,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            bufferedFraction = bufferedFraction,
            scrubFraction = scrubFraction,
            scrubPreviewFrame = scrubPreviewFrame,
            onScrubStart = onScrubStart,
            onScrubMove = onScrubMove,
            onScrubEnd = onScrubEnd,
            onScrubCancel = onScrubCancel,
            onSeekToFraction = onSeekToFraction,
        )
    }
}

/**
 * The redesign's load-bearing idea: every playback choice states its current
 * value on the bar, so nothing has to be opened to find out what is selected.
 */
@Composable
private fun StateChip(chip: PlayerChip, valueSize: TextUnit) {
    val shape = RoundedCornerShape(HaloRadius.Md)
    val sizeModifier = chip.width?.let { Modifier.width(it) } ?: Modifier
    Column(
        modifier = sizeModifier
            .clip(shape)
            .background(HaloPlayerColors.ChipFill)
            .background(if (chip.active) HaloPlayerColors.ChipActiveFill else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (chip.active) HaloPlayerColors.ChipActiveBorder else ChromeBorder,
                shape = shape,
            )
            .clickable(role = Role.Button, onClick = chip.onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ChipHeader(chip)
        Text(
            text = chip.value,
            color = HaloColors.Text,
            fontSize = valueSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChipHeader(chip: PlayerChip) {
    val color = if (chip.active) HaloPlayerColors.ChipActiveLabel else HaloColors.TextDim
    val badge = chip.badge
    if (badge == null) {
        Text(
            text = chip.kicker,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.9.sp,
            maxLines = 1,
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chip.kicker,
            color = color,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.45.sp,
            maxLines = 1,
        )
        Text(
            text = badge,
            color = color,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

private val TransportTrackHeight = 6.dp
private val TransportHitHeight = 34.dp
private val ThumbSize = 14.dp
private val ThumbScrubbingSize = 18.dp
internal val SubtitleChipWidth = 92.dp

// Not private: the frames behind the card are decoded at exactly this size, and
// the screen that asks for them has to know it.
internal val ScrubPreviewWidth = 176.dp
internal val ScrubPreviewHeight = 99.dp

@Composable
private fun TransportRow(
    metrics: PlayerMetrics,
    positionSeconds: Double,
    durationSeconds: Double?,
    bufferedFraction: Float,
    scrubFraction: Float?,
    scrubPreviewFrame: ImageBitmap?,
    onScrubStart: (Float) -> Unit,
    onScrubMove: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onScrubCancel: () -> Unit,
    onSeekToFraction: (Float) -> Unit,
) {
    // The thumb and the played fill follow the finger during a drag, while the
    // elapsed readout keeps showing where playback actually is. The prototype
    // does the opposite, because a mouse can hover a preview without moving
    // anything; a finger cannot, so the thing being dragged has to move with it.
    // Keeping the elapsed field on the engine means the two numbers on screen
    // are "where I am" and "where I would land" rather than the same number.
    val played = scrubFraction ?: progressFraction(positionSeconds, durationSeconds)
    val buffered = bufferedFraction.coerceIn(0f, 1f).coerceAtLeast(played)
    val thumbSize by animateDpAsState(
        targetValue = if (scrubFraction != null) ThumbScrubbingSize else ThumbSize,
        animationSpec = tween(durationMillis = 120),
        label = "transportThumb",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val elapsed = formatTimecode(positionSeconds)
        Text(
            text = elapsed,
            style = monoStyle(fontSize = metrics.transportTextSize),
            modifier = Modifier
                .widthIn(min = 52.dp)
                .semantics { contentDescription = "Elapsed $elapsed" },
        )

        var trackWidthPx by remember { mutableIntStateOf(0) }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(TransportHitHeight)
                .onSizeChanged { trackWidthPx = it.width }
                // Tap and drag are separate detectors on purpose. A tap that
                // never exceeds touch slop must seek without ever opening a
                // scrub, and a drag must not commit the position it started at.
                .pointerInput(trackWidthPx) {
                    if (trackWidthPx <= 0) return@pointerInput
                    detectTapGestures { offset -> onSeekToFraction(offset.x / trackWidthPx) }
                }
                .pointerInput(trackWidthPx) {
                    if (trackWidthPx <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> onScrubStart(offset.x / trackWidthPx) },
                        onDragEnd = onScrubEnd,
                        onDragCancel = onScrubCancel,
                        onHorizontalDrag = { change, _ ->
                            onScrubMove(change.position.x / trackWidthPx)
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TransportTrackHeight)
                    .clip(RoundedCornerShape(HaloRadius.Pill))
                    .background(TrackFill),
            ) {
                // Guarded rather than clamped: a zero-fraction fill is a
                // rounded box with no width, which still paints its own caps.
                if (buffered > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(buffered)
                            .height(TransportTrackHeight)
                            .background(BufferedFill),
                    )
                }
                if (played > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(played)
                            .height(TransportTrackHeight)
                            .clip(RoundedCornerShape(HaloRadius.Pill))
                            .background(Color.White),
                    )
                }
            }

            Box(
                Modifier
                    .offset {
                        IntOffset(
                            x = (played * trackWidthPx - thumbSize.toPx() / 2f).roundToInt(),
                            y = 0,
                        )
                    }
                    .size(thumbSize)
                    .clip(RoundedCornerShape(HaloRadius.Pill))
                    .background(Color.White),
            )

            if (scrubFraction != null) {
                ScrubPreview(
                    fraction = scrubFraction,
                    trackWidthPx = trackWidthPx,
                    targetSeconds = scrubTarget(scrubFraction, durationSeconds),
                    frame = scrubPreviewFrame,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }

        Text(
            text = formatTimecode(durationSeconds ?: 0.0),
            style = monoStyle(fontSize = metrics.transportTextSize, color = DurationText),
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 52.dp),
        )
    }
}

private fun scrubTarget(fraction: Float, durationSeconds: Double?): Double {
    val duration = durationSeconds ?: return 0.0
    if (!duration.isFinite() || duration <= 0.0) return 0.0
    return duration * fraction
}

/**
 * The card that floats over the track while scrubbing.
 *
 * [frame] is the picture at the position being aimed at, when one has been
 * decoded; the card keeps its placeholder until then and for sources whose
 * frames cannot be read at all. Frames swap without a crossfade: during a drag
 * they arrive one after another, and fading between them would leave the card
 * showing a blend of two moments rather than either of them.
 *
 * The timecode is drawn whether or not there is a picture under it, because the
 * timecode is what a scrub is actually aimed with.
 */
@Composable
private fun ScrubPreview(
    fraction: Float,
    trackWidthPx: Int,
    targetSeconds: Double,
    frame: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = (fraction * trackWidthPx - ScrubPreviewWidth.toPx() / 2f).roundToInt(),
                    y = -30.dp.roundToPx(),
                )
            }
            // Required, not plain size: the card is a child of the 34dp-tall
            // hit area for the track, so ordinary size constraints would clamp
            // it to that height instead of letting it stand above the bar.
            .requiredSize(width = ScrubPreviewWidth, height = ScrubPreviewHeight)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, PreviewBorder, RoundedCornerShape(10.dp)),
    ) {
        if (frame == null) {
            Box(Modifier.fillMaxSize().placeholderStripes())
        } else {
            // Fit rather than crop, over black. Cropping a scope-ratio film to
            // the card's 16:9 throws away the sides of a frame whose only job
            // is to be recognised, and a letterboxed picture inside a bordered
            // card reads as the shape of the film rather than as a mistake.
            Image(
                bitmap = frame,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            text = formatTimecode(targetSeconds),
            style = monoStyle(fontSize = 10.sp, color = Color(0xFFE7EAF1)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                )
                .padding(horizontal = 7.dp, vertical = 5.dp),
        )
    }
}
