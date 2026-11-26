package moe.ditto.halo.tv.player

import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.ditto.halo.AndroidMpvPlayerHost
import moe.ditto.halo.player.AudioStartSample
import moe.ditto.halo.player.PlaybackFile

/**
 * How long sound sent on untouched may take to start once the picture is up,
 * counted only while it could be playing. A passthrough start normally plays
 * within a second of the first frame; a receiver switching its input to a
 * compressed format can hold the output for two or three. Past this the
 * output is not coming, and the viewer is already looking at a still picture.
 */
internal const val PassthroughStartWindowMs = 5_000L

/** How often the engine is asked while a start is watched. */
internal const val PassthroughStartPollMs = 500L

/** Below this much `audio-pts` movement between two readings, the sound is not playing. */
private const val MinAudioAdvanceSeconds = 0.1

/**
 * A move larger than this between two readings, half a second apart, is a
 * seek rather than playback: it starts the window again, and it is not proof
 * that sound plays.
 */
private const val SeekJumpSeconds = 2.0

/**
 * While less than this is read ahead, playback may be waiting for the network
 * rather than for the sound, so the wait is not counted. mpv's `seeking` and
 * `core-idle` cannot tell the two apart: both stay set for as long as the
 * sound has not started, which is the very case being watched for.
 */
private const val MinCachedAheadSeconds = 0.5

internal enum class PassthroughStartVerdict {
    /** Not decided yet; ask again. */
    Waiting,

    /** Sound is playing; nothing more to watch. */
    Started,

    /** The sound did not start within [PassthroughStartWindowMs]: decode it here. */
    Stuck,

    /** Another file plays now; this watch is over and must not act. */
    Superseded,
}

/**
 * The rule for one passthrough start, apart from Android so it can be tested.
 * Fed one [AudioStartSample] per poll with the time since the last one. Time
 * counts only while the sound could be playing: not while paused, buffering,
 * before the first picture or while too little is read ahead; and a seek
 * starts the window again. Not thread-safe; one caller drives it.
 */
internal class PassthroughStartWatch(
    private val file: PlaybackFile,
    private val windowMs: Long = PassthroughStartWindowMs,
) {
    private var waitedMs = 0L
    // Whether the previous reading counted too: only time between two counted
    // readings is added, so time spent paused never leaks into the window.
    private var counting = false
    private var lastAudioPts: Double? = null
    private var lastPosition: Double? = null

    fun step(sample: AudioStartSample, elapsedMs: Long): PassthroughStartVerdict {
        if (sample.file != file) return PassthroughStartVerdict.Superseded
        val reading = sample.reading
        val previousPts = lastAudioPts
        lastAudioPts = reading.audioPts
        if (!reading.paused && previousPts != null && reading.audioPts != null) {
            val advance = reading.audioPts - previousPts
            if (advance >= MinAudioAdvanceSeconds && advance <= SeekJumpSeconds) return PassthroughStartVerdict.Started
        }
        val previousPosition = lastPosition
        lastPosition = reading.positionSeconds
        if (previousPosition != null && reading.positionSeconds != null &&
            abs(reading.positionSeconds - previousPosition) > SeekJumpSeconds
        ) {
            waitedMs = 0L
            counting = false
        }
        val starved = reading.cachedAheadSeconds != null && reading.cachedAheadSeconds < MinCachedAheadSeconds
        if (reading.paused || reading.pausedForCache || !reading.pictureReady || starved) {
            counting = false
            return PassthroughStartVerdict.Waiting
        }
        if (counting) waitedMs += elapsedMs.coerceAtLeast(0L)
        counting = true
        return if (waitedMs >= windowMs) PassthroughStartVerdict.Stuck else PassthroughStartVerdict.Waiting
    }
}

/**
 * Makes sure sound sent on untouched cannot freeze playback. A receiver path
 * can accept a format on paper and still never start: mpv then holds the
 * first frame forever, waiting for sound, with no error. When that happens
 * the track is decoded here instead, at the same place, and the formats that
 * failed are reported to [onFailed] so the next title does not wait again.
 *
 * Owned by [TvAudioOutput]. [watch] runs in the player screen's coroutine (the
 * main thread) and is cancelled with it: when the file, the track or the
 * `audio-spdif` value changes, and when the player closes. Every engine read
 * and change goes through the host's executor, and the change is refused
 * there unless the same file is still playing ([PlaybackFile]).
 */
internal class TvPassthroughWatchdog(
    private val host: AndroidMpvPlayerHost,
    private val onFailed: (Set<PassthroughFormat>) -> Unit,
) {
    /**
     * Watches the opened file's start while its [codec] track passes through
     * under [spdifCodecs], until the sound starts, the file changes, the
     * player closes or the caller is cancelled. A fallback empties the list
     * in the host's passthrough state for this file.
     */
    suspend fun watch(spdifCodecs: String, codec: String?) {
        val failing = formatsCarrying(spdifCodecs, codec)
        if (failing.isEmpty()) return
        val first = host.audioStartSample() ?: return
        val watch = PassthroughStartWatch(first.file)
        var last = SystemClock.elapsedRealtime()
        watch.step(first, 0L)
        while (true) {
            delay(PassthroughStartPollMs)
            val sample = host.audioStartSample() ?: return
            val now = SystemClock.elapsedRealtime()
            val verdict = watch.step(sample, now - last)
            last = now
            when (verdict) {
                PassthroughStartVerdict.Waiting -> continue
                PassthroughStartVerdict.Superseded -> return
                PassthroughStartVerdict.Started -> {
                    Log.i(LogTag, "sound started: $codec track, audio-spdif $spdifCodecs, output ${sample.reading.audioOutput}")
                    return
                }
                PassthroughStartVerdict.Stuck -> {
                    fallBack(sample, codec, failing)
                    return
                }
            }
        }
    }

    /**
     * Not cancellable once begun: the rebuild deselects the track for a
     * moment, which recomposes the caller and can cancel it, and the engine
     * change and the record must not come apart.
     *
     * The line names no audio output: one still open at this point is the
     * previous title's, which mpv keeps open between files.
     */
    private suspend fun fallBack(sample: AudioStartSample, codec: String?, failing: Set<PassthroughFormat>) =
        withContext(NonCancellable) {
            if (!host.decodeAudioHere(sample.file)) return@withContext
            // The record is written to storage, which stays off the main thread.
            withContext(Dispatchers.IO) { onFailed(failing) }
            Log.i(
                LogTag,
                "passthrough did not start, decoding here instead: $codec, no sound ${PassthroughStartWindowMs / 1000} s " +
                    "after the picture; ${failing.map { it.mpvName }} off on this output for " +
                    "${PassthroughFailureExpiryMs / DayMs} days or until it changes",
            )
        }

    private companion object {
        const val LogTag = "HALO_TV"
        const val DayMs = 24L * 60 * 60 * 1000
    }
}
