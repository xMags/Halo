package moe.ditto.halo.tv.player

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.Stream
import moe.ditto.halo.player.PlaybackFailure
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerState
import moe.ditto.halo.player.VideoFormat
import moe.ditto.halo.screens.player.PlaybackContext
import moe.ditto.halo.screens.player.PlayerSession

/**
 * What the TV player does about a Dolby Vision picture, apart from the screen:
 *
 * - in mpv, a picture that needs processing goes to the second engine when
 *   this device can take it there ([dolbyVisionStep]): at once once the
 *   device has shown the second engine works, else after mpv's verdict. The
 *   file is loaded again in the second engine ([PlayerSession.retry]), so it
 *   resumes where watching resumes, with its subtitle choice made again;
 * - without the second engine, as before it: remembered, moved on from, or
 *   with nothing else played on as it is;
 * - in the second engine, a first picture from the device's own decoder
 *   teaches the device that it works, and a failure leaves the file to mpv
 *   from then on and moves on, or with nothing else hands it back to mpv.
 *
 * Every effect is keyed to this item, so nothing carries over to the next.
 */
@Composable
internal fun TvDolbyVisionPlayback(
    session: PlayerSession,
    state: PlayerState,
    engine: PlaybackEngine,
    engines: TvPlaybackEngines,
    dolbyVision: TvDolbyVisionSources,
    context: PlaybackContext,
    sourceFallback: TvSourceFallback?,
    onPlaySource: (AddonSource, Stream) -> Unit,
) {
    val ownFormat = if (session.ownState && state.opened) state.videoFormat else VideoFormat()
    val profile = ownFormat.dolbyVisionProfile
    val step = dolbyVisionStep(
        format = ownFormat,
        engine = engine,
        secondEngineForFile = profile != null && dolbyVision.secondEngineFor(context.url, profile),
        secondEngineWorks = dolbyVision.secondEngineWorks,
    )
    LaunchedEffect(session.item, step) {
        val seen = profile ?: return@LaunchedEffect
        when (step) {
            DolbyVisionStep.Wait -> Unit
            DolbyVisionStep.HandOver -> {
                dolbyVision.remember(context.url, seen)
                val reason = if (ownFormat.dolbyVisionProcessed == false) "mpv showed it wrongly" else "the second engine shows these here"
                Log.i(LogTag, "second engine: Dolby Vision profile $seen, $reason; handing the file over")
                engines.chooseForNextLoad(context.url, PlaybackEngine.Media3)
                session.retry()
            }
            DolbyVisionStep.MoveOn -> {
                dolbyVision.remember(context.url, seen)
                val next = sourceFallback?.next(context)
                if (next == null) {
                    Log.i(LogTag, "Dolby Vision profile $seen is not processed on this TV; no other source, playing it as it is")
                    dolbyVision.playOnAsIs()
                    return@LaunchedEffect
                }
                Log.i(LogTag, "Dolby Vision profile $seen is not processed on this TV; moving to another source")
                session.leaveTo { onPlaySource(next.first, next.second) }
            }
        }
    }

    // The second engine's own picture of such a file, from the device's decoder.
    val secondEngineShows = engine == PlaybackEngine.Media3 && ownFormat.dolbyVisionProcessed == true
    LaunchedEffect(session.item, secondEngineShows) {
        if (secondEngineShows) dolbyVision.secondEngineWorked()
    }

    val secondEngineFailed = engine == PlaybackEngine.Media3 && session.ownState &&
        state.status == PlaybackStatus.Failed && state.failure == PlaybackFailure.UnsupportedVideo
    LaunchedEffect(session.item, secondEngineFailed) {
        if (!secondEngineFailed) return@LaunchedEffect
        dolbyVision.secondEngineFailed(context.url)
        val next = sourceFallback?.next(context)
        if (next != null) {
            Log.i(LogTag, "second engine: could not play this file; moving to another source")
            session.leaveTo { onPlaySource(next.first, next.second) }
            return@LaunchedEffect
        }
        Log.i(LogTag, "second engine: could not play this file and nothing else is offered; back to mpv")
        engines.chooseForNextLoad(context.url, PlaybackEngine.Mpv)
        session.retry()
    }
}

private const val LogTag = "HALO_TV"
