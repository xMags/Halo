package moe.ditto.halo.tv.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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

/**
 * The mode to show [fps] in: [pickDisplayMode]'s choice, or when none fits,
 * the [current] mode if it nearly does (within [KeepTolerance]). A rate a
 * little off what it should be, as a rough measurement gives, must never take
 * down a mode that already shows it about right; only a rate the current mode
 * does not fit at all goes back to the display's own choice (null).
 */
internal fun displayModeFor(modes: List<DisplayModeOption>, current: DisplayModeOption, fps: Double): DisplayModeOption? {
    pickDisplayMode(modes, current, fps)?.let { return it }
    if (!fps.isFinite() || fps <= 0) return null
    return current.takeIf { cadenceError(it.refreshRate, fps) <= KeepTolerance }
}

/** 1%: a mode this close to the rate is kept rather than swapped for the display's own choice. */
private const val KeepTolerance = 0.01

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

/** What a player screen leaving does to the display mode. */
internal enum class DisplayModeRelease {
    /** Another player screen holds it: nothing changes. */
    Keep,

    /** Another player screen was announced and has not come yet: wait for it, within a bound. */
    AwaitNext,

    /** The player was left: the display's own mode, at once. */
    RestoreNow,
}

/**
 * Who holds the display mode. A player screen holds it while on screen
 * ([claim], [release]); the code that replaces one player with another (the
 * next episode, an automatic source switch) says so first ([expectNext]),
 * since the new screen and the old one come and go in either order. Only
 * then does a leaving player keep the mode for the one coming; any other
 * leave gives it back at once. Pure, so the rule is tested.
 */
internal class DisplayModeClaims {
    private var claims = 0
    private var nextExpected = false

    /** A player is about to replace the one on screen. */
    fun expectNext() {
        nextExpected = true
    }

    /** A player screen came on screen; an announced one has now arrived. */
    fun claim() {
        claims += 1
        nextExpected = false
    }

    fun release(): DisplayModeRelease {
        claims = (claims - 1).coerceAtLeast(0)
        return when {
            claims > 0 -> DisplayModeRelease.Keep
            nextExpected -> DisplayModeRelease.AwaitNext
            else -> DisplayModeRelease.RestoreNow
        }
    }

    /** The announced player never came; true when nobody holds the mode, so it goes back. */
    fun nextNeverCame(): Boolean {
        nextExpected = false
        return claims == 0
    }

    val held: Boolean get() = claims > 0
}

/**
 * The display mode the player chose, held across the files that follow each
 * other in the player, and given back to the display's own choice as soon as
 * the player is left for any other screen. Every mode change drops the TV's
 * HDMI link for seconds, so a switch back and over again between two files
 * of the same rate is what this prevents; and the blackout of a real leave
 * starts at once rather than after the next page has shown.
 *
 * One per TV shell, main thread only.
 */
internal class TvDisplayModeHold {
    private val claims = DisplayModeClaims()
    private val main = Handler(Looper.getMainLooper())
    private var pendingRestore: Runnable? = null

    /** Called just before another player replaces this one. */
    fun expectNext() {
        claims.expectNext()
    }

    fun claim() {
        val waiting = pendingRestore
        claims.claim()
        if (waiting == null) return
        main.removeCallbacks(waiting)
        pendingRestore = null
        Log.i(LogTag, "display mode: held for the next file")
    }

    fun release(window: Window) {
        when (claims.release()) {
            DisplayModeRelease.Keep -> Log.i(LogTag, "display mode: held for the next file")
            DisplayModeRelease.RestoreNow -> restoreNow(window, "the player was left")
            DisplayModeRelease.AwaitNext -> {
                // Only a safety net: the next player normally claims within a frame or two.
                val restore = Runnable {
                    pendingRestore = null
                    if (claims.nextNeverCame()) restoreNow(window, "the next player did not come")
                }
                pendingRestore = restore
                main.postDelayed(restore, NextPlayerTimeoutMs)
            }
        }
    }

