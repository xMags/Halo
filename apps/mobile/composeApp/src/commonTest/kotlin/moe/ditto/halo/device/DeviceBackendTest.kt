package moe.ditto.halo.device

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.Manifest
import moe.ditto.halo.api.ManifestCatalog
import moe.ditto.halo.api.ManifestResource
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.auth.EpochClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The same cases as `packages/core/test/deviceBackend.test.ts`, so the phone's
 * port and the shared resolver are held to one set of rules.
 */
class DeviceBackendTest {

    @Test
    fun installingAddonsFetchesEachManifestOnceAndListsThemInOrder() = realTimeTest {
        val fixture = Fixture()
        val installed = fixture.backend.putAddons(listOf(CatalogAddon, StreamA))
        assertEquals(listOf(CatalogAddon to 0, StreamA to 1), installed.map { it.transportUrl to it.position })
        assertEquals(listOf("catalog", "stream-a"), installed.map { it.manifest.id })
        val listed = fixture.backend.getAddons()
        assertEquals(emptyList(), listed.global)
        assertEquals(installed, listed.user)

        // Kept URLs keep their id and are not fetched again; dropped ones go; order follows the list.
        fixture.calls.clear()
        val next = fixture.backend.putAddons(listOf(StreamB, CatalogAddon))
        assertEquals(listOf(StreamB, CatalogAddon), next.map { it.transportUrl })
        assertEquals(installed[0].id, next[1].id)
        assertEquals(listOf(StreamB), fixture.calls)
    }

    @Test
    fun oneBadManifestFailsTheWholeInstallAndLeavesTheListAsItWas() = realTimeTest {
        val broken = "https://broken.test/manifest.json"
        val fixture = Fixture(mapOf(broken to json("""{"id":"","name":"x"}""")))
        fixture.backend.putAddons(listOf(CatalogAddon))
        val error = assertStatus(400) { fixture.backend.putAddons(listOf(CatalogAddon, StreamA, broken)) }
        assertTrue(error.message!!.contains("could not fetch a valid manifest for https://broken.test"))
        assertEquals(listOf(CatalogAddon), fixture.backend.getAddons().user.map { it.transportUrl })
    }

    @Test
    fun addonListsAreRefusedForDuplicatesNonHttpUrlsAndMoreThanFifty() = realTimeTest {
        val backend = Fixture().backend
        assertStatus(400) { backend.putAddons(listOf(StreamA, StreamA)) }
        assertStatus(400) { backend.putAddons(listOf("ftp://addon.test/manifest.json")) }
        assertStatus(400) { backend.putAddons(listOf("not a url")) }
        assertStatus(400) { backend.putAddons(List(51) { "https://a$it.test/manifest.json" }) }
    }

    @Test
    fun hidingCatalogsStripsThemFromTheListButKeepsTheAddonAnswering() = realTimeTest {
        val fixture = Fixture(
            mapOf("https://catalog.test/catalog/movie/top.json" to json("""{"metas":[{"id":"tt1","type":"movie","name":"One"}]}""")),
        )
        val catalog = fixture.backend.putAddons(listOf(CatalogAddon)).single()
        fixture.backend.patchAddon(catalog.id, hideCatalogs = true)
        val listed = fixture.backend.getAddons().user.single()
        assertTrue(listed.hideCatalogs)
        assertEquals(emptyList(), listed.manifest.catalogs)
        assertEquals("tt1", fixture.backend.getCatalog(catalog.id, "movie", "top").metas.single().id)

        fixture.backend.patchAddon(catalog.id, hideCatalogs = false)
        assertEquals(1, fixture.backend.getAddons().user.single().manifest.catalogs.size)
        assertStatus(404) { fixture.backend.patchAddon("missing", hideCatalogs = true) }
    }

    @Test
    fun catalogsRefuseUnknownAddonsBoundExtrasAndReportUpstreamFailuresAs502() = realTimeTest {
        val fixture = Fixture(
            mapOf(
                "https://catalog.test/catalog/movie/top/search=dune.json" to
                    json("""{"metas":[{"id":"tt2","type":"movie","name":"Dune"}]}"""),
            ),
        )
        val catalog = fixture.backend.putAddons(listOf(CatalogAddon)).single()
        assertEquals("Dune", fixture.backend.getCatalog(catalog.id, "movie", "top", mapOf("search" to "dune")).metas.single().name)
        assertStatus(403) { fixture.backend.getCatalog("nope", "movie", "top") }
        assertStatus(400) { fixture.backend.getCatalog(catalog.id, "movie", "top", mapOf("search" to "x".repeat(257))) }
        assertStatus(400) { fixture.backend.getCatalog(catalog.id, "movie", "top", (0..8).associate { "k$it" to "v" }) }
        assertStatus(502) { fixture.backend.getCatalog(catalog.id, "movie", "missing") }
    }

