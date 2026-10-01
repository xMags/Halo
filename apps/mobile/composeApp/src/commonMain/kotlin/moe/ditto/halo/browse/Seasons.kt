package moe.ditto.halo.browse

import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState

/** Specials, which every media centre lists after the numbered seasons. */
private const val SpecialsSeason = 0

/**
 * The seasons a title has, ascending, with specials last regardless of their
 * number sorting first.
 *
 * Videos with no season are ignored here rather than grouped under a synthetic
 * one: they belong to titles that are not seasonal at all, which the detail
 * screen renders as a flat list with no picker.
 */
fun seasonNumbers(videos: List<MetaVideo>): List<Int> = videos
    .mapNotNull { it.season }
    .distinct()
    .sortedWith(compareBy({ it == SpecialsSeason }, { it }))

/**
 * The season of the most recently played episode, or null if none was.
 *
 * This is what the detail screen opens on, rather than the first season.
 * Mid-binge, the season being watched is where the next tap almost always goes,
 * and a viewer on season 4 should not have to walk back through the picker
 * every time they open the show.
 *
 * Only watch states belonging to [itemId] AND to an episode this title still
 * lists count — an addon can drop or renumber videos, and a state pointing at
 * one that no longer exists cannot say which season to open.
 */
fun lastWatchedSeason(videos: List<MetaVideo>, watchStates: List<WatchState>?, itemId: String): Int? {
    val byId = videos.associateBy { it.id }
    return watchStates.orEmpty()
        .filter { it.itemId == itemId && byId.containsKey(it.videoId) }
        .maxByOrNull { it.updatedAt }
        ?.let { byId.getValue(it.videoId).season }
}

/**
 * Episodes of [season] in broadcast order, or every video when a title has no
 * seasons to pick between.
 *
 * Episode numbers are optional in the protocol; the unnumbered sort first and
 * otherwise hold their original order, which is the addon's own.
 */
fun episodesIn(videos: List<MetaVideo>, season: Int?): List<MetaVideo> = videos
    .filter { season == null || it.season == season }
    .sortedBy { it.episode ?: 0 }

fun seasonLabel(season: Int): String = if (season == SpecialsSeason) "Specials" else "Season $season"

/**
 * Short episode tag ("S01E02") for titles and download labels, falling back to
 * whatever names the video when it is not numbered.
 */
fun episodeTag(video: MetaVideo): String {
    val season = video.season
    val episode = video.episode
    if (season == null || episode == null) return video.displayTitle ?: video.id
    return "S${season.pad()}E${episode.pad()}"
}

private fun Int.pad(): String = toString().padStart(2, '0')
