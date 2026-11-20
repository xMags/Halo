package moe.ditto.halo.browse

import moe.ditto.halo.api.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The text and artwork rules Search ranks by, as `@halo/core` answers them. */
class SearchResultsTest {
    private fun full(
        id: String,
        name: String,
        releaseInfo: String? = "2016",
        poster: String? = "https://image.tmdb.org/t/p/w500/$id.jpg",
    ) = MetaPreview(id = id, type = "movie", name = name, releaseInfo = releaseInfo, poster = poster)

    private val noPoster = "https://image.tmdb.org/t/p/w500null"

    @Test
    fun relevanceIgnoresCaseAccentsAndPunctuation() {
        assertEquals(0, nameRelevance("Amélie", "amelie"))
        assertEquals(1, nameRelevance("Spider-Man: No Way Home", "spider man"))
        assertEquals(0, nameRelevance("WALL·E", "wall e"))
        assertEquals(2, nameRelevance("Doctor Stranger", "doctor strange"))
        assertEquals(6, nameRelevance("Faust and the Devil", "doctor strange"))
    }

    @Test
    fun theTmdbAddonsLinkToAMissingImageIsNoPoster() {
        assertTrue(hasNoPoster(full("a", "A", poster = noPoster)))
        assertTrue(hasNoPoster(full("a", "A", poster = null)))
        assertFalse(hasNoPoster(full("a", "A")))
    }

    @Test
    fun aShortFormIsTheWordItStandsFor() {
        assertEquals(0, nameRelevance("Dr. Strange", "doctor strange"))
        assertEquals(0, nameRelevance("Doctor Strange", "dr strange"))
        assertEquals(0, nameRelevance("Mr. Robot", "mister robot"))
    }

    @Test
    fun anEntryMadeFromAFileNameIsKnownByItsIdWhereverItsCardIsServedFrom() {
        assertTrue(hasNoPoster(full("mf12", "Doctor Strange", poster = "https://cdn.test/cards/12.jpg")))
        assertFalse(hasNoPoster(full("tt12", "Doctor Strange", poster = "https://cdn.test/cards/12.jpg")))
    }

    @Test
    fun aRatingOfZeroOrNothingReadableIsNoRating() {
        assertEquals("6.8", ratedValue("6.8"))
        assertEquals("7.2", ratedValue(" 7.2 "))
        assertNull(ratedValue("0.0"))
        assertNull(ratedValue("0"))
        assertNull(ratedValue(""))
        assertNull(ratedValue(null))
        assertNull(ratedValue("N/A"))
    }
}
