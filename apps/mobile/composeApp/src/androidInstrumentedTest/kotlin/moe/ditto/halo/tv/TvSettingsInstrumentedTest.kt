package moe.ditto.halo.tv

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings with the remote alone: a playback default and a TV-only setting
 * survive a relaunch, an addon is added and removed without an account, and
 * signing out returns to the sign-in screen. Needs the fixture API (see
 * [TvFixture]).
 */
@RunWith(AndroidJUnit4::class)
class TvSettingsInstrumentedTest {
    @get:Rule(order = 0)
    val composeRule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<TvActivity>(TvFixture.launchIntent())

    private val remote = TvRemote(composeRule)

    private fun downTo(label: String) = moveTo(remote, label)

    private fun openSettings() = openSettingsFromHome(remote, composeRule)

    private fun switchState(title: String): String? =
        composeRule.onAllNodes(hasText(title, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription), useUnmergedTree = false)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.get(SemanticsProperties.StateDescription)

    private fun openPlayback() {
        downTo("Playback")
        // Into the pane, beside the section: whichever row is nearest.
        remote.right()
        moveTo(remote, "Default audio language")
    }

    @Test
    fun aPlaybackDefaultAndATvSettingSurviveARelaunch() {
        TvFixture.signIn(remote, composeRule)
        openSettings()
        openPlayback()

        // Default subtitles: English, from its panel.
        downTo("Default subtitles")
        remote.select()
        remote.waitForFocus(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        downTo("English")
        remote.select()
        remote.waitFor(hasText("English"))

        // Match frame rate: off.
        downTo("Match frame rate")
        assertTrue(switchState("Match frame rate") == "On")
        remote.select()
        composeRule.waitUntil(5_000) { switchState("Match frame rate") == "Off" }

        // A fresh launch of the app, signed in as it was left (the test's
        // own launch resets the session, so not that one).
        activityRule.scenario.close()
        val relaunch = Intent(ApplicationProvider.getApplicationContext<Context>(), TvActivity::class.java)
            .putExtra("serverUrl", TvFixture.ServerUrl)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<TvActivity>(relaunch)
        remote.leaveTouchMode()
        openSettings()
        openPlayback()
        remote.waitFor(hasText("English"))
        assertTrue("frame rate setting came back as ${switchState("Match frame rate")}", switchState("Match frame rate") == "Off")

        // Back as they were: the fixture server is shared by every test.
        downTo("Default subtitles")
        remote.select()
        // The panel opens on the current choice.
        remote.waitForFocusOn("English")
        downTo("Off")
        remote.select()
        downTo("Match frame rate")
        remote.select()
        composeRule.waitUntil(5_000) { switchState("Match frame rate") == "On" }
    }

    @Test
    fun signingOutAsksFirstAndReturnsToSignIn() {
        TvFixture.signIn(remote, composeRule)
        openSettings()
        remote.right()
        remote.waitForFocusOn("Sign out")
        remote.select()
        remote.waitFor(hasText("Sign out of Halo?"))
        // The safe choice comes first.
        remote.waitForFocusOn("Cancel")
        remote.right()
        remote.waitForFocusOn("Sign out")
        remote.select()
        remote.waitForText("Use a username and password", 15_000)
    }
}

/**
 * Settings without an account: the addon list is the TV's own. Separate
 * class, as it launches without signing in.
 */
@RunWith(AndroidJUnit4::class)
class TvSettingsWithoutAccountInstrumentedTest {
    private val launchIntent = Intent(ApplicationProvider.getApplicationContext<Context>(), TvActivity::class.java)
        .apply {
            putExtra("serverUrl", TvFixture.ServerUrl)
            putExtra("resetSession", true)
        }

    @get:Rule(order = 0)
    val composeRule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<TvActivity>(launchIntent)

    private val remote = TvRemote(composeRule)

    @Test
    fun anAddonIsAddedAndRemovedWithTheRemote() {
        remote.leaveTouchMode()
        remote.waitForFocusOn("Use a username and password", 15_000)
        remote.down()
        remote.waitForFocusOn("Continue without an account")
        remote.select()
        openSettingsFromHome(remote, composeRule)
        remote.down()
        remote.waitForFocusOn("Addons")
        remote.waitFor(hasText("Add an addon"), 20_000)
        remote.right()

        TvFixture.fill(remote, composeRule, "${TvFixture.ServerUrl}/dev/addons/subs/manifest.json")
        remote.waitForText("Fixture Subs", 20_000)

        repeat(15) { if (!remote.focused().contains("Fixture Subs")) remote.down() }
        remote.waitForFocusOn("Fixture Subs")
        remote.select()
        remote.waitForFocusOn("catalogs")
        remote.down()
        remote.waitForFocusOn("Remove addon")
        remote.select()
        remote.waitForFocusOn("Cancel")
        remote.right()
        remote.waitForFocusOn("Remove")
        remote.select()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Fixture Subs")).fetchSemanticsNodes().isEmpty()
        }
    }
}

/**
 * Presses Down until [label] has focus, then Up if it was above: rows are
 * reached by name, wherever focus entered a list.
 */
internal fun moveTo(remote: TvRemote, label: String, presses: Int = 12) {
    repeat(presses) {
        if (remote.focused().contains(label)) return
        remote.down()
    }
    repeat(presses * 2) {
        if (remote.focused().contains(label)) return
        remote.up()
    }
    remote.waitForFocusOn(label)
}

/** From Home, through the rail, to Settings with focus on its first section. */
internal fun openSettingsFromHome(remote: TvRemote, composeRule: ComposeTestRule) {
    composeRule.waitUntil(15_000) { remote.railShows("Home") }
    remote.waitForSteadyFocus()
    remote.left()
    // Into the rail, then down to Settings by name.
    moveTo(remote, "Settings")
    remote.select()
    composeRule.waitUntil(5_000) { remote.railShows("Settings") }
    remote.waitForFocusOn("Account")
}