    @Test
    fun metaComesFromTheFirstAddonThatCanDescribeTheIdSkippingFailures() = realTimeTest {
        val second = "https://meta-two.test/manifest.json"
        val fixture = Fixture(
            mapOf(
                second to manifestJson(manifest("meta-two", resources = listOf("meta"), idPrefixes = listOf("tt"))),
                "https://catalog.test/meta/movie/tt1.json" to Reply(HttpStatusCode.InternalServerError, "boom"),
                "https://meta-two.test/meta/movie/tt1.json" to json("""{"meta":{"id":"tt1","type":"movie","name":"From two"}}"""),
            ),
        )
        fixture.backend.putAddons(listOf(StreamA, CatalogAddon, second))
        assertEquals("From two", fixture.backend.getMeta("movie", "tt1").meta.name)
        assertStatus(404) { fixture.backend.getMeta("movie", "kitsu:1") }
    }

    @Test
    fun streamsAskEveryCapableAddonDropUnplayableOnesAndReportFailuresByCode() = realTimeTest {
        val hanging = "https://hanging.test/manifest.json"
        val invalid = "https://invalid.test/manifest.json"
        val fixture = Fixture(
            mapOf(
                hanging to manifestJson(streamManifest("hanging")),
                invalid to manifestJson(streamManifest("invalid")),
                "https://stream-a.test/stream/movie/tt1.json" to json(
                    """{"streams":[{"url":"https://cdn.test/a.mkv","name":"A","behaviorHints":{"bingeGroup":"a"}},""" +
                        """{"infoHash":"abc"},{"url":"magnet:?xt=1"}]}""",
                ),
                "https://stream-b.test/stream/movie/tt1.json" to Reply(HttpStatusCode.ServiceUnavailable, "down"),
                "https://hanging.test/stream/movie/tt1.json" to Hang,
                "https://invalid.test/stream/movie/tt1.json" to json("""{"nope":true}"""),
            ),
            timeoutMillis = 50,
        )
        fixture.backend.putAddons(listOf(StreamA, StreamB, hanging, invalid, CatalogAddon))
        val result = fixture.backend.getStreams("movie", "tt1")
        val only = result.results.single()
        assertEquals("stream-a", only.addon.name)
        assertEquals(listOf("https://cdn.test/a.mkv"), only.streams.map { it.url })
        assertEquals("A", only.streams.single().name)
        assertEquals("a", only.streams.single().behaviorHints?.bingeGroup)
        assertEquals(
            listOf(
                Triple("stream-b", "upstream_http", 503),
                Triple("hanging", "timeout", null),
                Triple("invalid", "invalid_response", null),
            ),
            result.errors.map { Triple(it.name, it.code, it.status) },
        )
    }

    @Test
    fun subtitlesValidateTheHashLeaveOutEmptyValuesAndReportHashMatching() = realTimeTest {
        val fixture = Fixture(
            mapOf(
                "https://stream-a.test/subtitles/movie/tt1/videoHash=0123456789abcdef&videoSize=42.json" to json(
                    """{"subtitles":[{"id":"s1","url":"https://subs.test/1.srt","lang":"eng"},""" +
                        """{"id":"s2","url":"javascript:alert(1)","lang":"eng"}]}""",
                ),
                "https://stream-a.test/subtitles/movie/tt1.json" to json("""{"subtitles":[]}"""),
            ),
        )
        fixture.backend.putAddons(listOf(StreamA))
        assertStatus(400) { fixture.backend.getSubtitles("movie", "tt1", videoHash = "xyz") }
        assertStatus(400) { fixture.backend.getSubtitles("movie", "tt1", videoSize = 0) }

        val matched = fixture.backend.getSubtitles("movie", "tt1", videoHash = "0123456789abcdef", videoSize = 42)
        assertTrue(matched.hashMatched)
        assertEquals(listOf("s1"), matched.results.single().subtitles.map { it.id })

        fixture.calls.clear()
        val bare = fixture.backend.getSubtitles("movie", "tt1", videoHash = "", filename = "")
        assertEquals(false, bare.hashMatched)
        assertEquals(listOf("https://stream-a.test/subtitles/movie/tt1.json"), fixture.calls)
    }

