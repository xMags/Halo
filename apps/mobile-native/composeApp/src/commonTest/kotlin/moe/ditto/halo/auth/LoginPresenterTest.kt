package moe.ditto.halo.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LoginPresenterTest {
    @Test
    fun credentialEditsAreIgnoredBeforeLocalModeDiscovery() {
        val presenter = presenter(AuthConfig.Local)
        presenter.editServerUrl("https://halo.local")

        presenter.editUsername("testuser")
        presenter.editPassword("secret")

        assertEquals("", presenter.state.username)
        assertEquals("", presenter.state.password)
    }

    @Test
    fun localModeRevealsCredentialsOnlyAfterDiscovery() = runTest {
        val presenter = presenter(AuthConfig.Local)
        presenter.editServerUrl(" https://halo.local/ ")

        presenter.continueFromServer()

        val phase = assertIs<LoginPhase.LocalCredentials>(presenter.state.phase)
        assertEquals("https://halo.local", phase.serverUrl)
        assertEquals("https://halo.local", presenter.state.serverUrl)
    }

    @Test
    fun editingServerResetsDiscoveredModeAndCredentials() = runTest {
        val presenter = presenter(AuthConfig.Local)
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
        val presenter = presenter(config, host)
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
        val presenter = presenter(AuthConfig.Local)
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

    @Test
    fun submitExchangesCredentialsForSessionAndWipesThePassword() = runTest {
        val authenticator = RecordingAuthenticator()
        val presenter = presenter(AuthConfig.Local, authenticator = authenticator)
        presenter.editServerUrl("https://halo.local")
        presenter.continueFromServer()
        presenter.editUsername(" testuser ")
        presenter.editPassword("hunter22")

        presenter.submitLocalCredentials()

        val phase = assertIs<LoginPhase.LocalSignedIn>(presenter.state.phase)
        assertEquals("https://halo.local", phase.serverUrl)
        assertEquals(listOf(Triple("https://halo.local", "testuser", "hunter22")), authenticator.calls)
        assertEquals("", presenter.state.password)
    }

    @Test
    fun submitWithBlankCredentialsIsIgnored() = runTest {
        val authenticator = RecordingAuthenticator()
        val presenter = presenter(AuthConfig.Local, authenticator = authenticator)
        presenter.editServerUrl("https://halo.local")
        presenter.continueFromServer()

        presenter.submitLocalCredentials()

        assertIs<LoginPhase.LocalCredentials>(presenter.state.phase)
        assertEquals(emptyList(), authenticator.calls)
    }

    @Test
    fun serverRejectionKeepsTheFormAndSurfacesTheServerMessage() = runTest {
        val authenticator = RecordingAuthenticator(failure = LocalAuthException(401, "invalid credentials"))
        val presenter = presenter(AuthConfig.Local, authenticator = authenticator)
        presenter.editServerUrl("https://halo.local")
        presenter.continueFromServer()
        presenter.editUsername("testuser")
        presenter.editPassword("wrong")

        presenter.submitLocalCredentials()

        assertIs<LoginPhase.LocalCredentials>(presenter.state.phase)
        assertEquals("invalid credentials", presenter.state.error)
        assertEquals("testuser", presenter.state.username)
    }

    @Test
    fun transportFailureReadsAsAConnectionProblemNotARejection() = runTest {
        val authenticator = RecordingAuthenticator(failure = RuntimeException())
        val presenter = presenter(AuthConfig.Local, authenticator = authenticator)
        presenter.editServerUrl("https://halo.local")
        presenter.continueFromServer()
        presenter.editUsername("testuser")
        presenter.editPassword("hunter22")

        presenter.submitLocalCredentials()

        assertIs<LoginPhase.LocalCredentials>(presenter.state.phase)
        assertEquals("Could not connect", presenter.state.error)
    }

    @Test
    fun aLateSubmitOutcomeCannotClobberAResetForm() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val presenter = presenter(
            AuthConfig.Local,
            authenticator = LocalAuthenticator { _, _, _ ->
                entered.complete(Unit)
                gate.await()
            },
        )
        presenter.editServerUrl("https://halo.local")
        presenter.continueFromServer()
        presenter.editUsername("testuser")
        presenter.editPassword("hunter22")

        val submit = launch { presenter.submitLocalCredentials() }
        // Only stage the race once the submit is provably in flight.
        entered.await()
        presenter.editServerUrl("https://other.example")
        gate.complete(Unit)
        submit.join()

        assertEquals(LoginPhase.Server, presenter.state.phase)
        assertEquals("https://other.example", presenter.state.serverUrl)
    }

    @Test
    fun submitOutsideTheCredentialsPhaseIsIgnored() = runTest {
        val authenticator = RecordingAuthenticator()
        val presenter = presenter(AuthConfig.Local, authenticator = authenticator)
        presenter.editServerUrl("https://halo.local")

        presenter.submitLocalCredentials()

        assertEquals(LoginPhase.Server, presenter.state.phase)
        assertEquals(emptyList(), authenticator.calls)
    }

    private suspend fun oidcPresenterAfterRequest(
        host: RecordingNativeHost = RecordingNativeHost(),
    ): LoginPresenter {
        val config = AuthConfig.Oidc(
            issuer = "https://auth.example/",
            clientId = "halo",
            scopes = listOf("openid", "offline_access"),
        )
        val presenter = presenter(config, host)
        presenter.editServerUrl("https://halo.example")
        presenter.continueFromServer()
        return presenter
    }

    private fun presenter(
        config: AuthConfig,
        host: RecordingNativeHost = RecordingNativeHost(),
        authenticator: LocalAuthenticator = LocalAuthenticator { _, _, _ -> },
    ): LoginPresenter = LoginPresenter(FakeConfigSource(config), host, authenticator)

    private class FakeConfigSource(private val config: AuthConfig) : AuthConfigSource {
        override suspend fun fetch(serverUrl: String): AuthConfig = config
    }

    private class RecordingAuthenticator(
        private val failure: Throwable? = null,
    ) : LocalAuthenticator {
        val calls = mutableListOf<Triple<String, String, String>>()

        override suspend fun signIn(serverUrl: String, username: String, password: String) {
            failure?.let { throw it }
            calls += Triple(serverUrl, username, password)
        }
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
