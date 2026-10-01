package moe.ditto.halo.auth

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The persisted local-mode session blob. */
@Serializable
data class LocalSessionData(
    val serverUrl: String,
    val token: String,
    val expiresAt: Long,
)

/**
 * Owns the local-mode session: persistence, expiry-band refresh scheduling,
 * and the sign-out invariant.
 *
 * Refresh bands, from token expiry backwards:
 * - inside [EXPIRY_MARGIN_MS]: the token is unusable; refresh is mandatory and
 *   a transport failure propagates to the caller.
 * - inside [PROACTIVE_REFRESH_MS]: the token still works; refresh is
 *   best-effort so an active device slides its session well before expiry,
 *   and a transport failure falls back to the current token.
 * - otherwise the cached token is returned untouched.
 *
 * Refreshes are single-flight: concurrent callers share one network call and
 * its outcome, including a failure — a waiter must never silently receive a
 * stale token in place of the error, or a post-401 retry would sign the
 * device out on a network blip.
 *
 * The session is cleared ONLY on a definitive server rejection (HTTP 401 from
 * the refresh endpoint, which covers both a dead token and the server's
 * absolute session-age cap). Nothing else — not transport failures, not 5xx —
 * may end a session.
 */
class LocalSessionManager(
    private val storage: SecureStorage,
    private val gateway: LocalAuthGateway,
    private val clock: EpochClock,
    private val scope: CoroutineScope,
    private val onSessionInvalidated: () -> Unit = {},
) : TokenProvider {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var session: LocalSessionData? = null

    private val flightGuard = Mutex()
    private var inFlight: Deferred<Result<String?>>? = null

    val current: LocalSessionData? get() = session

    /** Loads the persisted session into memory; an unreadable blob means signed out. */
    fun restore(): LocalSessionData? {
        val raw = storage.read(AuthStorageKeys.LocalSession) ?: return null
        val restored = try {
            json.decodeFromString(LocalSessionData.serializer(), raw)
        } catch (_: SerializationException) {
            storage.delete(AuthStorageKeys.LocalSession)
            null
        } catch (_: IllegalArgumentException) {
            storage.delete(AuthStorageKeys.LocalSession)
            null
        }
        session = restored
        return restored
    }

    fun establish(data: LocalSessionData) {
        session = data
        storage.write(AuthStorageKeys.LocalSession, json.encodeToString(LocalSessionData.serializer(), data))
    }

    fun clear() {
        session = null
        storage.delete(AuthStorageKeys.LocalSession)
    }

    override suspend fun accessToken(): String? {
        val current = session ?: return null
        val remaining = current.expiresAt - clock.nowMs()
        return when {
            remaining <= EXPIRY_MARGIN_MS -> refreshAccessToken()
            remaining <= PROACTIVE_REFRESH_MS -> try {
                refreshAccessToken() ?: return null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Best-effort band: the current token is still valid, so a
                // transport failure is tolerable here and only here.
                session?.token
            }
            else -> current.token
        }
    }

    override suspend fun refreshAccessToken(): String? {
        val flight = flightGuard.withLock {
            // The flight completes with an encapsulated Result so a failure
            // reaches every waiter through await() and never escapes into the
            // owning scope as an unhandled coroutine exception.
            inFlight ?: scope.async {
                val outcome = runCatching { refreshNow() }
                // NonCancellable: if the owning scope is cancelled mid-flight,
                // the latch must still be released or every later refresh
                // would await a dead Deferred.
                withContext(NonCancellable) { flightGuard.withLock { inFlight = null } }
                outcome
            }.also { inFlight = it }
        }
        return flight.await().getOrThrow()
    }

    private suspend fun refreshNow(): String? {
        val current = session ?: return null
        val issued = try {
            gateway.refresh(current.serverUrl, current.token)
        } catch (error: LocalAuthException) {
            if (error.status == 401) {
                // Only end the session the server actually rejected: a
                // sign-out or a new sign-in that raced this flight must not
                // be clobbered by its outcome.
                if (session === current) {
                    clear()
                    onSessionInvalidated()
                }
                return null
            }
            throw error
        }
        if (session !== current) return session?.token
        val next = current.copy(token = issued.token, expiresAt = issued.expiresAt)
        establish(next)
        return next.token
    }

    companion object {
        /** Beneath this the token cannot be trusted to survive a request round-trip. */
        const val EXPIRY_MARGIN_MS: Long = 60_000

        /**
         * Tokens live 30 days; refreshing any time inside the final 15 keeps an
         * active device signed in indefinitely, up to the server's absolute cap.
         */
        const val PROACTIVE_REFRESH_MS: Long = 15L * 24 * 60 * 60 * 1000
    }
}
