package moe.ditto.halo.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PosterFallbackTest {
    @Test
    fun anImdbTitleFallsBackOnItsMetahubPoster() {
        assertEquals("https://images.metahub.space/poster/medium/tt12735488/img", metahubPosterUrl("tt12735488"))
        assertEquals(
            "https://images.metahub.space/poster/medium/tt1/img",
            PosterItem(key = "movie:tt1", title = "A").fallbackPosterUrl,
        )
    }

    @Test
    fun otherIdsHaveNoFallback() {
        listOf(null, "", "tt", "kitsu:1234", "tmdb:1", "tt1:1:2", "xtt1").forEach { assertNull(metahubPosterUrl(it)) }
        assertNull(PosterItem(key = "series:kitsu:1234", title = "A").fallbackPosterUrl)
    }
}
