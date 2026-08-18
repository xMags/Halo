package moe.ditto.halo

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.WindowManager
import java.io.File
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.center
import androidx.compose.ui.test.click
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Shipping player flows over the real fixture API and real libmpv core.
 *
 * Requires `dev:fixtures --media <file>` on port 18790 and
 * `adb reverse tcp:18790 tcp:18790`. The launch is reset for every test and
 * signs into the local fixture account, so no test inherits a route, token or
 * playback position from another one.
 *
 * No coordinate is hard-coded. The one touch on the video uses the root's
 * measured centre, which is what a viewer means by "tap the picture" and keeps
 * working across emulator sizes and orientations.
 */
@RunWith(AndroidJUnit4::class)
class PlayerScreenInstrumentedTest {
    private val launchIntent = Intent(
        ApplicationProvider.getApplicationContext<Context>(),
        MainActivity::class.java,
    ).apply {
        putExtra("serverUrl", "http://127.0.0.1:18790")
        putExtra("resetSession", true)
    }

    @get:Rule(order = 0)
    val rule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<MainActivity>(launchIntent)

    @Test
    fun chromeHidesAfterPlaybackSettles() {
        gotoPlayer()
        showChrome()
        rule.onNodeWithText("SUBTITLES").assertExists()

        rule.waitUntil(6_000) { nodesWithText("SUBTITLES").isEmpty() }

        assertTrue(nodesWithText("SUBTITLES").isEmpty())
    }

    @Test
    fun trackSwitchKeepsPlaybackPositionAndDoesNotReloadTheScreen() {
        gotoPlayer()
        showChrome()
        rule.onNodeWithText("SUBTITLES").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(OffRow).fetchSemanticsNodes().isNotEmpty() }

        // On and then off, so one of the two is a real switch whichever track
        // the file opened with, and each has to reach the chip: the chip reads
        // the engine's own selection, so it only moves if the core reports the
        // change. It does not when tracks are re-read on count changes alone,
        // which turning subtitles off does not cause.
        rule.onNode(EmbeddedSubtitleRow).performScrollTo().performClick()
        rule.waitUntil(5_000) { chipReads("eng", ".ASS") }

        // An open rail suppresses chrome auto-hide, so the dynamic elapsed
        // value remains observable while the real player applies the track.
        val before = waitForElapsedAtLeast(5)
        rule.onNode(OffRow).performScrollTo().performClick()
        rule.waitUntil(5_000) { chipReads("Off") }

        val after = elapsedSeconds()
        assertTrue("position reset from $before to $after after switching a track", after >= before)

