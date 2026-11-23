package moe.ditto.halo.tv

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signs in on the TV with a username and password, using the remote and key
 * presses for typing, against the fixture API (`pnpm --filter @halo/api
 * dev:fixtures`, reached through `adb reverse tcp:18790 tcp:18790`).
 */
@RunWith(AndroidJUnit4::class)
class TvSignInInstrumentedTest {
    private val launchIntent = Intent(
        ApplicationProvider.getApplicationContext<Context>(),
        TvActivity::class.java,
    ).apply {
        putExtra("serverUrl", FixtureServerUrl)
        putExtra("resetSession", true)
    }

    @get:Rule(order = 0)
    val composeRule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<TvActivity>(launchIntent)

    private val remote = TvRemote(composeRule)

    private fun type(text: String) {
        InstrumentationRegistry.getInstrumentation().sendStringSync(text)
        composeRule.waitForIdle()
    }

    /** Opens the focused field's keyboard, replaces its text, and presses the keyboard's action key. */
    private fun fill(text: String) {
        remote.select()
        remote.press(KeyEvent.KEYCODE_MOVE_END)
        repeat(24) { remote.press(KeyEvent.KEYCODE_DEL) }
        type(text)
        remote.press(KeyEvent.KEYCODE_ENTER)
    }

    @Before
    fun openThePasswordForm() {
        remote.leaveTouchMode()
        remote.waitForFocusOn("Use a username and password")
        remote.select()
        remote.waitForFocusOn("Server address")
        remote.waitForText(FixtureServerUrl)
    }

    @Test
    fun aWrongPasswordSaysSoAndTheRightOneOpensTheApp() {
        remote.down()
        remote.waitForFocusOn("Username")
        fill("admin")

        remote.down()
        remote.waitForFocusOn("Password")
        fill("not-the-password")
        remote.waitFor(hasText("invalid", substring = true, ignoreCase = true), 15_000)

        // Still on the form, on the field just submitted.
        remote.waitForFocusOn("Password")
        fill("fixture-pass")
        composeRule.waitUntil(15_000) { remote.railShows("Home") }
        assertTrue(remote.railShows("Home"))
    }

    @Test
    fun backLeavesTheFormForTheChoices() {
        remote.back()
        remote.waitForFocusOn("Use a username and password")
    }

    private companion object {
        const val FixtureServerUrl = "http://127.0.0.1:18790"
    }
}
