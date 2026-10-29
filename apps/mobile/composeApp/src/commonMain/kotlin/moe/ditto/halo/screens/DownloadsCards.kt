package moe.ditto.halo.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.longPressClickable
import moe.ditto.halo.ui.monoStyle

/**
 * The two things the Downloads screen is made of: a row in the queue for a
 * download that has not finished, and a card in the grid for a file that is on
 * the device.
 *
 * The split is by what a viewer can do with the thing: wait for it, or watch
 * it. That is why the row carries a bar and its controls, and the card carries
 * nothing but the art, what the file is and a way to play it.
 *
 * Nothing here animates unless bytes are moving. Motion on this screen means
 * exactly one thing, so a paused, queued or finished item is still.
 */

private const val WideAspect = 16f / 9f

private val QueueArtShape = RoundedCornerShape(HaloRadius.Sm)
private val CardArtShape = RoundedCornerShape(HaloRadius.Md)

/** The selection ring sits this far outside the art, and is this thick. */
private val SelectionOffset = 2.dp
private val SelectionStroke = 2.dp
private val SelectionInset = SelectionOffset + SelectionStroke
private val SelectionShape = RoundedCornerShape(HaloRadius.Md + SelectionInset)

/** Queue art widths, the design's on a tablet and a narrower one where a phone needs the room for words. */
internal val QueueArtWidth = 128.dp
internal val QueueArtWidthCompact = 80.dp

/** A queued row's art recedes: its turn has not come. */
private const val QueuedArtAlpha = 0.55f

private val ProgressHeight = 6.dp
private val WatchedHairline = 4.dp
private val ControlIcon = 18.dp
private val CardPlayDisc = 36.dp

private const val ProgressAnimationMs = 600
private const val SheenMs = 2_400
private const val CardEntryMs = 280
private val CardEntryRise = 12.dp

/** The sheen is a highlight travelling across the bar, not a band the width of it. */
private const val SheenWidthFraction = 0.34f

/** The gap the design puts between the parts of a moving mono line. */
private val MetaGap = 14.dp

/**
 * One download that has not finished: its art, what it is, how far it has got
 * and the controls that stop, resume, retry or throw it away.
 *
 * [compact] is the phone's shape. The art narrows, and the bar and its figures
 * run the whole width of the row under the art rather than squeezing in beside
 * it, where a phone would leave them a few characters wide.
 */
@Composable
internal fun DownloadQueueRow(
    entry: DownloadEntry,
    art: String?,
    compact: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    /** The long-press menu, which is where choosing another source lives. A tap does nothing; the buttons act. */
    menu: (() -> Unit)? = null,
) {
    val queued = entry.status == DownloadStatus.Queued
    Column(
        modifier = modifier
            .fillMaxWidth()
            .riseIn()
            .longPressClickable(onClick = {}, role = null, onLongPress = menu)
            .padding(vertical = HaloSpacing.Sm + 4.dp),
        verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm + 2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (compact) HaloSpacing.Sm + 4.dp else HaloSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaloAsyncImage(
                url = art,
                contentDescription = null,
                modifier = Modifier
                    .width(if (compact) QueueArtWidthCompact else QueueArtWidth)
                    .aspectRatio(WideAspect)
                    .clip(QueueArtShape)
                    .alpha(if (queued) QueuedArtAlpha else 1f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (queued) 6.dp else HaloSpacing.Sm),
            ) {
                QueueRowTitleLine(entry, compact)
                if (!compact) QueueRowProgress(entry)
            }
            QueueRowActions(entry, onPause, onResume, onRemove)
        }
        if (compact && !queued) QueueRowProgress(entry)
    }
}

/**
 * The tag, the title and the episode's own name. On one line where there is
 * width for it, which is the design's; on a phone the episode's name takes a
 * second line instead of being cut to nothing.
 */
