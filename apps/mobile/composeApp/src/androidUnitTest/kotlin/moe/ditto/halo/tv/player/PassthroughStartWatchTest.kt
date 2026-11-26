package moe.ditto.halo.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import moe.ditto.halo.player.AudioStartReading
import moe.ditto.halo.player.AudioStartSample
import moe.ditto.halo.player.PlaybackFile

class PassthroughStartWatchTest {
    private val file = PlaybackFile(core = 1, load = 3)

    private fun sample(
        paused: Boolean = false,
        pausedForCache: Boolean = false,
        pictureReady: Boolean = true,
        audioPts: Double? = null,
        position: Double? = 600.0,
        cachedAhead: Double? = 20.0,
        of: PlaybackFile = file,
    ) = AudioStartSample(
        of,
        AudioStartReading(
            paused = paused,
            pausedForCache = pausedForCache,
            pictureReady = pictureReady,
            audioPts = audioPts,
            positionSeconds = position,
            cachedAheadSeconds = cachedAhead,
            audioOutput = null,
        ),
    )

    /** Feeds [count] half-second polls of [reading] and returns the last verdict. */
    private fun PassthroughStartWatch.poll(count: Int, reading: AudioStartSample): PassthroughStartVerdict {
        var verdict = PassthroughStartVerdict.Waiting
        repeat(count) { verdict = step(reading, PassthroughStartPollMs) }
        return verdict
    }

    @Test
    fun soundThatNeverComesIsStuckAfterTheWindow() {
        val watch = PassthroughStartWatch(file)
        // The first counted reading only starts the clock.
        assertEquals(PassthroughStartVerdict.Waiting, watch.step(sample(), 0L))
        assertEquals(PassthroughStartVerdict.Waiting, watch.poll(9, sample()))
        assertEquals(PassthroughStartVerdict.Stuck, watch.poll(1, sample()))
    }

    @Test
    fun movingSoundIsStarted() {
        val watch = PassthroughStartWatch(file)
        watch.step(sample(), 0L)
        assertEquals(PassthroughStartVerdict.Waiting, watch.step(sample(audioPts = 600.0), PassthroughStartPollMs))
        assertEquals(PassthroughStartVerdict.Started, watch.step(sample(audioPts = 600.5), PassthroughStartPollMs))
    }

    @Test
    fun soundThatOpenedButDoesNotMoveIsStillStuck() {
        val watch = PassthroughStartWatch(file)
        watch.step(sample(audioPts = 600.0), 0L)
        assertEquals(PassthroughStartVerdict.Stuck, watch.poll(11, sample(audioPts = 600.0)))
    }

    @Test
    fun aPauseHoldsTheClock() {
        val watch = PassthroughStartWatch(file)
        watch.step(sample(), 0L)
        watch.poll(8, sample())
        assertEquals(PassthroughStartVerdict.Waiting, watch.poll(100, sample(paused = true)))
        // The time before the pause still counts; the pause itself does not,
        // nor the poll that ends it.
        assertEquals(PassthroughStartVerdict.Waiting, watch.poll(2, sample()))
        assertEquals(PassthroughStartVerdict.Stuck, watch.poll(1, sample()))
    }

    @Test
    fun bufferingHoldsTheClock() {
        val watch = PassthroughStartWatch(file)
        watch.step(sample(), 0L)
        assertEquals(PassthroughStartVerdict.Waiting, watch.poll(100, sample(pausedForCache = true)))
        // Too little read ahead is waiting for the network too.
        assertEquals(PassthroughStartVerdict.Waiting, watch.poll(100, sample(cachedAhead = 0.2)))
        assertEquals(PassthroughStartVerdict.Waiting, watch.poll(10, sample()))
        assertEquals(PassthroughStartVerdict.Stuck, watch.poll(1, sample()))
    }

    @Test
    fun nothingCountsBeforeThePicture() {
        val watch = PassthroughStartWatch(file)
        watch.step(sample(pictureReady = false), 0L)
        assertEquals(PassthroughStartVerdict.Waiting, watch.poll(100, sample(pictureReady = false)))
    }

    @Test
    fun aSeekStartsTheWindowAgain() {
        val watch = PassthroughStartWatch(file)
        watch.step(sample(position = 0.0), 0L)
        watch.poll(9, sample(position = 0.0))
        assertEquals(PassthroughStartVerdict.Waiting, watch.step(sample(position = 1800.0), PassthroughStartPollMs))
        assertEquals(PassthroughStartVerdict.Waiting, watch.poll(9, sample(position = 1800.0)))
        assertEquals(PassthroughStartVerdict.Stuck, watch.poll(1, sample(position = 1800.0)))
    }

    @Test
    fun aJumpInTheSoundIsNotProofThatItPlays() {
        val watch = PassthroughStartWatch(file)
        watch.step(sample(audioPts = 10.0, position = 10.0), 0L)
        assertEquals(PassthroughStartVerdict.Waiting, watch.step(sample(audioPts = 900.0, position = 900.0), PassthroughStartPollMs))
    }

    @Test
    fun anOlderFileNeverActs() {
        val watch = PassthroughStartWatch(file)
        watch.step(sample(), 0L)
        watch.poll(10, sample())
        val newer = PlaybackFile(core = 1, load = 4)
        assertEquals(PassthroughStartVerdict.Superseded, watch.step(sample(of = newer), PassthroughStartPollMs))
        assertEquals(PassthroughStartVerdict.Superseded, watch.step(sample(of = PlaybackFile(core = 2, load = 3)), PassthroughStartPollMs))
    }

    @Test
    fun unknownReadAheadDoesNotHoldTheClock() {
        // A file read without a cache reports no read-ahead figure.
        val watch = PassthroughStartWatch(file)
        watch.step(sample(cachedAhead = null), 0L)
        assertEquals(PassthroughStartVerdict.Stuck, watch.poll(11, sample(cachedAhead = null)))
    }
}
