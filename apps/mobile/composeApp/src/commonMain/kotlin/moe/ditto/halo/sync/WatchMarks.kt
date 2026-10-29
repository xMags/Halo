package moe.ditto.halo.sync

import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState

/*
 * The rows a title menu writes to the watch history: marks (watched,
 * unwatched) and removals. The desktop app's `src/browse/watchMarks.ts`; keep
 * the two in step, since both write the same synced rows.
 */

/** The video a mark is about, with the display fields its row carries. */
data class WatchMarkTarget(
    val videoId: String,
    val itemId: String,
    /** The show's name for an episode, the title for a film: what the shelves print. */
    val name: String,
    val poster: String? = null,
)

/**
 * The row "Mark as watched" writes. A measured duration is kept; otherwise the
 * addon's runtime stands in, and with neither the duration stays 0, which every
 * reader takes as "length unknown". It is never invented.
 */
fun watchedRow(target: WatchMarkTarget, existing: WatchState?, runtimeSec: Double?, now: Long): WatchState {
    val durationSec = existing?.durationSec?.takeIf { it > 0 } ?: runtimeSec ?: 0.0
    return rowBase(target, existing, now).copy(positionSec = durationSec, durationSec = durationSec, watched = true)
}

/**
 * The row "Mark as unwatched" writes. It keeps the video in the history (a
 * removal, [removedRows], takes it out): no position and no length, which every
 * shelf and resume check reads as "never started", the same as a missing row.
 */
fun unwatchedRow(target: WatchMarkTarget, existing: WatchState?, now: Long): WatchState =
    rowBase(target, existing, now)

/**
 * Rows marking several videos watched at once, given first to last. Each is
 * stamped strictly later than the one before, so a shelf that follows a show's
 * newest row lands after the last of them.
 */
fun watchedRowsInOrder(
    targets: List<WatchMarkTarget>,
    existing: Map<String, WatchState>,
    runtimeSec: Double?,
    now: Long,
): List<WatchState> {
    var stamp = now
    return targets.map { target ->
        watchedRow(target, existing[target.videoId], runtimeSec, stamp).also { stamp = it.updatedAt + 1 }
    }
}

/**
 * The tombstones that remove videos from the watch history, which forgets
 * where they stopped and whether they were watched. The row otherwise stays as
 * it was: a server too old to know `removedAt` drops the field and stores the
 * row, and must not then wipe the progress of a video it goes on showing.
 */
fun removedRows(states: List<WatchState>, now: Long): List<WatchState> = states.map { state ->
    val stamp = maxOf(now, state.updatedAt + 1)
    state.copy(removedAt = stamp, updatedAt = stamp)
}

/**
 * The episodes before [video], first to last: every numbered episode of an
 * earlier season and the earlier ones of its own. Specials (season 0) only
 * count before another special. Addons without seasons (kitsu) put every
 * episode in season 0, where this is simply the episodes numbered lower.
 */
fun earlierEpisodes(videos: List<MetaVideo>, video: MetaVideo): List<MetaVideo> {
    val season = video.season ?: 0
    val episode = video.episode ?: return emptyList()
    return videos
        .filter { other ->
            val otherEpisode = other.episode
            if (other.id == video.id || otherEpisode == null) return@filter false
            val otherSeason = other.season ?: 0
            when {
                season == 0 -> otherSeason == 0 && otherEpisode < episode
                otherSeason == 0 -> false
                else -> otherSeason < season || (otherSeason == season && otherEpisode < episode)
            }
        }
        .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
}

/**
 * Minutes from an addon's free-text runtime: `48 min`, `2 h 46 min`, or a bare
 * number of minutes. Anything that does not parse is null rather than a guess.
 */
fun runtimeMinutes(runtime: String?): Int? {
    val text = runtime?.lowercase() ?: return null
    val hours = HoursPattern.find(text)?.groupValues?.get(1)?.toIntOrNull()
    val minutes = MinutesPattern.find(text)?.groupValues?.get(1)?.toIntOrNull()
    if (hours != null || minutes != null) {
        val total = (hours ?: 0) * 60 + (minutes ?: 0)
        return total.takeIf { it > 0 }
    }
    return BarePattern.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()
}

private val HoursPattern = Regex("""(\d+)\s*(?:h|hr|hour)""")
private val MinutesPattern = Regex("""(\d+)\s*(?:m|min)""")
private val BarePattern = Regex("""\s*(\d{2,3})\s*""")

private fun rowBase(target: WatchMarkTarget, existing: WatchState?, now: Long): WatchState = WatchState(
    videoId = target.videoId,
    itemId = target.itemId,
    positionSec = 0.0,
    durationSec = 0.0,
    watched = false,
    // The server rejects an empty name, and a newer row must not lose the
    // display fields an older one carried.
    name = target.name.ifEmpty { null } ?: existing?.name,
    poster = target.poster ?: existing?.poster,
    // Strictly newer than the row it replaces, or the server keeps the old one.
    updatedAt = maxOf(now, (existing?.updatedAt ?: 0L) + 1),
)
