package moe.ditto.halo.auth

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidOidcProtocolInstrumentedTest {
    @Test
    fun authorizationUrlContainsPkceAndExactRedirect() {
        val request = OidcHostRequest(
            serverUrl = "https://halo.ditto.moe",
            issuer = "https://auth.ditto.moe/application/o/halo/",
            clientId = "client",
            scopes = listOf("openid", "offline_access"),
        )
        val endpoints = AndroidOidcEndpoints(
            authorizationEndpoint = "https://auth.example/authorize/",
            tokenEndpoint = "https://auth.example/token/",
        )
        val verifier = "verifier-value"
        val url = AndroidOidcProtocol.authorizationUrl(request, endpoints, "state-value", verifier)

        assertTrue(url.startsWith("https://auth.example/authorize/?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client"))
        assertTrue(url.contains("redirect_uri=halo%3A%2F%2Foauth%2Fcallback"))
        assertTrue(url.contains("scope=openid%20offline_access"))
        assertTrue(url.contains("state=state-value"))
        assertTrue(url.contains("code_challenge=${AndroidOidcProtocol.codeChallenge(verifier)}"))
        assertTrue(url.contains("code_challenge_method=S256"))
    }

    @Test
    fun callbackRejectsWrongShapeStateMissingCodeAndOAuthError() {
        assertTrue(
            AndroidOidcProtocol.parseCallback(
                "https://oauth/callback?code=c&state=s",
                "s",
            ) is AndroidCallbackResult.NotCallback,
        )
        assertTrue(
            AndroidOidcProtocol.parseCallback("halo://oauth/callback?code=c&state=wrong", "s")
                is AndroidCallbackResult.StateMismatch,
        )
        assertTrue(
            AndroidOidcProtocol.parseCallback("halo://oauth/callback?state=s", "s")
                is AndroidCallbackResult.MissingCode,
        )
        val error = AndroidOidcProtocol.parseCallback(
            "halo://oauth/callback?error=access_denied&error_description=No%20thanks&state=s",
            "s",
        )
        assertEquals(
            "No thanks",
            (error as AndroidCallbackResult.AuthorizationError).reason,
        )
    }

    @Test
    fun tokenParsingAndExpiryKeepRefreshRotationAndInvalidGrantDistinct() {
        val success = AndroidOidcProtocol.parseTokenResponse(
            200,
            """{"access_token":"access-2","refresh_token":"refresh-2","id_token":"id","expires_in":120}""",
        ) as AndroidTokenResult.Success
        assertEquals("access-2", success.tokens.accessToken)
        assertEquals("refresh-2", success.tokens.refreshToken)
        assertEquals(120.0, success.tokens.expiresInSeconds!!, 0.0)
        assertTrue(AndroidOidcProtocol.expiresAtMs(1_000L, 120.0) > 1_000L)
        assertTrue(
            AndroidOidcProtocol.parseTokenResponse(400, """{"error":"invalid_grant"}""")
                is AndroidTokenResult.InvalidGrant,
        )
        assertTrue(
            AndroidOidcProtocol.parseTokenResponse(503, """{"error":"temporarily_unavailable"}""")
                is AndroidTokenResult.Failure,
        )
    }

    @Test
    fun sessionRefreshIsSingleFlightAndPersistsRotatedToken() = runBlocking {
        val storage = TestStorage()
        val calls = AtomicInteger()
        val wire = object : AndroidOidcWire {
            override suspend fun discover(issuer: String) = error("unused")

            override suspend fun postToken(endpoint: String, formBody: String): AndroidTokenResult {
                calls.incrementAndGet()
                delay(30)
                assertTrue(formBody.contains("refresh_token=refresh-1"))
                return AndroidTokenResult.Success(
                    AndroidIssuedTokens("access-2", "refresh-2", null, 300.0),
                )
            }

            override suspend fun revoke(endpoint: String, formBody: String) = Unit
        }
        val invalidations = AtomicInteger()
        val manager = AndroidOidcSessionManager(
            storage = storage,
            wire = wire,
            scope = CoroutineScope(Dispatchers.Default),
            nowMs = { 100_000L },
            onInvalidated = { invalidations.incrementAndGet() },
        )
        manager.establish(testSession(expiresAtMs = 0L))

        val tokens = withTimeout(2_000) {
            val first = async { manager.accessToken(false) }
            val second = async { manager.accessToken(true) }
            listOf(first.await(), second.await())
        }
        assertEquals(listOf("access-2", "access-2"), tokens)
        assertEquals(1, calls.get())
        assertEquals("refresh-2", AndroidOidcProtocol.decodeSession(storage.read(AndroidOidcStorageKeys.Session)!!).refreshToken)
        assertEquals(0, invalidations.get())
    }

    @Test
    fun invalidGrantClearsSessionButTransportFailurePreservesIt() = runBlocking {
        val storage = TestStorage()
        var result: AndroidTokenResult = AndroidTokenResult.InvalidGrant
        val manager = AndroidOidcSessionManager(
            storage,
            object : AndroidOidcWire {
                override suspend fun discover(issuer: String) = error("unused")
                override suspend fun postToken(endpoint: String, formBody: String) = result
                override suspend fun revoke(endpoint: String, formBody: String) = Unit
            },
            CoroutineScope(Dispatchers.Default),
            nowMs = { 100_000L },
            onInvalidated = {},
        )
        manager.establish(testSession(expiresAtMs = 0L))
        assertNull(manager.accessToken(true))
        assertNull(manager.restoreSession())

        result = AndroidTokenResult.Failure("network down")
        manager.establish(testSession(expiresAtMs = 0L))
        try {
            manager.accessToken(true)
            assertTrue("transport failure should throw", false)
        } catch (error: IllegalStateException) {
            assertEquals("network down", error.message)
        }
        assertEquals("https://halo.ditto.moe", manager.restoreSession())
    }

    @Test
    fun staleCallbackCannotConsumeNewPendingAttempt() = runBlocking {
        val storage = TestStorage()
        val wire = object : AndroidOidcWire {
            override suspend fun discover(issuer: String) = AndroidOidcEndpoints(
                "https://auth.example/authorize/",
                "https://auth.example/token/",
            )
            override suspend fun postToken(endpoint: String, formBody: String) = error("unused")
            override suspend fun revoke(endpoint: String, formBody: String) = Unit
        }
        val sessions = AndroidOidcSessionManager(storage, wire, CoroutineScope(Dispatchers.Default), onInvalidated = {})
        val logins = AndroidOidcLoginManager(storage, wire, sessions)
        val request = OidcHostRequest("https://halo.ditto.moe", "https://auth.example/", "client", listOf("openid"))
        val first = logins.reserveAttempt()
        val firstOpen = logins.begin(first, request) as AndroidBeginLoginResult.OpenBrowser
        val firstPending = AndroidOidcProtocol.decodePending(storage.read(AndroidOidcStorageKeys.PendingRequest)!!)
        val second = logins.reserveAttempt()
        logins.begin(second, request)

        val stale = logins.claimCallback(
            "halo://oauth/callback?code=old&state=${firstPending.state}",
        )
        assertTrue(stale is AndroidClaimCallbackResult.Ignored)
        assertNotNull(storage.read(AndroidOidcStorageKeys.PendingRequest))
        assertFalse(firstOpen.url.isEmpty())
    }

    @Test
    fun restoreReturnsServerWithoutNetwork() {
        val storage = TestStorage()
        storage.write(
            AndroidOidcStorageKeys.Session,
            AndroidOidcProtocol.encodeSession(testSession(expiresAtMs = 500_000L)),
        )
        val networkCalls = AtomicInteger()
        val wire = object : AndroidOidcWire {
            override suspend fun discover(issuer: String): AndroidOidcEndpoints {
                networkCalls.incrementAndGet()
                error("restore must be offline")
            }
            override suspend fun postToken(endpoint: String, formBody: String): AndroidTokenResult {
                networkCalls.incrementAndGet()
                error("restore must be offline")
            }
            override suspend fun revoke(endpoint: String, formBody: String) {
                networkCalls.incrementAndGet()
            }
        }
        val manager = AndroidOidcSessionManager(
            storage,
            wire,
            CoroutineScope(Dispatchers.Default),
            onInvalidated = {},
        )

        assertEquals("https://halo.ditto.moe", manager.restoreSession())
        assertEquals(0, networkCalls.get())
    }

    private fun testSession(expiresAtMs: Long) = AndroidOidcSession(
        serverUrl = "https://halo.ditto.moe",
        issuer = "https://auth.example/",
        clientId = "client",
        tokenEndpoint = "https://auth.example/token/",
        accessToken = "access-1",
        accessTokenExpiresAtMs = expiresAtMs,
        refreshToken = "refresh-1",
    )

    private class TestStorage : SecureStorage {
        private val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
    }
}
