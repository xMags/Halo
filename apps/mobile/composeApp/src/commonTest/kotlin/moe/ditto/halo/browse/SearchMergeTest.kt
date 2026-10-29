package moe.ditto.halo.browse

import moe.ditto.halo.api.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchMergeTest {

    private fun title(id: String, type: String = "movie") = MetaPreview(id = id, type = type, name = id)
    private fun keys(metas: List<MetaPreview>) = metas.map(::titleKey)

    @Test
    fun theListsTakeTurnsAndEachTitleAppearsOnce() {
        val cinemeta = listOf(title("tt1"), title("tt2"), title("tt3"), title("tt4"))
        val tmdb = listOf(title("tt2"), title("tt9"))
        val series = listOf(title("tt5", "series"))

        assertEquals(
            listOf("movie:tt1", "movie:tt2", "series:tt5", "movie:tt9", "movie:tt3", "movie:tt4"),
            keys(interleaveTitles(listOf(cinemeta, tmdb, series))),
        )
    }

    @Test
    fun titlesShownElsewhereAreLeftOut() {
        assertEquals(
            listOf("movie:tt2"),
            keys(interleaveTitles(listOf(listOf(title("tt1"), title("tt2"))), exclude = setOf("movie:tt1"))),
        )
    }

    @Test
    fun pastSearchesContainingTheTypedTextAreSuggested() {
        val history = listOf("dune part two", "Dune", "the office")

        assertEquals(listOf("dune part two", "Dune"), suggestedSearches(history, "DUN"))
        // What is already typed is not offered back.
        assertEquals(listOf("dune part two"), suggestedSearches(history, "dune"))
        assertEquals(emptyList(), suggestedSearches(history, "  "))
    }
}
