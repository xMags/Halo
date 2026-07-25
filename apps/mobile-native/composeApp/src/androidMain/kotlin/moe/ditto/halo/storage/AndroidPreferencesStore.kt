package moe.ditto.halo.storage

import android.content.Context

/**
 * App-private SharedPreferences, in a file of its own rather than the auth
 * layer's, so clearing credentials and clearing cached app data stay separate
 * operations.
 */
class AndroidPreferencesStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("halo.data", Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }
}