    @Test
    fun nextEpisodeMatchesTheSameAddonAndExactBingeGroupOnly() = realTimeTest {
        val fixture = Fixture(
            mapOf(
                "https://catalog.test/meta/series/tt9.json" to json(
                    """{"meta":{"id":"tt9","type":"series","name":"Show","videos":[""" +
                        """{"id":"tt9:1:1","title":"One","season":1,"episode":1},""" +
                        """{"id":"tt9:1:2","title":"Two","season":1,"episode":2}]}}""",
                ),
                // Ids are URL-encoded in the path.
                "https://stream-a.test/stream/series/tt9%3A1%3A2.json" to json(
                    """{"streams":[{"url":"https://cdn.test/other.mkv","behaviorHints":{"bingeGroup":"other"}},""" +
                        """{"url":"https://cdn.test/two.mkv","behaviorHints":{"bingeGroup":"grp"}}]}""",
                ),
            ),
        )
        val streamA = fixture.backend.putAddons(listOf(CatalogAddon, StreamA))[1]
        val matched = fixture.backend.getNextEpisode("series", "tt9", "tt9:1:1", streamA.id, "grp")
        assertEquals("tt9:1:2", matched.video?.id)
        assertEquals("https://cdn.test/two.mkv", matched.stream?.url)

        val unmatched = fixture.backend.getNextEpisode("series", "tt9", "tt9:1:1", streamA.id, "none")
        assertEquals("tt9:1:2", unmatched.video?.id)
        assertNull(unmatched.stream)

        val last = fixture.backend.getNextEpisode("series", "tt9", "tt9:1:2")
        assertNull(last.video)
        assertNull(last.stream)
        assertStatus(404) { fixture.backend.getNextEpisode("series", "tt404", "tt404:1:1") }
    }

    @Test
    fun nextEpisodeSkipsAnUnairedFollowerAndOrdersSpecialsLast() {
        val videos = listOf(
            moe.ditto.halo.api.MetaVideo(id = "s0e1", season = 0, episode = 1),
            moe.ditto.halo.api.MetaVideo(id = "s1e2", season = 1, episode = 2, released = "2030-01-01T00:00:00.000Z"),
            moe.ditto.halo.api.MetaVideo(id = "s1e1", season = 1, episode = 1),
        )
        val now = 1_800_000_000_000L
        assertNull(nextVideo(videos, "s1e1", now))
        assertEquals("s0e1", nextVideo(videos, "s1e2", now)?.id)
        // A bare date reads as that day's midnight UTC.
        val dated = listOf(videos[2], videos[1].copy(released = "2030-01-01"))
        assertNull(nextVideo(dated, "s1e1", now))
        assertEquals("s1e2", nextVideo(dated, "s1e1", 1_900_000_000_000L)?.id)
    }

    @Test
    fun libraryNewerWinsTieKeepsStoredTypeIsFixedAndRemovalsSurviveStaleReAdds() = realTimeTest {
        val backend = Fixture().backend
        backend.putLibrary(listOf(library("movie:tt1", 10, name = "Original")))

        var rows = backend.putLibrary(listOf(library("movie:tt1", 10, name = "Tie")))
        assertEquals("Original", rows.single().name)

        rows = backend.putLibrary(listOf(library("movie:tt1", 11, name = "Renamed", type = "series")))
        assertEquals("Renamed", rows.single().name)
        assertEquals("movie", rows.single().type)

        backend.putLibrary(listOf(library("movie:tt1", 20, removedAt = 20)))
        rows = backend.putLibrary(listOf(library("movie:tt1", 15)))
        assertEquals(20L, rows.single().removedAt)
        assertEquals(rows, backend.getLibrary())
    }

    @Test
    fun rowsTheServerWouldRefuseAreRefusedAndNothingFromTheBatchIsStored() = realTimeTest {
        val fixture = Fixture()
        val backend = fixture.backend
        assertStatus(400) { backend.putLibrary(listOf(library("movie:tt1", 1), library("", 1))) }
        assertStatus(400) { backend.putLibrary(listOf(library("movie:tt1", 1, poster = "not a url"))) }
        assertStatus(400) { backend.putWatchStates(listOf(watch("tt1", 1, positionSec = -1.0))) }
        assertStatus(400) { backend.putWatchStates(listOf(watch("tt1", 1, positionSec = Double.POSITIVE_INFINITY))) }
        assertStatus(400) { backend.putWatchStates(listOf(watch("tt1", 1, name = "x".repeat(513)))) }
        assertTrue(fixture.store.documents.isEmpty())
    }

