package moe.ditto.halo.browse

import moe.ditto.halo.api.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same cases as `@halo/core`'s `searchResults.test.ts`: the two must answer alike. */
class SearchResultsTest {
    private fun named(id: String, name: String, type: String = "movie") = MetaPreview(id = id, type = type, name = name)

    private fun full(
        id: String,
        name: String,
        releaseInfo: String? = "2016",
        poster: String? = "https://image.tmdb.org/t/p/w500/$id.jpg",
    ) = MetaPreview(id = id, type = "movie", name = name, releaseInfo = releaseInfo, poster = poster)

    private val noPoster = "https://image.tmdb.org/t/p/w500null"

    @Test
    fun namesThatAnswerTheQueryComeFirstEachLevelKeepingTheOrderGiven() {
        val merged = listOf(
            full("1", "Doctor Stranger"),
            full("2", "Doctor Strange in the Multiverse of Madness"),
            full("3", "Amuthu Dosthara"),
            full("4", "Dr. Strangelove"),
            full("5", "Doctor Strange"),
            full("6", "The Strange Case of Doctor Rx"),
        )

        // The one sharing no word with the query is gone; the rest are best-named first.
        // "Dr. Strangelove" reads as "doctor strangelove", which begins with the query as "Doctor Stranger" does.
        assertEquals(listOf("5", "2", "1", "4", "6"), refineResults(merged, "doctor strange").map { it.id })
    }

    @Test
    fun relevanceIgnoresCaseAccentsAndPunctuation() {
        assertEquals(0, nameRelevance("Amélie", "amelie"))
        assertEquals(1, nameRelevance("Spider-Man: No Way Home", "spider man"))
        assertEquals(0, nameRelevance("WALL·E", "wall e"))
        assertEquals(2, nameRelevance("Doctor Stranger", "doctor strange"))
        assertEquals(6, nameRelevance("Faust and the Devil", "doctor strange"))
    }

    @Test
    fun whenNothingNamesTheQueryEverythingStays() {
        val localized = listOf(full("tmdb:1", "डॉक्टर स्ट्रेंज"), full("tmdb:2", "कुछ और"))

        assertEquals(2, refineResults(localized, "doctor strange").size)
    }

    @Test
    fun aTitleWithNeitherAYearNorArtworkGoesAndOneMissingEitherComesLast() {
        val results = listOf(
            full("tmdb:1", "Doctor Strange Love", poster = noPoster),
            full("tmdb:2", "Untitled Doctor Strange Movie", releaseInfo = "", poster = noPoster),
            full("tt3", "Doctor Strange 3", releaseInfo = null, poster = "https://images.metahub.space/poster/small/tt3/img"),
            full("tmdb:5", "Doctor Strange in the Multiverse of Madness"),
            full("tmdb:6", "The Strange Case of Doctor Rx"),
        )

        assertEquals(
            listOf("tmdb:5", "tmdb:6", "tmdb:1", "tt3"),
            refineResults(results, "doctor strange").map { it.id },
        )
        // Once the poster link is known to be dead, the undated one has nothing to show.
        assertEquals(
            listOf("tmdb:5", "tmdb:6", "tmdb:1"),
            refineResults(results, "doctor strange", missingArt = setOf("movie:tt3")).map { it.id },
        )
    }

    @Test
    fun withAScreenfulOfCompleteResultsTheOnesWithoutArtworkAreLeftOut() {
        val complete = (0 until 8).map { full("tmdb:$it", "Doctor Strange $it") }
        val artless = full("tmdb:99", "Doctor Strange Love", poster = noPoster)

        assertFalse(refineResults(complete + artless, "doctor strange").any { it.id == "tmdb:99" })
        assertEquals("tmdb:99", refineResults(complete.drop(1) + artless, "doctor strange").last().id)
    }

    @Test
    fun theTmdbAddonsLinkToAMissingImageIsNoPoster() {
        assertTrue(hasNoPoster(full("a", "A", poster = noPoster)))
        assertTrue(hasNoPoster(full("a", "A", poster = null)))
        assertFalse(hasNoPoster(full("a", "A")))
    }

