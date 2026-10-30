package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamBehaviorHints
import moe.ditto.halo.browse.SourceEntry
import moe.ditto.halo.browse.SourceFilter
import moe.ditto.halo.browse.SourceStatus
import moe.ditto.halo.browse.medalFor
import moe.ditto.halo.browse.metaLine
import moe.ditto.halo.browse.parseSourceInfo
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.QualityMedal
import moe.ditto.halo.ui.formatBytes
import moe.ditto.halo.ui.longPressClickable
import moe.ditto.halo.ui.monoStyle

/**
 * The pieces of the sources page, in the desktop sheet's vocabulary: a pick
 * card arguing for one source, medal rows grouped by addon, and a footer that
 * says how the list was built. Each takes plain values; the page decides.
 */

/** What the pushed screen names at its top; the tablet rail has a header of its own. */
internal class SourcesHeading(
    /** `COUNTERPART · S01E03`; null for a film, which needs no second name. */
    val kicker: String?,
    val title: String,
    val onBack: () -> Unit,
)

@Composable
internal fun SourcesHeader(heading: SourcesHeading, countLine: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The pushed screen runs edge to edge; its heading clears the status bar.
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = HaloSpacing.Md, end = HaloSpacing.Md, top = HaloSpacing.Sm, bottom = HaloSpacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(SourcesPalette.ControlShape)
                .background(SourcesPalette.ButtonFill)
                .border(1.dp, SourcesPalette.CardEdge, SourcesPalette.ControlShape)
                .clickable(role = Role.Button, onClick = heading.onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HaloIcons.ChevronLeft, contentDescription = "Back", tint = HaloColors.Text, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            heading.kicker?.let {
                Text(
                    text = it,
                    color = HaloColors.TextDim,
                    fontSize = 12.5.sp,
                    letterSpacing = 0.75.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = heading.title,
                color = HaloColors.Text,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 26.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (heading.kicker == null) 0.dp else 4.dp),
            )
            countLine?.let {
                Text(
                    text = it,
                    color = HaloColors.TextDim,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun FilterRow(
    filter: SourceFilter,
    counts: Map<SourceFilter, Int>,
    onFilter: (SourceFilter) -> Unit,
    sortLabel: String,
    onOpenSort: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = HaloSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceFilter.entries.forEach { option ->
            val on = option == filter
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(PillShape)
                    .then(if (on) Modifier.background(PillOnFill).border(1.dp, PillOnEdge, PillShape) else Modifier)
                    .clickable(role = Role.RadioButton, onClick = { onFilter(option) })
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(option.label, color = if (on) HaloColors.AccentLight else HaloColors.TextDim, fontSize = 14.sp)
                Text("${counts[option] ?: 0}", color = SourcesPalette.Faint, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(PillShape)
                .clickable(role = Role.Button, onClick = onOpenSort)
                .semantics { contentDescription = "Sort: $sortLabel" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(HaloIcons.Sort, contentDescription = null, tint = HaloColors.TextMeta, modifier = Modifier.size(20.dp))
        }
    }
}

/** The top-ranked source, on its own card, with the two things a viewer does with it. */
@Composable
internal fun PickCard(
    entry: SourceEntry,
    save: SaveState,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onDetails: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = HaloSpacing.Md)
            .clip(shape)
            .background(SourcesPalette.CardFill)
            .background(PickGlow)
            .border(1.dp, PillOnEdge, shape)
            .longPressClickable(onClick = onDetails, onLongPress = onDetails)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(entry.status)
            Text(entry.status.label, color = statusInk(entry.status), fontSize = 13.5.sp)
            Text("·", color = SourcesPalette.Faint, fontSize = 13.5.sp)
            Text("Best pick", color = HaloColors.TextDim, fontSize = 13.5.sp)
        }
        Row(
            modifier = Modifier.padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QualityMedal(medalFor(entry.info), large = true)
            Text(
                text = metaLine(entry.info),
                style = monoStyle(12.sp, FontWeight.Normal, color = HaloColors.TextDim),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = entry.info.filename,
            style = monoStyle(11.5.sp, FontWeight.Normal, color = SourcesPalette.Faint),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        PlaySaveButtons(save = save, onPlay = onPlay, onSave = onSave, modifier = Modifier.padding(top = HaloSpacing.Md))
    }
}

/**
 * This video's finished download, above everything addons offer: it starts at
 * once and needs no connection. Drawn as the pick card is, its quality read
 * from the saved release's name, with one action, since there is nothing left
 * to save.
 */
@Composable
internal fun OfflineCard(entry: DownloadEntry, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val info = remember(entry.media) {
        parseSourceInfo(
            Stream(
                name = entry.media.streamName,
                title = entry.media.streamTitle,
                behaviorHints = StreamBehaviorHints(filename = entry.media.filename),
            ),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = HaloSpacing.Md)
            .clip(shape)
            .background(SourcesPalette.CardFill)
            .border(1.dp, PillOnEdge, shape)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(HaloRadius.Pill)).background(HaloColors.Success))
            Text("Saved on this device", color = HaloColors.Success, fontSize = 13.5.sp)
            Text("·", color = SourcesPalette.Faint, fontSize = 13.5.sp)
            Text("Plays offline", color = HaloColors.TextDim, fontSize = 13.5.sp)
        }
        Row(
            modifier = Modifier.padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (info.quality != null) QualityMedal(medalFor(info), large = true)
            Text(
                text = listOfNotNull(
                    info.codec,
                    formatBytes(entry.totalBytes.takeIf { it > 0 } ?: entry.downloadedBytes),
                    entry.subtitle?.lang?.uppercase() ?: "NO SUBS",
                ).joinToString(" · "),
                style = monoStyle(12.sp, FontWeight.Normal, color = HaloColors.TextDim),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .padding(top = HaloSpacing.Md)
                .fillMaxWidth()
                .height(46.dp)
                .clip(SourcesPalette.ControlShape)
                .background(HaloColors.Text)
                .clickable(role = Role.Button, onClick = onPlay),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HaloIcons.Play, contentDescription = null, tint = HaloColors.Background, modifier = Modifier.size(20.dp))
            Text(
                "Play offline",
                color = HaloColors.Background,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Play, and beside it whatever saving this source means right now. Shared by the pick and the details sheet. */
@Composable
internal fun PlaySaveButtons(save: SaveState, onPlay: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(SourcesPalette.ControlShape)
                .background(HaloColors.AccentLight)
                .clickable(role = Role.Button, onClick = onPlay),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HaloIcons.Play, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
            Text("Play", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
        if (save != SaveState.Unavailable) SaveButton(save, onSave)
    }
}

@Composable
private fun SaveButton(save: SaveState, onSave: () -> Unit) {
    // Only states that can start something take a tap; the rest report.
    val actionable = save is SaveState.Ready || save == SaveState.Failed
    val (label, tint) = when (save) {
        is SaveState.Ready -> (if (save.replaces) "Save instead" else "Save") to HaloColors.Text
        is SaveState.Saving -> (save.fraction?.let { "Saving ${(it * 100).toInt()}%" } ?: "Saving") to HaloColors.Text
        SaveState.Paused -> "Paused" to HaloColors.TextDim
        SaveState.Saved -> "Saved" to HaloColors.Success
        SaveState.Failed -> "Retry save" to HaloColors.Danger
        SaveState.Unavailable -> "" to HaloColors.Text
    }
    Row(
        modifier = Modifier
            .height(46.dp)
            .clip(SourcesPalette.ControlShape)
            .background(SourcesPalette.ButtonFill)
            .border(1.dp, SourcesPalette.CardEdge, SourcesPalette.ControlShape)
            .then(if (actionable) Modifier.clickable(role = Role.Button, onClick = onSave) else Modifier)
            .padding(horizontal = HaloSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SaveGlyph(save, tint)
        Text(label, color = tint, fontSize = 15.sp)
    }
}

@Composable
private fun SaveGlyph(save: SaveState, tint: Color) {
    when (save) {
        is SaveState.Saving -> {
            val fraction = save.fraction
            if (fraction == null) {
                CircularProgressIndicator(Modifier.size(16.dp), color = HaloColors.AccentLight, strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(16.dp), color = HaloColors.AccentLight, strokeWidth = 2.dp)
            }
        }
        SaveState.Saved -> Icon(HaloIcons.Check, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        else -> Icon(HaloIcons.Download, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun GroupHeading(name: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = name,
            color = HaloColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$count", color = SourcesPalette.Faint, fontSize = 13.5.sp)
    }
}

/**
 * One source: its medal, whether it starts now, and a quiet line of facts.
 * Tapping plays it; the chevron (or a long press) opens everything else.
 */
@Composable
internal fun SourceRow(entry: SourceEntry, save: SaveState, onPlay: () -> Unit, onDetails: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(shape)
            .background(SourcesPalette.CardFill)
            .border(1.dp, SourcesPalette.CardEdge, shape)
            .longPressClickable(onClick = onPlay, onLongPress = onDetails)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        QualityMedal(medalFor(entry.info))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StatusDot(entry.status)
                Text(
                    text = entry.status.label,
                    color = statusInk(entry.status),
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = metaLine(entry.info)
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = monoStyle(11.5.sp, FontWeight.Normal, color = HaloColors.TextDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RowSaveMark(save)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(SourcesPalette.ControlShape)
                .clickable(role = Role.Button, onClick = onDetails)
                .semantics { contentDescription = "Source details" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(HaloIcons.ChevronDown, contentDescription = null, tint = HaloColors.TextMeta, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Only the row a download actually came from wears its state: marking every
 * row claims something about sources nobody fetched. Nothing shows for a row
 * that could merely be saved; that lives in its details.
 */
@Composable
private fun RowScope.RowSaveMark(save: SaveState) {
    val glyph = Modifier.size(18.dp)
    when (save) {
        SaveState.Saved -> Icon(HaloIcons.Check, contentDescription = "Saved on this device", tint = HaloColors.Success, modifier = glyph)
        is SaveState.Saving -> {
            val fraction = save.fraction
            if (fraction == null) {
                CircularProgressIndicator(glyph.semantics { contentDescription = "Saving" }, color = HaloColors.AccentLight, strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = glyph.semantics { contentDescription = "Saving" },
                    color = HaloColors.AccentLight,
                    strokeWidth = 2.dp,
                )
            }
        }
        SaveState.Paused -> Icon(HaloIcons.Download, contentDescription = "Save paused", tint = HaloColors.TextDim, modifier = glyph)
        SaveState.Failed -> Icon(HaloIcons.Download, contentDescription = "Save failed", tint = HaloColors.Danger, modifier = glyph)
        is SaveState.Ready, SaveState.Unavailable -> Unit
    }
}

@Composable
internal fun MoreButton(count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(40.dp)
            .clip(shape)
            .drawBehind {
                val inset = 0.5.dp.toPx()
                drawRoundRect(
                    color = DashedEdge,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Show $count more sources that download before they play" },
        contentAlignment = Alignment.Center,
    ) {
        Text("$count more", color = HaloColors.TextDim, fontSize = 14.sp)
    }
}

/** Shown when a filter leaves nothing, with the one way out of it. */
@Composable
internal fun EmptyFilter(filter: SourceFilter, onShowAll: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 120.dp, bottom = HaloSpacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("No ${filter.label} sources", color = HaloColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(SourcesPalette.ControlShape)
                .background(SourcesPalette.ButtonFill)
                .border(1.dp, SourcesPalette.CardEdge, SourcesPalette.ControlShape)
                .clickable(role = Role.Button, onClick = onShowAll)
                .padding(horizontal = HaloSpacing.Md),
            contentAlignment = Alignment.Center,
        ) {
            Text("Show all", color = HaloColors.Text, fontSize = 14.sp)
        }
    }
}

/**
 * An addon that did not answer, above everything it would have added to. The
 * list below it stays usable; Retry asks every addon again.
 */
@Composable
internal fun FailureBanner(text: String, busy: Boolean, onRetry: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = HaloSpacing.Md)
            .clip(shape)
            .background(BannerFill)
            .border(1.dp, BannerEdge, shape)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(SourcesPalette.Caution))
        Text(text, color = HaloColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(SourcesPalette.ControlShape)
                .background(SourcesPalette.ButtonFill)
                .border(1.dp, SourcesPalette.CardEdge, SourcesPalette.ControlShape)
                .then(if (busy) Modifier else Modifier.clickable(role = Role.Button, onClick = onRetry))
                .semantics { contentDescription = if (busy) "Retrying sources" else "Retry sources" }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = HaloColors.Text, strokeWidth = 2.dp)
            } else {
                Text("Retry", color = HaloColors.Text, fontSize = 14.sp)
            }
        }
    }
}

/** A line above the sources explaining a download the page just could not start. */
@Composable
internal fun RefusalNotice(text: String) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = text,
        color = HaloColors.Text,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = HaloSpacing.Md)
            .clip(shape)
            .background(SourcesPalette.CardFill)
            .border(1.dp, SourcesPalette.CardEdge, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/**
 * The bar pinned under the list, as the desktop sheet pins it, that opens
 * [HowBuiltSheet].
 *
 * @param insetNavigationBar true on the pushed screen, whose bottom edge is the
 *   window's; the tablet rail already clears the home indicator itself.
 */
@Composable
internal fun HowBuiltBar(
    onOpen: () -> Unit,
    insetNavigationBar: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(FooterFill)
            .drawBehind { drawLine(SourcesPalette.CardEdge, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .clickable(role = Role.Button, onClick = onOpen)
            .then(if (insetNavigationBar) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
            .height(48.dp)
            .padding(horizontal = HaloSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("How this list was built", color = HaloColors.AccentLight, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
        Icon(
            HaloIcons.ChevronDown,
            contentDescription = "Show",
            tint = HaloColors.AccentLight,
            modifier = Modifier.size(20.dp).rotate(180f),
        )
    }
}

@Composable
internal fun StatusDot(status: SourceStatus, modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(statusDot(status)))
}

/** Green plays now, amber is a short wait, and a wait of unknown length is grey. */
internal fun statusInk(status: SourceStatus): Color = when (status) {
    SourceStatus.Instant -> SourcesPalette.StatusGreen
    SourceStatus.Caching -> SourcesPalette.Caution
    SourceStatus.Uncached, SourceStatus.Unknown -> HaloColors.TextDim
}

private fun statusDot(status: SourceStatus): Color = when (status) {
    SourceStatus.Instant -> SourcesPalette.StatusGreen
    SourceStatus.Caching -> SourcesPalette.Caution
    SourceStatus.Uncached, SourceStatus.Unknown -> SourcesPalette.Faint
}

private val PillShape = RoundedCornerShape(4.dp)
private val PillOnFill = HaloColors.AccentLight.copy(alpha = 0.141f)
private val PillOnEdge = HaloColors.AccentLight.copy(alpha = 0.278f)
private val PickGlow = Brush.linearGradient(
    0f to HaloColors.AccentLight.copy(alpha = 0.141f),
    0.62f to Color.Transparent,
)
private val DashedEdge = Color.White.copy(alpha = 0.149f)
private val BannerFill = Color(0xFF433519)
private val BannerEdge = SourcesPalette.Caution.copy(alpha = 0.24f)
private val FooterFill = Color(0xFF1C1E24)

/** The colours the sources page shares between its list and its details sheet. */
internal object SourcesPalette {
    val StatusGreen = Color(0xFF6CCB5F)
    val Caution = Color(0xFFFCE100)
    val Faint = Color.White.copy(alpha = 0.365f)
    val CardFill = Color.White.copy(alpha = 0.051f)
    val CardEdge = Color.White.copy(alpha = 0.082f)
    val ButtonFill = Color.White.copy(alpha = 0.059f)
    val ControlShape = RoundedCornerShape(4.dp)
    val Track = Color.White.copy(alpha = 0.10f)
}
