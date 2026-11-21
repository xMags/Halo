package moe.ditto.halo.magnets

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.people.PeopleAnswerException
import moe.ditto.halo.people.readCapabilities
import moe.ditto.halo.vectors.RuleVectors

/**
 * The shared magnet vectors (`packages/core/test/vectors/magnets.json`), the
 * cases `@halo/core`'s `magnets.test.ts` runs.
 */
class MagnetVectorsTest {
    private val vectors = HaloJson.parseToJsonElement(RuleVectors.magnets).jsonObject
    private val server = vectors.getValue("server").jsonPrimitive.content

    /** The vectors' size formatter. */
    private fun formatSize(bytes: Long?): String {
        if (bytes == null) return "UNKNOWN"
        val gib = bytes / 1024.0.pow(3)
        val decimals = if (gib >= 10) 1 else 2
        val scaled = kotlin.math.round(gib * 10.0.pow(decimals)) / 10.0.pow(decimals)
        val text = scaled.toString().let { if ('.' in it) it else "$it.0" }
        val (whole, fraction) = text.split('.')
        return "$whole.${fraction.padEnd(decimals, '0').take(decimals)} GB"
    }

    private fun JsonElement?.textOrNull() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A group of cases: a missing or empty one fails, so a test cannot pass with no cases. */
    private fun JsonObject.group(name: String): JsonArray {
        val rows = getValue(name).jsonArray
        assertTrue(rows.isNotEmpty(), "the vectors' $name group is empty")
        return rows
    }

