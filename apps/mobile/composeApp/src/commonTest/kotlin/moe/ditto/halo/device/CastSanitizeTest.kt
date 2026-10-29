package moe.ditto.halo.device

import kotlinx.serialization.json.Json
import moe.ditto.halo.api.MetaPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `sanitizeCast` in the shared core: addon output is untrusted. */
class CastSanitizeTest {
    @Test
    fun keepsBoundedNamesAndPartsAndOnlyHttpsPhotos() {
        val extras = Json.parseToJsonElement(
            """{"cast":[
                {"name":" Amy Adams ","character":"Louise","photo":"https://img.test/a.jpg"},
                {"name":"No Photo","photo":"http://img.test/b.jpg"},
                {"name":"","photo":"https://img.test/c.jpg"},
                {"character":"Nameless"},
                "not a person"
            ]}""",
        )
        assertEquals(
            listOf(MetaPerson("Amy Adams", "Louise", "https://img.test/a.jpg"), MetaPerson("No Photo", null, null)),
            sanitizeCast(extras),
        )
    }

    @Test
    fun aCastWithNobodyPicturedIsNone() {
        assertNull(sanitizeCast(Json.parseToJsonElement("""{"cast":[{"name":"A"}]}""")))
        assertNull(sanitizeCast(Json.parseToJsonElement("""{"cast":"nope"}""")))
        assertNull(sanitizeCast(null))
    }
}
