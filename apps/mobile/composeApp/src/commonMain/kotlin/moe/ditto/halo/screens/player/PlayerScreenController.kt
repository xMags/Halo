package moe.ditto.halo.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which tab the right rail is showing, or null when it is closed. */
internal enum class RailTab {
    Audio,
    Subtitles,
    Speed,
}

/**
 * The two halves of the subtitle tab. They are separated because they are used
 * at different moments and at different rates: a track is chosen once, while
 * size and delay are nudged, watched, and nudged again. Behind a list of addon
 * results the second of those is a scroll every time.
 */
internal enum class SubtitlePane {
    Tracks,
    Appearance,
}

/** How long chrome stays up with nothing touching it. */
private const val ChromeIdleMillis = 3_000L

/**
 * Everything the player screen knows that playback does not: whether the chrome
 * is up, which panel is open, and where a scrub is in progress.
 *
 * It is a plain class holding snapshot state rather than a pile of `remember`s
 * inside the composable, for two reasons. The auto-hide rule is a real state
 * machine (a timer that several unrelated interactions arm, cancel and suppress)
 * and it is the kind of thing that silently rots into "chrome hides while the
 * user is dragging the scrubber"; here it can be tested against virtual time
 * with no composition at all. And the rule is genuinely cross-cutting, so
 * spreading it across the widgets that trigger it would leave no single place
 * that states it.
 *
 * Playback-shaped state is not duplicated here. Position, duration, tracks and
 * status live in `PlayerState` and the screen reads them from there; the one
 * thing this holds about playback is whether it is paused, because the auto-hide
 * rule depends on it.
 *
 * [scope] must be a scope tied to the screen: the pending hide is cancelled with
 * it, so a screen that has gone away cannot hide chrome that no longer exists.
 */
internal class PlayerScreenController(private val scope: CoroutineScope) {

    var chromeVisible by mutableStateOf(true)
        private set

    var rail by mutableStateOf<RailTab?>(null)
        private set

    /**
     * Kept across openings of the rail rather than reset to [SubtitlePane.Tracks]
     * each time. Delay and size are set by trying a value against the picture and
     * coming back, and sending that viewer through the track list on every return
     * is the scroll this split exists to remove.
     */
    var subtitlePane by mutableStateOf(SubtitlePane.Tracks)
        private set

    /**
     * Which addon languages are unfolded, or null while the viewer has not said.
     * Null is not the same as "none": until then the list decides for itself
     * (see `defaultExpandedSubtitleLanguages`), and it has to keep deciding as
     * results arrive, which a set fixed at first composition could not do.
     */
    var expandedSubtitleLanguages by mutableStateOf<Set<String>?>(null)
        private set

    var episodeDrawerOpen by mutableStateOf(false)
        private set

    /** Non-null only while a scrub gesture is in flight, as a 0..1 fraction. */
    var scrubFraction by mutableStateOf<Float?>(null)
        private set

    /**
     * The one rail choice the engine cannot hold yet: the addon subtitle list is
     * not fetched at all. Everything else the rail offers now goes through the
     * live engine path, and the screen reads its echo from `PlayerState`.
     */
    var selectedAddonSubtitleId by mutableStateOf<String?>(null)
        private set

    /** Controls are locked away; the scrim swallows everything but the pill. */
    var locked by mutableStateOf(false)
        private set

    /** The unlock affordance, which hides itself so it is not burned into a frame. */
    var unlockPillVisible by mutableStateOf(false)
        private set

    /** What a brightness or volume drag is currently showing, if anything. */
    var hud by mutableStateOf<GestureHudValue?>(null)
        private set

    /** Non-null once the up-next countdown is running. */
    var upNextSecondsRemaining by mutableStateOf<Int?>(null)
        private set

    private var paused = false
    private var hideJob: Job? = null
    private var unlockPillJob: Job? = null
    private var hudJob: Job? = null
    private var upNextJob: Job? = null
    private var onAdvance: (() -> Unit)? = null
    private var advanceClaimed = false

