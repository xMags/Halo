package moe.ditto.halo.screens

import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.ui.formatBytes
import moe.ditto.halo.ui.formatSpeed

/**
 * One title's downloads, as the screen shows them: a header for the title and
 * a row per video underneath it.
 *
 * A download belongs to its show rather than standing on its own, which is what
 * makes a season of a series read as a season rather than as twelve unrelated
 * files.
 */
internal data class DownloadGroup(
    val itemId: String,
    val type: String,
    val metaId: String,
    val name: String,
    val poster: String?,
    val entries: List<DownloadEntry>,
)

/**
 * Groups by library item, newest title first, and orders each group the way its
 * episodes run.
 *
 * Episode order comes from the tag rather than from when each was downloaded:
 * someone who filled a gap in a season last would otherwise find that episode
 * at the bottom. Films and anything untagged fall back to download order, which
 * is the only order they have.
 */
internal fun groupDownloads(entries: List<DownloadEntry>): List<DownloadGroup> {
    val byItem = LinkedHashMap<String, MutableList<DownloadEntry>>()
    entries.forEach { entry -> byItem.getOrPut(entry.itemId) { mutableListOf() }.add(entry) }
    return byItem.map { (itemId, group) ->
        val first = group.minByOrNull { it.createdAt } ?: group.first()
        DownloadGroup(
            itemId = itemId,
            type = first.media.type,
            metaId = first.media.metaId,
            name = first.media.showTitle,
            poster = group.firstNotNullOfOrNull { it.media.poster },
            entries = group.sortedWith(
                compareBy(
                    { it.media.episodeTag ?: "" },
                    { it.createdAt },
                ),
            ),
        )
    }.sortedByDescending { group -> group.entries.maxOf { it.createdAt } }
}

/** What one row is called: the episode, or the film's own name. */
internal fun downloadRowTitle(entry: DownloadEntry): String =
    entry.media.episodeTag?.let { tag ->
        entry.media.episodeName?.let { name -> "$tag · $name" } ?: tag
    } ?: entry.media.showTitle

/**
 * The line under a row's title. Bytes are shown while they are still arriving
 * and once they have; a failure shows what failed instead, because that is the
 * only thing the viewer can act on.
 */
internal fun downloadStatusLabel(entry: DownloadEntry): String = when (entry.status) {
    DownloadStatus.Done -> listOfNotNull(
        "Downloaded",
        formatBytes(entry.totalBytes.takeIf { it > 0 } ?: entry.downloadedBytes).ifEmpty { null },
    ).joinToString(" · ")

    DownloadStatus.Downloading -> {
        val progress = when {
            entry.totalBytes > 0 ->
                "${formatBytes(entry.downloadedBytes)} of ${formatBytes(entry.totalBytes)}"
            entry.downloadedBytes > 0 -> formatBytes(entry.downloadedBytes)
            else -> "Starting…"
        }
        // Speed and time only once they have been measured. A rate invented
        // from a single sample swings by a factor of several, and an estimate
        // that jumps between two minutes and twenty is worse than none.
        listOfNotNull(
            progress,
            formatSpeed(entry.bytesPerSecond),
            entry.secondsRemaining?.let { formatRemaining(it) },
        ).joinToString(" · ")
    }

    DownloadStatus.Queued -> "Waiting for the current download"
    DownloadStatus.Paused -> listOfNotNull(
        "Paused",
        formatBytes(entry.downloadedBytes).ifEmpty { null },
    ).joinToString(" · ")

    DownloadStatus.Failed -> entry.failureMessage ?: "This download did not finish."
}

/**
 * `195` → `"3 min left"`. Rounded up, and coarse on purpose: the estimate is
 * only as good as a rate that moves, and a readout counting individual seconds
 * down invites watching it rather than trusting it.
 */
internal fun formatRemaining(seconds: Long): String {
    if (seconds < 60) return "less than a minute left"
    val minutes = (seconds + 59) / 60
    if (minutes < 60) return "$minutes min left"
    val hours = minutes / 60
    val remainder = minutes % 60
    if (remainder == 0L) return "$hours h left"
    return "$hours h $remainder min left"
}

/**
 * What one title's downloads add up to, plus what they are still doing.
 *
 * Sizes count what is actually on the device rather than what a finished
 * library would weigh, so a paused download contributes the part of it that
 * has arrived.
 */
internal fun downloadGroupSummary(entries: List<DownloadEntry>): String {
    val parts = mutableListOf("${entries.size} ${if (entries.size == 1) "download" else "downloads"}")
    formatBytes(entries.sumOf(::bytesOnDevice)).takeIf { it.isNotEmpty() }?.let(parts::add)
    val active = entries.count { it.status.isActive }
    if (active > 0) parts.add("$active in progress")
    // One transfer runs at a time, so at most one row here has a rate, and it
    // is the rate of this title rather than a sum of unrelated numbers.
    entries.firstNotNullOfOrNull { formatSpeed(it.bytesPerSecond) }?.let(parts::add)
    return parts.joinToString(" · ")
}

private fun bytesOnDevice(entry: DownloadEntry): Long = when (entry.status) {
    DownloadStatus.Done -> entry.totalBytes.takeIf { it > 0 } ?: entry.downloadedBytes
    else -> entry.downloadedBytes
}
