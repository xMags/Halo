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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
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

    LaunchedEffect(url) {
        playback.play(MediaItem(id = url, title = title, url = url))
        try {
            awaitCancellation()
        } finally {
            // Pausing rather than tearing down: teardown is terminal for the
            // presenter, and on iOS it shuts libmpv down for the rest of the
            // process, so a screen that tore down on the way out would play
            // exactly once per launch. The engine holds the source until the
            // next one replaces it.
            //
            // NonCancellable because this runs *because* the effect was
            // cancelled — a plain call here would be cancelled before it
            // reached the engine, and the audio would follow the user back to
            // the previous screen.
            withContext(NonCancellable) { playback.pause() }
        }
    }

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
                .clickable(role = Role.Button, onClick = onBack),
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
