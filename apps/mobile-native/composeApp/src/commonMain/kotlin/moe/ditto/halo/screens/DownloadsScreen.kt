package moe.ditto.halo.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ditto.halo.SignedInGraph
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
 * how a transfer is going, and whether a file can be watched yet. So transfers
 * come first, with a rate, a bar and the minute of history that says whether the
 * link is steady; finished files follow as rows that play on a tap.
 *
 * Downloads are device-local, so this never waits on the network and never shows
 * a loading state: the index is read from the store when the session's graph is
 * built, which is why an entry is on screen the moment the tab is opened,
 * offline included. Watch progress is the one exception, and it is decoration —
 * the hairline across a poster is absent offline rather than the screen waiting
 * for it.
 */
@Composable
internal fun DownloadsScreen(
    graph: SignedInGraph,
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
                        Box(Modifier.fillMaxHeight().width(1.dp).background(HaloColors.Hairline))
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
    val gutter = responsive.pick(phone = HaloSpacing.Md, tablet = HaloSpacing.Lg, large = HaloSpacing.Xl)
    val transferPoster = responsive.pick(phone = TransferPosterWidth, tablet = TransferPosterWidthTablet)
    val readyPoster = responsive.pick(phone = ReadyPosterWidth, tablet = ReadyPosterWidthTablet)

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
            DownloadsHeader(gutter = gutter)
        }

        item(key = "throughput") {
            ThroughputPanel(
                bytesPerSecond = aggregateRate(entries),
                queueLine = queueLine(sections.active),
                control = queueControl(sections.active),
                onControl = {
                    scope.launch { applyQueueControl(downloads, sections.active) }
                },
                history = history,
                availableWidth = listWidth,
                modifier = Modifier.padding(horizontal = gutter, vertical = HaloSpacing.Sm),
            )
        }

        // No meter at all when the platform cannot say how much room there is.
        // A bar drawn over a guessed total would be a picture of nothing.
        space?.let { measured ->
            item(key = "storage") {
                StorageMeterBlock(
                    meter = storageMeter(measured, downloadedBytesOnDevice(entries)),
                    modifier = Modifier.padding(horizontal = gutter, vertical = HaloSpacing.Sm + 2.dp),
                )
            }
        }

        if (sections.active.isNotEmpty()) {
            item(key = "active-label") {
                DownloadsSectionHeader(
                    label = "IN PROGRESS",
                    count = itemCountLabel(sections.active.size),
                    modifier = Modifier.padding(
                        start = gutter,
                        end = gutter,
                        top = HaloSpacing.Sm,
                        bottom = HaloSpacing.Sm + 2.dp,
                    ),
                )
            }
            items(items = sections.active, key = { it.videoId }) { entry ->
                DownloadTransferCard(
                    entry = entry,
                    poster = downloadPoster(entry, sections),
                    posterWidth = transferPoster,
                    onPause = { scope.launch { downloads.pause(entry.videoId) } },
                    onResume = { scope.launch { downloads.resume(entry.videoId) } },
                    onRemove = { onRemove(entry) },
                    modifier = Modifier.padding(start = gutter, end = gutter, bottom = HaloSpacing.Sm + 2.dp),
                )
            }
        }

        if (sections.ready.isNotEmpty()) {
            item(key = "ready-label") {
                DownloadsSectionHeader(
                    label = "ON THIS DEVICE",
                    count = readyCountLabel(sections.ready),
                    modifier = Modifier.padding(
                        start = gutter,
                        end = gutter,
                        top = HaloSpacing.Md,
                        bottom = HaloSpacing.Sm + 2.dp,
                    ),
                )
            }
            readyRows(
                sections = sections,
                progress = progress,
                posterWidth = readyPoster,
                gutter = gutter,
                selectedVideoId = selectedVideoId,
                onSelect = onSelect,
                onPlay = onPlay,
                onRemove = onRemove,
            )
        }
    }
}

private fun LazyListScope.readyRows(
    sections: DownloadSections,
    progress: Map<String, WatchState>,
    posterWidth: Dp,
    gutter: Dp,
    selectedVideoId: String?,
    onSelect: (DownloadEntry) -> Unit,
    onPlay: (DownloadEntry) -> Unit,
    onRemove: (DownloadEntry) -> Unit,
) {
    items(items = sections.ready, key = { it.videoId }) { entry ->
        DownloadReadyRow(
            entry = entry,
            poster = downloadPoster(entry, sections),
            posterWidth = posterWidth,
            watched = watchedFraction(progress[entry.videoId]),
            selected = entry.videoId == selectedVideoId,
            // On a phone nothing is selected, so the row's own tap plays it;
            // on a tablet it selects, and the button beside it plays.
            onClick = { if (selectedVideoId == null) onPlay(entry) else onSelect(entry) },
            onPlay = { onPlay(entry) },
            onRemove = { onRemove(entry) },
            modifier = Modifier.padding(start = gutter, end = gutter, bottom = HaloSpacing.Sm),
        )
    }
}

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
 * The screen's title, over a scrim the list passes under.
 *
 * Only the title: how many files there are and what they weigh is the storage
 * meter's job a few rows down, where the same two figures are also placed
 * against the room left on the volume.
 *
 * A gradient rather than a frosted surface: the shell offers the whole
 * navigation host as its blur source, and a surface cannot sample a backdrop it
 * is part of — see `LocalHazeState`. The gradient gives the same legibility
 * without a blur that would come out clear.
 */
@Composable
private fun DownloadsHeader(gutter: Dp) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = DownloadsTitle,
            style = HaloType.LargeTitle,
            modifier = Modifier
                .fillMaxWidth()
                .background(HaloColors.Background)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HaloSpacing.Xs,
                    start = gutter,
                    end = gutter,
                    bottom = HaloSpacing.Sm + 6.dp,
                ),
        )
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
    val gutter = responsive.pick(phone = HaloSpacing.Md, tablet = HaloSpacing.Lg, large = HaloSpacing.Xl)
    Column(Modifier.fillMaxSize().padding(start = responsive.contentInsetStart)) {
        DownloadsHeader(gutter = gutter)
        Box(Modifier.weight(1f)) { body() }
    }
}
