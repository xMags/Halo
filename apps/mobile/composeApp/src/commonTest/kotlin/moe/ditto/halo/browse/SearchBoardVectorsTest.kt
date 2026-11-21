package moe.ditto.halo.browse

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.vectors.RuleVectors

/**
 * The shared search vectors (`packages/core/test/vectors/search-board.json`),
 * the same cases `@halo/core`'s `searchBoard.test.ts` runs, so the two apps
 * rank, cut and grow their results alike.
 */
class SearchBoardVectorsTest {
    private val vectors = HaloJson.parseToJsonElement(RuleVectors.searchBoard).jsonObject
    private val currentYear = vectors.getValue("currentYear").jsonPrimitive.int

    private fun parts(query: String) = parseSearchQuery(query, currentYear)

    private fun title(element: JsonElement): MetaPreview {
        val meta = HaloJson.decodeFromJsonElement<MetaPreview>(element)
        val missing = element.jsonObject["posterMissing"]?.jsonPrimitive?.booleanOrNull == true
        return if (missing) meta.copy(posterMissing = true) else meta
    }

    private fun answer(element: JsonElement): SearchAnswer {
        val obj = element.jsonObject
        return SearchAnswer(
            key = obj.getValue("target").jsonPrimitive.content,
            titles = obj.getValue("titles").jsonArray.map(::title),
            addonRank = obj.getValue("addonRank").jsonPrimitive.int,
        )
    }

    private fun JsonObject.list(name: String): List<JsonElement> = this[name]?.jsonArray.orEmpty()

    /** A top-level group of cases: a missing or empty one fails, so a renamed group cannot pass with no cases. */
    private fun JsonObject.group(name: String): List<JsonObject> {
        val rows = getValue(name).jsonArray.map { it.jsonObject }
        assertTrue(rows.isNotEmpty(), "the vectors' $name group is empty")
        return rows
    }
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun strings(element: JsonElement?): List<String> = element?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun ids(titles: List<MetaPreview>) = titles.map { it.id }
    private fun meaning(obj: JsonObject) =
        if (obj["meaning"]?.jsonPrimitive?.content == "person") SearchQueryMeaning.Person else SearchQueryMeaning.Title

    /** The fields of a title the vectors name. */
    private fun field(title: MetaPreview?, name: String): String? = when (name) {
        "id" -> title?.id
        "name" -> title?.name
        "poster" -> title?.poster
        "releaseInfo" -> title?.releaseInfo
        "imdbRating" -> title?.imdbRating
        "description" -> title?.description
        else -> error("vector names an unknown field $name")
    }

    private fun assertFields(expected: JsonObject, actual: MetaPreview?, label: String, skip: Set<String> = emptySet()) {
        for ((name, value) in expected) {
            if (name in skip) continue
            assertEquals((value as? JsonPrimitive)?.content, field(actual, name), "$label: $name")
        }
    }

    @Test
    fun nameRelevance() {
        for (row in vectors.group("nameRelevance")) {
            assertEquals(row.getValue("tier").jsonPrimitive.int, nameRelevance(row.string("name"), row.string("query")), row.toString())
        }
    }

    @Test
    fun aTrailingYearIsReadApart() {
        for (row in vectors.group("parseQuery")) {
            val parsed = parts(row.string("query"))
            assertEquals(row.string("full"), parsed.full)
            assertEquals(row.string("name"), parsed.name)
            assertEquals(row.getValue("year").jsonPrimitive.intOrNull, parsed.year, row.string("query"))
        }
    }

    @Test
    fun whatCountsAsNoPoster() {
        for (row in vectors.group("hasNoPoster")) {
            val meta = MetaPreview(id = row["id"]?.jsonPrimitive?.content ?: "x", type = "movie", name = "x", poster = row["poster"]?.jsonPrimitive?.content)
            assertEquals(row.getValue("none").jsonPrimitive.boolean, hasNoPoster(meta), row.toString())
        }
    }

