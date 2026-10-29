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
        assertEquals(listOf("Popular Movies", "Trending Series"), rows.map { it.title })
        assertEquals(listOf("CINEMETA", "OTHER"), rows.map { it.source })
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

        assertEquals(listOf("Popular Movies"), rows.map { it.title })
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

        assertEquals(listOf("Popular Movies"), rows.map { it.title })
    }

    @Test
    fun keepsOnlyMovieAndSeriesCatalogs() {
        // TorBox's "other" catalog lists the account's raw torrents, not titles.
        val rows = catalogRows(
            listOf(
                addon(
                    "torbox",
                    "TorBox",
                    listOf(
                        catalog("movie", "torbox-movies", "TorBox Movies"),
                        catalog("other", "torbox-torrents", "TorBox Torrents"),
                        catalog("series", "torbox-series", "TorBox Series"),
                        catalog("channel", "torbox-channels", "TorBox Channels"),
                    ),
                ),
            ),
        )

        assertEquals(listOf("torbox/movie/torbox-movies", "torbox/series/torbox-series"), rows.map { it.key })
    }

    /* The cases below are the desktop app's `tests/homeRows.test.ts`, so both apps title shelves alike. */

    @Test
    fun aCatalogShelfIsTitledByNameAndKindWithItsAddonBesideIt() {
        assertEquals(ShelfLabels("Popular Movies", "CINEMETA"), catalogShelfLabels("Popular", "movie", "Cinemeta"))
        assertEquals(ShelfLabels("Featured Series", "CINEMETA"), catalogShelfLabels("Featured", "series", " Cinemeta "))
    }

    @Test
    fun aCatalogNameThatAlreadySaysItsKindIsNotDoubled() {
        assertEquals("Trending Movies", catalogShelfLabels("Trending Movies", "movie", "TMDB").title)
        assertEquals("Top Films", catalogShelfLabels("Top Films", "movie", "TMDB").title)
        assertEquals("Popular TV Shows", catalogShelfLabels("Popular TV Shows", "series", "TMDB").title)
        assertEquals("Anime Series", catalogShelfLabels("Anime Series", "series", "Kitsu").title)
    }

    @Test
    fun aWordThatOnlyContainsAKindWordStillGetsTheKind() {
        // "Showcase" holds "show" but does not say the catalog is of shows.
        assertEquals("Showcase Series", catalogShelfLabels("Showcase", "series", "Addon").title)
    }

    @Test
    fun aCatalogWithNoNameIsTitledByItsKind() {
        assertEquals("Movies", catalogShelfLabels(null, "movie", "Addon").title)
        assertEquals("Series", catalogShelfLabels("   ", "series", "Addon").title)
        val rows = catalogRows(listOf(addon("torbox", "TorBox", listOf(catalog("movie", "torbox-movies")))))
        assertEquals(listOf(ShelfLabels("Movies", "TORBOX")), rows.map { ShelfLabels(it.title, it.source) })
    }

    @Test
    fun anAddonsOwnPrefixMovesFromTheTitleToTheSourceLine() {
        assertEquals(
            ShelfLabels("Latest Movie Releases", "TMDB"),
            catalogShelfLabels("TMDB - Latest Releases", "movie", "The Movie Database Addon"),
        )
        assertEquals(ShelfLabels("Popular Series", "TMDB"), catalogShelfLabels("TMDB: Popular", "series", "The Movie Database Addon"))
        assertEquals(ShelfLabels("Popular Movies", "CINEMETA"), catalogShelfLabels("Cinemeta - Popular", "movie", "Cinemeta"))
    }

    @Test
    fun aPrefixThatIsNotTheAddonStaysInTheTitle() {
        assertEquals("Star Wars - Saga Movies", catalogShelfLabels("Star Wars - Saga", "movie", "Collections").title)
        // No spaces around the hyphen: part of a word, not a separator.
        assertEquals("Spider-Man Movie Picks", catalogShelfLabels("Spider-Man Picks", "movie", "Fan Lists").title)
    }

    @Test
    fun theSourceLineDropsATrailingAddon() {
        assertEquals("THE MOVIE DATABASE", catalogShelfLabels("Popular", "movie", "The Movie Database Addon").source)
        assertEquals("STREAMING CATALOGS", catalogShelfLabels("Popular", "movie", "Streaming Catalogs Add-on").source)
    }

    @Test
    fun aNameEndingOnAPluralNounTakesTheKindInFrontOfIt() {
        assertEquals("Latest Movie Releases", catalogShelfLabels("Latest Releases", "movie", "Addon").title)
        assertEquals("New Series Arrivals", catalogShelfLabels("New Arrivals", "series", "Addon").title)
        // A single word is the whole name; the kind goes behind it as usual.
        assertEquals("Releases Movies", catalogShelfLabels("Releases", "movie", "Addon").title)
    }

    @Test
    fun anythingThatIsNotASeriesCountsAsAMovieUnderTheFilter() {
        assertEquals(true, matchesTypeFilter(null, "anime"))
        assertEquals(true, matchesTypeFilter("movie", "anime"))
        assertEquals(false, matchesTypeFilter("movie", "series"))
        assertEquals(true, matchesTypeFilter("series", "series"))
        assertEquals(false, matchesTypeFilter("series", "movie"))
    }
}
