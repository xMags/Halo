package moe.ditto.halo.ui

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun pickerOptionsHaveUniqueCanonicalCodes() {
        assertEquals(LanguageOptions.size, LanguageOptions.map { it.code }.toSet().size)
        assertTrue(LanguageOptions.all { it.code.isNotBlank() && it.label.isNotBlank() })
    }
}
