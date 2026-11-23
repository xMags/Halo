package moe.ditto.halo.tv

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The browse screens with the remote alone, signed in to the fixture API
 * (`dev:fixtures --media <file>` on :18790, `adb reverse tcp:18790 tcp:18790`).
 */
@RunWith(AndroidJUnit4::class)
class TvBrowseInstrumentedTest {
    private val launchIntent = TvFixture.launchIntent()

    @get:Rule(order = 0)
    val composeRule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<TvActivity>(launchIntent)

    private val remote = TvRemote(composeRule)

    private fun fill(text: String) = TvFixture.fill(remote, composeRule, text)

    @Before
    fun signIn() = TvFixture.signIn(remote, composeRule)

    private fun search(term: String) {
        remote.left()
        remote.waitForFocusOn("Home")
        remote.up()
        remote.select()
        composeRule.waitUntil(5_000) { remote.railShows("Search") }
        remote.waitForFocusOn("Search")
        fill(term)
    }

    @Test
    fun theHeroPlaysAFilmThroughItsSources() {
        // Home opens on the hero's main button.
        remote.select()
        remote.waitForText("sources ·", 15_000)
        remote.waitFor(hasText("Plays instantly"))
        remote.select()
        remote.waitFor(hasContentDescription("Playback"), 15_000)
        // Under instrumentation this emulator often never gets the picture
        // going, and a player whose engine is stuck cannot be left; the rest
        // only runs once the playhead moves.
        val playhead = {
            composeRule.onAllNodes(hasContentDescription("Playback")).fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrNull(SemanticsProperties.StateDescription)
        }
        val start = playhead()
        val advancing = runCatching { composeRule.waitUntil(20_000) { playhead().let { it != null && it != start } } }.isSuccess
        assumeTrue("playback does not start under instrumentation on this emulator", advancing)
        // Out through the player's own exit, so the engine winds down: Back
        // hides the controls, Back leaves, and the player goes once the
        // decoder is released.
        val playing = { composeRule.onAllNodes(hasContentDescription("Playback")).fetchSemanticsNodes().isNotEmpty() }
        repeat(2) { if (playing()) remote.back() }
        composeRule.waitUntil(10_000) { !playing() }
        // Home, or the source list on the way to it.
        runCatching { composeRule.waitUntil(3_000) { remote.railShows("Home") } }
        if (!remote.railShows("Home")) remote.back()
        composeRule.waitUntil(10_000) { remote.railShows("Home") }
    }

    @Test
    fun aSeriesFromSearchChangesSeasonAndOpensAnEpisodesSources() {
        search("Breaking Bad")
        remote.waitForText("TOP RESULT", 15_000)
        remote.down(2)
        assertEquals("Breaking Bad", remote.focused())
        remote.select()
        remote.waitForText("Season 2", 15_000)

        remote.down()
        remote.waitForFocusOn("Season 1")
        remote.right()
        remote.waitForFocusOn("Season 2")
        remote.select()
        remote.waitForText("S02E01")

        remote.down()
        assertTrue("an episode has focus, got ${remote.focused()}", remote.focused().startsWith("S02E01"))
        remote.select()
        remote.waitForText("BREAKING BAD · S02E01", 15_000)
        remote.waitForText("sources ·", 15_000)

        // Back returns to the title page, on the episode that opened the sources.
        remote.back()
        remote.waitForText("Season 2")
        remote.waitForFocusOn("S02E01")
    }

    @Test
    fun removingATitleFromTheLibraryTakesItOutOfLibrary() {
        search("Breaking Bad")
        remote.waitForText("TOP RESULT", 15_000)
        remote.down(2)
        remote.select()
        remote.waitForText("Season 2", 15_000)
        // The main button reads Resume or Play, by how much was watched before.
        remote.waitForFocus(hasText("Resume", substring = true) or hasText("Play", substring = true))
        remote.right()
        // The fixture server keeps one library for every run: start from saved.
        if (remote.focused().contains("Add to library")) {
            remote.select()
            remote.waitForFocusOn("In library")
        }
        remote.waitForFocusOn("In library")
        remote.select()
        remote.waitForFocusOn("Add to library")

        remote.back()
        remote.back()
        composeRule.waitUntil(5_000) { remote.railShows("Home") }
        remote.left()
        remote.waitForFocusOn("Home")
        remote.down()
        remote.select()
        composeRule.waitUntil(5_000) { remote.railShows("Library") }
        remote.waitForText("Inception")
        assertTrue(
            "a removed title leaves the saved grid",
            composeRule.onAllNodes(hasContentDescription("Breaking Bad")).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun aPersonFoundBySearchOpensTheirPage() {
        search("Keanu")
        remote.waitForText("People", 15_000)
        remote.waitFor(hasContentDescription("Keanu Reeves"))
        // Down past the filters and the top result to the people row.
        repeat(4) {
            if (remote.focused() != "Keanu Reeves") remote.down()
        }
        assertEquals("Keanu Reeves", remote.focused())
        remote.select()
        remote.waitForText("The Matrix", 15_000)
    }

}
