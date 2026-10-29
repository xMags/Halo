package moe.ditto.halo.browse

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.WatchState
import kotlin.time.Instant

/*
 * The History view's rows: every video played, newest first, one row per
 * video (each episode of a show on its own line), read from the synced watch
 * history only. The saved library only lends a name or poster to rows written
 * before they carried their own. The desktop app's `src/history/historyRows.ts`;
 * keep the two in step.
 */

data class HistoryEntry(
    val itemId: String,
    val type: String,
    val metaId: String,
    val videoId: String,
    val name: String,
    val poster: String?,
    val positionSec: Double,
    val durationSec: Double,
    /** Watched, or so close to the end that nothing is left to return to. */
    val finished: Boolean,
    /** When it was last played, epoch milliseconds. */
    val updatedAt: Long,
)

data class HistoryGroup(val label: String, val entries: List<HistoryEntry>)

/** At or above this nothing is left worth returning to, flag or no flag. */
private const val FinishedFraction = 0.95

/** Watch rows as history entries, newest first, under the type filter. A row nothing names is left out. */
fun buildHistory(watchStates: List<WatchState>?, library: List<LibraryItem>?, filter: String?): List<HistoryEntry> {
    val libById = library.orEmpty().filterNot { it.isRemoved }.associateBy { it.id }
    return watchStates.orEmpty()
        .sortedByDescending { it.updatedAt }
        .mapNotNull { row ->
            val entry = libById[row.itemId]
            val name = row.name ?: entry?.name ?: return@mapNotNull null
            val type = historyItemType(row.itemId)
            if (!matchesTypeFilter(filter, type)) return@mapNotNull null
            HistoryEntry(
                itemId = row.itemId,
                type = type,
                metaId = historyMetaId(row.itemId),
                videoId = row.videoId,
                name = name,
                poster = row.poster ?: entry?.poster,
                positionSec = row.positionSec,
                durationSec = row.durationSec,
                finished = row.watched || (row.durationSec > 0 && row.positionSec / row.durationSec >= FinishedFraction),
                updatedAt = row.updatedAt,
            )
        }
}

/**
 * Splits newest-first entries into sections by the viewer's calendar: Today,
 * Yesterday, Earlier this week (the five days before), then one section per
 * month ("October 2026").
 */
fun groupHistory(entries: List<HistoryEntry>, nowMillis: Long, zone: TimeZone): List<HistoryGroup> {
    val today = localDate(nowMillis, zone)
    val groups = mutableListOf<Pair<String, MutableList<HistoryEntry>>>()
    for (entry in entries) {
        val day = localDate(entry.updatedAt, zone)
        val daysAgo = day.daysUntil(today)
        val label = when {
            daysAgo <= 0 -> "Today"
            daysAgo == 1 -> "Yesterday"
            daysAgo < 7 -> "Earlier this week"
            else -> "${MonthNames[day.month.ordinal]} ${day.year}"
        }
        val last = groups.lastOrNull()
        if (last != null && last.first == label) last.second += entry else groups += label to mutableListOf(entry)
    }
    return groups.map { (label, items) -> HistoryGroup(label, items) }
}

/**
 * When an entry was played, as its row prints it: the time of day within the
 * last two days, the weekday within the week, the date before that.
 */
fun historyTime(timestamp: Long, nowMillis: Long, zone: TimeZone): String {
    val played = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(zone)
    val daysAgo = played.date.daysUntil(localDate(nowMillis, zone))
    return when {
        daysAgo <= 1 -> "${played.hour.toString().padStart(2, '0')}:${played.minute.toString().padStart(2, '0')}"
        daysAgo < 7 -> DayNames.getValue(played.date.dayOfWeek)
        else -> "${played.date.day} ${MonthNames[played.date.month.ordinal].take(3)}"
    }
}

/**
 * The episode a video id names ("S01E02"), "MOVIE" for a film's own id, and
 * "SERIES" for an episode whose id does not carry its numbers. Meta ids may
 * contain colons, so the meta id is stripped as a prefix rather than split on.
 */
fun historyVideoTag(videoId: String, metaId: String): String {
    val prefix = "$metaId:"
    if (!videoId.startsWith(prefix)) return "MOVIE"
    val parts = videoId.removePrefix(prefix).split(':')
    val season = parts.getOrNull(0)?.toIntOrNull()
    val episode = parts.getOrNull(1)?.toIntOrNull()
    if (parts.size != 2 || season == null || episode == null) return "SERIES"
    return "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
}

/** A watch row's item id is `type:metaId`; a bare id with no prefix is a film, the oldest rows' shape. */
private fun historyItemType(itemId: String): String = itemId.substringBefore(':', missingDelimiterValue = "movie")

private fun historyMetaId(itemId: String): String = itemId.substringAfter(':', missingDelimiterValue = itemId)

private fun localDate(millis: Long, zone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date

private val MonthNames = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private val DayNames = mapOf(
    DayOfWeek.MONDAY to "Monday",
    DayOfWeek.TUESDAY to "Tuesday",
    DayOfWeek.WEDNESDAY to "Wednesday",
    DayOfWeek.THURSDAY to "Thursday",
    DayOfWeek.FRIDAY to "Friday",
    DayOfWeek.SATURDAY to "Saturday",
    DayOfWeek.SUNDAY to "Sunday",
)
