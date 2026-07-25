package moe.ditto.halo.storage

import platform.Foundation.NSUserDefaults

/**
 * User defaults, which is the right home for this data: app-private, cheap to
 * read on the main thread, and removed with the app. Credentials go to the
 * Keychain instead, through the auth layer's own storage.
 */
class IosUserDefaultsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {
    override fun read(key: String): String? = defaults.stringForKey(key)

    override fun write(key: String, value: String) = defaults.setObject(value, forKey = key)

    override fun delete(key: String) = defaults.removeObjectForKey(key)
}
