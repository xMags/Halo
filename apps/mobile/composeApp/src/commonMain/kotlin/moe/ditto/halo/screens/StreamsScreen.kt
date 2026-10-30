package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.AddonError
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.api.MalformedResponseException
import moe.ditto.halo.api.SessionRejectedException
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamsResult
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.browse.SourceDetails
import moe.ditto.halo.browse.SourceEntry
import moe.ditto.halo.browse.SourceFilter
import moe.ditto.halo.browse.SourceSort
import moe.ditto.halo.browse.SourcesResolve
import moe.ditto.halo.browse.buildListing
import moe.ditto.halo.browse.detailsFor
import moe.ditto.halo.browse.filterCounts
import moe.ditto.halo.browse.matchesFilter
import moe.ditto.halo.browse.qualityMix
import moe.ditto.halo.browse.resolveSources
import moe.ditto.halo.browse.sanitizeAddonName
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadMedia
import moe.ditto.halo.downloads.DownloadStartResult
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.downloads.sourceFingerprint
import moe.ditto.halo.sync.runtimeMinutes
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.LocalHazeState
import moe.ditto.halo.ui.SelectOption
import moe.ditto.halo.ui.SelectSheet
import moe.ditto.halo.ui.formatBytes
import moe.ditto.halo.ui.glassSource
import moe.ditto.halo.ui.languageLabel
import moe.ditto.halo.ui.rememberResponsive

/**
 * Where a title's playable sources are chosen: the best one on its own card,
 * the rest ranked and grouped by the addon that offered them.
 *
 * Nothing here drops a source: the server has already removed torrents and
 * external-link results and omitted addons left with none, so an empty answer
 * means nothing installed can play this. The groups stay separate for the same
 * reason search results do; which addon vouched for a source is half of what
 * makes it choosable.
 */
@Composable
internal fun StreamsScreen(
    graph: SessionGraph,
    type: String,
    /** The title the video belongs to, for its runtime. */
    metaId: String,
    videoId: String,
    /** `SHOW · S01E02` above the heading; null for a film. */
    kicker: String?,
    title: String,
    onBack: () -> Unit,
    /**
     * The chosen source, with the addon that offered it: playback asks the same
     * addon for this title's subtitles and for what follows it, so which one
     * vouched for the source has to travel with the source.
     */
    onPlay: (AddonSource, Stream) -> Unit,
    /**
     * The download a chosen source would become. Built by the caller, which
     * holds the route naming what is being watched; this screen knows only the
     * sources.
     */
    downloadMedia: (AddonSource, Stream, String) -> DownloadMedia,
    onOpenPlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()

    Box(modifier.fillMaxSize().background(HaloColors.Background)) {
        SourcesPicker(
            graph = graph,
            type = type,
            metaId = metaId,
            videoId = videoId,
            onPlay = onPlay,
            downloadMedia = downloadMedia,
            onOpenPlayback = onOpenPlayback,
            heading = SourcesHeading(kicker = kicker, title = title, onBack = onBack),
            modifier = Modifier
                .fillMaxHeight()
                // A source list stretched across a tablet puts the size of a
                // stream a foot away from its name. Centred in the content area
                // rather than in the window, so the navigation rail's column is
                // not counted as space to centre in.
                .padding(start = responsive.contentInsetStart)
                .then(
                    responsive.contentMaxWidth?.let { Modifier.widthIn(max = it).fillMaxWidth() }
                        ?: Modifier.fillMaxWidth(),
                )
                .align(Alignment.TopCenter),
        )
    }
}

/**
 * What saving one source means right now. There is at most one download per
 * video, so this is decided against that one download, never per row alone.
 */
internal sealed interface SaveState {
    /** The device has nowhere to keep downloads, so nothing is offered. */
    data object Unavailable : SaveState

    /** [replaces] when the video is already held from another source. */
    data class Ready(val replaces: Boolean) : SaveState

    data class Saving(val fraction: Float?) : SaveState

    data object Paused : SaveState