    @Test
    fun rankingForAReveal() {
        for (row in vectors.group("rank")) {
            val label = row.string("case")
            val answers = row.list("answers").map(::answer)
            val ranked = rankForReveal(answers, parts(row.string("query")))
            val keys = ranked.map { "${it.title.type}:${it.title.id}" }
            val sorted = row["sorted"]?.jsonPrimitive?.booleanOrNull == true
            assertEquals(strings(row["keys"]), if (sorted) keys.sorted() else keys, label)
            row["first"]?.jsonObject?.let { assertFields(it, ranked.first().title, label) }
            row["firstTier"]?.let { assertEquals(it.jsonPrimitive.int, ranked.first().tier, label) }
            row["firstRank"]?.jsonObject?.let {
                val first = ranked.first()
                assertEquals(it.getValue("copies").jsonPrimitive.int, first.copies, label)
                assertEquals(it.getValue("bestPosition").jsonPrimitive.int, first.bestPosition, label)
                assertEquals(it.getValue("addonRank").jsonPrimitive.int, first.addonRank, label)
            }
            if (row["reversedToo"]?.jsonPrimitive?.booleanOrNull == true) {
                val reversed = rankForReveal(answers.reversed(), parts(row.string("query"))).map { "${it.title.type}:${it.title.id}" }
                assertEquals(keys, reversed, "$label: reversed")
            }
        }
    }

    @Test
    fun byRelevanceKeepsEachLevelInOrder() {
        for (row in vectors.group("byRelevance")) {
            val titles = row.list("titles").map(::title)
            val ordered = titles.withIndex().sortedWith(compareBy({ nameRelevance(it.value.name, row.string("query")) }, { it.index })).map { it.value.id }
            assertEquals(strings(row["ids"]), ordered)
        }
    }

    @Test
    fun oneCatalogJudgedByTheCutoffs() {
        for (row in vectors.group("refine")) {
            val label = row.string("case")
            val ranked = rankForReveal(listOf(SearchAnswer("t/movie", row.list("titles").map(::title), 0)), parts(row.string("query")))
            val cutoffs = computeCutoffs(ranked)
            val kept = ranked.filter { passesCutoffs(it, cutoffs) }.map { it.title.id }
            row["ids"]?.let { assertEquals(strings(it), kept, label) }
            row["count"]?.let { assertEquals(it.jsonPrimitive.int, kept.size, label) }
            strings(row["absent"]).forEach { assertTrue(it !in kept, "$label: $it") }
            row["lastId"]?.let { assertEquals(it.jsonPrimitive.content, kept.last(), label) }
        }
    }