    /** Back to the display's own mode now, unless it already is. */
    fun restoreNow(window: Window, why: String) {
        if (window.attributes.preferredDisplayModeId == 0) return
        Log.i(LogTag, "display mode: back to the display's own choice ($why)")
        window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
    }

    private companion object {
        /**
         * How long an announced player may take to come before the mode goes
         * back anyway. It is composed as the old one goes, within a frame or
         * two and the 180 ms fade; this only catches one that never comes.
         */
        const val NextPlayerTimeoutMs = 3_000L
    }
}

/** What the display mode does while a player shows [frameRate] and, or not, a picture. */
internal enum class FrameRateStep {
    /** Pick the mode for the rate. */
    Match,

    /** Keep the mode in force: a picture is up, its rate not known yet. */
    Hold,

    /** No picture yet: keep the mode, and give it back if none comes in time. */
    AwaitPicture,
}

/**
 * The step for [frameRate] (null while not known) with [pictureShown]: a
 * known rate is matched, whichever engine reported it; an unknown rate with a
 * picture up is no reason to change anything; only no picture at all, for
 * long, gives the mode back.
 */
internal fun frameRateStep(frameRate: Double?, pictureShown: Boolean): FrameRateStep = when {
    frameRate != null -> FrameRateStep.Match
    pictureShown -> FrameRateStep.Hold
    else -> FrameRateStep.AwaitPicture
}

/**
 * Switches the display to the mode that suits [frameRate] while a player is
 * on screen ([hold] keeps it across the files that follow each other), and
 * back to the display's own choice when the player is left. Does nothing when
 * [enabled] is false. While a new file's rate is not known yet the mode in
 * force stays, since the next file usually has the same rate; if no picture
 * at all comes within [NoPictureRestoreMs] (a load that failed or hangs), the
 * display's own mode comes back, so an error card never sits at a film's rate.
 * A picture without a known rate keeps the mode ([frameRateStep]).
 *
 * Uses the window's preferred display mode, which Android has honoured since
 * 6.0 on every TV device and which the system-wide frame-rate API on newer
 * versions does not replace for a mode change the viewer has not opted into.
 */
@Composable
internal fun TvFrameRateMatching(frameRate: Double?, pictureShown: Boolean, enabled: Boolean, hold: TvDisplayModeHold) {
    val view = LocalView.current
    val activity = view.context.findActivity() ?: return

    LaunchedEffect(frameRate, pictureShown, enabled) {
        val window = activity.window
        val display: Display = view.display ?: return@LaunchedEffect
        if (!enabled) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
            return@LaunchedEffect
        }
        when (frameRateStep(frameRate, pictureShown)) {
            FrameRateStep.Hold -> return@LaunchedEffect
            FrameRateStep.AwaitPicture -> {
                // Cancelled by the picture or its rate arriving, which restarts this effect.
                delay(NoPictureRestoreMs)
                hold.restoreNow(window, "no picture for ${NoPictureRestoreMs / 1000} s")
                return@LaunchedEffect
            }
            FrameRateStep.Match -> Unit
        }
        if (frameRate == null) return@LaunchedEffect
        val current = display.mode.toOption()
        val choice = displayModeFor(display.supportedModes.map { it.toOption() }, current, frameRate)
        Log.i(LogTag, "frame rate $frameRate on ${current.refreshRate} Hz: ${choice?.refreshRate?.let { "$it Hz" } ?: "no match, system choice"}")
        // Already showing it, whether by an earlier choice (held from the
        // previous file) or the system's.
        if (choice != null && choice.id == current.id) return@LaunchedEffect
        val wanted = choice?.id ?: 0
        if (window.attributes.preferredDisplayModeId == wanted) return@LaunchedEffect
        window.attributes = window.attributes.apply { preferredDisplayModeId = wanted }
    }

    DisposableEffect(activity, hold) {
        hold.claim()
        onDispose { hold.release(activity.window) }
    }
}

/** How long a player may show no picture before the display's own mode comes back. */
private const val NoPictureRestoreMs = 15_000L

private fun Display.Mode.toOption() = DisplayModeOption(modeId, physicalWidth, physicalHeight, refreshRate)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val LogTag = "HALO_TV"
