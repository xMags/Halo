package moe.ditto.halo.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private val NoPictureInPictureChanges: Flow<Boolean> = flowOf(false)

/**
 * What the player needs from the device rather than from the media engine.
 *
 * Deliberately not part of [PlayerPort]. None of this is playback: the screen's
 * brightness, the system's media volume, which way the window is allowed to
 * rotate and whether the display may sleep are all properties of the device
 * that happen to matter while watching something. Putting them behind the media
 * boundary would mean every engine implementation had to answer questions about
 * window attributes.
 *
 * Volume drives the *system* media stream rather than the engine's own volume,
 * which is what every mobile player does: the hardware keys and this gesture
 * then move the same number, and a viewer who turns the sound down here does
 * not find it turned up again in the next app.
 *
 * Every method is main-thread and cheap; these are window and audio-manager
 * attributes, not I/O.
 */
interface PlayerSystemPort {
    /**
     * Screen brightness as 0..1, or null when the platform cannot say. Null is
     * a real answer: on a window that has never overridden it, there is no
     * window brightness to read, only the system's, which may be unreadable.
     */
    fun screenBrightness(): Float?

    /** Overrides brightness for this window only; the rest of the device is untouched. */
    fun setScreenBrightness(value: Float)

    /** Hands brightness back to the system, for when the player goes away. */
    fun clearScreenBrightnessOverride()

    /** System media volume as 0..1. */
    fun volume(): Float

    fun setVolume(value: Float)

    /**
     * How many steps the system's own volume has. A gesture finer than one step
     * cannot be honoured, so the caller rounds to this rather than pretending
     * to a precision the device does not have.
     */
    fun volumeSteps(): Int

    /**
     * Whether this platform can hand the video to a real operating-system
     * picture-in-picture window.
     *
     * The player hides its PiP control when this is false rather than offering
     * a button that draws something PiP-shaped inside the app. An in-app
     * imitation is not the feature: it cannot survive leaving Halo, which is
     * the entire point of asking for PiP.
     */
    val supportsPictureInPicture: Boolean
        get() = false

    /**
     * Asks the operating system to move this Activity or view into its native
     * picture-in-picture presentation. True means the handoff was accepted;
     * false means it was refused and the player stays as it is.
     *
     * Only called when [supportsPictureInPicture] is true, and even then a
     * refusal is ordinary: the window may be finishing, or the system may
     * decline for reasons the app cannot see.
     */
    fun enterPictureInPicture(): Boolean = false

    /**
     * Native picture-in-picture state and its later changes. True is the
     * completed handoff; false is the full app, when normal player chrome must
     * be restored. Platforms without native PiP report false once and never
     * change, since nothing can put them into PiP in the first place.
     */
    val pictureInPictureChanges: Flow<Boolean>
        get() = NoPictureInPictureChanges

    /**
     * Landscape and a display that stays awake, for as long as something needs
     * them.
     *
     * These are claims rather than settings, and every claim has to be
     * released. Counting matters because two players can overlap: navigating
     * from one episode to the next composes the new screen before the old one
     * is disposed, so the release of the outgoing screen arrives after the
     * claim of the incoming one. Treated as a plain setting, that release turns
     * the device back to portrait underneath a player that is still playing,
     * which is what happens on a real device rather than in a test.
     */
    fun lockLandscape()

    fun releaseLandscape()

    fun keepScreenOn()

    fun releaseScreenOn()

    /**
     * The system's own bars off the screen, so the video is the only thing on
     * it. Counted like [lockLandscape] and for the same overlapping-players
     * reason.
     *
     * The player's chrome is measured from an edge with no status or navigation
     * bar on it (see `rememberPlayerMetrics`), so this is what makes the layout
     * land where it was designed rather than pushed down by the clock.
     */
    fun hideSystemBars()

    fun releaseSystemBars()
}

/**
 * For platforms with no implementation yet, and for tests. Reporting null
 * brightness and zero volume is what makes the gesture layer decline to act
 * rather than act on invented numbers.
 */
object NoPlayerSystemPort : PlayerSystemPort {
    override fun screenBrightness(): Float? = null
    override fun setScreenBrightness(value: Float) = Unit
    override fun clearScreenBrightnessOverride() = Unit
    override fun volume(): Float = 0f
    override fun setVolume(value: Float) = Unit
    override fun volumeSteps(): Int = 0
    override fun lockLandscape() = Unit
    override fun releaseLandscape() = Unit
    override fun keepScreenOn() = Unit
    override fun releaseScreenOn() = Unit
    override fun hideSystemBars() = Unit
    override fun releaseSystemBars() = Unit
}