    @Test
    fun overlappingProgressReportsBothLand() = realTimeTest {
        val backend = Fixture().backend
        val first = async { backend.putWatchStates(listOf(watch("tt1", 5))) }
        val second = async { backend.putWatchStates(listOf(watch("tt2", 6))) }
        first.await()
        second.await()
        assertEquals(listOf("tt1", "tt2"), backend.getWatchStates().map { it.videoId }.sorted())
    }

    @Test
    fun aNewerWatchReportReplacesTheRowWholeAndAnOlderOneIsIgnored() = realTimeTest {
        val backend = Fixture().backend
        backend.putWatchStates(listOf(watch("tt1", 10, name = "Named", positionSec = 30.0)))
        var rows = backend.putWatchStates(listOf(watch("tt1", 9, positionSec = 99.0)))
        assertEquals(30.0, rows.single().positionSec)
        rows = backend.putWatchStates(listOf(watch("tt1", 11, positionSec = 40.0)))
        assertEquals(40.0, rows.single().positionSec)
        assertNull(rows.single().name)
    }

    @Test
    fun settingsNewerReplacesOlderIsIgnoredUnknownKeysPassThroughInvalidValuesAreRefused() = realTimeTest {
        val backend = Fixture().backend
        assertEquals(0L, backend.getSettings().updatedAt)
        assertEquals(UserSettings.Empty, backend.getSettings().value)
        val value = UserSettings(
            buildJsonObject {
                put("preferredSubtitleLang", "eng")
                put("futureSetting", buildJsonObject { put("nested", true) })
            },
        )
        assertEquals(value, backend.putSettings(value, 10).value)
        val ignored = backend.putSettings(UserSettings.Empty.withPreferredSubtitleLang("fre"), 9)
        assertEquals(value to 10L, ignored.value to ignored.updatedAt)
        assertEquals(value, backend.getSettings().value)
        assertStatus(400) { backend.putSettings(UserSettings.Empty.withPlaybackRate(9.0), 11) }
        assertStatus(400) { backend.putSettings(UserSettings(JsonObject(mapOf("videoFitMode" to JsonPrimitive("stretch")))), 11) }
        assertStatus(400) { backend.putSettings(UserSettings.Empty, 0) }
    }

    @Test
    fun anUnreadableStoredDocumentIsReportedNotReplaced() = realTimeTest {
        val fixture = Fixture()
        fixture.store.documents[DeviceCollection.Library] = "{ not json"
        assertFailsWith<DeviceDataException> { fixture.backend.getLibrary() }
        assertFailsWith<DeviceDataException> { fixture.backend.putLibrary(listOf(library("movie:tt1", 1))) }
        assertEquals("{ not json", fixture.store.documents[DeviceCollection.Library])

        fixture.store.documents[DeviceCollection.WatchStates] = """{"version":2,"items":[]}"""
        assertFailsWith<DeviceDataException> { fixture.backend.getWatchStates() }
    }

    @Test
    fun streamNormalizationKeepsOnlyBoundedFieldsAndSafeHeaders() {
        val streams = normalizeStreamsResponse(
            HaloJson.parseToJsonElement(
                """{"streams":[{"url":"https://cdn.test/a.mkv","title":"${"t".repeat(4_097)}",""" +
                    """"behaviorHints":{"videoSize":1.5,"proxyHeaders":{"request":{"Referer":"https://x.test","Bad":"a\nb"}}}},""" +
                    """{"url":"https://user@:443/x"},{"url":"https:// spaced.test/x"},{"url":"HTTPS://Upper.test/x"}]}""",
            ),
        )
        assertEquals(listOf("https://cdn.test/a.mkv", "HTTPS://Upper.test/x"), streams.map { it.url })
        val first = streams.first()
        assertNull(first.title)
        assertNull(first.behaviorHints?.videoSize)
        assertEquals(mapOf("Referer" to "https://x.test"), first.behaviorHints?.proxyHeaders?.request)
    }

    @Test
    fun addonNamesThatLookLikeUrlsOrKeysAreNotShown() {
        assertEquals("Torrentio", safeAddonName(" Torrentio\u0000 "))
        assertEquals("Addon", safeAddonName("https://evil.test"))
        assertEquals("Addon", safeAddonName("key ${"a".repeat(32)}"))
        assertEquals("Addon", safeAddonName("   "))
        assertEquals("ab ".repeat(27).take(80), safeAddonName("ab ".repeat(40)))
    }
}

