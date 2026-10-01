package moe.ditto.halo.browse

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.sync.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddonsRepositoryTest {

    @Test
    fun resolvesGlobalAddonsBeforeTheUsersOwn() = runTest {
        val api = browseApi { addonsJson(global = listOf(addon("cinemeta")), user = listOf(addon("torbox"))) }
        val repository = AddonsRepository(api.client, QueryCache(backgroundScope, FakeClock()))

        val effective = repository.observeEffective().first { it.value != null }.value

        assertEquals(listOf("cinemeta", "torbox"), effective?.map { it.id })
    }

    @Test
    fun readsTheAddonListOnceForCallersThatCannotWait() = runTest {
        // Search resolves the list inside its own fetch, so a cold cache must
        // not turn every query into a second round trip.
        val api = browseApi { addonsJson(user = listOf(addon("cinemeta"))) }
        val repository = AddonsRepository(api.client, QueryCache(backgroundScope, FakeClock()))

        repository.effective()
        val second = repository.effective()

        assertEquals(listOf("cinemeta"), second.map { it.id })
        assertEquals(1, api.reads("/addons").size)
    }

    @Test
    fun reusesWhatTheBrowsingScreensAlreadyFetched() = runTest {
        val api = browseApi { addonsJson(user = listOf(addon("cinemeta"))) }
        val cache = QueryCache(backgroundScope, FakeClock())
        val repository = AddonsRepository(api.client, cache)

        repository.observeEffective().first { it.value != null }
        repository.effective()

        assertEquals(1, api.reads("/addons").size)
    }

    @Test
    fun installingAnAddonSendsTheWholeListAndRefetchesWhatTheServerMadeOfIt() = runTest {
        // The server fetches and stores manifests itself, so the only way to
        // learn what a new addon can do is to read the list back.
        var generation = 0
        val api = BrowseApi { request ->
            when (request.method) {
                HttpMethod.Put -> HttpStatusCode.OK to "[]"
                else -> {
                    generation += 1
                    HttpStatusCode.OK to addonsJson(user = List(generation) { addon("addon$it") })
                }
            }
        }
        val repository = AddonsRepository(api.client, QueryCache(backgroundScope, FakeClock()))

        repository.observeEffective().first { it.value?.isNotEmpty() == true }
        repository.setUserAddons(listOf("https://a.test/manifest.json", "https://b.test/manifest.json"))
        val refetched = repository.observeEffective().first { it.value?.size == 2 }.value

        assertEquals(
            listOf("""["https://a.test/manifest.json","https://b.test/manifest.json"]"""),
            api.writtenBodies(),
        )
        assertEquals(listOf("addon0", "addon1"), refetched?.map { it.id })
    }

    @Test
    fun hidingCatalogsRefetchesBecauseTheServerStripsTheManifest() = runTest {
        // A hidden addon comes back with its catalogs already removed, so the
        // cached copy is wrong the moment the toggle is written.
        var hidden = false
        val api = BrowseApi { request ->
            when (request.method) {
                HttpMethod.Patch -> {
                    hidden = true
                    HttpStatusCode.OK to "{}"
                }
                else -> HttpStatusCode.OK to addonsJson(
                    user = listOf(
                        addon(
                            "cinemeta",
                            catalogs = if (hidden) emptyList() else listOf(catalog("movie", "top", "Popular")),
                        ),
                    ),
                )
            }
        }
        val repository = AddonsRepository(api.client, QueryCache(backgroundScope, FakeClock()))

        repository.observeEffective().first { it.value != null }
        repository.setHideCatalogs("cinemeta", hidden = true)
        val refetched = repository.observeEffective().first { it.value?.single()?.manifest?.catalogs?.isEmpty() == true }

        assertTrue(refetched.value?.single()?.manifest?.catalogs.orEmpty().isEmpty())
    }
}
