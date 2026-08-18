package moe.ditto.halo.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloPlayerColors
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.monoStyle

/** Opaque for the same reason the rail is: there is no blur to be had over video. */
private val DrawerFill = HaloPlayerColors.DrawerFill.compositeOver(HaloColors.Background)
private val DrawerEdge = Color.White.copy(alpha = 0.11f)
private val CloseButtonFill = Color.White.copy(alpha = 0.08f)
private val CardBorder = Color.White.copy(alpha = 0.10f)
private val CurrentCardBorder = Color(red = 10f / 255f, green = 132f / 255f, blue = 255f / 255f, alpha = 0.70f)
private val WatchedProgress = Color.White.copy(alpha = 0.55f)

private val DrawerCornerRadius = 22.dp
private val ThumbRadius = 10.dp
private val ProgressBarHeight = 3.dp

/**
 * Episodes of the season being watched, without leaving playback.
 *
 * New in this design: the old player had no way to reach the next episode except
 * by leaving, and coming back meant re-picking a source. Each card shows how far
 * through it you are and whether it is already on the device, so choosing is
 * possible from the strip itself rather than by opening each one.
 */
@Composable
internal fun PlayerEpisodeDrawer(
    metrics: PlayerMetrics,
    seasonTitle: String,
    episodes: List<PlayerEpisode>,
    currentTag: String,
    onClose: () -> Unit,
    onSelectEpisode: (PlayerEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = DrawerCornerRadius, topEnd = DrawerCornerRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DrawerFill)
            // Swallows taps: the video underneath toggles the chrome, and a
            // miss inside the drawer must not do that.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = metrics.hPad,
                    end = metrics.hPad,
                    top = 14.dp,
                    bottom = metrics.drawerBottomPadding,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = seasonTitle,
                        color = HaloColors.Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = episodeSummary(episodes),
                        color = HaloColors.TextDim,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(HaloRadius.Pill))
                        .background(CloseButtonFill)
                        .clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HaloIcons.Close,
                        contentDescription = "Close episodes",
                        tint = HaloColors.Text,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                episodes.forEach { episode ->
                    EpisodeCard(
                        episode = episode,
                        current = episode.tag == currentTag,
                        metrics = metrics,
                        onClick = { onSelectEpisode(episode) },
                    )
                }
            }
        }

        // Drawn over the fill so the rounded top corners carry it.
        Box(Modifier.matchParentSize().border(1.dp, DrawerEdge, shape))
    }
}

/**
 * "10 episodes · 3 downloaded", with the second half dropped when none are.
 * A permanent "0 downloaded" would read as a broken counter rather than as the
 * absence of a feature being used.
 */
private fun episodeSummary(episodes: List<PlayerEpisode>): String {
    val downloaded = episodes.count { it.downloaded }
    val count = "${episodes.size} episodes"
    if (downloaded == 0) return count
    return "$count · $downloaded downloaded"
}

@Composable
private fun EpisodeCard(
    episode: PlayerEpisode,
    current: Boolean,
    metrics: PlayerMetrics,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(metrics.episodeCardWidth)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.episodeThumbHeight)
                .clip(RoundedCornerShape(ThumbRadius))
                .placeholderStripes()
                .border(
                    width = 1.dp,
                    color = if (current) CurrentCardBorder else CardBorder,
                    shape = RoundedCornerShape(ThumbRadius),
                ),
        ) {
            HaloAsyncImage(
                url = episode.thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                placeholder = { Box(Modifier.fillMaxSize().placeholderStripes()) },
            )
            // Progress rides the bottom edge of the still rather than sitting
            // under it, so the strip stays one row of thumbnails to scan.
            if (episode.progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(episode.progress.coerceIn(0f, 1f))
                        .height(ProgressBarHeight)
                        .background(if (current) HaloColors.Accent else WatchedProgress),
                )
            }
        }

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = episode.tag,
                style = monoStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (current) HaloColors.Accent else HaloColors.TextDim,
                ),
            )
            if (episode.downloaded) {
                Icon(
                    imageVector = HaloIcons.Download,
                    contentDescription = "Downloaded",
                    tint = HaloColors.Success,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        Text(
            text = episode.name,
            color = if (current) HaloColors.Text else HaloColors.TextMeta,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
