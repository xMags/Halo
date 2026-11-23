package moe.ditto.halo.tv.browse

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.Stream
import moe.ditto.halo.browse.SourceDetails
import moe.ditto.halo.browse.SourceEntry
import moe.ditto.halo.browse.SourceFilter
import moe.ditto.halo.browse.SourceSort
import moe.ditto.halo.browse.SourceStatus
import moe.ditto.halo.browse.buildListing
import moe.ditto.halo.browse.detailsFor
import moe.ditto.halo.browse.filterCounts
import moe.ditto.halo.browse.medalFor
import moe.ditto.halo.browse.metaLine
import moe.ditto.halo.browse.resolveSources
import moe.ditto.halo.browse.sanitizeAddonName
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.screens.StreamsContentState
import moe.ditto.halo.screens.safeAddonFailure
import moe.ditto.halo.screens.streamsContentState
import moe.ditto.halo.shell.StreamsRoute
import moe.ditto.halo.sync.runtimeMinutes
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvFocusMemory
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvRowSurface
import moe.ditto.halo.tv.kit.TvSidePanel
import moe.ditto.halo.tv.kit.TvSurface
import moe.ditto.halo.tv.kit.TvSurfaceColors
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.tv.kit.defaultFocus
import moe.ditto.halo.tv.kit.rememberFocus
import moe.ditto.halo.tv.kit.rememberTvFocusMemory
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloSkeleton
import moe.ditto.halo.ui.QualityMedal
import moe.ditto.halo.ui.monoStyle

private val PanelWidth = 540.dp

/**
 * One video's sources on TV: a panel over the title's art, ranked and
 * filtered by the phone's own rules ([resolveSources], [buildListing]). Select
 * plays a source; Menu (or holding select) shows its file details. Sources
 * that must download before they play wait behind one row, as on the phone.
 * There is no download button: a TV keeps no downloads.
 */
