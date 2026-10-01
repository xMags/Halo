package moe.ditto.halo.browse

import moe.ditto.halo.api.CatalogExtra
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SearchGroupsTest {

    @Test
    fun targetsEveryCatalogThatAdvertisesSearchInEitherSpelling() {
        // Addons predating the `extra` array advertise search only through
        // extraSupported; checking one spelling loses those rows silently.
        val targets = searchTargets(
            listOf(
                addon(
                    "cinemeta",
                    "Cinemeta",
                    listOf(
                        catalog("movie", "top", "Popular", extra = listOf(CatalogExtra(name = "search"))),
                        catalog("series", "top", "Popular", extraSupported = listOf("search")),
                        catalog("movie", "featured", "Featured"),
                    ),
                ),
            ),
        )

        assertEquals(listOf("cinemeta/movie/top", "cinemeta/series/top"), targets.map { it.key })
        assertEquals(listOf("Popular – Movie", "Popular – Series"), targets.map { it.title })
    }

    @Test
    fun anUnnamedCatalogBorrowsItsAddonName() {
        val targets = searchTargets(
            listOf(addon("kitsu", "Kitsu", listOf(catalog("anime", "all", extraSupported = listOf("search"))))),
        )

        assertEquals(listOf("Kitsu – Anime"), targets.map { it.title })
    }

    @Test
    fun dropsCatalogsThatFailedOrFoundNothing() {
        val targets = listOf(target("a"), target("b"), target("c"))

        val groups = buildSearchGroups(
            targets = targets,
            // b failed outright, c answered with nothing — neither earns a heading.
            results = listOf(listOf(meta("tt1")), null, emptyList()),
        )

        assertEquals(listOf("a"), groups.map { it.key })
    }

    @Test
    fun removesDuplicatesWithinAGroupKeepingTheFirst() {
        val groups = buildSearchGroups(
            targets = listOf(target("a")),
            results = listOf(
                listOf(
                    meta("tt1", name = "First"),
                    meta("tt1", name = "Duplicate"),
                    meta("tt1", type = "series", name = "Same id, other type"),
                ),
            ),
        )

        assertEquals(listOf("First", "Same id, other type"), groups.single().metas.map { it.name })
    }

    @Test
    fun keepsTheSameTitleAcrossDifferentGroups() {
        // Two addons knowing one title is the normal case; deduplicating across
        // rows would empty whichever addon happened to resolve second.
        val groups = buildSearchGroups(
            targets = listOf(target("a"), target("b")),
            results = listOf(listOf(meta("tt1")), listOf(meta("tt1"))),
        )

        assertEquals(2, groups.size)
        assertEquals(listOf("tt1"), groups[1].metas.map { it.id })
    }

    @Test
    fun rejectsResultsThatDoNotLineUpWithTheirTargets() {
        // Positional pairing is the only link between the two lists, so a
        // mismatch would attribute one addon's results to another.
        assertFailsWith<IllegalArgumentException> {
            buildSearchGroups(listOf(target("a"), target("b")), listOf(listOf(meta("tt1"))))
        }
    }

    // ------------------------------------------------------------------
    // Saved matches, which lead the results
    // ------------------------------------------------------------------

    @Test
    fun savedMatchesIgnoreCaseAndMatchAnywhereInTheName() {
        val library = listOf(
            libraryItem("series:tt1", name = "Breaking Bad"),
            libraryItem("movie:tt2", name = "Inception"),
        )

        assertEquals(listOf("Breaking Bad"), savedSearchMatches(library, "bREAK").map { it.name })
        assertEquals(listOf("Breaking Bad"), savedSearchMatches(library, "ing b").map { it.name })
    }

    @Test
    fun savedMatchesExcludeRemovedTitles() {
        // A tombstone is not in the library, and offering it back under a
        // "My Library" heading would state the opposite.
        val library = listOf(libraryItem("movie:tt1", name = "The Godfather", removedAt = 5_000))

        assertEquals(emptyList(), savedSearchMatches(library, "godfather"))
    }

    @Test
    fun savedMatchesComeBackNewestFirst() {
        val library = listOf(
            libraryItem("movie:tt1", name = "Dune", addedAt = 1_000),
            libraryItem("movie:tt2", name = "Dune: Part Two", addedAt = 9_000),
        )

        assertEquals(listOf("Dune: Part Two", "Dune"), savedSearchMatches(library, "dune").map { it.name })
    }

    @Test
    fun savedMatchesNeedTheSameTermLengthTheCatalogSearchDoes() {
        // One character matches most of a library; the row would be noise, and
        // the addon rows beside it would not have run at all.
        val library = listOf(libraryItem("movie:tt1", name = "Dune"))

        assertEquals(emptyList(), savedSearchMatches(library, "d"))
        assertEquals(emptyList(), savedSearchMatches(null, "dune"))
    }

    private fun target(key: String) =
        SearchTarget(key = key, addonId = key, type = "movie", catalogId = "top", title = "Title $key")
}
