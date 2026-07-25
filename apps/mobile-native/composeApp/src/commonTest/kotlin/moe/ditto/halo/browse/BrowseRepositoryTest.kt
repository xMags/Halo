package moe.ditto.halo.browse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaResponse
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamsResult
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.sync.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowseRepositoryTest {
    private val searchable = listOf(
        addon(
            "cinemeta",
            "Cinemeta",
            listOf(
                catalog("movie", "top", "Popular", extraSupported = listOf("search")),
                catalog("series", "top", "Popular", extraSupported = listOf("search")),
                catalog("movie", "featured", "Featured"),
            ),
        ),
    )

    @Test
    fun readsOneCatalogByItsOpaqueAddonId() = runTest {
        val api = browseApi { catalogJson(listOf(meta("tt1", name = "Popular film"))) }
        val repository = repository(api)

        val metas = repository.catalog("cinemeta", "movie", "top").first { it.value != null }.value

        assertEquals(listOf("Popular film"), metas?.map { it.name })
        val request = api.reads("/catalog").single()
        assertEquals("cinemeta", request.url.parameters["addon"])
        assertEquals("movie", request.url.parameters["type"])
        assertEquals("top", request.url.parameters["id"])
    }

    @Test
    fun doesNotFetchAQueryWhoseInputsAreNotResolvedYet() = runTest {
        // Home's featured title has no catalog to read until the addon list
        // lands; fetching with a blank id would 400 on every cold start.
        val api = browseApi { catalogJson(emptyList()) }
        val repository = repository(api)

        repository.catalog("", "", "", enabled = false).first()

        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun readsFullMetadataForOneTitle() = runTest {
        val api = browseApi {
            HaloJson.encodeToString(
                MetaResponse.serializer(),
                MetaResponse(MetaDetail(id = "tt1", type = "movie", name = "A film")),
            )
        }
        val repository = repository(api)

        val detail = repository.meta("movie", "tt1").first { it.value != null }.value

        assertEquals("A film", detail?.name)
        assertEquals("tt1", api.reads("/meta").single().url.parameters["id"])
    }

    @Test
    fun keepsStreamsGroupedByTheAddonThatOfferedThem() = runTest {
        val api = browseApi {
            HaloJson.encodeToString(
                StreamsResult.serializer(),
                StreamsResult(
                    results = listOf(
                        AddonStreams(AddonSource("torbox", "TorBox"), listOf(Stream(url = "https://cdn.test/a.mp4"))),
                    ),
                ),
            )
        }
        val repository = repository(api)

        val groups = repository.streams("movie", "tt1").first { it.value != null }.value

        assertEquals(listOf("TorBox"), groups?.map { it.addon.name })
        assertEquals(listOf("https://cdn.test/a.mp4"), groups?.single()?.streams?.map { it.url })
    }

    @Test
    fun searchesEverySearchCapableCatalogAndKeepsOneRowPerAnswer() = runTest {
        val api = browseApi { request ->
            when (request.url.encodedPath) {
                "/addons" -> addonsJson(user = searchable)
                "/catalog" -> when (request.url.parameters["type"]) {
                    "movie" -> catalogJson(listOf(meta("tt1", name = "The Matrix")))
                    // A catalog with no matches is not a row with no results.
                    else -> catalogJson(emptyList())
                }
                else -> error("unexpected request to ${request.url}")
            }
        }
        val repository = repository(api)

        val groups = repository.search("  matrix  ").first { it.value != null }.value

        assertEquals(listOf("Popular – Movie"), groups?.map { it.title })
        assertEquals(listOf("The Matrix"), groups?.single()?.metas?.map { it.name })
        // The non-searchable catalog is never asked, and the term reaches the
        // ones that are as the protocol's `search` extra, trimmed.
        assertEquals(2, api.reads("/catalog").size)
        assertTrue(api.reads("/catalog").all { it.url.parameters["search"] == "matrix" })
    }

    @Test
    fun oneFailingCatalogDoesNotFailTheSearch() = runTest {
        // Addons are third-party and time out routinely; a shared failure would
        // turn one slow addon into an empty screen.
        val api = BrowseApi { request ->
            when {
                request.url.encodedPath == "/addons" -> HttpStatusCode.OK to addonsJson(user = searchable)
                request.url.parameters["type"] == "series" ->
                    HttpStatusCode.BadGateway to """{"error":"catalog fetch failed"}"""
                else -> HttpStatusCode.OK to catalogJson(listOf(meta("tt1", name = "The Matrix")))
            }
        }
        val repository = repository(api)

        val groups = repository.search("matrix").first { it.value != null }.value

        assertEquals(listOf("Popular – Movie"), groups?.map { it.title })
    }

    @Test
    fun doesNotSearchOnATermTooShortToMeanAnything() = runTest {
        val api = browseApi { error("nothing should be requested") }
        val repository = repository(api)

        repository.search("a").first()

        assertTrue(api.requests.isEmpty())
    }

    /** One cache shared by both repositories, exactly as the app wires them. */
    private fun TestScope.repository(api: BrowseApi): BrowseRepository {
        val cache = QueryCache(backgroundScope, FakeClock())
        return BrowseRepository(api.client, cache, AddonsRepository(api.client, cache))
    }
}