    /**
     * Chrome hides itself only when there is nothing to look at and nothing in
     * progress. Pausing is a deliberate stop, so the controls stay up; an open
     * rail or drawer is being read; and a scrub in flight is being aimed.
     */
    private val canAutoHide: Boolean
        get() = chromeVisible &&
            !paused &&
            scrubFraction == null &&
            rail == null &&
            !episodeDrawerOpen

    /**
     * Restarts the idle timer. Safe to call after any interaction: it cancels
     * whatever was pending, and starts nothing when hiding is suppressed, so
     * callers never have to know the rule.
     */
    fun armAutoHide() {
        hideJob?.cancel()
        hideJob = null
        if (!canAutoHide) return
        hideJob = scope.launch {
            delay(ChromeIdleMillis)
            chromeVisible = false
        }
    }

    /** Tapping the video. */
    fun toggleChrome() {
        chromeVisible = !chromeVisible
        armAutoHide()
    }

    fun showChrome() {
        chromeVisible = true
        armAutoHide()
    }

    /**
     * Playback's own pause state, pushed in by the screen. Re-arms rather than
     * assuming a direction: resuming starts the timer that pausing suppressed.
     */
    fun onPausedChanged(value: Boolean) {
        if (paused == value) return
        paused = value
        armAutoHide()
    }

    /**
     * Any transport action: the seek buttons, or play/pause. The chrome comes
     * back up if it was down, because these can be reached by gesture too.
     */
    fun onTransportUsed() {
        showChrome()
    }

    /** The rail and the drawer are alternatives; opening one closes the other. */
    fun openRail(tab: RailTab) {
        rail = tab
        episodeDrawerOpen = false
        showChrome()
    }

    fun closeRail() {
        rail = null
        armAutoHide()
    }

    fun toggleEpisodeDrawer() {
        episodeDrawerOpen = !episodeDrawerOpen
        rail = null
        showChrome()
    }

    fun closeEpisodeDrawer() {
        episodeDrawerOpen = false
        armAutoHide()
    }

    /**
     * A scrub gesture. [beginScrub] and [updateScrub] only move the preview;
     * nothing is sent to the engine until [endScrub] returns the position its
     * caller should seek to, so a drag across the track is one seek rather than
     * one per frame.
     */
    fun beginScrub(fraction: Float) {
        scrubFraction = fraction.coerceIn(0f, 1f)
        showChrome()
    }

    fun updateScrub(fraction: Float) {
        if (scrubFraction == null) return
        scrubFraction = fraction.coerceIn(0f, 1f)
    }

    /** Returns the committed fraction, or null if no scrub was in flight. */
    fun endScrub(): Float? {
        val committed = scrubFraction ?: return null
        scrubFraction = null
        armAutoHide()
        return committed
    }

    fun cancelScrub() {
        scrubFraction = null
        armAutoHide()
    }

    // --- Rail choices ----------------------------------------------------

    /**
     * Choosing an addon subtitle clears the in-file selection in the UI, since
     * only one subtitle can be showing. The engine side of that is a real
     * [moe.ditto.halo.player.PlayerPort.addSubtitle] call, which arrives with
     * the fetch.
     */
    fun selectAddonSubtitle(id: String?) {
        selectedAddonSubtitleId = id
    }

    fun selectSubtitlePane(pane: SubtitlePane) {
        subtitlePane = pane
        showChrome()
    }

    /**
     * Folding is per language and not exclusive: comparing two languages' offers
     * means having both open, and a group that closed the others as it opened
     * would make that impossible.
     */
    fun toggleSubtitleLanguage(language: String, currentlyExpanded: Set<String>) {
        expandedSubtitleLanguages = if (language in currentlyExpanded) {
            currentlyExpanded - language
        } else {
            currentlyExpanded + language
        }
        showChrome()
    }

    // --- Locking ---------------------------------------------------------

    /**
     * Locking puts everything away at once: an accidental tap on a control is
     * exactly what it exists to prevent, so leaving the chrome up would defeat
     * it.
     */
    fun lock() {
        locked = true
        chromeVisible = false
        rail = null
        episodeDrawerOpen = false
        hideJob?.cancel()
        revealUnlockPill()
    }

