package moe.ditto.halo.tv.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which item on a screen had focus, kept with the screen's saved state, so
 * coming back to the screen (Back from a title, a rail switch back) puts
 * focus where it was instead of at the top.
 *
 * Items mark themselves with [rememberFocus] under a key stable across
 * recomposition (a title id, a row and column). A screen gives its default
 * item [defaultFocus], which takes focus only when nothing is remembered.
 */
internal class TvFocusMemory(key: String?) {
    var key: String? = key
        internal set

    /** True until the first item has taken focus on this composition. */
    internal var pending: Boolean = true

    companion object {
        val Saver: Saver<TvFocusMemory, String> = Saver(
            save = { it.key ?: "" },
            restore = { TvFocusMemory(it.ifEmpty { null }) },
        )
    }
}

@Composable
internal fun rememberTvFocusMemory(): TvFocusMemory =
    rememberSaveable(saver = TvFocusMemory.Saver) { TvFocusMemory(null) }

/**
 * Records this item as the screen's focused one, and gives it focus again
 * when the screen is composed with [key] remembered.
 */
@Composable
internal fun Modifier.rememberFocus(memory: TvFocusMemory, key: String): Modifier {
    val requester = remember { FocusRequester() }
    val tracker = LocalTvFocusTracker.current
    LaunchedEffect(memory, key) {
        if (memory.key != key) return@LaunchedEffect
        claimFocus(memory, requester, tracker)
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) memory.key = key }
}

/**
 * The item that takes focus when the screen opens with nothing remembered,
 * or when the remembered item is gone (a title removed from a row).
 */
@Composable
internal fun Modifier.defaultFocus(memory: TvFocusMemory, key: String, ready: Boolean = true): Modifier {
    val requester = remember { FocusRequester() }
    val tracker = LocalTvFocusTracker.current
    LaunchedEffect(memory, ready) {
        if (!ready || !memory.pending) return@LaunchedEffect
        // A remembered item claims first; this one waits until it has had
        // every chance (it may be gone, a title removed from a row).
        val remembered = memory.key
        delay(if (remembered == null || remembered == key) RestoreGraceMs else RememberedWaitMs)
        claimFocus(memory, requester, tracker)
    }
    return this.rememberFocus(memory, key).focusRequester(requester)
}

/**
 * Asks for focus each frame until it is given, another item has taken it,
 * or [ClaimMs] has passed:
 * an item in a lazy row can be composed a frame before it is placed, and a
 * request made then fails. Never while the viewer has gone to the rail.
 */
private suspend fun claimFocus(memory: TvFocusMemory, requester: FocusRequester, tracker: TvFocusTracker) {
    withTimeoutOrNull(ClaimMs) {
        while (memory.pending) {
            if (tracker.inRailByChoice) {
                memory.pending = false
                return@withTimeoutOrNull
            }
            if (runCatching { requester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) {
                memory.pending = false
                return@withTimeoutOrNull
            }
            withFrameNanos { }
        }
    }
}

private const val RestoreGraceMs = 120L

/** Longer than a remembered item's own [ClaimMs] of trying. */
private const val RememberedWaitMs = 1_100L

/** How long an item keeps asking, frame by frame. */
private const val ClaimMs = 900L
