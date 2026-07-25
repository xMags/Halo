package moe.ditto.halo.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeRowsTest {

    @Test
    fun offersInProgressTitlesMostRecentFirst() {
        val shelves = homeShelves(
            watchStates = listOf(
                watchState("tt1", "movie:tt1", positionSec = 300.0, name = "Older", updatedAt = 10),
                watchState("tt2", "movie:tt2", positionSec = 400.0, name = "Newer", updatedAt = 20),
            ),
            library = null,
            type = null,
        )

        assertEquals(listOf("Newer", "Older"), shelves.continueWatching.map { it.meta.name })
        assertEquals(0.4f, shelves.continueWatching.first().progress)
        assertEquals("movie:tt2", shelves.continueWatching.first().itemId)
    }

    @Test
    fun excludesWhatIsNotWorthResuming() {
        val shelves = homeShelves(
            watchStates = listOf(
                watchState("done", "movie:done", watched = true, name = "Watched"),
                watchState("barely", "movie:barely", positionSec = 20.0, name = "Barely started"),
                watchState("ending", "movie:ending", positionSec = 960.0, name = "At the credits"),
                watchState("live", "movie:live", durationSec = 0.0, name = "No duration"),
                watchState("keep", "movie:keep", positionSec = 500.0, name = "Mid-way"),
            ),
            library = null,
            type = null,
        )

        assertEquals(listOf("Mid-way"), shelves.continueWatching.map { it.meta.name })
    }

    @Test
    fun collapsesSeveralEpisodesOfOneShowIntoTheMostRecentCard() {
        // Two half-watched episodes are one thing to resume — and would collide
        // on the list key if both became cards.
        val shelves = homeShelves(
            watchStates = listOf(
                watchState("tt9:1:1", "series:tt9", name = "Show", updatedAt = 10),
                watchState("tt9:1:2", "series:tt9", positionSec = 600.0, name = "Show", updatedAt = 50),
            ),
            library = null,
            type = null,
        )

        val entry = shelves.continueWatching.single()
        assertEquals("series:tt9", entry.itemId)
        assertEquals(0.6f, entry.progress)
    }

    @Test
    fun prefersTheRowsOwnNameAndPosterOverTheLibraryEntry() {
        val shelves = homeShelves(
            watchStates = listOf(
                watchState("tt1", "movie:tt1", name = "From the row", poster = "row.jpg"),
                watchState("tt2", "movie:tt2", updatedAt = 5),
            ),
            library = listOf(
                libraryItem("movie:tt1", name = "From the library", poster = "library.jpg"),
                libraryItem("movie:tt2", name = "Fallback", poster = "fallback.jpg"),
            ),
            type = null,
        )

        val (first, second) = shelves.continueWatching
        assertEquals("From the row", first.meta.name)
        assertEquals("row.jpg", first.meta.poster)
        // Rows written before those fields existed still render, via the library.
        assertEquals("Fallback", second.meta.name)
        assertEquals("fallback.jpg", second.meta.poster)
    }

    @Test
    fun dropsRowsNothingCanName() {
        val shelves = homeShelves(
            watchStates = listOf(watchState("tt1", "movie:tt1")),
            library = listOf(libraryItem("movie:other")),
            type = null,
        )

        assertTrue(shelves.continueWatching.isEmpty())
    }

    @Test
    fun ignoresARemovedLibraryEntryWhenNamingARow() {
        // A tombstone is sync bookkeeping, not content: it must not put a title
        // the user deleted back on Home under its old name.
        val shelves = homeShelves(
            watchStates = listOf(watchState("tt1", "movie:tt1")),
            library = listOf(libraryItem("movie:tt1", name = "Removed", removedAt = 2_000)),
            type = null,
        )

        assertTrue(shelves.continueWatching.isEmpty())
    }

    @Test
    fun continueWatchingIgnoresTheTypeFilter() {
        // Parity with the shipping client, which filters the other two shelves
        // but not this one. Resuming is about where you left off, not about
        // what you are currently browsing for.
        val shelves = homeShelves(
            watchStates = listOf(watchState("tt1", "movie:tt1", name = "A film")),
            library = null,
            type = "series",
        )

        assertEquals(listOf("A film"), shelves.continueWatching.map { it.meta.name })
    }

    @Test
    fun historyExcludesWhatIsAlreadyOfferedAsResumable() {
        val shelves = homeShelves(
            watchStates = listOf(
                watchState("tt1", "movie:tt1", positionSec = 500.0, name = "In progress", updatedAt = 20),
                watchState("tt2", "movie:tt2", watched = true, name = "Finished", updatedAt = 10),
            ),
            library = null,
            type = null,
        )

        assertEquals(listOf("In progress"), shelves.continueWatching.map { it.meta.name })
        assertEquals(listOf("Finished"), shelves.recentlyWatched.map { it.name })
    }

    @Test
    fun historyKeepsOneEntryPerTitleAndRespectsTheTypeFilter() {
        val shelves = homeShelves(
            watchStates = listOf(
                watchState("tt9:1:1", "series:tt9", watched = true, name = "Show", updatedAt = 10),
                watchState("tt9:1:2", "series:tt9", watched = true, name = "Show", updatedAt = 50),
                watchState("tt1", "movie:tt1", watched = true, name = "Film", updatedAt = 40),
            ),
            library = null,
            type = "series",
        )

        assertEquals(listOf("Show"), shelves.recentlyWatched.map { it.name })
    }

    @Test
    fun historyIsCappedAtFifteenTitles() {
        val shelves = homeShelves(
            watchStates = (1..20).map {
                watchState("tt$it", "movie:tt$it", watched = true, name = "Title $it", updatedAt = it.toLong())
            },
            library = null,
            type = null,
        )

        assertEquals(15, shelves.recentlyWatched.size)
        assertEquals("Title 20", shelves.recentlyWatched.first().name)
        assertEquals("Title 6", shelves.recentlyWatched.last().name)
    }

    @Test
    fun libraryShelfShowsSavedTitlesNewestFirstWithoutTombstones() {
        val shelves = homeShelves(
            watchStates = null,
            library = listOf(
                libraryItem("movie:tt1", name = "Older", addedAt = 10),
                libraryItem("movie:tt2", name = "Newer", addedAt = 20),
                libraryItem("movie:tt3", name = "Removed", addedAt = 30, removedAt = 40),
            ),
            type = null,
        )

        assertEquals(listOf("Newer", "Older"), shelves.library.map { it.name })
    }

    @Test
    fun libraryShelfRespectsTheTypeFilter() {
        val shelves = homeShelves(
            watchStates = null,
            library = listOf(libraryItem("movie:tt1", name = "Film"), libraryItem("series:tt9", name = "Show")),
            type = "series",
        )

        assertEquals(listOf("Show"), shelves.library.map { it.name })
    }

    @Test
    fun splitsLibraryIdsOnTheFirstColonOnly() {
        // Types never contain a colon; meta ids routinely do (kitsu:1234), so
        // splitting on the last one would corrupt every anime id.
        val shelves = homeShelves(
            watchStates = listOf(watchState("kitsu:42:1", "series:kitsu:42", name = "Anime")),
            library = listOf(libraryItem("series:kitsu:42", name = "Anime")),
            type = null,
        )

        val fromHistory = shelves.continueWatching.single().meta
        assertEquals("kitsu:42", fromHistory.id)
        assertEquals("series", fromHistory.type)
        assertEquals("kitsu:42", shelves.library.single().id)
    }

    @Test
    fun ignoresAWatchStateWhoseItemIdCarriesNoType() {
        val shelves = homeShelves(
            watchStates = listOf(watchState("tt1", "malformed", name = "Nameless shape")),
            library = null,
            type = null,
        )

        assertTrue(shelves.continueWatching.isEmpty())
        assertNull(shelves.recentlyWatched.firstOrNull())
    }
}
