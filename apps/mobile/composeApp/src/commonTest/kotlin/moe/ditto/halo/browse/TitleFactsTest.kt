package moe.ditto.halo.browse

import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.CatalogExtra
import moe.ditto.halo.api.LegacyTrailer
import moe.ditto.halo.api.Manifest
import moe.ditto.halo.api.ManifestCatalog
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaExtras
import moe.ditto.halo.api.MetaPerson
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamBehaviorHints
import moe.ditto.halo.api.TrailerStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The desktop app's `detailFacts`, `similarTitles` and `episodePages` rules, so both title pages say the same. */
class TitleFactsTest {
    private fun meta(
        cast: List<String> = emptyList(),
        director: List<String> = emptyList(),
        pictured: List<MetaPerson> = emptyList(),
    ) = MetaDetail(
        id = "tt1",
        type = "movie",
        name = "Film",
        cast = cast,
        director = director,
        appExtras = MetaExtras(pictured).takeIf { pictured.isNotEmpty() },
    )

    @Test
    fun theCastRowPrefersThePicturedCastAndClosesOnDirectorsEachNameOnce() {
        val pictured = listOf(MetaPerson("Amy Adams", "Louise", "https://p.test/a.jpg"), MetaPerson("Jeremy Renner", " "))
        assertEquals(
            listOf(Credit("Amy Adams", "Louise", "https://p.test/a.jpg"), Credit("Jeremy Renner", null, null), Credit("Denis Villeneuve", "Director", null)),
            creditsOf(meta(cast = listOf("Ignored"), director = listOf("Denis Villeneuve", "amy adams"), pictured = pictured)),
        )
        assertEquals(listOf("A", "B"), creditsOf(meta(cast = listOf("A", "B", "a "))).map { it.name })
    }

    @Test
    fun theTrailerIsAYouTubeIdTheAddonSentAndNothingElse() {
        val base = meta()
        assertEquals("https://www.youtube.com/watch?v=abcDEF_123", trailerUrl(base.copy(trailerStreams = listOf(TrailerStream(ytId = "abcDEF_123")))))
        assertEquals("https://www.youtube.com/watch?v=xyz789abc", trailerUrl(base.copy(trailers = listOf(LegacyTrailer("clip0001", "Clip"), LegacyTrailer("xyz789abc", "Trailer")))))
        assertNull(trailerUrl(base.copy(trailerStreams = listOf(TrailerStream(ytId = "javascript:alert(1)")))))
        assertNull(trailerUrl(base))
    }

    @Test
    fun runtimesAndDatesReadAsPeopleSayThem() {
        assertEquals("2 h 30 min", formatRuntime("150 min"))
        assertEquals("55 min", formatRuntime("55 min"))
        assertEquals("2 h", formatRuntime("120"))
        assertEquals("about an hour", formatRuntime("about an hour"))
        assertEquals("18 July 2025", formatReleaseDate("2025-07-18T00:00:00.000Z"))
        assertNull(formatReleaseDate("soon"))
    }

    @Test
    fun aboutLeavesOutWhatTheAddonDidNotSendAndAnEpisodeRuntimeOnASeries() {
        val film = meta(director = listOf("D")).copy(released = "2016-11-11", runtime = "116 min", language = " ", country = "United States")
        assertEquals(
            listOf("Director" to "D", "Released" to "11 November 2016", "Runtime" to "1 h 56 min", "Country" to "United States"),
            aboutRows(film, isSeries = false),
        )
        assertEquals(listOf("Director", "Released", "Country"), aboutRows(film, isSeries = true).map { it.first })
    }

    @Test
    fun sourcesGroupByTheTierThePickerNamesBestFirst() {
        fun stream(title: String, filename: String? = null) =
            Stream(url = "https://s.test/v", name = "Addon", title = title, behaviorHints = filename?.let { StreamBehaviorHints(filename = it) })
        val availability = summarizeAvailability(
            listOf(
                stream("Film 1080p WEB"),
                stream("Film 2160p", filename = "film.1080p.mkv"),
                stream("Film 720p"),
                stream("Film 480p"),
                stream("Film 576p"),
                stream("Film, no resolution"),
            ),
        )
        assertEquals(listOf("FULL HD" to 2, "HD" to 1, "SD" to 2, "OTHER" to 1), availability.groups.map { it.label to it.count })
        assertEquals(6, availability.total)
        assertEquals("FULL HD", availability.best?.label)
        assertNull(summarizeAvailability(listOf(stream("unnamed"))).best)
    }

    @Test
    fun moreLikeThisUsesTheFirstCatalogThatFiltersByTheTitlesOwnGenre() {
        fun addon(id: String, vararg catalogs: ManifestCatalog) =
            AddonEntry(id = id, manifest = Manifest(id = id, version = "1", name = id, catalogs = catalogs.toList()), position = 0)
        val listed = ManifestCatalog(type = "movie", id = "top", extra = listOf(CatalogExtra("genre", options = listOf("Drama"))))
        val open = ManifestCatalog(type = "movie", id = "pop", extra = listOf(CatalogExtra("genre")))
        val needsMore = ManifestCatalog(type = "movie", id = "year", extra = listOf(CatalogExtra("genre"), CatalogExtra("year", isRequired = true)))
        val addons = listOf(addon("a", needsMore, listed), addon("b", open))

        assertEquals(GenreCatalog("a", "top", "Drama"), pickGenreCatalog(addons, "movie", listOf("Drama", "Sci-Fi")))
        assertEquals(GenreCatalog("b", "pop", "Sci-Fi"), pickGenreCatalog(addons, "movie", listOf("Sci-Fi")))
        assertNull(pickGenreCatalog(addons, "series", listOf("Drama")))
    }

    @Test
    fun aLongSeasonPagesByTwentyFiveOpeningOnTheEpisodeInProgress() {
        val season = (1..60).map { MetaVideo(id = "s:1:$it", season = 1, episode = it) }
        val pages = episodePages(season)
        assertEquals(listOf("Episodes 1–25", "Episodes 26–50", "Episodes 51–60"), pages.map { it.label })
        assertEquals(2, pageContaining(pages, "s:1:55"))
        assertEquals(0, pageContaining(pages, "elsewhere"))
        assertEquals(1, episodePages(season.take(25)).size)
    }
}
