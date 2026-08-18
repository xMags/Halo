package moe.ditto.halo.player

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** What a hashed source is, in the two extras subtitle addons match on. */
internal data class VideoFingerprint(val hash: String, val sizeBytes: Long)

/**
 * Hashes a streaming source over HTTP, reading only the 128 KiB the hash is
 * defined over.
 *
 * Every failure returns null rather than throwing. A host that refuses range
 * requests, a link that has already expired, a file too short to hash: none of
 * those are reasons to interrupt playback, and the caller's fallback is a
 * name-based subtitle search that still works, only less precisely.
 *
 * The request goes to the source URL directly, which is the same URL the engine
 * is already streaming from, so this exposes nothing the player did not have.
 */
internal class StreamVideoHasher(private val http: HttpClient) {

    /**
     * [knownSizeBytes] is the size the addon already declared in its behaviour
     * hints. Supplying it removes the two requests this would otherwise spend
     * discovering something the caller was told, which matters because the
     * source host is often the same rate-limited resolver the engine is
     * streaming through. A declared size that turns out to be wrong yields a
     * hash no subtitle matches, which is the same outcome as not hashing.
     */
    suspend fun fingerprint(url: String, knownSizeBytes: Long? = null): VideoFingerprint? = try {
        hash(url, knownSizeBytes)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    private suspend fun hash(url: String, knownSizeBytes: Long?): VideoFingerprint? {
        val size = knownSizeBytes?.takeIf { it > 0 } ?: contentLength(url) ?: return null
        if (size < VideoHash.MinimumHashableBytes) return null

        val last = size - 1
        val chunks = coroutineScope {
            val head = async { rangeBytes(url, 0, VideoHash.ChunkBytes - 1L) }
            val tail = async { rangeBytes(url, size - VideoHash.ChunkBytes, last) }
            listOf(head.await(), tail.await())
        }
        val (head, tail) = chunks
        if (head == null || tail == null) return null

        return VideoFingerprint(VideoHash.fromChunks(size, head, tail), size)
    }

    /**
     * HEAD first, then a one-byte range: some hosts answer HEAD with nothing
     * useful but still report the total in `Content-Range`.
     */
    private suspend fun contentLength(url: String): Long? {
        val head: HttpResponse = http.head(url)
        if (head.status.isSuccess()) {
            head.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        }

        val probe = http.get(url) { header(HttpHeaders.Range, "bytes=0-0") }
        if (probe.status != HttpStatusCode.PartialContent) return null
        return probe.headers[HttpHeaders.ContentRange]
            ?.substringAfterLast('/')
            ?.takeIf { it != "*" }
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
    }

    /**
     * Anything but 206 means the host ignored the range and is about to send
     * the whole file, which for a hash would be a download of gigabytes.
     */
    private suspend fun rangeBytes(url: String, from: Long, to: Long): ByteArray? {
        val response = http.get(url) { header(HttpHeaders.Range, "bytes=$from-$to") }
        if (response.status != HttpStatusCode.PartialContent) return null
        val bytes = response.readRawBytes()
        return bytes.takeIf { it.size.toLong() == to - from + 1 }
    }
}
