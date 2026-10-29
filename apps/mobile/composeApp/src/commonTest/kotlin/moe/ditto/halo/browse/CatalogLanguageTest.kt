package moe.ditto.halo.browse

import moe.ditto.halo.api.CatalogExtra
import moe.ditto.halo.api.ManifestCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The desktop app's `tests/catalogLanguage.test.ts` cases, so both apps find the same language rows. */
class CatalogLanguageTest {
    private val languages = listOf("English", "Hindi", "Tamil", "Telugu", "French", "German", "Japanese", "Korean")
    private val tmdbLanguage = catalog(CatalogExtra(name = "genre", options = languages))
    private val cinemetaTop = catalog(
        CatalogExtra(name = "genre", options = listOf("Action", "Drama", "Comedy", "Romance")),
        CatalogExtra(name = "skip"),
    )
    private val tmdbYear = catalog(CatalogExtra(name = "genre", options = listOf("2026", "2025", "2024")))

    @Test
    fun aCatalogWhoseGenreChoicesAreLanguagesOffersThem() {
        assertEquals(languages, languageChoices(tmdbLanguage))
        assertNull(languageChoices(cinemetaTop))
        assertNull(languageChoices(tmdbYear))
        assertNull(languageChoices(catalog()))
        assertNull(languageChoices(catalog(CatalogExtra(name = "genre", isRequired = true, options = languages))))
    }

    @Test
    fun theChosenLanguageNarrowsOnlyALanguageCatalogThatOffersIt() {
        assertEquals(mapOf("genre" to "Tamil"), catalogExtra(languageChoices(tmdbLanguage), "Tamil"))
        assertEquals(emptyMap(), catalogExtra(languageChoices(tmdbLanguage), "Klingon"))
        assertEquals(emptyMap(), catalogExtra(languageChoices(tmdbLanguage), null))
        assertEquals(emptyMap(), catalogExtra(languageChoices(cinemetaTop), "Hindi"))
    }

    private fun catalog(vararg extra: CatalogExtra) =
        ManifestCatalog(type = "movie", id = "c", extra = extra.toList())
}
