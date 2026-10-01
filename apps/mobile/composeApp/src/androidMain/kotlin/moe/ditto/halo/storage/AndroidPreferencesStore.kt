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
        check(prefs.edit().putString(key, value).commit()) {
            "Could not persist app state."
        }
    }

    override fun delete(key: String) {
        check(prefs.edit().remove(key).commit()) {
            "Could not delete app state."
        }
    }
}
