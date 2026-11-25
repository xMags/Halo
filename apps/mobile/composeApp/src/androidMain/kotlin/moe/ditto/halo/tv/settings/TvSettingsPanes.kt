package moe.ditto.halo.tv.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.device.DefaultAddonUrls
import moe.ditto.halo.people.TmdbKeyStatus
import moe.ditto.halo.screens.AccountAvatar
import moe.ditto.halo.screens.AddonOwnership
import moe.ditto.halo.screens.SettingsLanguageKind
import moe.ditto.halo.screens.SettingsSession
import moe.ditto.halo.screens.SettingsUiState
import moe.ditto.halo.screens.TmdbGetKeyUrl
import moe.ditto.halo.screens.TmdbRefusedLaterText
import moe.ditto.halo.screens.TmdbWorkingText
import moe.ditto.halo.screens.accountDisplayName
import moe.ditto.halo.screens.addonsNotMovedMessage
import moe.ditto.halo.screens.tmdbProblemOf
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvChoiceRow
import moe.ditto.halo.tv.kit.TvHaloMark
import moe.ditto.halo.tv.kit.TvRowSurface
import moe.ditto.halo.tv.kit.TvSwitchRow
import moe.ditto.halo.tv.kit.TvTextField
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.tv.player.SurroundSound
import moe.ditto.halo.tv.player.TvPlaybackPreferences
import moe.ditto.halo.tv.signin.TvQrCode
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.languageLabel

/** A pane's name, and a line on what it holds. */
@Composable
private fun PaneTitle(text: String, description: String? = null) {
    Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = TvType.Title)
        if (description != null) Text(description, style = TvType.Meta)
    }
}

/** A small label over a group of rows within a pane. */
@Composable
private fun GroupLabel(text: String) {
    Text(text.uppercase(), style = TvType.Overline, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
}

/** A rounded panel that holds a pane's summary: who is signed in, what this app is. */
@Composable
private fun SummaryCard(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(HaloColors.Surface, RoundedCornerShape(18.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}

/** A coloured dot and a short state, such as "Connected · 58 ms". */
@Composable
private fun StatusLine(text: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(if (ok) HaloColors.Success else HaloColors.TextDim, CircleShape))
        Text(text, style = TvType.Meta.copy(color = if (ok) HaloColors.Text else HaloColors.TextDim))
    }
}

internal fun LazyListScope.accountPane(
    graph: SessionGraph,
    state: SettingsUiState,
    onSignOut: () -> Unit,
    onSignIn: () -> Unit,
) {
    item(key = "account-title") { PaneTitle("Account", "Who this TV is signed in as.") }
    if (!state.signedIn) {
        item(key = "account-device") {
            Text(
                "Your library, history and addons are kept on this TV. Sign in to sync them with your other screens.",
                style = TvType.Body,
            )
        }
        item(key = "account-sign-in") { TvButton("Sign in", onClick = onSignIn, style = TvButtonStyle.Primary) }
        item(key = "tmdb") { TmdbKeyPane(graph) }
        return
    }
    val account = state.account
    item(key = "account-card") {
        val connected = state.account != null || state.addons != null || state.settings != null
        SummaryCard {
            AccountAvatar(graph, onClick = null, size = 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(account?.let { accountDisplayName(it.username) } ?: "…", style = TvType.Title, maxLines = 1)
                Text("Signed in as ${account?.username ?: "…"}", style = TvType.Meta, maxLines = 1)
                StatusLine(
                    text = if (connected) listOfNotNull("Connected", state.latencyMillis?.let { "$it ms" }).joinToString(" · ") else "Connecting…",
                    ok = connected,
                )
            }
        }
    }
    item(key = "account-sign-out") {
        TvButton("Sign out", onClick = onSignOut, style = TvButtonStyle.Danger, modifier = Modifier.padding(top = 12.dp))
    }
}

/**
 * Without an account: people search with the viewer's own TMDB key, typed
 * with the remote. A TV has no browser, so the page to get a key is a QR code
 * for the phone.
 */
@Composable
private fun TmdbKeyPane(graph: SessionGraph) {
    val scope = rememberCoroutineScope()
    val status by graph.tmdbKeys.status.collectAsState()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val shown = message ?: if (status == TmdbKeyStatus.Refused) false to TmdbRefusedLaterText else null

    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("People search", style = TvType.Title)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Find actors, directors and writers with your own free TMDB key. Scan the code to get one on your phone.",
                style = TvType.Body,
                modifier = Modifier.weight(1f),
            )
            TvQrCode(TmdbGetKeyUrl, size = 140.dp)
        }
        if (status == TmdbKeyStatus.Working) {
            Text(TmdbWorkingText, style = TvType.Body.copy(color = HaloColors.Success))
            TvButton(
                "Remove key",
                style = TvButtonStyle.Danger,
                onClick = {
                    message = null
                    runCatching { graph.tmdbKeys.remove() }.onFailure { message = false to "Halo couldn't remove the key from this TV." }
                },
            )
        } else {
            TvTextField(
                value = text,
                onValueChange = { text = it },
                label = "TMDB key",
                placeholder = "API key or read access token",
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
                onSubmit = {
                    if (busy || text.isBlank()) return@TvTextField
                    busy = true
                    scope.launch {
                        val problem = tmdbProblemOf(graph.saveTmdbKey(text))
                        busy = false
                        if (problem == null) {
                            text = ""
                            message = true to TmdbWorkingText
                        } else {
                            message = false to problem
                        }
                    }
                },
            )
        }
        shown?.let { (ok, line) -> Text(line, style = TvType.Body.copy(color = if (ok) HaloColors.Success else HaloColors.Danger)) }
        Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", style = TvType.Caption)
    }
}

