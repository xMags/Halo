package moe.ditto.halo.tv

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the TV shell with the remote alone, starting from a clean sign-in:
 * continue without an account, visit every rail destination, and check what
 * Back does on each.
 */
@RunWith(AndroidJUnit4::class)
class TvShellInstrumentedTest {
    private val launchIntent = Intent(
        ApplicationProvider.getApplicationContext<Context>(),
        TvActivity::class.java,
    ).apply { putExtra("resetSession", true) }

    @get:Rule(order = 0)
    val composeRule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<TvActivity>(launchIntent)

    private val remote = TvRemote(composeRule)

    @Before
    fun enterWithoutAnAccount() {
        remote.leaveTouchMode()
        remote.waitForText("Continue without an account")
        remote.waitForFocusOn("Use a username and password")
        remote.down()
        remote.waitForFocusOn("Continue without an account")
        remote.select()
        composeRule.waitUntil(10_000) { remote.railShows("Home") }
    }

    @Test
    fun theRailReachesEveryDestinationAndBackGoesHome() {
        // Left from the screen's first column reaches the rail, on Home.
        remote.left()
        remote.waitForFocusOn("Home")

        remote.up()
        assertEquals("Search", remote.focused())
        remote.select()
        composeRule.waitUntil(5_000) { remote.railShows("Search") }

        // Choosing a destination hands focus to it.
        assertTrue("focus left the rail, got ${remote.focused()}", remote.focused() !in RailLabels)

        remote.left()
        remote.waitForFocusOn("Search")
        remote.down(2)
        assertEquals("Library", remote.focused())
        remote.select()
        composeRule.waitUntil(5_000) { remote.railShows("Library") }

        remote.back()
        composeRule.waitUntil(5_000) { remote.railShows("Home") }

        remote.left()
        remote.waitForFocusOn("Home")
        remote.down(2)
        assertEquals("Settings", remote.focused())
        remote.select()
        composeRule.waitUntil(5_000) { remote.railShows("Settings") }

        remote.back()
        composeRule.waitUntil(5_000) { remote.railShows("Home") }
    }

    @Test
    fun rightFromTheRailReturnsToWhereFocusWas() {
        val before = remote.focused()
        remote.left()
        remote.waitForFocusOn("Home")
        remote.right()
        assertEquals(before, remote.focused())
    }

    @Test
    fun backOnHomeAsksBeforeExiting() {
        remote.back()
        remote.waitFor(hasText("Exit Halo?"))
        // Focus starts on the safe choice.
        remote.waitForFocusOn("Stay")
        remote.back()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Exit Halo?")).fetchSemanticsNodes().isEmpty() }
        assertTrue(remote.railShows("Home"))
    }

    private companion object {
        val RailLabels = setOf("Search", "Home", "Library", "Settings", "Account")
    }
}
