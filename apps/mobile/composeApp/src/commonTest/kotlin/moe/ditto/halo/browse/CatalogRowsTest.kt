package moe.ditto.halo.browse

import moe.ditto.halo.api.CatalogExtra
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogRowsTest {

    @Test
    fun buildsOneRowPerParameterlessCatalogInAddonOrder() {
        val rows = catalogRows(
            listOf(
                addon("cinemeta", "Cinemeta", listOf(catalog("movie", "top", "Popular"))),
                addon("other", "Other", listOf(catalog("series", "trending", "Trending"))),
            ),
        )

        assertEquals(listOf("cinemeta/movie/top", "other/series/trending"), rows.map { it.key })
        assertEquals(listOf("Popular · Movies", "Trending · Series"), rows.map { it.title })
        assertEquals(listOf("cinemeta", "other"), rows.map { it.addonId })
        assertEquals(listOf("top", "trending"), rows.map { it.catalogId })
    }

    @Test
    fun dropsCatalogsThatRequireAnExtra() {
        // Home asks no questions, so a catalog gated on a search term or a genre
        // could only ever render an empty heading.
        val rows = catalogRows(
            listOf(
                addon(
                    "cinemeta",
                    catalogs = listOf(
                        catalog("movie", "top", "Popular"),
                        catalog(
                            "movie",
                            "search",
                            "Search",
                            extra = listOf(CatalogExtra(name = "search", isRequired = true)),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("Popular · Movies"), rows.map { it.title })
    }

    @Test
    fun dropsCatalogsGatedThroughTheLegacyExtraRequiredList() {
        // Older addons declare the same gate in a parallel array; missing this
        // spelling puts a permanently empty row on Home.
        val rows = catalogRows(
            listOf(
                addon(
                    "legacy",
                    catalogs = listOf(catalog("movie", "genre", "By genre", extraRequired = listOf("genre"))),
                ),
            ),
        )

        assertEquals(emptyList(), rows)
    }

    @Test
    fun keepsCatalogsWhoseExtrasAreAllOptional() {
        val rows = catalogRows(
            listOf(
                addon(
                    "cinemeta",
                    catalogs = listOf(
                        catalog(
                            "movie",
                            "top",
                            "Popular",
                            extra = listOf(
                                CatalogExtra(name = "genre", isRequired = false),
                                CatalogExtra(name = "skip"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("Popular · Movies"), rows.map { it.title })
    }

    @Test
    fun anUnnamedCatalogBorrowsItsAddonName() {
        val rows = catalogRows(listOf(addon("torbox", "TorBox", listOf(catalog("movie", "torbox-movies")))))

        assertEquals(listOf("TorBox · Movies"), rows.map { it.title })
    }

    @Test
    fun showsAnUnrecognisedTypeAsTheAddonSpelledIt() {
        // The protocol lets addons invent types; a row titled with the raw type
        // still browses, whereas dropping it hides content entirely.
        assertEquals("Movies", collectionTypeLabel("movie"))
        assertEquals("Series", collectionTypeLabel("series"))
        assertEquals("channel", collectionTypeLabel("channel"))
    }
}
