package moe.ditto.halo.player

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Still frames pulled out of a video without playing it.
 *
 * Deliberately not part of [PlayerPort], for the same reason [PlayerSystemPort]
 * is not: this is not the engine that is playing. It cannot be, either. A frame
 * from somewhere else in the timeline is only obtainable by seeking there, and
 * the one core that is playing must not be seeked to fetch a picture of where
 * the viewer has not gone yet. So this is a second reader over the same source,
 * and every implementation of it will be.
 *
 * That second reader is why it opens late and closes early (see
 * `ScrubPreviewFrames`): it holds its own connection to the source, and some
 * hosts count those.
 *
 * [ImageBitmap] rather than a pixel buffer because it is the neutral image
 * currency of Compose Multiplatform, and the alternative would have every
 * platform encode a bitmap that the one consumer immediately decodes again. It
 * is a graphics type, not a widget.
 */
interface VideoFrameSource {
    /**
     * Opens a reader over [url], or null when this platform cannot read it.
     *
     * Null is an ordinary answer rather than a failure: platform decoders cover
     * less than the playback engine does, so a file that plays perfectly may
     * still have no frames to offer. The caller shows what it showed before
     * frames existed.
     *
     * May block on the network; implementations are responsible for not
     * blocking the calling thread.
     */
    suspend fun open(url: String): VideoFrameReader?
}

interface VideoFrameReader {
    /**
     * The frame at or before [positionSeconds], scaled to fit within
     * [widthPx] x [heightPx] with its aspect ratio kept, or null when there is
     * no frame to be had there.
     *
     * "At or before" is the nearest preceding keyframe rather than the exact
     * position: an exact frame means decoding forward from that keyframe, which
     * costs the whole interval for a picture that is glanced at.
     *
     * May block; implementations are responsible for not blocking the calling
     * thread, and for serialising concurrent calls if their reader demands it.
     */
    suspend fun frameAt(positionSeconds: Double, widthPx: Int, heightPx: Int): ImageBitmap?

    /**
     * Releases the reader and whatever connection it holds. Returns
     * immediately; a decode already in flight finishes first. Safe to call
     * twice, and no [frameAt] succeeds afterwards.
     */
    fun close()
}

/** For platforms with no implementation yet, and for tests. */
object NoVideoFrameSource : VideoFrameSource {
    override suspend fun open(url: String): VideoFrameReader? = null
}
