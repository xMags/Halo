package moe.ditto.halo.screens.player

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ditto.halo.player.VideoFrameReader
import moe.ditto.halo.player.VideoFrameSource

/**
 * How far apart the frames behind the scrub card are.
 *
 * The picture is approximate and the timecode on it is exact, which is the
 * arrangement every scrubber with thumbnails uses: a finger crossing a
 * two-hour timeline moves through several seconds per pixel, so a frame per
 * position would mean a decode per pixel and none of them would arrive. Ten
 * seconds is also near the keyframe spacing of most releases, so a finer step
 * would mostly re-decode the same picture under a different label.
 */
private const val FrameStepSeconds = 10.0

/**
 * How many decoded frames are kept.
 *
 * Each is the size of the card — a few hundred pixels square at device
 * density, so a handful of megabytes at this count. It is a cache for moving
 * back and forth across the same stretch, which is what aiming a scrub
 * actually looks like, not a filmstrip of the whole episode.
 */
private const val MaxCachedFrames = 12

/**
 * How long the reader outlives the last thing that needed it.
 *
 * It holds a second connection to the source for as long as it is open, and
 * some hosts count those. Long enough that consecutive scrubs reuse it rather
 * than paying to reopen; short enough that watching an episode does not hold it
 * for an hour.
 */
private const val ReaderIdleMillis = 20_000L

/**
 * How many failed opens are tolerated before this source is given up on.
 *
 * A failure cannot be attributed: an unsupported container and a network blip
 * look the same from here. One retry covers the blip, and giving up after it
 * keeps an unreadable file from paying the open cost on every gesture — which
 * is seconds of a blocked reader, for a picture that will never come.
 */
private const val MaxOpenFailures = 2

/**
 * How many decodes may fail in a row before this source is given up on.
 *
 * One failure says nothing: a single position can be unreadable in a file
 * whose every other position is fine, which is why a success resets the count.
 * A run of them says the device has no decoder for this video at all — the
 * ordinary outcome for a codec the platform's frame extractor does not cover
 * even though the playback engine does. Counting consecutive failures tells
 * those apart without asking the platform a capability question that an open
 * reader cannot answer.
 */
private const val MaxDecodeFailures = 3

/**
 * The frames behind the scrub preview card.
 *
 * All of the awkwardness here comes from one fact: a frame is expensive and a
 * finger is fast. So requests are quantised to [FrameStepSeconds], only the
 * newest is honoured (the channel is conflated, so a drag that crosses twenty
 * slots decodes the one it stopped on rather than all twenty), decoded frames
 * are cached, and the reader is opened and released around the scrubbing
 * rather than held for the whole of playback.
 *
 * The expense is lopsided, which is what [warm] is for: the first frame out of
 * a freshly opened reader costs around a second on a 4K source and every frame
 * after it around a tenth of that, because the platform builds a decoder on the
 * first decode and then keeps it inside the reader. Whoever pays that second
 * should not be a finger already on the bar.
 *
 * [frameFor] answers only for the slot asked about. It never substitutes a
 * neighbouring frame: the card puts the target timecode on the picture, and a
 * picture from elsewhere under that number is a lie rather than an
 * approximation. Missing is drawn as the placeholder the card shipped with.
 *
 * [scope] must be tied to the screen; the worker and the idle timer are
 * cancelled with it. [close] is still required, because the reader it holds is
 * not owned by that scope.
 */
