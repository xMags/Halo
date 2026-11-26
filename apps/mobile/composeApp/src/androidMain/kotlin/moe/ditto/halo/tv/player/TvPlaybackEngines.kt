package moe.ditto.halo.tv.player

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ditto.halo.AndroidMpvPlayerHost
import moe.ditto.halo.NativePlayerSurface
import moe.ditto.halo.player.DirectVideoFrame
import moe.ditto.halo.player.DirectVideoSurface
import moe.ditto.halo.player.Media3PlayerHost
import moe.ditto.halo.player.Media3SoundState
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlayerEvent
import moe.ditto.halo.player.PlayerPort

/** Which engine plays a file on the TV. mpv is the first choice for everything. */
internal enum class PlaybackEngine { Mpv, Media3 }

/** Why the second engine plays a file: what it does there that mpv did not on this device. */
internal enum class SecondEngineReason {
    /** A Dolby Vision picture mpv shows wrongly here. */
    DolbyVision,

    /** Dolby surround mpv could not send on untouched here. */
    Sound,
}

/** Picks the engine for a file about to load; see [TvDolbyVisionSources.engineFor]. */
internal fun interface EngineChooser {
    fun engineFor(item: MediaItem): PlaybackEngine

    /** The frame rate this device saw for [item]'s file before, null when it knows none. */
    fun frameRateFor(item: MediaItem): Double? = null
}

/**
 * The TV's player: mpv, or for the files mpv was seen to show wrongly on
 * this device, the second engine, behind the one [PlayerPort] the session
 * drives. Only one engine holds a file, and so a decoder, at a time: before
 * the other starts, the one playing gives its decoders up and waits until
 * they are gone ([AndroidMpvPlayerHost.stopForOtherEngine], or the second
 * engine's release).
 *
 * Commands about the file go to the engine playing it; settings that outlive
 * a file (speed, subtitle look, languages, fit) go to both, so whichever plays
 * next starts with them, as mpv's own core properties do. Events reach the
 * session only from the engine playing ([events]).
 *
 * Built once by the TV activity, like the hosts it routes between. Loads are
 * taken one at a time.
 */
