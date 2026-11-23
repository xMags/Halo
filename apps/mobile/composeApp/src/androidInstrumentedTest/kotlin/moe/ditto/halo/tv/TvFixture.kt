package moe.ditto.halo.tv

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The fixture API the TV tests sign in to: `pnpm --filter @halo/api
 * dev:fixtures --media <file>` on :18790, reached through
 * `adb reverse tcp:18790 tcp:18790`.
 */
internal object TvFixture {
    const val ServerUrl = "http://127.0.0.1:18790"

    fun launchIntent(): Intent = Intent(
        ApplicationProvider.getApplicationContext<Context>(),
        TvActivity::class.java,
    ).apply {
        putExtra("serverUrl", ServerUrl)
        putExtra("resetSession", true)
    }

    /** Opens the focused field's keyboard, replaces its text, and presses the keyboard's action key. */
    fun fill(remote: TvRemote, compose: ComposeTestRule, text: String) {
        remote.select()
        remote.press(KeyEvent.KEYCODE_MOVE_END)
        repeat(24) { remote.press(KeyEvent.KEYCODE_DEL) }
        InstrumentationRegistry.getInstrumentation().sendStringSync(text)
        compose.waitForIdle()
        remote.press(KeyEvent.KEYCODE_ENTER)
    }

    /** Signs in with the password form and waits for Home to give its hero focus. */
    fun signIn(remote: TvRemote, compose: ComposeTestRule) {
        remote.leaveTouchMode()
        remote.waitForFocusOn("Use a username and password")
        remote.select()
        remote.waitForText(ServerUrl)
        remote.down()
        fill(remote, compose, "admin")
        remote.down()
        fill(remote, compose, "fixture-pass")
        compose.waitUntil(15_000) { remote.railShows("Home") }
        // Home hands focus to its hero once the hero is drawn; keys pressed
        // before that would go nowhere. Its button reads Resume or Play, by
        // what earlier runs left half-watched on the shared server.
        remote.waitForFocus(hasText("Resume", substring = true) or hasText("Play", substring = true), 30_000)
    }
}
