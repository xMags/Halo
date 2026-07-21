package moe.ditto.halo.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionControllerTest {

    private val now = 1_000_000_000_000L
    private val clock = EpochClock { now }

    @Test
    fun restoreWithoutAPersistedSessionIsSignedOut() = runTest {
        val controller = SessionController(InMemorySecureStorage(), NoNetworkGateway(), clock, backgroundScope)

        controller.restore()

        assertEquals(SessionState.SignedOut, controller.state.value)
    }

    @Test
    fun restoreBootsAPersistedSessionWithoutTheNetwork() = runTest {
        val storage = InMemorySecureStorage()
        storage.write(
            AuthStorageKeys.LocalSession,
            """{"serverUrl":"https://halo.local","token":"t1","expiresAt":${now + 1_000_000}}""",
        )
        // NoNetworkGateway throws on any call, so reaching SignedIn proves the
        // offline-boot property rather than assuming it.
        val controller = SessionController(storage, NoNetworkGateway(), clock, backgroundScope)

        controller.restore()

        assertEquals(SessionState.SignedIn(SessionKind.Local, "https://halo.local"), controller.state.value)
    }

    @Test
    fun signInPersistsTheSessionAndTheServerUrl() = runTest {
        val storage = InMemorySecureStorage()
        val gateway = LoginGateway(IssuedToken("issued-token", now + 1_000_000))
        val controller = SessionController(storage, gateway, clock, backgroundScope)
        controller.restore()

        controller.signIn("https://halo.local", "testuser", "hunter22")

        assertEquals(SessionState.SignedIn(SessionKind.Local, "https://halo.local"), controller.state.value)
        assertNotNull(storage.read(AuthStorageKeys.LocalSession))
        assertEquals("https://halo.local", storage.read(AuthStorageKeys.ServerUrl))
        assertEquals("issued-token", controller.tokenProvider.accessToken())
    }

    @Test
    fun failedSignInLeavesNoSessionBehind() = runTest {
        val storage = InMemorySecureStorage()
        val gateway = LoginGateway(failure = LocalAuthException(401, "invalid credentials"))
        val controller = SessionController(storage, gateway, clock, backgroundScope)
        controller.restore()

        assertFailsWith<LocalAuthException> {
            controller.signIn("https://halo.local", "testuser", "wrong")
        }

        assertEquals(SessionState.SignedOut, controller.state.value)
        assertNull(storage.read(AuthStorageKeys.LocalSession))
    }

    @Test
    fun definitiveRefreshRejectionSignsTheDeviceOut() = runTest {
        val storage = InMemorySecureStorage()
        storage.write(
            AuthStorageKeys.LocalSession,
            """{"serverUrl":"https://halo.local","token":"t1","expiresAt":${now + 1_000_000}}""",
        )
        val gateway = LoginGateway(refreshFailure = LocalAuthException(401, "session expired"))
        val controller = SessionController(storage, gateway, clock, backgroundScope)
        controller.restore()

        assertNull(controller.tokenProvider.refreshAccessToken())

        assertEquals(SessionState.SignedOut, controller.state.value)
        assertNull(storage.read(AuthStorageKeys.LocalSession))
    }

    @Test
    fun signOutClearsTheSessionButKeepsTheServerUrlPrefill() = runTest {
        val storage = InMemorySecureStorage()
        val gateway = LoginGateway(IssuedToken("issued-token", now + 1_000_000))
        val controller = SessionController(storage, gateway, clock, backgroundScope)
        controller.restore()
        controller.signIn("https://halo.local", "testuser", "hunter22")

        controller.signOut()

        assertEquals(SessionState.SignedOut, controller.state.value)
        assertNull(storage.read(AuthStorageKeys.LocalSession))
        assertEquals("https://halo.local", controller.storedServerUrl())
    }

    // ------------------------------------------------------------------
    // OIDC arm (fake port; the wire lives with the native host)
    // ------------------------------------------------------------------

    @Test
    fun restorePrefersLocalThenFallsBackToTheOidcPort() = runTest {
        val port = FakeOidcPort(persistedServerUrl = "https://halo.ditto.moe")
        val controller = SessionController(InMemorySecureStorage(), NoNetworkGateway(), clock, backgroundScope, port)

        controller.restore()

        assertEquals(SessionState.SignedIn(SessionKind.Oidc, "https://halo.ditto.moe"), controller.state.value)
    }

    @Test
    fun oidcSuccessEventEstablishesTheSessionAndTheServerPrefill() = runTest {
        val storage = InMemorySecureStorage()
        val controller = SessionController(storage, NoNetworkGateway(), clock, backgroundScope, FakeOidcPort())
        controller.restore()

        controller.onAuthEvent(AuthEvent.OidcSucceeded("https://halo.ditto.moe", "proof"))

        assertEquals(SessionState.SignedIn(SessionKind.Oidc, "https://halo.ditto.moe"), controller.state.value)
        assertEquals("https://halo.ditto.moe", controller.storedServerUrl())
    }

    @Test
    fun tokenProviderDispatchesToTheOidcPortWhileOidcIsSignedIn() = runTest {
        val port = FakeOidcPort(persistedServerUrl = "https://halo.ditto.moe", token = "oidc-token")
        val controller = SessionController(InMemorySecureStorage(), NoNetworkGateway(), clock, backgroundScope, port)
        controller.restore()

        assertEquals("oidc-token", controller.tokenProvider.accessToken())
        assertEquals(false, port.lastForceRefresh)
        assertEquals("oidc-token", controller.tokenProvider.refreshAccessToken())
        assertEquals(true, port.lastForceRefresh)
    }

    @Test
    fun oidcTransportFailureThrowsAndNeverSignsOut() = runTest {
        val port = FakeOidcPort(
            persistedServerUrl = "https://halo.ditto.moe",
            tokenFailure = IllegalStateException("refresh request failed"),
        )
        val controller = SessionController(InMemorySecureStorage(), NoNetworkGateway(), clock, backgroundScope, port)
        controller.restore()

        assertFailsWith<IllegalStateException> { controller.tokenProvider.accessToken() }

        // The invariant both arms share: only a definitive rejection may end a
        // session, and a transport failure is not one.
        assertEquals(SessionState.SignedIn(SessionKind.Oidc, "https://halo.ditto.moe"), controller.state.value)
    }

    @Test
    fun invalidationEventSignsOutOnlyTheOidcSessionItBelongsTo() = runTest {
        val port = FakeOidcPort(persistedServerUrl = "https://halo.ditto.moe")
        val controller = SessionController(InMemorySecureStorage(), NoNetworkGateway(), clock, backgroundScope, port)
        controller.restore()

        controller.onAuthEvent(AuthEvent.OidcSessionInvalidated)
        assertEquals(SessionState.SignedOut, controller.state.value)
    }

    @Test
    fun staleInvalidationCannotClobberALocalSession() = runTest {
        val storage = InMemorySecureStorage()
        val gateway = LoginGateway(IssuedToken("issued-token", now + 1_000_000))
        val controller = SessionController(storage, gateway, clock, backgroundScope, FakeOidcPort())
        controller.restore()
        controller.signIn("https://halo.local", "testuser", "hunter22")

        // A late OIDC invalidation (e.g. from a superseded session's in-flight
        // refresh) must not sign out the local session that replaced it.
        controller.onAuthEvent(AuthEvent.OidcSessionInvalidated)

        assertEquals(SessionState.SignedIn(SessionKind.Local, "https://halo.local"), controller.state.value)
    }

    @Test
    fun resetHatchClearsBothArmsBeforeRestoreCanRun() = runTest {
        val storage = InMemorySecureStorage()
        storage.write(
            AuthStorageKeys.LocalSession,
            """{"serverUrl":"https://halo.local","token":"t1","expiresAt":${now + 1_000_000}}""",
        )
        val port = FakeOidcPort(persistedServerUrl = "https://halo.ditto.moe")
        val controller = SessionController(storage, NoNetworkGateway(), clock, backgroundScope, port)

        controller.resetPersistedSessions()
        controller.restore()

        assertEquals(SessionState.SignedOut, controller.state.value)
        assertNull(storage.read(AuthStorageKeys.LocalSession))
        assertEquals(1, port.signOutCount)
    }

    private class FakeOidcPort(
        var persistedServerUrl: String? = null,
        var token: String? = null,
        var tokenFailure: Throwable? = null,
    ) : OidcSessionPort {
        var lastForceRefresh: Boolean? = null
            private set
        var signOutCount = 0
            private set

        override fun restoreSession(): String? = persistedServerUrl

        override suspend fun accessToken(forceRefresh: Boolean): String? {
            lastForceRefresh = forceRefresh
            tokenFailure?.let { throw it }
            return if (persistedServerUrl != null) token else null
        }

        override suspend fun signOut() {
            signOutCount += 1
            persistedServerUrl = null
        }
    }

    private class NoNetworkGateway : LocalAuthGateway {
        override suspend fun login(serverUrl: String, username: String, password: String): IssuedToken =
            error("network use is a test failure here")

        override suspend fun refresh(serverUrl: String, token: String): IssuedToken =
            error("network use is a test failure here")
    }

    private class LoginGateway(
        private val issued: IssuedToken? = null,
        private val failure: Throwable? = null,
        private val refreshFailure: Throwable? = null,
    ) : LocalAuthGateway {
        override suspend fun login(serverUrl: String, username: String, password: String): IssuedToken {
            failure?.let { throw it }
            return issued ?: error("no login result configured")
        }

        override suspend fun refresh(serverUrl: String, token: String): IssuedToken {
            refreshFailure?.let { throw it }
            return issued ?: error("no refresh result configured")
        }
    }
}
