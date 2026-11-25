package moe.ditto.halo.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.ditto.halo.api.MetaVideo

class ContinueUpNextTest {
    private fun finished(videoId: String, itemId: String = "series:tt9", updatedAt: Long = 50) =
        watchState(videoId, itemId, watched = true, name = "Show", updatedAt = updatedAt)

    @Test
    fun aFinishedEpisodeAsksForItsSuccessorWhenItIsNotKnown() {
        val shelves = homeShelves(listOf(finished("tt9:1:2")), library = null, type = null)

        assertTrue(shelves.continueWatching.isEmpty())
        assertEquals(listOf(ContinueNextRequest("series", "tt9", "tt9:1:2")), shelves.nextEpisodeRequests)
    }

    @Test
    fun aKnownSuccessorBecomesAnUpNextCard() {
        val shelves = homeShelves(listOf(finished("tt9:1:2")), library = null, type = null) {
            NextEpisodeLookup.Resolved("tt9:1:3")
        }

        val entry = shelves.continueWatching.single()
        assertEquals("tt9:1:3", entry.videoId)
        assertTrue(entry.upNext)
        assertEquals(0f, entry.progress)
        assertTrue(shelves.nextEpisodeRequests.isEmpty())
    }

    @Test
    fun theLastEpisodeLeavesTheShelf() {
        val shelves = homeShelves(listOf(finished("tt9:1:2")), library = null, type = null) { NextEpisodeLookup.None }

        assertTrue(shelves.continueWatching.isEmpty())
        assertTrue(shelves.nextEpisodeRequests.isEmpty())
    }

    @Test
    fun theNewestActivitySpeaksForTheShowOverAnOlderEpisodeLeftPartway() {
        val shelves = homeShelves(
            listOf(
                watchState("tt9:1:1", "series:tt9", positionSec = 400.0, name = "Show", updatedAt = 10),
                finished("tt9:1:2", updatedAt = 50),
            ),
            library = null,
            type = null,
        ) { NextEpisodeLookup.Resolved("tt9:1:3") }

        assertEquals(listOf("tt9:1:3"), shelves.continueWatching.map { it.videoId })
    }

    @Test
    fun anEpisodeMarkedWatchedWithoutALengthStillMovesOn() {
        val marked = watchState("tt9:1:2", "series:tt9", positionSec = 0.0, durationSec = 0.0, watched = true, name = "Show")
        val shelves = homeShelves(listOf(marked), library = null, type = null)

        assertEquals(listOf(ContinueNextRequest("series", "tt9", "tt9:1:2")), shelves.nextEpisodeRequests)
    }

    @Test
    fun aFinishedFilmSimplyLeaves() {
        val shelves = homeShelves(listOf(finished("tt1", itemId = "movie:tt1")), library = null, type = null)

        assertTrue(shelves.continueWatching.isEmpty())
        assertTrue(shelves.nextEpisodeRequests.isEmpty())
    }

    @Test
    fun aBarelyOpenedEpisodeDoesNotHideAnOlderOneLeftPartway() {
        val shelves = homeShelves(
            listOf(
                watchState("tt9:1:1", "series:tt9", positionSec = 400.0, name = "Show", updatedAt = 10),
                watchState("tt9:1:2", "series:tt9", positionSec = 5.0, name = "Show", updatedAt = 50),
            ),
            library = null,
            type = null,
        )

        assertEquals(listOf("tt9:1:1"), shelves.continueWatching.map { it.videoId })
    }

    private fun video(season: Int, episode: Int) = MetaVideo(id = "tt9:$season:$episode", season = season, episode = episode)

    @Test
    fun theNextEpisodeCrossesIntoTheNextSeasonAndSkipsSpecials() {
        val videos = listOf(video(2, 1), video(1, 2), video(0, 1), video(1, 1))

        assertEquals("tt9:1:2", episodeAfter(videos, "tt9:1:1")?.id)
        assertEquals("tt9:2:1", episodeAfter(videos, "tt9:1:2")?.id)
        assertNull(episodeAfter(videos, "tt9:2:1"))
        assertNull(episodeAfter(videos, "tt9:0:1"))
        assertNull(episodeAfter(videos, "unknown"))
    }
}
