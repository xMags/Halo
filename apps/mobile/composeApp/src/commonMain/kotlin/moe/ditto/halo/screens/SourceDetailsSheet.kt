package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.ditto.halo.browse.DetailChip
import moe.ditto.halo.browse.SourceDetails
import moe.ditto.halo.browse.SourceEntry
import moe.ditto.halo.browse.formatSourceSize
import moe.ditto.halo.browse.medalFor
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.QualityMedal
import moe.ditto.halo.ui.SheetScaffold
import moe.ditto.halo.ui.monoStyle

/** A source opened for a closer look, with the read-out computed against the list it came from. */
internal class SourceDetailsRequest(val entry: SourceEntry, val details: SourceDetails)

/**
 * Everything known about one source, as a bottom sheet over the list: picture,
 * sound, bandwidth, subtitles and provenance as tiles, the file name with a
 * copy button, then Play and Save. The desktop shows the same tiles expanded
 * under a card; a phone has no room to open a card that far in place.
 *
 * Sits where every sheet sits (see [SheetScaffold]): the last child of the
 * picker's root box, beside the list it frosts.
 */
// BackHandler is still marked experimental in Compose 1.11; see SourcesRail.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SourceDetailsSheet(
    request: SourceDetailsRequest?,
    saveStateOf: (SourceEntry) -> SaveState,
    onPlay: (SourceEntry) -> Unit,
    onSave: (SourceEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = request != null, onBack = onDismiss)
    // Kept while the sheet slides away, so it does not empty on the way out.
    var shown by remember { mutableStateOf(request) }
    if (request != null) shown = request

    SheetScaffold(visible = request != null, onDismiss = onDismiss, bottomPadding = HaloSpacing.Lg) {
        val current = shown ?: return@SheetScaffold
        DetailsBody(
            entry = current.entry,
            details = current.details,
            save = saveStateOf(current.entry),
            onPlay = {
                onDismiss()
                onPlay(current.entry)
            },
            onSave = { onSave(current.entry) },
        )
    }
}

@Composable
private fun ColumnScope.DetailsBody(
    entry: SourceEntry,
    details: SourceDetails,
    save: SaveState,
    onPlay: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HaloSpacing.Md)
            .padding(top = HaloSpacing.Xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QualityMedal(medalFor(entry.info), large = true)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    StatusDot(entry.status)
                    Text(entry.status.label, color = statusInk(entry.status), fontSize = 13.5.sp)
                }
                val origin = listOfNotNull(details.provider, entry.info.sizeBytes?.let(::formatSourceSize))
                Text(origin.joinToString(" · "), color = HaloColors.TextDim, fontSize = 13.5.sp)
            }
        }

        TilePair(modifier = Modifier.padding(top = HaloSpacing.Md)) {
            Tile("PICTURE", Modifier.weight(1f)) {
                Text(details.resolution, style = monoStyle(14.5.sp, FontWeight.ExtraBold))
                Chips(details.pictureChips)
            }
            Tile("SOUND", Modifier.weight(1f)) {
                Text(details.sound, color = HaloColors.Text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Chips(details.audioLanguages)
            }
        }

        Tile("BANDWIDTH NEEDED", Modifier.padding(top = 8.dp).fillMaxWidth(), trailing = details.heaviestLabel) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(details.mbpsLabel, style = monoStyle(15.sp, FontWeight.ExtraBold))
                Text(details.bandwidthNote, color = HaloColors.TextDim, fontSize = 12.5.sp)
            }
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(SourcesPalette.Track)) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(details.meterFraction)
                        .clip(CircleShape)
                        .background(HaloColors.AccentLight),
                )
            }
        }

        TilePair(modifier = Modifier.padding(top = 8.dp)) {
            Tile("SUBTITLES", Modifier.weight(1f)) {
                Chips(details.subtitles)
            }
            Tile("COMES FROM", Modifier.weight(1f)) {
                Text(details.provider, color = HaloColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                CachePill(details.cacheLabel, details.cacheGood)
            }
        }

        FileName(entry.info.filename, modifier = Modifier.padding(top = 12.dp))

        PlaySaveButtons(save = save, onPlay = onPlay, onSave = onSave, modifier = Modifier.padding(top = HaloSpacing.Md))
    }
}

/** Two tiles side by side at the taller one's height, so their edges line up. */
@Composable
private fun TilePair(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun Tile(
    caption: String,
    modifier: Modifier = Modifier,
    trailing: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(SourcesPalette.CardFill)
            .border(1.dp, SourcesPalette.CardEdge, shape)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row {
            Text(caption, style = monoStyle(10.5.sp, color = HaloColors.TextDim, letterSpacing = 1.sp), modifier = Modifier.weight(1f))
            if (trailing.isNotEmpty()) {
                Text(trailing, style = monoStyle(10.5.sp, color = SourcesPalette.Faint, letterSpacing = 1.sp))
            }
        }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(chips: List<DetailChip>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        chips.forEach { chip ->
            Text(
                text = chip.label,
                style = monoStyle(
                    10.5.sp,
                    FontWeight.SemiBold,
                    color = if (chip.muted) HaloColors.Text.copy(alpha = 0.45f) else HaloColors.Text,
                    letterSpacing = 0.8.sp,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (chip.muted) ChipMutedFill else ChipFill)
                    .padding(horizontal = 7.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun CachePill(label: String, good: Boolean) {
    val ink = if (good) SourcesPalette.StatusGreen else HaloColors.TextDim
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(ink.copy(alpha = 0.12f))
            .border(1.dp, ink.copy(alpha = 0.3f), shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(ink))
        Text(label, color = ink, fontSize = 12.sp)
    }
}

/** The release name in full, since it is the one thing that identifies the file, and a way to take it elsewhere. */
@Composable
private fun FileName(name: String, modifier: Modifier = Modifier) {
    // The multiplatform replacement (LocalClipboard) needs a platform ClipEntry
    // built per target; this one still takes plain text everywhere.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember(name) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(CopiedMillis)
            copied = false
        }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = name,
            style = monoStyle(11.sp, FontWeight.Normal, color = SourcesPalette.Faint),
            modifier = Modifier.weight(1f).padding(top = 2.dp),
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(SourcesPalette.ControlShape)
                .background(SourcesPalette.ButtonFill)
                .border(1.dp, SourcesPalette.CardEdge, SourcesPalette.ControlShape)
                .clickable(role = Role.Button, onClickLabel = "Copy file name") {
                    clipboard.setText(AnnotatedString(name))
                    copied = true
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (copied) HaloIcons.Check else HaloIcons.Copy,
                contentDescription = if (copied) "File name copied" else "Copy file name",
                tint = if (copied) SourcesPalette.StatusGreen else HaloColors.Text,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private const val CopiedMillis = 1600L
private val ChipFill = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f)
private val ChipMutedFill = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f)
