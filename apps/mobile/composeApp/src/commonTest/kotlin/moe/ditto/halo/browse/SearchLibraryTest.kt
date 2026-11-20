package moe.ditto.halo.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.MetaPreview

/** The library row Search shows, and the identities that keep its titles out of the results below it. */
class SearchLibraryTest {
    private fun saved(id: String, name: String, addedAt: Long, removedAt: Long? = null) =
        LibraryItem(id = id, type = id.substringBefore(':'), name = name, addedAt = addedAt, removedAt = removedAt, updatedAt = addedAt)

    @Test
    fun everyWordOfTheQueryFoldedMustBeInTheNameNewestFirst() {
        val library = listOf(
            saved("movie:tt1", "Amélie", addedAt = 1),
            saved("movie:tt2", "Dune: Part Two", addedAt = 3),
            saved("movie:tt3", "Dune", addedAt = 2),
            saved("series:tt4", "Two and a Half Men", addedAt = 4),
        )
        assertEquals(listOf("tt2"), libraryMatches(library, "two dune").map { it.id })
        assertEquals(listOf("tt2", "tt3"), libraryMatches(library, "DUNE").map { it.id })
        assertEquals(listOf("tt1"), libraryMatches(library, "amelie").map { it.id })
        assertEquals(emptyList(), libraryMatches(library, "   "))
        assertEquals(emptyList(), libraryMatches(null, "dune"))
    }

    @Test
    fun removedEntriesAndIdsThatNameNoTitleAreLeftOut() {
        val library = listOf(
            saved("movie:tt1", "Dune", addedAt = 1, removedAt = 5),
            LibraryItem(id = "tt9", type = "movie", name = "Dune", addedAt = 2, updatedAt = 2),
            LibraryItem(id = "movie:", type = "movie", name = "Dune", addedAt = 3, updatedAt = 3),
            saved("movie:kitsu:12", "Dune Anime", addedAt = 4),
        )
        assertEquals(listOf(MetaPreview(id = "kitsu:12", type = "movie", name = "Dune Anime")), libraryMatches(library, "dune"))
    }

    @Test
    fun aTitleIsKnownByItsKeyItsImdbIdAndItsNameWithItsYear() {
        val cinemeta = MetaPreview(id = "tt1160419", type = "movie", name = "Dune", releaseInfo = "2021")
        val tmdb = MetaPreview(id = "tmdb:438631", type = "movie", name = "Dune", releaseInfo = "2021", imdbId = "tt1160419")
        val remake = MetaPreview(id = "tt0087182", type = "movie", name = "Dune", releaseInfo = "1984")
        val undated = MetaPreview(id = "tmdb:1", type = "movie", name = "Dune")
        val shared = titleIdentities(cinemeta).toSet()
        assertTrue(titleIdentities(tmdb).any { it in shared }, "the same film from two addons")
        assertTrue(titleIdentities(remake).none { it in shared }, "another year is another film")
        assertEquals(listOf("movie:tmdb:1"), titleIdentities(undated), "a name alone is not trusted")
    }
}
