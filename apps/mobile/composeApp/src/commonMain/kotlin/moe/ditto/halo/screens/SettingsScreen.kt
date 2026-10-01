package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ditto.halo.SignedInGraph
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.LanguageOptions
import moe.ditto.halo.ui.SelectOption
import moe.ditto.halo.ui.SelectSheet

private const val NoLanguage = "none"
private const val RemoveAddon = "remove"

private sealed interface SettingsOverlay {
    data class Language(val kind: SettingsLanguageKind) : SettingsOverlay
    data class Remove(val entry: AddonEntry, val ownership: AddonOwnership) : SettingsOverlay
}
/** The original app's settings surface, backed by the signed-in graph. */
@Composable
internal fun SettingsScreen(
    graph: SignedInGraph,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDebugGate: (() -> Unit)? = null,
) {
    val addonsState by remember(graph) { graph.addons.observe() }.collectAsState(QueryState())
    val accountState by remember(graph) { graph.account.observe() }.collectAsState(QueryState())
    val settingsState by remember(graph) { graph.settings.observe() }.collectAsState(QueryState())
    val scope = rememberCoroutineScope()

    var userAddonUrl by remember { mutableStateOf("") }
    var globalAddonUrl by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf<SettingsOverlay?>(null) }

    fun launchMutation(key: String, failureMessage: String, action: suspend () -> Unit) {
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

    fun currentUrls(entries: List<AddonEntry>): List<String>? {
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
        val addons = addonsState.value
        if (addons == null) {
            mutationError = "Wait for the addon list to finish loading."
            return
        }
        if (addons.effective.any { it.transportUrl == url }) {
            mutationError = "That addon is already installed."
            return
        }
        val entries = if (ownership == AddonOwnership.User) addons.user else addons.global
        val urls = currentUrls(entries)
        if (urls == null) {
            mutationError = "This addon list cannot be edited safely until it is refreshed."
            return
        }
        val key = if (ownership == AddonOwnership.User) "user-add" else "global-add"
        launchMutation(key, "Could not install the addon. Check the manifest URL and try again.") {
            if (ownership == AddonOwnership.User) {
                graph.addons.setUserAddons(urls + url)
                userAddonUrl = ""
            } else {
                graph.addons.setGlobalAddons(urls + url)
                globalAddonUrl = ""
            }
        }
    }

    fun removeAddon(entry: AddonEntry, ownership: AddonOwnership) {
        val addons = addonsState.value ?: return
        val entries = if (ownership == AddonOwnership.User) addons.user else addons.global
        val urls = currentUrls(entries) ?: run {
            mutationError = "This addon list cannot be edited safely until it is refreshed."
            return
        }
        val removedUrl = entry.transportUrl ?: run {
            mutationError = "This addon does not expose an editable manifest URL."
            return
        }
        val key = "${ownership.name.lowercase()}-remove-${entry.id}"
        launchMutation(key, "Could not remove the addon. Try again.") {
            if (ownership == AddonOwnership.User) {
                graph.addons.setUserAddons(urls.filterNot { it == removedUrl })
            } else {
                graph.addons.setGlobalAddons(urls.filterNot { it == removedUrl })
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

    fun updateAutoplay(enabled: Boolean) {
        launchMutation("autoplay", "Could not save the autoplay preference. Try again.") {
            graph.settings.update { it.withAutoplayNextEpisode(enabled) }
        }
    }

    val uiState = SettingsUiState(
        serverUrl = graph.client.baseUrl,
        addons = addonsState.value,
        account = accountState.value,
        settings = settingsState.value,
        accountFetching = accountState.isFetching,
        settingsFetching = settingsState.isFetching,
        addonsError = addonsState.error != null,
        accountError = accountState.error != null,
        settingsError = settingsState.error != null,
        pendingAction = pendingAction,
        mutationError = mutationError,
    )

    Box(modifier.fillMaxSize().background(HaloColors.Background)) {
        SettingsContent(
            state = uiState,
            userAddonUrl = userAddonUrl,
            globalAddonUrl = globalAddonUrl,
            onUserAddonUrlChange = { userAddonUrl = it },
            onGlobalAddonUrlChange = { globalAddonUrl = it },
            onAddAddon = ::addAddon,
            onToggleCatalogs = ::toggleCatalogs,
            onRemoveAddon = { entry, ownership -> overlay = SettingsOverlay.Remove(entry, ownership) },
            onOpenLanguage = { overlay = SettingsOverlay.Language(it) },
            onAutoplayChange = ::updateAutoplay,
            onSignOut = onSignOut,
            onOpenDebugGate = onOpenDebugGate,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        when (val activeOverlay = overlay) {
            is SettingsOverlay.Language -> {
                val settings = settingsState.value ?: UserSettings.Empty
                val selected = if (activeOverlay.kind == SettingsLanguageKind.Audio) {
                    settings.preferredAudioLang
                } else {
                    settings.preferredSubtitleLang
                }
                SelectSheet(
                    visible = true,
                    title = if (activeOverlay.kind == SettingsLanguageKind.Audio) {
                        "Default audio language"
                    } else {
                        "Default subtitles"
                    },
                    options = listOf(
                        SelectOption(
                            key = NoLanguage,
                            label = if (activeOverlay.kind == SettingsLanguageKind.Audio) {
                                "Auto (first track)"
                            } else {
                                "Off"
                            },
                            selected = selected == null,
                        ),
                    ) + LanguageOptions.map { language ->
                        SelectOption(
                            key = language.code,
                            label = language.label,
                            selected = selected == language.code,
                        )
                    },
                    onSelect = { key -> updateLanguage(activeOverlay.kind, key.takeUnless { it == NoLanguage }) },
                    onClose = { overlay = null },
                )
            }
            is SettingsOverlay.Remove -> SelectSheet(
                visible = true,
                title = "Remove addon?",
                description = activeOverlay.entry.manifest.name,
                options = listOf(
                    SelectOption(
                        key = RemoveAddon,
                        label = "Remove addon",
                        detail = "This removes it from ${if (activeOverlay.ownership == AddonOwnership.Global) "every user" else "your account"}.",
                        destructive = true,
                    ),
                ),
                onSelect = { removeAddon(activeOverlay.entry, activeOverlay.ownership) },
                onClose = { overlay = null },
            )
            null -> Unit
        }
    }
}

internal fun normalizedAddonUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val schemeSeparator = trimmed.indexOf("://")
    if (schemeSeparator <= 0) return null
    val scheme = trimmed.substring(0, schemeSeparator).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val parsed = try {
        Url(trimmed)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (parsed.protocol != URLProtocol.HTTP && parsed.protocol != URLProtocol.HTTPS) return null
    if (parsed.host.isBlank()) return null
    return parsed.toString()
}
