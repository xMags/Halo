package moe.ditto.halo.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.browse.homeLanguageChoices
import moe.ditto.halo.screens.AddonOwnership
import moe.ditto.halo.screens.SettingsLanguageKind
import moe.ditto.halo.screens.rememberSettingsSession
import moe.ditto.halo.tv.kit.TvConfirmDialog
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvRowSurface
import moe.ditto.halo.tv.kit.TvSidePanel
import moe.ditto.halo.tv.kit.TvSurfaceColors
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.tv.kit.defaultFocus
import moe.ditto.halo.tv.kit.rememberFocus
import moe.ditto.halo.tv.kit.rememberTvFocusMemory
import moe.ditto.halo.tv.player.SurroundSound
import moe.ditto.halo.tv.player.TvPlaybackPreferences
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.LanguageOptions

/** The parts of Settings, listed down the left. */
internal enum class TvSettingsSection(val label: String, val icon: ImageVector) {
    Account("Account", HaloIcons.Person),
    Addons("Addons", HaloIcons.Extension),
    Home("Home", HaloIcons.Home),
    Playback("Playback", HaloIcons.Play),
    About("About", HaloIcons.Info),
}

/** A section's name rests bare; the open one keeps a quiet fill while focus is in its pane. */
private val SectionColors = TvSurfaceColors(rest = Color.Transparent, focused = HaloColors.SurfaceHigh)
private val OpenSectionColors = TvSurfaceColors(rest = HaloColors.Surface, focused = HaloColors.SurfaceHigh)

/** What a choice list in a side panel is choosing. */
private sealed interface TvSettingsPanel {
    data class Language(val kind: SettingsLanguageKind) : TvSettingsPanel
    data class CatalogLanguage(val choices: List<String>) : TvSettingsPanel
    data object Surround : TvSettingsPanel
    data class Addon(val entry: AddonEntry, val ownership: AddonOwnership, val removable: Boolean) : TvSettingsPanel
}

/**
 * Settings on a TV: the sections down the left, the focused one's rows on
 * the right. The rules (what can be added, removed or changed, and how a
 * failed change is reported) are the phone's own, through the shared
 * settings session; only the remote's way of working them differs. Choices
 * open in a side panel; signing out and removing an addon ask first.
 */
