package moe.ditto.halo.tv.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import kotlin.math.roundToInt

/** One of the display's modes, apart from Android so the choice can be tested. */
internal data class DisplayModeOption(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * The display mode that shows a video of [fps] frames a second without judder,
 * or null when none of them does (the system's own choice is then as good as
 * any).
 *
 * Only modes at the current resolution are considered: a resolution change is
 * a different, slower switch the viewer did not ask for. A rate that shows
 * every frame the same number of times wins (24 for 24, 50 for 25, 60 for
 * 30), and failing that one within half a percent (24 for 23.976, which
 * repeats a frame every 40 seconds instead of every other one). Among those,
 * the current mode stays when it qualifies, since every switch blanks the
 * screen for a moment; otherwise the fastest one no faster than the current
 * mode, which keeps menus smooth, else the slowest.
 */
internal fun pickDisplayMode(modes: List<DisplayModeOption>, current: DisplayModeOption, fps: Double): DisplayModeOption? {
    if (!fps.isFinite() || fps <= 0) return null
    val sameSize = modes.filter { it.width == current.width && it.height == current.height }
    for (tolerance in listOf(ExactTolerance, NearTolerance)) {
        val fitting = sameSize.filter { cadenceError(it.refreshRate, fps) <= tolerance }
        if (fitting.isEmpty()) continue
        fitting.firstOrNull { it.id == current.id }?.let { return it }
        val notFaster = fitting.filter { it.refreshRate <= current.refreshRate + 0.5f }
        return notFaster.maxByOrNull { it.refreshRate } ?: fitting.minBy { it.refreshRate }
    }
    return null
}

/** How far [refresh] is from showing each frame a whole number of times, relative to that number. */
private fun cadenceError(refresh: Float, fps: Double): Double {
    val ratio = refresh / fps
    val repeats = ratio.roundToInt()
    if (repeats < 1) return Double.MAX_VALUE
    return abs(ratio - repeats) / repeats
}

/** 0.05%: 23.976 and 24 are different rates; 23.976 and 23.98 are the same. */
private const val ExactTolerance = 0.0005

/** 0.5%: close enough that a repeated frame is rare. */
private const val NearTolerance = 0.005

/**
 * Switches the display to the mode that suits [frameRate] while this is on
 * screen, and back to the display's own choice when it leaves. Does nothing
 * when [enabled] is false or the rate is not known yet.
 *
 * Uses the window's preferred display mode, which Android has honoured since
 * 6.0 on every TV device and which the system-wide frame-rate API on newer
 * versions does not replace for a mode change the viewer has not opted into.
 */
@Composable
internal fun TvFrameRateMatching(frameRate: Double?, enabled: Boolean) {
    val view = LocalView.current
    val activity = view.context.findActivity() ?: return

    LaunchedEffect(frameRate, enabled) {
        val window = activity.window
        val display: Display = view.display ?: return@LaunchedEffect
        if (!enabled) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
            return@LaunchedEffect
        }
        // Not known yet (a new file is opening): leave the display alone
        // rather than switching back and then over again.
        if (frameRate == null) return@LaunchedEffect
        val current = display.mode.toOption()
        val choice = pickDisplayMode(display.supportedModes.map { it.toOption() }, current, frameRate)
        Log.i(LogTag, "frame rate $frameRate on ${current.refreshRate} Hz: ${choice?.refreshRate?.let { "$it Hz" } ?: "no match, system choice"}")
        // Already showing it, whether by an earlier choice or the system's.
        if (choice != null && choice.id == current.id) return@LaunchedEffect
        val wanted = choice?.id ?: 0
        if (window.attributes.preferredDisplayModeId == wanted) return@LaunchedEffect
        window.attributes = window.attributes.apply { preferredDisplayModeId = wanted }
    }

    DisposableEffect(activity) {
        onDispose {
            val window = activity.window
            window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
        }
    }
}

private fun Display.Mode.toOption() = DisplayModeOption(modeId, physicalWidth, physicalHeight, refreshRate)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val LogTag = "HALO_TV"
