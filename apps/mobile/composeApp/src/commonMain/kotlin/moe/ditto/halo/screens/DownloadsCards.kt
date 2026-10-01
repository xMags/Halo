package moe.ditto.halo.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloDimensions
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.monoStyle

/**
 * The two things the Downloads list is made of: a card for a transfer that is
 * still arriving, and a row for a file that is on the device.
 *
 * They share one glass treatment and one set of controls, at two scales. The
 * split is by what a viewer can do with the thing — wait for it, or watch it —
 * which is why the card carries a rate and a bar and the row carries a play
 * button.
 *
 * Nothing here animates unless bytes are moving. Motion on this screen means
 * exactly one thing, so a paused, queued or finished item is still.
 */

// The design's glass card: lit along the top edge, falling away towards the
// bottom. Both stops are the app's own glass, the lower one thinned.
private val CardFill = listOf(HaloColors.Glass, HaloColors.Primary.copy(alpha = 0.03f))
private val SelectedCardFill = listOf(
    HaloColors.Accent.copy(alpha = 0.18f),
    HaloColors.Accent.copy(alpha = 0.07f),
)
private val SelectedCardBorder = HaloColors.Accent.copy(alpha = 0.5f)

private val CardShape = RoundedCornerShape(HaloRadius.Xl)
private val PosterShape = RoundedCornerShape(HaloRadius.Md)

/** Gap between a poster and the words beside it, and between the words themselves. */
private val CardGap = HaloSpacing.Sm + HaloSpacing.Xs
private val CardPadding = HaloSpacing.Sm + 5.dp

private val ProgressHeight = 6.dp
private val WatchedHairline = 4.dp

/** Visual size of a circular control, inside a hit area big enough for a thumb. */
private val ControlSize = 36.dp
private val ControlTarget = 44.dp
private val ControlIcon = 17.dp

/**
 * Poster widths, phone then tablet. A transfer's art stretches to the card's
 * own height and never below [TransferPosterMinHeight]; a finished row's holds
 * the 2:3 ratio, since a row is short enough for a poster to be one.
 */
internal val TransferPosterWidth = 104.dp
internal val TransferPosterWidthTablet = 118.dp
internal val ReadyPosterWidth = 52.dp
internal val ReadyPosterWidthTablet = 62.dp
private val TransferPosterMinHeight = 78.dp

private const val ProgressAnimationMs = 600
private const val SheenMs = 2_400
private const val BreatheMs = 1_800
private const val CardEntryMs = 280
private val CardEntryRise = 12.dp

/** The sheen is a highlight travelling across the bar, not a band the width of it. */
private const val SheenWidthFraction = 0.34f

/**
 * One transfer that has not finished: what it is, how fast it is arriving, and
 * the two controls that stop it or throw it away.
 */
