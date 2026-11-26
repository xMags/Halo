package moe.ditto.halo.tv.player

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.ditto.halo.player.Media3PlayerHost
import moe.ditto.halo.player.PlaybackFailure
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerState
import moe.ditto.halo.player.SubtitleDrawing
import moe.ditto.halo.player.VideoPathPolicy
import moe.ditto.halo.screens.player.PlaybackContext
import moe.ditto.halo.screens.player.PlayerSession

/**
 * What the TV player does about Dolby surround the second engine may send on
 * untouched ([TvSecondEngineSound]), apart from the screen:
 *
 * - in mpv, once the file is open with its picture up and its subtitle chosen,
 *   a Dolby track on an output where mpv's pass-through failed goes to the
 *   second engine when everything else about the file plays there too. The
 *   choice is made once per file, at its start: a later change of audio track
 *   never switches engines;
 * - in the second engine, the sound sent on untouched must be seen to play
 *   ([watchPassthrough]) or it is decoded there instead, keeping the picture;
 * - a file the second engine could not play for its sound goes back to mpv,
 *   which plays it as before.
 */
@Composable
internal fun TvSurroundPlayback(
    session: PlayerSession,
    state: PlayerState,
    engine: PlaybackEngine,
    engines: TvPlaybackEngines,
    media3Host: Media3PlayerHost,
    sound: TvSecondEngineSound,
    context: PlaybackContext,
    /** The applied `audio-spdif` list of mpv's file, so a pass-through mpv gives up on is looked at again. */
    mpvSpdifCodecs: String?,
) {
    var handedOver by remember(session.item) { mutableStateOf(false) }
    val opened = session.ownState && state.opened
    val audioTrack = state.tracks.audio.firstOrNull { it.id == state.tracks.selectedAudioId }
    val pictureUp = state.videoFormat.width > 0 && state.videoFormat.height > 0
    val inMpv = engine == PlaybackEngine.Mpv
    LaunchedEffect(session.item, inMpv, opened, pictureUp, audioTrack?.codec, mpvSpdifCodecs) {
        if (!inMpv || !opened || !pictureUp || handedOver) return@LaunchedEffect
        val codec = audioTrack?.codec ?: return@LaunchedEffect
        if (dolbyFormatOf(codec) == null || context.url in sound.filesMpvKeeps) return@LaunchedEffect
        // The subtitle the session restores decides what must keep showing.
        withTimeoutOrNull(SubtitleChoiceWaitMs) { snapshotFlow { session.selectionApplied }.first { it } }
        val subtitle = state.tracks.subtitles.firstOrNull { it.id == state.tracks.selectedSubtitleId }
        val drawing = VideoPathPolicy.subtitleDrawing(subtitle != null, subtitle?.codec, state.subtitleTrackStyling)
        val format = sound.handoverFormat(
            codec = codec,
            pictureFromHardware = engines.mpvVideoFrame.value.direct,
            subtitleDrawnByApp = drawing != SubtitleDrawing.Engine,
        ) ?: return@LaunchedEffect
        handedOver = true
        Log.i(LogTag, "second engine: taking this file for its ${format.mpvName} sound; handing the file over")
        engines.chooseForNextLoad(context.url, PlaybackEngine.Media3, SecondEngineReason.Sound, state.videoFormat.frameRate)
        session.retry()
    }

    // The second engine's sound sent on untouched, watched until it plays.
    val passing by media3Host.soundPassthrough.collectAsState()
    val ownPassing = passing?.takeIf { it.item == session.item && engine == PlaybackEngine.Media3 }
    LaunchedEffect(session.item, ownPassing?.starts) {
        val watched = ownPassing ?: return@LaunchedEffect
        watchPassthrough(media3Host, sound, watched.encoding)
    }

    val reason by engines.secondEngineReason.collectAsState()
    val soundFileFailed = engine == PlaybackEngine.Media3 && reason == SecondEngineReason.Sound && session.ownState &&
        state.status == PlaybackStatus.Failed && state.failure == PlaybackFailure.UnsupportedVideo
    LaunchedEffect(session.item, soundFileFailed) {
        if (!soundFileFailed) return@LaunchedEffect
        sound.keepWithMpv(context.url)
        Log.i(LogTag, "second engine: could not play this file taken for its sound; back to mpv")
        engines.chooseForNextLoad(context.url, PlaybackEngine.Mpv)
        session.retry()
    }
}

/**
 * Watches sound sent on untouched as [encoding] until it is seen to play:
 * Media3 keeps time by the audio output, so the position moves only while
 * that output takes the stream ([PassthroughStartWatch], as mpv's start is
 * watched). If it never does, the sound is decoded in the second engine
 * instead and the failure is remembered for the output. A new opening of the
 * output (an HDMI re-plug) cancels this watch and starts the next.
 */
private suspend fun watchPassthrough(host: Media3PlayerHost, sound: TvSecondEngineSound, encoding: Int) {
    val first = host.audioStartSample() ?: return
    val watch = PassthroughStartWatch(first.file)
    var last = SystemClock.elapsedRealtime()
    watch.step(first, 0L)
    while (true) {
        delay(PassthroughStartPollMs)
        val sample = host.audioStartSample() ?: return
        val now = SystemClock.elapsedRealtime()
        val verdict = watch.step(sample, now - last)
        last = now
        when (verdict) {
            PassthroughStartVerdict.Waiting -> continue
            PassthroughStartVerdict.Superseded -> return
            PassthroughStartVerdict.Started -> {
                Log.i(LogTag, "second engine: sound sent on untouched is playing (encoding $encoding, position moving)")
                sound.passthroughWorked(encoding)
                return
            }
            PassthroughStartVerdict.Stuck -> {
                if (!host.decodeAudioHere(sample.file)) return
                Log.i(LogTag, "second engine: sound sent on untouched did not start in ${PassthroughStartWindowMs / 1000} s; decoding it here, picture kept")
                sound.onPassthroughFailed(encoding)
                return
            }
        }
    }
}

/** How long the start waits for the session to restore the viewer's subtitle before deciding. */
private const val SubtitleChoiceWaitMs = 3_000L

private const val LogTag = "HALO_TV"
