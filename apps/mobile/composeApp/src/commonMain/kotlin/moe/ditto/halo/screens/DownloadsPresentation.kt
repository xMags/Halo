package moe.ditto.halo.screens

import androidx.compose.ui.unit.Dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamBehaviorHints
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.browse.MedalFinish
import moe.ditto.halo.browse.medalFor
import moe.ditto.halo.browse.parseSourceInfo
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.downloads.StorageSpace
import moe.ditto.halo.screens.player.streamBadges
import moe.ditto.halo.ui.formatBytes
import moe.ditto.halo.ui.formatSpeed
import moe.ditto.halo.ui.languageLabel
import kotlin.time.Instant

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
 * The screen's two lists: the queue, which is everything still arriving in any
 * state, and the grid of what is on the device.
 *
 * Ordering comes from [groupDownloads] rather than from a sort of its own, so a
 * season stays in episode order and the most recent title stays on top. Both
 * lists are flat, but in the order the grouped screen settled on, which keeps a
 * season's episodes side by side in the grid without a header per show.
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
        // The title that finished last leads, whenever it was started; a
        // show's episodes stay together, in the order they run.
        ready = groups
            .mapNotNull { group ->
                val done = group.entries.filter { it.status == DownloadStatus.Done }
                done.takeIf { it.isNotEmpty() }?.let { it to it.maxOf(::finishedAt) }
            }
            .sortedByDescending { (_, latest) -> latest }
            .flatMap { (done, _) -> done },
        posters = groups.associate { it.itemId to it.poster },
    )
}

/**
 * When a finished entry finished. Entries from before that was recorded fall
 * back on their last update, which for a finished download is its finishing
 * or later, never its start.
 */
private fun finishedAt(entry: DownloadEntry): Long = entry.completedAt ?: entry.updatedAt

/** The art for one row: the video's own poster, else the title's. */
internal fun downloadPoster(entry: DownloadEntry, sections: DownloadSections): String? =
    entry.media.poster ?: sections.posters[entry.itemId]

/**
 * The landscape art the queue rows and grid cards are drawn with: the episode's
 * own still where the addon gave one, else the poster, which a 16:9 crop still
 * shows as this title rather than as a grey tile.
 */
internal fun downloadArt(entry: DownloadEntry, sections: DownloadSections): String? =
    entry.media.episodeThumbnail ?: downloadPoster(entry, sections)

/**
 * The line under the header: how many of each state there are, with the
 * states that have none left out. Empty when there is nothing at all, where
 * the empty screen's own message says it better.
 */
internal fun downloadsSummary(entries: List<DownloadEntry>): String {
    fun count(status: DownloadStatus) = entries.count { it.status == status }
    return listOf(
        count(DownloadStatus.Downloading) to "downloading",
        count(DownloadStatus.Queued) to "waiting",
        count(DownloadStatus.Paused) to "paused",
        count(DownloadStatus.Failed) to "failed",
        count(DownloadStatus.Done) to "ready",
    ).filter { (count, _) -> count > 0 }
        .joinToString(MetaSeparator) { (count, label) -> "$count $label" }
}

/**
 * How many connections the running transfers are receiving over, or null when
 * that is unknown: nothing is running, or a platform that cannot say is
 * running it. Unknown is never zero, because a pill reading "0 connections"
 * over a moving chart would be a false statement.
 */
internal fun aggregateConnections(entries: List<DownloadEntry>): Int? {
    val running = entries.filter { it.status == DownloadStatus.Downloading }
    if (running.isEmpty()) return null
    val counts = running.map { it.connections ?: return null }
    return counts.sum().takeIf { it > 0 }
}

/** `1` → `"1 connection"`, `6` → `"6 connections"`. */
internal fun connectionsLabel(count: Int): String = "$count ${if (count == 1) "connection" else "connections"}"

/**
 * The episode and its show, split for a queue row that sets the three in
 * different weights: the tag in mono, the show as the title, the episode's own
 * name dimmed after it. A film is only its title.
 */
internal data class QueueRowTitles(val tag: String?, val title: String, val subtitle: String?)

internal fun queueRowTitles(entry: DownloadEntry): QueueRowTitles = QueueRowTitles(
    tag = entry.media.episodeTag,
    title = entry.media.showTitle,
    subtitle = entry.media.episodeName.takeIf { entry.media.isEpisode },
)