@Composable
internal fun TvSettingsScreen(
    graph: SessionGraph,
    preferences: TvPlaybackPreferences,
    onSignOut: () -> Unit,
    onSignIn: () -> Unit,
) {
    val (session, state) = rememberSettingsSession(graph)
    val memory = rememberTvFocusMemory()
    var section by rememberSaveable { mutableStateOf(TvSettingsSection.Account) }
    var panel by remember { mutableStateOf<TvSettingsPanel?>(null) }
    var askSignOut by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf<TvSettingsPanel.Addon?>(null) }
    val languageChoices = state.addons?.let { homeLanguageChoices(it.user + it.global) }
    val sections = TvSettingsSection.entries.filter { it != TvSettingsSection.Home || languageChoices != null }
    val sectionFocus = remember { TvSettingsSection.entries.associateWith { FocusRequester() } }

    Box(Modifier.fillMaxSize().background(HaloColors.Background)) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = TvMetrics.ContentStart, end = TvMetrics.SideMargin, top = TvMetrics.VerticalMargin + 8.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(
                Modifier
                    .width(240.dp)
                    .fillMaxHeight()
                    // Left from a pane returns to that pane's section, not to
                    // whichever name is level with the row: the section follows
                    // focus, so landing elsewhere would swap the pane.
                    .focusRestorer(sectionFocus.getValue(section))
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Settings", style = TvType.Hero, modifier = Modifier.padding(bottom = 16.dp))
                sections.forEachIndexed { index, item ->
                    val focusKey = "section/${item.name}"
                    val open = item == section
                    TvRowSurface(
                        title = item.label,
                        onClick = { section = item },
                        titleColor = if (open) HaloColors.Text else HaloColors.TextDim,
                        colors = if (open) OpenSectionColors else SectionColors,
                        leading = { focused ->
                            Icon(
                                item.icon,
                                contentDescription = null,
                                tint = if (open || focused) HaloColors.AccentLight else HaloColors.TextDim,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        // The section follows focus, so the pane is always the
                        // one beside the highlighted name.
                        modifier = Modifier
                            .focusRequester(sectionFocus.getValue(item))
                            .onFocusChanged { if (it.hasFocus) section = item }
                            .let { if (index == 0) it.defaultFocus(memory, focusKey) else it.rememberFocus(memory, focusKey) },
                    )
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 72.dp, bottom = 40.dp),
            ) {
                state.mutationError?.let { error -> item(key = "error") { ErrorLine(error) } }
                when (section) {
                    TvSettingsSection.Account -> accountPane(
                        graph = graph,
                        state = state,
                        onSignOut = { askSignOut = true },
                        onSignIn = onSignIn,
                    )
                    TvSettingsSection.Addons -> addonsPane(
                        state = state,
                        session = session,
                        onOpenAddon = { entry, ownership, removable -> panel = TvSettingsPanel.Addon(entry, ownership, removable) },
                    )
                    TvSettingsSection.Home -> homePane(
                        state = state,
                        choices = languageChoices.orEmpty(),
                        onOpen = { panel = TvSettingsPanel.CatalogLanguage(languageChoices.orEmpty()) },
                    )
                    TvSettingsSection.Playback -> playbackPane(
                        state = state,
                        preferences = preferences,
                        onOpenLanguage = { panel = TvSettingsPanel.Language(it) },
                        onAutoplay = session::updateAutoplay,
                        onOpenSurround = { panel = TvSettingsPanel.Surround },
                    )
                    TvSettingsSection.About -> aboutPane()
                }
            }
        }

        when (val open = panel) {
            is TvSettingsPanel.Language -> {
                val settings = state.settings ?: UserSettings.Empty
                val selected = if (open.kind == SettingsLanguageKind.Audio) settings.preferredAudioLang else settings.preferredSubtitleLang
                ChoicePanel(
                    title = if (open.kind == SettingsLanguageKind.Audio) "Default audio language" else "Default subtitles",
                    choices = listOf(null to if (open.kind == SettingsLanguageKind.Audio) "Auto (first track)" else "Off") +
                        LanguageOptions.map { it.code to it.label },
                    selected = selected,
                    onSelect = { code ->
                        panel = null
                        session.updateLanguage(open.kind, code)
                    },
                    onDismiss = { panel = null },
                )
            }
            is TvSettingsPanel.CatalogLanguage -> ChoicePanel(
                title = "Language rows on Home",
                choices = listOf(null to "Addon default") + open.choices.map { it to it },
                selected = state.settings?.catalogLanguage?.takeIf { it in open.choices },
                onSelect = { language ->
                    panel = null
                    session.updateCatalogLanguage(language)
                },
                onDismiss = { panel = null },
            )
            TvSettingsPanel.Surround -> ChoicePanel(
                title = "Surround sound",
                choices = listOf(SurroundSound.Auto.name to "Auto: send it to the receiver when it can play it", SurroundSound.Off.name to "Off: decode on this TV"),
                selected = preferences.surroundSound.name,
                onSelect = { name ->
                    panel = null
                    SurroundSound.entries.firstOrNull { it.name == name }?.let(preferences::updateSurroundSound)
                },
                onDismiss = { panel = null },
            )
            is TvSettingsPanel.Addon -> AddonPanel(
                entry = open.entry,
                ownership = open.ownership,
                editable = open.ownership == AddonOwnership.User || state.account?.isAdmin == true,
                busy = state.pendingAction != null,
                removable = open.removable,
                position = state.addons?.let { addons ->
                    val list = if (open.ownership == AddonOwnership.User) addons.user else addons.global
                    list.indexOfFirst { it.id == open.entry.id }.takeIf { it >= 0 }?.let { it to list.size }
                },
                onMove = { earlier -> session.moveAddon(open.entry, open.ownership, earlier) },
                onToggleCatalogs = {
                    panel = null
                    session.toggleCatalogs(open.entry, open.ownership)
                },
                onRemove = {
                    panel = null
                    askRemove = open
                },
                onDismiss = { panel = null },
            )
            null -> Unit
        }

        if (askSignOut) {
            TvConfirmDialog(
                title = "Sign out of Halo?",
                message = "This TV forgets your account. Your library and history stay with it.",
                confirmLabel = "Sign out",
                destructive = true,
                onConfirm = {
                    askSignOut = false
                    onSignOut()
                },
                onDismiss = { askSignOut = false },
            )
        }
        askRemove?.let { removing ->
            TvConfirmDialog(
                title = "Remove ${removing.entry.manifest.name}?",
                message = "This removes it from your account on every device.",
                confirmLabel = "Remove",
                destructive = true,
                onConfirm = {
                    askRemove = null
                    session.removeAddon(removing.entry, removing.ownership)
                },
                onDismiss = { askRemove = null },
            )
        }
    }
}

