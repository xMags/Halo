package moe.ditto.halo.storage

/**
 * App-private key/value storage for non-secret device state.
 *
 * Deliberately a separate interface from the auth layer's secure storage
 * rather than a second instance of it. That one is backed by the Keychain and
 * its Android counterpart, chosen and audited for credentials; routing
 * ordinary app data through it would widen a posture that is meant to stay
 * narrow, and pay the Keychain's cost for data that does not need it.
 *
 * Implementations must be safe to call from any thread. Values are small
 * strings, so calls are synchronous.
 */
interface KeyValueStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
}

object StorageKeys {
    /**
     * Last settings payload seen from the server. The player applies subtitle
     * and audio preferences at creation time, so it needs an answer before the
     * network has one — an unreachable server must not silently mean defaults.
     */
    const val SettingsMirror = "halo.settings.v1"
}
