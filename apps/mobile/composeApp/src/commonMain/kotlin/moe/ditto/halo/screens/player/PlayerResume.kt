package moe.ditto.halo.screens.player

import moe.ditto.halo.api.WatchState

/**
 * Where a video opens: exactly where it was left, whenever there is a place to
 * go back to.
 *
 * That is the same test the title page and continue watching use to offer
 * "Resume" (a saved position that is not marked watched), so whatever says
 * Resume resumes. Nothing near the start or the end is second-guessed: a
 * position is only recorded past the first seconds, and the end is already
 * handled by the row being marked watched.
 *
 * The one refusal is a position past the end of the file being opened, which
 * happens when another source is a shorter cut; that starts from the top.
 * [durationSeconds] is the opened file's own length, null when it is unknown.
 */
internal fun resumePosition(saved: WatchState?, durationSeconds: Double?): Double? {
    // A removal from the history is a removal of the place too.
    if (saved == null || saved.watched || saved.removedAt != null) return null
    val position = saved.positionSec
    if (!position.isFinite() || position <= 0.0) return null
    if (durationSeconds != null && durationSeconds > 0.0 && position >= durationSeconds) return null
    return position
}
