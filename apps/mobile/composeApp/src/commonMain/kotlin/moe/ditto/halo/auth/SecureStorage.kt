package moe.ditto.halo.auth

/**
 * Owned key/value storage for auth secrets — Keychain on iOS, app-private
 * storage on Android. Deliberately not a library dependency: the surface is
 * three calls and the security posture of each platform impl needs to be
 * auditable in this repo.
 *
 * Implementations must be safe to call from any thread; values are small
 * JSON blobs, so calls are synchronous.
 */
interface SecureStorage {
    fun read(key: String): String?

    /**
     * Like [read], but a value that is stored and cannot be read right now
     * (the device still locked, a passing keystore fault) throws
     * [SecureStorageUnavailableException] instead of reading as absent. For
     * data the caller would otherwise write over, such as the addon list of
     * Halo without an account, which is the only copy.
     */
    fun readStrict(key: String): String? = read(key)

    fun write(key: String, value: String)
    fun delete(key: String)
}

/**
 * Storage keys shared with the Expo client's naming so a reader can grep one
 * name across both apps. The stores are separate (SecureStore vs Keychain
 * service), so no runtime collision or migration is implied.
 */
/** A value is stored under the key but cannot be read now; it is kept, not lost. */
class SecureStorageUnavailableException(message: String) : Exception(message)

object AuthStorageKeys {
    const val LocalSession = "halo.localSession"
    const val AddonSession = "halo.addonSession"
    const val ServerUrl = "halo.serverUrl"
}
