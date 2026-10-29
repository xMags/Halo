package moe.ditto.halo.api

import io.ktor.client.statement.HttpResponse

/**
 * Everything the app's screens ask of a backend. [HaloClient] answers it from
 * the user's account on the Halo server; `DeviceBackend` answers it on the
 * phone for people using Halo without an account. Account-only calls (who is
 * signed in, the admin's global addons) stay on [HaloClient].
 *
 * Both answer with the same shapes and fail with the same [HaloApiException]
 * statuses, so a screen cannot tell which one it is talking to.
 */
interface HaloBackend {
    /** Global addons plus the caller's own; without an account there are no global ones. */
    suspend fun getAddons(): AddonsResponse

    /**
     * Declares the caller's own addon list, array order being priority. Kept
     * URLs keep their id; new ones have their manifest fetched and validated,
     * all or nothing.
     */
    suspend fun putAddons(transportUrls: List<String>): List<AddonEntry>

    suspend fun patchAddon(addonId: String, hideCatalogs: Boolean)

    /** One catalog from one installed addon, addressed by its opaque [AddonEntry.id]. */
    suspend fun getCatalog(
        addonId: String,
        type: String,
        id: String,
        extra: Map<String, String> = emptyMap(),
    ): CatalogResponse

    /** First addon that can describe this type/id wins; 404 when none can. */
    suspend fun getMeta(type: String, id: String): MetaResponse

    /** Asks every stream-capable addon; playable streams grouped by addon. */
    suspend fun getStreams(type: String, videoId: String): StreamsResult

    /** The episode after [videoId] and, when it can be matched, its stream. */
    suspend fun getNextEpisode(
        type: String,
        metaId: String,
        videoId: String,
        addonId: String? = null,
        bingeGroup: String? = null,
    ): NextEpisodeResult

    /** External subtitles from every subtitle-capable addon. */
    suspend fun getSubtitles(
        type: String,
        videoId: String,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
    ): SubtitlesResult

    suspend fun getLibrary(): List<LibraryItem>

    suspend fun putLibrary(items: List<LibraryItem>): List<LibraryItem>

    suspend fun getWatchStates(): List<WatchState>

    suspend fun putWatchStates(states: List<WatchState>): List<WatchState>

    suspend fun getSettings(): SettingsPayload

    suspend fun putSettings(value: UserSettings, updatedAt: Long): SettingsPayload

    /**
     * Opens a subtitle file an addon listed. Signed in, it comes through the
     * server's authenticated proxy; without an account, straight from the
     * addon's host. Throws on a non-success status.
     */
    suspend fun openSubtitleFile(url: String): HttpResponse
}
