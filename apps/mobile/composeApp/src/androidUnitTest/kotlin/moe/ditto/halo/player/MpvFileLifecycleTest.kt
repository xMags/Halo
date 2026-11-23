package moe.ditto.halo.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvFileLifecycleTest {
    @Test
    fun replacementEndDoesNotFailTheNewFile() {
        val lifecycle = MpvFileLifecycle()
        lifecycle.onLoadRequested()
        lifecycle.onFileStarted()

        lifecycle.onLoadRequested()
        // The old connection can log while mpv is closing it. Its END_FILE is
        // expected and must not become the replacement's visible failure.
        lifecycle.recordError("old connection closed")
        assertNull(lifecycle.endFileFailure())

        lifecycle.onFileStarted()
        lifecycle.recordError("new source failed")
        assertEquals("new source failed", lifecycle.endFileFailure())
    }

    @Test
    fun rapidReplacementsConsumeOneOldEndEach() {
        val lifecycle = MpvFileLifecycle()
        lifecycle.onLoadRequested()
        lifecycle.onLoadRequested()
        lifecycle.onLoadRequested()

        assertNull(lifecycle.endFileFailure())
        assertNull(lifecycle.endFileFailure())
        assertEquals("Playback ended unexpectedly.", lifecycle.endFileFailure())
    }

    @Test
    fun naturalEndIsNotAPlaybackFailureAndNotifiesOnlyOnce() {
        val lifecycle = MpvFileLifecycle()
        lifecycle.onLoadRequested()
        lifecycle.onFileStarted()

        assertTrue(lifecycle.markEofReached())
        assertFalse(lifecycle.markEofReached())
        assertNull(lifecycle.endFileFailure())
    }

    @Test
    fun anEndAtTheStatedLengthIsAnEnd() {
        assertFalse(endedShortOfDuration(599.96, 600.0))
        // Audio a few seconds longer than the picture.
        assertFalse(endedShortOfDuration(592.0, 600.0))
        // A two-hour film may stop a couple of minutes short of a padded length.
        assertFalse(endedShortOfDuration(7_080.0, 7_200.0))
    }

    @Test
    fun anEndWellShortOfTheLengthIsALostSource() {
        // A dropped connection: the cache played out at 6:48 of 10:00.
        assertTrue(endedShortOfDuration(408.0, 600.0))
        assertTrue(endedShortOfDuration(3_600.0, 7_200.0))
    }

    @Test
    fun withoutAUsableLengthEveryEndIsAnEnd() {
        assertFalse(endedShortOfDuration(10.0, null))
        assertFalse(endedShortOfDuration(null, 600.0))
        assertFalse(endedShortOfDuration(10.0, 0.0))
        assertFalse(endedShortOfDuration(10.0, Double.NaN))
    }
}
