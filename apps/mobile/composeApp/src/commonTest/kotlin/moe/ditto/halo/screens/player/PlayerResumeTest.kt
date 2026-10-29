package moe.ditto.halo.screens.player

import moe.ditto.halo.api.WatchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where a video opens, given where it was left and the length of the file opened. */
class PlayerResumeTest {

    private fun saved(positionSec: Double, durationSec: Double = 10_000.0, watched: Boolean = false) = WatchState(
        videoId = "tt1",
        itemId = "tt1",
        positionSec = positionSec,
        durationSec = durationSec,
        watched = watched,
        updatedAt = 1,
    )

    @Test
    fun opensExactlyWhereItWasLeft() {
        assertEquals(1_669.0, resumePosition(saved(1_669.0), durationSeconds = 9_949.0))
    }

    @Test
    fun aShortWatchStillResumes() {
        // Anything recorded is something the title page offers to resume.
        assertEquals(12.0, resumePosition(saved(12.0), durationSeconds = 9_949.0))
    }

    @Test
    fun closeToTheEndStillResumesUntilItIsMarkedWatched() {
        assertEquals(9_400.0, resumePosition(saved(9_400.0), durationSeconds = 9_949.0))
        assertNull(resumePosition(saved(9_400.0, watched = true), durationSeconds = 9_949.0))
    }

    @Test
    fun nothingSavedOpensAtTheStart() {
        assertNull(resumePosition(null, durationSeconds = 9_949.0))
        assertNull(resumePosition(saved(0.0), durationSeconds = 9_949.0))
    }

    @Test
    fun aVideoRemovedFromTheHistoryOpensAtTheStart() {
        assertNull(resumePosition(saved(1_669.0).copy(removedAt = 5), durationSeconds = 9_949.0))
    }

    @Test
    fun aPositionPastTheEndOfAShorterCutOpensAtTheStart() {
        assertNull(resumePosition(saved(6_000.0), durationSeconds = 5_400.0))
    }

    @Test
    fun anUnknownLengthDoesNotStopTheResume() {
        assertEquals(1_669.0, resumePosition(saved(1_669.0), durationSeconds = null))
        assertEquals(1_669.0, resumePosition(saved(1_669.0), durationSeconds = 0.0))
    }
}
