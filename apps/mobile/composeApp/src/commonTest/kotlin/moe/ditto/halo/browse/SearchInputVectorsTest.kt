package moe.ditto.halo.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.vectors.RuleVectors

/**
 * The shared pasted-input vectors (`packages/core/test/vectors/search-input.json`),
 * the cases `@halo/core`'s `searchInput.test.ts` runs.
 */
class SearchInputVectorsTest {
    /** The desktop's wording for a local path; a phone says "device", the only deliberate difference. */
    private val desktopLocalFile = "Files on this PC can't be opened from Search."

    private fun fields(input: SearchInput): Map<String, String> = when (input) {
        SearchInput.Text -> mapOf("kind" to "text")
        is SearchInput.ImdbTitle -> mapOf("kind" to "imdbTitle", "imdbId" to input.imdbId)
        is SearchInput.ImdbPerson -> mapOf("kind" to "imdbPerson", "personId" to input.personId)
        is SearchInput.TvdbNumeric -> mapOf("kind" to "tvdbNumeric", "tvdbId" to input.tvdbId, "tvdbKind" to input.tvdbKind.wire)
        is SearchInput.TvdbSlug -> mapOf("kind" to "tvdbSlug", "tvdbKind" to input.tvdbKind.wire, "displayName" to input.displayName)
        is SearchInput.Magnet -> mapOf("kind" to "magnet", "infoHash" to input.infoHash, "displayName" to input.displayName)
        is SearchInput.WebLink -> mapOf("kind" to "webLink", "url" to input.url, "host" to input.host, "displayName" to input.displayName)
        is SearchInput.Unsupported -> mapOf("kind" to "unsupported", "reason" to input.reason)
    }

    @Test
    fun pastedInputReadsAsItsKindWithItsFields() {
        val rows = HaloJson.parseToJsonElement(RuleVectors.searchInput).jsonObject.getValue("rows").jsonArray
        assertTrue(rows.size >= 60)
        for (row in rows.map { it.jsonObject }) {
            val input = row.getValue("input").jsonPrimitive.content
            val actual = fields(classifySearchInput(input))
            val label = input.take(80)
            for ((name, value) in row) {
                if (name == "input") continue
                val expected = value.jsonPrimitive.content.let { if (it == desktopLocalFile) UnsupportedReasons.LocalFile else it }
                assertEquals(expected, actual[name], "$label: $name")
            }
        }
    }

    @Test
    fun aMagnetKeepsNothingOfItsTrackers() {
        val input = classifySearchInput("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Film&tr=udp%3A%2F%2Ftracker.example%3A1337")
        assertEquals(SearchInput.Magnet("c12fe1c06bba254a9dc9f519b335aa7c1367a88a", "Film"), input)
    }

    @Test
    fun emptyInputIsWords() {
        assertEquals(SearchInput.Text, classifySearchInput(""))
    }
}