/** A side panel of choices; [selected] is the current one's key, null for the first "none" choice. */
@Composable
private fun ChoicePanel(
    title: String,
    choices: List<Pair<String?, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Opens on the current choice, scrolled to it, so a long list is not a hunt.
    val selectedIndex = choices.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // After the panel's own first focus, which goes to its first row.
        withFrameNanos { }
        runCatching { selectedFocus.requestFocus() }
    }
    TvSidePanel(title = title, onDismiss = onDismiss, width = 460.dp) {
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(choices, key = { _, choice -> choice.first ?: "none" }) { index, (key, label) ->
                TvRowSurface(
                    title = label,
                    onClick = { onSelect(key) },
                    // The current choice is the row's state, read with its name.
                    modifier = (if (index == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier)
                        .semantics { if (key == selected) stateDescription = "Selected" },
                    trailing = { if (key == selected) Icon(HaloIcons.Check, contentDescription = null, tint = HaloColors.AccentLight) },
                )
            }
        }
    }
}

@Composable
private fun AddonPanel(
    entry: AddonEntry,
    ownership: AddonOwnership,
    /** False for an addon for everyone when the viewer is not an admin. */
    editable: Boolean,
    /** A change is saving; presses wait for it. */
    busy: Boolean,
    removable: Boolean,
    /** Where it stands in its list and how long that list is; null while unknown. */
    position: Pair<Int, Int>?,
    onMove: (earlier: Boolean) -> Unit,
    onToggleCatalogs: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    TvSidePanel(
        title = entry.manifest.name,
        subtitle = if (ownership == AddonOwnership.Global) "For everyone on this server" else "Yours",
        onDismiss = onDismiss,
    ) {
        if (!editable) {
            Text("Only an admin can change addons for everyone.", style = TvType.Body)
            return@TvSidePanel
        }
        // The rows stay while a change saves, so the one in focus keeps it;
        // presses then wait for the save instead of piling up.
        fun whenIdle(action: () -> Unit): () -> Unit = { if (!busy) action() }
        TvRowSurface(
            title = if (entry.hideCatalogs) "Show its catalogs on Home" else "Hide its catalogs on Home",
            onClick = whenIdle(onToggleCatalogs),
        )
        // Order is priority: earlier addons are asked first. The panel stays
        // open, so several presses walk an addon up or down the list. Both
        // rows always show (a row vanishing under focus would drop it); at an
        // end the row says so.
        position?.let { (index, size) ->
            if (size > 1) {
                TvRowSurface(
                    title = "Move up",
                    hint = if (index == 0) "Already asked first" else "Asked before the addon above it",
                    titleColor = if (index == 0) HaloColors.TextDim else HaloColors.Text,
                    onClick = whenIdle { if (index > 0) onMove(true) },
                )
                TvRowSurface(
                    title = "Move down",
                    hint = if (index == size - 1) "Already asked last" else "Asked after the addon below it",
                    titleColor = if (index == size - 1) HaloColors.TextDim else HaloColors.Text,
                    onClick = whenIdle { if (index < size - 1) onMove(false) },
                )
            }
            Text(
                if (busy) "Saving…" else "Position ${index + 1} of $size",
                style = TvType.Caption,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (removable) {
            TvRowSurface(title = "Remove addon", titleColor = HaloColors.Danger, onClick = whenIdle(onRemove))
        }
    }
}

@Composable
internal fun ErrorLine(text: String) {
    Text(
        text,
        style = TvType.Body.copy(color = HaloColors.Danger),
        modifier = Modifier
            .background(HaloColors.Danger.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
