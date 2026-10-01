package moe.ditto.halo

import android.content.Context
import android.content.Intent
import java.io.File
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ditto.halo.player.SubtitleFontLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android parallel to the iOS XCUITests (OwnershipUITests + PlaybackUITests):
 * drives the REAL [MainActivity] — real libmpv core over the fixture server — by
 * Compose semantics (content descriptions + text), asserting playback and the
 * core/view ownership invariants without a single hard-coded coordinate.
 *
 * Requires: one local-auth fixture server on the host, serving both auth and
 * media, plus `adb reverse tcp:18788 tcp:18788`. The launch overrides below
 * point both routes at that same fixture, so this class never silently falls
 * back to the unrelated default server on 18787.
 */
@RunWith(AndroidJUnit4::class)
class PlayerOwnershipInstrumentedTest {
    private val launchIntent = Intent(
        ApplicationProvider.getApplicationContext<Context>(),
        MainActivity::class.java,
    ).apply {
        putExtra("serverUrl", "http://127.0.0.1:18788")
        putExtra("mediaHttpBase", "http://127.0.0.1:18788/media")
        putExtra("resetSession", true)
    }

    @get:Rule(order = 0)
    val rule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<MainActivity>(launchIntent)

    @Test
    fun initializedCoreIsNotMuted() {
        activityRule.scenario.onActivity { activity ->
            val muted = activity.playerMutedForTest()
            assertNotNull("initialized libmpv core did not expose its mute property", muted)
            assertFalse("initialized libmpv core is muted", muted == true)
        }
    }

