package moe.ditto.halo.auth

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Owns the session a [SignInAddon] produced: persistence and the post-401 check.
 *
 * Add-on sessions cannot be refreshed, so [refreshAccessToken] (the client's
 * post-401 hook) asks the Halo server instead whether it still accepts the
 * session. A 401 alone is not proof the session ended: the addon proxy relays
 * an addon's own 401. Still accepted returns the same token, so the client's
 * single retry runs and the session is kept; refused clears it. Checks are
 * single-flight, and transport failures (or a 503) propagate, so only the
 * server's definitive refusal ends a session.
 */
class AddonSessionManager(
    private val storage: SecureStorage,
    private val recheck: HaloSessionRecheck,
    private val scope: CoroutineScope,
) : TokenProvider {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var session: AddonSession? = null

    private val flightGuard = Mutex()
    private var inFlight: Deferred<Result<String?>>? = null

    val current: AddonSession? get() = session

    /** Loads the persisted session into memory; an unreadable blob means signed out. */
    fun restore(): AddonSession? {
        val raw = storage.read(AuthStorageKeys.AddonSession) ?: return null
        val restored = try {
            json.decodeFromString(AddonSession.serializer(), raw)
        } catch (_: SerializationException) {
            storage.delete(AuthStorageKeys.AddonSession)
            null
        } catch (_: IllegalArgumentException) {
            storage.delete(AuthStorageKeys.AddonSession)
            null
        }
        session = restored
        return restored
    }

    fun establish(data: AddonSession) {
        session = data
        storage.write(AuthStorageKeys.AddonSession, json.encodeToString(AddonSession.serializer(), data))
    }

    fun clear() {
        session = null
        storage.delete(AuthStorageKeys.AddonSession)
    }

    /** Clears the session and hands it back, so the caller can end it with its add-on. */
    fun clearAndTake(): AddonSession? = session.also { clear() }

    override suspend fun accessToken(): String? = session?.token

    override suspend fun refreshAccessToken(): String? {
        val flight = flightGuard.withLock {
            inFlight ?: scope.async {
                val outcome = runCatching { recheckNow() }
                withContext(NonCancellable) { flightGuard.withLock { inFlight = null } }
                outcome
            }.also { inFlight = it }
        }
        return flight.await().getOrThrow()
    }

    private suspend fun recheckNow(): String? {
        val current = session ?: return null
        return when (recheck.recheck(current.serverUrl, current.token)) {
            // A sign-out or new sign-in that raced the check wins.
            SessionRecheck.Alive -> if (session === current) current.token else session?.token
            SessionRecheck.Rejected -> {
                if (session === current) clear()
                null
            }
        }
    }
}
