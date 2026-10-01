package moe.ditto.halo.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.api.SettingsPayload
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.auth.EpochClock
import moe.ditto.halo.cache.HaloKey
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.storage.KeyValueStore
import moe.ditto.halo.storage.StorageKeys

/**
 * Synced preferences, kept as one document with a last-write-wins timestamp,
 * mirrored on the device.
 *
 * The mirror exists because preferences are needed before the network can
 * answer: the player fixes subtitle and audio options when it starts, so a
 * slow or unreachable server would otherwise mean silently playing with
 * defaults rather than the user's choices.
 */
class SettingsRepository(
    private val client: HaloClient,
    private val cache: QueryCache,
    private val store: KeyValueStore,
    private val clock: EpochClock,
) {
    fun observe(): Flow<QueryState<UserSettings>> =
        cache.query(HaloKey.Settings, HaloKey.Settings.staleMs) { load() }
            .map { QueryState(it.value?.value, it.isFetching, it.error) }

    /**
     * The best answer available without waiting: live cache, then the mirror,
     * then defaults. For callers that cannot render a loading state, such as
     * the player deciding which subtitle track to select.
     */
    suspend fun current(): UserSettings =
        cache.peek<SettingsPayload>(HaloKey.Settings)?.value ?: readMirror()?.value ?: UserSettings.Empty

    /**
     * Applies [patch] to the current settings and saves the result.
     *
     * The whole document is sent, not the patch. Preferences are stored as one
     * blob under a single timestamp, so a request carrying only the changed
     * field would overwrite the document and erase any edit made moments
     * earlier — the cache is patched first precisely so the request carries
     * both.
     */
    suspend fun update(patch: (UserSettings) -> UserSettings): UserSettings {
        val echo = cache.mutate<SettingsPayload>(
            key = HaloKey.Settings,
            optimistic = { current ->
                SettingsPayload(patch(current?.value ?: UserSettings.Empty), clock.nowMs())
            },
            put = { merged -> client.putSettings(merged.value, merged.updatedAt) },
            // The server keeps whichever write is newest, so it may answer with
            // a document older than one already applied here. Never let that
            // echo roll the newer one back.
            resolve = { current, echo -> if (current != null && current.updatedAt > echo.updatedAt) current else echo },
        )
        val resolved = cache.peek<SettingsPayload>(HaloKey.Settings) ?: echo
        writeMirror(resolved)
        return resolved.value
    }

    /**
     * Server first, mirror when it cannot be reached. An unreachable server is
     * not an empty settings document — falling back to defaults would silently
     * undo every preference for the duration of an outage.
     */
    private suspend fun load(): SettingsPayload {
        val payload = try {
            client.getSettings()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return readMirror() ?: throw failure
        }
        writeMirror(payload)
        return payload
    }

    private fun readMirror(): SettingsPayload? {
        val raw = store.read(StorageKeys.SettingsMirror) ?: return null
        return try {
            HaloJson.decodeFromString(SettingsPayload.serializer(), raw)
        } catch (_: SerializationException) {
            // A mirror written by an incompatible build is not worth a crash.
            null
        }
    }

    private fun writeMirror(payload: SettingsPayload) {
        store.write(StorageKeys.SettingsMirror, HaloJson.encodeToString(SettingsPayload.serializer(), payload))
    }
}
