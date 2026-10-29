package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.downloads.DeviceDownloadRuntime
import moe.ditto.halo.downloads.StorageSpace
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.ConfirmSheet
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.LocalHazeState
import moe.ditto.halo.ui.ResponsiveInfo
import moe.ditto.halo.ui.glassSource
import moe.ditto.halo.ui.rememberResponsive

/**
 * What is arriving, and what is already here.
 *
 * The screen is built around two questions the old grouped list answered badly:
 * how a transfer is going, and whether a file can be watched yet. So the queue
 * comes first, in one panel with the rate, the minute of history that says
 * whether the link is steady and the room left on the device; finished files
 * follow as a grid of artwork that plays on a tap.
 *
 * Downloads are device-local, so this never waits on the network and never shows
 * a loading state: the index is read from the store when the session's graph is
 * built, which is why an entry is on screen the moment the tab is opened,
 * offline included. Watch progress is the one exception, and it is decoration:
 * the hairline across a card's art is absent offline rather than the screen waiting
 * for it.
 */
@Composable
internal fun DownloadsScreen(
    graph: SessionGraph,
    /** Plays a download from the device. Nothing here touches the network. */
    onPlay: (DownloadEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloads = graph.downloads
    val responsive = rememberResponsive()
    val entries by downloads.entries.collectAsState()
    val watchStates by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())
    val scope = rememberCoroutineScope()

    val sections = remember(entries) { downloadSections(entries) }
    val progress = remember(watchStates.value) { watchStates.value.orEmpty().associateBy { it.videoId } }
    val history = rememberThroughputHistory { aggregateRate(entries) }
    val space = rememberStorageSpace(downloads)

    var pendingRemoval by remember { mutableStateOf<DownloadEntry?>(null) }
    var selectedVideoId by remember { mutableStateOf<String?>(null) }
    // The selection follows the list rather than being held against it: a
    // removed or finished-and-replaced row must not leave the pane showing an
    // entry the list no longer has.
    val selected = sections.ready.firstOrNull { it.videoId == selectedVideoId } ?: sections.ready.firstOrNull()

    // The removal sheet frosts this screen, so the blur source has to be the
    // screen's own body: a frosted surface cannot sample a source it is part of,
    // and sampling the shell's whole-navigation-host source (which contains this
    // sheet) leaves the card drawing nothing at all. See `LocalHazeState`.
    val bodyHaze = rememberHazeState()

    CompositionLocalProvider(LocalHazeState provides bodyHaze) {
        Box(modifier.fillMaxSize().background(HaloColors.Background)) {
            // The sheet blurs this, and only this.
            Box(Modifier.fillMaxSize().glassSource(bodyHaze)) {
                when {
                    !downloads.isAvailable -> DownloadsPlaceholder(responsive) {
                        CenterMessage("This device has nowhere to keep downloads.")
                    }

                    entries.isEmpty() -> DownloadsPlaceholder(responsive) {
                        CenterMessage(
                            "Downloads live here. Pick a source on any title and tap the download icon, " +
                                "then it plays with no network at all.",
                        )
                    }

                    // Landscape on a tablet is the one shape with width to spare for a
                    // second pane; portrait is the phone layout at tablet spacing.
                    responsive.isTablet && responsive.isLandscape -> Row(
                        // The navigation rail floats over the leading edge, so
                        // the list starts beside it rather than under it.
                        Modifier.fillMaxSize().padding(start = responsive.contentInsetStart),
                    ) {
                        DownloadsList(
                            modifier = Modifier.weight(1f),
                            responsive = responsive,
                            listWidth = responsive.contentWidth - detailPaneWidth(responsive),
                            entries = entries,
                            sections = sections,
                            progress = progress,
                            history = history,
                            space = space,
                            selectedVideoId = selected?.videoId,
                            downloads = downloads,
                            onSelect = { selectedVideoId = it.videoId },
                            onPlay = onPlay,
                            onRemove = { pendingRemoval = it },
                        )
                        Box(Modifier.fillMaxHeight().width(1.dp).background(HaloColors.SurfaceHigh))
                        DownloadDetailPane(
                            entry = selected,
                            poster = selected?.let { downloadPoster(it, sections) },
                            watched = selected?.let { watchedFraction(progress[it.videoId]) },
                            onPlay = { selected?.let(onPlay) },
                            onRemove = { pendingRemoval = selected },
                            modifier = Modifier.width(detailPaneWidth(responsive)),
                        )
                    }

                    else -> DownloadsList(
                        modifier = Modifier
                            .padding(start = responsive.contentInsetStart)
                            .then(
                                responsive.contentMaxWidth?.let { Modifier.widthIn(max = it).fillMaxWidth() }
                                    ?: Modifier.fillMaxWidth(),
                            )
                            .align(Alignment.TopCenter),
                        responsive = responsive,
                        listWidth = responsive.contentMaxWidth?.coerceAtMost(responsive.width) ?: responsive.width,
                        entries = entries,
                        sections = sections,
                        progress = progress,
                        history = history,
                        space = space,
                        selectedVideoId = null,
                        downloads = downloads,
                        onSelect = {},
                        onPlay = onPlay,
                        onRemove = { pendingRemoval = it },
                    )
                }
            }

            // Last child of the root, per the sheet contract, and a sibling of the
            // source above rather than a child of it.
            val removal = pendingRemoval
            ConfirmSheet(
                visible = removal != null,
                title = "Delete this download?",
                subject = removal?.let(::downloadRowTitle),
                body = "The video and its subtitle are removed from this device. " +
                    "Nothing on the server changes, so it can be downloaded again.",
                confirmLabel = "Delete from device",
                icon = HaloIcons.Trash,
                // This tab keeps its chrome, and where that chrome is the tab
                // bar it draws over the sheet. The rail does not.
                bottomClearance = responsive.floatingBarClearance,
                onConfirm = {
                    removal?.let { entry -> scope.launch { downloads.remove(entry.videoId) } }
                    pendingRemoval = null
                },
                onDismiss = { pendingRemoval = null },
            )
        }
    }
}