    data object Saved : SaveState

    data object Failed : SaveState
}

/**
 * Only the source the download actually came from wears its state; any other
 * source can be saved instead, which replaces it after asking.
 */
internal fun saveStateOf(stream: Stream, download: DownloadEntry?, available: Boolean): SaveState {
    if (!available) return SaveState.Unavailable
    if (download == null) return SaveState.Ready(replaces = false)
    if (stream.url?.let(::sourceFingerprint) != download.media.sourceFingerprint) return SaveState.Ready(replaces = true)
    return when (download.status) {
        DownloadStatus.Done -> SaveState.Saved
        DownloadStatus.Downloading, DownloadStatus.Queued -> SaveState.Saving(download.fraction)
        // Paused belongs to the Downloads tab, which is where it can be resumed.
        DownloadStatus.Paused -> SaveState.Paused
        DownloadStatus.Failed -> SaveState.Failed
    }
}

/** The page's own choices, held above the list so the sheets beside it can change them. */
@Stable
internal class SourcesView {
    var filter by mutableStateOf(SourceFilter.All)
    var sort by mutableStateOf(SourceSort.Recommended)

    /** Shows the sources that must download first, which Recommended keeps behind one row. */
    var revealAll by mutableStateOf(false)

    /** Non-null while the "how this list was built" sheet is open. */
    var howBuilt by mutableStateOf<HowBuiltFacts?>(null)
}

/**
 * One video's sources, with the download state that belongs to the video rather
 * than to any one row, and the sheets that open over the list.
 *
 * Shared by the pushed picker and by the rail the tablet layout opens over a
 * title, so the two cannot drift apart in what a source does when it is tapped,
 * or in what happens when one is downloaded over another.
 */