/** What a grid card is called: a film by its name, an episode by its show and its own name. */
internal fun downloadCardTitle(entry: DownloadEntry): String =
    if (entry.media.isEpisode) {
        listOfNotNull(entry.media.showTitle, entry.media.episodeName).joinToString(MetaSeparator)
    } else {
        entry.media.showTitle
    }

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

/** The badge over a card's art: its label, and whether it earns the gold finish. */
internal data class DownloadQualityBadge(val label: String, val gold: Boolean)

/**
 * The source picker's quality medal (`4K HDR`, `1080p FHD`) for the badge over
 * a card's art, so a download reads the way its source did when it was chosen.
 * Gold is kept for the top tier, as it is there. Null when the source's naming
 * does not state a quality, rather than a badge that guesses.
 */
internal fun downloadQualityBadge(entry: DownloadEntry): DownloadQualityBadge? {
    val info = parseSourceInfo(
        Stream(
            name = entry.media.streamName,
            title = entry.media.streamTitle,
            behaviorHints = StreamBehaviorHints(filename = entry.media.filename),
        ),
    )
    if (info.quality == null) return null
    val medal = medalFor(info)
    val label = listOfNotNull(medal.plate, medal.tag).joinToString(" ")
    return DownloadQualityBadge(label = label, gold = medal.finish == MedalFinish.Gold)
}

/** The subtitle kept with a file, as a card's meta line ends: its language, or that there is none. */
internal fun downloadSubtitleTag(entry: DownloadEntry): String {
    val subtitle = entry.subtitle ?: return "NO SUBS"
    return subtitle.lang?.trim()?.takeIf { it.isNotEmpty() }?.uppercase() ?: "SUBS"
}

/** `2160p · HEVC · 20 GB · ENG`: what a finished file is, under its card. */
internal fun downloadCardMeta(entry: DownloadEntry): String = listOfNotNull(
    downloadQualityLabel(entry).takeIf { it.isNotEmpty() },
    downloadSizeLabel(entry).takeIf { it.isNotEmpty() },
    downloadSubtitleTag(entry),
).joinToString(MetaSeparator)

/**
 * A phone poster's shorter line: the size and the subtitle. The quality is on
 * the badge over the art, and a third of a phone's width has room for no more.
 */
internal fun downloadPosterMeta(entry: DownloadEntry): String = listOfNotNull(
    downloadSizeLabel(entry).takeIf { it.isNotEmpty() },
    downloadSubtitleTag(entry),
).joinToString(MetaSeparator)

/**
 * The mono line under the detail pane's title. An entry carries no year or
 * runtime, so this is what it is and, for an episode, which show it belongs to.
 */
internal fun downloadDetailMeta(entry: DownloadEntry): String =
    if (entry.media.isEpisode) {
        "SERIES$MetaSeparator${entry.media.showTitle.uppercase()}"
    } else {
        "MOVIE"
    }

