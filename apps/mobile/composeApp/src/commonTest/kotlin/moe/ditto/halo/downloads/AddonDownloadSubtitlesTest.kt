package moe.ditto.halo.downloads

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.api.SettingsPayload
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.auth.TokenProvider
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.player.StreamVideoHasher
import moe.ditto.halo.player.SubtitleFileCache
import moe.ditto.halo.storage.StorageKeys
import moe.ditto.halo.sync.FakeClock
import moe.ditto.halo.sync.FakeStore
import moe.ditto.halo.sync.SettingsRepository
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonDownloadSubtitlesTest {

    @Test
    fun thePreferredLanguageIsFetchedAndStoredBesideTheVideo() = runTest {
        val world = subtitleWorld(preferredLang = "eng")

        val subtitle = assertNotNull(world.source.fetch(media(videoId = "tt1")))

        assertEquals("eng", subtitle.lang)
        assertEquals("os-9", subtitle.subId)
        // A name, never a path: the container it sits in can be renumbered.
        assertTrue(!subtitle.fileName.contains('/'))
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,000\nhello\n",
            world.fileSystem.read("/downloads/${subtitle.fileName}".toPath()) { readUtf8() },
        )
    }

    @Test
    fun theHashTheAddonAlreadyGaveIsUsedRatherThanFetchedAgain() = runTest {
        val world = subtitleWorld(preferredLang = "eng")

        world.source.fetch(
            media(videoId = "tt1").copy(videoHash = "abcdef0123456789", videoSize = 1_234),
        )

        val request = assertNotNull(world.requests.firstOrNull { it.url.encodedPath == "/subtitles" })
        assertEquals("abcdef0123456789", request.url.parameters["videoHash"])
        assertEquals("1234", request.url.parameters["videoSize"])
        // No range request went to the source: the hash was already known.
        assertTrue(world.requests.none { it.url.host == "source.test" })
    }

    @Test
    fun aDifferentLanguageIsNotKeptJustBecauseItWasOffered() = runTest {
        val world = subtitleWorld(preferredLang = "jpn")

        assertNull(world.source.fetch(media(videoId = "tt1")))
    }

    @Test
    fun withNoPreferredLanguageNothingIsFetchedAtAll() = runTest {
        val world = subtitleWorld(preferredLang = null)

        assertNull(world.source.fetch(media(videoId = "tt1")))
        assertTrue(world.requests.isEmpty())
    }

    @Test
    fun aDeviceWithNowhereToKeepDownloadsAsksForNothing() = runTest {
        val world = subtitleWorld(preferredLang = "eng", storage = FakeDownloadStorage(path = null))

        assertNull(world.source.fetch(media(videoId = "tt1")))
        assertTrue(world.requests.isEmpty())
    }

    @Test
    fun anUnreachableServerCostsTheSubtitleAndNothingElse() = runTest {
        val world = subtitleWorld(preferredLang = "eng", subtitlesStatus = HttpStatusCode.InternalServerError)

        // Null rather than a throw: the download itself is never blocked on this.
        assertNull(world.source.fetch(media(videoId = "tt1")))
    }
}

private class SubtitleWorld(
    val source: AddonDownloadSubtitles,
    val requests: List<HttpRequestData>,
    val fileSystem: FakeFileSystem,
)

private object StaticTokens : TokenProvider {
    override suspend fun accessToken(): String = "token"
    override suspend fun refreshAccessToken(): String = "token"
}

private const val SubtitlesBody = """
{"results":[{"addon":{"id":"os","name":"OpenSubtitles"},
"subtitles":[{"id":"os-9","url":"https://subs.test/9.srt","lang":"eng"}]}],"errors":[]}
"""

private fun CoroutineScope.subtitleWorld(
    preferredLang: String?,
    storage: DownloadStoragePort = FakeDownloadStorage(),
    subtitlesStatus: HttpStatusCode = HttpStatusCode.OK,
): SubtitleWorld {
    val requests = mutableListOf<HttpRequestData>()
    val httpClient = HttpClient(
        MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath == "/subtitles" -> respond(
                    content = if (subtitlesStatus == HttpStatusCode.OK) SubtitlesBody else """{"error":"nope"}""",
                    status = subtitlesStatus,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> respond(
                    content = "1\n00:00:01,000 --> 00:00:02,000\nhello\n",
                    status = HttpStatusCode.OK,
                )
            }
        },
    )
    val client = HaloClient("https://halo.test", StaticTokens, httpClient)
    val clock = FakeClock()
    val store = FakeStore()
    preferredLang?.let {
        store.write(
            StorageKeys.SettingsMirror,
            HaloJson.encodeToString(
                SettingsPayload.serializer(),
                SettingsPayload(UserSettings().withPreferredSubtitleLang(it), clock.nowMs()),
            ),
        )
    }
    val fileSystem = FakeFileSystem()
    return SubtitleWorld(
        source = AddonDownloadSubtitles(
            client = client,
            hasher = StreamVideoHasher(httpClient),
            settings = SettingsRepository(
                client,
                QueryCache(CoroutineScope(coroutineContext + SupervisorJob()), clock),
                store,
                clock,
            ),
            storage = storage,
            cacheFor = { directory -> SubtitleFileCache(client, directory, fileSystem) },
        ),
        requests = requests,
        fileSystem = fileSystem,
    )
}