@Composable
internal fun TvSourcesScreen(
    graph: SessionGraph,
    route: StreamsRoute,
    onPlay: (AddonSource, Stream) -> Unit,
) {
    val memory = rememberTvFocusMemory()
    val scope = rememberCoroutineScope()
    val query by remember(graph, route.type, route.videoId) { graph.browse.streams(route.type, route.videoId) }.collectAsState(QueryState())
    val meta by remember(graph, route.type, route.metaId) { graph.browse.meta(route.type, route.metaId) }.collectAsState(QueryState())
    val runtimeSeconds = (runtimeMinutes(meta.value?.runtime) ?: 0) * 60.0
    val state = streamsContentState(query)
    val answer = state as? StreamsContentState.Sources
    val resolve = remember(answer?.groups, answer?.failures, runtimeSeconds) {
        answer?.let { resolveSources(it.groups, it.failures, runtimeSeconds) }
    }
    var filter by remember { mutableStateOf(SourceFilter.All) }
    var revealAll by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Pair<SourceEntry, SourceDetails>?>(null) }
    val retry: () -> Unit = { scope.launch { runCatching { graph.browse.retryStreams(route.type, route.videoId) } } }

    Box(Modifier.fillMaxSize().background(HaloColors.Background)) {
        // The title behind the panel, dimmed: where the sources belong.
        HaloAsyncImage(
            url = meta.value?.background ?: route.episodeThumbnail ?: route.poster,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(HaloColors.Background.copy(alpha = 0.82f)))
        Column(
            Modifier.padding(start = TvMetrics.SideMargin + 8.dp, top = TvMetrics.VerticalMargin + 8.dp).width(320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(route.showTitle, style = TvType.Hero.copy(color = HaloColors.Text.copy(alpha = 0.4f)), maxLines = 3)
        }

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(PanelWidth)
                .fillMaxHeight()
                .shadow(32.dp)
                .background(HaloColors.Surface)
                .padding(start = 32.dp, end = TvMetrics.SideMargin, top = TvMetrics.VerticalMargin + 8.dp, bottom = TvMetrics.VerticalMargin),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            route.sourcesKicker?.let { Text(it, style = TvType.Overline) }
            Text(route.sourcesTitle, style = TvType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (state) {
                    is StreamsContentState.Loading -> "Asking your addons…"
                    is StreamsContentState.Sources -> resolve?.summary.orEmpty()
                    else -> ""
                },
                style = TvType.Meta.copy(color = HaloColors.TextDim),
            )
            Box(Modifier.weight(1f).padding(top = 16.dp)) {
                when (state) {
                    is StreamsContentState.Loading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(4) { HaloSkeleton(Modifier.fillMaxWidth().height(64.dp)) }
                    }
                    is StreamsContentState.RequestFailure -> Failure(state.message, memory, retry)
                    is StreamsContentState.AllAddonsFailed -> Failure(
                        "No addons could load sources. " + state.failures.joinToString(" ") { safeAddonFailure(it) },
                        memory,
                        retry,
                    )
                    is StreamsContentState.NoSources -> Failure(NoSourcesMessage, memory, retry)
                    is StreamsContentState.Sources -> {
                        val result = resolve
                        if (result == null || result.pool.isEmpty()) {
                            Failure(NoSourcesMessage, memory, retry)
                        } else {
                            val listing = remember(result, filter, revealAll) {
                                buildListing(result, filter, SourceSort.Recommended, revealAll)
                            }
                            val counts = remember(result) { filterCounts(result.pool) }
                            val heaviest = remember(result) { result.pool.maxOfOrNull { it.neededMbps } ?: 0.0 }
                            SourcesList(
                                entries = listOfNotNull(listing.pick) + listing.groups.flatMap { it.entries },
                                hiddenCount = listing.hiddenCount,
                                empty = listing.empty,
                                filter = filter,
                                counts = counts,
                                memory = memory,
                                onFilter = { filter = it },
                                onReveal = { revealAll = true },
                                onPlay = { onPlay(it.addon, it.stream) },
                                onDetails = { details = it to detailsFor(it, heaviest) },
                            )
                        }
                    }
                }
            }
            Text("Press select to play · Menu for file details", style = TvType.Caption, modifier = Modifier.padding(top = 8.dp))
        }

        details?.let { (entry, facts) ->
            SourceDetailsPanel(entry, facts, onPlay = { details = null; onPlay(entry.addon, entry.stream) }, onDismiss = { details = null })
        }
    }
}

@Composable
private fun Failure(message: String, memory: TvFocusMemory, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, style = TvType.Body)
        TvButton("Try again", onClick = onRetry, modifier = Modifier.defaultFocus(memory, "retry"))
    }
}