    @Test
    fun theBoardRevealsOnceAndLateAnswersOnlyFillInOrAppend() {
        for (row in vectors.group("board")) {
            val label = row.string("case")
            val board = SearchBoard()
            val revealed = board.reveal(row.list("answers").map(::answer), parts(row.string("query")), meaning(row))
            row["top"]?.let { expected ->
                assertEquals(if (expected is JsonNull) null else expected.jsonPrimitive.content, revealed.topMatch?.id, "$label: top")
            }
            row["titles"]?.let { assertEquals(strings(it), ids(revealed.titles), "$label: titles") }
            row["cutoffs"]?.jsonObject?.forEach { (name, value) ->
                val actual = when (name) {
                    "dropUnrelated" -> revealed.cutoffs.dropUnrelated
                    "dropSomeWords" -> revealed.cutoffs.dropSomeWords
                    "dropArtless" -> revealed.cutoffs.dropArtless
                    "keepCredited" -> revealed.cutoffs.keepCredited
                    else -> error("unknown cutoff $name")
                }
                assertEquals(value.jsonPrimitive.boolean, actual, "$label: $name")
            }
            row.list("steps").forEachIndexed { index, element ->
                val step = element.jsonObject
                val stepLabel = "$label: step $index"
                step["admitPersonWork"]?.let {
                    assertEquals(it.jsonPrimitive.boolean, board.admitPersonWork(), stepLabel)
                    return@forEachIndexed
                }
                val diff = board.absorb(answer(step.getValue("absorb")))
                step["appends"]?.let { assertEquals(strings(it), ids(diff.appends), "$stepLabel: appends") }
                step["updates"]?.jsonArray?.let { expected ->
                    assertEquals(expected.size, diff.updates.size, "$stepLabel: updates")
                    expected.forEachIndexed { at, update ->
                        val (index2, updated) = diff.updates[at]
                        assertEquals(update.jsonObject.getValue("index").jsonPrimitive.int, index2, stepLabel)
                        assertFields(update.jsonObject, updated, stepLabel, skip = setOf("index"))
                        assertEquals(updated, board.titles[index2], "$stepLabel: kept on the board")
                    }
                }
                when (val top = step["topMatchUpdate"]) {
                    null -> Unit
                    is JsonNull -> assertNull(diff.topMatchUpdate, "$stepLabel: top match")
                    else -> assertFields(top.jsonObject, diff.topMatchUpdate, "$stepLabel: top")
                }
            }
            row["finalTitles"]?.let { assertEquals(strings(it), ids(board.titles), "$label: final") }
            row["finalTop"]?.let { assertEquals(it.jsonPrimitive.content, board.topMatch?.id, "$label: final top") }
        }
    }

    @Test
    fun posterLinksWorthCheckingBeforeTheReveal() {
        for (row in vectors.group("posterLinks")) {
            val answers = row.list("answers").map(::answer)
            assertEquals(strings(row["links"]), revealPosterLinks(answers, parts(row.string("query")), meaning(row), row.getValue("limit").jsonPrimitive.int))
            assertTrue(revealPosterLinks(answers, parts(row.string("query")), meaning(row), 2).size <= 2)
        }
    }

    @Test
    fun mergingCopiesAndTheMissingPosterMark() {
        for (row in vectors.group("mergeCopies")) {
            val merged = mergeCopies(title(row.getValue("kept")), title(row.getValue("other")))
            assertEquals(row.getValue("posterMissing").jsonPrimitive.boolean, merged.posterMissing, row.string("case"))
            row["poster"]?.let { assertEquals(it.jsonPrimitive.content, merged.poster, row.string("case")) }
        }
    }

    @Test
    fun whenResultsAreRevealed() {
        for (row in vectors.group("reveal")) {
            val decision = decideReveal(
                row.getValue("pending").jsonPrimitive.int,
                row.getValue("total").jsonPrimitive.int,
                row.getValue("answeredWithItems").jsonPrimitive.int,
                row.getValue("sinceMs").jsonPrimitive.long,
            )
            assertEquals(row.string("decision"), decision.name.lowercase(), row.toString())
        }
    }

    @Test
    fun suggestionsAndNotes() {
        for (row in vectors.group("suggestions")) {
            assertEquals(strings(row["expect"]), searchSuggestions(strings(row["history"]), row.string("typed"), strings(row["found"])), row.string("typed"))
        }
        for (row in vectors.group("failureNote")) {
            assertEquals(row.string("note"), searchFailureNote(strings(row["failed"]), row.getValue("total").jsonPrimitive.int))
        }
        for (row in vectors.group("stillAsking")) {
            assertEquals(row.string("note"), searchStillAskingNote(row.getValue("pending").jsonPrimitive.int))
        }
    }

    @Test
    fun queryLimits() {
        assertEquals(120, cappedQuery("a".repeat(130)).length)
        assertEquals(119, cappedQuery("a".repeat(119) + "😀").length)
        assertTrue(!isSearchableQuery(" a "))
        assertTrue(isSearchableQuery(" ab "))
    }

