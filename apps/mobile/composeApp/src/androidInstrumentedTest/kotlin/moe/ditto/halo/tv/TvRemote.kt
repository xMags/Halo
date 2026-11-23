package moe.ditto.halo.tv

import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry

/**
 * A remote for instrumented TV tests: real key events through the window, as
 * a remote sends them, plus reads of where focus is.
 */
internal class TvRemote(private val compose: ComposeTestRule) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** A remote never puts the window in touch mode; instrumentation does. */
    fun leaveTouchMode() {
        instrumentation.setInTouchMode(false)
        compose.waitForIdle()
    }

    fun press(vararg keyCodes: Int) {
        keyCodes.forEach { code ->
            instrumentation.sendKeyDownUpSync(code)
            compose.waitForIdle()
            Log.i(LogTag, "${KeyEvent.keyCodeToString(code)} -> ${focused()}")
        }
    }

    fun up(times: Int = 1) = repeat(times) { press(KeyEvent.KEYCODE_DPAD_UP) }
    fun down(times: Int = 1) = repeat(times) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
    fun left(times: Int = 1) = repeat(times) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
    fun right(times: Int = 1) = repeat(times) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
    fun select() = press(KeyEvent.KEYCODE_DPAD_CENTER)
    fun back() = press(KeyEvent.KEYCODE_BACK)

    fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            captureScreen()
            throw AssertionError("never saw ${matcher.description}; focus is on \"${focused()}\"", timeout)
        }
    }

    /**
     * Saves the screen to `/data/local/tmp/tv-test-failure-<time>.png`
     * through the shell, which outlives the test app being uninstalled after
     * the run.
     */
    fun captureScreen() {
        runCatching {
            val path = "/data/local/tmp/tv-test-failure-${System.currentTimeMillis()}.png"
            instrumentation.uiAutomation.executeShellCommand("screencap -p $path").close()
        }
    }

    fun waitForText(text: String, timeoutMs: Long = 10_000) = waitFor(hasText(text, substring = true), timeoutMs)

    /** Waits until the focused node matches [matcher]; a timeout names what has focus instead. */
    fun waitForFocus(matcher: SemanticsMatcher, timeoutMs: Long = 5_000) {
        try {
            compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            captureScreen()
            throw AssertionError("focus never reached ${matcher.description}; it is on \"${focused()}\"", timeout)
        }
    }

    fun waitForFocusOn(description: String, timeoutMs: Long = 5_000) =
        waitForFocus(hasContentDescription(description, substring = true) or hasText(description, substring = true), timeoutMs)

    /** The focused node's description, or its text when it has none. */
    fun focused(): String {
        val nodes = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        val node = nodes.singleOrNull() ?: return "<${nodes.size} focused>"
        val description = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString()
        if (description.isNotEmpty()) return description
        return node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
    }

    /** True when a rail entry (the only controls with a selected state there) has focus. */
    fun railHasFocus(): Boolean =
        compose.onAllNodes(isFocused() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .fetchSemanticsNodes()
            .isNotEmpty()

    /** True when the rail entry named [label] is the current destination. */
    fun railShows(label: String): Boolean =
        compose.onAllNodes(hasContentDescription(label) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .fetchSemanticsNodes()
            .isNotEmpty()
}

private const val LogTag = "TvRemote"
