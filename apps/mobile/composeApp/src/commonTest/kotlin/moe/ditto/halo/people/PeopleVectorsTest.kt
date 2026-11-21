package moe.ditto.halo.people

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.vectors.RuleVectors

/**
 * The shared people vectors (`packages/core/test/vectors/people.json`), the
 * cases `@halo/core`'s `people.test.ts` runs.
 */
class PeopleVectorsTest {
    private val vectors = HaloJson.parseToJsonElement(RuleVectors.people).jsonObject

    private fun JsonObject.list(name: String) = getValue(name).jsonArray.map { it.jsonObject }

    /** A group of cases: an empty one fails, so a test cannot pass with no cases. */
    private fun JsonObject.group(name: String): List<JsonObject> {
        val rows = list(name)
        assertTrue(rows.isNotEmpty(), "the vectors' $name group is empty")
        return rows
    }
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonElement?.textOrNull() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun strings(element: JsonElement?) = element?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun person(obj: JsonObject) = PersonSummary(
        tmdbId = obj.getValue("tmdbId").jsonPrimitive.long,
        name = obj.text("name"),
        knownFor = PersonDepartment.entries.firstOrNull { it.wire == obj["knownFor"].textOrNull() },
        photo = obj["photo"].textOrNull(),
        popularity = obj.getValue("popularity").jsonPrimitive.double,
        knownForTitles = strings(obj["knownForTitles"]),
    )

    private fun credit(obj: JsonObject) = PersonCredit(
        type = obj.text("type"),
        imdbId = obj.text("imdbId"),
        tmdbId = obj["tmdbId"]?.jsonPrimitive?.long ?: 0,
        name = obj.text("name"),
        year = obj["year"].textOrNull(),
        poster = obj["poster"].textOrNull(),
        roles = roles(obj["roles"]),
        character = obj["character"].textOrNull(),
        popularity = obj["popularity"]?.jsonPrimitive?.double ?: 0.0,
    )

    private fun roles(element: JsonElement?) = strings(element).map { wire -> PersonCreditRole.entries.first { it.wire == wire } }

    private fun filter(wire: String) = FilmographyFilter.entries.first { it.name.lowercase() == wire }

    @Test
    fun thePeopleRow() {
        for (row in vectors.group("peopleRow")) {
            val tier = row.getValue("bestTitleTier").jsonPrimitive.intOrNull
            val chosen = choosePeopleRow(row.list("people").map(::person), row.text("query"), tier).map { it.tmdbId }
            assertEquals(row.getValue("ids").jsonArray.map { it.jsonPrimitive.long }, chosen, "${row.text("query")} / $tier")
        }
        for (row in vectors.group("bestTitleTier")) {
            assertEquals(row.getValue("tier").jsonPrimitive.intOrNull, bestTitleTier(strings(row["names"]), row.text("query"), 2026), row.text("query"))
        }
        for (row in vectors.group("exactNamePerson")) {
            val expected = (row.getValue("id") as? JsonPrimitive)?.takeUnless { it is JsonNull }?.long
            assertEquals(expected, exactNamePerson(row.list("people").map(::person), row.text("name"))?.tmdbId, row.text("name"))
        }
        val titles = vectors.getValue("creditTitles").jsonObject
        val read = creditTitles(titles.list("credits").map { obj ->
            PersonCredit(obj.text("type"), obj.text("imdbId"), 0, obj.text("name"), obj["year"].textOrNull(), obj["poster"].textOrNull(), listOf(PersonCreditRole.Cast), null, 0.0)
        })
        val expected = titles.list("titles")
        assertEquals(expected.size, read.size)
        expected.zip(read).forEach { (want, got) ->
            assertEquals(want.text("id"), got.id)
            assertEquals(want.text("imdb_id"), got.imdbId)
            assertEquals(want.text("type"), got.type)
            assertEquals(want.text("name"), got.name)
            assertEquals(want["poster"].textOrNull(), got.poster)
            assertEquals(want["releaseInfo"].textOrNull(), got.releaseInfo)
        }
        for (row in vectors.group("personRowLine")) {
            val department = PersonDepartment.entries.firstOrNull { it.wire == row["knownFor"].textOrNull() }
            assertEquals(row.text("line"), personRowLine(department, strings(row["knownForTitles"])))
        }
    }

