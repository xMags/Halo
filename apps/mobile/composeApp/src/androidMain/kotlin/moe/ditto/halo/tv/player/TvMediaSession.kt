package moe.ditto.halo.tv.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.roundToLong
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerState

/** What the system's media controls (voice, a phone remote app) can ask of the player. */
internal interface TvMediaCommands {
    fun play()
    fun pause()
    fun seekTo(seconds: Double)
    /** Forward when positive. */
    fun skipBy(seconds: Double)
    fun next()
    fun previous()
    fun stop()
}

/** The session's state for a player state; PlaybackState.STATE_* values. */
internal fun sessionStateFor(state: PlayerState): Int = when {
    state.status == PlaybackStatus.Loading || state.buffering != null -> PlaybackState.STATE_BUFFERING
    state.status == PlaybackStatus.Playing -> PlaybackState.STATE_PLAYING
    state.status == PlaybackStatus.Paused -> PlaybackState.STATE_PAUSED
    state.status == PlaybackStatus.Ended -> PlaybackState.STATE_STOPPED
    state.status == PlaybackStatus.Failed -> PlaybackState.STATE_ERROR
    else -> PlaybackState.STATE_NONE
}

/** What was last told to the system, to tell a real change from the playhead simply moving on. */
internal data class SessionSnapshot(
    val sessionState: Int,
    val positionSeconds: Double,
    val rate: Double,
    val atMillis: Long,
)

/**
 * True when the system's copy is out of date. While playing, the system works
 * the position out from the last one it was given, so only a change of state
 * or speed, or a jump (a seek) needs telling.
 */
internal fun sessionNeedsUpdate(last: SessionSnapshot?, next: SessionSnapshot): Boolean {
    if (last == null) return true
    if (last.sessionState != next.sessionState || last.rate != next.rate) return true
    val moving = next.sessionState == PlaybackState.STATE_PLAYING
    val expected = last.positionSeconds + if (moving) (next.atMillis - last.atMillis) / 1000.0 * last.rate else 0.0
    return abs(next.positionSeconds - expected) > DriftSeconds
}

/**
 * The player's media session while a title is on screen. It is what lets the
 * system's controls reach Halo: voice commands, a phone remote app, and the
 * media keys of a remote whose keys go to the session rather than the window.
 */
internal class TvMediaSession(context: Context, commands: TvMediaCommands) {
    private val session = MediaSession(context, "Halo")
    private var last: SessionSnapshot? = null
    private var lastMetadata: Triple<String, String?, Long?>? = null

    init {
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = commands.play()
                override fun onPause() = commands.pause()
                override fun onSeekTo(pos: Long) = commands.seekTo(pos / 1000.0)
                override fun onFastForward() = commands.skipBy(SkipSeconds)
                override fun onRewind() = commands.skipBy(-SkipSeconds)
                override fun onSkipToNext() = commands.next()
                override fun onSkipToPrevious() = commands.previous()
                override fun onStop() = commands.stop()
            },
        )
        session.isActive = true
    }

    fun update(state: PlayerState, title: String, subtitle: String?) {
        val duration = state.durationSeconds?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).roundToLong() }
        val metadata = Triple(title, subtitle, duration)
        if (metadata != lastMetadata) {
            lastMetadata = metadata
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .apply { subtitle?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it) } }
                    .apply { duration?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) } }
                    .build(),
            )
        }
        val next = SessionSnapshot(sessionStateFor(state), state.positionSeconds, state.playbackRate, SystemClock.elapsedRealtime())
        if (!sessionNeedsUpdate(last, next)) return
        last = next
        val speed = if (next.sessionState == PlaybackState.STATE_PLAYING) next.rate.toFloat() else 0f
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(next.sessionState, (next.positionSeconds * 1000).roundToLong(), speed, next.atMillis)
                .setActions(Actions)
                .build(),
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private companion object {
        const val SkipSeconds = 10.0
        const val Actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP
    }
}

/** How far the playhead may stray from where the system expects it before it is told again. */
private const val DriftSeconds = 2.0
