package moe.ditto.halo.device

import moe.ditto.halo.auth.SecureStorage
import moe.ditto.halo.storage.KeyValueStore

/** The documents Halo without an account keeps, one per collection. */
enum class DeviceCollection {
    Addons,
    Library,
    WatchStates,
    Settings,
}

/** Where Halo without an account keeps its documents. A missing document reads as null. */
interface DeviceStore {
    fun read(collection: DeviceCollection): String?

    fun write(collection: DeviceCollection, contents: String)
}

/**
 * Whether this phone is used without an account. The profile is created by
 * "Continue without an account" and exists until its data has moved into an
 * account, so a launch with one and no session goes straight back to it.
 */
interface DeviceProfileStore {
    fun hasProfile(): Boolean

    fun createProfile()

    /** Removes the profile and every document, once their contents live in an account. */
    fun clear()
}

/** No profile can exist: for hosts and tests that never use Halo without an account. */
object NoDeviceProfile : DeviceProfileStore {
    override fun hasProfile(): Boolean = false

    override fun createProfile() = Unit

    override fun clear() = Unit
}

/**
 * The phone's documents. On this device they are the only copy.
 *
 * The addon list goes to [secure] storage (the Keychain, or the Keystore-backed
 * store on Android): addon URLs routinely embed debrid API keys, which is why
 * the server never hands them out either. The library, watch history,
 * settings and the profile marker are not secret and go to [plain] app-private
 * storage, beside the downloads index.
 */
class PlatformDeviceStore(
    private val secure: SecureStorage,
    private val plain: KeyValueStore,
) : DeviceStore, DeviceProfileStore {
    override fun read(collection: DeviceCollection): String? = when (collection) {
        DeviceCollection.Addons -> secure.read(AddonsKey)
        else -> plain.read(collection.plainKey())
    }

    override fun write(collection: DeviceCollection, contents: String) {
        when (collection) {
            DeviceCollection.Addons -> secure.write(AddonsKey, contents)
            else -> plain.write(collection.plainKey(), contents)
        }
    }

    override fun hasProfile(): Boolean = plain.read(ProfileKey) != null

    override fun createProfile() {
        if (!hasProfile()) plain.write(ProfileKey, ProfileVersion)
    }

    /** The profile goes last: until it is gone, a failed clear is retried on the next move. */
    override fun clear() {
        secure.delete(AddonsKey)
        DeviceCollection.entries.filter { it != DeviceCollection.Addons }.forEach { plain.delete(it.plainKey()) }
        plain.delete(ProfileKey)
    }

    private fun DeviceCollection.plainKey(): String = when (this) {
        DeviceCollection.Addons -> error("The addon list is kept in secure storage")
        DeviceCollection.Library -> "halo.device.library.v1"
        DeviceCollection.WatchStates -> "halo.device.watchStates.v1"
        DeviceCollection.Settings -> "halo.device.settings.v1"
    }

    private companion object {
        const val AddonsKey = "halo.device.addons.v1"
        const val ProfileKey = "halo.device.profile.v1"
        const val ProfileVersion = "1"
    }
}
