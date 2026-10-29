package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.TimeZone
import moe.ditto.halo.browse.HistoryEntry
import moe.ditto.halo.browse.HistoryGroup
import moe.ditto.halo.browse.historyTime
import moe.ditto.halo.browse.historyVideoTag
import moe.ditto.halo.auth.SystemEpochClock
import moe.ditto.halo.shell.StreamsRoute
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.longPressClickable
import moe.ditto.halo.ui.monoStyle

/**
 * The Library tab's History view: every video played, newest first, under day
 * and month headings. Read only, and apart from the saved titles: nothing here
 * saves or removes anything.
 *
 * A row left partway opens that video's sources to carry on; a finished one
 * opens the title page; a long press opens its menu. Rows fetch nothing: the
 * episode tag comes from the video id.
 */
@Composable
internal fun HistoryList(
    groups: List<HistoryGroup>?,
    failed: Boolean,
    hasAny: Boolean,
    header: @Composable () -> Unit,
    gutter: Dp,
    bottomPadding: Dp,
    onOpenSources: (StreamsRoute) -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember(groups) { SystemEpochClock.nowMs() }
    val zone = remember { TimeZone.currentSystemDefault() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = bottomPadding),
    ) {
        item(key = "header") { header() }
        val message = when {
            groups == null && failed -> "Could not reach your Halo server."
            groups == null -> null
            !hasAny -> "Nothing watched yet. What you play shows up here."
            groups.isEmpty() -> "Nothing of this type in your history."
            else -> null
        }
        if (message != null) {
            item(key = "message") {
                Box(Modifier.fillMaxWidth().padding(top = HaloSpacing.Xl), contentAlignment = Alignment.Center) {
                    CenterMessage(message)
                }
            }
        }
        groups.orEmpty().forEach { group ->
            item(key = "label/${group.label}") {
                Text(
                    text = group.label.uppercase(),
                    style = monoStyle(11.sp, FontWeight.Bold, HaloColors.TextDim, 0.6.sp),
                    modifier = Modifier.padding(top = HaloSpacing.Md, bottom = HaloSpacing.Xs),
                )
            }
            items(items = group.entries, key = { "row/${it.videoId}" }) { entry ->
                HistoryRow(
                    entry = entry,
                    time = historyTime(entry.updatedAt, now, zone),
                    onClick = {
                        if (entry.finished) {
                            onOpenDetail(MetaRef(entry.type, entry.metaId))
                        } else {
                            onOpenSources(resumeRoute(entry.type, entry.metaId, entry.videoId, entry.name, entry.poster))
                        }
                    },
                )
            }
        }
    }
}

private val ThumbShape = RoundedCornerShape(6.dp)

@Composable
private fun HistoryRow(entry: HistoryEntry, time: String, onClick: () -> Unit) {
    val progress = if (entry.finished || entry.durationSec <= 0) 0f else (entry.positionSec / entry.durationSec).toFloat()
    val state = when {
        entry.finished -> "WATCHED"
        entry.durationSec > 0 -> "${timeLeft(entry.positionSec, entry.durationSec)} LEFT"
        else -> "STARTED"
    }
    val menus = LocalTitleMenus.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .longPressClickable(onClick = onClick, onLongPress = menus?.let { { it.openHistory(entry) } })
            .padding(vertical = HaloSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 44.dp, height = 66.dp).clip(ThumbShape)) {
            HaloAsyncImage(url = entry.poster, contentDescription = null, modifier = Modifier.fillMaxSize())
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Box(Modifier.fillMaxWidth(progress.coerceAtMost(1f)).fillMaxHeight().background(HaloColors.Accent))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                color = HaloColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${historyVideoTag(entry.videoId, entry.metaId)} · $state",
                style = monoStyle(11.sp, color = HaloColors.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text(text = time, color = HaloColors.TextDim, fontSize = 12.5.sp, maxLines = 1)
    }
}

/** "1 H 28 MIN", "2 H" or "42 MIN": the desktop's `formatTimeLeft` wording. */
internal fun timeLeft(positionSec: Double, durationSec: Double): String {
    val remaining = (durationSec - positionSec).coerceAtLeast(0.0)
    if (remaining < 60) return "UNDER A MIN"
    val minutes = kotlin.math.round(remaining / 60).toLong()
    if (minutes < 60) return "$minutes MIN"
    val rest = minutes % 60
    return if (rest == 0L) "${minutes / 60} H" else "${minutes / 60} H $rest MIN"
}

private val ClearButtonShape = RoundedCornerShape(HaloRadius.Pill)

/**
 * Clears the whole history, after the confirmation it asks for. Red like every
 * destructive control in the app, and at the end of the row, out of the way
 * of the filter above it.
 */
@Composable
internal fun ClearHistoryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Row(
            modifier = Modifier
                .clip(ClearButtonShape)
                .background(HaloColors.Danger.copy(alpha = 0.12f))
                .border(1.dp, HaloColors.Danger.copy(alpha = 0.38f), ClearButtonShape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = HaloSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = HaloIcons.Trash, contentDescription = null, tint = HaloColors.Danger, modifier = Modifier.size(16.dp))
            Text(text = "Clear history", color = HaloColors.Danger, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
