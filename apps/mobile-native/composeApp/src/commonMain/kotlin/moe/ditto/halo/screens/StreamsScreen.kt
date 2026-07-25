package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.SignedInGraph
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.Stream
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.formatBytes

/**
 * Where a title's playable sources are chosen, grouped by the addon that
 * offered them.
 *
 * Nothing here filters: the server has already dropped torrents and
 * external-link results and omitted addons left with none, so an empty list
 * means nothing installed can play this — not that something was hidden. The
 * groups stay separate for the same reason search results do; which addon
 * vouched for a source is half of what makes it choosable.
 */
@Composable
internal fun StreamsScreen(
    graph: SignedInGraph,
    type: String,
    videoId: String,
    /** What is being played, for the header — an episode reads "Show — S01E02". */
    title: String,
    onBack: () -> Unit,
    onPlay: (Stream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val streams by remember(graph, type, videoId) {
        graph.browse.streams(type, videoId)
    }.collectAsState(QueryState())

    Column(modifier.fillMaxSize().background(HaloColors.Background)) {
        // The header outlives every state below it: a screen that swaps itself
        // for a spinner takes its own way back with it.
        ScreenHeader(
            title = "Sources",
            subtitle = title,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = HaloSpacing.Md),
        )

        val groups = streams.value
        when {
            // A source list that could not be fetched is not an empty one, and
            // telling someone to install an addon when the server is unreachable
            // sends them to fix the wrong thing.
            groups == null && streams.error != null -> CenterMessage("Could not reach your Halo server.")
            groups == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = HaloColors.Accent)
                    Text(
                        text = "Asking your addons for sources…",
                        style = HaloType.Caption,
                        modifier = Modifier.padding(top = HaloSpacing.Md),
                    )
                }
            }
            groups.isEmpty() ->
                CenterMessage("No playable sources. Install a stream addon (e.g. a debrid-backed one) in Settings.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // No tab-bar allowance: this screen covers the bar.
                contentPadding = PaddingValues(
                    start = HaloSpacing.Md,
                    end = HaloSpacing.Md,
                    bottom = HaloSpacing.Xl,
                ),
            ) {
                // Keyed by addon, so a slow one arriving late does not renumber
                // the groups already on screen.
                items(items = groups, key = { it.addon.id }) { group ->
                    AddonGroup(group = group, onPlay = onPlay)
                }
            }
        }
    }
}

@Composable
private fun AddonGroup(group: AddonStreams, onPlay: (Stream) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = HaloSpacing.Md)) {
        Text(
            text = group.addon.name.uppercase(),
            style = HaloType.Overline.copy(color = HaloColors.Accent),
            modifier = Modifier.padding(start = HaloSpacing.Xs, bottom = HaloSpacing.Sm),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(HaloRadius.Lg))
                .background(HaloColors.Glass)
                .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(HaloRadius.Lg)),
        ) {
            group.streams.forEachIndexed { index, stream ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Hairline))
                StreamRow(stream = stream, fallbackName = group.addon.name, onClick = { onPlay(stream) })
            }
        }
    }
}

@Composable
private fun StreamRow(stream: Stream, fallbackName: String, onClick: () -> Unit) {
    val size = stream.behaviorHints?.videoSize?.let(::formatBytes).orEmpty()
    val detail = stream.title ?: stream.description
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = HaloSpacing.Md, vertical = HaloSpacing.Sm + 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
    ) {
        Column(Modifier.weight(1f)) {
            // Real results put quality, codec and cache state on separate lines
            // of one string — clamping to a single line throws away the half a
            // source is picked on.
            Text(
                text = stream.name ?: fallbackName,
                color = HaloColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = HaloType.Caption.copy(fontSize = 12.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            stream.behaviorHints?.filename?.let { filename ->
                Text(
                    text = filename,
                    style = HaloType.Caption.copy(fontSize = 10.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (size.isNotEmpty()) {
            Text(
                text = size,
                style = HaloType.Caption.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
    }
}
