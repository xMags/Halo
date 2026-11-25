package moe.ditto.halo.tv.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.WatchState

class TvLibraryOrderTest {
    private fun item(id: String, name: String, added: Long) =
        LibraryItem(id = id, type = id.substringBefore(':'), name = name, addedAt = added, updatedAt = added)

    private fun played(itemId: String, at: Long, removed: Boolean = false) =
        WatchState(videoId = itemId.substringAfter(':'), itemId = itemId, positionSec = 10.0, durationSec = 100.0, watched = false, removedAt = if (removed) at else null, updatedAt = at)

    private val dune = item("movie:tt1", "dune", added = 1)
    private val alien = item("movie:tt2", "Alien", added = 3)
    private val cars = item("movie:tt3", "Cars", added = 2)
    private val items = listOf(dune, alien, cars)

    @Test
    fun recentlyAddedIsNewestFirst() {
        assertEquals(listOf(alien, cars, dune), sortLibrary(items, LibrarySort.RecentlyAdded, null))
    }

    @Test
    fun titlesSortAlphabeticallyIgnoringCase() {
        assertEquals(listOf(alien, cars, dune), sortLibrary(items, LibrarySort.TitleAZ, null))
    }

    @Test
    fun recentlyWatchedGoesByTheNewestPlayThenByAddition() {
        val states = listOf(played("movie:tt1", 50), played("movie:tt3", 20), played("movie:tt3", 10))
        assertEquals(listOf(dune, cars, alien), sortLibrary(items, LibrarySort.RecentlyWatched, states))
    }

    @Test
    fun aPlayRemovedFromHistoryDoesNotCount() {
        val states = listOf(played("movie:tt1", 50, removed = true))
        assertEquals(listOf(alien, cars, dune), sortLibrary(items, LibrarySort.RecentlyWatched, states))
    }
}