/**
 * The figure at the top of the screen, in bytes per second.
 *
 * A sum rather than the one live entry's rate, even though the coordinator runs
 * one transfer at a time: the aggregate stays correct if that ever changes, and
 * with one transfer it *is* that transfer's rate. Only a downloading entry
 * contributes: a paused one keeps no rate, and a queued one has never had one.
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

/** The rate split into its number and its unit, which the panel sets at two sizes. */
internal fun rateParts(bytesPerSecond: Long): Pair<String, String> {
    val figure = rateFigure(bytesPerSecond)
    return figure.substringBeforeLast(' ') to figure.substringAfterLast(' ')
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

/** `0.48` → `"48%"`, or null when the source never said how big the file is. */
internal fun downloadPercentLabel(entry: DownloadEntry): String? =
    entry.fraction?.let { "${(it * 100).toInt()}%" }

/**
 * The mono figures under a queue row's bar, most important first. A running
 * transfer says how far, how much and how long; a stopped one says why it is
 * stopped before how far it got. Queued rows have none: their caption is all
 * there is to say.
 */
internal fun queueRowMeta(entry: DownloadEntry): List<String> {
    val progress = listOfNotNull(downloadBytesLabel(entry), downloadPercentLabel(entry))
    return when (entry.status) {
        DownloadStatus.Downloading ->
            progress.ifEmpty { listOf("Starting…") } + listOfNotNull(downloadEtaLabel(entry))
        DownloadStatus.Paused -> listOf("Paused") + progress
        DownloadStatus.Failed -> listOf("Failed") + progress
        DownloadStatus.Queued, DownloadStatus.Done -> emptyList()
    }
}

/** The caption a queued row carries instead of a bar: its turn has not come. */
internal fun queueRowCaption(entry: DownloadEntry): String? =
    "Up next".takeIf { entry.status == DownloadStatus.Queued }

/** `3` → `"3 items"`, for a section's right-hand count. */
internal fun itemCountLabel(count: Int): String = "$count ${if (count == 1) "item" else "items"}"

/** The same, with what those items weigh, since the finished section carries both. */
internal fun readyCountLabel(entries: List<DownloadEntry>): String {
    val bytes = entries.sumOf { entry -> entry.totalBytes.takeIf { it > 0 } ?: entry.downloadedBytes }
    val size = formatBytes(bytes)
    val count = itemCountLabel(entries.size)
    return if (size.isEmpty()) count else "$count$MetaSeparator$size"
}

/**
 * The finished section's caption. The device class is all the app knows of the
 * device, so a tablet is named as one and anything else stays a device, the
 * word the delete confirmation already uses.
 */
internal fun readySectionLabel(isTablet: Boolean): String = if (isTablet) "On this tablet" else "On this device"

/**
 * Columns in the finished grid: as many cells of at least [minCell] as fit
 * across [available] with [gap] between them, never fewer than one, and never
 * more than [maxColumns] where a layout caps it.
 */
internal fun readyGridColumns(available: Dp, minCell: Dp, gap: Dp, maxColumns: Int? = null): Int {
    val fit = ((available.value + gap.value) / (minCell.value + gap.value)).toInt().coerceAtLeast(1)
    return maxColumns?.let { fit.coerceAtMost(it.coerceAtLeast(1)) } ?: fit
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
internal fun downloadFacts(entry: DownloadEntry, nowMs: Long, zone: TimeZone): List<Pair<String, String>> = buildList {
    downloadQualityLabel(entry).takeIf { it.isNotEmpty() }?.let { add("Quality" to it) }
    downloadSizeLabel(entry).takeIf { it.isNotEmpty() }?.let { add("Size" to it) }
    entry.subtitle?.lang?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Subtitle" to languageLabel(it)) }
    downloadAddedLabel(entry.createdAt, nowMs, zone)?.let { add("Added" to it) }
}

/**
 * `29 Oct`, or `29 Oct 2025` outside the current year. Null for a timestamp
 * that cannot be a real one, which an entry migrated without a date carries.
 */
internal fun downloadAddedLabel(createdAt: Long, nowMs: Long, zone: TimeZone): String? {
    if (createdAt <= 0) return null
    val day = Instant.fromEpochMilliseconds(createdAt).toLocalDateTime(zone).date
    val year = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).year
    val label = "${day.day} ${ShortMonths[day.month.ordinal]}"
    return if (day.year == year) label else "$label ${day.year}"
}

private val ShortMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Two of these figures, and the third is what is left. */
internal data class StorageMeter(
    /** Share of the volume this app's downloads occupy, 0..1. */
    val downloadsFraction: Float,
    /** Share taken by everything else on the device, 0..1. */
    val otherFraction: Float,
    /** The big number: what the downloads weigh, without its unit. */
    val downloadsFigure: String,
    /** What follows it: the unit, and the volume it is part of. */
    val ofTotalLine: String,
    /** The legend's three values. */
    val downloadsLabel: String,
    val otherLabel: String,
    val freeLabel: String,
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
    val totalLabel = formatBytes(space.totalBytes)
    // A figure with nothing in it borrows the volume's unit, so an empty
    // library reads as "0 GB of 225 GB" rather than as a bare zero.
    val emptyLabel = "0 ${totalLabel.substringAfterLast(' ')}"
    val downloadsLabel = formatBytes(downloads).ifEmpty { emptyLabel }
    return StorageMeter(
        downloadsFraction = downloads / total,
        otherFraction = other / total,
        downloadsFigure = downloadsLabel.substringBeforeLast(' '),
        ofTotalLine = "${downloadsLabel.substringAfterLast(' ')} of $totalLabel",
        downloadsLabel = downloadsLabel,
        otherLabel = formatBytes(other).ifEmpty { emptyLabel },
        freeLabel = formatBytes(space.freeBytes).ifEmpty { emptyLabel },
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
 * The control the queue currently offers, or null when it offers neither:
 * nothing running to stop, and nothing stopped to start.
 */
internal fun queueControl(active: List<DownloadEntry>): QueueControl? = when {
    active.any { it.status.isActive } -> QueueControl.PauseAll
    active.any { it.status == DownloadStatus.Paused } -> QueueControl.ResumeAll
    else -> null
}
