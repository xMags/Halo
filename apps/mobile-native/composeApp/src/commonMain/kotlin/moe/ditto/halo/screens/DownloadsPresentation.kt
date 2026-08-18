package moe.ditto.halo.screens

import moe.ditto.halo.api.WatchState
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.downloads.StorageSpace
import moe.ditto.halo.screens.player.streamBadges
import moe.ditto.halo.ui.formatBytes
import moe.ditto.halo.ui.formatSpeed

/**
 * Everything the Downloads screen decides before it draws anything.
 *
 * Kept apart from the composables so the arithmetic behind the meter, the
 * sections and every figure on a card can be tested without a renderer. The
 * screen reads as layout because none of this is in it.
 */

/** How the design separates the dotted parts of a line. */
private const val MetaSeparator = " · "

/**
 * The screen's two lists: what is still arriving, and what is on the device.
 *
 * Ordering comes from [groupDownloads] rather than from a sort of its own, so a
 * season stays in episode order and the most recent title stays on top —
 * the list is flat here, but it is the same order the grouped screen settled on.
 */
internal data class DownloadSections(
    val active: List<DownloadEntry>,
    val ready: List<DownloadEntry>,
    /**
     * Title art per library item. An entry whose own media carries no poster
     * still shows the title's, which is what keeps one episode of a season from
     * being the only blank tile in the list.
     */
    val posters: Map<String, String?>,
)

internal fun downloadSections(entries: List<DownloadEntry>): DownloadSections {
    val groups = groupDownloads(entries)
    val ordered = groups.flatMap { it.entries }
    return DownloadSections(
        active = ordered.filterNot { it.status == DownloadStatus.Done },
        ready = ordered.filter { it.status == DownloadStatus.Done },
        posters = groups.associate { it.itemId to it.poster },
    )
}

/** The art for one row: the video's own poster, else the title's. */
internal fun downloadPoster(entry: DownloadEntry, sections: DownloadSections): String? =
    entry.media.poster ?: sections.posters[entry.itemId]

/**
 * The line under a card's title: the show, for an episode.
 *
 * Null for a film, whose name is already the title line — a second line
 * repeating it would only push the figures down.
 */
internal fun downloadRowSubtitle(entry: DownloadEntry): String? =
    entry.media.showTitle.takeIf { entry.media.isEpisode }

/**
 * What this file is, read out of the source's own naming by the player's badge
 * parser. Empty when the addon named the source after itself and nothing else,
 * which is the honest answer rather than a guessed resolution.
 */
internal fun downloadQualityLabel(entry: DownloadEntry): String =
    streamBadges(
        filename = entry.media.filename,
        title = entry.media.streamTitle,
        name = entry.media.streamName,
    ).joinToString(MetaSeparator)

/**
 * The figure at the top of the screen, in bytes per second.
 *
 * A sum rather than the one live entry's rate, even though the coordinator runs
 * one transfer at a time: the aggregate stays correct if that ever changes, and
 * with one transfer it *is* that transfer's rate. Only a downloading entry
 * contributes — a paused one keeps no rate, and a queued one has never had one.
 */
internal fun aggregateRate(entries: List<DownloadEntry>): Long =
    entries.filter { it.status == DownloadStatus.Downloading }.sumOf { it.bytesPerSecond }

/**
 * A rate as the screen spells it, including the zero.
 *
 * [formatSpeed] answers null below a measurable rate, which is right for a line
 * that omits what it cannot say; the throughput figure is a fixed readout that
 * has to hold its place, so a stopped link reads as stopped.
 */
internal fun rateFigure(bytesPerSecond: Long): String = formatSpeed(bytesPerSecond) ?: IdleRate

private const val IdleRate = "0.0 MB/s"

/** What sits where the rate does on a card, per status. */
internal fun downloadRateLabel(entry: DownloadEntry): String = when (entry.status) {
    DownloadStatus.Downloading -> rateFigure(entry.bytesPerSecond)
    // The queue is a state, not a slow transfer: showing 0.0 MB/s here would
    // read as a stalled link rather than as a turn not yet taken.
    DownloadStatus.Queued -> "Queued"
    DownloadStatus.Paused -> "Paused"
    DownloadStatus.Failed -> "Failed"
    DownloadStatus.Done -> "Downloaded"
}

/** True while bytes are actually moving, which is what every animation keys off. */
internal fun isTransferLive(entry: DownloadEntry): Boolean = entry.status == DownloadStatus.Downloading

/**
 * Bytes moved: both halves when the source declared a size, otherwise only what
 * has arrived. Null before anything has, where a `0 B` would claim a fact.
 */
internal fun downloadBytesLabel(entry: DownloadEntry): String? {
    val moved = formatBytes(entry.downloadedBytes)
    val total = formatBytes(entry.totalBytes)
    return when {
        total.isNotEmpty() && moved.isNotEmpty() -> "$moved of $total"
        total.isNotEmpty() -> "0 of $total"
        moved.isNotEmpty() -> moved
        else -> null
    }
}

