package moe.ditto.halo.tv.player

import android.media.AudioManager
import android.media.session.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerBuffering
import moe.ditto.halo.player.PlayerState

class TvPlaybackSystemTest {
    @Test
    fun aShortInterruptionPausesAndPlaysOnAfterwards() {
        val policy = TvAudioFocusPolicy()
        assertEquals(TvFocusResponse.Pause, policy.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = true))
        assertEquals(TvFocusResponse.Resume, policy.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false))
        // Once only.
        assertEquals(TvFocusResponse.Nothing, policy.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = true))
    }

    @Test
    fun aLastingLossPausesForGood() {
        val policy = TvAudioFocusPolicy()
        assertEquals(TvFocusResponse.Pause, policy.onChange(AudioManager.AUDIOFOCUS_LOSS, playing = true))
        assertEquals(TvFocusResponse.Nothing, policy.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false))
    }

    @Test
    fun aFilmPausedByTheViewerStaysPaused() {
        val policy = TvAudioFocusPolicy()
        assertEquals(TvFocusResponse.Nothing, policy.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = false))
        assertEquals(TvFocusResponse.Nothing, policy.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false))

        // Or paused during the interruption.
        policy.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = true)
        policy.onViewerChoice()
        assertEquals(TvFocusResponse.Nothing, policy.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false))
    }

    @Test
    fun aRequestToLowerTheVolumeIsLeftToTheSystem() {
        val policy = TvAudioFocusPolicy()
        assertEquals(TvFocusResponse.Nothing, policy.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, playing = true))
    }

    @Test
    fun theSessionStateFollowsThePlayer() {
        assertEquals(PlaybackState.STATE_BUFFERING, sessionStateFor(PlayerState(status = PlaybackStatus.Loading)))
        assertEquals(PlaybackState.STATE_PLAYING, sessionStateFor(PlayerState(status = PlaybackStatus.Playing)))
        assertEquals(
            PlaybackState.STATE_BUFFERING,
            sessionStateFor(PlayerState(status = PlaybackStatus.Playing, buffering = PlayerBuffering(percent = 40))),
        )
        assertEquals(PlaybackState.STATE_PAUSED, sessionStateFor(PlayerState(status = PlaybackStatus.Paused)))
        assertEquals(PlaybackState.STATE_STOPPED, sessionStateFor(PlayerState(status = PlaybackStatus.Ended)))
        assertEquals(PlaybackState.STATE_ERROR, sessionStateFor(PlayerState(status = PlaybackStatus.Failed)))
        assertEquals(PlaybackState.STATE_NONE, sessionStateFor(PlayerState(status = PlaybackStatus.Idle)))
    }

    @Test
    fun theSystemIsToldOfChangesNotOfThePlayheadMovingOn() {
        val playing = SessionSnapshot(PlaybackState.STATE_PLAYING, positionSeconds = 100.0, rate = 1.0, atMillis = 0)
        assertTrue(sessionNeedsUpdate(null, playing))
        // Ten seconds on, ten seconds further: what the system works out itself.
        assertFalse(sessionNeedsUpdate(playing, playing.copy(positionSeconds = 110.0, atMillis = 10_000)))
        // A seek.
        assertTrue(sessionNeedsUpdate(playing, playing.copy(positionSeconds = 160.0, atMillis = 10_000)))
        // A pause, and a change of speed.
        assertTrue(sessionNeedsUpdate(playing, playing.copy(sessionState = PlaybackState.STATE_PAUSED, atMillis = 1_000)))
        assertTrue(sessionNeedsUpdate(playing, playing.copy(rate = 1.5, atMillis = 1_000)))
        // Paused, the playhead stays put.
        val paused = playing.copy(sessionState = PlaybackState.STATE_PAUSED)
        assertFalse(sessionNeedsUpdate(paused, paused.copy(atMillis = 60_000)))
    }
}
