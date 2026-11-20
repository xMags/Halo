package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.people.TmdbKeySaveResult
import moe.ditto.halo.people.TmdbKeyStatus
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType

/*
 * Without an account, people search and IMDb/TVDB links ask TMDB directly
 * with the viewer's own free key. The pasted key is never shown back: once
 * saved, the card says so and offers to remove it. TMDB asks every app using
 * its API to credit it, as the line under the card does. The desktop has the
 * same card (`settings/TmdbKeyCard.tsx`).
 */

private const val GetKeyUrl = "https://www.themoviedb.org/settings/api"
private const val WorkingText = "Key works. People search and IMDb and TVDB links are on."
private const val RefusedLaterText = "TMDB no longer accepts the saved key. Paste a new one to turn people search back on."
private val InputShape = RoundedCornerShape(8.dp)

private fun problemOf(result: TmdbKeySaveResult): String? = when (result) {
    TmdbKeySaveResult.Saved -> null
    TmdbKeySaveResult.NotAKey -> "That isn't a TMDB API key or read access token. Copy one from your TMDB account's API page."
    TmdbKeySaveResult.Refused -> "TMDB refused this key. Check it and try again."
    TmdbKeySaveResult.Unreachable -> "Couldn't reach TMDB to check the key. Try again when you're online."
    TmdbKeySaveResult.NotStored -> "TMDB accepted the key, but Halo couldn't save it on this phone."
}

@Composable
internal fun TmdbKeyCard(graph: SessionGraph) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val status by graph.tmdbKeys.status.collectAsState()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val saved = status == TmdbKeyStatus.Working

    val save = save@{
        if (busy || text.isBlank()) return@save
        busy = true
        scope.launch {
            val result = graph.saveTmdbKey(text)
            busy = false
            val problem = problemOf(result)
            if (problem == null) {
                text = ""
                message = true to WorkingText
            } else {
                message = false to problem
            }
        }
        Unit
    }
    val remove = {
        message = null
        try {
            graph.tmdbKeys.remove()
        } catch (_: Exception) {
            message = false to "Halo couldn't remove the key from this phone."
        }
    }
    val shown = message ?: if (status == TmdbKeyStatus.Refused) false to RefusedLaterText else null

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = HaloSpacing.Md, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(HaloIcons.Person, contentDescription = null, tint = HaloColors.AccentLight, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("People search", style = HaloType.Body.copy(color = HaloColors.Text, fontWeight = FontWeight.SemiBold))
                    Text(
                        "Find actors, directors and writers, and open IMDb and TVDB links, with your own TMDB key.",
                        style = HaloType.Body.copy(color = HaloColors.TextDim, fontSize = 13.sp),
                    )
                    Text(
                        text = "Get a free key",
                        color = HaloColors.Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(role = Role.Button) { runCatching { uriHandler.openUri(GetKeyUrl) } }.padding(vertical = 2.dp),
                    )
                }
            }
            SettingsDivider()
            if (saved) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = HaloSpacing.Md, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
                ) {
                    Icon(HaloIcons.CheckCircle, contentDescription = null, tint = HaloColors.Success, modifier = Modifier.size(16.dp))
                    Text("Key saved", color = HaloColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    // Removing the key is destructive, so it is red.
                    Text(
                        text = "Remove",
                        color = HaloColors.Danger,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(InputShape)
                            .background(HaloColors.DangerFill)
                            .border(1.dp, HaloColors.DangerBorder, InputShape)
                            .clickable(role = Role.Button, onClick = remove)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(HaloSpacing.Sm + 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it.take(512)
                            message = null
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = HaloColors.Text, fontSize = 13.5.sp),
                        cursorBrush = SolidColor(HaloColors.Accent),
                        // Never shown back, and never suggested by the keyboard.
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier
                            .weight(1f)
                            .clip(InputShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .semantics { contentDescription = "TMDB API key or read access token" }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        decorationBox = { input ->
                            Box {
                                if (text.isEmpty()) Text("API key or read access token", color = HaloColors.TextDim, fontSize = 13.5.sp)
                                input()
                            }
                        },
                    )
                    val enabled = !busy && text.isNotBlank()
                    Box(
                        Modifier
                            .height(40.dp)
                            .width(64.dp)
                            .clip(InputShape)
                            .background(if (enabled) HaloColors.Accent else HaloColors.Accent.copy(alpha = 0.45f))
                            .clickable(enabled = enabled, role = Role.Button, onClick = save),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(color = HaloColors.OnAccent, strokeWidth = 2.dp, modifier = Modifier.size(17.dp))
                        } else {
                            Text("Save", color = HaloColors.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            SettingsDivider()
            Text(
                "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                color = HaloColors.TextFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = HaloSpacing.Md, vertical = 10.dp),
            )
        }
        shown?.let { (ok, line) ->
            Text(
                line,
                color = if (ok) HaloColors.Success else HaloColors.Danger,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = HaloSpacing.Xs),
            )
        }
    }
}
