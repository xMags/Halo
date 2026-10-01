package moe.ditto.halo.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/**
 * The auto-hide rule is the thing worth pinning down: several unrelated
 * interactions arm it, and four independent conditions suppress it. Every case
 * below is one of those conditions, checked against virtual time so the tests
 * cost nothing to run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerScreenControllerTest {

    /** Comfortably past the 3 s idle window. */
    private val pastIdle = 3_500L

    @Test
    fun chromeStartsVisibleAndHidesWhenIdle() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        assertTrue(controller.chromeVisible)

        controller.armAutoHide()
        advanceTimeBy(pastIdle)

        assertFalse(controller.chromeVisible, "chrome should hide itself once idle")
    }

    @Test
    fun chromeStaysUpWhilePaused() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.onPausedChanged(true)

        advanceTimeBy(pastIdle)

        assertTrue(controller.chromeVisible, "a deliberate pause should keep the controls up")
    }

    @Test
    fun resumingAfterAPauseRestartsTheIdleTimer() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.onPausedChanged(true)
        advanceTimeBy(pastIdle)
        assertTrue(controller.chromeVisible)

        controller.onPausedChanged(false)
        advanceTimeBy(pastIdle)

        assertFalse(controller.chromeVisible)
    }

    @Test
    fun chromeStaysUpWhileTheRailIsOpen() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.openRail(RailTab.Subtitles)

        advanceTimeBy(pastIdle)

        assertTrue(controller.chromeVisible)
        assertEquals(RailTab.Subtitles, controller.rail)
    }

    @Test
    fun closingTheRailLetsTheChromeHideAgain() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.openRail(RailTab.Audio)
        controller.closeRail()

        advanceTimeBy(pastIdle)

        assertNull(controller.rail)
        assertFalse(controller.chromeVisible)
    }

    @Test
    fun chromeStaysUpWhileTheEpisodeDrawerIsOpen() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.toggleEpisodeDrawer()

        advanceTimeBy(pastIdle)

        assertTrue(controller.episodeDrawerOpen)
        assertTrue(controller.chromeVisible)
    }

    @Test
    fun theRailAndTheDrawerAreMutuallyExclusive() = runTest {
        val controller = PlayerScreenController(backgroundScope)

        controller.openRail(RailTab.Speed)
        controller.toggleEpisodeDrawer()
        assertNull(controller.rail, "opening the drawer should close the rail")
        assertTrue(controller.episodeDrawerOpen)

        controller.openRail(RailTab.Audio)
        assertFalse(controller.episodeDrawerOpen, "opening the rail should close the drawer")
        assertEquals(RailTab.Audio, controller.rail)
    }

    @Test
    fun chromeStaysUpWhileScrubbing() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.beginScrub(0.5f)

        advanceTimeBy(pastIdle)

        assertTrue(controller.chromeVisible, "chrome must not vanish under a finger mid-drag")
    }

    @Test
    fun aScrubCommitsItsFinalPositionAndReleasesTheTimer() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.beginScrub(0.5f)
        controller.updateScrub(0.8f)

        assertEquals(0.8f, controller.endScrub())
        assertNull(controller.scrubFraction)

        advanceTimeBy(pastIdle)
        assertFalse(controller.chromeVisible)
    }

    @Test
    fun scrubFractionsAreClampedToTheTrack() = runTest {
        val controller = PlayerScreenController(backgroundScope)

        controller.beginScrub(-0.4f)
        assertEquals(0f, controller.scrubFraction)

        controller.updateScrub(1.9f)
        assertEquals(1f, controller.scrubFraction)
    }

    @Test
    fun aScrubUpdateWithoutAScrubInFlightIsIgnored() = runTest {
        val controller = PlayerScreenController(backgroundScope)

        controller.updateScrub(0.7f)

        assertNull(controller.scrubFraction, "a stray drag event must not open a scrub")
        assertNull(controller.endScrub())
    }

    @Test
    fun tappingTheVideoTogglesTheChromeBothWays() = runTest {
        val controller = PlayerScreenController(backgroundScope)

        controller.toggleChrome()
        assertFalse(controller.chromeVisible)

        // Hidden chrome has no timer to arm, so it stays down until asked back.
        advanceTimeBy(pastIdle)
        assertFalse(controller.chromeVisible)

        controller.toggleChrome()
        assertTrue(controller.chromeVisible)
    }

    @Test
    fun lockingPutsEveryPanelAndTheChromeAway() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.openRail(RailTab.Audio)

        controller.lock()

        assertTrue(controller.locked)
        assertFalse(controller.chromeVisible, "a stray tap on a control is what locking prevents")
        assertNull(controller.rail)
        assertFalse(controller.episodeDrawerOpen)
    }

    @Test
    fun theUnlockPillHidesItselfAndComesBackOnATap() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.lock()
        assertTrue(controller.unlockPillVisible)

        advanceTimeBy(pastIdle)
        assertFalse(controller.unlockPillVisible, "a static overlay burns into a panel")

        controller.revealUnlockPill()
        assertTrue(controller.unlockPillVisible)

        advanceTimeBy(pastIdle)
        assertFalse(controller.unlockPillVisible, "the timer should restart, not be spent")
    }

    @Test
    fun theUnlockPillCannotBeSummonedWhileUnlocked() = runTest {
        val controller = PlayerScreenController(backgroundScope)

        controller.revealUnlockPill()

        assertFalse(controller.unlockPillVisible)
    }

    @Test
    fun unlockingBringsTheChromeBack() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.lock()

        controller.unlock()

        assertFalse(controller.locked)
        assertFalse(controller.unlockPillVisible)
        assertTrue(controller.chromeVisible)
    }

    @Test
    fun theGestureReadoutClearsItselfShortlyAfterTheGesture() = runTest {
        val controller = PlayerScreenController(backgroundScope)

        controller.showHud(GestureHudKind.Volume, 0.4f)
        assertEquals(GestureHudValue(GestureHudKind.Volume, 0.4f), controller.hud)

        advanceTimeBy(400L)
        assertEquals(GestureHudValue(GestureHudKind.Volume, 0.4f), controller.hud)

        advanceTimeBy(300L)
        assertNull(controller.hud)
    }

    @Test
    fun theGestureReadoutClampsItsValue() = runTest {
        val controller = PlayerScreenController(backgroundScope)

        controller.showHud(GestureHudKind.Brightness, 1.7f)

        assertEquals(1f, controller.hud?.value)
    }

    @Test
    fun theUpNextCountdownAdvancesExactlyOnce() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        var advances = 0

        controller.showUpNext(onAdvance = { advances += 1 })
        assertEquals(UpNextSeconds, controller.upNextSecondsRemaining)

        advanceTimeBy(UpNextSeconds * 1_000L + 500L)

        assertEquals(1, advances)
    }

    @Test
    fun playNowBeatsTheCountdownAndTheCountdownCannotFireAfterIt() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        var advances = 0
        controller.showUpNext(onAdvance = { advances += 1 })

        // The exact race the old player had: tapping on the final tick, with a
        // timer already in flight, advanced twice.
        advanceTimeBy(7_500L)
        controller.advanceToNext()
        advanceTimeBy(5_000L)

        assertEquals(1, advances)
        assertNull(controller.upNextSecondsRemaining)
    }

    @Test
    fun cancellingStopsTheCountdownFromEverAdvancing() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        var advances = 0
        controller.showUpNext(onAdvance = { advances += 1 })

        advanceTimeBy(3_000L)
        controller.dismissUpNext()
        advanceTimeBy(10_000L)

        assertEquals(0, advances)
        assertNull(controller.upNextSecondsRemaining)
    }

    @Test
    fun aClaimedAdvanceCannotBeRestarted() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        var advances = 0
        controller.showUpNext(onAdvance = { advances += 1 })
        controller.advanceToNext()

        // A second NaturalEnd, or a second tap, must not reopen the card.
        controller.showUpNext(onAdvance = { advances += 1 })
        advanceTimeBy(10_000L)

        assertNull(controller.upNextSecondsRemaining)
        assertEquals(1, advances)
    }

    @Test
    fun pictureInPictureHandoffClearsThePanelsAndReturningRestoresTheChrome() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.toggleEpisodeDrawer()

        controller.prepareForPictureInPicture()

        assertFalse(controller.chromeVisible)
        assertFalse(controller.episodeDrawerOpen)

        controller.exitPictureInPicture()
        assertTrue(controller.chromeVisible)
    }

    @Test
    fun usingTheTransportBringsTheChromeBackAndRearmsTheTimer() = runTest {
        val controller = PlayerScreenController(backgroundScope)
        controller.toggleChrome()
        assertFalse(controller.chromeVisible)

        controller.onTransportUsed()
        assertTrue(controller.chromeVisible)

        advanceTimeBy(2_000L)
        assertTrue(controller.chromeVisible, "the timer should have restarted, not resumed")

        advanceTimeBy(2_000L)
        assertFalse(controller.chromeVisible)
    }
}