    fun unlock() {
        locked = false
        unlockPillJob?.cancel()
        unlockPillVisible = false
        showChrome()
    }

    /**
     * The unlock pill hides itself, and any tap on the locked scrim brings it
     * back. It cannot stay up: the point of locking is a clean picture, and a
     * static overlay is what burns into an OLED panel.
     */
    fun revealUnlockPill() {
        if (!locked) return
        unlockPillVisible = true
        unlockPillJob?.cancel()
        unlockPillJob = scope.launch {
            delay(UnlockPillIdleMillis)
            unlockPillVisible = false
        }
    }

    // --- Picture in picture ----------------------------------------------

    /**
     * Clears full-screen controls before the handoff, so the window the system
     * lifts out holds the picture and nothing else. Panels and chrome would
     * otherwise be scaled down into an unreadable thumbnail.
     */
    fun prepareForPictureInPicture() {
        chromeVisible = false
        rail = null
        episodeDrawerOpen = false
        hideJob?.cancel()
    }

    /** Coming back from the system's window, where the player is full-screen again. */
    fun exitPictureInPicture() {
        showChrome()
    }

    // --- Gesture readout -------------------------------------------------

    /**
     * Shows the brightness or volume readout and clears it shortly after the
     * gesture stops feeding it, so it does not sit over the picture.
     */
    fun showHud(kind: GestureHudKind, value: Float) {
        hud = GestureHudValue(kind, value.coerceIn(0f, 1f))
        hudJob?.cancel()
        hudJob = scope.launch {
            delay(HudIdleMillis)
            hud = null
        }
    }

    // --- Up next ---------------------------------------------------------

    /**
     * Starts the countdown to the next episode.
     *
     * The countdown, `Play now` and `Cancel` all funnel through one claim, so
     * whichever happens first wins and the other two become no-ops. Without
     * that, tapping `Play now` on the final tick advances twice: once from the
     * tap and once from the timer that was already in flight.
     */
    fun showUpNext(seconds: Int = UpNextSeconds, onAdvance: () -> Unit) {
        if (upNextSecondsRemaining != null || advanceClaimed) return
        this.onAdvance = onAdvance
        upNextSecondsRemaining = seconds
        upNextJob = scope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1_000L)
                remaining -= 1
                upNextSecondsRemaining = remaining
            }
            advanceToNext()
        }
    }

    /** `Play now`: takes the claim early and stops the countdown. */
    fun advanceToNext() {
        val advance = onAdvance
        if (!claimAdvance()) return
        upNextSecondsRemaining = null
        advance?.invoke()
    }

    /** `Cancel`: takes the claim so the pending countdown cannot fire. */
    fun dismissUpNext() {
        claimAdvance()
        upNextSecondsRemaining = null
    }

    private fun claimAdvance(): Boolean {
        if (advanceClaimed) return false
        advanceClaimed = true
        upNextJob?.cancel()
        upNextJob = null
        onAdvance = null
        return true
    }
}

/** Which half of the screen a vertical drag was on, and so what it changes. */
internal enum class GestureHudKind {
    Brightness,
    Volume,
}

/** [value] is 0..1; the readout renders it as a percentage and a meter. */
internal data class GestureHudValue(val kind: GestureHudKind, val value: Float)

/** How long the unlock affordance stays up after being asked for. */
private const val UnlockPillIdleMillis = 3_000L

/** How long a gesture readout outlives the gesture feeding it. */
private const val HudIdleMillis = 500L

/**
 * The old player counted down from five. Eight is long enough to read the
 * episode title and decide, which is the only reason the card exists.
 */
internal const val UpNextSeconds = 8

/**
 * Delay limits, shared by the subtitle and audio steppers. Five seconds either
 * way covers every real desync; beyond that the track is the wrong one.
 */
internal const val MaxDelaySeconds = 5.0
internal const val DelayStepSeconds = 0.05
