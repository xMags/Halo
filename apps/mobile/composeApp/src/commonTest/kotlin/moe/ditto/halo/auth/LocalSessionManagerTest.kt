package moe.ditto.halo.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSessionManagerTest {

    private val now = 1_000_000_000_000L
    private val clock = EpochClock { now }

    private fun sessionExpiringIn(remainingMs: Long) = LocalSessionData(
        serverUrl = "https://halo.local",
        token = "current-token",
        expiresAt = now + remainingMs,
    )

    @Test
    fun accessTokenIsNullWithoutASession() = runTest {
        val manager = LocalSessionManager(InMemorySecureStorage(), ThrowingGateway(), clock, backgroundScope)

        assertNull(manager.accessToken())
    }

    @Test
    fun freshTokenIsReturnedWithoutTouchingTheNetwork() = runTest {
        val gateway = ThrowingGateway()
        val manager = LocalSessionManager(InMemorySecureStorage(), gateway, clock, backgroundScope)
        manager.establish(sessionExpiringIn(DAYS_16))

        assertEquals("current-token", manager.accessToken())
    }

    @Test
    fun proactiveBandRefreshesAndPersistsTheRotatedToken() = runTest {
        val storage = InMemorySecureStorage()
        val gateway = FakeGateway(refreshed = IssuedToken("rotated-token", now + DAYS_30))
        val manager = LocalSessionManager(storage, gateway, clock, backgroundScope)
        manager.establish(sessionExpiringIn(DAYS_14))

        assertEquals("rotated-token", manager.accessToken())
        assertEquals(1, gateway.refreshCalls)
        assertTrue(storage.read(AuthStorageKeys.LocalSession)!!.contains("rotated-token"))
    }

    @Test
    fun proactiveBandToleratesTransportFailureAndKeepsTheCurrentToken() = runTest {
        val gateway = ThrowingGateway()
        val manager = LocalSessionManager(InMemorySecureStorage(), gateway, clock, backgroundScope)
        manager.establish(sessionExpiringIn(DAYS_14))

        assertEquals("current-token", manager.accessToken())
        assertEquals("current-token", manager.current?.token)
    }

    @Test
    fun expiryMarginMakesRefreshMandatoryAndPropagatesTransportFailure() = runTest {
        val manager = LocalSessionManager(InMemorySecureStorage(), ThrowingGateway(), clock, backgroundScope)
        manager.establish(sessionExpiringIn(30_000))

        assertFailsWith<FakeTransportException> { manager.accessToken() }
        // The session survives a network failure unconditionally.
        assertEquals("current-token", manager.current?.token)
    }

    @Test
    fun definitiveRejectionClearsTheSessionAndSignalsInvalidation() = runTest {
        val storage = InMemorySecureStorage()
        var invalidated = 0
        val gateway = FakeGateway(refreshFailure = LocalAuthException(401, "session expired"))
        val manager = LocalSessionManager(storage, gateway, clock, backgroundScope, onSessionInvalidated = { invalidated++ })
        manager.establish(sessionExpiringIn(30_000))

        assertNull(manager.refreshAccessToken())
        assertNull(manager.current)
        assertNull(storage.read(AuthStorageKeys.LocalSession))
        assertEquals(1, invalidated)
    }

    @Test
    fun serverErrorPropagatesWithoutEndingTheSession() = runTest {
        val storage = InMemorySecureStorage()
        val gateway = FakeGateway(refreshFailure = LocalAuthException(500, "boom"))
        val manager = LocalSessionManager(storage, gateway, clock, backgroundScope)
        manager.establish(sessionExpiringIn(30_000))

        assertFailsWith<LocalAuthException> { manager.refreshAccessToken() }
        assertEquals("current-token", manager.current?.token)
        assertTrue(storage.read(AuthStorageKeys.LocalSession)!!.contains("current-token"))
    }

    @Test
    fun concurrentRefreshCallersShareOneNetworkCall() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(refreshed = IssuedToken("rotated-token", now + DAYS_30), gate = gate)
        val manager = LocalSessionManager(InMemorySecureStorage(), gateway, clock, backgroundScope)
        manager.establish(sessionExpiringIn(30_000))

        val callers = (1..5).map { async { manager.refreshAccessToken() } }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)

        assertEquals(List(5) { "rotated-token" }, callers.awaitAll())
        assertEquals(1, gateway.refreshCalls)
    }

    @Test
    fun concurrentRefreshCallersShareTheFailure() = runTest {
        val gateway = ThrowingGateway()
        val manager = LocalSessionManager(InMemorySecureStorage(), gateway, clock, backgroundScope)
        manager.establish(sessionExpiringIn(30_000))

        val callers = (1..3).map { async { runCatching { manager.refreshAccessToken() } } }
        testScheduler.advanceUntilIdle()

        val outcomes = callers.awaitAll()
        assertTrue(outcomes.all { it.isFailure }, "a waiter must never get a stale token in place of the shared failure")
        assertEquals(1, gateway.refreshCalls)
        assertEquals("current-token", manager.current?.token)
    }

    @Test
    fun signOutDuringAnInFlightRefreshIsNotClobberedByItsSuccess() = runTest {
        val gate = CompletableDeferred<Unit>()
        val storage = InMemorySecureStorage()
        val gateway = FakeGateway(refreshed = IssuedToken("rotated-token", now + DAYS_30), gate = gate)
        val manager = LocalSessionManager(storage, gateway, clock, backgroundScope)
        manager.establish(sessionExpiringIn(30_000))

        val flight = async { manager.refreshAccessToken() }
        gateway.entered.await()
        manager.clear()
        gate.complete(Unit)

        assertNull(flight.await())
        assertNull(manager.current)
        assertNull(storage.read(AuthStorageKeys.LocalSession))
    }

    @Test
    fun aRacedDefinitiveRejectionDoesNotEndTheReplacementSession() = runTest {
        val gate = CompletableDeferred<Unit>()
        var invalidated = 0
        val gateway = FakeGateway(refreshFailure = LocalAuthException(401, "unauthorized"), gate = gate)
        val manager = LocalSessionManager(
            InMemorySecureStorage(), gateway, clock, backgroundScope,
            onSessionInvalidated = { invalidated++ },
        )
        manager.establish(sessionExpiringIn(30_000))

        val flight = async { manager.refreshAccessToken() }
        gateway.entered.await()
        val replacement = LocalSessionData("https://other.local", "new-token", now + DAYS_30)
        manager.establish(replacement)
        gate.complete(Unit)

        assertNull(flight.await())
        assertEquals(replacement, manager.current)
        assertEquals(0, invalidated)
    }

    @Test
    fun restoreRoundTripsThePersistedSession() = runTest {
        val storage = InMemorySecureStorage()
        LocalSessionManager(storage, ThrowingGateway(), clock, backgroundScope)
            .establish(sessionExpiringIn(DAYS_16))

        val restored = LocalSessionManager(storage, ThrowingGateway(), clock, backgroundScope).restore()

        assertEquals(sessionExpiringIn(DAYS_16), restored)
    }

    @Test
    fun corruptPersistedSessionReadsAsSignedOutAndIsRemoved() = runTest {
        val storage = InMemorySecureStorage()
        storage.write(AuthStorageKeys.LocalSession, "{not json")
        val manager = LocalSessionManager(storage, ThrowingGateway(), clock, backgroundScope)

        assertNull(manager.restore())
        assertNull(storage.read(AuthStorageKeys.LocalSession))
    }

    private class FakeTransportException : Exception("connection reset")

    /** Every call is a transport failure; also proves code paths that must not touch the network. */
    private class ThrowingGateway : LocalAuthGateway {
        var refreshCalls = 0
            private set

        override suspend fun login(serverUrl: String, username: String, password: String) =
            throw FakeTransportException()

        override suspend fun refresh(serverUrl: String, token: String): IssuedToken {
            refreshCalls += 1
            throw FakeTransportException()
        }
    }

    private class FakeGateway(
        private val refreshed: IssuedToken? = null,
        private val refreshFailure: Throwable? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : LocalAuthGateway {
        var refreshCalls = 0
            private set

        /** Completes once [refresh] is provably suspended at [gate] — race tests await this before staging the race. */
        val entered = CompletableDeferred<Unit>()

        override suspend fun login(serverUrl: String, username: String, password: String): IssuedToken =
            error("unused")

        override suspend fun refresh(serverUrl: String, token: String): IssuedToken {
            refreshCalls += 1
            entered.complete(Unit)
            gate?.await()
            refreshFailure?.let { throw it }
            return refreshed ?: error("no refresh result configured")
        }
    }

    private companion object {
        const val DAYS_14 = 14L * 24 * 60 * 60 * 1000
        const val DAYS_16 = 16L * 24 * 60 * 60 * 1000
        const val DAYS_30 = 30L * 24 * 60 * 60 * 1000
    }
}
