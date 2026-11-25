package moe.ditto.halo.player

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
    suspend fun fingerprint(
        url: String,
        knownSizeBytes: Long? = null,
        /** Request headers the source needs; without them a host that wants a Referer refuses the reads. */
        headers: Map<String, String> = emptyMap(),
    ): VideoFingerprint? = try {
        hash(url, knownSizeBytes, playbackHeaders(headers))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    private suspend fun hash(url: String, knownSizeBytes: Long?, headers: Map<String, String>): VideoFingerprint? {
        val declared = knownSizeBytes?.takeIf { it > 0 }
        val source = if (declared != null) ResolvedSource(url, declared) else resolve(url, headers) ?: return null
        val size = source.sizeBytes
        if (size < VideoHash.MinimumHashableBytes) return null

        val last = size - 1
        val chunks = coroutineScope {
            val head = async { rangeBytes(source.url, headers, 0, VideoHash.ChunkBytes - 1L) }
            val tail = async { rangeBytes(source.url, headers, size - VideoHash.ChunkBytes, last) }
            listOf(head.await(), tail.await())
        }
        val (head, tail) = chunks
        if (head == null || tail == null) return null

        return VideoFingerprint(VideoHash.fromChunks(size, head, tail), size)
    }

    /** Where the link led and how big the file is. */
    private data class ResolvedSource(val url: String, val sizeBytes: Long)

    /**
     * One byte asked for with a ranged GET, never a HEAD: a debrid addon's play
     * link may answer HEAD differently from GET (ElfHosted sends HEAD on a good
     * link to its placeholder clip), and the hash must describe the file the
     * player gets. The size comes from `Content-Range`, and the chunks are then
     * read from where the redirects ended, so both come from that one file and
     * the play link is resolved once. The body is never read: a host that
     * ignores the range would be sending the whole file.
     */
    private suspend fun resolve(url: String, headers: Map<String, String>): ResolvedSource? =
        http.prepareGet(url) {
            sourceHeaders(headers)
            header(HttpHeaders.Range, "bytes=0-0")
        }.execute { probe ->
            if (probe.status != HttpStatusCode.PartialContent) return@execute null
            val size = probe.headers[HttpHeaders.ContentRange]
                ?.substringAfterLast('/')
                ?.takeIf { it != "*" }
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: return@execute null
            ResolvedSource(probe.call.request.url.toString(), size)
        }

    /**
     * Anything but 206 means the host ignored the range and is about to send
     * the whole file, which for a hash would be a download of gigabytes.
     */
    private suspend fun rangeBytes(url: String, headers: Map<String, String>, from: Long, to: Long): ByteArray? =
        http.prepareGet(url) {
            sourceHeaders(headers)
            header(HttpHeaders.Range, "bytes=$from-$to")
        }.execute { response ->
            if (response.status != HttpStatusCode.PartialContent) return@execute null
            response.readRawBytes().takeIf { it.size.toLong() == to - from + 1 }
        }
}

/** The source's own headers first, so a request's Range, set after, is the one sent. */
private fun HttpRequestBuilder.sourceHeaders(headers: Map<String, String>) {
    headers.forEach { (name, value) -> header(name, value) }
}