        rule.onNodeWithContentDescription("Close playback options").performClick()
        showChrome()
        // The same player is still visible, rather than a picker or a new load.
        rule.onNodeWithText("The Matrix").assertExists()
    }

    @Test
    fun authenticatedExternalSubtitleLoadsWithoutResettingPlaybackPosition() {
        activityRule.scenario.onActivity { activity ->
            File(activity.cacheDir, "halo-subtitles").deleteRecursively()
        }
        gotoPlayer()
        showChrome()
        rule.onNodeWithText("SUBTITLES").performClick()
        rule.waitUntil(10_000) { nodesWithText("Fixture Subs").isNotEmpty() }

        val before = waitForElapsedAtLeast(5)
        // Scrolled to rather than clicked where it happens to be: the addon
        // results sit under their language's header, far enough down a short
        // landscape rail that a click aimed at the node's own centre lands
        // outside the window and is swallowed.
        rule.onAllNodes(hasText("Fixture Subs") and hasClickAction())
            .onFirst()
            .performScrollTo()
            .performClick()
        rule.waitUntil(10_000) {
            var cached = false
            activityRule.scenario.onActivity { activity ->
                cached = File(activity.cacheDir, "halo-subtitles")
                    .listFiles()
                    .orEmpty()
                    .any { it.isFile && it.length() > 0L }
            }
            cached
        }

        val after = elapsedSeconds()
        assertTrue("position reset from $before to $after after loading an external subtitle", after >= before)
    }

    @Test
    fun backWindsDownBeforeThePlayerAndItsDeviceClaimsDisappear() {
        gotoPlayer()
        // The bars go on the system's schedule rather than the claim's, so this
        // waits for the window instead of reading it the instant after.
        rule.waitUntil(5_000) { systemBarsHidden() }
        showChrome()

        rule.onNodeWithContentDescription("Back").performClick()
        // Back lands on the title, not on Home: entering the player pops the
        // sources route it was chosen from.
        rule.waitUntil(10_000) { nodesWithText(SourcesLabel).isNotEmpty() }

        activityRule.scenario.onActivity { activity ->
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
            assertFalse(
                "player left FLAG_KEEP_SCREEN_ON behind",
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
            )
        }
        rule.waitUntil(5_000) { systemBarsVisible() }
        assertTrue("player left the system bars hidden behind it", systemBarsVisible())
    }

    /**
     * Read off the real window, not off the port's own bookkeeping.
     *
     * The two bars are named one at a time rather than asked for as
     * `Type.systemBars()`: that mask includes the caption bar, which a phone
     * never has, so a combined query answers "not visible" no matter what the
     * status and navigation bars are doing.
     */
    private fun statusAndNavigationBars(): List<Boolean> {
        var bars = emptyList<Boolean>()
        activityRule.scenario.onActivity { activity ->
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView) ?: return@onActivity
            bars = listOf(
                insets.isVisible(WindowInsetsCompat.Type.statusBars()),
                insets.isVisible(WindowInsetsCompat.Type.navigationBars()),
            )
        }
        return bars
    }

    private fun systemBarsVisible(): Boolean = statusAndNavigationBars().let { it.size == 2 && it.all { on -> on } }

    private fun systemBarsHidden(): Boolean = statusAndNavigationBars().let { it.size == 2 && it.none { on -> on } }

    private fun gotoPlayer() {
        rule.onNodeWithContentDescription("Continue").performClick()
        rule.waitUntil(10_000) { nodesWithText("Username").isNotEmpty() }
        rule.onNode(hasText("Username") and hasSetTextAction()).performTextInput("admin")
        rule.onNode(hasText("Password") and hasSetTextAction()).performTextInput("fixture-pass")
        rule.onNodeWithContentDescription("Sign In").performClick()

        // In through the hero's artwork rather than its Play button. The button
        // sits at the bottom of a landscape hero, under the floating tab bar
        // that is drawn over it: a click aimed at the button lands on the tab,
        // which is invisible to the test because occlusion is not part of what
        // "displayed" means here. The artwork's centre is clear, and the detail
        // screen it opens has no tab bar over its Sources button.
        rule.waitUntil(15_000) { nodesWithText(FeaturedTitle).isNotEmpty() }
        rule.onAllNodes(hasText(FeaturedTitle)).onFirst().performClick()
        rule.waitUntil(10_000) { nodesWithText(SourcesLabel).isNotEmpty() }
        rule.onAllNodes(hasText(SourcesLabel)).onFirst().performScrollTo().performClick()

        rule.waitUntil(10_000) { nodesWithText(FirstSourceFilename).isNotEmpty() }
        rule.onAllNodes(hasText(FirstSourceFilename)).onFirst().performClick()

        rule.waitUntil(15_000) { nodesWithContentDescription("Back").isNotEmpty() }
        showChrome()
        rule.waitUntil(25_000) {
            nodesWithContentDescription("Elapsed", substring = true).isNotEmpty()
        }
    }

    private fun showChrome() {
        if (nodesWithText("SUBTITLES").isNotEmpty()) return
        rule.onRoot().performTouchInput { click(center) }
        rule.waitUntil(3_000) { nodesWithText("SUBTITLES").isNotEmpty() }
    }

    private fun waitForElapsedAtLeast(seconds: Int): Int {
        rule.waitUntil(15_000) { elapsedSeconds() >= seconds }
        return elapsedSeconds()
    }

    private fun elapsedSeconds(): Int {
        val node = nodesWithContentDescription("Elapsed", substring = true).firstOrNull() ?: return -1
        val description = node.config[SemanticsProperties.ContentDescription].firstOrNull() ?: return -1
        val timecode = description.removePrefix("Elapsed ")
        val parts = timecode.split(':').mapNotNull(String::toIntOrNull)
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> -1
        }
    }

    /**
     * The chip and the rail row both carry the word a track is named by, so
     * every matcher here pairs it with the label only one of them has.
     */
    private fun chipReads(value: String, badge: String? = null): Boolean {
        val chip = hasText("SUBTITLES") and hasText(value)
        val matcher = badge?.let { chip and hasText(it) } ?: chip
        return rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }

    private fun nodesWithText(text: String) =
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes()

    private fun nodesWithContentDescription(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasContentDescription(text, substring = substring)).fetchSemanticsNodes()

    private companion object {
        /** The rail's own rows, told apart from the chips by their detail line. */
        val OffRow = hasText("Off") and hasText("No subtitles")
        val EmbeddedSubtitleRow = hasText("eng") and hasText("eng · ASS")

        /** The fixture catalog's first movie, which is also the Home hero. */
        const val FeaturedTitle = "The Matrix"
        const val SourcesLabel = "Sources"
        const val FirstSourceFilename = "tt0133093.2160p.WEB-DL.DDP5.1.HDR.HEVC.mkv"
    }
}
