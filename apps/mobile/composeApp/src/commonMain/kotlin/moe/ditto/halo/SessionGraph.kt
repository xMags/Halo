package moe.ditto.halo

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ditto.halo.api.HaloBackend
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.auth.EpochClock
import moe.ditto.halo.auth.SystemEpochClock
import moe.ditto.halo.auth.TokenProvider
import moe.ditto.halo.browse.AddonsRepository
import moe.ditto.halo.browse.BrowseRepository
import moe.ditto.halo.cache.HaloKey
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.device.DefaultAddonUrls
import moe.ditto.halo.device.DeviceBackend
import moe.ditto.halo.device.PlatformDeviceStore
import moe.ditto.halo.device.moveRecordsIntoAccount
import moe.ditto.halo.downloads.AddonDownloadSubtitles
import moe.ditto.halo.downloads.DeviceDownloadRuntime
import moe.ditto.halo.downloads.DownloadStoragePort
import moe.ditto.halo.player.StreamVideoHasher
import moe.ditto.halo.player.SubtitleFileCache
import moe.ditto.halo.storage.KeyValueStore
import moe.ditto.halo.storage.SearchHistoryStore
import moe.ditto.halo.storage.SubtitleChoiceStore
import moe.ditto.halo.storage.VideoFitModeStore
import moe.ditto.halo.sync.AccountRepository
import moe.ditto.halo.sync.LibraryRepository
import moe.ditto.halo.sync.SettingsRepository
import moe.ditto.halo.sync.WatchStateRepository

/** What a session's screens are answered from. */
internal sealed interface SessionMode {
    /** A signed-in account on a Halo server. */
    data class Account(
        val serverUrl: String,
        val tokens: TokenProvider,
        val onUnauthorized: suspend () -> Unit,
    ) : SessionMode

    /** Halo without an account: the addons are asked directly and everything is kept on the phone. */
    data object Device : SessionMode
}

/**
 * Everything a session needs, built once per session and thrown away with it.
 *
 * The lifetime is the point. The cache holds one user's collections and the
 * client holds one server's base URL, so both are wrong the moment either
 * changes — and the change is not always visible in session *state*, since
 * two users on the same server produce an equal one. Rebuilding on
 * `SessionController.sessionGeneration` is what keeps a signed-in user from
 * inheriting the previous one's library, and what keeps the phone's own data
 * apart from an account's.
 *
 * Dependencies are passed down through composable parameters rather than a
 * CompositionLocal: a local would resolve at runtime and read as available
 * everywhere, while a parameter is checked by the compiler and navigable by
 * the IDE.
 */
internal class SessionGraph(
    mode: SessionMode,
    private val deviceStore: PlatformDeviceStore,
    keyValueStore: KeyValueStore,
    subtitleCacheDirectory: String,
    downloadRuntime: DeviceDownloadRuntime,
    downloadStorage: DownloadStoragePort,
    private val clock: EpochClock = SystemEpochClock,
) {
    /**
     * Outlives every screen: an in-flight fetch must survive navigating away
     * so the next reader joins it instead of starting over. Supervised so one
     * failed request cannot cancel the rest.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Plain client on purpose — [HaloClient] serializes and deserializes
     * itself, so content negotiation would only add a second opinion about
     * how to decode addon payloads that must stay lenient.
     */
    private val httpClient = HttpClient()

    /** Account-only calls (who is signed in, the admin's global addons); null without an account. */
    val accountClient: HaloClient? = when (mode) {
        is SessionMode.Account -> HaloClient(mode.serverUrl, mode.tokens, httpClient, mode.onUnauthorized)
        SessionMode.Device -> null
    }

    /** What every screen asks: the account's server, or the phone itself without one. */
    val backend: HaloBackend = accountClient ?: DeviceBackend(httpClient, deviceStore, clock)

    val signedIn: Boolean
        get() = accountClient != null

    val cache = QueryCache(scope, clock)

    val addons = AddonsRepository(backend, cache, accountClient)
    val account = AccountRepository(accountClient, cache)
    val browse = BrowseRepository(backend, cache, addons)
    val library = LibraryRepository(backend, cache, clock)
    val watchStates = WatchStateRepository(backend, cache, clock)
    val settings = SettingsRepository(backend, cache, keyValueStore, clock)

    /**
     * Hashes a source over range requests so subtitle results match the exact
     * file. Shares the session's HTTP client: it talks to the source host, not
     * to Halo, and needs none of the client's auth or base URL.
     */
    val videoHasher = StreamVideoHasher(httpClient)
    val subtitleFiles = SubtitleFileCache(backend, subtitleCacheDirectory)

    /**
     * Device-owned and application-scoped. This graph contributes only its
     * subtitle lookup while the session exists.
     */
    val downloads = downloadRuntime
    private val detachDownloadSubtitles = downloads.bindSubtitleSource(
        AddonDownloadSubtitles(backend, videoHasher, settings, downloadStorage),
    )

    /**
     * Device-local, so they are not per-user the way the cache is; they are
     * rebuilt with the graph only because their in-memory state is read at
     * construction and would otherwise go stale against the store.
     */
    val searchHistory = SearchHistoryStore(keyValueStore)
    val subtitleChoices = SubtitleChoiceStore(keyValueStore, clock)
    val videoFitMode = VideoFitModeStore(keyValueStore)

    private val _addonsNotMoved = MutableStateFlow<List<String>>(emptyList())

    /**
     * Names of addons from Halo without an account that the account refused
     * on sign-in because their manifest did not load. The rest of the phone's
     * data moved; these have to be added again.
     */
    val addonsNotMoved: StateFlow<List<String>> = _addonsNotMoved

    init {
        val client = accountClient
        when {
            client == null -> scope.launch { seedDefaultAddons() }
            deviceStore.hasProfile() -> scope.launch { moveDeviceData(client) }
        }
    }

    /**
     * Halo without an account always has the built-in addons (Cinemeta,
     * OpenSubtitles, The Movie Database) so it is not empty. Any missing one
     * is added after the phone's own on every start: a first run may have had
     * no network, and a later build may add to the list. They cannot be
     * removed in this mode, so this never undoes a choice.
     */
    private suspend fun seedDefaultAddons() {
        try {
            val installed = backend.getAddons().user.mapNotNull { it.transportUrl }
            val missing = DefaultAddonUrls.filterNot { it in installed }
            if (missing.isNotEmpty()) addons.setUserAddons(installed + missing)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Best effort: the next start tries again.
        }
    }

    /**
     * Moves what Halo without an account kept on the phone into the account,
     * then clears it from the phone. Any failure keeps the phone's copy, and
     * the next signed-in start tries again; every step is safe to repeat.
     */
    private suspend fun moveDeviceData(account: HaloClient) {
        val notMoved = try {
            moveRecordsIntoAccount(DeviceBackend(httpClient, deviceStore, clock), account)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return
        }
        // Only after everything has moved: until then the phone keeps the only copy.
        deviceStore.clear()
        listOf(HaloKey.Library, HaloKey.WatchStates, HaloKey.Settings, HaloKey.Addons).forEach { cache.invalidate(it) }
        _addonsNotMoved.value = notMoved
    }

    /**
     * Ends the session's work and its connections. Cancelling the scope also
     * kills anything the cache still has in flight, which is the intent: those
     * requests carry the outgoing session's token and their results would land
     * in a cache nobody reads.
     */
    fun close() {
        detachDownloadSubtitles()
        scope.cancel()
        httpClient.close()
    }
}
