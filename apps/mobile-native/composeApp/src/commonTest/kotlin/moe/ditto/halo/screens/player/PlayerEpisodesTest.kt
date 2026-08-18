package moe.ditto.halo.screens.player

import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamBehaviorHints
import moe.ditto.halo.api.WatchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerEpisodesTest {

    private val meta = MetaDetail(
        id = "tt0903747",
        type = "series",
        name = "Breaking Bad",
        videos = listOf(
            video("tt0903747:1:1", season = 1, episode = 1, title = "Pilot"),
            video("tt0903747:2:1", season = 2, episode = 1, title = "Seven Thirty-Seven"),
            video("tt0903747:2:2", season = 2, episode = 2, title = "Grilled"),
            video("tt0903747:2:3", season = 2, episode = 3, title = "Bit by a Dead Bee"),
        ),
    )

    @Test
    fun theDrawerShowsTheSeasonBeingWatchedAndNothingElse() {
        val episodes = playerEpisodes(meta, currentVideoId = "tt0903747:2:2", watchStates = null)

        assertEquals(listOf("S02E01", "S02E02", "S02E03"), episodes.map { it.tag })
        assertEquals("Grilled", episodes[1].name)
        assertEquals("https://images.test/2.jpg", episodes[1].thumbnail)
    }

    @Test
    fun progressComesFromWatchStateAndAFinishedEpisodeReadsAsFull() {
        val states = listOf(
            watchState("tt0903747:2:1", positionSec = 2_700.0, durationSec = 2_820.0, watched = true),
            watchState("tt0903747:2:2", positionSec = 705.0, durationSec = 2_820.0, watched = false),
        )

        val episodes = playerEpisodes(meta, currentVideoId = "tt0903747:2:2", watchStates = states)

        // Watched wins over the raw fraction: someone who stopped in the
        // credits has finished it, and a bar at 96% says they have not.
        assertEquals(1f, episodes[0].progress)
        assertEquals(0.25f, episodes[1].progress)
        assertEquals(0f, episodes[2].progress)
    }

    @Test
    fun anEpisodeWithNoDurationYetHasNoProgressRatherThanInfiniteProgress() {
        val states = listOf(watchState("tt0903747:2:1", positionSec = 30.0, durationSec = 0.0, watched = false))

        assertEquals(0f, playerEpisodes(meta, "tt0903747:2:1", states)[0].progress)
    }

    @Test
    fun aTitleWithNoVideosHasNoDrawerContents() {
        assertEquals(emptyList(), playerEpisodes(null, "tt0133093", null))
        assertEquals(emptyList(), playerEpisodes(meta.copy(videos = emptyList()), "tt0133093", null))
    }

    @Test
    fun theSeasonTitleFallsBackToTheShowWhenNothingIsNumbered() {
        assertEquals("Season 2", playerSeasonTitle(meta, "tt0903747:2:2", fallback = "Breaking Bad"))
        assertEquals("Breaking Bad", playerSeasonTitle(meta, "unknown-video", fallback = "Breaking Bad"))
        assertEquals("The Matrix", playerSeasonTitle(null, "tt0133093", fallback = "The Matrix"))
    }

    @Test
    fun theSameReleaseIsTheSameBingeGroupFromTheSameAddon() {
        val results = listOf(
            AddonStreams(
                addon = AddonSource("torrentio", "Torrentio"),
                streams = listOf(
                    stream("https://a.test/1080p.mkv", "show|1080p"),
                    stream("https://a.test/2160p.mkv", "show|2160p"),
                ),
            ),
            AddonStreams(
                addon = AddonSource("other", "Other"),
                streams = listOf(stream("https://b.test/2160p.mkv", "show|2160p")),
            ),
        )

        val match = sameReleaseStream(results, addonId = "torrentio", bingeGroup = "show|2160p")

        assertEquals("torrentio", match?.first?.id)
        assertEquals("https://a.test/2160p.mkv", match?.second?.url)
    }

    @Test
    fun aDifferentAddonOfferingTheSameGroupIsNotTheSameRelease() {
        val results = listOf(
            AddonStreams(
                addon = AddonSource("other", "Other"),
                streams = listOf(stream("https://b.test/2160p.mkv", "show|2160p")),
            ),
        )

        assertNull(sameReleaseStream(results, addonId = "torrentio", bingeGroup = "show|2160p"))
    }

    @Test
    fun withoutABingeGroupThereIsNothingToMatchOn() {
        val results = listOf(
            AddonStreams(
                addon = AddonSource("torrentio", "Torrentio"),
                streams = listOf(stream("https://a.test/1080p.mkv", bingeGroup = null)),
            ),
        )

        // Guessing here would autoplay an unrelated file rather than letting
        // the viewer choose.
        assertNull(sameReleaseStream(results, addonId = "torrentio", bingeGroup = null))
    }

    @Test
    fun aMatchWithNoUrlIsNotPlayable() {
        val results = listOf(
            AddonStreams(
                addon = AddonSource("torrentio", "Torrentio"),
                streams = listOf(Stream(behaviorHints = StreamBehaviorHints(bingeGroup = "show|2160p"))),
            ),
        )

        assertNull(sameReleaseStream(results, addonId = "torrentio", bingeGroup = "show|2160p"))
    }

    @Test
    fun theLibraryItemIdIsScopedByTypeRatherThanBeingTheBareMetaId() {
        val context = PlaybackContext(
            url = "https://a.test/e.mkv",
            type = "series",
            metaId = "tt0903747",
            videoId = "tt0903747:1:2",
            showTitle = "Breaking Bad",
            addonId = "torrentio",
        )

        // Every reader of a watch state joins on this, and a bare meta id
        // matches nothing: the row exists and no shelf can find it.
        assertEquals("series:tt0903747", context.itemId)
    }

    @Test
    fun nextPlaybackReplacesEveryFileSpecificField() {
        val current = PlaybackContext(
            url = "https://a.test/e1.mkv",
            type = "series",
            metaId = "tt0903747",
            videoId = "tt0903747:2:1",
            showTitle = "Breaking Bad",
            episodeTag = "S02E01",
            episodeName = "Seven Thirty-Seven",
            episodeThumbnail = "https://images.test/old.jpg",
            addonId = "torrentio",
            bingeGroup = "old-group",
            filename = "old.mkv",
            videoSize = 10,
            videoHash = "old-hash",
            streamName = "old name",
            streamTitle = "old title",
        )
        val stream = Stream(
            url = "https://a.test/e2.mkv",
            name = "new name",
            description = "new description",
            behaviorHints = StreamBehaviorHints(
                bingeGroup = "new-group",
                filename = "new.mkv",
                videoSize = 20,
                videoHash = "new-hash",
            ),
        )

        val next = nextPlaybackContext(current, meta.videos[2], stream)

        assertEquals("tt0903747:2:2", next?.videoId)
        assertEquals("S02E02", next?.episodeTag)
        assertEquals("Grilled", next?.episodeName)
        assertEquals("https://images.test/2.jpg", next?.episodeThumbnail)
        assertEquals("https://a.test/e2.mkv", next?.url)
        assertEquals("new-group", next?.bingeGroup)
        assertEquals("new.mkv", next?.filename)
        assertEquals(20, next?.videoSize)
        assertEquals("new-hash", next?.videoHash)
        assertEquals("new name", next?.streamName)
        assertEquals("new description", next?.streamTitle)
        // Title and addon identity survive the episode change.
        assertEquals("tt0903747", next?.metaId)
        assertEquals("torrentio", next?.addonId)
    }

    @Test
    fun drawerEpisodeSelectionCarriesTheEpisodesThumbnail() {
        val current = PlaybackContext(
            url = "https://a.test/e1.mkv",
            type = "series",
            metaId = "tt0903747",
            videoId = "tt0903747:2:1",
            showTitle = "Breaking Bad",
            addonId = "old-addon",
        )

        val selected = episodePlaybackContext(
            current = current,
            video = meta.videos[2],
            stream = stream("https://a.test/e2.mkv", "release"),
            addonId = "selected-addon",
        )

        assertEquals("https://images.test/2.jpg", selected?.episodeThumbnail)
        assertEquals("selected-addon", selected?.addonId)
    }

    @Test
    fun nextPlaybackWithoutAUrlCannotPlay() {
        assertNull(nextPlaybackContext(
            PlaybackContext("https://a.test/e1", "series", "show", "e1", "Show", addonId = "addon"),
            MetaVideo(id = "e2", season = 1, episode = 2),
            Stream(),
        ))
    }

    @Test
    fun theNextEpisodeStaysInsideTheSeason() {
        assertEquals("tt0903747:2:3", nextEpisodeAfter(meta.videos, "tt0903747:2:2")?.id)
        // Last of the season: nothing follows, rather than the first of the next.
        assertNull(nextEpisodeAfter(meta.videos, "tt0903747:2:3"))
        assertNull(nextEpisodeAfter(meta.videos, "not-an-episode"))
    }

    private fun video(id: String, season: Int, episode: Int, title: String) =
        MetaVideo(
            id = id,
            title = title,
            season = season,
            episode = episode,
            thumbnail = "https://images.test/$episode.jpg",
        )

    private fun watchState(videoId: String, positionSec: Double, durationSec: Double, watched: Boolean) =
        WatchState(
            videoId = videoId,
            itemId = "tt0903747",
            positionSec = positionSec,
            durationSec = durationSec,
            watched = watched,
            updatedAt = 1,
        )

    private fun stream(url: String, bingeGroup: String?) =
        Stream(url = url, behaviorHints = StreamBehaviorHints(bingeGroup = bingeGroup))
}
