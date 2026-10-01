package moe.ditto.halo.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Settings
import android.util.Rational
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Android's answers to [PlayerSystemPort], all of them attributes of the one
 * Activity the app runs in.
 *
 * The Activity is held directly rather than through the application context
 * because three of these are window attributes and the fourth is the window's
 * requested orientation; none of them exist without it. It is the same Activity
 * that owns the render surface, so its lifetime already covers the player's.
 */
internal class AndroidPlayerSystemPort(private val activity: Activity) : PlayerSystemPort {

    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Claims outstanding, not booleans: see PlayerSystemPort. Main thread only,
    // which is where every caller of these already is.
    private var landscapeHolders = 0
    private var screenOnHolders = 0
    private var immersiveHolders = 0

    private val mutablePictureInPictureChanges = MutableStateFlow(activity.isInPictureInPictureMode)
    override val pictureInPictureChanges: Flow<Boolean> = mutablePictureInPictureChanges

    private val insets: WindowInsetsControllerCompat
        get() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

    /**
     * Hiding the bars is a request the system may undo on its own: returning
     * from another app, from the lock screen or from a system dialog can bring
     * them back while the player is still on screen and still holding its claim.
     * Nothing recomposes at that point, so re-applying on regained focus is the
     * only place the claim can be honoured again.
     */
    private val restoreImmersiveOnFocus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused && immersiveHolders > 0) applyImmersive()
    }

    /**
     * The window's own override if it has one, otherwise the device's current
     * setting, so a brightness drag starts from what is actually on screen
     * rather than from the middle of the range.
     */
    override fun screenBrightness(): Float? {
        val override = activity.window.attributes.screenBrightness
        if (override >= 0f) return override.coerceIn(0f, 1f)
        return systemBrightness()
    }

    override fun setScreenBrightness(value: Float) {
        applyWindowBrightness(value.coerceIn(MinimumVisibleBrightness, 1f))
    }

    override fun clearScreenBrightnessOverride() {
        applyWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    override fun volume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun setVolume(value: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val step = (value.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
        // No flags: the system's own volume panel appearing over the video is
        // exactly what the in-player readout exists to replace.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
    }

    override fun volumeSteps(): Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    /**
     * PiP support is a device capability as well as a manifest declaration, and
     * a device cannot grow one while the app runs, so this is read once. Where
     * it is false the player offers no PiP control at all.
     */
    override val supportsPictureInPicture: Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun enterPictureInPicture(): Boolean {
        if (!supportsPictureInPicture) return false
        if (activity.isFinishing || activity.isDestroyed) return false
        if (activity.isInPictureInPictureMode) return true

        val parameters = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(PictureInPictureWidth, PictureInPictureHeight))
            .build()
        return try {
            activity.enterPictureInPictureMode(parameters)
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Called only by the owning Activity's platform callback. */
    internal fun onPictureInPictureModeChanged(active: Boolean) {
        mutablePictureInPictureChanges.value = active
    }

    /**
     * Sensor landscape rather than a fixed one, so the device may still be
     * turned end for end; what is locked out is portrait.
     */
    override fun lockLandscape() {
        if (landscapeHolders++ > 0) return
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun releaseLandscape() {
        if (landscapeHolders <= 0 || --landscapeHolders > 0) return
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun keepScreenOn() {
        if (screenOnHolders++ > 0) return
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun releaseScreenOn() {
        if (screenOnHolders <= 0 || --screenOnHolders > 0) return
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Status and navigation bars both, which is what the player's layout is
     * measured for: a bar left on screen would keep its inset and push the
     * chrome off the design.
     *
     * Transient-by-swipe rather than a bar the system keeps: an edge swipe
     * brings them back for a moment and they leave again on their own, so the
     * clock and the back gesture stay reachable without a tap that would
     * otherwise have gone to the video.
     */
    override fun hideSystemBars() {
        if (immersiveHolders++ > 0) return
        activity.window.decorView.viewTreeObserver
            .addOnWindowFocusChangeListener(restoreImmersiveOnFocus)
        applyImmersive()
    }

    /**
     * The behaviour goes back first. Shown under the transient rule the bars
     * are an overlay the system takes away again on its own timer and which
     * reports no inset while it lasts, so the rest of the app would lay out as
     * though it were still full-screen and lose its clock a moment later.
     */
    override fun releaseSystemBars() {
        if (immersiveHolders <= 0 || --immersiveHolders > 0) return
        activity.window.decorView.viewTreeObserver
            .removeOnWindowFocusChangeListener(restoreImmersiveOnFocus)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        insets.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyImmersive() {
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyWindowBrightness(value: Float) {
        val attributes = activity.window.attributes
        attributes.screenBrightness = value
        activity.window.attributes = attributes
    }

    /**
     * Readable without a permission, and reported on the same 0..255 scale the
     * settings slider uses. Absent on a device that has never stored one, which
     * is not worth a failure: the caller falls back to leaving brightness alone.
     */
    private fun systemBrightness(): Float? = try {
        val raw = Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        (raw / MaxSystemBrightness).coerceIn(0f, 1f)
    } catch (_: Settings.SettingNotFoundException) {
        null
    }

    private companion object {
        const val MaxSystemBrightness = 255f

        /**
         * Zero is a black screen with no way back other than by feel, so the
         * lowest a drag can reach is dim rather than off.
         */
        const val MinimumVisibleBrightness = 0.01f

        // PlayerState has no decoded video dimensions yet. A standard video
        // window is preferable to inheriting an unusually wide phone screen,
        // and remains inside Android's accepted PiP aspect-ratio range.
        const val PictureInPictureWidth = 16
        const val PictureInPictureHeight = 9
    }
}
