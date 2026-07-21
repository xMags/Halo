package moe.ditto.halo.auth

/** In-memory [SecureStorage] stand-in for tests. */
class InMemorySecureStorage : SecureStorage {
    private val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}
