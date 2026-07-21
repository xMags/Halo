@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.ditto.halo

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import moe.ditto.halo.auth.AuthConfig
import moe.ditto.halo.auth.AuthEvent
import moe.ditto.halo.auth.OidcHostRequest
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlayerEvent
import moe.ditto.halo.player.PlayerTrack
import platform.UIKit.UIView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class IosHostBridgeTest {
    @Test
    fun authAdapterUsesOneHostForDiscoveryAndOidcRequests() = runTest {
        val host = RecordingAuthHost()
        val adapter = IosAuthHostAdapter(host)

        assertEquals(AuthConfig.Local, adapter.fetch("https://halo.local"))
        adapter.requestOidc(
            OidcHostRequest(
                serverUrl = "https://halo.example",
                issuer = "https://auth.example/",
                clientId = "halo",
                scopes = listOf("openid", "groups"),
            ),
        )

        assertEquals("openid groups", host.requestedScopes)
        assertEquals(1, host.oidcRequestCount)
    }

    @Test
    fun authAdapterSurfacesConfigFetchErrorsAsExceptions() = runTest {
        val host = object : HaloIosAuthHost {
            override val hostId = "auth-host"
            override val oidcRequestCount = 0L
            override fun fetchAuthConfig(serverUrl: String, completion: (String?, String?) -> Unit) =
                completion(null, "boom")

            override fun requestOidc(serverUrl: String, issuer: String, clientId: String, scopes: String) = Unit
            override fun restoreOidcSession(): String? = null
            override fun fetchOidcAccessToken(forceRefresh: Boolean, completion: (String?, String?) -> Unit) =
                completion(null, null)

            override fun signOutOidc(completion: () -> Unit) = completion()
            override fun setAuthEventSink(sink: HaloIosAuthEventSink?) = Unit
        }
        val adapter = IosAuthHostAdapter(host)

        val error = assertFailsWith<IllegalStateException> { adapter.fetch("https://halo.example") }
        assertEquals("boom", error.message)
    }

    @Test
    fun authEventBridgeTranslatesSinkCallsIntoTypedEvents() = runTest {
        val bridge = IosAuthEventBridge()

        bridge.onOidcSucceeded("https://halo.example", "fixture-access-proof-abc123")
        bridge.onOidcFailed("Authorization state did not match")
        bridge.onOidcSessionInvalidated()

        val received = mutableListOf<AuthEvent>()
        val job = launch { bridge.events.collect { received += it } }
        while (received.size < 3) yield()
        job.cancel()

        assertEquals(AuthEvent.OidcSucceeded("https://halo.example", "fixture-access-proof-abc123"), received[0])
        assertEquals(AuthEvent.OidcFailed("Authorization state did not match"), received[1])
        assertEquals(AuthEvent.OidcSessionInvalidated, received[2])
    }

    @Test
    fun oidcSessionPortMapsTheCallbackPairOntoPortSemantics() = runTest {
        val host = RecordingAuthHost()
        val port = IosOidcSessionPort(host)

        // No persisted session: restore misses, fetch resolves to null.
        assertEquals(null, port.restoreSession())
        assertEquals(null, port.accessToken(forceRefresh = false))

        host.persistedServerUrl = "https://halo.example"
        host.nextToken = "fixture-access-proof-live"
        assertEquals("https://halo.example", port.restoreSession())
        assertEquals("fixture-access-proof-live", port.accessToken(forceRefresh = false))
        assertEquals(false, host.lastForceRefresh)
        assertEquals("fixture-access-proof-live", port.accessToken(forceRefresh = true))
        assertEquals(true, host.lastForceRefresh)

        // Transport failure: the error message surfaces as an exception, so the
        // caller cannot misread it as signed-out.
        host.nextError = "refresh request failed"
        val error = assertFailsWith<IllegalStateException> { port.accessToken(forceRefresh = false) }
        assertEquals("refresh request failed", error.message)

        port.signOut()
        assertEquals(1, host.signOutCount)
    }

    @Test
    fun playerAdapterDelegatesToTheExistingHost() = runTest {
        val host = RecordingPlayerHost()
        val adapter = IosPlayerHostAdapter(host)
        val item = MediaItem("one", "Episode one", "https://example.test/one.mp4")

        adapter.load(item)
        adapter.seekTo(42.5)
        adapter.setSubtitleDelay(0.5)
        adapter.setSubtitleScale(1.5)
        adapter.setSubtitleFont("Courier New")
        adapter.addSubtitle("http://127.0.0.1:18787/media/sample4k.ass")

        assertEquals(listOf(item), host.loads)
        assertEquals(42.5, host.lastSeekSeconds)
        assertEquals(listOf(0.5), host.subtitleDelays)
        assertEquals(listOf(1.5), host.subtitleScales)
        assertEquals(listOf<String?>("Courier New"), host.subtitleFonts)
        assertEquals(listOf("http://127.0.0.1:18787/media/sample4k.ass"), host.addedSubtitles)
        assertEquals(1, host.coreCreationCount)
    }

    @Test
    fun eventBridgeTranslatesNormalizedTrackJsonIntoTypedEvents() = runTest {
        val bridge = IosPlayerEventBridge()

        bridge.onReady(60.0)
        bridge.onTracks(
            """
            {
              "audio": [{"id": "2", "label": "AAC stereo", "language": "eng"}],
              "subtitles": [
                {"id": "3", "label": "English ASS", "language": "eng"},
                {"id": "4", "label": "Signs"}
              ],
              "selectedAudioId": "2",
              "selectedSubtitleId": "3"
            }
            """.trimIndent(),
        )
        bridge.onTracks("this is not json")
        bridge.onEnded()

        val received = mutableListOf<PlayerEvent>()
        val flowJob = launch { bridge.events.collect { received += it } }
        // Channel is buffered; everything already sent is immediately available.
        while (received.size < 3) yield()
        flowJob.cancel()

        assertEquals(PlayerEvent.Ready(60.0), received[0])
        val tracks = (received[1] as PlayerEvent.TracksChanged).tracks
        assertEquals(listOf(PlayerTrack("2", "AAC stereo", "eng")), tracks.audio)
        assertEquals(
            listOf(PlayerTrack("3", "English ASS", "eng"), PlayerTrack("4", "Signs")),
            tracks.subtitles,
        )
        assertEquals("2", tracks.selectedAudioId)
        assertEquals("3", tracks.selectedSubtitleId)
        // The malformed document was dropped, so the third event is the end.
        assertEquals(PlayerEvent.NaturalEnd, received[2])
    }

    @Test
    fun onlyExplicitRecreateChangesThePlayerInstanceId() {
        val authHost = RecordingAuthHost()
        val playerHost = RecordingPlayerHost()
        val diagnostics = IosNativeHostDiagnostics(authHost, playerHost)
        val before = diagnostics.snapshot()

        playerHost.didAttachPlayerView()
        playerHost.didResizePlayerView(320.0, 180.0)
        playerHost.didDetachPlayerView()
        val afterLifecycleOnly = diagnostics.snapshot()
        diagnostics.destroyAndRecreatePlayerCore()
        val afterRecreate = diagnostics.snapshot()

        assertEquals(before.playerInstanceId, afterLifecycleOnly.playerInstanceId)
        assertEquals(before.coreCreationCount, afterLifecycleOnly.coreCreationCount)
        assertEquals(before.coreDestructionCount, afterLifecycleOnly.coreDestructionCount)
        assertEquals(before.playerViewInstanceId, afterLifecycleOnly.playerViewInstanceId)
        assertNotEquals(before.playerInstanceId, afterRecreate.playerInstanceId)
        assertEquals(before.playerViewInstanceId, afterRecreate.playerViewInstanceId)
        assertEquals(before.coreCreationCount + 1, afterRecreate.coreCreationCount)
        assertEquals(before.coreDestructionCount + 1, afterRecreate.coreDestructionCount)
    }

    private class RecordingAuthHost : HaloIosAuthHost {
        override val hostId = "auth-host"
        override var oidcRequestCount = 0L
        var requestedScopes: String? = null
            private set
        var persistedServerUrl: String? = null
        var nextToken: String? = null
        var nextError: String? = null
        var lastForceRefresh: Boolean? = null
            private set
        var signOutCount = 0
            private set

        override fun fetchAuthConfig(serverUrl: String, completion: (String?, String?) -> Unit) =
            completion("""{"mode":"local"}""", null)

        override fun requestOidc(
            serverUrl: String,
            issuer: String,
            clientId: String,
            scopes: String,
        ) {
            oidcRequestCount += 1
            requestedScopes = scopes
        }

        override fun restoreOidcSession(): String? = persistedServerUrl

        override fun fetchOidcAccessToken(forceRefresh: Boolean, completion: (String?, String?) -> Unit) {
            lastForceRefresh = forceRefresh
            when (val error = nextError) {
                null -> completion(if (persistedServerUrl != null) nextToken else null, null)
                else -> completion(null, error)
            }
        }

        override fun signOutOidc(completion: () -> Unit) {
            signOutCount += 1
            persistedServerUrl = null
            completion()
        }

        override fun setAuthEventSink(sink: HaloIosAuthEventSink?) = Unit
    }

    private class RecordingPlayerHost : HaloIosPlayerHost {
        override val hostId = "player-host"
        override var instanceId = "core-1"
        override val playerViewInstanceId = "view-1"
        override var coreCreationCount = 1L
        override var coreDestructionCount = 0L
        override val playerViewCreationCount = 1L
        override var attachCount = 0L
        override var resizeCount = 0L
        override var detachCount = 0L
        override val loadCount: Long get() = loads.size.toLong()
        override val teardownCount = 0L
        val loads = mutableListOf<MediaItem>()
        val subtitleDelays = mutableListOf<Double>()
        val subtitleScales = mutableListOf<Double>()
        val subtitleFonts = mutableListOf<String?>()
        val addedSubtitles = mutableListOf<String>()
        var lastSeekSeconds: Double? = null
            private set

        override fun playerView(): UIView = UIView()

        override fun didAttachPlayerView() {
            attachCount += 1
        }

        override fun didResizePlayerView(widthPoints: Double, heightPoints: Double) {
            resizeCount += 1
        }

        override fun didDetachPlayerView() {
            detachCount += 1
        }

        override fun load(id: String, title: String, url: String) {
            loads += MediaItem(id, title, url)
        }

        override fun setPaused(paused: Boolean) = Unit

        override fun seekTo(positionSeconds: Double) {
            lastSeekSeconds = positionSeconds
        }

        override fun selectAudioTrack(id: String?) = Unit
        override fun selectSubtitleTrack(id: String?) = Unit

        override fun setEventSink(sink: HaloIosPlayerEventSink?) = Unit

        override fun setSubtitleDelay(seconds: Double) {
            subtitleDelays += seconds
        }

        override fun setSubtitleScale(scale: Double) {
            subtitleScales += scale
        }

        override fun setSubtitleFont(font: String?) {
            subtitleFonts += font
        }

        override fun addSubtitle(url: String) {
            addedSubtitles += url
        }

        override fun teardown() = Unit

        override fun destroyAndRecreateCore() {
            coreDestructionCount += 1
            coreCreationCount += 1
            instanceId = "core-$coreCreationCount"
        }
    }
}
