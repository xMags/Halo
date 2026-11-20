package moe.ditto.halo.people

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.auth.SecureStorage

/** Whether a TMDB key is saved on this phone, and whether TMDB still takes it. */
enum class TmdbKeyStatus { None, Working, Refused }

enum class TmdbKeySaveResult { Saved, NotAKey, Refused, Unreachable, NotStored }

/**
 * The viewer's own TMDB key, for people search and IMDb/TVDB links without an
 * account. Kept in secure storage (the Keychain, or the Keystore-backed store
 * on Android), on this phone only: it never moves into an account, so it
 * outlives the documents an account takes over, and it is never shown again
 * once saved. Thread-safe: storage calls are synchronous and the status is a
 * state flow.
 */
class TmdbKeys(private val storage: SecureStorage) {
    @Serializable
    private data class Stored(val version: Int = 1, val kind: String, val value: String, val refused: Boolean = false)

    private val _status = MutableStateFlow(statusOf(read()))
    val status: StateFlow<TmdbKeyStatus> = _status

    private fun read(): Stored? {
        val raw = storage.read(Key) ?: return null
        val stored = try {
            HaloJson.decodeFromString(Stored.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Read back through the same shape check it was saved with.
        val parsed = parseTmdbCredential(stored.value) ?: return null
        return stored.takeIf { stored.version == 1 && kindOf(parsed) == stored.kind }
    }

    private fun statusOf(stored: Stored?): TmdbKeyStatus = when {
        stored == null -> TmdbKeyStatus.None
        stored.refused -> TmdbKeyStatus.Refused
        else -> TmdbKeyStatus.Working
    }

    /** The saved key for the on-device backend, or null when there is none or TMDB refused it. */
    fun usableCredential(): TmdbCredential? {
        val stored = read()?.takeUnless { it.refused } ?: return null
        return parseTmdbCredential(stored.value)
    }

    /** TMDB refused a key the backend sent: remembered, so the features stay off until it is replaced. */
    fun markRefused(credential: TmdbCredential) {
        val stored = read() ?: return
        if (stored.value != credential.value || stored.refused) return
        write(stored.copy(refused = true))
    }

    internal fun store(credential: TmdbCredential) = write(Stored(kind = kindOf(credential), value = credential.value))

    fun remove() {
        storage.delete(Key)
        _status.value = TmdbKeyStatus.None
    }

    private fun write(stored: Stored) {
        storage.write(Key, HaloJson.encodeToString(Stored.serializer(), stored))
        _status.value = statusOf(stored)
    }

    private fun kindOf(credential: TmdbCredential) = if (credential is TmdbCredential.ApiKey) "apiKey" else "readToken"

    private companion object {
        const val Key = "halo.device.tmdbKey.v1"
    }
}

/** How long TMDB gets to say whether it takes a key. */
private const val CheckTimeoutMs = 10_000L

/**
 * Checks a pasted key's shape, then asks TMDB whether it takes it (its
 * cheapest call, `/3/configuration`), and saves it only when it does.
 */
suspend fun saveTmdbKey(http: HttpClient, keys: TmdbKeys, pasted: String): TmdbKeySaveResult {
    val credential = parseTmdbCredential(pasted) ?: return TmdbKeySaveResult.NotAKey
    val status = try {
        withTimeout(CheckTimeoutMs) {
            val url = "https://api.themoviedb.org/3/configuration" +
                if (credential is TmdbCredential.ApiKey) "?api_key=${credential.value.encodeURLParameter()}" else ""
            val response = http.get(url) {
                header(HttpHeaders.Accept, "application/json")
                if (credential is TmdbCredential.ReadToken) header(HttpHeaders.Authorization, "Bearer ${credential.value}")
            }
            response.status
        }
    } catch (cancellation: CancellationException) {
        // A timeout is the network's answer, not the caller cancelling.
        if (cancellation is kotlinx.coroutines.TimeoutCancellationException) return TmdbKeySaveResult.Unreachable
        throw cancellation
    } catch (_: Exception) {
        return TmdbKeySaveResult.Unreachable
    }
    if (status.value == 401) return TmdbKeySaveResult.Refused
    if (!status.isSuccess()) return TmdbKeySaveResult.Unreachable
    return try {
        keys.store(credential)
        TmdbKeySaveResult.Saved
    } catch (_: Exception) {
        TmdbKeySaveResult.NotStored
    }
}
