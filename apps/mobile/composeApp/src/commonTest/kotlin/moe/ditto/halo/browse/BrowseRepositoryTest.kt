package moe.ditto.halo.browse

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.AddonError
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaResponse
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamsResult
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.people.Capabilities
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
    fun aLaterCatalogPageSendsItsOffsetBesideTheRowsOwnExtras() = runTest {
        val api = browseApi { catalogJson(listOf(meta("tt51"))) }
        val repository = repository(api)
        val source = CatalogPageSource("cinemeta", "movie", "top", mapOf("language" to "it"))

        val page = repository.catalogNextPage(source, mapOf("language" to "it", "skip" to "50"))

        assertEquals(listOf("tt51"), page.metas.map { it.id })
        val request = api.reads("/catalog").single()
        assertEquals("50", request.url.parameters["skip"])
        assertEquals("it", request.url.parameters["language"])
        assertEquals("top", request.url.parameters["id"])
    }

    @Test
    fun aCatalogPageCountsTheEntriesTheServerSentBeforeAnyWereDropped() = runTest {
        val api = browseApi { catalogJson(listOf(meta("dltorbox", name = "Delete all files"), meta("tt1"))) }
        val repository = repository(api)

        val page = repository.catalogPage("cinemeta", "movie", "top").first { it.value != null }.value

        assertEquals(listOf("tt1"), page?.metas?.map { it.id })
        assertEquals(2, page?.receivedCount)
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
                    errors = listOf(
                        AddonError(
                            id = "failed-opaque-id",
                            message = "Addon timed out.",
                            name = "Slow addon",
                            code = "timeout",
                        ),
                    ),
                ),
            )
        }
        val repository = repository(api)

        val result = repository.streams("movie", "tt1").first { it.value != null }.value

        assertEquals(listOf("TorBox"), result?.results?.map { it.addon.name })
        assertEquals(listOf("https://cdn.test/a.mp4"), result?.results?.single()?.streams?.map { it.url })
        assertEquals(listOf("Slow addon"), result?.errors?.map { it.name })
        assertEquals(listOf("timeout"), result?.errors?.map { it.code })
    }

    @Test
    fun retryInvalidatesTheStreamQueryWithoutStartingALoop() = runTest {
        var attempts = 0
        val api = browseApi {
            attempts += 1
            HaloJson.encodeToString(
                StreamsResult.serializer(),
                StreamsResult(
                    results = listOf(
                        AddonStreams(
                            AddonSource("torbox", "TorBox"),
                            listOf(Stream(url = "https://cdn.test/$attempts.mp4")),
                        ),
                    ),
                ),
            )
        }
        val repository = repository(api)
        repository.streams("movie", "tt1").first { it.value != null }
        assertEquals(1, attempts)

        repository.retryStreams("movie", "tt1")
        // No automatic loop is introduced for an unobserved key. The next
        // observer sees the cached value, then receives the one refetch that
        // manual invalidation requested.
        assertEquals(1, attempts)
        val refreshed = repository.streams("movie", "tt1").first {
            it.value?.results?.single()?.streams?.single()?.url == "https://cdn.test/2.mp4"
        }

        assertEquals(2, attempts)
        assertEquals("https://cdn.test/2.mp4", refreshed.value?.results?.single()?.streams?.single()?.url)
    }

    @Test
    fun searchAsksOnlyCatalogsThatCanSearchWithTheQueryAsTheSearchExtra() = runTest {
        val api = browseApi { request ->
            when (request.url.encodedPath) {
                "/addons" -> addonsJson(user = searchable)
                "/catalog" -> catalogJson(listOf(meta("tt1", name = "The Matrix")))
                else -> error("unexpected request to ${request.url}")
            }
        }
        val cache = QueryCache(backgroundScope, FakeClock())
        val addons = AddonsRepository(api.client, cache)
        val sources = BackendSearchSources(
            backend = api.client,
            addons = addons::effective,
            capabilities = { Capabilities.None },
            posterProbe = PosterProbe(HttpClient(MockEngine { error("no poster is asked about here") })),
        )

        val targets = boardTargets(sources.addons())
        // The catalog that cannot search is never a target.
        assertEquals(listOf("movie/top", "series/top"), targets.map { "${it.type}/${it.catalogId}" })
        val found = sources.catalog(targets.first(), "matrix")

        assertEquals(listOf("The Matrix"), found.map { it.name })
        assertEquals(1, api.reads("/catalog").size)
        assertEquals("matrix", api.reads("/catalog").single().url.parameters["search"])
    }

    /** One cache shared by both repositories, exactly as the app wires them. */
    private fun TestScope.repository(api: BrowseApi): BrowseRepository {
        val cache = QueryCache(backgroundScope, FakeClock())
        return BrowseRepository(api.client, cache, AddonsRepository(api.client, cache))
    }
}
