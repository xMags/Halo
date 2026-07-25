package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ditto.halo.NativePlayerSurface
import moe.ditto.halo.PlaybackHost
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType

/**
 * Playback, with nothing around it yet: the native surface, a way back, and
 * enough state to tell a stalled load from a failed one.
 *
 * Controls, gestures, subtitle styling, watch-state reporting and autoplay are
 * all still to come; what this screen establishes is that a source chosen in
 * the picker reaches the engine and paints. Anything richer belongs with the
 * real player rather than here, where it would be written twice.
 */
// BackHandler is still marked experimental in Compose 1.11; the opt-in is
// scoped to this screen rather than turned on for the whole module, so a future
// signature change surfaces here and nowhere else.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerScreen(
    playback: PlaybackHost,
    surface: NativePlayerSurface,
    url: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by playback.state.collectAsState()
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        playback.play(MediaItem(id = url, title = title, url = url))
    }

    /**
     * Leaving is asynchronous on purpose, and every way out goes through here.
     *
     * The engine is wound down first and the navigation happens only once that
     * has finished — the screen is still on screen while it runs, which is the
     * one moment its render surface is guaranteed to still exist. Doing this
     * from a disposal callback instead cannot work: the surface is torn down
     * before those run.
     *
     * Playback is paused rather than torn down. Teardown is terminal for the
     * presenter, and on iOS it shuts libmpv down for the rest of the process,
     * so a screen that tore down on the way out would play exactly once per
     * launch.
     */
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                playback.windDownForExit()
                onBack()
            }
        }
    }

    // The system gesture and button take the same path as the button drawn
    // here; a back that skipped the wind-down would hang the app just as
    // reliably as no wind-down at all.
    BackHandler(enabled = !leaving, onBack = leave)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        // The engine paints the whole box; everything else sits on top of it.
        surface.Content(Modifier.fillMaxSize())

        when (state.status) {
            PlaybackStatus.Loading -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = HaloColors.Accent)
                Text(
                    text = title,
                    style = HaloType.Caption,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = HaloSpacing.Md, start = HaloSpacing.Lg, end = HaloSpacing.Lg),
                )
            }
            // A dead source is otherwise indistinguishable from one that is
            // simply slow, and both look like a black rectangle.
            PlaybackStatus.Failed -> Text(
                text = state.error ?: "This source could not be played.",
                style = HaloType.Body.copy(color = HaloColors.Danger),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = HaloSpacing.Xl),
            )
            else -> Unit
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(HaloSpacing.Md)
                .size(34.dp)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(role = Role.Button, onClick = leave),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HaloIcons.ChevronLeft,
                contentDescription = "Back",
                tint = HaloColors.Text,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