internal fun LazyListScope.addonsPane(
    state: SettingsUiState,
    session: SettingsSession,
    onOpenAddon: (AddonEntry, AddonOwnership, Boolean) -> Unit,
) {
    item(key = "addons-title") { PaneTitle("Addons", "Where Halo finds titles, streams and subtitles.") }
    if (state.addonsNotMoved.isNotEmpty()) item(key = "not-moved") { ErrorLine(addonsNotMovedMessage(state.addonsNotMoved)) }
    val addons = state.addons
    if (addons == null) {
        item(key = "addons-state") {
            Text(if (state.addonsError) "Could not load your addons." else "Loading addons…", style = TvType.Body)
        }
        return
    }
    val canMutate = state.pendingAction == null
    item(key = "add-user") {
        AddAddonRow(
            label = "Add an addon",
            value = session.userAddonUrl,
            busy = state.pendingAction == "user-add",
            enabled = canMutate,
            onValueChange = { session.userAddonUrl = it },
            onAdd = { session.addAddon(AddonOwnership.User) },
        )
    }
    item(key = "user-label") { GroupLabel("Yours") }
    if (addons.user.isEmpty()) item(key = "no-user") { Text("No personal addons yet.", style = TvType.Body) }
    addons.user.forEach { addon ->
        item(key = "user/${addon.id}") {
            AddonRow(
                addon = addon,
                busy = state.pendingAction?.endsWith(addon.id) == true,
                // The built-in addons are what Halo needs to browse at all.
                onOpen = { onOpenAddon(addon, AddonOwnership.User, addon.transportUrl !in DefaultAddonUrls) },
            )
        }
    }
    val isAdmin = state.account?.isAdmin == true
    if (addons.global.isEmpty() && !isAdmin) return
    item(key = "global-title") { GroupLabel(if (isAdmin) "For everyone · admin" else "For everyone") }
    if (isAdmin) {
        item(key = "add-global") {
            AddAddonRow(
                label = "Add an addon for everyone",
                value = session.globalAddonUrl,
                busy = state.pendingAction == "global-add",
                enabled = canMutate,
                onValueChange = { session.globalAddonUrl = it },
                onAdd = { session.addAddon(AddonOwnership.Global) },
            )
        }
    }
    if (addons.global.isEmpty()) item(key = "no-global") { Text("No addons for everyone yet.", style = TvType.Body) }
    addons.global.forEach { addon ->
        item(key = "global/${addon.id}") {
            // Addons for everyone are managed on the server, never removed from an app.
            AddonRow(addon = addon, busy = state.pendingAction?.endsWith(addon.id) == true, onOpen = { onOpenAddon(addon, AddonOwnership.Global, false) })
        }
    }
}

