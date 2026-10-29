package moe.ditto.halo.device

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.AddonsResponse
import moe.ditto.halo.api.CatalogResponse
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.api.HaloBackend
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.Manifest
import moe.ditto.halo.api.MetaResponse
import moe.ditto.halo.api.NextEpisodeResult
import moe.ditto.halo.api.SettingsPayload
import moe.ditto.halo.api.StreamsResult
import moe.ditto.halo.api.SubtitlesResult
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.auth.EpochClock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A stored document exists but cannot be read back; it is left untouched rather than overwritten. */
class DeviceDataException(collection: DeviceCollection) :
    Exception("The ${collection.name.lowercase()} saved on this phone could not be read.")

/**
 * Halo's backend for someone using it without an account: the contract
 * [moe.ditto.halo.api.HaloClient] offers, answered on the phone. A Kotlin port
 * of `packages/core/src/device/deviceBackend.ts`, which the desktop app runs;
 * keep the two in step.
 *
 * Addons are asked directly through [AddonResolver] (the rules the API server
 * runs), and the addon list, library, watch history and settings are kept in
 * [store] under the server's validation and newest-wins rules. Errors are
 * [HaloApiException]s with the statuses the server would answer, so screens
 * handle both alike. There are no global addons on a phone.
 *
 * Writes to one collection run one at a time, each re-reading the stored
 * document first: the player reports progress on a timer and again on pause,
 * so two reports can overlap, and neither may overwrite the other.
 */
