package moe.ditto.halo

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.auth.EpochClock
import moe.ditto.halo.auth.SystemEpochClock
import moe.ditto.halo.auth.TokenProvider
import moe.ditto.halo.browse.AddonsRepository
import moe.ditto.halo.browse.BrowseRepository
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.storage.KeyValueStore
import moe.ditto.halo.storage.SearchHistoryStore
import moe.ditto.halo.storage.SubtitleChoiceStore
import moe.ditto.halo.sync.LibraryRepository
import moe.ditto.halo.sync.SettingsRepository
import moe.ditto.halo.sync.WatchStateRepository

/**
 * Everything a signed-in session needs, built once per session and thrown away
 * with it.
 *
 * The lifetime is the point. The cache holds one user's collections and the
 * client holds one server's base URL, so both are wrong the moment either
 * changes — and the change is not always visible in session *state*, since
 * two users on the same server produce an equal one. Rebuilding on
 * `SessionController.sessionGeneration` is what keeps a signed-in user from
 * inheriting the previous one's library.
 *
 * Dependencies are passed down through composable parameters rather than a
 * CompositionLocal: a local would resolve at runtime and read as available
 * everywhere, while a parameter is checked by the compiler and navigable by
 * the IDE.
 */
internal class SignedInGraph(
    serverUrl: String,
    tokens: TokenProvider,
    keyValueStore: KeyValueStore,
    clock: EpochClock = SystemEpochClock,
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

    val client = HaloClient(serverUrl, tokens, httpClient)
    val cache = QueryCache(scope, clock)

    val addons = AddonsRepository(client, cache)
    val browse = BrowseRepository(client, cache, addons)
    val library = LibraryRepository(client, cache, clock)
    val watchStates = WatchStateRepository(client, cache, clock)
    val settings = SettingsRepository(client, cache, keyValueStore, clock)

    /**
     * Device-local, so they are not per-user the way the cache is; they are
     * rebuilt with the graph only because their in-memory state is read at
     * construction and would otherwise go stale against the store.
     */
    val searchHistory = SearchHistoryStore(keyValueStore)
    val subtitleChoices = SubtitleChoiceStore(keyValueStore, clock)

    /**
     * Ends the session's work and its connections. Cancelling the scope also
     * kills anything the cache still has in flight, which is the intent: those
     * requests carry the outgoing session's token and their results would land
     * in a cache nobody reads.
     */
    fun close() {
        scope.cancel()
        httpClient.close()
    }
}