@Composable
private fun SourcesList(
    entries: List<SourceEntry>,
    hiddenCount: Int,
    empty: Boolean,
    filter: SourceFilter,
    counts: Map<SourceFilter, Int>,
    memory: TvFocusMemory,
    onFilter: (SourceFilter) -> Unit,
    onReveal: () -> Unit,
    onPlay: (SourceEntry) -> Unit,
    onDetails: (SourceEntry) -> Unit,
) {
    ProvideVerticalPivot(pivot = 8.dp) {
        LazyColumn(
            state = rememberLazyListState(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp, start = 6.dp, end = 6.dp),
        ) {
            item(key = "filters") {
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceFilter.entries.forEach { option ->
                        val count = counts[option] ?: 0
                        if (option != SourceFilter.All && count == 0) return@forEach
                        FilterPill(
                            label = option.label,
                            count = count,
                            selected = option == filter,
                            onClick = { onFilter(option) },
                            modifier = Modifier.rememberFocus(memory, "filter/${option.name}"),
                        )
                    }
                }
            }
            if (empty) {
                item(key = "empty") { Text("No sources at this quality.", style = TvType.Body) }
            }
            items(entries, key = { it.key }) { entry ->
                SourceRow(
                    entry = entry,
                    onClick = { onPlay(entry) },
                    onMenu = { onDetails(entry) },
                    modifier = if (entry == entries.first()) {
                        Modifier.defaultFocus(memory, "source/${entry.key}")
                    } else {
                        Modifier.rememberFocus(memory, "source/${entry.key}")
                    },
                )
            }
            if (hiddenCount > 0) {
                item(key = "more") {
                    TvRowSurface(
                        title = "Show $hiddenCount more",
                        hint = "These download before they play",
                        onClick = onReveal,
                        modifier = Modifier.rememberFocus(memory, "more"),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, count: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvSurface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        cornerRadius = 17.dp,
        colors = if (selected) TvSurfaceColors(HaloColors.Text, HaloColors.Primary) else TvSurfaceColors(HaloColors.FieldFill, HaloColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = if (selected) HaloColors.OnPrimary else HaloColors.TextMeta, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("$count", color = if (selected) HaloColors.OnPrimary.copy(alpha = 0.6f) else HaloColors.TextDim, fontSize = 14.sp)
        }
    }
}

private fun statusColor(status: SourceStatus): Color = when (status) {
    SourceStatus.Instant -> HaloColors.Success
    SourceStatus.Caching -> HaloColors.Gold
    SourceStatus.Uncached, SourceStatus.Unknown -> HaloColors.TextDim
}

@Composable
private fun SourceRow(entry: SourceEntry, onClick: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val medal = medalFor(entry.info)
    TvSurface(
        onClick = onClick,
        onMenu = onMenu,
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .semantics { contentDescription = "${listOfNotNull(medal.plate, medal.tag).joinToString(" ")}, ${entry.status.label}, ${metaLine(entry.info)}" },
        cornerRadius = 14.dp,
        colors = TvSurfaceColors(Color.White.copy(alpha = 0.03f), HaloColors.SurfaceHigh),
        focusScale = TvMetrics.RowFocusScale,
        ringBackground = HaloColors.Surface,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            QualityMedal(medal, large = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(7.dp).background(statusColor(entry.status), CircleShape))
                    Text(entry.status.label, color = statusColor(entry.status), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text(
                    listOf(metaLine(entry.info), sanitizeAddonName(entry.addon.name)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = monoStyle(13.sp, FontWeight.Medium, color = HaloColors.TextDim, letterSpacing = 0.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SourceDetailsPanel(entry: SourceEntry, facts: SourceDetails, onPlay: () -> Unit, onDismiss: () -> Unit) {
    TvSidePanel(title = "File details", subtitle = entry.status.label, onDismiss = onDismiss, width = 480.dp) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { QualityMedal(medalFor(entry.info), large = true) }
            item { Fact("Picture", listOf(facts.resolution) + facts.pictureChips.map { it.label }) }
            item { Fact("Sound", listOf(facts.sound) + facts.audioLanguages.map { it.label }) }
            if (facts.subtitles.isNotEmpty()) item { Fact("Subtitles", facts.subtitles.map { it.label }) }
            item { Fact("From", listOf(facts.provider, facts.cacheLabel)) }
            item { Fact("Needs", listOf(facts.mbpsLabel, facts.bandwidthNote).filter { it.isNotBlank() }) }
            entry.info.filename.takeIf { it.isNotBlank() }?.let { name ->
                item { Text(name, style = monoStyle(12.sp, FontWeight.Medium, color = HaloColors.TextDim), maxLines = 3) }
            }
        }
        TvButton("Play this source", onClick = onPlay, style = TvButtonStyle.Primary)
    }
}

@Composable
private fun Fact(label: String, values: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = TvType.Overline)
        Text(values.filter { it.isNotBlank() }.joinToString(" · "), style = TvType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private const val NoSourcesMessage =
    "None of your addons offered a source for this. Add a streaming addon in Settings, or try again later."