/* ── Fixtures ──────────────────────────────────────────────────────────────── */

/**
 * Runs on real time. The mock addons answer on the HTTP engine's own threads,
 * which the test scheduler cannot see, so under virtual time every request
 * budget would expire before the answer arrived.
 */
internal fun realTimeTest(body: suspend CoroutineScope.() -> Unit): TestResult =
    runTest { withContext(Dispatchers.Default) { body() } }

private const val CatalogAddon = "https://catalog.test/manifest.json"
private const val StreamA = "https://stream-a.test/manifest.json"
private const val StreamB = "https://stream-b.test/manifest.json"

internal class MemoryDeviceStore : DeviceStore {
    val documents = mutableMapOf<DeviceCollection, String>()

    override fun read(collection: DeviceCollection): String? = documents[collection]

    override fun write(collection: DeviceCollection, contents: String) {
        documents[collection] = contents
    }
}

internal sealed interface Route

internal data class Reply(val status: HttpStatusCode, val body: String) : Route

/** An addon that never answers; only its request budget ends the wait. */
internal data object Hang : Route

internal fun json(body: String): Route = Reply(HttpStatusCode.OK, body)

internal fun manifest(
    id: String,
    resources: List<String> = emptyList(),
    types: List<String> = listOf("movie", "series"),
    catalogs: List<ManifestCatalog> = emptyList(),
    idPrefixes: List<String>? = null,
) = Manifest(
    id = id,
    version = "1.0.0",
    name = id,
    resources = resources.map { ManifestResource(it) },
    types = types,
    catalogs = catalogs,
    idPrefixes = idPrefixes,
)

/** Every field written out, empty lists included, the way real addons send their manifests. */
private val ManifestWire = Json {
    encodeDefaults = true
    explicitNulls = false
}

internal fun manifestJson(manifest: Manifest): Route = json(ManifestWire.encodeToString(Manifest.serializer(), manifest))

private fun streamManifest(id: String) = manifest(id, resources = listOf("stream", "subtitles"), idPrefixes = listOf("tt"))

/** Answers from a table of exact URLs; anything else is a 404. Records every request's URL. */
internal class Fixture(
    routes: Map<String, Route> = emptyMap(),
    timeoutMillis: Long = ResolveTimeoutMillis,
    val store: MemoryDeviceStore = MemoryDeviceStore(),
) {
    val calls = mutableListOf<String>()
    private val table = mapOf(
        CatalogAddon to manifestJson(
            manifest(
                "catalog",
                resources = listOf("catalog", "meta"),
                catalogs = listOf(ManifestCatalog(type = "movie", id = "top", name = "Top")),
                idPrefixes = listOf("tt"),
            ),
        ),
        StreamA to manifestJson(streamManifest("stream-a")),
        StreamB to manifestJson(streamManifest("stream-b")),
    ) + routes

    private val http = HttpClient(
        MockEngine { request ->
            val url = request.url.toString()
            calls += url
            when (val route = table[url]) {
                null -> respond("not found", HttpStatusCode.NotFound)
                Hang -> awaitCancellation()
                is Reply -> respond(
                    route.body,
                    route.status,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        },
    )

    private var clock = 1_000L
    val backend = DeviceBackend(
        http = http,
        store = store,
        clock = EpochClock { ++clock },
        resolver = AddonResolver(http, timeoutMillis),
    )
}

private suspend fun assertStatus(status: Int, block: suspend () -> Unit): HaloApiException {
    val error = assertFailsWith<HaloApiException> { block() }
    assertEquals(status, error.status)
    return error
}

private fun library(
    id: String,
    updatedAt: Long,
    name: String = id,
    type: String = "movie",
    poster: String? = null,
    removedAt: Long? = null,
) = LibraryItem(id = id, type = type, name = name, poster = poster, addedAt = 1, removedAt = removedAt, updatedAt = updatedAt)

private fun watch(
    videoId: String,
    updatedAt: Long,
    positionSec: Double = 10.0,
    name: String? = null,
) = WatchState(
    videoId = videoId,
    itemId = "movie:$videoId",
    positionSec = positionSec,
    durationSec = 100.0,
    watched = false,
    name = name,
    updatedAt = updatedAt,
)