@Composable
internal fun DownloadTransferCard(
    entry: DownloadEntry,
    poster: String?,
    posterWidth: Dp,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The card is as tall as its words, and the art stretches to meet them.
    // Sized the other way round — art holding its own 2:3 — a queued card with
    // two lines in it would stand as tall as a poster.
    //
    // So the art is laid out over the card rather than beside it, in a column
    // the row leaves empty for it. `matchParentSize` is what makes that work:
    // the image takes the card's height without being asked how tall it would
    // like to be, and the card's height stays a question about the text. An
    // intrinsic-height row would answer it from the text's *unwrapped* width
    // instead, and clip the meta line the moment it needed a second line.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .riseIn()
            .clip(CardShape)
            .background(Brush.linearGradient(CardFill))
            .border(1.dp, HaloColors.GlassBorder, CardShape)
            .padding(CardPadding),
    ) {
        Box(Modifier.matchParentSize()) {
            HaloAsyncImage(
                url = poster,
                contentDescription = null,
                modifier = Modifier
                    .width(posterWidth)
                    .fillMaxHeight()
                    .clip(PosterShape)
                    .border(1.dp, HaloColors.Hairline, PosterShape),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = TransferPosterMinHeight),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            Spacer(Modifier.width(posterWidth))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm - 1.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Xs)) {
                    RowTitles(entry, Modifier.weight(1f))
                    val resumable =
                        entry.status == DownloadStatus.Paused || entry.status == DownloadStatus.Failed
                    // Expired or unreadable protected requests cannot resume by
                    // design. Do not present a control whose handler must reject
                    // the tap; the failure text directs the viewer to Sources.
                    if (entry.failure?.requiresNewSource != true) {
                        CircleAction(
                            icon = when {
                                entry.status == DownloadStatus.Failed -> HaloIcons.Refresh
                                resumable -> HaloIcons.Play
                                else -> HaloIcons.Pause
                            },
                            description = when {
                                entry.status == DownloadStatus.Failed -> "Retry download"
                                resumable -> "Resume download"
                                else -> "Pause download"
                            },
                            onClick = if (resumable) onResume else onPause,
                        )
                    }
                    CircleAction(
                        icon = HaloIcons.Trash,
                        description = "Delete download",
                        onClick = onRemove,
                        destructive = true,
                    )
                }
                TransferProgressBar(entry)
                TransferMetaLine(entry)
                entry.failureMessage?.takeIf { entry.status == DownloadStatus.Failed }?.let { message ->
                    Text(
                        text = message,
                        style = HaloType.Caption.copy(color = HaloColors.Danger),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * One file that is on the device. The whole row plays it — a finished download
 * is a thing to watch, and the row is what a thumb lands on — with the button
 * there for the same reason the play button on a poster is.
 *
 * On a tablet the row is also the detail pane's selection, so tapping it does
 * both: [onClick] selects, [onPlay] plays.
 */
@Composable
internal fun DownloadReadyRow(
    entry: DownloadEntry,
    poster: String?,
    posterWidth: Dp,
    watched: Float?,
    selected: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.linearGradient(if (selected) SelectedCardFill else CardFill))
            .border(1.dp, if (selected) SelectedCardBorder else HaloColors.GlassBorder, CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(CardPadding),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(posterWidth)
                .aspectRatio(1f / HaloDimensions.PosterRatio)
                .clip(PosterShape),
        ) {
            HaloAsyncImage(
                url = poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().border(1.dp, HaloColors.Hairline, PosterShape),
            )
            // Where the viewer got to, across the foot of the art: the same
            // reading a poster gives everywhere else in the app.
            if (watched != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(WatchedHairline)
                        .background(HaloColors.Primary.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(watched)
                            .fillMaxSize()
                            .background(HaloColors.Accent),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RowTitles(entry)
            val facts = listOfNotNull(
                downloadQualityLabel(entry).takeIf { it.isNotEmpty() },
                downloadSizeLabel(entry).takeIf { it.isNotEmpty() },
            )
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString(FactSeparator),
                    style = metaStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PlayButton(label = "Play", onClick = onPlay)
        CircleAction(
            icon = HaloIcons.Trash,
            description = "Delete download",
            onClick = onRemove,
            tint = HaloColors.TextDim,
        )
    }
}

/** Title over subtitle, both single-line: the shape every row on this screen holds. */
@Composable
private fun RowTitles(entry: DownloadEntry, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = downloadRowTitle(entry),
            style = HaloType.Callout,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        downloadRowSubtitle(entry)?.let { subtitle ->
            Text(
                text = subtitle,
                style = HaloType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The gap the design puts between the parts of a moving mono line. */
private const val MetaGap = "   "

/** Facts about a finished file are dotted rather than spaced: they do not tick. */
private const val FactSeparator = " · "

@Composable
private fun metaStyle() = monoStyle(fontSize = 11.5.sp, color = HaloColors.TextDim)

/**
 * The line under the bar: a breathing dot, the rate, the bytes, the time left
 * and what the file is.
 *
 * One text rather than a row of them, so a narrow phone wraps it to a second
 * line instead of truncating a figure. Only the dot is a composable of its own,
 * because it is the one part that moves.
 */
@Composable
private fun TransferMetaLine(entry: DownloadEntry) {
    val live = isTransferLive(entry)
    val rateColor = when {
        entry.status == DownloadStatus.Failed -> HaloColors.Danger
        live && entry.bytesPerSecond > 0 -> HaloColors.Success
        else -> HaloColors.TextDim
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color = rateColor, breathing = live)
        // Three lines, not two: at 390dp a rate, both byte counts, an estimate
        // and a codec run past two, and a figure that has been cut in half is
        // worse than a line that has grown. The extra line costs nothing when
        // it is not needed.
        Text(
            text = metaLine(entry, rateColor),
            style = metaStyle(),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun metaLine(entry: DownloadEntry, rateColor: Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = rateColor, fontWeight = FontWeight.Bold)) {
        append(downloadRateLabel(entry))
    }
    listOfNotNull(downloadBytesLabel(entry), downloadEtaLabel(entry)).forEach { part ->
        append(MetaGap)
        append(part)
    }
    downloadQualityLabel(entry).takeIf { it.isNotEmpty() }?.let { quality ->
        append(MetaGap)
        // A shade under the rest of the line: it says what the file is, which
        // does not change while someone watches the figures that do.
        withStyle(SpanStyle(color = HaloColors.TextDim.copy(alpha = 0.7f))) { append(quality) }
    }
}

/** Alive while bytes arrive, still otherwise. */
@Composable
private fun StatusDot(color: Color, breathing: Boolean) {
    val alpha = if (breathing) {
        val transition = rememberInfiniteTransition(label = "status-dot")
        transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = BreatheMs / 2, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status-dot-alpha",
        ).value
    } else {
        0.4f
    }
    Box(
        Modifier
            .size(6.dp)
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(color.copy(alpha = alpha)),
    )
}

/**
 * The bar, drawn rather than composed: the track, the fill and the travelling
 * sheen are three shapes in one rounded clip, and nesting them as boxes would
 * cost three layout nodes to say the same thing.
 *
 * The track is drawn whether or not there is a fraction to put in it. A queued
 * transfer, and one whose size the source never declared, both stand at nothing
 * — and a card that simply omits the bar reads as a different kind of thing
 * from the card above it rather than as the same thing earlier on.
 */
@Composable
private fun TransferProgressBar(entry: DownloadEntry) {
    val live = isTransferLive(entry)
    val fraction = entry.fraction

    val width by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(durationMillis = ProgressAnimationMs, easing = FastOutSlowInEasing),
        label = "download-progress",
    )
    val sheen = if (live) {
        val transition = rememberInfiniteTransition(label = "sheen")
        transition.animateFloat(
            initialValue = -1f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = SheenMs, easing = LinearEasing),
            ),
            label = "sheen-offset",
        ).value
    } else {
        null
    }
    val fill = when {
        live -> Brush.horizontalGradient(listOf(HaloColors.Accent, HaloColors.AccentLight))
        entry.status == DownloadStatus.Failed -> Brush.horizontalGradient(listOf(HaloColors.Danger, HaloColors.Danger))
        // A stopped transfer keeps its progress but loses its colour: the bar
        // still says how far it got, and nothing about it says it is moving.
        else -> Brush.horizontalGradient(List(2) { HaloColors.Primary.copy(alpha = 0.45f) })
    }
    val track = HaloColors.Primary.copy(alpha = 0.09f)
    val sheenBrush = Brush.horizontalGradient(
        listOf(Color.Transparent, HaloColors.Primary.copy(alpha = 0.55f), Color.Transparent),
    )

    Canvas(Modifier.fillMaxWidth().height(ProgressHeight)) {
        val radius = CornerRadius(size.height / 2f)
        val clip = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    cornerRadius = radius,
                ),
            )
        }
        clipPath(clip) {
            drawRect(color = track)
            val filled = size.width * width
            if (filled > 0f) drawRect(brush = fill, size = Size(filled, size.height))
            if (sheen != null) {
                val sheenWidth = size.width * SheenWidthFraction
                drawRect(
                    brush = sheenBrush,
                    topLeft = Offset(sheen * sheenWidth, 0f),
                    size = Size(sheenWidth, size.height),
                )
            }
        }
    }
}

