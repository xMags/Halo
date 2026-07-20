package moe.ditto.halo.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LoginPresenterTest {
    @Test
    fun credentialEditsAreIgnoredBeforeLocalModeDiscovery() {
        val presenter = LoginPresenter(FakeConfigSource(AuthConfig.Local), RecordingNativeHost())
        presenter.editServerUrl("https://halo.local")

        presenter.editUsername("testuser")
        presenter.editPassword("secret")

        assertEquals("", presenter.state.username)
        assertEquals("", presenter.state.password)
    }

    @Test
    fun localModeRevealsCredentialsOnlyAfterDiscovery() = runTest {
        val presenter = LoginPresenter(FakeConfigSource(AuthConfig.Local), RecordingNativeHost())
        presenter.editServerUrl(" https://halo.local/ ")

        presenter.continueFromServer()

        val phase = assertIs<LoginPhase.LocalCredentials>(presenter.state.phase)
        assertEquals("https://halo.local", phase.serverUrl)
        assertEquals("https://halo.local", presenter.state.serverUrl)
    }

    @Test
    fun editingServerResetsDiscoveredModeAndCredentials() = runTest {
        val presenter = LoginPresenter(FakeConfigSource(AuthConfig.Local), RecordingNativeHost())
        presenter.editServerUrl("https://first.local")
        presenter.continueFromServer()
        presenter.editUsername("testuser")
        presenter.editPassword("secret")

        presenter.editServerUrl("https://second.example")

        assertEquals(LoginPhase.Server, presenter.state.phase)
        assertEquals("", presenter.state.username)
        assertEquals("", presenter.state.password)
        assertNull(presenter.state.error)
    }

    @Test
    fun oidcModeEmitsNativeHostRequest() = runTest {
        val config = AuthConfig.Oidc(
            issuer = "https://auth.example/",
            clientId = "halo",
            scopes = listOf("openid", "offline_access"),
        )
        val host = RecordingNativeHost()
        val presenter = LoginPresenter(FakeConfigSource(config), host)
        presenter.editServerUrl("https://halo.example/")

        presenter.continueFromServer()

        val phase = assertIs<LoginPhase.OidcRequested>(presenter.state.phase)
        assertEquals(phase.request, host.request)
        assertEquals("https://halo.example", phase.request.serverUrl)
    }

    @Test
    fun oidcSuccessEventTransitionsToSucceeded() = runTest {
        val presenter = oidcPresenterAfterRequest()

        presenter.onAuthEvent(AuthEvent.OidcSucceeded("fixture-access-proof-abc123"))

        val phase = assertIs<LoginPhase.OidcSucceeded>(presenter.state.phase)
        assertEquals("fixture-access-proof-abc123", phase.tokenProof)
    }

    @Test
    fun oidcFailureEventTransitionsToFailed() = runTest {
        val presenter = oidcPresenterAfterRequest()

        presenter.onAuthEvent(AuthEvent.OidcFailed("Authorization state did not match"))

        val phase = assertIs<LoginPhase.OidcFailed>(presenter.state.phase)
        assertEquals("Authorization state did not match", phase.reason)
    }

    @Test
    fun authEventsAreDroppedOutsideAnInFlightRequest() {
        // A late event that lands after the form reset (still on Server) must not
        // resurrect a terminal phase.
        val presenter = LoginPresenter(FakeConfigSource(AuthConfig.Local), RecordingNativeHost())
        presenter.editServerUrl("https://halo.local")

        presenter.onAuthEvent(AuthEvent.OidcSucceeded("proof"))

        assertEquals(LoginPhase.Server, presenter.state.phase)
    }

    @Test
    fun retryReEmitsTheRequestWithoutRediscovery() = runTest {
        val host = RecordingNativeHost()
        val presenter = oidcPresenterAfterRequest(host)
        presenter.onAuthEvent(AuthEvent.OidcFailed("boom"))

        presenter.retryOidc()

        assertIs<LoginPhase.OidcRequested>(presenter.state.phase)
        assertEquals(2, host.requestCount)
    }

    private suspend fun oidcPresenterAfterRequest(
        host: RecordingNativeHost = RecordingNativeHost(),
    ): LoginPresenter {
        val config = AuthConfig.Oidc(
            issuer = "https://auth.example/",
            clientId = "halo",
            scopes = listOf("openid", "offline_access"),
        )
        val presenter = LoginPresenter(FakeConfigSource(config), host)
        presenter.editServerUrl("https://halo.example")
        presenter.continueFromServer()
        return presenter
    }

    private class FakeConfigSource(private val config: AuthConfig) : AuthConfigSource {
        override suspend fun fetch(serverUrl: String): AuthConfig = config
    }

    private class RecordingNativeHost : NativeHostRequests {
        var request: OidcHostRequest? = null
            private set
        var requestCount = 0
            private set

        override fun requestOidc(request: OidcHostRequest) {
            this.request = request
            requestCount += 1
        }
    }
}
