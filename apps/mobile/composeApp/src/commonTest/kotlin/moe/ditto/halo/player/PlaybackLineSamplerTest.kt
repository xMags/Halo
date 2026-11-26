package moe.ditto.halo.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** The sampler ported from the desktop player's `lineSampler.ts`, so both players measure a line alike. */
class PlaybackLineSamplerTest {
    /** Megabits per second as mpv's bytes per second. */
    private fun bytes(mbps: Double): Double = mbps * 1_000_000 / 8

    private fun filling(sampler: PlaybackLineSampler, vararg mbps: Double) {
        sampler.cacheIdleChanged(false)
        for (value in mbps) {
            sampler.cacheSpeed(bytes(value))
            sampler.tick()
        }
    }

    @Test
    fun aReadingNeedsFiveConsecutiveFillingSeconds() {
        val sampler = PlaybackLineSampler()
        filling(sampler, 10.0, 10.0, 10.0, 10.0)
        assertEquals(0.0, sampler.session().peakMbps)
        filling(sampler, 10.0)
        assertEquals(10.0, sampler.session().peakMbps, 1e-9)
    }

    @Test
    fun thePeakIsTheBestMovingAverageNotTheBestSecond() {
        val sampler = PlaybackLineSampler()
        filling(sampler, 10.0, 10.0, 10.0, 10.0, 60.0, 10.0, 10.0)
        // 10,10,10,10,60 averages 20; no later window beats it.
        assertEquals(20.0, sampler.session().peakMbps, 1e-9)
    }

    @Test
    fun anIdleCacheBreaksTheRun() {
        val sampler = PlaybackLineSampler()
        filling(sampler, 30.0, 30.0, 30.0, 30.0)
        sampler.cacheIdleChanged(true)
        sampler.tick()
        filling(sampler, 30.0, 30.0, 30.0, 30.0)
        assertEquals(0.0, sampler.session().peakMbps)
    }

    @Test
    fun nothingIsSampledBeforeTheCacheSaysItIsFilling() {
        val sampler = PlaybackLineSampler()
        repeat(10) {
            sampler.cacheSpeed(bytes(50.0))
            sampler.tick()
        }
        assertEquals(0.0, sampler.session().peakMbps)
    }

    @Test
    fun unusableSpeedsCountAsZero() {
        val sampler = PlaybackLineSampler()
        sampler.cacheIdleChanged(false)
        for (speed in listOf(null, Double.NaN, -5.0, Double.POSITIVE_INFINITY, bytes(25.0))) {
            sampler.cacheSpeed(speed)
            sampler.tick()
        }
        assertEquals(5.0, sampler.session().peakMbps, 1e-9)
    }

    @Test
    fun aStallCountsOnlyAfterPlaybackStartedAndNotJustAfterASeek() {
        val beforeStart = PlaybackLineSampler()
        beforeStart.pausedForCache(true, 1_000)
        assertEquals(false, beforeStart.session().stalled)

        val afterSeek = PlaybackLineSampler()
        afterSeek.playbackStarted()
        afterSeek.seeked(10_000)
        afterSeek.pausedForCache(true, 12_999)
        assertEquals(false, afterSeek.session().stalled)
        afterSeek.pausedForCache(false, 13_500)
        afterSeek.pausedForCache(true, 13_000)
        assertEquals(true, afterSeek.session().stalled)

        val plain = PlaybackLineSampler()
        plain.playbackStarted()
        plain.pausedForCache(false, 5_000)
        assertEquals(false, plain.session().stalled)
        plain.pausedForCache(true, 5_000)
        assertEquals(true, plain.session().stalled)
    }
}