internal class DeviceBackend(
    private val http: HttpClient,
    private val store: DeviceStore,
    private val clock: EpochClock,
    private val resolver: AddonResolver = AddonResolver(http),
) : HaloBackend {
    private val locks = DeviceCollection.entries.associateWith { Mutex() }

    /* ── Addons ─────────────────────────────────────────────────────────── */

    override suspend fun getAddons(): AddonsResponse =
        AddonsResponse(global = emptyList(), user = readAddons().mapIndexed { index, addon -> addon.toEntry(index) })

    /**
     * The server's diff contract: kept URLs keep their id and manifest, new
     * URLs have their manifest fetched and validated all or nothing, and URLs
     * left out are removed.
     */
    override suspend fun putAddons(transportUrls: List<String>): List<AddonEntry> {
        addonUrlsProblem(transportUrls)?.let { throw apiError(400, it) }
        return exclusive(DeviceCollection.Addons) {
            val current = readAddons().associateBy { it.transportUrl }
            val fetched = when (val result = resolver.manifests(transportUrls.filterNot(current::containsKey))) {
                is ManifestsResult.Failed -> throw apiError(400, result.message)
                is ManifestsResult.Fetched -> result.manifests
            }
            val addedAt = clock.nowMs()
            val next = transportUrls.map { url ->
                current[url] ?: StoredAddon(newId(), url, fetched.getValue(url), hideCatalogs = false, addedAt = addedAt)
            }
            writeList(DeviceCollection.Addons, StoredAddon.serializer(), next)
            next.mapIndexed { index, addon -> addon.toEntry(index) }
        }
    }

    override suspend fun patchAddon(addonId: String, hideCatalogs: Boolean) {
        exclusive(DeviceCollection.Addons) {
            val addons = readAddons()
            if (addons.none { it.id == addonId }) throw apiError(404, "addon not found")
            val next = addons.map { if (it.id == addonId) it.copy(hideCatalogs = hideCatalogs) else it }
            writeList(DeviceCollection.Addons, StoredAddon.serializer(), next)
        }
    }

    /* ── Resolution ─────────────────────────────────────────────────────── */

    override suspend fun getCatalog(
        addonId: String,
        type: String,
        id: String,
        extra: Map<String, String>,
    ): CatalogResponse {
        if (addonId.isEmpty() || type.isEmpty() || id.isEmpty()) throw apiError(400, "addon, type and id are required")
        catalogExtraProblem(extra)?.let { throw apiError(400, it) }
        val entry = readAddons().firstOrNull { it.id == addonId } ?: throw apiError(403, "addon not installed")
        return try {
            resolver.catalog(entry.resolvable(), type, id, extra)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw apiError(502, "catalog fetch failed")
        }
    }

    override suspend fun getMeta(type: String, id: String): MetaResponse {
        if (type.isEmpty() || id.isEmpty()) throw apiError(400, "type and id are required")
        return resolver.meta(resolvableAddons(), type, id) ?: throw apiError(404, "no metadata found")
    }

    override suspend fun getStreams(type: String, videoId: String): StreamsResult {
        if (type.isEmpty() || videoId.isEmpty()) throw apiError(400, "type and videoId are required")
        return resolver.streams(resolvableAddons(), type, videoId)
    }

    override suspend fun getNextEpisode(
        type: String,
        metaId: String,
        videoId: String,
        addonId: String?,
        bingeGroup: String?,
    ): NextEpisodeResult {
        if (type.isEmpty() || metaId.isEmpty() || videoId.isEmpty()) {
            throw apiError(400, "type, metaId and videoId are required")
        }
        // HaloClient leaves empty values out of the query, so the server never sees them.
        val addon = addonId?.takeIf { it.isNotEmpty() }
        val group = bingeGroup?.takeIf { it.isNotEmpty() }
        if (group != null && group.length > MaxBingeGroupLength) throw apiError(400, "bingeGroup too long")
        return resolver.nextEpisode(resolvableAddons(), type, metaId, videoId, addon, group, clock.nowMs())
            ?: throw apiError(404, "no metadata found")
    }

    override suspend fun getSubtitles(
        type: String,
        videoId: String,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
    ): SubtitlesResult {
        if (type.isEmpty() || videoId.isEmpty()) throw apiError(400, "type and videoId are required")
        // Same omissions as HaloClient's query string: empty values never reach the resolver.
        val query = SubtitleQuery(
            videoHash = videoHash?.takeIf { it.isNotEmpty() },
            videoSize = videoSize,
            filename = filename?.takeIf { it.isNotEmpty() },
        )
        subtitleQueryProblem(query)?.let { throw apiError(400, it) }
        return resolver.subtitles(resolvableAddons(), type, videoId, query)
    }

    /** Straight from the addon's host: there is no server here to proxy through. */
    override suspend fun openSubtitleFile(url: String): HttpResponse {
        val response = http.get(url)
        if (!response.status.isSuccess()) {
            response.bodyAsText()
            throw apiError(502, "subtitle fetch failed")
        }
        return response
    }

    /* ── Library, watch history, settings ───────────────────────────────── */

    /** Everything stored, tombstones included, as the server answers. */
    override suspend fun getLibrary(): List<LibraryItem> = readList(DeviceCollection.Library, LibraryItem.serializer())

    override suspend fun putLibrary(items: List<LibraryItem>): List<LibraryItem> {
        items.firstNotNullOfOrNull(::libraryItemProblem)?.let { throw apiError(400, it) }
        return exclusive(DeviceCollection.Library) {
            val merged = mergeLibrary(readList(DeviceCollection.Library, LibraryItem.serializer()), items)
            writeList(DeviceCollection.Library, LibraryItem.serializer(), merged)
            merged
        }
    }

    /** Removed rows stay stored, so the removal outlasts stale copies, but are never returned, as on the server. */
    override suspend fun getWatchStates(): List<WatchState> =
        liveWatchStates(readList(DeviceCollection.WatchStates, WatchState.serializer()))

    override suspend fun putWatchStates(states: List<WatchState>): List<WatchState> {
        states.firstNotNullOfOrNull(::watchStateProblem)?.let { throw apiError(400, it) }
        return exclusive(DeviceCollection.WatchStates) {
            val merged = mergeWatchStates(readList(DeviceCollection.WatchStates, WatchState.serializer()), states)
            writeList(DeviceCollection.WatchStates, WatchState.serializer(), merged)
            liveWatchStates(merged)
        }
    }

    override suspend fun getSettings(): SettingsPayload = readSettings() ?: SettingsPayload(UserSettings.Empty, 0)

    /** Stored only when strictly newer than what is there; answers whatever is stored afterwards. */
    override suspend fun putSettings(value: UserSettings, updatedAt: Long): SettingsPayload {
        settingsProblem(value, updatedAt)?.let { throw apiError(400, it) }
        return exclusive(DeviceCollection.Settings) {
            val stored = readSettings()
            if (stored != null && updatedAt <= stored.updatedAt) return@exclusive stored
            val next = SettingsPayload(value, updatedAt)
            store.write(
                DeviceCollection.Settings,
                HaloJson.encodeToString(SettingsDocument.serializer(), SettingsDocument(DocumentVersion, next.value, next.updatedAt)),
            )
            next
        }
    }

    /* ── Internals ──────────────────────────────────────────────────────── */

    private suspend fun <T> exclusive(collection: DeviceCollection, task: suspend () -> T): T =
        locks.getValue(collection).withLock { task() }

    private fun readAddons(): List<StoredAddon> = readList(DeviceCollection.Addons, StoredAddon.serializer())

    private fun resolvableAddons(): List<ResolvableAddon> = readAddons().map { it.resolvable() }

    private fun <T> readList(collection: DeviceCollection, serializer: KSerializer<T>): List<T> {
        val raw = store.read(collection) ?: return emptyList()
        val document = try {
            HaloJson.decodeFromString(ListDocument.serializer(serializer), raw)
        } catch (_: SerializationException) {
            throw DeviceDataException(collection)
        } catch (_: IllegalArgumentException) {
            throw DeviceDataException(collection)
        }
        if (document.version != DocumentVersion) throw DeviceDataException(collection)
        return document.items
    }

    private fun <T> writeList(collection: DeviceCollection, serializer: KSerializer<T>, items: List<T>) {
        store.write(collection, HaloJson.encodeToString(ListDocument.serializer(serializer), ListDocument(DocumentVersion, items)))
    }

    private fun readSettings(): SettingsPayload? {
        val raw = store.read(DeviceCollection.Settings) ?: return null
        val document = try {
            HaloJson.decodeFromString(SettingsDocument.serializer(), raw)
        } catch (_: SerializationException) {
            throw DeviceDataException(DeviceCollection.Settings)
        } catch (_: IllegalArgumentException) {
            throw DeviceDataException(DeviceCollection.Settings)
        }
        if (document.version != DocumentVersion) throw DeviceDataException(DeviceCollection.Settings)
        return SettingsPayload(document.value, document.updatedAt)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newId(): String = Uuid.random().toString()

    private companion object {
        const val DocumentVersion = 1
    }
}

@Serializable
internal data class StoredAddon(
    val id: String,
    val transportUrl: String,
    val manifest: Manifest,
    val hideCatalogs: Boolean,
    val addedAt: Long,
) {
    fun resolvable() = ResolvableAddon(id, transportUrl, manifest)

    /** Wire shape, with hidden catalogs stripped as the server strips them. */
    fun toEntry(position: Int) = AddonEntry(
        id = id,
        transportUrl = transportUrl,
        manifest = if (hideCatalogs) manifest.copy(catalogs = emptyList()) else manifest,
        position = position,
        hideCatalogs = hideCatalogs,
    )
}

@Serializable
private data class ListDocument<T>(val version: Int, val items: List<T>)

@Serializable
private data class SettingsDocument(val version: Int, val value: UserSettings, val updatedAt: Long)

/** The error the server would answer with, so callers handle both alike. */
private fun apiError(status: Int, message: String) = HaloApiException(status, message)
