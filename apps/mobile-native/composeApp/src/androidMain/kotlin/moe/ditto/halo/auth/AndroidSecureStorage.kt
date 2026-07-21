package moe.ditto.halo.auth

import android.content.Context

/**
 * App-private SharedPreferences. Values are plaintext at rest — acceptable
 * only while Android builds are emulator/test targets; any Android build that
 * installs on a real device must first move this behind Android Keystore
 * encryption (the iOS counterpart already sits in the Keychain).
 */
class AndroidSecureStorage(context: Context) : SecureStorage {
    private val prefs = context.getSharedPreferences("halo.auth", Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }
}