@Composable
internal fun SourcesPicker(
    graph: SessionGraph,
    type: String,
    metaId: String,
    videoId: String,
    onPlay: (AddonSource, Stream) -> Unit,
    downloadMedia: (AddonSource, Stream, String) -> DownloadMedia,
    onOpenPlayback: () -> Unit,
    modifier: Modifier = Modifier,
    /** The pushed screen's heading; the rail draws its own. */
    heading: SourcesHeading? = null,
    contentPadding: PaddingValues = PaddingValues(
        start = HaloSpacing.Md,
        end = HaloSpacing.Md,
        bottom = HaloSpacing.Xl,
    ),
) {
    val streams by remember(graph, type, videoId) {
        graph.browse.streams(type, videoId)
    }.collectAsState(QueryState())
    // Usually already cached by the title page; only the runtime is read, for
    // what each file asks of the line.
    val meta by remember(graph, type, metaId) { graph.browse.meta(type, metaId) }.collectAsState(QueryState())
    val settings by remember(graph) { graph.settings.observe() }.collectAsState(QueryState())
    val scope = rememberCoroutineScope()

    // One download per video, so this is the state of the whole picker's
    // subject rather than of any one row.
    val downloads by graph.downloads.entries.collectAsState()
    val download = remember(downloads, videoId) { downloads.firstOrNull { it.videoId == videoId } }
    // A finished copy leads the list: nothing an addon offers starts faster.
    val menus = LocalTitleMenus.current
    val offline = download?.takeIf { it.status == DownloadStatus.Done && menus != null }
    val downloadsAvailable = graph.downloads.isAvailable
    var refusal by remember { mutableStateOf<String?>(null) }
    // A second source for a video already held is a replacement, and a
    // replacement throws away bytes someone waited for. Asked, never assumed.
    var pendingReplacement by remember { mutableStateOf<DownloadMedia?>(null) }

    val view = remember(videoId) { SourcesView() }
    var sortOpen by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<SourceDetailsRequest?>(null) }

    val saveState = { entry: SourceEntry -> saveStateOf(entry.stream, download, downloadsAvailable) }
    val save = { entry: SourceEntry ->
        val url = entry.stream.url
        if (url != null) {
            val media = downloadMedia(entry.addon, entry.stream, url)
            if (download != null && download.media.sourceFingerprint != sourceFingerprint(url)) {
                pendingReplacement = media
            } else {
                scope.launch { refusal = beginDownload(graph, media) }
            }
        }
    }

    // The sheets frost the source list, so the list is this picker's own blur
    // source; a sheet inside a source it samples comes out clear. See
    // `LocalHazeState`.
    val bodyHaze = rememberHazeState()

    CompositionLocalProvider(LocalHazeState provides bodyHaze) {
        Box(modifier) {
            StreamsContent(
                query = streams,
                onRetry = { scope.launch { graph.browse.retryStreams(type, videoId) } },
                onPlay = onPlay,
                heading = heading,
                saveStateOf = saveState,
                onSave = save,
                refusal = refusal,
                offline = offline,
                onPlayOffline = { entry -> menus?.playDownload(entry) },
                runtimeSeconds = (runtimeMinutes(meta.value?.runtime) ?: 0) * 60.0,
                view = view,
                onOpenSort = { sortOpen = true },
                onOpenDetails = { entry, read -> details = SourceDetailsRequest(entry, read) },
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize().glassSource(bodyHaze),
            )

            SelectSheet(
                visible = sortOpen,
                title = "Sort sources",
                options = SourceSort.entries.map {
                    SelectOption(key = it.name, label = it.label, selected = it == view.sort)
                },
                onSelect = { key ->
                    sortOpen = false
                    SourceSort.entries.firstOrNull { it.name == key }?.let { view.sort = it }
                },
                onClose = { sortOpen = false },
            )

            SourceDetailsSheet(
                request = details,
                saveStateOf = saveState,
                onPlay = { entry -> onPlay(entry.addon, entry.stream) },
                onSave = save,
                onDismiss = { details = null },
            )

            HowBuiltSheet(
                facts = view.howBuilt,
                rules = choiceRules(settings.value),
                onOpenPlayback = onOpenPlayback,
                onDismiss = { view.howBuilt = null },
            )

            val replacement = pendingReplacement
            SelectSheet(
                visible = replacement != null,
                title = "Replace the download?",
                description = "This video is already downloaded from another source.",
                options = listOf(
                    SelectOption(
                        key = ReplaceKey,
                        label = "Download this source instead",
                        detail = "The file already on the device is deleted first.",
                        destructive = true,
                    ),
                ),
                onSelect = {
                    val media = replacement
                    pendingReplacement = null
                    if (media != null) {
                        scope.launch {
                            refusal = beginReplacement(graph, media)
                        }
                    }
                },
                onClose = { pendingReplacement = null },
            )
        }
    }
}

/** The sheet's "How Halo chose": the settings that decide what plays, as they stand. */
private fun choiceRules(settings: UserSettings?): List<ChoiceRule> = listOf(
    ChoiceRule("Prefer sources that play instantly", "On"),
    ChoiceRule("Preferred audio language", settings?.preferredAudioLang?.let(::languageLabel) ?: "Automatic"),
    ChoiceRule("Preferred subtitles", settings?.preferredSubtitleLang?.let(::languageLabel) ?: "Off"),
    ChoiceRule("Autoplay next episode", if (settings?.autoplayNextEpisode == false) "Off" else "On"),
)

private const val ReplaceKey = "replace"

/** Starts a download and returns what stopped it, or null when it took. */
private suspend fun beginDownload(graph: SessionGraph, media: DownloadMedia): String? =
    when (val result = graph.downloads.start(media)) {
        is DownloadStartResult.NotEnoughSpace -> notEnoughSpaceMessage(result)
        is DownloadStartResult.Failed -> result.message
        else -> null
    }

private suspend fun beginReplacement(graph: SessionGraph, media: DownloadMedia): String? =
    when (val result = graph.downloads.replace(media)) {
        is DownloadStartResult.NotEnoughSpace -> notEnoughSpaceMessage(result)
        is DownloadStartResult.Failed -> result.message
        else -> null
    }

