package moe.ditto.halo.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayModeClaimsTest {

    @Test
    fun leavingThePlayerGivesTheModeBackAtOnce() {
        val claims = DisplayModeClaims()
        claims.claim()
        // Back to the title page: no player was announced.
        assertEquals(DisplayModeRelease.RestoreNow, claims.release())
        assertFalse(claims.held)
    }

    @Test
    fun anAnnouncedPlayerComingFirstKeepsTheMode() {
        val claims = DisplayModeClaims()
        claims.claim()
        claims.expectNext()
        // The next episode's player is composed before the old one goes.
        claims.claim()
        assertEquals(DisplayModeRelease.Keep, claims.release())
        assertTrue(claims.held)
        // And leaving that one later is a real leave again.
        assertEquals(DisplayModeRelease.RestoreNow, claims.release())
    }

    @Test
    fun anAnnouncedPlayerComingSecondIsWaitedFor() {
        val claims = DisplayModeClaims()
        claims.claim()
        claims.expectNext()
        assertEquals(DisplayModeRelease.AwaitNext, claims.release())
        // It arrives: the wait ends with the mode held.
        claims.claim()
        assertTrue(claims.held)
        assertFalse(claims.nextNeverCame(), "the safety net finds it held and does nothing")
    }

    @Test
    fun anAnnouncedPlayerThatNeverComesGivesTheModeBackAfterAll() {
        val claims = DisplayModeClaims()
        claims.claim()
        claims.expectNext()
        assertEquals(DisplayModeRelease.AwaitNext, claims.release())
        assertTrue(claims.nextNeverCame())
        // The announcement is spent: a later leave is a real one.
        claims.claim()
        assertEquals(DisplayModeRelease.RestoreNow, claims.release())
    }

    @Test
    fun aPictureWithoutAKnownRateKeepsTheMode() {
        // The second engine's picture is up before its rate is measured: nothing changes.
        assertEquals(FrameRateStep.Hold, frameRateStep(frameRate = null, pictureShown = true))
        // Only no picture at all is waited on, and may give the mode back.
        assertEquals(FrameRateStep.AwaitPicture, frameRateStep(frameRate = null, pictureShown = false))
        // A known rate is matched, from whichever engine, picture or not yet.
        assertEquals(FrameRateStep.Match, frameRateStep(frameRate = 24.0, pictureShown = true))
        assertEquals(FrameRateStep.Match, frameRateStep(frameRate = 23.976, pictureShown = false))
    }

    @Test
    fun aHandOverWithTheSameRateKeepsTheModeItMatched() {
        val modes = listOf(DisplayModeOption(4, 3840, 2160, 59.94f), DisplayModeOption(6, 3840, 2160, 24f))
        // mpv matched 24 Hz; the second engine then reports the same rate.
        val current = modes[1]
        assertEquals(current, pickDisplayMode(modes, current, 24.0))
        assertEquals(current, pickDisplayMode(modes, current, 23.976))
    }

    private val cube = listOf(
        DisplayModeOption(1, 3840, 2160, 23.976f),
        DisplayModeOption(2, 3840, 2160, 24f),
        DisplayModeOption(3, 3840, 2160, 25f),
        DisplayModeOption(4, 3840, 2160, 59.94f),
        DisplayModeOption(5, 3840, 2160, 60f),
        DisplayModeOption(6, 3840, 2160, 29.97f),
        DisplayModeOption(7, 3840, 2160, 50f),
    )

    @Test
    fun aSlightlyOffRateNeverTakesDownAModeThatShowsItAboutRight() {
        val at24 = cube[1]
        // The rough measurement that undid the match on the device.
        assertEquals(at24, displayModeFor(cube, at24, 23.81))
        assertEquals(at24, displayModeFor(cube, at24, 24.02))
        assertEquals(cube[0], displayModeFor(cube, at24, 23.98))
    }

    @Test
    fun aRateTheCurrentModeDoesNotFitStillMovesOn() {
        val at24 = cube[1]
        assertEquals(cube[2], displayModeFor(cube, at24, 25.0))
        assertEquals(cube[3], displayModeFor(cube, at24, 59.94))
        // Nothing fits and the current mode is far off: the display's own choice.
        assertEquals(null, displayModeFor(cube, at24, 27.0))
    }

    @Test
    fun aStrayReleaseNeverGoesBelowNobody() {
        val claims = DisplayModeClaims()
        assertEquals(DisplayModeRelease.RestoreNow, claims.release())
        claims.claim()
        assertTrue(claims.held)
    }
}
