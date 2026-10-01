package moe.ditto.halo.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Android-owned, encrypted OIDC session lifecycle with single-flight refresh. */
internal class AndroidOidcSessionManager(
    private val storage: SecureStorage,
    private val wire: AndroidOidcWire,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onInvalidated: () -> Unit,
) {
    private val lock = Any()
    private var sessionLoaded = false
    private var session: AndroidOidcSession? = null
    private var generation = 0L
    private var refreshInFlight: Deferred<String?>? = null

    fun restoreSession(): String? = synchronized(lock) {
        loadSessionLocked()?.serverUrl
    }

    suspend fun accessToken(forceRefresh: Boolean): String? {
        val access = synchronized(lock) {
            val current = loadSessionLocked() ?: return@synchronized Access.Immediate(null)
            val remaining = current.accessTokenExpiresAtMs - nowMs()
            if (!forceRefresh && remaining > AndroidOidcProtocol.ExpiryMarginMs) {
                return@synchronized Access.Immediate(current.accessToken)
            }

            refreshInFlight?.let { return@synchronized Access.Await(it) }
            val refreshGeneration = generation
            val deferred = scope.async(start = CoroutineStart.LAZY) {
                refreshNow(current, refreshGeneration)
            }
            refreshInFlight = deferred
            deferred.invokeOnCompletion {
                synchronized(lock) {
                    if (refreshInFlight === deferred) refreshInFlight = null
                }
            }
            Access.Await(deferred)
        }

        return when (access) {
            is Access.Immediate -> access.token
            is Access.Await -> {
                // Lazy start closes the assignment/completion race: the
                // deferred is visible in refreshInFlight before any network
                // work can complete and clear the latch.
                access.future.start()
                access.future.await()
            }
        }
    }

    fun establish(session: AndroidOidcSession) {
        synchronized(lock) {
            storage.write(AndroidOidcStorageKeys.Session, AndroidOidcProtocol.encodeSession(session))
            this.session = session
            sessionLoaded = true
            generation += 1
        }
    }

    fun clearAndTake(): AndroidOidcSession? = synchronized(lock) {
        val existing = loadSessionLocked()
        storage.delete(AndroidOidcStorageKeys.Session)
        session = null
        sessionLoaded = true
        generation += 1
        existing
    }

    private suspend fun refreshNow(current: AndroidOidcSession, refreshGeneration: Long): String? {
        val form = AndroidOidcProtocol.tokenForm(
            grantType = "refresh_token",
            clientId = current.clientId,
            values = listOf("refresh_token" to current.refreshToken),
        )
        return when (val result = wire.postToken(current.tokenEndpoint, form)) {
            is AndroidTokenResult.Success -> applyRefresh(current, refreshGeneration, result.tokens)
            AndroidTokenResult.InvalidGrant -> invalidate(refreshGeneration)
            is AndroidTokenResult.Failure -> throw IllegalStateException(result.reason)
        }
    }

    private fun applyRefresh(
        current: AndroidOidcSession,
        refreshGeneration: Long,
        issued: AndroidIssuedTokens,
    ): String? = synchronized(lock) {
        if (generation != refreshGeneration) return@synchronized session?.accessToken
        val updated = current.copy(
            accessToken = issued.accessToken,
            accessTokenExpiresAtMs = AndroidOidcProtocol.expiresAtMs(nowMs(), issued.expiresInSeconds),
            refreshToken = issued.refreshToken ?: current.refreshToken,
            idToken = issued.idToken ?: current.idToken,
        )
        // One encrypted JSON record makes rotating-token persistence atomic.
        storage.write(AndroidOidcStorageKeys.Session, AndroidOidcProtocol.encodeSession(updated))
        session = updated
        updated.accessToken
    }

    private fun invalidate(refreshGeneration: Long): String? {
        val didInvalidate = synchronized(lock) {
            if (generation != refreshGeneration) return@synchronized false
            storage.delete(AndroidOidcStorageKeys.Session)
            session = null
            sessionLoaded = true
            generation += 1
            true
        }
        if (didInvalidate) onInvalidated()
        return null
    }

    private fun loadSessionLocked(): AndroidOidcSession? {
        if (sessionLoaded) return session
        sessionLoaded = true
        val encoded = storage.read(AndroidOidcStorageKeys.Session) ?: return null
        session = try {
            AndroidOidcProtocol.decodeSession(encoded)
        } catch (_: Exception) {
            storage.delete(AndroidOidcStorageKeys.Session)
            null
        }
        return session
    }

    private sealed interface Access {
        data class Immediate(val token: String?) : Access
        data class Await(val future: Deferred<String?>) : Access
    }
}

