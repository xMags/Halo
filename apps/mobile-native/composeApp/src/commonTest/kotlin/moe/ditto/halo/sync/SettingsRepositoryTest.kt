package moe.ditto.halo.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.content.TextContent
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.api.SettingsPayload
import moe.ditto.halo.api.SubtitleOutline
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.auth.TokenProvider
import moe.ditto.halo.cache.HaloKey
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.storage.StorageKeys
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SettingsRepositoryTest {
    private val stored = """{"value":{"preferredAudioLang":"eng","subtitleScalePercent":120},"updatedAt":1000}"""

    @Test
    fun readingSettingsMirrorsThemForOfflineUse() = runTest {
        val api = RecordingApi { stored }
        val clock = FakeClock()
        val store = FakeStore()
        val repository = SettingsRepository(api.client, QueryCache(backgroundScope, clock), store, clock)

        val settings = repository.observe().first { it.value != null }

        assertEquals("eng", settings.value?.preferredAudioLang)
        assertContains(store.values.getValue(StorageKeys.SettingsMirror), "preferredAudioLang")
    }

    @Test
    fun anUnreachableServerFallsBackToTheMirror() = runTest {
        // Preferences apply at player start, so an outage must not silently
        // mean defaults for the whole session.
        val clock = FakeClock()
        val store = FakeStore().apply { values[StorageKeys.SettingsMirror] = stored }
        val repository = SettingsRepository(offlineClient(), QueryCache(backgroundScope, clock), store, clock)

        val settings = repository.observe().first { it.value != null }

        assertEquals("eng", settings.value?.preferredAudioLang)
        assertEquals(120, settings.value?.subtitleScalePercent)
    }

    @Test
    fun anUnreachableServerWithNoMirrorSurfacesTheFailure() = runTest {
        val clock = FakeClock()
        val repository = SettingsRepository(offlineClient(), QueryCache(backgroundScope, clock), FakeStore(), clock)

        val state = repository.observe().first { it.error != null }

        assertNotNull(state.error)
        assertNull(state.value)
    }

    @Test
    fun currentPrefersTheMirrorOverDefaults() = runTest {
        val clock = FakeClock()
        val store = FakeStore().apply { values[StorageKeys.SettingsMirror] = stored }
        val repository = SettingsRepository(offlineClient(), QueryCache(backgroundScope, clock), store, clock)

        assertEquals("eng", repository.current().preferredAudioLang)
    }

    @Test
    fun currentFallsBackToDefaultsWithNothingStored() = runTest {
        val clock = FakeClock()
        val repository = SettingsRepository(offlineClient(), QueryCache(backgroundScope, clock), FakeStore(), clock)

        assertEquals(UserSettings.Empty, repository.current())
    }

    @Test
    fun aMirrorFromAnIncompatibleBuildIsIgnoredRatherThanFatal() = runTest {
        val clock = FakeClock()
        val store = FakeStore().apply { values[StorageKeys.SettingsMirror] = "{not json" }
        val repository = SettingsRepository(offlineClient(), QueryCache(backgroundScope, clock), store, clock)

        assertEquals(UserSettings.Empty, repository.current())
    }

    @Test
    fun updatingSendsTheWholeDocument() = runTest {
        // Settings are one blob under one timestamp, so a request carrying
        // only the changed field would overwrite everything else.
        val api = RecordingApi { stored }
        val clock = FakeClock()
        val cache = QueryCache(backgroundScope, clock)
        val repository = SettingsRepository(api.client, cache, FakeStore(), clock)
        repository.observe().first { it.value != null }

        repository.update { it.withSubtitleOutline(SubtitleOutline.Thick) }

        val body = api.bodyOf(0)
        assertContains(body, "\"subtitleOutline\":\"thick\"")
        assertContains(body, "\"preferredAudioLang\":\"eng\"")
        assertContains(body, "\"subtitleScalePercent\":120")
    }

    @Test
    fun updatingPreservesPreferencesThisBuildDoesNotKnow() = runTest {
        // The server keeps unknown fields so an older client cannot delete a
        // newer one's settings; dropping them here would defeat that from the
        // client side instead.
        val withUnknown = """{"value":{"preferredAudioLang":"eng","desktopOnly":"keep"},"updatedAt":1000}"""
        val api = RecordingApi { withUnknown }
        val clock = FakeClock()
        val repository = SettingsRepository(api.client, QueryCache(backgroundScope, clock), FakeStore(), clock)
        repository.observe().first { it.value != null }

        repository.update { it.withPlaybackRate(1.5) }

        assertContains(api.bodyOf(0), "\"desktopOnly\":\"keep\"")
    }

    @Test
    fun successiveEditsBuildOnEachOther() = runTest {
        val api = RecordingApi { request ->
            (request.body as? TextContent)?.text ?: stored
        }
        val clock = FakeClock()
        val repository = SettingsRepository(api.client, QueryCache(backgroundScope, clock), FakeStore(), clock)

        repository.update { it.withPreferredAudioLang("jpn") }
        clock.now += 1
        val settings = repository.update { it.withSubtitleShadow(false) }

        assertEquals("jpn", settings.preferredAudioLang)
        assertEquals(false, settings.subtitleShadow)
    }

    @Test
    fun anOlderEchoDoesNotUndoANewerEdit() = runTest {
        // The server answers with whichever document is newest, which may be
        // one this device has already moved past.
        val clock = FakeClock(now = 5_000)
        val api = RecordingApi { """{"value":{"preferredAudioLang":"stale"},"updatedAt":1}""" }
        val cache = QueryCache(backgroundScope, clock)
        val repository = SettingsRepository(api.client, cache, FakeStore(), clock)

        val result = repository.update { it.withPreferredAudioLang("eng") }

        assertEquals("eng", result.preferredAudioLang)
    }

    @Test
    fun updatingRefreshesTheMirror() = runTest {
        val api = RecordingApi { request -> (request.body as? TextContent)?.text ?: stored }
        val clock = FakeClock()
        val store = FakeStore()
        val repository = SettingsRepository(api.client, QueryCache(backgroundScope, clock), store, clock)

        repository.update { it.withPlaybackRate(2.0) }

        assertContains(store.values.getValue(StorageKeys.SettingsMirror), "\"playbackRate\":2.0")
    }

    @Test
    fun aFailedUpdateKeepsTheChangeVisibleAndSurfacesTheError() = runTest {
        val clock = FakeClock()
        val cache = QueryCache(backgroundScope, clock)
        val repository = SettingsRepository(offlineClient(), cache, FakeStore(), clock)

        assertFailsWith<IOException> {
            repository.update { it.withPlaybackRate(1.25) }
        }

        // Not rolled back: a different edit may already have landed, and the
        // entry is stale so the next read takes server truth.
        assertEquals(1.25, cache.peek<SettingsPayload>(HaloKey.Settings)?.value?.playbackRate)
    }

    private fun offlineClient() = HaloClient(
        baseUrl = "https://halo.test",
        tokens = object : TokenProvider {
            override suspend fun accessToken(): String = "token"

            override suspend fun refreshAccessToken(): String = "token"
        },
        httpClient = HttpClient(MockEngine { throw IOException("offline") }),
    )
}
