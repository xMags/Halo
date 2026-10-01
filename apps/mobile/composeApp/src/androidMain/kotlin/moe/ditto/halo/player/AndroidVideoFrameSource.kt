package moe.ditto.halo.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Android's answer to [VideoFrameSource], over the platform's own extractor
 * rather than over libmpv.
 *
 * libmpv would have been the obvious choice — it is already in the process and
 * it opens everything. It cannot answer this question, for two separate
 * reasons. The core that is playing must not be seeked to fetch a frame from
 * somewhere the viewer has not gone, so any mpv route means a second core; and
 * this module's JNI binding exposes no data-returning command, so even that
 * second core could not hand a frame back in memory. It would have to write a
 * screenshot to a file per frame and have it decoded again.
 *
 * The cost of the platform extractor is honest and bounded: it decodes only
 * what the device's own codecs cover, which is less than mpv covers. A source
 * it will not open reports so once, and the scrub card falls back to the
 * timecode over a placeholder, which is what it showed before frames existed.
 */
internal class AndroidVideoFrameSource : VideoFrameSource {
    override suspend fun open(url: String): VideoFrameReader? {
        val reader = AndroidVideoFrameReader()
        if (reader.openSource(url)) return reader
        reader.close()
        return null
    }
}

/**
 * [MediaMetadataRetriever] is not thread-safe and every call into it blocks, so
 * the whole reader is confined to one thread of its own. Confinement is also
 * what makes [close] safe without a lock: the release runs behind whatever
 * decode was already queued.
 */
private class AndroidVideoFrameReader : VideoFrameReader {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "halo-scrub-frames").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val retriever = MediaMetadataRetriever()

    @Volatile private var closed = false

    /**
     * Opening reads the container's header off the source, so on a remote file
     * this is a network round trip and belongs on the worker like everything
     * else. A failure is reported rather than thrown: an unreadable source is
     * an expected outcome here, not an error.
     */
    suspend fun openSource(url: String): Boolean = withContext(worker) {
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                // The headers overload is what accepts a remote URL at all. No
                // header is sent: stream URLs are the addon's own direct links,
                // and a session token must never ride along to one.
                retriever.setDataSource(url, emptyMap<String, String>())
            } else {
                retriever.setDataSource(url)
            }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            false
        }
    }

    override suspend fun frameAt(positionSeconds: Double, widthPx: Int, heightPx: Int): ImageBitmap? {
        if (closed || widthPx <= 0 || heightPx <= 0) return null
        val timeUs = (positionSeconds.coerceAtLeast(0.0) * MicrosecondsPerSecond).toLong()
        return withContext(worker) {
            // Re-checked on the worker, not only at the call: a close that
            // arrives while this was queued has already scheduled the release
            // behind it, and decoding into a retriever about to be released is
            // the one ordering this confinement exists to prevent.
            if (closed) return@withContext null
            try {
                scaledFrame(timeUs, widthPx, heightPx)?.asImageBitmap()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.executor.execute { runCatching { retriever.release() } }
        // Shuts the thread down once that release has run; tasks already
        // queued are not discarded.
        worker.close()
    }

    private fun scaledFrame(timeUs: Long, widthPx: Int, heightPx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                widthPx,
                heightPx,
            )
        }
        // API 26 has no scaled variant. A full-size frame off a 4K source is
        // around 33 MB, and the card that displays it is a couple of hundred
        // pixels wide, so it is reduced before anything holds on to it.
        val full = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        return full.reducedTo(widthPx, heightPx)
    }

    private companion object {
        const val MicrosecondsPerSecond = 1_000_000.0
    }
}

/**
 * Fits the bitmap inside [maxWidth] x [maxHeight] without stretching it, and
 * never enlarges: a frame from a source smaller than the card is left alone
 * rather than blown up to fill it.
 */
private fun Bitmap.reducedTo(maxWidth: Int, maxHeight: Int): Bitmap {
    if (width <= 0 || height <= 0) return this
    val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    if (scale >= 1f) return this
    val scaled = Bitmap.createScaledBitmap(
        this,
        (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== this) recycle()
    return scaled
}