internal class TvPlaybackEngines(
    private val mpvHost: AndroidMpvPlayerHost,
    private val mpv: PlayerPort,
    /** The second engine itself, for the pass-through watch that reads and corrects its sound. */
    val media3Host: Media3PlayerHost,
    private val media3: PlayerPort,
    private val chooser: EngineChooser,
) : PlayerPort {
    private val activeEngine = MutableStateFlow(PlaybackEngine.Mpv)
    val active: StateFlow<PlaybackEngine> = activeEngine.asStateFlow()

    private val reason = MutableStateFlow<SecondEngineReason?>(null)

    /** Why the second engine plays the current file; null while mpv plays. */
    val secondEngineReason: StateFlow<SecondEngineReason?> = reason.asStateFlow()

    /** The sound the second engine sends on untouched, for the screen's one view of pass-through. */
    val secondEngineSound: StateFlow<Media3SoundState?> get() = media3Host.soundPassthrough

    /** mpv's picture: whether its direct path (the device's hardware decoder) shows the file. */
    val mpvVideoFrame: StateFlow<DirectVideoFrame> get() = mpvHost.videoFrame

    private val loads = Mutex()

    private class NextChoice(val url: String, val engine: PlaybackEngine, val reason: SecondEngineReason?, val frameRate: Double?)

    // A one-time choice for the next load of a URL: the screen's hand-over
    // to the second engine, or back to mpv after the second engine failed.
    @Volatile private var nextChoice: NextChoice? = null

    /** Each engine's events, passed on only while that engine plays. */
    val events: Flow<PlayerEvent> = merge(
        mpvHost.playerEvents.filter { activeEngine.value == PlaybackEngine.Mpv },
        media3Host.playerEvents.filter { activeEngine.value == PlaybackEngine.Media3 },
    )

    /**
     * The next load of [url] goes to [engine] (for [why], with the second
     * engine), whatever the chooser says; once. [frameRate] is the rate the
     * file was already seen at (mpv's, for a hand-over of the same file),
     * which the second engine then uses rather than measuring a rougher one.
     */
    fun chooseForNextLoad(url: String, engine: PlaybackEngine, why: SecondEngineReason? = null, frameRate: Double? = null) {
        nextChoice = NextChoice(url, engine, why, frameRate)
    }

    private fun current(): PlayerPort = if (activeEngine.value == PlaybackEngine.Media3) media3 else mpv

    // Both engines open a file at its start place: mpv by its per-file
    // `start`, the second engine by the media item's start position.
    override val opensAtStartPosition: Boolean get() = true

    override suspend fun load(item: MediaItem) = load(item, startPositionSeconds = null)

    override suspend fun load(item: MediaItem, startPositionSeconds: Double?) = loads.withLock {
        val once = nextChoice?.takeIf { it.url == item.url }
        nextChoice = null
        val engine = once?.engine ?: chooser.engineFor(item)
        val previous = activeEngine.value
        if (engine != previous) {
            when (previous) {
                PlaybackEngine.Mpv -> mpvHost.stopForOtherEngine()
                PlaybackEngine.Media3 -> media3.teardown()
            }
            activeEngine.value = engine
        }
        // The chooser takes a file only for its picture.
        reason.value = if (engine == PlaybackEngine.Media3) once?.reason ?: SecondEngineReason.DolbyVision else null
        when {
            engine == PlaybackEngine.Media3 -> Log.i(
                LogTag,
                "second engine: plays this file (${if (once != null) "handed over" else "remembered on this TV"}, for its ${if (reason.value == SecondEngineReason.Sound) "sound" else "picture"})",
            )
            previous == PlaybackEngine.Media3 -> Log.i(LogTag, "second engine: done; mpv plays this file")
        }
        if (engine == PlaybackEngine.Media3) {
            // The same file's rate as mpv reported it, else as remembered on
            // this TV; measuring is only for a file known by neither.
            val knownRate = once?.frameRate ?: chooser.frameRateFor(item)
            media3Host.load(item, startPositionSeconds, knownRate)
        } else {
            mpvHost.load(item, startPositionSeconds)
        }
    }

    // The file playing.
    override suspend fun setPaused(paused: Boolean) = current().setPaused(paused)
    override suspend fun seekTo(positionSeconds: Double) = current().seekTo(positionSeconds)
    override suspend fun seekNearKeyframe(positionSeconds: Double, forward: Boolean) =
        if (activeEngine.value == PlaybackEngine.Media3) {
            media3.seekNearKeyframe(positionSeconds, forward)
        } else {
            mpvHost.seekNearKeyframe(positionSeconds, forward)
        }
    override suspend fun selectAudioTrack(id: String?) = current().selectAudioTrack(id)
    override suspend fun selectSubtitleTrack(id: String?) = current().selectSubtitleTrack(id)
    override suspend fun addSubtitle(url: String, title: String?, lang: String?) = current().addSubtitle(url, title, lang)
    override suspend fun releaseVideoOutput() = current().releaseVideoOutput()

    // Settings both keep.
    override suspend fun setPreferredAudioLanguages(codes: List<String>) = both { it.setPreferredAudioLanguages(codes) }
    override suspend fun setPlaybackRate(rate: Double) = both { it.setPlaybackRate(rate) }
    override suspend fun setAudioDelay(seconds: Double) = both { it.setAudioDelay(seconds) }
    override suspend fun setVideoFillsScreen(fills: Boolean) = both { it.setVideoFillsScreen(fills) }
    override suspend fun setSubtitleDelay(seconds: Double) = both { it.setSubtitleDelay(seconds) }
    override suspend fun setSubtitleScale(scale: Double) = both { it.setSubtitleScale(scale) }
    override suspend fun setSubtitleFont(font: String?) = both { it.setSubtitleFont(font) }
    override suspend fun setSubtitleTrackStyling(keepScript: Boolean) = both { it.setSubtitleTrackStyling(keepScript) }
    override suspend fun setSubtitleOutline(widthPixels: Double) = both { it.setSubtitleOutline(widthPixels) }
    override suspend fun setSubtitleShadow(offsetPixels: Double) = both { it.setSubtitleShadow(offsetPixels) }
    override suspend fun setSubtitleLift(percent: Int) = both { it.setSubtitleLift(percent) }
    override suspend fun teardown() = both { it.teardown() }

    private suspend fun both(action: suspend (PlayerPort) -> Unit) {
        action(mpv)
        action(media3)
    }

    private companion object {
        const val LogTag = "HALO_TV"
    }
}

/**
 * The surface of whichever engine plays: mpv's own, or the second engine's
 * view sized and captioned by the app in the same way as mpv's direct path.
 * Changing engines takes one view away, which detaches it as any exit does.
 */
internal class TvEngineSurface(
    private val mpvSurface: NativePlayerSurface,
    private val media3Host: Media3PlayerHost,
    private val engines: TvPlaybackEngines,
) : NativePlayerSurface {
    @Composable
    override fun Content(modifier: Modifier) {
        val engine by engines.active.collectAsState()
        when (engine) {
            PlaybackEngine.Mpv -> mpvSurface.Content(modifier)
            PlaybackEngine.Media3 -> DirectVideoSurface(
                frame = media3Host.videoFrame,
                subtitles = media3Host.subtitleOverlay,
                surface = media3Host::composeSurface,
                modifier = modifier,
            )
        }
    }
}