    @Test
    fun theTopMatchIsTheBestNamedFirstTitleNotTheFirstCatalogToAnswer() {
        val film = SearchResultGroup("c/movie", "Movies", listOf(named("m1", "El Camino: A Breaking Bad Movie")))
        val show = SearchResultGroup("c/series", "Series", listOf(named("s1", "Breaking Bad", "series")))
        val other = SearchResultGroup("t/movie", "Movies", listOf(named("m9", "Breaking Bad Habits")))

        assertEquals("s1", searchTopMatch(listOf(film, show, other), "breaking bad")?.id)
        // The exact name leads even when a longer one's addon is listed first.
        assertEquals("s1", searchTopMatch(listOf(other, film, show), "breaking bad")?.id)
        // A tie goes to the earlier catalog.
        assertEquals("m1", searchTopMatch(listOf(film, other), "nothing alike")?.id)
        assertNull(searchTopMatch(emptyList(), "breaking bad"))
    }

    @Test
    fun twoAddonsCopiesOfATitleAreOneByImdbIdAndTheKeptCopyGainsWhatTheOtherKnew() {
        val cinemeta = MetaPreview(
            id = "tt0035767",
            type = "movie",
            name = "The Crime Doctor's Strangest Case",
            releaseInfo = "1943",
            poster = "https://images.metahub.space/poster/small/tt0035767/img",
        )
        val tmdb = MetaPreview(
            id = "tmdb:84650",
            type = "movie",
            name = "Crime Doctor: Strangest Case",
            releaseInfo = "1944",
            imdbRating = "6.3",
            poster = "https://image.tmdb.org/t/p/w500/4at.jpg",
            imdbId = "tt0035767",
        )

        val merged = interleaveTitles(listOf(listOf(cinemeta), listOf(tmdb)), merge = MetaPreview::asOneTitleWith)

        assertEquals(1, merged.size)
        assertEquals("tt0035767", merged[0].id)
        assertEquals("6.3", merged[0].imdbRating)
        assertEquals("https://image.tmdb.org/t/p/w500/4at.jpg", merged[0].poster)
    }

    @Test
    fun aShortFormIsTheWordItStandsFor() {
        assertEquals(0, nameRelevance("Dr. Strange", "doctor strange"))
        assertEquals(0, nameRelevance("Doctor Strange", "dr strange"))
        assertEquals(0, nameRelevance("Mr. Robot", "mister robot"))
    }

    @Test
    fun withAScreenfulNamingEveryWordTitlesNamingOnlySomeAreLeftOut() {
        val named = (0 until 8).map { full("tmdb:$it", "Doctor Strange $it") }
        val loose = listOf(full("tmdb:90", "Doctor Sleep"), full("tmdb:91", "Strangers"))

        assertEquals(named.map { it.id }, refineResults(named + loose, "doctor strange").map { it.id })
        assertEquals(9, refineResults(named.drop(1) + loose, "doctor strange").size)
    }

    @Test
    fun anEntryMadeFromAFileNameIsTheTitleItNamesAndTheImdbCopyLeads() {
        val fromFile = MetaPreview(
            id = "mf1706190",
            type = "movie",
            name = "Doctor Strange (2016)",
            poster = "https://mediafusion.test/poster/movie/mf1706190.jpg",
        )
        val real = MetaPreview(
            id = "tt1211837",
            type = "movie",
            name = "Doctor Strange",
            releaseInfo = "2016",
            poster = "https://live.metahub.space/poster/medium/tt1211837/img",
        )

        for (lists in listOf(listOf(listOf(fromFile), listOf(real)), listOf(listOf(real), listOf(fromFile)))) {
            val merged = interleaveTitles(lists, merge = MetaPreview::asOneTitleWith, tidy = MetaPreview::withYearOutOfName)
            assertEquals(1, merged.size)
            assertEquals("tt1211837", merged[0].id)
            assertEquals("Doctor Strange", merged[0].name)
            assertEquals(real.poster, merged[0].poster)
        }
        val alone = fromFile.withYearOutOfName()
        assertEquals("Doctor Strange", alone.name)
        assertEquals("2016", alone.releaseInfo)
        assertTrue(hasNoPoster(fromFile))
        assertFalse(hasNoPoster(real))
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