internal fun notEnoughSpaceMessage(result: DownloadStartResult.NotEnoughSpace): String =
    "This source needs ${formatBytes(result.requiredBytes)} and the device has " +
        "${formatBytes(result.freeBytes)} free."

/** Pure rendering states for the source request, independent of the app graph. */
internal sealed interface StreamsContentState {
    val isFetching: Boolean

    data class Loading(override val isFetching: Boolean) : StreamsContentState

    data class RequestFailure(
        val message: String,
        override val isFetching: Boolean,
    ) : StreamsContentState

    data class AllAddonsFailed(
        val failures: List<AddonError>,
        override val isFetching: Boolean,
    ) : StreamsContentState

    data class NoSources(override val isFetching: Boolean) : StreamsContentState

    data class Sources(
        val groups: List<AddonStreams>,
        val failures: List<AddonError>,
        val refreshFailure: String?,
        override val isFetching: Boolean,
    ) : StreamsContentState
}

internal fun streamsContentState(query: QueryState<StreamsResult>): StreamsContentState {
    val value = query.value
    if (value == null) {
        val error = query.error
        return if (error == null) {
            StreamsContentState.Loading(query.isFetching)
        } else {
            StreamsContentState.RequestFailure(safeStreamsRequestMessage(error), query.isFetching)
        }
    }
    if (value.results.isEmpty() && value.errors.isNotEmpty()) {
        return StreamsContentState.AllAddonsFailed(value.errors, query.isFetching)
    }
    if (value.results.isEmpty()) return StreamsContentState.NoSources(query.isFetching)
    return StreamsContentState.Sources(
        groups = value.results,
        failures = value.errors,
        refreshFailure = query.error?.let(::safeStreamsRequestMessage),
        isFetching = query.isFetching,
    )
}

internal fun safeStreamsRequestMessage(error: Throwable): String = when (error) {
    is SessionRejectedException -> "Your session is no longer valid. Sign in again."
    is MalformedResponseException -> "Halo returned an invalid source response."
    is HaloApiException -> when {
        // Survived a successful refresh, so the session is kept; see HaloApiException.
        error.status == 401 -> "Halo did not accept your sign-in just now. Try again in a moment."
        error.status >= 500 -> "Halo could not load sources right now."
        // A plan refusal is written for the person watching; other server text is not.
        error.message.orEmpty().startsWith("Your plan") -> error.message.orEmpty()
        else -> "Halo rejected the source request."
    }
    else -> "Could not reach your Halo server."
}

