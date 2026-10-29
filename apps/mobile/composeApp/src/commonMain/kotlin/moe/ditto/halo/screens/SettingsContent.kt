package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.AddonsResponse
import moe.ditto.halo.api.Me
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.browse.homeLanguageChoices
import moe.ditto.halo.device.DefaultAddonUrls
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.languageLabel
import moe.ditto.halo.ui.rememberResponsive

internal enum class SettingsLanguageKind {
    Audio,
    Subtitles,
}

internal enum class AddonOwnership {
    User,
    Global,
}

internal data class SettingsUiState(
    /** False for Halo without an account: no server, nothing to sign out of. */
    val signedIn: Boolean = true,
    /** Addons the account refused when the phone's data moved into it on sign-in. */
    val addonsNotMoved: List<String> = emptyList(),
    /** The last measured round trip to the server; null until one has completed. */
    val latencyMillis: Long? = null,
    val addons: AddonsResponse? = null,
    val account: Me? = null,
    val settings: UserSettings? = null,
    val accountFetching: Boolean = false,
    val settingsFetching: Boolean = false,
    val addonsError: Boolean = false,
    val accountError: Boolean = false,
    val settingsError: Boolean = false,
    val pendingAction: String? = null,
    val mutationError: String? = null,
)

@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    userAddonUrl: String,
    globalAddonUrl: String,
    onUserAddonUrlChange: (String) -> Unit,
    onGlobalAddonUrlChange: (String) -> Unit,
    onAddAddon: (AddonOwnership) -> Unit,
    onToggleCatalogs: (AddonEntry, AddonOwnership) -> Unit,
    onRemoveAddon: (AddonEntry, AddonOwnership) -> Unit,
    onOpenLanguage: (SettingsLanguageKind) -> Unit,
    /** Opens the language picker for Home's language rows, with the languages they offer. */
    onOpenCatalogLanguage: (List<String>) -> Unit,
    onAutoplayChange: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    onSignIn: () -> Unit,
    onOpenDebugGate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
    val preferences = state.settings ?: UserSettings.Empty
    val canMutate = state.pendingAction == null

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            // Inside the content area rather than the window: the navigation
            // rail moves the optical centre, and a form centred on the window
            // reads as pushed to one side.
            .padding(start = responsive.contentInsetStart)
            .then(
                responsive.contentMaxWidth?.let { Modifier.widthIn(max = it).fillMaxWidth() }
                    ?: Modifier.fillMaxWidth(),
            ),
        contentPadding = PaddingValues(
            start = HaloSpacing.Md,
            end = HaloSpacing.Md,
            bottom = responsive.bottomContentPadding,
        ),
    ) {
        item(key = "header") { ScreenHeader(title = "Settings") }

        state.mutationError?.let { message ->
            item(key = "mutation-error") { ErrorBanner(message) }
        }
        if (state.addonsNotMoved.isNotEmpty()) {
            item(key = "addons-not-moved") { ErrorBanner(addonsNotMovedMessage(state.addonsNotMoved)) }
        }

        item(key = "addons-label") { SettingsGroupLabel("Addons") }
        item(key = "addons-card") {
            when {
                state.addons == null && state.addonsError -> SettingsStateCard("Could not load your addons.")
                state.addons == null -> SettingsLoadingCard("Loading addons")
                else -> SettingsCard {
                    AddonUrlRow(
                        value = userAddonUrl,
                        label = "Personal addon URL",
                        enabled = canMutate,
                        busy = state.pendingAction == "user-add",
                        onValueChange = onUserAddonUrlChange,
                        onSubmit = { onAddAddon(AddonOwnership.User) },
                    )
                    if (state.addons.user.isNotEmpty()) SettingsDivider()
                    if (state.addons.user.isEmpty()) {
                        EmptySettingsRow("No personal addons yet.")
                    } else {
                        state.addons.user.forEachIndexed { index, addon ->
                            AddonRow(
                                addon = addon,
                                ownership = AddonOwnership.User,
                                enabled = canMutate,
                                busy = state.pendingAction?.endsWith(addon.id) == true,
                                onToggleCatalogs = onToggleCatalogs,
                                onRemove = onRemoveAddon,
                                // The built-in addons are what Halo needs to browse
                                // at all, so they have no delete button, signed in
                                // or not, as on the desktop app.
                                removable = addon.transportUrl !in DefaultAddonUrls,
                            )
                            if (index != state.addons.user.lastIndex) SettingsDivider()
                        }
                    }
                }
            }
        }

        val globalAddons = state.addons?.global.orEmpty()
        if (state.account?.isAdmin == true || globalAddons.isNotEmpty()) {
            item(key = "global-label") {
                SettingsGroupLabel(if (state.account?.isAdmin == true) "Global, admin" else "Global")
            }
            item(key = "global-card") {
                SettingsCard {
                    if (state.account?.isAdmin == true) {
                        AddonUrlRow(
                            value = globalAddonUrl,
                            label = "Global addon URL",
                            enabled = canMutate,
                            busy = state.pendingAction == "global-add",
                            onValueChange = onGlobalAddonUrlChange,
                            onSubmit = { onAddAddon(AddonOwnership.Global) },
                        )
                        SettingsDivider()
                    }
                    if (globalAddons.isEmpty()) {
                        EmptySettingsRow("No global addons yet.")
                    } else {
                        globalAddons.forEachIndexed { index, addon ->
                            AddonRow(
                                addon = addon,
                                ownership = AddonOwnership.Global,
                                enabled = canMutate && state.account?.isAdmin == true,
                                busy = state.pendingAction?.endsWith(addon.id) == true,
                                onToggleCatalogs = onToggleCatalogs,
                                onRemove = onRemoveAddon,
                                // Global addons are managed on the server, never
                                // removed from an app, admin or not.
                                removable = false,
                            )
                            if (index != globalAddons.lastIndex) SettingsDivider()
                        }
                    }
                }
            }
        }

        // Only when an installed addon has a language row (TMDB's "Language"
        // row); with none, there is nothing for the setting to change.
        val languageChoices = state.addons?.let { homeLanguageChoices(it.user + it.global) }
        if (languageChoices != null) {
            item(key = "home-label") { SettingsGroupLabel("Home") }
            item(key = "home-card") {
                SettingsCard {
                    SettingValueRow(
                        label = "Language rows",
                        value = preferences.catalogLanguage?.takeIf { it in languageChoices } ?: "Addon default",
                        enabled = state.settings != null && canMutate,
                        onClick = { onOpenCatalogLanguage(languageChoices) },
                    )
                }
                Text(
                    text = "Rows such as TMDB's Language row show titles in this language.",
                    style = HaloType.Caption,
                    modifier = Modifier.padding(top = HaloSpacing.Sm, start = HaloSpacing.Xs),
                )
            }
        }

        if (state.account == null && (state.accountFetching || state.accountError)) {
            item(key = "account-state") {
                val text = if (state.accountError) {
                    "Admin controls are unavailable because account details could not be loaded."
                } else {
                    "Checking account permissions..."
                }
                Text(
                    text = text,
                    style = HaloType.Caption,
                    modifier = Modifier.padding(top = HaloSpacing.Sm, start = HaloSpacing.Xs),
                )
            }
        }

        item(key = "playback-label") { SettingsGroupLabel("Playback") }
        item(key = "playback-card") {
            SettingsCard {
                SettingValueRow(
                    label = "Default audio language",
                    value = preferences.preferredAudioLang?.let(::languageLabel) ?: "Auto",
                    enabled = state.settings != null && canMutate,
                    onClick = { onOpenLanguage(SettingsLanguageKind.Audio) },
                )
                SettingsDivider()
                SettingValueRow(
                    label = "Default subtitles",
                    value = preferences.preferredSubtitleLang?.let(::languageLabel) ?: "Off",
                    enabled = state.settings != null && canMutate,
                    onClick = { onOpenLanguage(SettingsLanguageKind.Subtitles) },
                )
                SettingsDivider()
                AutoplayRow(
                    enabled = preferences.autoplayNextEpisode ?: true,
                    interactive = state.settings != null && canMutate,
                    busy = state.pendingAction == "autoplay",
                    onChange = onAutoplayChange,
                )
            }
        }
        if (state.settings == null && (state.settingsFetching || state.settingsError)) {
            item(key = "settings-state") {
                Text(
                    text = if (state.settingsError) {
                        "Playback preferences could not be loaded."
                    } else {
                        "Loading playback preferences..."
                    },
                    style = HaloType.Caption,
                    modifier = Modifier.padding(top = HaloSpacing.Sm, start = HaloSpacing.Xs),
                )
            }
        }

        if (!state.signedIn) {
            item(key = "account-label") { SettingsGroupLabel("Account") }
            item(key = "account-card") {
                SettingsCard {
                    Text(
                        text = "Your library, history and addons are kept on this phone. Sign in to sync them.",
                        style = HaloType.Body.copy(color = HaloColors.TextDim),
                        modifier = Modifier.padding(horizontal = HaloSpacing.Md, vertical = 13.dp),
                    )
                    SettingsDivider()
                    ActionSettingRow(label = "Sign in", onClick = onSignIn)
                }
            }
        }

        if (state.signedIn) item(key = "server-label") { SettingsGroupLabel("Server") }
        if (state.signedIn) item(key = "server-card") {
            val connected = state.account != null || state.addons != null || state.settings != null
            SettingsCard {
                StaticSettingRow(label = "Latency", value = state.latencyMillis?.let { "$it ms" } ?: "...")
                SettingsDivider()
                StatusRow(connected = connected)
            }
        }

        if (onOpenDebugGate != null) {
            item(key = "developer-label") { SettingsGroupLabel("Developer") }
            item(key = "developer-card") {
                SettingsCard {
                    ActionSettingRow(label = "Debug gate", onClick = onOpenDebugGate)
                }
            }
        }

        if (state.signedIn) item(key = "sign-out") {
            Box(
                modifier = Modifier
                    .padding(top = HaloSpacing.Lg)
                    .fillMaxWidth()
                    .clip(SettingsCardShape)
                    .background(HaloColors.Glass)
                    .border(1.dp, HaloColors.GlassBorder, SettingsCardShape)
                    .clickable(role = Role.Button, onClick = onSignOut)
                    .padding(vertical = HaloSpacing.Md),
                contentAlignment = Alignment.Center,
            ) {
                Text("Sign Out", color = HaloColors.Danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun addonsNotMovedMessage(names: List<String>): String {
    val one = names.size == 1
    return "${names.joinToString(", ")} could not be added to your account because " +
        "${if (one) "it" else "they"} did not load. You can add ${if (one) "it" else "them"} again above."
}
