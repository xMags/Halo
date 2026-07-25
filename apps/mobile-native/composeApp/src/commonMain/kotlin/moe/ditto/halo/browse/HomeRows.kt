package moe.ditto.halo.browse

import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.api.WatchState

/** An in-progress title, with how far into it the last session got. */
data class ContinueEntry(
    val meta: MetaPreview,
    /** The library id (`"${type}:${metaId}"`) this video belongs to. */
    val itemId: String,
    val progress: Float,
)

/** Home's three personal shelves, above the catalog rows. */
data class HomeShelves(
    val continueWatching: List<ContinueEntry>,
    val recentlyWatched: List<MetaPreview>,
    val library: List<MetaPreview>,
)

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
 * Note that it does NOT reach Continue Watching — that shelf ignores the filter
 * entirely, matching the shipping client. Resuming is treated as being about
 * where you left off rather than about what you are browsing for.
 */
fun homeShelves(
    watchStates: List<WatchState>?,
    library: List<LibraryItem>?,
    type: String?,
): HomeShelves {
    val active = library.orEmpty().filterNot { it.isRemoved }
    val byItemId = active.associateBy { it.id }
    val continueWatching = continueWatching(watchStates, byItemId)
    return HomeShelves(
        continueWatching = continueWatching,
        recentlyWatched = recentlyWatched(
            watchStates = watchStates,
            library = byItemId,
            exclude = continueWatching.mapTo(mutableSetOf()) { it.itemId },
            type = type,
        ),
        library = active
            .filter { type == null || it.type == type }
            .sortedByDescending { it.addedAt }
            .map { MetaPreview(id = metaIdOf(it.id, it.type), type = it.type, name = it.name, poster = it.poster) },
    )
}

/**
 * In-progress titles, most recently played first.
 *
 * Library membership is not required: watching something is enough to want to
 * finish it. The fraction bounds keep out the two things nobody wants offered
 * back — a title opened by mistake, and one that has effectively ended but sits
 * just under the watched threshold because the credits were skipped.
 */
private fun continueWatching(
    watchStates: List<WatchState>?,
    library: Map<String, LibraryItem>,
): List<ContinueEntry> {
    val seen = mutableSetOf<String>()
    return watchStates.orEmpty()
        .filter { it.durationSec > 0 && !it.watched }
        .map { it to it.positionSec / it.durationSec }
        .filter { (_, fraction) -> fraction > MinResumableFraction && fraction < MaxResumableFraction }
        .sortedByDescending { (state, _) -> state.updatedAt }
        // One card per title: two half-watched episodes of the same series are
        // one thing to resume, and would otherwise collide on the list key too.
        .filter { (state, _) -> seen.add(state.itemId) }
        .mapNotNull { (state, fraction) ->
            watchStateMeta(state, library)?.let { ContinueEntry(it, state.itemId, fraction.toFloat()) }
        }
}

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
        .filter { type == null || it.type == type }
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
