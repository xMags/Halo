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
