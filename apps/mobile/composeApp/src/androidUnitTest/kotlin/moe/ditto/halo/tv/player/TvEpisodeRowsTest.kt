package moe.ditto.halo.tv.player

import moe.ditto.halo.screens.player.PlayerEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvEpisodeRowsTest {

    private fun episode(
        id: String,
        progress: Float = 0f,
        season: Int? = 1,
        number: Int? = 3,
        tag: String = "S01E03",
        name: String = "The Long Night",
    ) = PlayerEpisode(id, tag, name, thumbnail = null, progress = progress, downloaded = false, season = season, episode = number)

    @Test
    fun theStatusLineFollowsProgressAndThePlayingEpisodeWins() {
        assertEquals("Playing now", episodeStatusLine(episode("a", progress = 1f), currentVideoId = "a"))
        assertEquals("Watched", episodeStatusLine(episode("b", progress = 0.95f), currentVideoId = "a"))
        assertEquals("94% watched", episodeStatusLine(episode("b", progress = 0.94f), currentVideoId = "a"))
        assertEquals("1% watched", episodeStatusLine(episode("b", progress = 0.01f), currentVideoId = "a"))
        assertNull(episodeStatusLine(episode("b"), currentVideoId = "a"))
        assertEquals(EpisodeRowState.Playing, episodeRowState(episode("a", progress = 1f), "a"))
    }

    @Test
    fun theBarShowsOnlyBetweenStartedAndWatched() {
        assertFalse(episodeShowsProgress(episode("a")))
        assertTrue(episodeShowsProgress(episode("a", progress = 0.5f)))
        assertFalse(episodeShowsProgress(episode("a", progress = 0.95f)))
    }

    @Test
    fun theTagComesFromTheNumbersOrFromATagTheNameDoesNotRepeat() {
        assertEquals("S01 · E03", episodeTagLabel(episode("a")))
        assertEquals("S12 · E104", episodeTagLabel(episode("a", season = 12, number = 104)))
        assertEquals("Pilot cut", episodeTagLabel(episode("a", season = null, number = null, tag = "Pilot cut")))
        assertNull(episodeTagLabel(episode("a", season = null, number = null, tag = "The Long Night")))
    }

    @Test
    fun thePlateNamesTheEpisodeNumberOrItsTag() {
        assertEquals("E3", episodePlateLabel(episode("a")))
        assertEquals("Special", episodePlateLabel(episode("a", number = null, tag = "Special")))
    }

    @Test
    fun theHeaderNamesThePlayingEpisodesSeasonAndCounts() {
        val season = listOf(episode("a"), episode("b"), episode("c"))
        assertEquals("Season 1 · 3 episodes", episodesPanelSubtitle(season, currentVideoId = "b"))
        assertEquals("Specials · 1 episode", episodesPanelSubtitle(listOf(episode("a", season = 0)), currentVideoId = "a"))
        assertEquals("2 episodes", episodesPanelSubtitle(listOf(episode("a", season = null), episode("b", season = null)), "a"))
        assertEquals("0 episodes", episodesPanelSubtitle(emptyList(), "a"))
    }
}