    @Test
    fun anUnrevealedBoardAbsorbsNothing() {
        val board = SearchBoard()
        assertTrue(board.absorb(SearchAnswer("t", listOf(MetaPreview("tt1", "movie", "Dune")), 0)).isEmpty)
        assertTrue(board.titles.isEmpty())
        assertTrue(!board.admitPersonWork())
    }

    @Test
    fun whateverTheArrivalOrderALateAnswerNeverMovesAShownCard() {
        fun full(id: String, name: String, year: String = "2016", imdbId: String? = null, rating: String? = null) =
            MetaPreview(id = id, type = "movie", name = name, releaseInfo = year, poster = "https://image.tmdb.org/t/p/w500/$id.jpg", imdbId = imdbId, imdbRating = rating)
        val answers = listOf(
            SearchAnswer("c/movie", listOf(full("tt1160419", "Dune", "2021"), full("tt15239678", "Dune: Part Two"), full("tt0087182", "Dune", "1984")), 0),
            SearchAnswer("c/series", listOf(MetaPreview("tt10466872", "series", "Dune: Prophecy", releaseInfo = "2024"), MetaPreview("tt0142032", "series", "Dune", releaseInfo = "2000")), 0),
            SearchAnswer("t/movie", listOf(full("tmdb:438631", "Dune", "2021", "tt1160419", "7.8"), full("tmdb:693134", "Dune: Part Two", imdbId = "tt15239678"), full("tmdb:1", "Dune Drifter")), 1),
            SearchAnswer("m/movie", listOf(MetaPreview("mf1706190", "movie", "Dune (2021)"), MetaPreview("mf17", "movie", "Dune World"), full("tmdb:2", "Faust and the Devil")), 2),
            SearchAnswer("o/movie", listOf(MetaPreview("tt0087182", "movie", "Dune", releaseInfo = "1984", imdbRating = "6.3"), full("o:3", "Dune Messiah"), full("o:4", "Children of Dune")), 3),
        )
        val key = { meta: MetaPreview -> "${meta.type}:${meta.id}" }
        for (seed in 1..40) {
            val order = answers.shuffled(Random(seed))
            val revealedCount = seed % order.size + 1
            for (query in listOf("dune", "dune 2021")) {
                val board = SearchBoard()
                val revealed = board.reveal(order.take(revealedCount), parts(query))
                var shown = revealed.titles.map(key)
                val top = revealed.topMatch?.let(key)
                for (late in order.drop(revealedCount)) {
                    val before = board.titles.size
                    val diff = board.absorb(late)
                    val now = board.titles.map(key)
                    assertEquals(before + diff.appends.size, now.size, "seed $seed")
                    assertEquals(shown, now.take(shown.size), "seed $seed")
                    diff.updates.forEach { (at, updated) ->
                        assertTrue(at < before, "seed $seed")
                        assertEquals(key(updated), now[at], "seed $seed")
                    }
                    assertEquals(top, board.topMatch?.let(key), "seed $seed")
                    shown = now
                }
            }
        }
    }

    @Test
    fun searchTargetsAreFilmAndSeriesCatalogsThatNeedOnlyTheQuery() {
        val manifest = HaloJson.decodeFromString<moe.ditto.halo.api.AddonEntry>(
            """{"id":"a","transportUrl":"https://a.test/manifest.json","position":0,"manifest":{"id":"a","version":"1","name":"A","resources":[],"types":[],"catalogs":[
              {"type":"movie","id":"top","extra":[{"name":"search"}]},
              {"type":"series","id":"legacy","extraSupported":["search"]},
              {"type":"movie","id":"genre","extra":[{"name":"search"},{"name":"genre","isRequired":true}]},
              {"type":"tv","id":"channels","extra":[{"name":"search"}]},
              {"type":"other","id":"torrents","extra":[{"name":"search"}]},
              {"type":"movie","id":"browse"}]}}""",
        )
        assertEquals(listOf("a/movie/top", "a/series/legacy"), boardTargets(listOf(manifest)).map { it.key })
    }
}
