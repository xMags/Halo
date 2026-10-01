package moe.ditto.halo

import moe.ditto.halo.player.PlayerSystemPort

/**
 * Swift-owned access to UIKit and the system audio volume.
 *
 * This is intentionally separate from [HaloIosPlayerHost]. Screen presentation
 * and device volume are not libmpv capabilities, and keeping a second boundary
 * preserves the same split common code expresses with [PlayerSystemPort].
 */
interface HaloIosPlayerSystemHost {
    /** Negative means the platform could not read the current brightness. */
    fun screenBrightness(): Double
    fun setScreenBrightness(value: Double)
    fun clearScreenBrightnessOverride()

    fun volume(): Double
    fun setVolume(value: Double)
    fun volumeSteps(): Int

    fun lockLandscape()
    fun releaseLandscape()
    fun keepScreenOn()
    fun releaseScreenOn()
    fun hideSystemBars()
    fun releaseSystemBars()
}

internal class IosPlayerSystemPort(
    private val host: HaloIosPlayerSystemHost,
) : PlayerSystemPort {
    /**
     * iOS has picture-in-picture, but not for this player. `AVPictureInPicture-
     * Controller` drives an `AVPlayerLayer` or an `AVSampleBufferDisplayLayer`,
     * and libmpv renders into a Metal layer that is neither: there is no layer
     * for UIKit to lift out of the app. Supporting it would mean pulling
     * decoded frames back out of mpv and feeding a sample-buffer layer
     * alongside the renderer, which is a second video path, not a flag.
     *
     * So the control is not offered here. The alternative -- a PiP button that
     * draws a small video-shaped box inside Halo -- promises the one thing it
     * cannot do, which is keep playing once the viewer leaves.
     */
    override val supportsPictureInPicture: Boolean = false

    override fun screenBrightness(): Float? = host.screenBrightness()
        .takeIf { it.isFinite() && it >= 0.0 }
        ?.coerceIn(0.0, 1.0)
        ?.toFloat()

    override fun setScreenBrightness(value: Float) {
        host.setScreenBrightness(value.coerceIn(0f, 1f).toDouble())
    }

    override fun clearScreenBrightnessOverride() {
        host.clearScreenBrightnessOverride()
    }

    override fun volume(): Float = host.volume()
        .takeIf { it.isFinite() }
        ?.coerceIn(0.0, 1.0)
        ?.toFloat()
        ?: 0f

    override fun setVolume(value: Float) {
        host.setVolume(value.coerceIn(0f, 1f).toDouble())
    }

    override fun volumeSteps(): Int = host.volumeSteps().coerceAtLeast(0)

    override fun lockLandscape() = host.lockLandscape()
    override fun releaseLandscape() = host.releaseLandscape()
    override fun keepScreenOn() = host.keepScreenOn()
    override fun releaseScreenOn() = host.releaseScreenOn()
    override fun hideSystemBars() = host.hideSystemBars()
    override fun releaseSystemBars() = host.releaseSystemBars()
}
