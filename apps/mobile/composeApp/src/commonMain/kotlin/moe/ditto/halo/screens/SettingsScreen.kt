package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.rememberHazeState
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.LanguageOptions
import moe.ditto.halo.ui.LocalHazeState
import moe.ditto.halo.ui.SelectOption
import moe.ditto.halo.ui.SelectSheet
import moe.ditto.halo.ui.glassSource
import moe.ditto.halo.ui.rememberResponsive

private const val NoLanguage = "none"
private const val RemoveAddon = "remove"

private sealed interface SettingsOverlay {
    data class Language(val kind: SettingsLanguageKind) : SettingsOverlay
    data class CatalogLanguage(val choices: List<String>) : SettingsOverlay
    data class Remove(val entry: AddonEntry, val ownership: AddonOwnership) : SettingsOverlay
}
/** The original app's settings surface, backed by the session graph. */
@Composable
internal fun SettingsScreen(
    graph: SessionGraph,
    onSignOut: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDebugGate: (() -> Unit)? = null,
) {
    val (session, uiState) = rememberSettingsSession(graph)
    var overlay by remember { mutableStateOf<SettingsOverlay?>(null) }

    // The sheets frost this screen, so the blur source is the screen's own
    // body: a sheet inside the shell's navigation-host source samples nothing
    // and comes out clear. See `LocalHazeState`.
    val bodyHaze = rememberHazeState()
    val responsive = rememberResponsive()

    CompositionLocalProvider(LocalHazeState provides bodyHaze) {
        Box(modifier.fillMaxSize().background(HaloColors.Background)) {
            Box(Modifier.fillMaxSize().glassSource(bodyHaze), contentAlignment = Alignment.TopCenter) {
                SettingsContent(
                    state = uiState,
                    userAddonUrl = session.userAddonUrl,
                    globalAddonUrl = session.globalAddonUrl,
                    onUserAddonUrlChange = { session.userAddonUrl = it },
                    onGlobalAddonUrlChange = { session.globalAddonUrl = it },
                    onAddAddon = session::addAddon,
                    onToggleCatalogs = session::toggleCatalogs,
                    onRemoveAddon = { entry, ownership -> overlay = SettingsOverlay.Remove(entry, ownership) },
                    onOpenLanguage = { overlay = SettingsOverlay.Language(it) },
                    onOpenCatalogLanguage = { overlay = SettingsOverlay.CatalogLanguage(it) },
                    onAutoplayChange = session::updateAutoplay,
                    onSignOut = onSignOut,
                    onSignIn = onSignIn,
                    accountAvatar = { size -> AccountAvatar(graph, onClick = null, size = size) },
                    tmdbKeyCard = if (graph.signedIn) null else { { TmdbKeyCard(graph) } },
                    onOpenDebugGate = onOpenDebugGate,
                )
            }

            when (val activeOverlay = overlay) {
                is SettingsOverlay.Language -> {
                    val settings = uiState.settings ?: UserSettings.Empty
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
                        onSelect = { key -> session.updateLanguage(activeOverlay.kind, key.takeUnless { it == NoLanguage }) },
                        onClose = { overlay = null },
                    bottomClearance = responsive.floatingBarClearance,
                    )
                }
                is SettingsOverlay.CatalogLanguage -> {
                    val selected = uiState.settings?.catalogLanguage?.takeIf { it in activeOverlay.choices }
                    SelectSheet(
                        visible = true,
                        title = "Language rows on Home",
                        description = "Rows such as TMDB's Language row show titles in this language.",
                        options = listOf(
                            SelectOption(key = NoLanguage, label = "Addon default", selected = selected == null),
                        ) + activeOverlay.choices.map { language ->
                            SelectOption(key = language, label = language, selected = selected == language)
                        },
                        onSelect = { key -> session.updateCatalogLanguage(key.takeUnless { it == NoLanguage }) },
                        onClose = { overlay = null },
                        bottomClearance = responsive.floatingBarClearance,
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
                    onSelect = { session.removeAddon(activeOverlay.entry, activeOverlay.ownership) },
                    onClose = { overlay = null },
                    bottomClearance = responsive.floatingBarClearance,
                )
                null -> Unit
            }
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
