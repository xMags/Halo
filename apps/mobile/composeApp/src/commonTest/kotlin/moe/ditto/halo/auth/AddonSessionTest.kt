package moe.ditto.halo.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AddonSessionTest {

    private val clock = EpochClock { 1_000_000_000_000L }
    private val persisted = AddonSession(
        mode = "example",
        serverUrl = "https://halo.example",
        token = "session-1",
        extras = mapOf("provider" to "https://sso.example"),
    )

    // ------------------------------------------------------------------
    // Session manager
    // ------------------------------------------------------------------

    @Test
    fun anAcceptedSessionKeepsItsTokenAfterA401() = runTest {
        val recheck = FakeSessionRecheck { SessionRecheck.Alive }
        val sessions = AddonSessionManager(InMemorySecureStorage(), recheck, backgroundScope)
        sessions.establish(persisted)

        assertEquals("session-1", sessions.refreshAccessToken())
        assertEquals(listOf("https://halo.example" to "session-1"), recheck.checks)
        assertEquals(persisted, sessions.current)
    }

    @Test
    fun aRefusedSessionIsClearedFromStorage() = runTest {
        val storage = InMemorySecureStorage()
        val sessions = AddonSessionManager(storage, FakeSessionRecheck { SessionRecheck.Rejected }, backgroundScope)
        sessions.establish(persisted)

        assertNull(sessions.refreshAccessToken())
        assertNull(sessions.current)
        assertNull(storage.read(AuthStorageKeys.AddonSession))
    }

    @Test
    fun aFailedCheckThrowsAndKeepsTheSession() = runTest {
        val storage = InMemorySecureStorage()
        val sessions = AddonSessionManager(storage, FakeSessionRecheck { throw IllegalStateException("HTTP 503") }, backgroundScope)
        sessions.establish(persisted)

        assertFailsWith<IllegalStateException> { sessions.refreshAccessToken() }
        assertEquals(persisted, sessions.current)
        assertNotNull(storage.read(AuthStorageKeys.AddonSession))
    }

    @Test
    fun concurrent401sShareOneCheck() = runTest {
        val release = CompletableDeferred<Unit>()
        val recheck = FakeSessionRecheck {
            release.await()
            SessionRecheck.Alive
        }
        val sessions = AddonSessionManager(InMemorySecureStorage(), recheck, backgroundScope)
        sessions.establish(persisted)

        val waiters = List(5) { async { sessions.refreshAccessToken() } }
        advanceUntilIdle()
        release.complete(Unit)

        assertEquals(List(5) { "session-1" }, waiters.awaitAll())
        assertEquals(1, recheck.checks.size)
    }

    @Test
    fun aPersistedSessionRoundTripsAndAnUnreadableBlobRestoresAsSignedOut() = runTest {
        val storage = InMemorySecureStorage()
        AddonSessionManager(storage, FakeSessionRecheck(), backgroundScope).establish(persisted)
        assertEquals(persisted, AddonSessionManager(storage, FakeSessionRecheck(), backgroundScope).restore())

        storage.write(AuthStorageKeys.AddonSession, "{not json")
        assertNull(AddonSessionManager(storage, FakeSessionRecheck(), backgroundScope).restore())
        assertNull(storage.read(AuthStorageKeys.AddonSession))
    }

    // ------------------------------------------------------------------
    // Session controller, add-on arm
    // ------------------------------------------------------------------

    private val config = JsonObject(mapOf("mode" to JsonPrimitive("example")))

    private fun controller(
        storage: SecureStorage,
        addon: FakeSignInAddon,
        scope: CoroutineScope,
        recheck: FakeSessionRecheck = FakeSessionRecheck(),
    ) = SessionController(
        storage = storage,
        gateway = NoLocalGateway(),
        clock = clock,
        scope = scope,
        addons = listOf(addon),
        browserSignIn = NoBrowserSignIn,
        sessionRecheck = recheck,
    )

    @Test
    fun anAddonSignInPersistsTheSessionAndTheServerUrl() = runTest {
        val storage = InMemorySecureStorage()
        val addon = FakeSignInAddon(token = "session-9", extras = mapOf("provider" to "https://sso.example"))
        val controller = controller(storage, addon, backgroundScope)
        controller.restore()

        controller.signInWithAddon("https://halo.example", AuthConfig.Addon(addon, config))

        assertEquals(SessionState.SignedIn(SessionKind.Addon, "https://halo.example"), controller.state.value)
        assertEquals("session-9", controller.tokenProvider.accessToken())
        assertEquals("https://halo.example", controller.storedServerUrl())
        assertEquals(
            AddonSession("example", "https://halo.example", "session-9", mapOf("provider" to "https://sso.example")),
            controller.addonSessions.current,
        )
        val signIn = addon.signIns.single()
        assertEquals(config, signIn.config)
        assertSame(NoBrowserSignIn, signIn.browser)
    }

    @Test
    fun aRefusedAddonSignInLeavesNoSessionBehind() = runTest {
        val storage = InMemorySecureStorage()
        val addon = FakeSignInAddon(failure = SignInAddonException("This account is disabled."))
        val controller = controller(storage, addon, backgroundScope)
        controller.restore()

        val error = assertFailsWith<SignInAddonException> {
            controller.signInWithAddon("https://halo.example", AuthConfig.Addon(addon, config))
        }
        assertEquals("This account is disabled.", error.message)
        assertEquals(SessionState.SignedOut, controller.state.value)
        assertNull(storage.read(AuthStorageKeys.AddonSession))
    }

    @Test
    fun aPersistedAddonSessionRestoresWithoutTheNetwork() = runTest {
        val storage = InMemorySecureStorage()
        AddonSessionManager(storage, FakeSessionRecheck(), backgroundScope).establish(persisted)
        val recheck = FakeSessionRecheck()
        val controller = controller(storage, FakeSignInAddon(), backgroundScope, recheck)

        controller.restore()

        assertEquals(SessionState.SignedIn(SessionKind.Addon, "https://halo.example"), controller.state.value)
        assertTrue(recheck.checks.isEmpty())
    }

    @Test
    fun the401PathDispatchesToTheAddonCheck() = runTest {
        val storage = InMemorySecureStorage()
        val addon = FakeSignInAddon()
        val controller = controller(storage, addon, backgroundScope, FakeSessionRecheck { SessionRecheck.Rejected })
        controller.restore()
        controller.signInWithAddon("https://halo.example", AuthConfig.Addon(addon, config))
        val generation = controller.sessionGeneration.value

        assertNull(controller.tokenProvider.refreshAccessToken())
        controller.rejectSession(generation)

        assertEquals(SessionState.SignedOut, controller.state.value)
        assertEquals(SessionController.RejectionNotice, controller.loginNotice.value)
        assertNull(storage.read(AuthStorageKeys.AddonSession))
    }

    @Test
    fun signingOutHandsTheEndedSessionToItsAddon() = runTest {
        val storage = InMemorySecureStorage()
        val addon = FakeSignInAddon(token = "session-5")
        val controller = controller(storage, addon, backgroundScope)
        controller.restore()
        controller.signInWithAddon("https://halo.example", AuthConfig.Addon(addon, config))

        controller.signOut()
        // The add-on's sign-out runs off-path in the controller's (background) scope.
        runCurrent()

        assertEquals(SessionState.SignedOut, controller.state.value)
        assertNull(storage.read(AuthStorageKeys.AddonSession))
        assertEquals(listOf(AddonSession("example", "https://halo.example", "session-5")), addon.signOuts)
    }

    /** The add-on arm must never touch the local-accounts wire. */
    private class NoLocalGateway : LocalAuthGateway {
        override suspend fun login(serverUrl: String, username: String, password: String): IssuedToken =
            error("no local sign-in in these tests")

        override suspend fun refresh(serverUrl: String, token: String): IssuedToken =
            error("no local refresh in these tests")
    }
}
