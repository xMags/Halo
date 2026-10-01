package moe.ditto.halo.screens

import moe.ditto.halo.api.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PosterItemsTest {

    @Test
    fun aTappedCardResolvesBackToTheTitleItStandsFor() {
        val item = MetaPreview(id = "tt0111161", type = "movie", name = "The Shawshank Redemption").posterItem()

        assertEquals(MetaRef("movie", "tt0111161"), item.metaRef())
    }

    @Test
    fun metaIdsCarryingColonsSurviveTheRoundTrip() {
        // Anime addons address titles as "kitsu:1234". Splitting on the last
        // colon, or on every one, truncates the id and the detail screen then
        // asks for a title that does not exist.
        val item = MetaPreview(id = "kitsu:1234", type = "series", name = "Frieren").posterItem()

        assertEquals("series:kitsu:1234", item.key)
        assertEquals(MetaRef("series", "kitsu:1234"), item.metaRef())
    }

    @Test
    fun progressRidesAlongOnlyWhenGiven() {
        val meta = MetaPreview(id = "tt1", type = "movie", name = "A film")

        assertEquals(0.42f, meta.posterItem(progress = 0.42f).progress)
        assertNull(meta.posterItem().progress)
    }
}
