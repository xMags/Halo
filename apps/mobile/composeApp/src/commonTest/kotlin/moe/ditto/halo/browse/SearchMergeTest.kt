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
            keys(interleaveTitles(listOf(listOf(title("tt1"), title("tt2"))), exclude = listOf(title("tt1")))),
        )
    }

    private fun dated(id: String, name: String, releaseInfo: String? = null, type: String = "movie") =
        MetaPreview(id = id, type = type, name = name, releaseInfo = releaseInfo)

    @Test
    fun oneTitleNamedByTwoAddonsWithDifferentIdsAppearsOnce() {
        val cinemeta = listOf(dated("tt1160419", "Dune", "2021"), dated("tt15239678", "Dune: Part Two", "2024"))
        val tmdb = listOf(dated("tmdb:438631", "Dune", "2021"), dated("tmdb:693134", "Dune Part Two", "2024"))
        val series = listOf(
            dated("tt10466872", "Dune: Prophecy", "2024–", "series"),
            dated("tmdb:90228", "Dune: Prophecy", "2024-2025", "series"),
        )

        // The better-ranked copy stays: Cinemeta's, which comes first.
        assertEquals(
            listOf("movie:tt1160419", "series:tt10466872", "movie:tt15239678"),
            keys(interleaveTitles(listOf(cinemeta, tmdb, series))),
        )
    }

    @Test
    fun sameNamedTitlesFromOtherYearsOrKindsOrWithoutAYearStaySeparate() {
        val lists = listOf(
            listOf(dated("tt1160419", "Dune", "2021"), dated("tt0087182", "Dune", "1984")),
            listOf(dated("tt0142032", "Dune", "2000", "series"), dated("tmdb:1", "Dune"), dated("tmdb:2", "Dune")),
        )
        assertEquals(
            listOf("movie:tt1160419", "series:tt0142032", "movie:tt0087182", "movie:tmdb:1", "movie:tmdb:2"),
            keys(interleaveTitles(lists)),
        )
    }

    @Test
    fun aCopyFromAnotherAddonOfWhatIsShownAboveIsLeftOut() {
        val top = dated("tt1160419", "Dune", "2021")
        assertEquals(
            listOf("movie:tt9"),
            keys(interleaveTitles(listOf(listOf(dated("tmdb:438631", "Dune", "2021"), title("tt9"))), exclude = listOf(top))),
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
