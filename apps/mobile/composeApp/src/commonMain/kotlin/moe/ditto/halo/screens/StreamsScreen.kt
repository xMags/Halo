package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.SignedInGraph
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.AddonError
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.api.MalformedResponseException
import moe.ditto.halo.api.SessionRejectedException
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamsResult
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadMedia
import moe.ditto.halo.downloads.DownloadStartResult
import moe.ditto.halo.downloads.sourceFingerprint
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.SelectOption
import moe.ditto.halo.ui.SelectSheet
import moe.ditto.halo.ui.formatBytes
import moe.ditto.halo.ui.rememberResponsive
import kotlinx.coroutines.launch

/**
 * Where a title's playable sources are chosen, grouped by the addon that
 * offered them.
 *
 * Nothing here filters: the server has already dropped torrents and
 * external-link results and omitted addons left with none, so an empty list
 * means nothing installed can play this, not that something was hidden. The
 * groups stay separate for the same reason search results do; which addon
 * vouched for a source is half of what makes it choosable.
 */
@Composable
internal fun StreamsScreen(
    graph: SignedInGraph,
    type: String,
    videoId: String,
    /** What is being played, for the header. An episode reads "Show · S01E02". */
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
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()

    Box(modifier.fillMaxSize().background(HaloColors.Background)) {
        Column(
            Modifier
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
        ) {
            // The header outlives every state below it: a screen that swaps itself
            // for a spinner takes its own way back with it.
            ScreenHeader(
                title = "Sources",
                subtitle = title,
                onBack = onBack,
                modifier = Modifier.padding(horizontal = HaloSpacing.Md),
            )
            SourcesPicker(
                graph = graph,
                type = type,
                videoId = videoId,
                onPlay = onPlay,
                downloadMedia = downloadMedia,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * One video's sources, with the download state that belongs to the video rather
 * than to any one row.
 *
 * Shared by the pushed picker and by the rail the tablet layout opens over a
 * title, so the two cannot drift apart in what a source does when it is tapped,
 * or in what happens when one is downloaded over another.
 */
@Composable
internal fun SourcesPicker(
    graph: SignedInGraph,
    type: String,
    videoId: String,
    onPlay: (AddonSource, Stream) -> Unit,
    downloadMedia: (AddonSource, Stream, String) -> DownloadMedia,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = HaloSpacing.Md,
        end = HaloSpacing.Md,
        bottom = HaloSpacing.Xl,
    ),
) {
    val streams by remember(graph, type, videoId) {
        graph.browse.streams(type, videoId)
    }.collectAsState(QueryState())
    val scope = rememberCoroutineScope()

    // One download per video, so this is the state of the whole picker's
    // subject rather than of any one row.
    val downloads by graph.downloads.entries.collectAsState()
    val download = remember(downloads, videoId) { downloads.firstOrNull { it.videoId == videoId } }
    var refusal by remember { mutableStateOf<String?>(null) }
    // A second source for a video already held is a replacement, and a
    // replacement throws away bytes someone waited for. Asked, never assumed.
    var pendingReplacement by remember { mutableStateOf<DownloadMedia?>(null) }

    Box(modifier) {
        StreamsContent(
            query = streams,
            onRetry = { scope.launch { graph.browse.retryStreams(type, videoId) } },
            onPlay = onPlay,
            download = download,
            // No column at all where the device has nowhere to keep downloads,
            // rather than a glyph that cannot do anything.
            downloadsAvailable = graph.downloads.isAvailable,
            onDownload = { addon, stream ->
                val url = stream.url
                if (url != null) {
                    val media = downloadMedia(addon, stream, url)
                    if (download != null && download.media.sourceFingerprint != sourceFingerprint(url)) {
                        pendingReplacement = media
                    } else {
                        scope.launch { refusal = beginDownload(graph, media) }
                    }
                }
            },
            refusal = refusal,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
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

private const val ReplaceKey = "replace"

/** Starts a download and returns what stopped it, or null when it took. */
private suspend fun beginDownload(graph: SignedInGraph, media: DownloadMedia): String? =
    when (val result = graph.downloads.start(media)) {
        is DownloadStartResult.NotEnoughSpace -> notEnoughSpaceMessage(result)
        is DownloadStartResult.Failed -> result.message
        else -> null
    }

private suspend fun beginReplacement(graph: SignedInGraph, media: DownloadMedia): String? =
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
        error.status == 401 -> "Your session is no longer valid. Sign in again."
        error.status >= 500 -> "Halo could not load sources right now."
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
    /** This video's download, if it has one. There is at most one per video. */
    download: DownloadEntry? = null,
    downloadsAvailable: Boolean = false,
    onDownload: (AddonSource, Stream) -> Unit = { _, _ -> },
    /** Why the last download was not accepted; shown above the sources. */
    refusal: String? = null,
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
    when (val state = streamsContentState(query)) {
        is StreamsContentState.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = HaloColors.Accent)
                Text(
                    text = "Asking your addons for sources…",
                    style = HaloType.Caption,
                    modifier = Modifier.padding(top = HaloSpacing.Md),
                )
            }
        }
        is StreamsContentState.RequestFailure -> FailurePanel(
            title = state.message,
            failures = emptyList(),
            busy = state.isFetching,
            onRetry = onRetry,
            modifier = modifier,
        )
        is StreamsContentState.AllAddonsFailed -> FailurePanel(
            title = "No addons could load sources.",
            failures = state.failures,
            busy = state.isFetching,
            onRetry = onRetry,
            modifier = modifier,
        )
        is StreamsContentState.NoSources -> CenterMessage(
            "No playable sources. Install a stream addon (e.g. a debrid-backed one) in Settings.",
            modifier,
        )
        // No tab-bar allowance in the default padding: this screen covers the
        // bar, and the rail it is shown in on a tablet has none to cover.
        is StreamsContentState.Sources -> LazyColumn(
            modifier = modifier,
            contentPadding = contentPadding,
        ) {
            if (refusal != null) {
                item(key = "download-refusal") {
                    Notice(text = refusal)
                }
            }
            if (state.failures.isNotEmpty() || state.refreshFailure != null) {
                item(key = "source-failures") {
                    FailureNotice(
                        failures = state.failures,
                        refreshFailure = state.refreshFailure,
                        busy = state.isFetching,
                        onRetry = onRetry,
                    )
                }
            }
            // Keyed by addon, so a slow one arriving late does not renumber
            // the groups already on screen.
            items(items = state.groups, key = { it.addon.id }) { group ->
                AddonGroup(
                    group = group,
                    onPlay = onPlay,
                    download = download,
                    downloadsAvailable = downloadsAvailable,
                    onDownload = onDownload,
                )
            }
        }
    }
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
private fun FailureNotice(
    failures: List<AddonError>,
    refreshFailure: String?,
    busy: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = HaloSpacing.Md)
            .clip(RoundedCornerShape(HaloRadius.Md))
            .background(HaloColors.SurfaceHigh)
            .border(1.dp, HaloColors.Border, RoundedCornerShape(HaloRadius.Md))
            .padding(HaloSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(HaloSpacing.Xs),
    ) {
        Text(
            text = refreshFailure ?: "Some addons could not load sources.",
            style = HaloType.Callout,
            color = HaloColors.Text,
        )
        failures.forEach { failure ->
            Text(text = safeAddonFailure(failure), style = HaloType.Caption)
        }
        RetryButton(busy = busy, onClick = onRetry)
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
    val candidate = error.name
        ?.filter { it.code >= 32 && it.code != 127 }
        ?.trim()
        ?.take(80)
        ?.takeIf { it.isNotEmpty() }
    val name = candidate
        ?.takeUnless {
            "://" in it || '/' in it || '\\' in it || CredentialLikeNameSegment.containsMatchIn(it)
        }
        ?: "An addon"
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

private val CredentialLikeNameSegment = Regex("[A-Za-z0-9_-]{32,}")

@Composable
private fun AddonGroup(
    group: AddonStreams,
    onPlay: (AddonSource, Stream) -> Unit,
    download: DownloadEntry?,
    downloadsAvailable: Boolean,
    onDownload: (AddonSource, Stream) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                StreamRow(
                    stream = stream,
                    fallbackName = group.addon.name,
                    onClick = { onPlay(group.addon, stream) },
                    // Only the row the download actually came from wears its
                    // state. Marking every row "downloaded" claims something
                    // about sources nobody fetched.
                    download = download?.takeIf {
                        stream.url?.let(::sourceFingerprint) == it.media.sourceFingerprint
                    },
                    heldElsewhere = download != null &&
                        stream.url?.let(::sourceFingerprint) != download.media.sourceFingerprint,
                    downloadsAvailable = downloadsAvailable,
                    onDownload = { onDownload(group.addon, stream) },
                )
            }
        }
    }
}

@Composable
private fun StreamRow(
    stream: Stream,
    fallbackName: String,
    onClick: () -> Unit,
    download: DownloadEntry?,
    heldElsewhere: Boolean,
    downloadsAvailable: Boolean,
    onDownload: () -> Unit,
) {
    val size = stream.behaviorHints?.videoSize?.let(::formatBytes).orEmpty()
    val detail = stream.title ?: stream.description
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = HaloSpacing.Md, top = HaloSpacing.Sm + 2.dp, bottom = HaloSpacing.Sm + 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
    ) {
        Column(Modifier.weight(1f)) {
            // Real results put quality, codec and cache state on separate lines
            // of one string. Clamping to a single line throws away the half a
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
        if (downloadsAvailable) {
            DownloadAction(download = download, heldElsewhere = heldElsewhere, onDownload = onDownload)
        } else {
            Box(Modifier.width(HaloSpacing.Md))
        }
    }
}

/**
 * The keep-it column at the end of a source row.
 *
 * [download] is this row's own download, so only the source that was actually
 * fetched wears a state; [heldElsewhere] says the video is held from a
 * different source, which dims this row's glyph without disabling it. Tapping
 * it then offers to replace what is on the device, because one download per
 * video is a rule about storage rather than a reason to refuse a better source.
 */
@Composable
private fun DownloadAction(download: DownloadEntry?, heldElsewhere: Boolean, onDownload: () -> Unit) {
    val column = Modifier.width(DownloadColumnWidth)
    val status = download?.status
    Box(column, contentAlignment = Alignment.Center) {
        when (status) {
            null, DownloadStatus.Failed -> Icon(
                imageVector = HaloIcons.Download,
                contentDescription = when {
                    status != null -> "Retry this download"
                    heldElsewhere -> "Download this source instead"
                    else -> "Download this source"
                },
                tint = when {
                    status != null -> HaloColors.Danger
                    heldElsewhere -> HaloColors.TextDim
                    else -> HaloColors.Accent
                },
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onDownload)
                    .padding(HaloSpacing.Xs)
                    .size(DownloadGlyphSize),
            )

            DownloadStatus.Done -> Icon(
                imageVector = HaloIcons.Check,
                contentDescription = "Downloaded",
                tint = HaloColors.Success,
                modifier = Modifier.padding(HaloSpacing.Xs).size(DownloadGlyphSize),
            )

            DownloadStatus.Downloading, DownloadStatus.Queued -> {
                val fraction = download.fraction
                if (fraction == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(DownloadGlyphSize),
                        color = HaloColors.Accent,
                        strokeWidth = 2.dp,
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.size(DownloadGlyphSize),
                        color = HaloColors.Accent,
                        strokeWidth = 2.dp,
                    )
                }
            }

            // Paused belongs to the Downloads tab, which is where it can be
            // resumed; here it only reports that the video is part-way there.
            DownloadStatus.Paused -> Icon(
                imageVector = HaloIcons.Download,
                contentDescription = "Download paused",
                tint = HaloColors.TextDim,
                modifier = Modifier.padding(HaloSpacing.Xs).size(DownloadGlyphSize),
            )
        }
    }
}

/** Wide enough for the glyph plus the row's trailing padding. */
private val DownloadColumnWidth = 50.dp
private val DownloadGlyphSize = 22.dp

/** A line above the sources explaining something the screen just refused to do. */
@Composable
private fun Notice(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = HaloSpacing.Md)
            .clip(RoundedCornerShape(HaloRadius.Md))
            .background(HaloColors.SurfaceHigh)
            .border(1.dp, HaloColors.Border, RoundedCornerShape(HaloRadius.Md))
            .padding(HaloSpacing.Md),
    ) {
        Text(text = text, style = HaloType.Callout, color = HaloColors.Text)
    }
}
