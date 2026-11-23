package moe.ditto.halo.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.AddonsResponse
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.cache.QueryState
import kotlin.time.TimeSource

private const val LatencyProbeIntervalMillis = 15_000L

/**
 * What a settings screen does, apart from how it looks: the addon list edits,
 * the language and autoplay preferences, the server's measured latency, and
 * the one-at-a-time rule for changes with their error line. The phone's
 * Settings and the TV's both draw from this, so the rules exist once.
 */
internal class SettingsSession(
    private val graph: SessionGraph,
    private val scope: CoroutineScope,
) {
    var userAddonUrl by mutableStateOf("")
    var globalAddonUrl by mutableStateOf("")
    var pendingAction by mutableStateOf<String?>(null)
        private set
    var mutationError by mutableStateOf<String?>(null)
        private set
    var latencyMillis by mutableStateOf<Long?>(null)
        internal set

    internal var addons: QueryState<AddonsResponse> = QueryState()
    internal var settings: QueryState<UserSettings> = QueryState()

    private fun launchMutation(key: String, failureMessage: String, action: suspend () -> Unit) {
        if (pendingAction != null) return
        pendingAction = key
        mutationError = null
        scope.launch {
            try {
                action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutationError = failureMessage
            } finally {
                pendingAction = null
            }
        }
    }

    private fun currentUrls(entries: List<AddonEntry>): List<String>? {
        val urls = entries.mapNotNull { it.transportUrl }
        return urls.takeIf { it.size == entries.size }
    }

    fun addAddon(ownership: AddonOwnership) {
        val raw = if (ownership == AddonOwnership.User) userAddonUrl else globalAddonUrl
        val url = normalizedAddonUrl(raw)
        if (url == null) {
            mutationError = "Enter a complete HTTP or HTTPS manifest URL."
            return
        }
        val addons = addons.value
        if (addons == null) {
            mutationError = "Wait for the addon list to finish loading."
            return
        }
        // Every installed entry, not just the effective ones: a personal copy
        // of a global addon is installed even though it is not in use.
        if ((addons.global + addons.user).any { it.transportUrl == url }) {
            mutationError = "That addon is already installed."
            return
        }
        val entries = if (ownership == AddonOwnership.User) addons.user else addons.global
        if (currentUrls(entries) == null) {
            mutationError = "This addon list cannot be edited safely until it is refreshed."
            return
        }
        val key = if (ownership == AddonOwnership.User) "user-add" else "global-add"
        launchMutation(key, "Could not install the addon. Check the manifest URL and try again.") {
            // Added to the list as the server has it now, not the one shown.
            val add = { current: List<String> -> if (url in current) current else current + url }
            if (ownership == AddonOwnership.User) {
                graph.addons.editUserAddons(add)
                userAddonUrl = ""
            } else {
                graph.addons.editGlobalAddons(add)
                globalAddonUrl = ""
            }
        }
    }

    fun removeAddon(entry: AddonEntry, ownership: AddonOwnership) {
        val addons = addons.value ?: return
        val entries = if (ownership == AddonOwnership.User) addons.user else addons.global
        currentUrls(entries) ?: run {
            mutationError = "This addon list cannot be edited safely until it is refreshed."
            return
        }
        val removedUrl = entry.transportUrl ?: run {
            mutationError = "This addon does not expose an editable manifest URL."
            return
        }
        val key = "${ownership.name.lowercase()}-remove-${entry.id}"
        launchMutation(key, "Could not remove the addon. Try again.") {
            val remove = { current: List<String> -> current.filterNot { it == removedUrl } }
            if (ownership == AddonOwnership.User) {
                graph.addons.editUserAddons(remove)
            } else {
                graph.addons.editGlobalAddons(remove)
            }
        }
    }

    fun toggleCatalogs(entry: AddonEntry, ownership: AddonOwnership) {
        val key = "${ownership.name.lowercase()}-catalogs-${entry.id}"
        launchMutation(key, "Could not update catalog visibility. Try again.") {
            if (ownership == AddonOwnership.User) {
                graph.addons.setHideCatalogs(entry.id, !entry.hideCatalogs)
            } else {
                graph.addons.setGlobalHideCatalogs(entry.id, !entry.hideCatalogs)
            }
        }
    }

    fun updateLanguage(kind: SettingsLanguageKind, code: String?) {
        val key = if (kind == SettingsLanguageKind.Audio) "audio-language" else "subtitle-language"
        launchMutation(key, "Could not save the language preference. Try again.") {
            graph.settings.update { current ->
                if (kind == SettingsLanguageKind.Audio) {
                    current.withPreferredAudioLang(code)
                } else {
                    current.withPreferredSubtitleLang(code)
                }
            }
        }
    }

    fun updateCatalogLanguage(language: String?) {
        launchMutation("catalog-language", "Could not save the language for Home's rows. Try again.") {
            graph.settings.update { it.withCatalogLanguage(language) }
        }
    }

    fun updateAutoplay(enabled: Boolean) {
        launchMutation("autoplay", "Could not save the autoplay preference. Try again.") {
            graph.settings.update { it.withAutoplayNextEpisode(enabled) }
        }
    }
}

/** The settings session for [graph], with its data observed and its latency measured while on screen. */
@Composable
internal fun rememberSettingsSession(graph: SessionGraph): Pair<SettingsSession, SettingsUiState> {
    val scope = rememberCoroutineScope()
    val session = remember(graph, scope) { SettingsSession(graph, scope) }
    val addonsState by remember(graph) { graph.addons.observe() }.collectAsState(QueryState())
    val accountState by remember(graph) { graph.account.observe() }.collectAsState(QueryState())
    val settingsState by remember(graph) { graph.settings.observe() }.collectAsState(QueryState())
    val addonsNotMoved by graph.addonsNotMoved.collectAsState()
    session.addons = addonsState
    session.settings = settingsState

    // Settings shows how quick the server is rather than where it lives. A
    // small authenticated request is timed end to end, the same path every
    // screen's calls take, and re-measured while the screen stays open. A
    // failed probe keeps the last reading: the Status row reports outages.
    // Without an account there is no server to time.
    LaunchedEffect(graph) {
        val client = graph.accountClient ?: return@LaunchedEffect
        while (true) {
            val started = TimeSource.Monotonic.markNow()
            try {
                client.getMe()
                session.latencyMillis = started.elapsedNow().inWholeMilliseconds
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
            }
            delay(LatencyProbeIntervalMillis)
        }
    }

    val state = SettingsUiState(
        signedIn = graph.signedIn,
        addonsNotMoved = addonsNotMoved,
        latencyMillis = session.latencyMillis,
        addons = addonsState.value,
        account = accountState.value,
        settings = settingsState.value,
        accountFetching = accountState.isFetching,
        settingsFetching = settingsState.isFetching,
        addonsError = addonsState.error != null,
        accountError = accountState.error != null,
        settingsError = settingsState.error != null,
        pendingAction = session.pendingAction,
        mutationError = session.mutationError,
    )
    return session to state
}