@Composable
internal fun StreamsContent(
    query: QueryState<StreamsResult>,
    onRetry: () -> Unit,
    onPlay: (AddonSource, Stream) -> Unit,
    modifier: Modifier = Modifier,
    heading: SourcesHeading? = null,
    saveStateOf: (SourceEntry) -> SaveState = { SaveState.Unavailable },
    onSave: (SourceEntry) -> Unit = {},
    /** Why the last download was not accepted; shown above the sources. */
    refusal: String? = null,
    /**
     * This video's finished download. It leads the list, and shows above any
     * loading or failure too: a saved file plays whatever the addons answer.
     */
    offline: DownloadEntry? = null,
    onPlayOffline: (DownloadEntry) -> Unit = {},
    /** The video's length, for what each file asks of the line; zero when unknown. */
    runtimeSeconds: Double = 0.0,
    view: SourcesView = remember { SourcesView() },
    onOpenSort: () -> Unit = {},
    onOpenDetails: (SourceEntry, SourceDetails) -> Unit = { _, _ -> },
    /**
     * The list's own insets. The rail sets its own, being a narrower surface
     * with its header outside the scroll; the pushed screen takes the default.
     */
    contentPadding: PaddingValues = PaddingValues(
        start = HaloSpacing.Md,
        end = HaloSpacing.Md,
        bottom = HaloSpacing.Xl,
    ),
) {
    val state = streamsContentState(query)
    val answer = state as? StreamsContentState.Sources
    val resolve = remember(answer?.groups, answer?.failures, runtimeSeconds) {
        answer?.let { resolveSources(it.groups, it.failures, runtimeSeconds) }
    }

    // The heading outlives every state below it: a screen that swaps itself
    // for a spinner takes its own way back with it.
    Column(modifier) {
        heading?.let {
            SourcesHeader(
                heading = it,
                countLine = when (state) {
                    is StreamsContentState.Loading -> "Asking your addons…"
                    is StreamsContentState.Sources -> resolve?.summary
                    else -> null
                },
            )
        }
        val body = Modifier.fillMaxWidth().weight(1f)
        if (offline != null && state !is StreamsContentState.Sources) {
            Box(Modifier.padding(contentPadding.horizontalOnly())) {
                OfflineCard(entry = offline, onPlay = { onPlayOffline(offline) })
            }
        }
        when (state) {
            is StreamsContentState.Loading -> Box(body, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = HaloColors.AccentLight)
            }
            is StreamsContentState.RequestFailure -> FailurePanel(
                title = state.message,
                failures = emptyList(),
                busy = state.isFetching,
                onRetry = onRetry,
                modifier = body,
            )
            is StreamsContentState.AllAddonsFailed -> FailurePanel(
                title = "No addons could load sources.",
                failures = state.failures,
                busy = state.isFetching,
                onRetry = onRetry,
                modifier = body,
            )
            is StreamsContentState.NoSources -> CenterMessage(NoSourcesMessage, body)
            is StreamsContentState.Sources ->
                if (resolve == null || resolve.pool.isEmpty()) {
                    CenterMessage(NoSourcesMessage, body)
                } else {
                    SourcesList(
                        state = state,
                        resolve = resolve,
                        view = view,
                        offline = offline,
                        onPlayOffline = onPlayOffline,
                        refusal = refusal,
                        onRetry = onRetry,
                        onPlay = { entry -> onPlay(entry.addon, entry.stream) },
                        saveStateOf = saveStateOf,
                        onSave = onSave,
                        onOpenSort = onOpenSort,
                        onOpenDetails = onOpenDetails,
                        contentPadding = contentPadding,
                        insetNavigationBar = heading != null,
                        modifier = body,
                    )
                }
        }
    }
}

private const val NoSourcesMessage =
    "No playable sources. Install a stream addon (e.g. a debrid-backed one) in Settings."

