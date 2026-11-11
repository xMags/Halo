package moe.ditto.halo.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageOptionsTest {
    @Test
    fun labelsCanonicalAndAliasCodes() {
        assertEquals("English", languageLabel("eng"))
        assertEquals("German", languageLabel("DEU"))
        assertEquals("Portuguese (BR)", languageLabel("pob"))
        assertEquals("unknown", languageLabel("unknown"))
    }

    @Test
    fun aTrackTaggedWithARegionMatchesTheLanguagePreference() {
        assertTrue(languageMatches("en-US", "eng"))
        assertTrue(languageMatches("EN_gb", "eng"))
        assertTrue(languageMatches("en", "eng"))
        assertFalse(languageMatches("por", "eng"))
        assertFalse(languageMatches("und", "eng"))
    }

    @Test
    fun brazilianPortugueseStaysApartFromPortuguese() {
        assertTrue(languageMatches("pt-BR", "pob"))
        assertTrue(languageMatches("pt", "por"))
        assertFalse(languageMatches("pt-BR", "por"))
    }

    @Test
    fun aPreferenceListsEverySpellingOfItsLanguageItsOwnFirst() {
        assertEquals(listOf("eng", "en"), languageCodeVariants("eng"))
        assertEquals(listOf("ger", "de", "deu"), languageCodeVariants("ger"))
        assertEquals(listOf("pob", "pb", "pt-br"), languageCodeVariants("pob"))
        // A language outside the folded pairs is still named by its own code.
        assertEquals(listOf("hin"), languageCodeVariants("hin"))
        assertEquals(emptyList(), languageCodeVariants(null))
        assertEquals(emptyList(), languageCodeVariants(" "))
    }

    @Test
    fun pickerOptionsHaveUniqueCanonicalCodes() {
        assertEquals(LanguageOptions.size, LanguageOptions.map { it.code }.toSet().size)
        assertTrue(LanguageOptions.all { it.code.isNotBlank() && it.label.isNotBlank() })
    }
}
