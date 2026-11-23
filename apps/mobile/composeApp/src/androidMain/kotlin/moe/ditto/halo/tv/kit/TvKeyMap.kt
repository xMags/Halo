package moe.ditto.halo.tv.kit

import android.view.KeyEvent

/** What a remote, keyboard or game controller key means to Halo on a TV. */
internal enum class TvAction {
    Select,
    Menu,
    Back,
    PlayPause,
    Play,
    Pause,
    Stop,
    FastForward,
    Rewind,
    Next,
    Previous,
    Up,
    Down,
    Left,
    Right,
}

/**
 * Android key codes to [TvAction]s, in one place so every screen reads a remote
 * the same way. Fire TV remotes send the media and Menu keys; game controllers
 * send A for select and B for back.
 */
internal object TvKeyMap {
    fun actionFor(keyCode: Int): TvAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        -> TvAction.Select

        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_BUTTON_Y,
        -> TvAction.Menu

        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_ESCAPE,
        -> TvAction.Back

        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        -> TvAction.PlayPause

        KeyEvent.KEYCODE_MEDIA_PLAY -> TvAction.Play
        KeyEvent.KEYCODE_MEDIA_PAUSE -> TvAction.Pause
        KeyEvent.KEYCODE_MEDIA_STOP -> TvAction.Stop

        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
        KeyEvent.KEYCODE_BUTTON_R1,
        -> TvAction.FastForward

        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
        KeyEvent.KEYCODE_BUTTON_L1,
        -> TvAction.Rewind

        KeyEvent.KEYCODE_MEDIA_NEXT -> TvAction.Next
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> TvAction.Previous

        KeyEvent.KEYCODE_DPAD_UP -> TvAction.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> TvAction.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> TvAction.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> TvAction.Right

        else -> null
    }
}
