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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * The right-hand pane on a landscape tablet: everything about the row the list
 * has selected, and the two things worth doing to it.
 *
 * It exists because the extra width is there, not because the phone layout is
 * missing something — every fact here is reachable on a phone by playing the
 * file. So it holds no control the list does not, and its Play enters the same
 * player route the row does.
 */
private val StillShape = RoundedCornerShape(HaloRadius.Lg + 2.dp)
private val FactsShape = RoundedCornerShape(HaloRadius.Lg + 2.dp)
private val ActionShape = RoundedCornerShape(HaloRadius.Md - 1.dp)
private val PlayDisc = 62.dp

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
        Box(modifier.fillMaxSize().background(HaloColors.Primary.copy(alpha = 0.025f))) {
            CenterMessage("Pick something on the left to see what it holds.")
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HaloColors.Primary.copy(alpha = 0.025f))
            .verticalScroll(rememberScrollState())
            .padding(
                start = HaloSpacing.Lg,
                end = HaloSpacing.Lg,
                // The pane owns its own insets: it is a sibling of the list
                // rather than inside it, so nothing else is holding the status
                // bar off its artwork or the floating chrome off its buttons.
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HaloSpacing.Lg,
                bottom = rememberResponsive().bottomContentPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(WideAspect)
                .clip(StillShape)
                .clickable(role = Role.Button, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            // The episode's own still where the addon gave one, the title's
            // poster otherwise: a 16:9 frame cropped from a 2:3 poster is still
            // this title rather than a grey tile.
            HaloAsyncImage(
                url = entry.media.episodeThumbnail ?: poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().border(1.dp, HaloColors.Hairline, StillShape),
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
                style = HaloType.Title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            downloadRowSubtitle(entry)?.let { subtitle ->
                Text(text = subtitle, style = HaloType.Caption.copy(fontSize = 13.sp), maxLines = 1)
            }
        }

        PlayButton(
            // Named for where it will start, and for the fact that starting it
            // costs nothing: this file is already here.
            label = if (watched != null) "Resume offline" else "Play offline",
            onClick = onPlay,
            fillWidth = true,
        )

        val facts = downloadFacts(entry)
        if (facts.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(FactsShape)
                    .background(
                        Brush.linearGradient(
                            listOf(HaloColors.Glass, HaloColors.Primary.copy(alpha = 0.03f)),
                        ),
                    )
                    .border(1.dp, HaloColors.GlassBorder, FactsShape),
            ) {
                facts.forEachIndexed { index, (label, value) ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Hairline))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = HaloSpacing.Md - 2.dp, vertical = HaloSpacing.Sm + 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = HaloType.Caption.copy(fontSize = 13.sp),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = value,
                            style = monoStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ActionShape)
                .background(HaloColors.Danger.copy(alpha = 0.12f))
                .border(1.dp, HaloColors.Danger.copy(alpha = 0.4f), ActionShape)
                .clickable(role = Role.Button, onClick = onRemove)
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
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
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
