package moe.ditto.halo.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ditto.halo.PlaybackHost
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.screens.player.PlayerSession

/**
 * What the TV player owes the rest of the system while a title is on screen:
 * a media session (voice commands and phone remotes reach the player through
 * it), audio focus (another app's sound pauses the film, a short interruption
 * only for its length), and a pause when the viewer goes to the home screen.
 */
@Composable
internal fun TvPlaybackSystem(
    playback: PlaybackHost,
    session: PlayerSession,
    scope: CoroutineScope,
    /** Shows the controls, so a pause from outside is visible on screen. */
    onPausedFromOutside: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentSession by rememberUpdatedState(session)
    val currentOnPaused by rememberUpdatedState(onPausedFromOutside)
    val policy = remember { TvAudioFocusPolicy() }

    fun setPaused(paused: Boolean) {
        scope.launch { playback.setPaused(paused) }
    }

    val focus = remember(context) {
        TvAudioFocus(context) { change ->
            val playing = playback.state.value.status == PlaybackStatus.Playing
            when (policy.onChange(change, playing)) {
                TvFocusResponse.Pause -> {
                    setPaused(true)
                    currentOnPaused()
                }
                TvFocusResponse.Resume -> setPaused(false)
                TvFocusResponse.Nothing -> Unit
            }
        }
    }

    val mediaSession = remember(context) {
        TvMediaSession(
            context,
            object : TvMediaCommands {
                override fun play() {
                    policy.onViewerChoice()
                    setPaused(false)
                }

                override fun pause() {
                    policy.onViewerChoice()
                    setPaused(true)
                    currentOnPaused()
                }

                override fun seekTo(seconds: Double) {
                    scope.launch { playback.seekTo(seconds.coerceAtLeast(0.0)) }
                }

                override fun skipBy(seconds: Double) {
                    val target = playback.state.value.positionSeconds + seconds
                    scope.launch { playback.seekTo(target.coerceAtLeast(0.0)) }
                }

                override fun next() {
                    currentSession.playNext()
                }

                override fun previous() {
                    scope.launch { playback.seekTo(0.0) }
                }

                override fun stop() = currentSession.leave()
            },
        )
    }

    DisposableEffect(mediaSession, focus) {
        onDispose {
            mediaSession.release()
            focus.abandon()
        }
    }

    // The session follows the player; the sound is claimed as it starts.
    LaunchedEffect(mediaSession) {
        var wasPlaying = false
        playback.state.collect { state ->
            val title = currentSession.context
            val subtitle = listOfNotNull(title.episodeTag, title.episodeName).joinToString(" · ").ifBlank { null }
            mediaSession.update(state, title.showTitle, subtitle)
            val playing = state.status == PlaybackStatus.Playing
            val started = playing && !wasPlaying
            wasPlaying = playing
            if (!started) return@collect
            // Playing again by any route settles an interruption.
            policy.onViewerChoice()
            if (!focus.request()) setPaused(true)
        }
    }

    // The home button: nobody is watching any more.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
            policy.onViewerChoice()
            setPaused(true)
            // Coming back, the controls say it is paused rather than frozen.
            currentOnPaused()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