internal sealed interface AndroidBeginLoginResult {
    data class OpenBrowser(val attempt: Long, val url: String) : AndroidBeginLoginResult
    data class Failure(val reason: String) : AndroidBeginLoginResult
    data object Superseded : AndroidBeginLoginResult
}

internal sealed interface AndroidClaimCallbackResult {
    data object NotCallback : AndroidClaimCallbackResult
    data object Ignored : AndroidClaimCallbackResult
    data class Failure(val reason: String) : AndroidClaimCallbackResult
    data class Exchange(val claim: AndroidOidcCodeClaim) : AndroidClaimCallbackResult
}

internal data class AndroidOidcCodeClaim(
    val attempt: Long,
    val code: String,
    val pending: AndroidPendingOidcRequest,
)

internal sealed interface AndroidCompleteLoginResult {
    data class Success(val serverUrl: String, val accessToken: String) : AndroidCompleteLoginResult
    data class Failure(val reason: String) : AndroidCompleteLoginResult
    data object Superseded : AndroidCompleteLoginResult
}

/** Serializes pending PKCE attempts so a stale browser callback cannot win. */
internal class AndroidOidcLoginManager(
    private val storage: SecureStorage,
    private val wire: AndroidOidcWire,
    private val sessions: AndroidOidcSessionManager,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var generation = 0L

    fun reserveAttempt(): Long = synchronized(lock) {
        generation += 1
        storage.delete(AndroidOidcStorageKeys.PendingRequest)
        generation
    }

    suspend fun begin(attempt: Long, request: OidcHostRequest): AndroidBeginLoginResult {
        val endpoints = try {
            wire.discover(request.issuer)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return failureIfCurrent(attempt, "Discovery failed: ${error.message ?: "Could not connect"}")
        }
        val state = AndroidOidcProtocol.randomUrlSafe()
        val verifier = AndroidOidcProtocol.randomUrlSafe()
        val pending = AndroidPendingOidcRequest(
            serverUrl = request.serverUrl,
            issuer = request.issuer,
            clientId = request.clientId,
            scopes = request.scopes,
            state = state,
            verifier = verifier,
            endpoints = endpoints,
        )
        val authorizationUrl = try {
            AndroidOidcProtocol.authorizationUrl(request, endpoints, state, verifier)
        } catch (error: IllegalArgumentException) {
            return failureIfCurrent(attempt, error.message ?: "Could not build authorization URL")
        }

        return synchronized(lock) {
            if (attempt != generation) return@synchronized AndroidBeginLoginResult.Superseded
            storage.write(AndroidOidcStorageKeys.PendingRequest, AndroidOidcProtocol.encodePending(pending))
            AndroidBeginLoginResult.OpenBrowser(attempt, authorizationUrl)
        }
    }

    fun claimCallback(url: String): AndroidClaimCallbackResult = synchronized(lock) {
        if (AndroidOidcProtocol.isLogoutCallback(url)) return@synchronized AndroidClaimCallbackResult.Ignored
        val pending = loadPendingLocked() ?: return@synchronized when (
            AndroidOidcProtocol.parseCallback(url, expectedState = "")
        ) {
            AndroidCallbackResult.NotCallback -> AndroidClaimCallbackResult.NotCallback
            else -> AndroidClaimCallbackResult.Ignored
        }

        return@synchronized when (val callback = AndroidOidcProtocol.parseCallback(url, pending.state)) {
            AndroidCallbackResult.NotCallback -> AndroidClaimCallbackResult.NotCallback
            // A mismatched callback may belong to the browser attempt that the
            // current one superseded. It must not fail or consume the new one.
            AndroidCallbackResult.StateMismatch -> AndroidClaimCallbackResult.Ignored
            is AndroidCallbackResult.AuthorizationError -> {
                storage.delete(AndroidOidcStorageKeys.PendingRequest)
                AndroidClaimCallbackResult.Failure(callback.reason)
            }
            AndroidCallbackResult.MissingCode -> {
                storage.delete(AndroidOidcStorageKeys.PendingRequest)
                AndroidClaimCallbackResult.Failure("Authorization response had no code")
            }
            is AndroidCallbackResult.Code -> {
                storage.delete(AndroidOidcStorageKeys.PendingRequest)
                AndroidClaimCallbackResult.Exchange(
                    AndroidOidcCodeClaim(generation, callback.value, pending),
                )
            }
        }
    }

    suspend fun exchange(claim: AndroidOidcCodeClaim): AndroidCompleteLoginResult {
        val pending = claim.pending
        val form = AndroidOidcProtocol.tokenForm(
            grantType = "authorization_code",
            clientId = pending.clientId,
            values = listOf(
                "redirect_uri" to AndroidOidcProtocol.RedirectUri,
                "code" to claim.code,
                "code_verifier" to pending.verifier,
            ),
        )
        val outcome = try {
            wire.postToken(pending.endpoints.tokenEndpoint, form)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return completeFailureIfCurrent(claim.attempt, "Token request failed: ${error.message ?: "Could not connect"}")
        }

        return when (outcome) {
            AndroidTokenResult.InvalidGrant -> completeFailureIfCurrent(
                claim.attempt,
                "Token exchange failed: invalid_grant",
            )
            is AndroidTokenResult.Failure -> completeFailureIfCurrent(claim.attempt, outcome.reason)
            is AndroidTokenResult.Success -> {
                val refreshToken = outcome.tokens.refreshToken
                    ?: return completeFailureIfCurrent(
                        claim.attempt,
                        "Token response did not include refresh_token",
                    )
                synchronized(lock) {
                    if (claim.attempt != generation) {
                        return@synchronized AndroidCompleteLoginResult.Superseded
                    }
                    val session = AndroidOidcSession(
                        serverUrl = pending.serverUrl,
                        issuer = pending.issuer,
                        clientId = pending.clientId,
                        tokenEndpoint = pending.endpoints.tokenEndpoint,
                        revocationEndpoint = pending.endpoints.revocationEndpoint,
                        endSessionEndpoint = pending.endpoints.endSessionEndpoint,
                        accessToken = outcome.tokens.accessToken,
                        accessTokenExpiresAtMs = AndroidOidcProtocol.expiresAtMs(
                            nowMs(),
                            outcome.tokens.expiresInSeconds,
                        ),
                        refreshToken = refreshToken,
                        idToken = outcome.tokens.idToken,
                    )
                    try {
                        sessions.establish(session)
                        AndroidCompleteLoginResult.Success(pending.serverUrl, outcome.tokens.accessToken)
                    } catch (error: Throwable) {
                        AndroidCompleteLoginResult.Failure(
                            error.message ?: "Could not persist the OIDC session",
                        )
                    }
                }
            }
        }
    }

    fun cancelAttempt(attempt: Long): Boolean = synchronized(lock) {
        if (attempt != generation || loadPendingLocked() == null) return@synchronized false
        storage.delete(AndroidOidcStorageKeys.PendingRequest)
        generation += 1
        true
    }

    fun supersedeSilently() {
        synchronized(lock) {
            generation += 1
            storage.delete(AndroidOidcStorageKeys.PendingRequest)
        }
    }

    fun isCurrent(attempt: Long): Boolean = synchronized(lock) { attempt == generation }

    private fun failureIfCurrent(attempt: Long, reason: String): AndroidBeginLoginResult = synchronized(lock) {
        if (attempt == generation) AndroidBeginLoginResult.Failure(reason)
        else AndroidBeginLoginResult.Superseded
    }

    private fun completeFailureIfCurrent(attempt: Long, reason: String): AndroidCompleteLoginResult =
        synchronized(lock) {
            if (attempt == generation) AndroidCompleteLoginResult.Failure(reason)
            else AndroidCompleteLoginResult.Superseded
        }

    private fun loadPendingLocked(): AndroidPendingOidcRequest? {
        val encoded = storage.read(AndroidOidcStorageKeys.PendingRequest) ?: return null
        return try {
            AndroidOidcProtocol.decodePending(encoded)
        } catch (_: Exception) {
            storage.delete(AndroidOidcStorageKeys.PendingRequest)
            null
        }
    }
}
