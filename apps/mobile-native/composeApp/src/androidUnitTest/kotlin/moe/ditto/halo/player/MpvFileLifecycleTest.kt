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
}
