package moe.ditto.halo.tv.player

import moe.ditto.halo.browse.seasonLabel
import moe.ditto.halo.screens.player.PlayerEpisode
import kotlin.math.roundToInt

/** From this fraction on an episode counts as watched, as everywhere else in the app. */
internal const val WatchedFraction = 0.95f

/** What an episode row in the player says about the episode. */
internal enum class EpisodeRowState { Playing, Watched, Started, Unwatched }

/** The episode playing wins over whatever its progress says; it is the one on screen. */
internal fun episodeRowState(episode: PlayerEpisode, currentVideoId: String): EpisodeRowState = when {
    episode.videoId == currentVideoId -> EpisodeRowState.Playing
    episode.progress >= WatchedFraction -> EpisodeRowState.Watched
    episode.progress > 0f -> EpisodeRowState.Started
    else -> EpisodeRowState.Unwatched
}

/** The row's status line: "Playing now", "Watched", "42% watched", or none. */
internal fun episodeStatusLine(episode: PlayerEpisode, currentVideoId: String): String? =
    when (episodeRowState(episode, currentVideoId)) {
        EpisodeRowState.Playing -> "Playing now"
        EpisodeRowState.Watched -> "Watched"
        EpisodeRowState.Started -> "${(episode.progress * 100).roundToInt()}% watched"
        EpisodeRowState.Unwatched -> null
    }

/** True when the row's art carries a progress bar: started and not yet finished. */
internal fun episodeShowsProgress(episode: PlayerEpisode): Boolean =
    episode.progress > 0f && episode.progress < WatchedFraction

/**
 * "S01 · E03" over the episode's name, from the addon's numbers. An episode
 * it does not number has only its tag, which is shown when it says something
 * the name does not.
 */
internal fun episodeTagLabel(episode: PlayerEpisode): String? {
    val season = episode.season
    val number = episode.episode
    if (season != null && number != null) return "S${twoDigits(season)} · E${twoDigits(number)}"
    return episode.tag.takeIf { it.isNotBlank() && it != episode.name }
}

/** What the art's stand-in plate says when there is no still: the episode's number, or its tag. */
internal fun episodePlateLabel(episode: PlayerEpisode): String =
    episode.episode?.let { "E$it" } ?: episode.tag

/**
 * "Season 1 · 9 episodes" under the panel's title, the season being the one
 * the playing episode belongs to; just the count when the addon numbers no
 * season.
 */
internal fun episodesPanelSubtitle(episodes: List<PlayerEpisode>, currentVideoId: String): String {
    val count = if (episodes.size == 1) "1 episode" else "${episodes.size} episodes"
    val season = (episodes.firstOrNull { it.videoId == currentVideoId } ?: episodes.firstOrNull())?.season
        ?: return count
    return "${seasonLabel(season)} · $count"
}

private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')
