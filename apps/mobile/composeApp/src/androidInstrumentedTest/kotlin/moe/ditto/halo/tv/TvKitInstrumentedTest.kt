package moe.ditto.halo.tv

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the TV kit gallery with real key events, the way a remote does, and
 * checks where focus goes and what each key does.
 */
@RunWith(AndroidJUnit4::class)
class TvKitInstrumentedTest {
    private val launchIntent = Intent(
        ApplicationProvider.getApplicationContext<Context>(),
        TvActivity::class.java,
    ).apply { putExtra("tvKitGallery", true) }

    @get:Rule(order = 0)
    val composeRule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<TvActivity>(launchIntent)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** A remote never puts the window in touch mode; instrumentation does. */
    @Before
    fun leaveTouchMode() {
        instrumentation.setInTouchMode(false)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("TV kit")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(button("Play")).assertIsFocused()
    }

    private fun press(vararg keyCodes: Int) {
        keyCodes.forEach { code ->
            instrumentation.sendKeyDownUpSync(code)
            composeRule.waitForIdle()
        }
    }

    /** A held key: down, a long-press repeat, then up. */
    private fun hold(keyCode: Int) {
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        instrumentation.sendKeySync(
            KeyEvent.changeTimeRepeat(
                KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0),
                downTime + 600,
                1,
                KeyEvent.FLAG_LONG_PRESS,
            ),
        )
        instrumentation.sendKeySync(KeyEvent(downTime, downTime + 700, KeyEvent.ACTION_UP, keyCode, 0))
        composeRule.waitForIdle()
    }

    private fun lastAction(): String =
        composeRule.onNode(hasContentDescription("Last action"))
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    private fun focusedDescription(): String {
        val nodes = composeRule.onAllNodes(isFocused()).fetchSemanticsNodes()
        assertEquals("exactly one focused node", 1, nodes.size)
        return nodes.single().config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .joinToString()
            .ifEmpty { nodes.single().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text } }
    }

    private fun button(text: String): SemanticsMatcher = hasText(text) and hasClickAction()

    @Test
    fun selectClicksAndHoldOrMenuOpensTheMenu() {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("Clicked Play", lastAction())

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val poster = focusedDescription()
        assertTrue("focus entered the poster row, got $poster", poster.startsWith("Poster "))

        hold(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("Menu on $poster", lastAction())

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("Clicked $poster", lastAction())

        press(KeyEvent.KEYCODE_MENU)
        assertEquals("Menu on $poster", lastAction())
    }

    @Test
    fun aRowGivesFocusBackToTheCardThatLastHadIt() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
        val remembered = focusedDescription()
        assertTrue(remembered.startsWith("Poster "))

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("moved to the next row", focusedDescription().startsWith("Wide "))

        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(remembered, focusedDescription())
    }

    @Test
    fun aDialogKeepsFocusUntilBackAndReturnsItToItsOpener() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.onNode(button("Open dialog")).assertIsFocused()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        composeRule.onNode(button("Cancel")).assertIsFocused()

        // Nothing outside the dialog can take focus.
        press(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        composeRule.onNode(button("Cancel")).assertIsFocused()

        press(KeyEvent.KEYCODE_BACK)
        assertEquals("Dismissed dialog", lastAction())
        composeRule.onNode(button("Open dialog")).assertIsFocused()
    }

    @Test
    fun aSidePanelClosesWithBackAndReturnsFocus() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.onNode(button("Open panel")).assertIsFocused()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("Picked English", lastAction())
        composeRule.onNode(button("Open panel")).assertIsFocused()
    }
}