private const val DownloadsTitle = "Downloads"

/** How often the volume is re-measured. Slow, because free space is a slow figure. */
private const val StorageRefreshMs = 5_000L

private val DetailPaneWidth = 360.dp
private val DetailPaneWidthLarge = 400.dp
private val WideDetailPaneFrom = 1_300.dp

private fun detailPaneWidth(responsive: ResponsiveInfo): Dp =
    if (responsive.width >= WideDetailPaneFrom) DetailPaneWidthLarge else DetailPaneWidth

/**
 * Room on the device, re-read on a timer rather than per frame: both halves of
 * it are filesystem calls, and the answer moves at the speed of a download
 * rather than of a recomposition.
 */
@Composable
private fun rememberStorageSpace(downloads: DeviceDownloadRuntime): StorageSpace? {
    var space by remember(downloads) { mutableStateOf(downloads.storageSpace()) }
    LaunchedEffect(downloads) {
        while (true) {
            delay(StorageRefreshMs)
            space = downloads.storageSpace()
        }
    }
    return space
}

@Composable
private fun DownloadsList(
    modifier: Modifier,
    responsive: ResponsiveInfo,
    /** What the list itself is laid out in, which on a tablet is not the window. */
    listWidth: Dp,
    entries: List<DownloadEntry>,
    sections: DownloadSections,
    progress: Map<String, WatchState>,
    history: ThroughputHistory,
    space: StorageSpace?,
    selectedVideoId: String?,
    downloads: DeviceDownloadRuntime,
    onSelect: (DownloadEntry) -> Unit,
    onPlay: (DownloadEntry) -> Unit,
    onRemove: (DownloadEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val gutter = downloadsGutter(responsive)
    val wideQueue = listWidth >= WideQueuePanelFrom
    // A phone shows finished downloads as posters, three to a row like the
    // catalogs' poster rows, rather than one wide card filling the screen.
    val posters = !responsive.isTablet
    val columns = readyGridColumns(
        available = listWidth - gutter * 2,
        minCell = if (posters) ReadyPosterMinWidth else ReadyCardMinWidth,
        gap = ReadyGridGap,
        maxColumns = if (posters) PhoneMaxReadyPosters else null,
    )
    // Only a layout with a detail pane selects; without one a card plays.
    val selecting = selectedVideoId != null
    val menus = LocalTitleMenus.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Horizontal padding belongs to the blocks rather than to the list: the
        // header's scrim has to reach both edges, and a content inset would
        // leave a sliver of scrolling list showing beside it.
        contentPadding = PaddingValues(
            // Chrome that floats over this screen has to be cleared or the last
            // row sits behind the glass.
            bottom = responsive.bottomContentPadding,
        ),
    ) {
        stickyHeader(key = "header") {
            DownloadsHeader(
                gutter = gutter,
                summary = downloadsSummary(entries),
                stacked = !responsive.isTablet,
                control = queueControl(sections.active),
                onControl = { scope.launch { applyQueueControl(downloads, sections.active) } },
            )
        }

        item(key = "queue") {
            QueuePanel(
                bytesPerSecond = aggregateRate(entries),
                connections = aggregateConnections(entries),
                idle = entries.none { it.status == DownloadStatus.Downloading },
                history = history,
                meter = space?.let { storageMeter(it, downloadedBytesOnDevice(entries)) },
                wide = wideQueue,
                modifier = Modifier.padding(start = gutter, end = gutter, bottom = SectionGap),
                rows = sections.active.takeIf { it.isNotEmpty() }?.let { active ->
                    {
                        active.forEachIndexed { index, entry ->
                            key(entry.videoId) {
                                if (index > 0) {
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.SurfaceHigh))
                                }
                                DownloadQueueRow(
                                    entry = entry,
                                    art = downloadArt(entry, sections),
                                    compact = !wideQueue,
                                    onPause = { scope.launch { downloads.pause(entry.videoId) } },
                                    onResume = { scope.launch { downloads.resume(entry.videoId) } },
                                    onRemove = { onRemove(entry) },
                                    menu = menus?.let { { it.openDownload(entry) } },
                                )
                            }
                        }
                    }
                },
            )
        }

        if (sections.ready.isNotEmpty()) {
            item(key = "ready-label") {
                DownloadsSectionCaption(
                    label = readySectionLabel(responsive.isTablet),
                    count = readyCountLabel(sections.ready),
                    modifier = Modifier.padding(start = gutter, end = gutter, bottom = HaloSpacing.Sm + 4.dp),
                )
            }
            // Rows of cells rather than a lazy grid: the grid scrolls with the
            // queue above it as one list, and a grid nested in a list cannot.
            items(
                items = sections.ready.chunked(columns),
                key = { row -> "ready:" + row.first().videoId },
            ) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = gutter, end = gutter, bottom = ReadyGridGap),
                    horizontalArrangement = Arrangement.spacedBy(ReadyGridGap),
                ) {
                    row.forEach { entry ->
                        DownloadReadyCard(
                            entry = entry,
                            art = if (posters) {
                                downloadPoster(entry, sections) ?: downloadArt(entry, sections)
                            } else {
                                downloadArt(entry, sections)
                            },
                            watched = watchedFraction(progress[entry.videoId]),
                            selected = entry.videoId == selectedVideoId,
                            showPlay = !selecting || entry.videoId == selectedVideoId,
                            onClick = { if (selecting) onSelect(entry) else onPlay(entry) },
                            onPlay = { onPlay(entry) },
                            menu = menus?.let { { it.openDownload(entry) } },
                            poster = posters,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // A short last row keeps its cells the width of the rest.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Below this the queue panel stacks storage under the chart rather than beside it. */
private val WideQueuePanelFrom = 600.dp

/** Between the queue panel and the section under it, the design's 20dp. */
private val SectionGap = HaloSpacing.Md + HaloSpacing.Xs

/** Narrowest phone poster: three fit across a 360dp phone, more only sideways. */
private val ReadyPosterMinWidth = 96.dp

/** Sideways a phone could fit more posters, each too small to read at a phone's distance. */
private const val PhoneMaxReadyPosters = 4

/** The design's 28dp margin on a tablet, the phone's usual 16 below that. */
private fun downloadsGutter(responsive: ResponsiveInfo): Dp =
    responsive.pick(phone = HaloSpacing.Md, tablet = HaloSpacing.Lg + HaloSpacing.Xs)

/**
 * Stops everything that is running, or starts everything that was stopped.
 *
 * Resume deliberately leaves failures alone: retrying one is a decision about a
 * source that did not work, and belongs to the card's own control rather than to
 * a button that means "carry on".
 */
private suspend fun applyQueueControl(downloads: DeviceDownloadRuntime, active: List<DownloadEntry>) {
    when (queueControl(active)) {
        QueueControl.PauseAll -> active.filter { it.status.isActive }
            .forEach { downloads.pause(it.videoId) }

        QueueControl.ResumeAll -> active.filter { it.status == DownloadStatus.Paused }
            .forEach { downloads.resume(it.videoId) }

        null -> Unit
    }
}

/**
 * The screen's title and what is in it, over a scrim the list passes under.
 *
 * The summary counts states rather than bytes: what the downloads weigh is the
 * storage readout's job, where it is also placed against the room left. The
 * queue control sits at the end of the title line because it acts on every
 * row at once, so it belongs to none of them.
 *
 * [stacked] puts the summary under the title, which is all a phone has room
 * for once the control shares the title's line.
 *
 * A gradient rather than a frosted surface: the shell offers the whole
 * navigation host as its blur source, and a surface cannot sample a backdrop it
 * is part of (see `LocalHazeState`). The gradient gives the same legibility
 * without a blur that would come out clear.
 */
@Composable
private fun DownloadsHeader(
    gutter: Dp,
    summary: String,
    stacked: Boolean,
    control: QueueControl?,
    onControl: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HaloColors.Background)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HaloSpacing.Xs,
                    start = gutter,
                    end = gutter,
                    bottom = HaloSpacing.Xs,
                ),
            verticalArrangement = Arrangement.spacedBy(HaloSpacing.Xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = ControlTarget),
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = DownloadsTitle,
                        style = HaloType.LargeTitle,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (!stacked && summary.isNotEmpty()) {
                        Text(
                            text = summary,
                            color = HaloColors.TextDim,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
                control?.let { QueueControlButton(it, onControl, iconOnly = stacked) }
            }
            if (stacked && summary.isNotEmpty()) {
                Text(text = summary, color = HaloColors.TextDim, fontSize = 14.sp, maxLines = 2)
            }
        }
        // A separate strip rather than a gradient behind the words: the fade has
        // to end below the title, and a gradient measured as a fraction of the
        // header would climb into it as the header grows.
        Box(
            Modifier
                .fillMaxWidth()
                .height(HeaderFade)
                .background(
                    Brush.verticalGradient(
                        listOf(HaloColors.Background, HaloColors.Background.copy(alpha = 0f)),
                    ),
                ),
        )
    }
}

/** How far the header's cover trails off over the list passing under it. */
private val HeaderFade = HaloSpacing.Md

/** Header over whatever stands in for the list, carrying the list's own padding. */
@Composable
private fun DownloadsPlaceholder(responsive: ResponsiveInfo, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = responsive.contentInsetStart)) {
        DownloadsHeader(
            gutter = downloadsGutter(responsive),
            summary = "",
            stacked = !responsive.isTablet,
            control = null,
            onControl = {},
        )
        Box(Modifier.weight(1f)) { body() }
    }
}