/**
 * A card arrives by rising into place rather than appearing. Only transfers use
 * it: they are few and always at the top, so it reads as the queue moving
 * rather than as the whole list animating on every scroll.
 */
@Composable
private fun Modifier.riseIn(): Modifier {
    var shown by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = CardEntryMs, easing = FastOutSlowInEasing),
        label = "card-entry",
    )
    LaunchedEffect(Unit) { shown = true }
    val rise = with(LocalDensity.current) { CardEntryRise.toPx() }
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * rise
    }
}

/**
 * A circular control. The disc is the design's 36dp; the hit area around it is
 * bigger, because a thumb is not 36dp wide.
 *
 * [destructive] is the only red control on the screen and reads as such. It
 * still routes through the confirmation sheet — the colour is a warning, not
 * the confirmation itself.
 */
@Composable
internal fun CircleAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = HaloColors.Text,
    destructive: Boolean = false,
) {
    val fill = if (destructive) {
        listOf(HaloColors.Danger.copy(alpha = 0.2f), HaloColors.Danger.copy(alpha = 0.08f))
    } else {
        listOf(HaloColors.Primary.copy(alpha = 0.12f), HaloColors.Primary.copy(alpha = 0.05f))
    }
    val border = if (destructive) HaloColors.Danger.copy(alpha = 0.38f) else HaloColors.GlassBorder
    Box(
        modifier = modifier
            .size(ControlTarget)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(ControlSize)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .background(Brush.verticalGradient(fill))
                .border(1.dp, border, RoundedCornerShape(HaloRadius.Pill)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (destructive) HaloColors.Danger else tint,
                modifier = Modifier.size(ControlIcon),
            )
        }
    }
}

private val PlayButtonShape = RoundedCornerShape(HaloRadius.Md - 1.dp)
private val PlayButtonFill = listOf(HaloColors.Primary, HaloColors.PrimaryShade)

/**
 * The one white control in the list. White because it is the thing the screen
 * exists for: everything else here manages a file, and this one watches it.
 */
@Composable
internal fun PlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(PlayButtonShape)
            .background(Brush.verticalGradient(PlayButtonFill))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(
                horizontal = if (fillWidth) HaloSpacing.Md else HaloSpacing.Sm + 5.dp,
                vertical = if (fillWidth) 13.dp else HaloSpacing.Sm + 1.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HaloIcons.Play,
            contentDescription = null,
            tint = HaloColors.OnPrimary,
            modifier = Modifier.size(if (fillWidth) 18.dp else 16.dp),
        )
        Text(
            text = label,
            color = HaloColors.OnPrimary,
            fontSize = if (fillWidth) 15.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** A section's label and its count, over the list it introduces. */
@Composable
internal fun DownloadsSectionHeader(label: String, count: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text = label, style = HaloType.Overline.copy(color = HaloColors.TextMeta))
        Text(text = count, style = monoStyle(fontSize = 11.sp, color = HaloColors.TextDim))
    }
}

/** The screen's glass surface: one treatment for every panel and card on it. */
internal fun Modifier.glassPanel(): Modifier = this
    .clip(CardShape)
    .background(Brush.linearGradient(CardFill))
    .border(1.dp, HaloColors.GlassBorder, CardShape)