@Composable
private fun QueueRowTitleLine(entry: DownloadEntry, compact: Boolean) {
    val titles = queueRowTitles(entry)
    val queued = entry.status == DownloadStatus.Queued
    val titleColor = if (queued) HaloColors.TextMeta else HaloColors.Text
    val subtitle: @Composable (Modifier) -> Unit = { modifier ->
        titles.subtitle?.let {
            Text(
                text = it,
                color = HaloColors.TextDim,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier,
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
            titles.tag?.let {
                Text(
                    text = it,
                    style = monoStyle(fontSize = 13.sp, color = HaloColors.TextDim),
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Text(
                text = titles.title,
                color = titleColor,
                fontSize = if (compact) 15.sp else 16.sp,
                fontWeight = FontWeight.SemiBold,
                // A phone gives a long title a second line rather than cutting
                // it to a few characters beside the row's two controls.
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                // Unweighted, so the title takes what it needs first and the
                // episode's name gets what is left, down to nothing.
                modifier = Modifier.alignByBaseline(),
            )
            if (!compact) subtitle(Modifier.alignByBaseline().weight(1f))
        }
        if (compact) subtitle(Modifier)
        queueRowCaption(entry)?.let { caption ->
            Text(
                text = caption.uppercase(),
                style = kickerStyle(HaloColors.TextFaint),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The bar, the figures under it, and for a failure what failed. Queued rows have none of these. */
@Composable
private fun QueueRowProgress(entry: DownloadEntry) {
    if (entry.status == DownloadStatus.Queued) return
    Column(verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
        TransferProgressBar(entry)
        val meta = queueRowMeta(entry)
        if (meta.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(MetaGap)) {
                meta.forEachIndexed { index, part ->
                    Text(
                        text = part,
                        style = monoStyle(
                            fontSize = 12.sp,
                            color = when {
                                index > 0 -> HaloColors.TextDim
                                entry.status == DownloadStatus.Failed -> HaloColors.Danger
                                else -> HaloColors.TextMeta
                            },
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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

/**
 * The row's controls. The first stops a running or waiting download, resumes
 * a paused one or retries a failed one; the second throws it away, through the
 * confirmation sheet.
 */
@Composable
private fun QueueRowActions(
    entry: DownloadEntry,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
        val resumable = entry.status == DownloadStatus.Paused || entry.status == DownloadStatus.Failed
        // Expired or unreadable protected requests cannot resume by design. Do
        // not present a control whose handler must reject the tap; the failure
        // text directs the viewer to Sources, which the long-press menu opens.
        if (entry.failure?.requiresNewSource != true) {
            QueueIconButton(
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
        QueueIconButton(
            icon = HaloIcons.Trash,
            description = "Cancel download",
            onClick = onRemove,
            destructive = true,
        )
    }
}

/**
 * A square control in the queue. [destructive] tints only the glyph: the
 * colour is a warning, and the confirmation sheet behind it is the safeguard.
 */
@Composable
private fun QueueIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(ControlTarget)
            .clip(ControlShape)
            .background(HaloColors.SurfaceHigh)
            .border(1.dp, HaloColors.Border, ControlShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (destructive) HaloColors.Danger else HaloColors.Text,
            modifier = Modifier.size(ControlIcon),
        )
    }
}

/**
 * One file on the device, as a cell of the grid.
 *
 * Where a detail pane is beside the grid, a tap selects and the white disc on
 * the selected card plays. Without one nothing is selected, so a tap plays and
 * every card carries the disc, for the reason a poster's play button exists.
 *
 * [onRemove] puts a delete control beside the title, for the layout with no
 * detail pane to hold one.
 */
@Composable
internal fun DownloadReadyCard(
    entry: DownloadEntry,
    art: String?,
    watched: Float?,
    selected: Boolean,
    showPlay: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    menu: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.longPressClickable(onClick = onClick, onLongPress = menu),
        verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm + 2.dp - SelectionInset),
    ) {
        // The ring is drawn in a margin every card keeps, so selecting one
        // never moves its art.
        Box(
            Modifier
                .fillMaxWidth()
                .border(SelectionStroke, if (selected) HaloColors.Accent else Color.Transparent, SelectionShape)
                .padding(SelectionInset),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(WideAspect)
                    .clip(CardArtShape)
                    .background(HaloColors.SurfaceHigh),
            ) {
                HaloAsyncImage(url = art, contentDescription = null, modifier = Modifier.fillMaxSize())
                downloadQualityBadge(entry)?.let { badge ->
                    ArtBadge(
                        text = badge.label,
                        background = if (badge.gold) HaloColors.Gold else HaloColors.OverlayPill,
                        color = if (badge.gold) HaloColors.Surface else HaloColors.Text,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
                entry.media.episodeTag?.let { tag ->
                    ArtBadge(
                        text = tag,
                        background = HaloColors.OverlayPill,
                        color = HaloColors.Text,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
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
                if (showPlay) {
                    // The disc is the design's 36dp; the target around it is 44.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(HaloSpacing.Xs)
                            .size(ControlTarget)
                            .clickable(role = Role.Button, onClick = onPlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(CardPlayDisc)
                                .clip(RoundedCornerShape(HaloRadius.Pill))
                                .background(HaloColors.Text.copy(alpha = 0.92f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = HaloIcons.Play,
                                contentDescription = "Play this download",
                                tint = HaloColors.Background,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(start = SelectionInset + 2.dp, end = SelectionInset),
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = downloadCardTitle(entry),
                    color = HaloColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = downloadCardMeta(entry),
                    style = monoStyle(fontSize = 12.sp, color = HaloColors.TextDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            onRemove?.let { remove ->
                Box(
                    modifier = Modifier
                        .size(ControlTarget)
                        .clickable(role = Role.Button, onClick = remove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HaloIcons.Trash,
                        contentDescription = "Delete download",
                        tint = HaloColors.Danger,
                        modifier = Modifier.size(ControlIcon),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtBadge(text: String, background: Color, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = monoStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, letterSpacing = 0.44.sp),
        maxLines = 1,
        modifier = modifier
            .padding(HaloSpacing.Sm)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * The bar, drawn rather than composed: the track, the fill and the travelling
 * sheen are three shapes in one rounded clip, and nesting them as boxes would
 * cost three layout nodes to say the same thing.
 *
 * The track is drawn whether or not there is a fraction to put in it. A
 * transfer whose size the source never declared stands at nothing, and a row
 * that simply omitted the bar would read as a different kind of thing from the
 * row above it rather than as the same thing earlier on.
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
    val track = HaloColors.Border
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
 * A queue row arrives by rising into place rather than appearing. Only the
 * queue uses it: it is short and always at the top, so it reads as the queue
 * moving rather than as the whole screen animating on every scroll.
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

/** The section's caption and its count, over the grid it introduces. */
@Composable
internal fun DownloadsSectionCaption(label: String, count: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = label.uppercase(), style = kickerStyle(), modifier = Modifier.alignByBaseline())
        Text(
            text = count.uppercase(),
            style = kickerStyle(HaloColors.TextFaint),
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** Gap between grid cells, both ways, the design's 16dp. */
internal val ReadyGridGap: Dp = HaloSpacing.Md

/** The narrowest a grid cell gets before the grid drops a column. */
internal val ReadyCardMinWidth: Dp = 260.dp
