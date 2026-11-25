package moe.ditto.halo.browse

import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState

/** An in-progress title, with how far into it the last session got. */
data class ContinueEntry(
    val meta: MetaPreview,
    /** The library id (`"${type}:${metaId}"`) this video belongs to. */
    val itemId: String,
    val progress: Float,
    /** The video left partway, which is what resuming the card plays. */
    val videoId: String,
    /** How much of that video is left, in seconds. */
    val remainingSec: Double = 0.0,
    /** The episode after a finished one, never started: [videoId] names it. */
    val upNext: Boolean = false,
)

/** Home's three personal shelves, above the catalog rows. */
data class HomeShelves(
    val continueWatching: List<ContinueEntry>,
    val recentlyWatched: List<MetaPreview>,
    val library: List<MetaPreview>,
    /** Series whose newest episode is finished and whose next one is not known yet. */
    val nextEpisodeRequests: List<ContinueNextRequest> = emptyList(),
)

/** A finished episode whose successor Continue watching wants to offer. */
data class ContinueNextRequest(val type: String, val metaId: String, val videoId: String)

/** What a finished episode's successor is known to be. */
sealed interface NextEpisodeLookup {
    /** Not asked yet, or the asking failed: try again on the next visit. */
    data object Unknown : NextEpisodeLookup

    /** The last episode there is: the show leaves Continue watching. */
    data object None : NextEpisodeLookup

    data class Resolved(val videoId: String) : NextEpisodeLookup
}

/**
 * The episode after [videoId] in watching order: the rest of its season, then
 * the next season's first. Specials stay apart, so finishing a regular
 * episode never leads into them, and a special leads to the next special.
 */
fun episodeAfter(videos: List<MetaVideo>, videoId: String): MetaVideo? {
    val current = videos.firstOrNull { it.id == videoId } ?: return null
    val special = current.season == SpecialsSeason
    val ordered = videos
        .filter { it.season != null && it.episode != null && (it.season == SpecialsSeason) == special }
        .distinctBy { it.id }
        .sortedWith(compareBy({ it.season }, { it.episode }))
    val index = ordered.indexOfFirst { it.id == videoId }
    if (index < 0) return null
    return ordered.getOrNull(index + 1)
}

/** Under this the title was barely opened; over it, it is effectively finished. */
private const val MinResumableFraction = 0.02
private const val MaxResumableFraction = 0.95

private const val RecentlyWatchedLimit = 15

/**
 * Builds all three shelves together, because they are defined against each
 * other: Recently Watched means "watched, but not already offered above", so
 * deriving it apart from Continue Watching would show the same title twice.
 *
 * [type] filters by media type ("movie"/"series"), null meaning no filter.
 *
 * It deliberately does NOT reach Continue Watching. That shelf is not part of
 * what the filter browses: it is the unfinished pile, and something half-watched
 * does not stop being half-watched because the filter currently reads Movies.
 * Recently Watched and the library shelf are browsing surfaces and do honour it.
 */
fun homeShelves(
    watchStates: List<WatchState>?,
    library: List<LibraryItem>?,
    type: String?,
    /** A finished episode's successor, as far as it is known; see [NextEpisodeLookup]. */
    nextEpisode: (ContinueNextRequest) -> NextEpisodeLookup = { NextEpisodeLookup.Unknown },
): HomeShelves {
    val active = library.orEmpty().filterNot { it.isRemoved }
    val byItemId = active.associateBy { it.id }
    val requests = mutableListOf<ContinueNextRequest>()
    val continueWatching = continueWatching(watchStates, byItemId, nextEpisode, requests)
    return HomeShelves(
        nextEpisodeRequests = requests,
        continueWatching = continueWatching,
        recentlyWatched = recentlyWatched(
            watchStates = watchStates,
            library = byItemId,
            exclude = continueWatching.mapTo(mutableSetOf()) { it.itemId },
            type = type,
        ),
        library = active
            .filter { matchesTypeFilter(type, it.type) }
            .sortedByDescending { it.addedAt }
            .map { MetaPreview(id = metaIdOf(it.id, it.type), type = it.type, name = it.name, poster = it.poster) },
    )
}

