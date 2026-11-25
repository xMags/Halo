package moe.ditto.halo.tv.browse

import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.WatchState

/** How the saved titles are ordered, as the desktop app offers them. */
internal enum class LibrarySort(val label: String) {
    RecentlyAdded("Recently added"),
    TitleAZ("Title A–Z"),
    RecentlyWatched("Recently watched"),
}

/**
 * The saved titles in [sort]'s order. "Recently watched" goes by the newest
 * play of anything in the title (any episode of a series); titles never
 * played follow, newest addition first, so they keep a sensible order too.
 */
internal fun sortLibrary(items: List<LibraryItem>, sort: LibrarySort, watchStates: List<WatchState>?): List<LibraryItem> = when (sort) {
    LibrarySort.RecentlyAdded -> items.sortedByDescending { it.addedAt }
    LibrarySort.TitleAZ -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    LibrarySort.RecentlyWatched -> {
        val lastPlayed = HashMap<String, Long>()
        // A play removed from history no longer counts as watched.
        watchStates.orEmpty().filter { it.removedAt == null }.forEach { state ->
            lastPlayed[state.itemId] = maxOf(lastPlayed[state.itemId] ?: 0L, state.updatedAt)
        }
        items.sortedWith(
            compareByDescending<LibraryItem> { lastPlayed[it.id] ?: 0L }.thenByDescending { it.addedAt },
        )
    }
}
