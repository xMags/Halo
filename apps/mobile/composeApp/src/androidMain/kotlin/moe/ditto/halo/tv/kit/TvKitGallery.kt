package moe.ditto.halo.tv.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons

/**
 * Every TV kit component on one screen, for checking focus and looks on the
 * emulator. Debug builds only; the instrumented kit test drives it by keys.
 * The last action shows at the top, so a test can read what a key did.
 */
@Composable
internal fun TvKitGallery() {
    var lastAction by remember { mutableStateOf("Nothing yet") }
    var dialog by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(false) }
    var autoplay by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    val contentFocus = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstButton.requestFocus() } }

    Box(Modifier.fillMaxSize().background(HaloColors.Background)) {
        Column(
            Modifier
                .fillMaxSize()
                .focusRequester(contentFocus)
                .focusRestorer(firstButton)
                .verticalScroll(rememberScrollState())
                .padding(top = TvMetrics.VerticalMargin, bottom = TvMetrics.VerticalMargin),
            verticalArrangement = Arrangement.spacedBy(TvMetrics.RowGap),
        ) {
            Column(Modifier.padding(start = TvMetrics.ContentStart), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("TV kit", style = TvType.Title)
                Text(
                    lastAction,
                    style = TvType.Meta,
                    modifier = Modifier.semantics { contentDescription = "Last action" },
                )
            }
            Row(
                Modifier.padding(start = TvMetrics.ContentStart),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TvButton(
                    "Play",
                    onClick = { lastAction = "Clicked Play" },
                    icon = HaloIcons.Play,
                    style = TvButtonStyle.Primary,
                    modifier = Modifier.focusRequester(firstButton),
                )
                TvButton("Open dialog", onClick = { dialog = true })
                TvButton("Open panel", onClick = { panel = true })
                TvButton("Remove", onClick = { lastAction = "Clicked Remove" }, style = TvButtonStyle.Danger)
                TvIconButton(HaloIcons.Search, contentDescription = "Search", onClick = { lastAction = "Clicked Search" })
            }
            TvRow(title = "Posters") {
                items(12) { index ->
                    TvPosterCard(
                        title = "Poster ${index + 1}",
                        posterUrl = null,
                        subtitle = "202${index % 10}",
                        progress = if (index % 3 == 0) 0.4f else null,
                        onClick = { lastAction = "Clicked Poster ${index + 1}" },
                        onMenu = { lastAction = "Menu on Poster ${index + 1}" },
                    )
                }
            }
            TvRow(title = "Continue watching") {
                items(8) { index ->
                    TvWideCard(
                        title = "Wide ${index + 1}",
                        imageUrl = null,
                        line = "S1 E${index + 1}",
                        detail = "${10 + index} min left",
                        progress = 0.2f + index * 0.08f,
                        onClick = { lastAction = "Clicked Wide ${index + 1}" },
                        badge = if (index == 0) {
                            { Text("Watched", style = TvType.Caption.copy(color = HaloColors.Success)) }
                        } else {
                            null
                        },
                    )
                }
            }
            Column(
                Modifier.padding(start = TvMetrics.ContentStart, end = TvMetrics.SideMargin).width(560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvSwitchRow(
                    title = "Play the next episode",
                    hint = "Starts the next episode when one ends",
                    checked = autoplay,
                    onCheckedChange = {
                        autoplay = it
                        lastAction = "Autoplay ${if (it) "on" else "off"}"
                    },
                )
                TvChoiceRow(title = "Subtitles", value = "English", onClick = { lastAction = "Clicked Subtitles" })
                TvTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "Server address",
                    placeholder = "https://",
                    onSubmit = { lastAction = "Submitted $text" },
                )
            }
        }
        if (dialog) {
            TvConfirmDialog(
                title = "Remove this addon?",
                message = "Its catalogs and sources leave Halo.",
                confirmLabel = "Remove",
                destructive = true,
                onConfirm = {
                    lastAction = "Confirmed Remove"
                    dialog = false
                },
                onDismiss = {
                    lastAction = "Dismissed dialog"
                    dialog = false
                },
                returnFocusTo = contentFocus,
            )
        }
        if (panel) {
            TvSidePanel(
                title = "Subtitles",
                subtitle = "English (SDH)",
                onDismiss = {
                    lastAction = "Closed panel"
                    panel = false
                },
                returnFocusTo = contentFocus,
            ) {
                listOf("Off", "English", "English (SDH)", "French").forEach { option ->
                    TvChoiceRow(
                        title = option,
                        value = "",
                        onClick = {
                            lastAction = "Picked $option"
                            panel = false
                        },
                    )
                }
            }
        }
    }
}