    private fun request(element: JsonElement): MagnetRequest {
        val obj = element.jsonObject
        fun file(entry: JsonElement) = entry.jsonObject.let {
            MagnetFile(
                id = it.getValue("id").jsonPrimitive.content,
                name = it.getValue("name").jsonPrimitive.content,
                sizeBytes = it.getValue("sizeBytes").jsonPrimitive.long,
                season = it["season"]?.jsonPrimitive?.intOrNull,
                episode = it["episode"]?.jsonPrimitive?.intOrNull,
                playUrl = it.getValue("playUrl").jsonPrimitive.content,
            )
        }
        val identity = (obj["identified"] as? JsonObject)?.let {
            MagnetIdentity(it.getValue("type").jsonPrimitive.content, it.getValue("id").jsonPrimitive.content, it.getValue("name").jsonPrimitive.content, it["year"].textOrNull(), it["poster"].textOrNull())
        }
        return MagnetRequest(
            id = obj.getValue("id").jsonPrimitive.content,
            state = MagnetState.entries.first { it.wire == obj.getValue("state").jsonPrimitive.content },
            reason = obj["reason"].textOrNull(),
            name = obj["name"].textOrNull().orEmpty(),
            sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.long ?: 0,
            progress = obj["progress"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            files = (obj["files"] as? JsonArray).orEmpty().map(::file),
            identified = identity,
        )
    }

    /** Every field [expected] names matches, nested objects and lists included. */
    private fun assertFields(actual: Any?, expected: JsonElement, label: String) {
        when (expected) {
            is JsonArray -> {
                val list = actual as List<*>
                assertEquals(expected.size, list.size, "$label: length")
                expected.forEachIndexed { index, item -> assertFields(list[index], item, "$label[$index]") }
            }
            is JsonObject -> expected.forEach { (key, value) -> assertFields(property(actual, key), value, "$label.$key") }
            is JsonNull -> assertEquals(null, actual, label)
            is JsonPrimitive -> when {
                expected.isString -> assertEquals(expected.content, actual?.toString(), label)
                expected.booleanOrNull != null -> assertEquals(expected.boolean, actual, label)
                else -> assertEquals(expected.double, (actual as Number).toDouble(), label)
            }
        }
    }

    private fun property(owner: Any?, key: String): Any? = when (owner) {
        is MagnetRequest -> when (key) {
            "state" -> owner.state.wire
            "reason" -> owner.reason
            "name" -> owner.name
            "sizeBytes" -> owner.sizeBytes
            "progress" -> owner.progress
            "files" -> owner.files
            "identified" -> owner.identified
            else -> error("unknown request field $key")
        }
        is MagnetFile -> when (key) {
            "id" -> owner.id
            "sizeBytes" -> owner.sizeBytes
            "season" -> owner.season
            "episode" -> owner.episode
            "playUrl" -> owner.playUrl
            else -> error("unknown file field $key")
        }
        is MagnetIdentity -> when (key) {
            "type" -> owner.type
            "id" -> owner.id
            "year" -> owner.year
            "poster" -> owner.poster
            else -> error("unknown identity field $key")
        }
        is MagnetCard -> when (key) {
            "tone" -> owner.tone.name.lowercase()
            "title" -> owner.title
            "mono" -> owner.mono
            "plain" -> owner.plain
            "tail" -> owner.tail
            "secondary" -> owner.secondary
            "progress" -> owner.progress
            "action" -> owner.action.name.replaceFirstChar { it.lowercase() }
            "actionLabel" -> owner.actionLabel
            "showsPoster" -> owner.showsPoster
            else -> error("unknown card field $key")
        }
        null -> null
        else -> error("cannot read $key of $owner")
    }

    @Test
    fun capabilities() {
        for (row in vectors.group("capabilities").map { it.jsonObject }) {
            val read = readCapabilities(row.getValue("body"))
            val expected = row.getValue("expect").jsonObject
            assertEquals(expected.getValue("people").jsonPrimitive.boolean, read.people)
            assertEquals(expected.getValue("lookup").jsonPrimitive.boolean, read.lookup)
            assertEquals(expected.getValue("magnets").jsonPrimitive.boolean, read.magnets)
            assertEquals(expected.getValue("magnetsCachedOnly").jsonPrimitive.boolean, read.magnetsCachedOnly, row.toString())
        }
        assertFailsWith<PeopleAnswerException> { readCapabilities(JsonArray(listOf(JsonPrimitive(true)))) }
    }

    @Test
    fun aServerThatFetchesUncachedTorrentsWordsNotCachedOtherwise() {
        val request = readMagnet(HaloJson.parseToJsonElement("""{"id":"mg_a","state":"not_cached","sizeBytes":0}"""), server)
        val card = buildMagnetCard(MagnetPhase.Answered, "", request, cachedOnly = false, formatSize = ::formatSize)
        assertEquals("The server's debrid account couldn't start it. Try another magnet for the same title.", card.secondary)
    }

    @Test
    fun playLinksAreRebuiltOnTheAppServerOrRefused() {
        val links = vectors.getValue("playLinks").jsonObject
        for (row in links.group("rebuilt").map { it.jsonObject }) {
            assertEquals(row.getValue("link").jsonPrimitive.content, magnetPlayLinkOn(row.getValue("url").jsonPrimitive.content, row.getValue("on").jsonPrimitive.content))
        }
        for (url in links.group("refused").map { it.jsonPrimitive.content }) {
            assertEquals(null, magnetPlayLinkOn(url, server), url.take(100))
        }
        for (row in links.group("refusedServer").map { it.jsonObject }) {
            assertEquals(null, magnetPlayLinkOn(row.getValue("url").jsonPrimitive.content, row.getValue("on").jsonPrimitive.content))
        }
    }

    @Test
    fun magnetAnswers() {
        for (row in vectors.group("readerAccepted").map { it.jsonObject }) {
            val label = row.getValue("case").jsonPrimitive.content
            val read = readMagnet(row.getValue("body"), server)
            val expected = row.getValue("expect").jsonObject
            expected.forEach { (key, value) ->
                when (key) {
                    "nameLength" -> assertEquals(value.jsonPrimitive.content.toInt(), read.name.length, label)
                    "namePrefix" -> assertTrue(read.name.startsWith(value.jsonPrimitive.content), label)
                    else -> assertFields(property(read, key), value, "$label.$key")
                }
            }
        }
        vectors.group("readerRefused").forEachIndexed { index, body ->
            assertFailsWith<MagnetAnswerException>("refused[$index]") { readMagnet(body, server) }
        }
    }

    @Test
    fun theFileATitlePageOffers() {
        for (row in vectors.group("fileFor").map { it.jsonObject }) {
            val file = magnetFileFor(request(row.getValue("magnet")), row.getValue("type").jsonPrimitive.content, row.getValue("videoId").jsonPrimitive.content)
            assertEquals(row["file"].textOrNull(), file?.id, "${row["type"]} ${row["videoId"]}")
        }
    }

    @Test
    fun theActionCard() {
        vectors.group("cards").map { it.jsonObject }.forEachIndexed { index, row ->
            val phase = MagnetPhase.entries.first { it.name.lowercase() == row.getValue("phase").jsonPrimitive.content }
            val card = buildMagnetCard(phase, "Dune.2021.2160p", row["request"]?.let(::request), cachedOnly = true, formatSize = ::formatSize)
            row["expect"]?.jsonObject?.forEach { (key, value) ->
                when (key) {
                    "identityId" -> assertEquals(value.textOrNull(), card.identity?.id, "card $index")
                    "unidentifiedFileId" -> assertEquals(value.textOrNull(), card.unidentifiedFile?.id, "card $index")
                    else -> assertFields(property(card, key), value, "card $index.$key")
                }
            }
            row["expectTailStartsWith"]?.let { assertTrue(card.tail.startsWith(it.jsonPrimitive.content), "card $index: ${card.tail}") }
            listOf(card.title, card.secondary, card.mono, card.plain, card.tail, card.actionLabel).forEach {
                assertTrue('—' !in it, "card $index uses an em dash")
            }
        }
    }
}