    @Test
    fun theFilmography() {
        val film = vectors.getValue("filmography").jsonObject
        val credits = film.list("credits").map(::credit)
        for ((wire, ids) in film.getValue("byFilter").jsonObject) {
            assertEquals(strings(ids), filmography(credits, filter(wire)).map { it.imdbId }, wire)
        }
        assertEquals(strings(film["filters"]).map(::filter), filmographyFilters(credits))
        assertEquals(strings(film["directorOnlyFilters"]).map(::filter), filmographyFilters(listOf(credits[0])))
        assertEquals(listOf(FilmographyFilter.All), filmographyFilters(emptyList()))
        for (row in film.group("creditLines")) assertEquals(row.text("line"), creditLine(credits[row.getValue("index").jsonPrimitive.int]))
        for (row in film.group("roleLabels")) assertEquals(row.text("label"), creditRoleLabel(roles(row["roles"])))
    }

    @Test
    fun tmdbCredentialsByShape() {
        for (row in vectors.group("credentials")) {
            val parsed = parseTmdbCredential(row.text("pasted"))
            val kind = when (parsed) {
                is TmdbCredential.ApiKey -> "apiKey"
                is TmdbCredential.ReadToken -> "readToken"
                null -> null
            }
            assertEquals(row["kind"].textOrNull(), kind, row.text("pasted").take(40))
            row["value"].textOrNull()?.let { assertEquals(it, parsed?.value) }
        }
    }

    @Test
    fun misspelledNames() = runTest {
        for (row in vectors.group("closestWords")) assertEquals(strings(row["words"]), closestPeopleWords(row.text("query")), row.text("query"))
        for (row in vectors.group("editDistance")) {
            assertEquals(row.getValue("distance").jsonPrimitive.int, editDistance(row.text("a"), row.text("b"), row.getValue("limit").jsonPrimitive.int))
        }
        for (row in vectors.group("allowedDistance")) {
            assertEquals(row.getValue("allowed").jsonPrimitive.int, allowedDistance(row.getValue("length").jsonPrimitive.int))
        }
        for (row in vectors.group("closestPeople")) {
            val answers = row.getValue("answers").jsonObject
            val closest = closestPeople({ word -> answers[word]?.jsonArray?.map { person(it.jsonObject) }.orEmpty() }, row.text("query"))
            assertEquals(row["corrected"].textOrNull(), closest?.corrected, row.text("query"))
            assertEquals(row.getValue("ids").jsonArray.map { it.jsonPrimitive.long }, closest?.people?.map { it.tmdbId }.orEmpty())
        }
        for (row in vectors.group("endAtSentence")) {
            assertEquals(row.text("cut"), endAtSentence(row.text("text"), row.getValue("max").jsonPrimitive.int))
        }
    }

    @Test
    fun theReadersKeepOnlySafeFields() {
        val search = readPeopleSearch(
            HaloJson.parseToJsonElement(
                """{"people":[{"tmdbId":137427,"name":" Denis Villeneuve ","knownFor":"Directing","photo":"https://image.tmdb.org/t/p/w185/dv.jpg","popularity":12.5},
                {"tmdbId":137427,"name":"Again"},{"tmdbId":3,"name":"Elsewhere","photo":"https://evil.example/a.jpg","knownFor":"Lighting","popularity":-1},
                {"tmdbId":0,"name":"Zero"},{"tmdbId":4,"name":"Line\u0007Break","knownForTitles":["","Real",7]}],"corrected":"Somebody Else"}""",
            ),
        )
        assertEquals(listOf(137427L, 3L, 4L), search.people.map { it.tmdbId })
        assertEquals("Denis Villeneuve", search.people[0].name)
        assertEquals(null, search.people[1].photo)
        assertEquals("Line Break", search.people[2].name)
        assertEquals(listOf("Real"), search.people[2].knownForTitles)
        assertEquals(null, search.corrected)
        val person = readPerson(HaloJson.parseToJsonElement("""{"person":{"tmdbId":1,"name":"A","biography":"Line one.\r\nLine two ."},"partial":true}"""))
        assertEquals("Line one.\nLine two .", person.person.biography)
        assertEquals(true, person.partial)
        assertEquals(
            IdLookup.Title("Interstellar", "movie", "tt0816692", "2014", null),
            readIdLookup(HaloJson.parseToJsonElement("""{"kind":"title","name":"Interstellar","type":"movie","id":"tt0816692","year":"2014"}""")),
        )
    }
}
