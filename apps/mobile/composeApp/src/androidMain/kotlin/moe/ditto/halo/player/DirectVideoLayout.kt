package moe.ditto.halo.player

import kotlin.math.roundToInt

/**
 * What the player surface needs to lay out video on a direct-video profile.
 *
 * Immutable so the core executor can publish a new one and Compose can read
 * it on the main thread with no shared mutable state between them.
 */
internal data class DirectVideoFrame(
    /** True while the decoder draws straight onto the surface and the app sizes it. */
    val direct: Boolean = false,
    /** mpv's `video-params/dw`: the display width, aspect already applied. 0 while unknown. */
    val videoWidth: Int = 0,
    /** mpv's `video-params/dh`. 0 while unknown. */
    val videoHeight: Int = 0,
    /** Cover the box (cropping) rather than fit inside it. */
    val fillsScreen: Boolean = false,
)

/**
 * The subtitle the app draws on the direct path, and the viewer's style as
 * mpv holds it. Defaults are mpv's own, which is what the engine draws with
 * until the app applies the viewer's settings.
 */
internal data class AppSubtitleState(
    /** mpv's `sub-text`: the current cue as plain text, lines split by `\n`. Empty for none. */
    val text: String = "",
    /** mpv's `sub-scale`. */
    val scale: Double = 1.0,
    /** The chosen family, or null for the default sans-serif. */
    val font: String? = null,
    /** mpv's `sub-border-size`, in its 720-unit canvas. */
    val outlineUnits: Double = SubtitleStyle.DefaultOutlineWidthPixels,
    /** mpv's `sub-shadow-offset`, in its 720-unit canvas. */
    val shadowUnits: Double = 0.0,
    /** mpv's `sub-pos`: 100 rests at the bottom, smaller lifts the text. */
    val position: Int = 100,
)

/** A whole-pixel size. */
internal data class PixelSize(val width: Int, val height: Int)

/**
 * The surface size that shows a [videoWidth] x [videoHeight] picture in a
 * [boxWidth] x [boxHeight] box without distortion: the largest size that fits
 * inside when [fill] is false (bars on two sides), the smallest that covers
 * the box when it is true (the overflow is cropped by the caller). Until the
 * picture's size is known the surface fills the box.
 *
 * The side that touches the box is taken from it exactly and only the other
 * side is rounded, so a picture with the box's own aspect fills it to the
 * pixel.
 */
internal fun fitVideoToBox(boxWidth: Int, boxHeight: Int, videoWidth: Int, videoHeight: Int, fill: Boolean): PixelSize {
    if (boxWidth <= 0 || boxHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return PixelSize(boxWidth, boxHeight)
    // Cross-multiplied in Long, so no aspect ratio is ever rounded.
    val videoIsWider = videoWidth.toLong() * boxHeight >= boxWidth.toLong() * videoHeight
    val widthBound = if (fill) !videoIsWider else videoIsWider
    if (widthBound) {
        val height = (boxWidth.toDouble() * videoHeight / videoWidth).roundToInt()
        return PixelSize(boxWidth, height)
    }
    val width = (boxHeight.toDouble() * videoWidth / videoHeight).roundToInt()
    return PixelSize(width, boxHeight)
}

/**
 * mpv lays text subtitles out on a virtual canvas 720 units high scaled to
 * the window (`sub-scale-with-window`, on by default), so its sizes and
 * margins are in those units. The defaults are mpv 0.41's: `sub-font-size`
 * 38, `sub-margin-x` 25, `sub-margin-y` 22.
 */
internal object MpvSubtitleCanvas {
    const val Height = 720.0
    const val FontSize = 38.0
    const val MarginX = 25.0
    const val MarginY = 22.0
}

/** [units] of mpv's 720-unit canvas, in pixels of a box [boxHeight] pixels high. */
internal fun mpvUnitsToPixels(units: Double, boxHeight: Float): Float = (units * boxHeight / MpvSubtitleCanvas.Height).toFloat()

/** Where and how large the app draws a subtitle, in pixels of its box. */
internal data class SubtitleGeometry(
    /** The text's height from ascent to descent, as libass sizes a font. */
    val fontPixels: Float,
    /** How far the outline reaches past each glyph edge. */
    val outlinePixels: Float,
    /** How far the shadow sits down and to the right. */
    val shadowPixels: Float,
    /** The gap kept clear at each side; lines wrap and centre between them. */
    val sideMarginPixels: Float,
    /** The width lines may use. */
    val textWidthPixels: Float,
    /** Where the bottom of the last line sits, measured from the top of the box. */
    val bottomPixels: Float,
)

/**
 * The subtitle's geometry in a [boxWidth] x [boxHeight] box, following mpv:
 * every size is in the 720-unit canvas, so all of them scale with the box's
 * height; the text block is bottom-anchored at `sub-pos` percent of the
 * height, less the bottom margin.
 */
internal fun subtitleGeometry(boxWidth: Float, boxHeight: Float, subtitles: AppSubtitleState): SubtitleGeometry {
    val sideMargin = mpvUnitsToPixels(MpvSubtitleCanvas.MarginX, boxHeight)
    val bottom = boxHeight * subtitles.position.coerceIn(0, 100) / 100f - mpvUnitsToPixels(MpvSubtitleCanvas.MarginY, boxHeight)
    return SubtitleGeometry(
        fontPixels = mpvUnitsToPixels(MpvSubtitleCanvas.FontSize * subtitles.scale.coerceAtLeast(0.0), boxHeight),
        outlinePixels = mpvUnitsToPixels(subtitles.outlineUnits.coerceAtLeast(0.0), boxHeight),
        shadowPixels = mpvUnitsToPixels(subtitles.shadowUnits.coerceAtLeast(0.0), boxHeight),
        sideMarginPixels = sideMargin,
        textWidthPixels = (boxWidth - 2 * sideMargin).coerceAtLeast(0f),
        bottomPixels = bottom,
    )
}