@Composable
private fun AddAddonRow(
    label: String,
    value: String,
    busy: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        TvTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = "https://…/manifest.json",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            onSubmit = onAdd,
            modifier = Modifier.weight(1f),
        )
        TvButton(if (busy) "Adding…" else "Add", onClick = onAdd, enabled = enabled && value.isNotBlank())
    }
}

@Composable
private fun AddonRow(addon: AddonEntry, busy: Boolean, onOpen: () -> Unit) {
    TvRowSurface(
        title = addon.manifest.name,
        hint = when {
            busy -> "Saving…"
            addon.hideCatalogs -> "Catalogs hidden on Home"
            else -> "v${addon.manifest.version}"
        },
        onClick = onOpen,
        leading = { AddonLogo(addon.manifest.logo) },
    )
}

/** An addon's own logo, or a puzzle piece for one that has none or whose logo fails. */
@Composable
private fun AddonLogo(url: String?) {
    val glyph: @Composable BoxScope.() -> Unit = {
        Icon(HaloIcons.Extension, contentDescription = null, tint = HaloColors.TextDim, modifier = Modifier.align(Alignment.Center).size(20.dp))
    }
    HaloAsyncImage(
        url = url,
        contentDescription = null,
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Fit,
        failure = glyph,
    )
}

internal fun LazyListScope.homePane(state: SettingsUiState, choices: List<String>, onOpen: () -> Unit) {
    item(key = "home-title") { PaneTitle("Home", "What the rows on Home show.") }
    item(key = "language-rows") {
        TvChoiceRow(
            title = "Language rows",
            value = state.settings?.catalogLanguage?.takeIf { it in choices } ?: "Addon default",
            hint = "Rows such as TMDB's Language row show titles in this language.",
            onClick = onOpen,
        )
    }
}

internal fun LazyListScope.playbackPane(
    state: SettingsUiState,
    preferences: TvPlaybackPreferences,
    onOpenLanguage: (SettingsLanguageKind) -> Unit,
    onAutoplay: (Boolean) -> Unit,
    onOpenSurround: () -> Unit,
) {
    item(key = "playback-title") { PaneTitle("Playback", "How videos start, sound and look.") }
    val settings = state.settings
    item(key = "everywhere") { GroupLabel("On all your screens") }
    item(key = "audio-language") {
        TvChoiceRow(
            title = "Default audio language",
            value = settings?.preferredAudioLang?.let(::languageLabel) ?: "Auto (first track)",
            onClick = { onOpenLanguage(SettingsLanguageKind.Audio) },
        )
    }
    item(key = "subtitle-language") {
        TvChoiceRow(
            title = "Default subtitles",
            value = settings?.preferredSubtitleLang?.let(::languageLabel) ?: "Off",
            onClick = { onOpenLanguage(SettingsLanguageKind.Subtitles) },
        )
    }
    item(key = "autoplay") {
        TvSwitchRow(
            title = "Play the next episode automatically",
            checked = settings?.autoplayNextEpisode ?: true,
            onCheckedChange = onAutoplay,
        )
    }
    item(key = "tv-only") { GroupLabel("On this TV only") }
    item(key = "frame-rate") {
        TvSwitchRow(
            title = "Match frame rate",
            hint = "Switch the TV to the video's own rate for smooth motion.",
            checked = preferences.matchFrameRate,
            onCheckedChange = preferences::updateMatchFrameRate,
        )
    }
    item(key = "surround") {
        TvChoiceRow(
            title = "Surround sound",
            value = if (preferences.surroundSound == SurroundSound.Auto) "Auto" else "Off",
            hint = "Auto sends Dolby and DTS to a receiver that plays them.",
            onClick = onOpenSurround,
        )
    }
}

internal fun LazyListScope.aboutPane() {
    item(key = "about-title") { PaneTitle("About") }
    item(key = "about-card") {
        val context = LocalContext.current
        SummaryCard {
            TvHaloMark(56.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Halo", style = TvType.Title)
                Text("Version ${versionOf(context)}", style = TvType.Meta)
            }
        }
    }
    item(key = "about") {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("© 2026 Last Projects & Shy", style = TvType.Body)
            Text("GPL-3.0 license", style = TvType.Body)
            Text("Playback by mpv (libmpv), under the GPL.", style = TvType.Caption, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun versionOf(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "Unknown"
