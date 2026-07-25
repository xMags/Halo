package moe.ditto.halo.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeasonsTest {
    private val videos = listOf(
        video("s1e2", season = 1, episode = 2),
        video("s2e1", season = 2, episode = 1),
        video("s0e1", season = 0, episode = 1),
        video("s1e1", season = 1, episode = 1),
    )

    @Test
    fun listsSeasonsAscendingWithSpecialsLast() {
        assertEquals(listOf(1, 2, 0), seasonNumbers(videos))
    }

    @Test
    fun ignoresVideosWithNoSeason() {
        assertEquals(emptyList(), seasonNumbers(listOf(video("only"))))
    }

    @Test
    fun opensOnTheSeasonOfTheMostRecentlyPlayedEpisode() {
        // Mid-binge the current season is where the next tap goes; opening on
        // season 1 makes a viewer walk the picker back every single time.
        val season = lastWatchedSeason(
            videos = videos,
            watchStates = listOf(
                watchState("s1e1", "series:show", updatedAt = 10),
                watchState("s2e1", "series:show", updatedAt = 90),
            ),
            itemId = "series:show",
        )

        assertEquals(2, season)
    }

    @Test
    fun ignoresProgressBelongingToAnotherTitle() {
        val season = lastWatchedSeason(
            videos = videos,
            watchStates = listOf(watchState("s2e1", "series:other", updatedAt = 90)),
            itemId = "series:show",
        )

        assertNull(season)
    }

    @Test
    fun ignoresProgressForAnEpisodeTheTitleNoLongerLists() {
        // Addons renumber and drop videos; a state pointing at one that is gone
        // cannot say which season to open.
        val season = lastWatchedSeason(
            videos = videos,
            watchStates = listOf(watchState("removed-episode", "series:show", updatedAt = 90)),
            itemId = "series:show",
        )

        assertNull(season)
    }

    @Test
    fun listsOneSeasonsEpisodesInBroadcastOrder() {
        assertEquals(listOf("s1e1", "s1e2"), episodesIn(videos, season = 1).map { it.id })
    }

    @Test
    fun listsEveryVideoWhenThereIsNoSeasonToPick() {
        val flat = listOf(video("b", episode = 2), video("a", episode = 1))

        assertEquals(listOf("a", "b"), episodesIn(flat, season = null).map { it.id })
    }

    @Test
    fun labelsSpecialsByNameAndSeasonsByNumber() {
        assertEquals("Specials", seasonLabel(0))
        assertEquals("Season 3", seasonLabel(3))
    }

    @Test
    fun tagsEpisodesWithPaddedSeasonAndEpisodeNumbers() {
        assertEquals("S01E02", episodeTag(video("id", season = 1, episode = 2)))
        assertEquals("S10E12", episodeTag(video("id", season = 10, episode = 12)))
    }

    @Test
    fun fallsBackToTheVideoTitleWhenItIsNotNumbered() {
        assertEquals("A special", episodeTag(video("id", title = "A special")))
        assertEquals("id", episodeTag(video("id")))
    }
}
