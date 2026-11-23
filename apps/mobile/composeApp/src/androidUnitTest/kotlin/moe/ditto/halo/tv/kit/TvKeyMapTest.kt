package moe.ditto.halo.tv.kit

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvKeyMapTest {
    private fun assertMaps(action: TvAction, vararg keyCodes: Int) {
        keyCodes.forEach { code -> assertEquals(action, TvKeyMap.actionFor(code), "key code $code") }
    }

    @Test
    fun remoteCentreKeyboardEnterAndControllerASelect() {
        assertMaps(
            TvAction.Select,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
        )
    }

    @Test
    fun fireTvMenuKeyOpensTheMenu() {
        assertMaps(TvAction.Menu, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y)
    }

    @Test
    fun backComesFromTheRemoteTheControllerAndEscape() {
        assertMaps(TvAction.Back, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE)
    }

    @Test
    fun mediaKeysMapOneToOne() {
        assertMaps(TvAction.PlayPause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertMaps(TvAction.Play, KeyEvent.KEYCODE_MEDIA_PLAY)
        assertMaps(TvAction.Pause, KeyEvent.KEYCODE_MEDIA_PAUSE)
        assertMaps(TvAction.Stop, KeyEvent.KEYCODE_MEDIA_STOP)
        assertMaps(TvAction.FastForward, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD)
        assertMaps(TvAction.Rewind, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD)
        assertMaps(TvAction.Next, KeyEvent.KEYCODE_MEDIA_NEXT)
        assertMaps(TvAction.Previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    @Test
    fun directionsMapToTheirOwnActions() {
        assertMaps(TvAction.Up, KeyEvent.KEYCODE_DPAD_UP)
        assertMaps(TvAction.Down, KeyEvent.KEYCODE_DPAD_DOWN)
        assertMaps(TvAction.Left, KeyEvent.KEYCODE_DPAD_LEFT)
        assertMaps(TvAction.Right, KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    @Test
    fun unrelatedKeysMeanNothing() {
        assertNull(TvKeyMap.actionFor(KeyEvent.KEYCODE_A))
        assertNull(TvKeyMap.actionFor(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(TvKeyMap.actionFor(KeyEvent.KEYCODE_HOME))
    }
}