    @Test
    fun activityRecreationStartsAUsableCoreWithoutStaleCallbacks() {
        gotoPlayer()
        rule.waitUntil(25_000) {
            rule.onAllNodes(hasText("Status: Playing", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        activityRule.scenario.recreate()

        // Recreation destroys a live SurfaceView while the old host is winding
        // down. The replacement host must initialize independently of that
        // close queue. A stale callback cannot satisfy this read because the
        // seam belongs to the new MainActivity instance.
        rule.waitUntil(10_000) {
            var initialized = false
            activityRule.scenario.onActivity { initialized = it.playerMutedForTest() != null }
            initialized
        }
    }

    @Test
    fun everyBundledSubtitleFamilyUnpacksForLibass() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = SubtitleFontLibrary.prepare(context)

        assertNotNull("subtitle font directory was not prepared", directory)
        assertEquals(
            setOf("Inter", "Source Serif 4", "JetBrains Mono"),
            SubtitleFontLibrary.bundledFamilies(),
        )
        assertEquals(
            setOf(
                "inter_regular.otf",
                "inter_bold.otf",
                "sourceserif4_regular.otf",
                "sourceserif4_bold.otf",
                "jetbrainsmono_regular.ttf",
            ),
            File(directory!!).list().orEmpty().toSet(),
        )
    }

    @After
    fun leavePlayerShellCleanly() {
        val gateNodes = rule.onAllNodes(hasContentDescription("Gate"))
            .fetchSemanticsNodes()
        if (gateNodes.isEmpty()) return

        // Gate awaits the same decoder wind-down used by the real player screen
        // before removing the SurfaceView, so ActivityScenarioRule can close it.
        rule.onNodeWithContentDescription("Gate").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasContentDescription("Player shell"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun gotoPlayer() {
        rule.onNodeWithContentDescription("Continue").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasContentDescription("Open gate menu")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Open gate menu").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasContentDescription("Player shell")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Player shell").performClick()
        // Wait for the surface to attach (view id becomes concrete).
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("view-1", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Re-reads the diagnostics snapshot until [substring] appears; the "recreate
     *  core" op is async (off the UI thread, so it cannot ANR), so the shell needs
     *  a refresh to observe the new core — exactly what a user would tap. */
    private fun refreshUntil(substring: String, timeoutMs: Long = 25_000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            rule.onNodeWithContentDescription("Refresh counters").performClick()
            rule.waitForIdle()
            if (rule.onAllNodes(hasText(substring, substring = true)).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(250)
        }
    }

    @Test
    fun playbackReachesPlayingWithTracksAndLiveSubtitleScale() {
        gotoPlayer()

        // Real libmpv playback over the fixture reaches Playing.
        rule.waitUntil(25_000) {
            rule.onAllNodes(hasText("Status: Playing", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        // Embedded tracks were parsed and surfaced through the neutral boundary.
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("1 audio", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasText("1 subtitle", substring = true)).assertExists()

        // Live subtitle scale applies to the running core (no recreation): the
        // button label reflects the new scale AND the core id is unchanged.
        rule.onNodeWithContentDescription("Scale 1.0").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasContentDescription("Scale 1.5")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasText("Core instance: core-1", substring = true)).assertExists()
        rule.onNode(hasText("create 1", substring = true)).assertExists()
    }

    @Test
    fun replacingPlayingFileDoesNotFailTheReplacement() {
        gotoPlayer()
        rule.waitUntil(25_000) {
            rule.onAllNodes(hasText("Status: Playing", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // This is the production failure sequence: loadfile ends the old file
        // before starting the replacement. The Android binding does not expose
        // mpv_end_file_reason, so the old END_FILE used to make the presenter
        // terminally Failed even while the replacement's audio kept playing.
        rule.onNodeWithContentDescription("Load ASS (HTTP)").performClick()
        refreshUntil("Playback: load 2")

        rule.waitUntil(25_000) {
            rule.onAllNodes(hasText("Status: Playing", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        // Give the replaced file's delayed END_FILE enough time to arrive. A
        // check made immediately after the new FILE_LOADED event would miss the
        // exact race this test is meant to catch.
        Thread.sleep(1_500)
        rule.waitForIdle()

        rule.onNode(hasText("Status: Playing", substring = true)).assertExists()
        rule.onNode(hasText("Status: Failed", substring = true)).assertDoesNotExist()
    }

    /**
     * DEVICE-ONLY. Recreating the core exercises mpv core-lifecycle churn
     * (`mpv.detachSurface()` + a second `MPVLib.create`/`init`), which hangs on
     * the emulator's software-GL (SwiftShader) + emulated-MediaCodec path — the
     * native surface-detach / thread-join never returns (traced: the executor
     * logs "enqueue" then never "swapped to core-2"). Single-core playback is
     * unaffected (see the passing test above). The core-recreate-keeps-view
     * ownership property is already proven on the iOS sim; verify on a real
     * Android device, then drop @Ignore.
     */
    @Ignore("mpv core-lifecycle churn hangs on emulator software-GL; verify on a real device")
    @Test
    fun recreateCoreChangesCoreIdButKeepsView() {
        gotoPlayer()

        // Baseline ownership: one core, one view.
        rule.onNode(hasText("Core instance: core-1", substring = true)).assertExists()
        rule.onNode(hasText("Player view instance: view-1", substring = true)).assertExists()

        // Recreate the core.
        rule.onNodeWithContentDescription("Explicitly recreate player core").performClick()

        // The new core is created and swapped in (create 2) …
        refreshUntil("Core instance: core-2")
        rule.onNode(hasText("Core instance: core-2", substring = true)).assertExists()
        rule.onNode(hasText("create 2", substring = true)).assertExists()

        // … while the SurfaceView identity is preserved — the ownership invariant.
        rule.onNode(hasText("Player view instance: view-1", substring = true)).assertExists()

        // Old-core teardown (destroy count) is intentionally NOT asserted: on a
        // real device mpv_terminate_destroy completes and destroy increments, but
        // on the emulator's software-GL/emulated-codec path it hangs joining mpv's
        // render/decode threads. It is a device-verification item, consistent with
        // the standing "test playback on real hardware" rule.
    }
}
