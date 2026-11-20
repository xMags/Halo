package moe.ditto.halo.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.CatalogExtra
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.api.Manifest
import moe.ditto.halo.api.ManifestCatalog
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.auth.EpochClock
import moe.ditto.halo.people.PeopleSearch
import moe.ditto.halo.people.PersonCreditRole
import moe.ditto.halo.people.PersonCredit
import moe.ditto.halo.people.PersonDepartment
import moe.ditto.halo.people.PersonSummary

/** [SearchEngine] on virtual time: when results are revealed, and that late answers never move them. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchEngineTest {
    private class Answer(val afterMs: Long, val titles: List<MetaPreview>? = null, val failure: Exception? = null)

    private class FakeSources(
        val catalogs: Map<String, List<Answer>>,
        val peopleAnswer: Answer? = null,
        val person: List<PersonSummary> = emptyList(),
        val credits: List<PersonCredit> = emptyList(),
        val creditsAfterMs: Long = 0,
        val addonsFail: Boolean = false,
    ) : SearchSources {
        val asked = mutableMapOf<String, Int>()
        val creditsAsked = mutableListOf<Long>()

        override suspend fun addons(): List<AddonEntry> {
            if (addonsFail) throw HaloApiException(503, "down")
            return catalogs.keys.mapIndexed { index, id ->
                val search = CatalogExtra(name = ManifestCatalog.SearchExtra, isRequired = true)
                AddonEntry(id = id, manifest = Manifest(id = id, version = "1", name = "Addon $id", catalogs = listOf(ManifestCatalog(type = "movie", id = "top", extra = listOf(search)))), position = index)
            }
        }

        override suspend fun catalog(target: BoardTarget, query: String): List<MetaPreview> {
            val attempt = asked.getOrElse(target.addonId) { 0 }
            asked[target.addonId] = attempt + 1
            val answer = catalogs.getValue(target.addonId).let { it[minOf(attempt, it.lastIndex)] }
            delay(answer.afterMs)
            answer.failure?.let { throw it }
            return answer.titles.orEmpty()
        }

        override suspend fun peopleOn() = peopleAnswer != null
        override suspend fun people(query: String): PeopleSearch {
            val answer = checkNotNull(peopleAnswer)
            delay(answer.afterMs)
            answer.failure?.let { throw it }
            return PeopleSearch(person, null)
        }

        override suspend fun credits(tmdbId: Long): List<PersonCredit> {
            creditsAsked += tmdbId
            delay(creditsAfterMs)
            return credits
        }

        override suspend fun posterLoads(url: String): Boolean? = true
    }

    private fun title(id: String, name: String, year: String = "2020") =
        MetaPreview(id = id, type = "movie", name = name, poster = "https://img.test/$id.jpg", releaseInfo = year)

    private fun TestScope.engine(sources: SearchSources) =
        SearchEngine(sources, EpochClock { testScheduler.currentTime }, currentYear = { 2026 })

    /** Every snapshot of [query]'s search, collected in the background. */
    private fun TestScope.collect(sources: SearchSources, query: String): MutableList<SearchSnapshot> {
        val seen = mutableListOf<SearchSnapshot>()
        backgroundScope.launch { engine(sources).run(query).collect { seen += it } }
        runCurrent()
        return seen
    }

    private fun ids(snapshot: SearchSnapshot) = listOfNotNull(snapshot.topMatch) .map { it.id } + snapshot.titles.map { it.id }

    @Test
    fun revealsWhenEveryCatalogHasAnswered() = runTest {
        val sources = FakeSources(
            mapOf(
                "a" to listOf(Answer(100, listOf(title("tt1", "Dune"), title("tt2", "Dune Two")))),
                "b" to listOf(Answer(300, listOf(title("tt3", "Dune World")))),
            ),
        )
        val seen = collect(sources, "dune")
        assertFalse(seen.last().revealed)
        advanceTimeBy(301)
        runCurrent()
        val shown = seen.last()
        assertTrue(shown.revealed)
        assertEquals(0, shown.pending)
        assertEquals(setOf("tt1", "tt2", "tt3"), ids(shown).toSet())
        assertEquals("tt1", shown.topMatch?.id)
    }

    @Test
    fun aSlowCatalogIsAppendedWithoutMovingWhatIsShown() = runTest {
        val sources = FakeSources(
            mapOf(
                "a" to listOf(Answer(100, listOf(title("tt1", "Dune"), title("tt2", "Dune Part Two")))),
                "b" to listOf(Answer(5_000, listOf(title("tt9", "Dune"), title("tt3", "Dune Messiah")))),
            ),
        )
        val seen = collect(sources, "dune")
        advanceTimeBy(1_199)
        runCurrent()
        assertFalse(seen.last().revealed, "the reveal waits for the deadline")
        advanceTimeBy(2)
        runCurrent()
        val revealed = seen.last()
        assertTrue(revealed.revealed)
        assertEquals(1, revealed.pending)
        val before = ids(revealed)
        advanceTimeBy(4_000)
        runCurrent()
        val after = seen.last()
        assertEquals(0, after.pending)
        assertEquals(before, ids(after).take(before.size), "nothing shown moved")
        assertTrue("tt3" in ids(after).drop(before.size))
    }

    @Test
    fun aFailedCatalogIsAskedOnceMoreButNotAfterA429() = runTest {
        val sources = FakeSources(
            mapOf(
                "a" to listOf(Answer(10, failure = HaloApiException(502, "x")), Answer(10, listOf(title("tt1", "Dune")))),
                "b" to listOf(Answer(10, failure = HaloApiException(429, "slow down")), Answer(10, listOf(title("tt2", "Dune Too")))),
            ),
        )
        val seen = collect(sources, "dune")
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, sources.asked["a"])
        assertEquals(1, sources.asked["b"])
        val shown = seen.last()
        assertTrue(shown.revealed)
        assertEquals(listOf("tt1"), ids(shown))
        assertEquals(listOf("Addon b"), shown.failedAddons)
    }

    @Test
    fun aPersonsExactNameWaitsForTheirFilmsAndShowsThem() = runTest {
        val villeneuve = PersonSummary(137427, "Denis Villeneuve", PersonDepartment.Directing, "https://image.tmdb.org/t/p/w185/dv.jpg", 10.0, listOf("Dune"))
        val sicario = PersonCredit("movie", "tt3397884", 273481, "Sicario", "2015", null, listOf(PersonCreditRole.Director), null, 30.0)
        val sources = FakeSources(
            mapOf("a" to listOf(Answer(50, listOf(title("tt1", "Denis Villeneuve: A Portrait"))))),
            peopleAnswer = Answer(200),
            person = listOf(villeneuve),
            credits = listOf(sicario),
            creditsAfterMs = 600,
        )
        val seen = collect(sources, "Denis Villeneuve")
        advanceTimeBy(500)
        runCurrent()
        assertFalse(seen.last().revealed, "the named person's films are waited for")
        advanceTimeBy(400)
        runCurrent()
        val shown = seen.last()
        assertTrue(shown.revealed)
        assertEquals(listOf(137427L), sources.creditsAsked)
        assertEquals(listOf(137427L), shown.people.map { it.tmdbId })
        assertTrue("tt3397884" in ids(shown), "their film is among the results")
    }

    @Test
    fun aMissingPeopleAnswerDelaysTheRevealOnlyToTheDeadline() = runTest {
        val sources = FakeSources(
            mapOf("a" to listOf(Answer(50, listOf(title("tt1", "Dune"))))),
            peopleAnswer = Answer(10_000),
        )
        val seen = collect(sources, "dune")
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(seen.last().revealed)
        advanceTimeBy(300)
        runCurrent()
        assertTrue(seen.last().revealed)
        assertEquals(emptyList(), seen.last().people)
    }

    @Test
    fun anUnreadableAddonListRevealsNothingAndSaysSo() = runTest {
        val seen = collect(FakeSources(emptyMap(), addonsFail = true), "dune")
        advanceTimeBy(200)
        runCurrent()
        assertTrue(seen.last().revealed)
        assertTrue(seen.last().addonsFailed)
        assertEquals(emptyList(), ids(seen.last()))
    }
}
