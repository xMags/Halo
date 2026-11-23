package moe.ditto.halo.tv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV player with the remote alone, on the fixture video: play, pause and
 * resume with media keys, seek both ways with the bar, change subtitle and audio from
 * their panels, leave with Back, and find the place kept on the way back in.
 */
@RunWith(AndroidJUnit4::class)
class TvPlayerInstrumentedTest {
    @get:Rule(order = 0)
    val composeRule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<TvActivity>(TvFixture.launchIntent())

    private val remote = TvRemote(composeRule)

    @Before
    fun signIn() = TvFixture.signIn(remote, composeRule)

    /** The playhead in seconds, as the player reports it. */
    private fun position(): Int {
        val node = composeRule.onAllNodes(hasContentDescription("Playback")).fetchSemanticsNodes().single()
        val text = node.config[SemanticsProperties.StateDescription]
        return text.split(':').fold(0) { total, part -> total * 60 + part.trim().toInt() }
    }

    private fun showControls() {
        if (composeRule.onAllNodes(hasContentDescription("Position")).fetchSemanticsNodes().isEmpty()) {
            remote.press(KeyEvent.KEYCODE_DPAD_UP)
        }
        remote.waitFor(hasContentDescription("Position"))
    }

    private fun openPlayer() {
        remote.select()
        remote.waitForText("sources ·", 15_000)
        remote.waitForFocusOn("Plays instantly", 15_000)
        remote.select()
        remote.waitFor(hasContentDescription("Playback"), 15_000)
        showControls()
        val start = position()
        val advancing = runCatching {
            composeRule.waitUntil(20_000) { runCatching { position() > start }.getOrDefault(false) }
        }.isSuccess
        // The Android TV emulator's software renderer never starts the picture
        // under instrumentation (the phone player's suite stops at the same
        // point there); on a device or a GPU-backed emulator it plays.
        assumeTrue("playback does not start under instrumentation on this emulator", advancing)
    }

    /** Back until the player has wound down and gone. */
    private fun leavePlayer() {
        repeat(3) {
            if (composeRule.onAllNodes(hasContentDescription("Playback")).fetchSemanticsNodes().isEmpty()) return
            remote.back()
            Thread.sleep(800)
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Playback")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun theRemotePlaysPausesSeeksChangesTracksAndLeaves() {
        openPlayer()
        // The fixture video is short and the server remembers every run's
        // place, so start from the top: Previous goes back to the start.
        remote.press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        composeRule.waitUntil(10_000) { position() < 10 }

        // Media keys pause and play, whatever has focus.
        remote.press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        remote.waitFor(hasContentDescription("Play"))
        remote.press(KeyEvent.KEYCODE_MEDIA_PLAY)
        remote.waitFor(hasContentDescription("Pause"))

        // Seek: the bar has focus while the controls show; Right builds a
        // target and select seeks to it.
        showControls()
        remote.waitForFocusOn("Position")
        val before = position()
        remote.right(3)
        remote.select()
        composeRule.waitUntil(10_000) { position() >= before + 25 }

        // And back: two presses, twenty seconds.
        showControls()
        remote.waitForFocusOn("Position")
        val ahead = position()
        remote.left(2)
        remote.select()
        composeRule.waitUntil(10_000) { position() <= ahead - 15 }

        // Subtitles: off, from the panel.
        showControls()
        remote.waitForFocusOn("Position")
        remote.down()
        remote.right(2)
        remote.waitForFocusOn("Subtitles")
        remote.select()
        remote.waitForFocusOn("Off")
        remote.select()
        remote.back()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Off")).fetchSemanticsNodes().isEmpty() }

        // Audio: the first track, from the panel.
        showControls()
        remote.waitForFocusOn("Position")
        remote.down()
        remote.right()
        remote.waitForFocusOn("Audio")
        remote.select()
        remote.waitForText("Delay")
        remote.select()
        remote.back()

        val leftAt = position()
        leavePlayer()

        // In again: the place left is where it carries on.
        remote.waitForText("Continue watching", 15_000)
        remote.waitForFocusOn("Resume", 15_000)
        openPlayer()
        assertTrue("resumed at ${position()}, left at $leftAt", position() >= leftAt - 5)
        leavePlayer()
    }
}