/**
 * What a file weighs, once there is only one number worth giving: a finished
 * download reading "1.4 GB of 1.4 GB" says the same thing twice.
 */
internal fun downloadSizeLabel(entry: DownloadEntry): String =
    formatBytes(entry.totalBytes.takeIf { it > 0 } ?: entry.downloadedBytes)

/**
 * Time left, or null when there is nothing to say: only a running transfer with
 * a measured rate and a declared size has an estimate at all.
 */
internal fun downloadEtaLabel(entry: DownloadEntry): String? {
    if (entry.status != DownloadStatus.Downloading) return null
    return entry.secondsRemaining?.let(::formatRemaining)
}

/** `3` → `"3 items"`, for a section's right-hand count. */
internal fun itemCountLabel(count: Int): String = "$count ${if (count == 1) "item" else "items"}"

/** The same, with what those items weigh — the finished section carries both. */
internal fun readyCountLabel(entries: List<DownloadEntry>): String {
    val bytes = entries.sumOf { entry -> entry.totalBytes.takeIf { it > 0 } ?: entry.downloadedBytes }
    val size = formatBytes(bytes)
    val count = itemCountLabel(entries.size)
    return if (size.isEmpty()) count else "$count$MetaSeparator$size"
}

/** The queue line beside the throughput figure. */
internal fun queueLine(active: List<DownloadEntry>): String {
    if (active.isEmpty()) return "queue empty"
    val downloading = active.count { it.status == DownloadStatus.Downloading }
    val waiting = active.size - downloading
    if (downloading == 0) return "${itemCountLabel(active.size)} waiting"
    if (waiting == 0) return "1 transfer"
    return "1 transfer$MetaSeparator$waiting waiting"
}

/**
 * How far through this video the viewer is, for the hairline across a finished
 * row's poster. Null unless it is genuinely part-watched: an untouched download
 * has no line to draw, and a finished one is not a thing to resume.
 */
internal fun watchedFraction(state: WatchState?): Float? {
    if (state == null || state.watched || state.durationSec <= 0) return null
    val fraction = (state.positionSec / state.durationSec).coerceIn(0.0, 1.0).toFloat()
    return fraction.takeIf { it > MinVisibleWatched && it < 1f }
}

/** Below this the hairline is a speck that reads as a rendering artifact. */
private const val MinVisibleWatched = 0.02f

/** The facts table in the detail pane, holding only what an entry actually knows. */
internal fun downloadFacts(entry: DownloadEntry): List<Pair<String, String>> = buildList {
    downloadQualityLabel(entry).takeIf { it.isNotEmpty() }?.let { add("Quality" to it) }
    downloadSizeLabel(entry).takeIf { it.isNotEmpty() }?.let { add("File size" to it) }
    entry.subtitle?.lang?.let { add("Subtitle" to it) }
}

/** Two of these figures, and the third is what is left. */
internal data class StorageMeter(
    /** Share of the volume this app's downloads occupy, 0..1. */
    val downloadsFraction: Float,
    /** Share taken by everything else on the device, 0..1. */
    val otherFraction: Float,
    val usedLine: String,
    val freeLine: String,
)

/**
 * The meter over the volume the downloads live on.
 *
 * Downloads are clamped into what the volume reports as used: the entries'
 * byte counts and the two space figures are measured separately, and a rounding
 * disagreement between them must not push the other-apps segment negative.
 */
internal fun storageMeter(space: StorageSpace, downloadBytes: Long): StorageMeter {
    val used = (space.totalBytes - space.freeBytes).coerceAtLeast(0)
    val downloads = downloadBytes.coerceIn(0, used)
    val other = used - downloads
    val total = space.totalBytes.toFloat()
    return StorageMeter(
        downloadsFraction = downloads / total,
        otherFraction = other / total,
        usedLine = listOfNotNull(
            formatBytes(downloads).takeIf { it.isNotEmpty() }?.let { "$it of downloads" },
            formatBytes(used).takeIf { it.isNotEmpty() }?.let { "$it used" },
        ).joinToString(MetaSeparator),
        freeLine = "${formatBytes(space.freeBytes)} free of ${formatBytes(space.totalBytes)}",
    )
}

/** What the whole library weighs on the device, for the meter's downloads segment. */
internal fun downloadedBytesOnDevice(entries: List<DownloadEntry>): Long = entries.sumOf { entry ->
    when (entry.status) {
        DownloadStatus.Done -> entry.totalBytes.takeIf { it > 0 } ?: entry.downloadedBytes
        else -> entry.downloadedBytes
    }
}

/** Whether the whole-queue control pauses or resumes, and what it is called. */
internal enum class QueueControl(val label: String) {
    PauseAll("Pause all"),
    ResumeAll("Resume all"),
}

/**
 * The control the queue currently offers, or null when it offers neither —
 * nothing running to stop, and nothing stopped to start.
 */
internal fun queueControl(active: List<DownloadEntry>): QueueControl? = when {
    active.any { it.status.isActive } -> QueueControl.PauseAll
    active.any { it.status == DownloadStatus.Paused } -> QueueControl.ResumeAll
    else -> null
}
