package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.TimeZone
import moe.ditto.halo.auth.SystemEpochClock
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.monoStyle
import moe.ditto.halo.ui.rememberResponsive

/**
 * The right-hand pane on a landscape tablet: everything about the card the
 * grid has selected, and the two things worth doing to it.
 *
 * It exists because the extra width is there, not because the phone layout is
 * missing something: every fact here is reachable on a phone by playing the
 * file. So it holds no control the grid does not, and its Play enters the same
 * player route the card does.
 */
private val StillShape = RoundedCornerShape(HaloRadius.Lg)
private val FactsShape = RoundedCornerShape(HaloRadius.Lg)
private val ActionShape = RoundedCornerShape(HaloRadius.Md)
private val PlayDisc = 62.dp
private val PlayOfflineHeight = 48.dp
private val DeleteHeight = 44.dp

private const val WideAspect = 16f / 9f

@Composable
internal fun DownloadDetailPane(
    entry: DownloadEntry?,
    poster: String?,
    watched: Float?,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entry == null) {
        Box(modifier.fillMaxSize()) {
            CenterMessage("Pick something on the left to see what it holds.")
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = HaloSpacing.Lg,
                end = HaloSpacing.Lg,
                // The pane owns its own insets: it is a sibling of the list
                // rather than inside it, so nothing else is holding the status
                // bar off its artwork or the floating chrome off its buttons.
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HaloSpacing.Lg +
                    HaloSpacing.Xs,
                bottom = rememberResponsive().bottomContentPadding,
            ),
    ) {
        // Everything but the delete scrolls, so the delete stays where the
        // design pins it, at the foot of the pane, however tall the rest gets.
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(WideAspect)
                    .clip(StillShape)
                    .background(HaloColors.SurfaceHigh)
                    .clickable(role = Role.Button, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                // The episode's own still where the addon gave one, the title's
                // poster otherwise: a 16:9 frame cropped from a 2:3 poster is
                // still this title rather than a grey tile.
                HaloAsyncImage(
                    url = entry.media.episodeThumbnail ?: poster,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .size(PlayDisc)
                        .clip(RoundedCornerShape(HaloRadius.Pill))
                        .background(HaloColors.Primary.copy(alpha = 0.94f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HaloIcons.Play,
                        contentDescription = "Play this download",
                        tint = HaloColors.OnPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = downloadRowTitle(entry),
                    style = HaloType.Title.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = downloadDetailMeta(entry),
                    style = monoStyle(fontSize = 12.sp, color = HaloColors.TextDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            PlayOfflineButton(
                // Named for where it will start, and for the fact that starting
                // it costs nothing: this file is already here.
                label = if (watched != null) "Resume offline" else "Play offline",
                onClick = onPlay,
            )

            // Read once per entry: the date only changes the year it shows,
            // and that cannot happen while the pane is open often enough to
            // be worth a clock that ticks.
            val facts = remember(entry) {
                downloadFacts(entry, SystemEpochClock.nowMs(), TimeZone.currentSystemDefault())
            }
            if (facts.isNotEmpty()) FactsCard(facts)
        }

        Box(Modifier.height(HaloSpacing.Md))
        DeleteButton(onRemove)
    }
}

/**
 * The one white control in the pane. White because it is the thing the screen
 * exists for: everything else here manages a file, and this one watches it.
 */
@Composable
private fun PlayOfflineButton(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PlayOfflineHeight)
            .clip(ActionShape)
            .background(Brush.verticalGradient(listOf(HaloColors.Primary, HaloColors.PrimaryShade)))
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HaloIcons.Play,
            contentDescription = null,
            tint = HaloColors.OnPrimary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            color = HaloColors.OnPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun FactsCard(facts: List<Pair<String, String>>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(FactsShape)
            .background(HaloColors.Surface)
            .border(1.dp, HaloColors.Border, FactsShape),
    ) {
        facts.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Border))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HaloSpacing.Md - 2.dp, vertical = HaloSpacing.Sm + 4.dp),
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = label, color = HaloColors.TextDim, fontSize = 14.sp, maxLines = 1)
                Text(
                    text = value,
                    style = monoStyle(fontSize = 14.sp),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Red, as every destructive control is; it still opens the confirmation sheet rather than deleting. */
@Composable
private fun DeleteButton(onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DeleteHeight)
            .clip(ActionShape)
            .background(HaloColors.DangerFill)
            .border(1.dp, HaloColors.DangerBorder, ActionShape)
            .clickable(role = Role.Button, onClick = onRemove),
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HaloIcons.Trash,
            contentDescription = null,
            tint = HaloColors.Danger,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "Delete from device",
            color = HaloColors.Danger,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