/**
 * In-progress titles, most recently played first, one card per title.
 *
 * Library membership is not required: watching something is enough to want to
 * finish it. A title is represented by whatever it did most recently: left
 * partway, it resumes; finished, a series moves on to its next episode (a
 * film simply leaves). A barely opened title (under the lower bound) does not
 * speak for its show, so an earlier episode left partway still shows.
 *
 * A finished episode whose successor is not known yet adds to [requests]
 * instead of a card, so the shelf shows what is known and gains the next
 * episode's card once the caller has looked it up.
 */
private fun continueWatching(
    watchStates: List<WatchState>?,
    library: Map<String, LibraryItem>,
    nextEpisode: (ContinueNextRequest) -> NextEpisodeLookup,
    requests: MutableList<ContinueNextRequest>,
): List<ContinueEntry> {
    val claimed = mutableSetOf<String>()
    val entries = mutableListOf<ContinueEntry>()
    for (state in watchStates.orEmpty().filter { it.removedAt == null }.sortedByDescending { it.updatedAt }) {
        if (state.itemId in claimed) continue
        // Marked watched from a menu, a row may carry no length; it is still
        // finished. Otherwise a row with no length says nothing of progress.
        if (state.durationSec <= 0 && !state.watched) continue
        val fraction = if (state.durationSec > 0) state.positionSec / state.durationSec else 1.0
        val finished = state.watched || fraction >= MaxResumableFraction
        if (!finished && fraction <= MinResumableFraction) continue
        val meta = watchStateMeta(state, library) ?: continue
        // From here this row answers for its title, card or not.
        claimed += state.itemId
        if (!finished) {
            entries += ContinueEntry(meta, state.itemId, fraction.toFloat(), state.videoId, state.durationSec - state.positionSec)
            continue
        }
        if (meta.type != "series") continue
        val request = ContinueNextRequest(meta.type, meta.id, state.videoId)
        when (val next = nextEpisode(request)) {
            NextEpisodeLookup.Unknown -> if (requests.size < MaxNextEpisodeRequests) requests += request
            NextEpisodeLookup.None -> Unit
            is NextEpisodeLookup.Resolved -> entries += ContinueEntry(meta, state.itemId, 0f, next.videoId, upNext = true)
        }
    }
    return entries
}

/** Lookups one visit to Home may start: the newest finished shows first. */
private const val MaxNextEpisodeRequests = 20

/**
 * Playback history: the last distinct titles by recency at any progress, so a
 * finished series is still one tap from a rewatch.
 */
private fun recentlyWatched(
    watchStates: List<WatchState>?,
    library: Map<String, LibraryItem>,
    exclude: Set<String>,
    type: String?,
): List<MetaPreview> {
    val seen = mutableSetOf<String>()
    return watchStates.orEmpty()
        .filterNot { exclude.contains(it.itemId) }
        .sortedByDescending { it.updatedAt }
        .filter { seen.add(it.itemId) }
        .mapNotNull { watchStateMeta(it, library) }
        .filter { matchesTypeFilter(type, it.type) }
        .take(RecentlyWatchedLimit)
}

/**
 * A card for a watch-state row: its own denormalised fields first, the library
 * entry only as a fallback for rows written before those fields existed.
 *
 * A row nothing can name is dropped rather than shown as its id — the title was
 * watched without ever being saved, and by a client old enough not to record
 * what it was.
 */
private fun watchStateMeta(state: WatchState, library: Map<String, LibraryItem>): MetaPreview? {
    val entry = library[state.itemId]
    val name = state.name ?: entry?.name ?: return null
    val type = typeOf(state.itemId) ?: return null
    return MetaPreview(
        id = metaIdOf(state.itemId, type),
        type = type,
        name = name,
        poster = state.poster ?: entry?.poster,
    )
}

/**
 * The type half of `"${type}:${metaId}"`. Split on the FIRST colon only: types
 * never contain one, but meta ids routinely do (`kitsu:1234`).
 */
private fun typeOf(itemId: String): String? = itemId.substringBefore(':', missingDelimiterValue = "").ifEmpty { null }

private fun metaIdOf(itemId: String, type: String): String = itemId.substring(type.length + 1)

/**
 * Whether a title of [type] shows under the All / Movies / Series filter,
 * [filter] being null for All. Anything that is not a series counts as a
 * movie, as on the desktop app, so a title of an addon's own type (Kitsu's
 * "anime", say) is not lost from both filtered views.
 */
fun matchesTypeFilter(filter: String?, type: String): Boolean = when (filter) {
    null -> true
    "series" -> type == "series"
    else -> type != "series"
}