internal class ScrubPreviewFrames(
    private val source: VideoFrameSource,
    private val url: String,
    private val frameWidthPx: Int,
    private val frameHeightPx: Int,
    private val scope: CoroutineScope,
    /**
     * True when [url] is a file on this device.
     *
     * It decides when the reader is allowed to exist. A local reader costs a
     * file handle and a decoder, so it is opened before the first scrub asks
     * for anything ([warm]) and kept for as long as the scrubber is on screen.
     * A remote one also holds a connection to a host that may be counting
     * them, so it stays lazy: opened by the first request, dropped a while
     * after the last.
     */
    private val local: Boolean = false,
) {
    private val frames = mutableStateMapOf<Long, ImageBitmap>()

    /** Insertion order, for eviction; the snapshot map does not promise one. */
    private val cached = ArrayDeque<Long>()

    private val requests = Channel<Long>(Channel.CONFLATED)

    private var reader: VideoFrameReader? = null
    private var worker: Job? = null
    private var idleRelease: Job? = null
    private var lastRequestedSlot: Long? = null
    private var openFailures = 0
    private var decodeFailures = 0

    /**
     * Whether anything has been decoded through the reader that is open now.
     *
     * The platform builds its decoder on the first decode and keeps it for the
     * life of the reader, so this is the difference between the next frame
     * costing a second and costing a tenth of one. Reopening puts it back.
     */
    private var decodedSinceOpen = false
    private var closed = false

    private val enabled: Boolean
        get() = !closed &&
            frameWidthPx > 0 &&
            frameHeightPx > 0 &&
            openFailures < MaxOpenFailures &&
            decodeFailures < MaxDecodeFailures

    /** The frame for [positionSeconds]'s own slot, or null if it is not decoded. */
    fun frameFor(positionSeconds: Double): ImageBitmap? = frames[slotOf(positionSeconds)]

    /**
     * Asks for the frame at [positionSeconds]. Returns immediately; the frame
     * appears through [frameFor] when it has been decoded, and may never
     * appear at all.
     */
    fun request(positionSeconds: Double) {
        if (!enabled) return
        val slot = slotOf(positionSeconds)
        // The caller feeds this every pointer move, and most moves stay inside
        // the slot they started in. Doing nothing for those is what keeps the
        // idle timer and the channel from churning through a drag.
        if (slot == lastRequestedSlot) return
        lastRequestedSlot = slot
        onActivity()
        if (frames.containsKey(slot)) return
        startWorker()
        requests.trySend(slot)
    }

    /**
     * Opens the reader and decodes one frame at [positionSeconds] before
     * anything has asked to see a picture.
     *
     * Called when the transport appears, so that the second the first decode
     * costs is spent while the viewer is looking at the bar rather than while
     * they are dragging along it. Local sources only: on a remote one the same
     * eagerness would open a connection for a scrub that may never happen.
     */
    fun warm(positionSeconds: Double) {
        if (!local || !enabled) return
        cancelIdleRelease()
        val slot = slotOf(positionSeconds)
        lastRequestedSlot = slot
        startWorker()
        // Sent even when this slot is already cached. The cache holds pictures,
        // not the decoder that made them, and the decoder is the point here.
        requests.trySend(slot)
    }

    /**
     * The scrubber has left the screen, so nothing can ask for a frame until it
     * comes back. Starts the reader's release; [request] and [warm] call it off
     * again. Safe to call repeatedly: a release already pending is left to run
     * rather than pushed further out.
     */
    fun idle() {
        if (idleRelease?.isActive == true) return
        armIdleRelease()
    }

    /** Releases the reader and forgets the frames. Safe to call twice. */
    fun close() {
        if (closed) return
        closed = true
        worker?.cancel()
        worker = null
        cancelIdleRelease()
        releaseReader()
        frames.clear()
        cached.clear()
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (slot in requests) {
                if (!enabled) break
                val open = reader ?: openReader() ?: continue
                // The cache is consulted after the reader is open, and a slot
                // already held is still decoded while nothing has been decoded
                // through this reader: that first decode is the one that builds
                // the platform's decoder, and skipping it would leave warm()
                // having opened a reader that is still cold.
                if (decodedSinceOpen && frames.containsKey(slot)) {
                    onActivity()
                    continue
                }
                val frame = try {
                    open.frameAt(slot.toDouble(), frameWidthPx, frameHeightPx)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }
                if (frame == null) {
                    decodeFailures += 1
                } else {
                    decodeFailures = 0
                    decodedSinceOpen = true
                    cache(slot, frame)
                }
                onActivity()
            }
        }
    }

    private suspend fun openReader(): VideoFrameReader? {
        val opened = try {
            source.open(url)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }
        if (opened == null) {
            openFailures += 1
            return null
        }
        // A close that landed while the open was in flight owns this reader's
        // fate: nothing else will release it once close() has already run.
        if (closed) {
            opened.close()
            return null
        }
        openFailures = 0
        reader = opened
        return opened
    }

    private fun cache(slot: Long, frame: ImageBitmap) {
        frames[slot] = frame
        cached.addLast(slot)
        while (cached.size > MaxCachedFrames) {
            frames.remove(cached.removeFirst())
        }
    }

    /**
     * A remote reader is dropped a while after the last thing that needed it,
     * because it is holding a connection someone may be counting. A local one
     * only has to outlive the scrubber's time on screen, and outliving it is
     * exactly what keeps its decoder warm, so activity calls a pending release
     * off rather than pushing it further out.
     */
    private fun onActivity() {
        if (local) cancelIdleRelease() else armIdleRelease()
    }

    /**
     * Frames already decoded survive the release. They cost nothing to keep and
     * a viewer who comes back to the scrubber is usually coming back to the
     * same part of the timeline.
     */
    private fun armIdleRelease() {
        cancelIdleRelease()
        if (closed) return
        idleRelease = scope.launch {
            delay(ReaderIdleMillis)
            releaseReader()
        }
    }

    private fun cancelIdleRelease() {
        idleRelease?.cancel()
        idleRelease = null
    }

    private fun releaseReader() {
        reader?.close()
        reader = null
        decodedSinceOpen = false
    }

    private fun slotOf(positionSeconds: Double): Long {
        val position = if (positionSeconds.isFinite()) positionSeconds.coerceAtLeast(0.0) else 0.0
        val step = FrameStepSeconds.toLong()
        return (position / FrameStepSeconds).toLong() * step
    }
}
