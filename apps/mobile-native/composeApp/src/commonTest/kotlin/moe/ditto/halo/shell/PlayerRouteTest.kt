package moe.ditto.halo.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamBehaviorHints

/**
 * The hand-off from the source picker into playback.
 *
 * It is worth testing without a screen around it because it is the one place
 * where playback context can be silently dropped: every field here has a reader
 * in a later slice, and a field that never gets set fails as a missing subtitle
 * search or a next episode that cannot be found, far from the line that dropped
 * it.
 */
class PlayerRouteTest {

    private val episode = StreamsRoute(
        type = "series",
        metaId = "tt0944947",
        videoId = "tt0944947:1:1",
        showTitle = "Game of Thrones",
        episodeTag = "S01E01",
        episodeName = "Winter Is Coming",
        episodeThumbnail = "https://images.example/s01e01.jpg",
        poster = "https://images.example/game-of-thrones.jpg",
    )

    private val addon = AddonSource(id = "addon-1", name = "Torrentio TB")

    private val stream = Stream(
        url = "https://example.test/one.mkv",
        name = "[TB+] Torrentio\n1080p",
        title = "Game of Thrones S01E01 1080p BluRay x265",
        behaviorHints = StreamBehaviorHints(
            bingeGroup = "torrentio|106e91b9",
            filename = "Game of Thrones S01E01 1080p BluRay x265.mkv",
            // Past 2^31: the size has to survive the route as a 64-bit value.
            videoSize = 34_249_807_367,
            videoHash = "8330adfa7c2e68e8",
        ),
    )

    @Test
    fun carriesEveryPartOfTheChosenSourceIntoPlayback() {
        val route = episode.playerRoute(addon, stream, stream.url!!)

        assertEquals("https://example.test/one.mkv", route.url)
        assertEquals("series", route.type)
        assertEquals("tt0944947", route.metaId)
        assertEquals("tt0944947:1:1", route.videoId)
        assertEquals("Game of Thrones", route.showTitle)
        assertEquals("S01E01", route.episodeTag)
        assertEquals("Winter Is Coming", route.episodeName)
        assertEquals("https://images.example/s01e01.jpg", route.episodeThumbnail)
        assertEquals("https://images.example/game-of-thrones.jpg", route.poster)
        assertEquals("addon-1", route.addonId)
        assertEquals("torrentio|106e91b9", route.bingeGroup)
        assertEquals("Game of Thrones S01E01 1080p BluRay x265.mkv", route.filename)
        assertEquals(34_249_807_367, route.videoSize)
        assertEquals("8330adfa7c2e68e8", route.videoHash)
        assertEquals("[TB+] Torrentio\n1080p", route.streamName)
        assertEquals("Game of Thrones S01E01 1080p BluRay x265", route.streamTitle)
    }

    @Test
    fun readsBackAsThePlaybackContextTheScreenTakes() {
        val context = episode.playerRoute(addon, stream, stream.url!!).playbackContext()

        assertEquals("tt0944947:1:1", context.videoId)
        assertEquals("https://images.example/s01e01.jpg", context.episodeThumbnail)
        assertEquals("https://images.example/game-of-thrones.jpg", context.poster)
        assertEquals(34_249_807_367, context.videoSize)
        assertTrue(context.isEpisode)
        assertEquals("Game of Thrones · S01E01", context.displayTitle)
    }

    @Test
    fun aSourceWithNoHintsCarriesNoneRatherThanEmptyOnes() {
        val bare = Stream(url = "https://example.test/two.mkv", name = "Fixture")
        val route = episode.playerRoute(addon, bare, bare.url!!)
        val context = route.playbackContext()

        assertNull(context.bingeGroup)
        assertNull(context.filename)
        assertNull(context.videoHash)
        // Zero is how the route spells "the addon did not say"; nothing past the
        // route should ever see the sentinel itself.
        assertEquals(0L, route.videoSize)
        assertNull(context.videoSize)
    }

    @Test
    fun fallsBackToTheDescriptionWhenASourceHasNoTitle() {
        val described = Stream(
            url = "https://example.test/three.mkv",
            name = "Fixture",
            description = "2160p HEVC",
        )

        assertEquals("2160p HEVC", episode.playerRoute(addon, described, described.url!!).streamTitle)
    }

    @Test
    fun aFilmIsOneVideoWithNoEpisodeToName() {
        val film = StreamsRoute(
            type = "movie",
            metaId = "tt0133093",
            videoId = "tt0133093",
            showTitle = "The Matrix",
            poster = "https://images.example/matrix.jpg",
        )
        val context = film.playerRoute(addon, stream, stream.url!!).playbackContext()

        assertFalse(context.isEpisode)
        assertNull(context.episodeTag)
        assertNull(context.episodeName)
        assertEquals("The Matrix", context.displayTitle)
        assertEquals("https://images.example/matrix.jpg", context.poster)
        assertEquals("The Matrix", film.displayTitle)
    }

    @Test
    fun pickingAnotherSourceRebuildsTheSamePickerContext() {
        val player = episode.playerRoute(addon, stream, stream.url!!)

        assertEquals(episode, player.sourcesRoute())
    }

    @Test
    fun fallbackEpisodePickerCarriesTheSelectedEpisodesThumbnail() {
        val player = episode.playerRoute(addon, stream, stream.url!!)
        val fallback = player.episodeSources(
            moe.ditto.halo.screens.player.EpisodeChoice.NeedsSource(
                videoId = "tt0944947:1:2",
                episodeTag = "S01E02",
                episodeName = "The Kingsroad",
                episodeThumbnail = "https://images.example/s01e02.jpg",
            ),
        )

        assertEquals("https://images.example/s01e02.jpg", fallback.episodeThumbnail)
    }

    @Test
    fun theSourcePickersHeaderNamesTheEpisodeItIsPickingFor() {
        assertEquals("Game of Thrones · S01E01", episode.displayTitle)
    }
}