@Composable
private fun ColumnScope.SourcesList(
    state: StreamsContentState.Sources,
    resolve: SourcesResolve,
    view: SourcesView,
    /** This video's finished download, shown above everything addons offer. */
    offline: DownloadEntry?,
    onPlayOffline: (DownloadEntry) -> Unit,
    refusal: String?,
    onRetry: () -> Unit,
    onPlay: (SourceEntry) -> Unit,
    saveStateOf: (SourceEntry) -> SaveState,
    onSave: (SourceEntry) -> Unit,
    onOpenSort: () -> Unit,
    onOpenDetails: (SourceEntry, SourceDetails) -> Unit,
    contentPadding: PaddingValues,
    insetNavigationBar: Boolean,
    modifier: Modifier,
) {
    val listing = remember(resolve, view.filter, view.sort, view.revealAll) {
        buildListing(resolve, view.filter, view.sort, view.revealAll)
    }
    val counts = remember(resolve) { filterCounts(resolve.pool) }
    // The meter compares a file with the heaviest one the viewer could pick
    // under this filter, hidden ones included.
    val heaviest = remember(resolve, view.filter) {
        resolve.pool.filter { matchesFilter(it, view.filter) }.maxOfOrNull { it.neededMbps } ?: 0.0
    }
    val openDetails = { entry: SourceEntry -> onOpenDetails(entry, detailsFor(entry, heaviest)) }
    val failureText = state.refreshFailure ?: when (state.failures.size) {
        0 -> null
        1 -> safeAddonFailure(state.failures.single())
        else -> "${state.failures.size} addons could not load sources."
    }

    // No tab-bar allowance in the default padding: this screen covers the
    // bar, and the rail it is shown in on a tablet has none to cover.
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        offline?.let { entry ->
            item(key = "offline") { OfflineCard(entry = entry, onPlay = { onPlayOffline(entry) }) }
        }
        if (refusal != null) {
            item(key = "download-refusal") { RefusalNotice(refusal) }
        }
        if (failureText != null) {
            item(key = "source-failures") {
                FailureBanner(text = failureText, busy = state.isFetching, onRetry = onRetry)
            }
        }
        item(key = "filters") {
            FilterRow(
                filter = view.filter,
                counts = counts,
                onFilter = { view.filter = it },
                sortLabel = view.sort.label,
                onOpenSort = onOpenSort,
            )
        }
        if (listing.empty) {
            item(key = "empty-filter") {
                EmptyFilter(filter = view.filter, onShowAll = { view.filter = SourceFilter.All })
            }
        }
        listing.pick?.let { pick ->
            item(key = "pick") {
                PickCard(
                    entry = pick,
                    save = saveStateOf(pick),
                    onPlay = { onPlay(pick) },
                    onSave = { onSave(pick) },
                    onDetails = { openDetails(pick) },
                )
            }
        }
        // Keyed by addon and source, so a slow addon arriving late does not
        // renumber the groups already on screen.
        listing.groups.forEach { group ->
            item(key = "group:${group.addon.id}") {
                GroupHeading(name = group.name, count = group.entries.size)
            }
            items(items = group.entries, key = { it.key }) { entry ->
                SourceRow(
                    entry = entry,
                    save = saveStateOf(entry),
                    onPlay = { onPlay(entry) },
                    onDetails = { openDetails(entry) },
                )
            }
        }
        if (listing.hiddenCount > 0) {
            item(key = "more") {
                MoreButton(count = listing.hiddenCount, onClick = { view.revealAll = true })
            }
        }
    }
    HowBuiltBar(
        onOpen = { view.howBuilt = HowBuiltFacts(resolve.providers, qualityMix(resolve.pool)) },
        insetNavigationBar = insetNavigationBar,
    )
}

@Composable
private fun FailurePanel(
    title: String,
    failures: List<AddonError>,
    busy: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(HaloSpacing.Lg), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
        ) {
            Text(text = title, style = HaloType.Body, color = HaloColors.TextDim)
            failures.forEach { failure ->
                Text(text = safeAddonFailure(failure), style = HaloType.Caption)
            }
            RetryButton(busy = busy, onClick = onRetry)
        }
    }
}

@Composable
private fun RetryButton(busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.semantics {
            contentDescription = if (busy) "Retrying sources" else "Retry sources"
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = HaloColors.Accent,
            contentColor = HaloColors.OnAccent,
            disabledContainerColor = HaloColors.Accent.copy(alpha = 0.6f),
            disabledContentColor = HaloColors.OnAccent,
        ),
        shape = RoundedCornerShape(HaloRadius.Md),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.height(18.dp),
                color = HaloColors.OnAccent,
                strokeWidth = 2.dp,
            )
        } else {
            Text("Retry", fontWeight = FontWeight.Bold)
        }
    }
}

/** Uses only safe compatibility fields. Opaque ids and legacy messages never reach the screen. */
internal fun safeAddonFailure(error: AddonError): String {
    val name = sanitizeAddonName(error.name)
    return when (error.code) {
        "timeout" -> "$name timed out."
        "upstream_http" -> error.status
            ?.takeIf { it in 100..599 }
            ?.let { "$name returned HTTP $it." }
            ?: "$name returned an HTTP error."
        "blocked_target" -> "$name was blocked for safety."
        "invalid_response" -> "$name returned invalid data."
        else -> "$name is unavailable."
    }
}

/** The padding's sides alone, for a block that sits above a list using it. */
@Composable
private fun PaddingValues.horizontalOnly(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(start = calculateStartPadding(direction), end = calculateEndPadding(direction))
}
