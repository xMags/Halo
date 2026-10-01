package moe.ditto.halo.downloads

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeoutOrNull
import moe.ditto.halo.auth.EpochClock
import okio.FileSystem
import okio.Path
import okio.buffer

/** How far a transfer has got, and what the source said about resuming it. */
internal data class TransferProgress(
    val downloadedBytes: Long,
    /** Zero when the source declared no size. */
    val totalBytes: Long,
    val validator: String?,
)

/**
 * The transfer failed for a reason worth showing. Messages never carry the
 * source URL: resolved stream URLs routinely embed a debrid token.
 */
internal class DownloadTransferException(
    val failure: DownloadFailure,
) : Exception(failure.message) {
    constructor(message: String) : this(DownloadFailure(DownloadFailureCode.Unknown))
}

/**
 * Moves one source's bytes onto the device.
 *
 * Android runs this implementation inside its WorkManager worker. iOS uses a
 * background URLSession instead, while preserving this file and range logic as
 * the Android transfer boundary.
 */
internal interface DownloadTransfer {
    /**
     * Writes [sourceUrl] into [partFile], resuming from whatever that file
     * already holds, and moves it onto [target] once it is whole.
     *
     * Cancellation is how a pause is expressed: the partial file is left intact
     * and the next call continues from it. Returns the final progress, which is
     * the only trustworthy byte count for a finished entry.
     */
    suspend fun transfer(
        sourceUrl: String,
        partFile: Path,
        target: Path,
        resumeValidator: String?,
        onProgress: suspend (TransferProgress) -> Unit,
    ): TransferProgress
}

/**
 * The shared implementation: one ranged GET, streamed to disk.
 *
 * Resuming is a `Range` request guarded by `If-Range`. The guard is the part
 * that matters. Sources behind a resolver are re-minted links to files that may
 * have been replaced, and appending fresh bytes to a stale prefix produces a
 * file that is exactly the right length and unplayable. When the validator no
 * longer matches, the source answers 200 rather than 206 and this restarts from
 * the beginning instead.
 */
internal class HttpRangeTransfer(
    private val http: HttpClient,
    private val fileSystem: FileSystem,
    private val clock: EpochClock,
    /**
     * How long a transfer may receive nothing before it counts as dead. Mobile
     * links stall without closing, and no timeout is configured on the client
     * because a legitimate download runs for an hour.
     */
    private val stallTimeoutMs: Long = DefaultStallTimeoutMs,
    /** Progress is reported at most this often; it arrives far faster than a screen needs. */
    private val progressIntervalMs: Long = DefaultProgressIntervalMs,
) : DownloadTransfer {

    override suspend fun transfer(
        sourceUrl: String,
        partFile: Path,
        target: Path,
        resumeValidator: String?,
        onProgress: suspend (TransferProgress) -> Unit,
    ): TransferProgress {
        partFile.parent?.let { fileSystem.createDirectories(it) }
        val existing = fileSystem.metadataOrNull(partFile)?.size?.takeIf { it > 0 } ?: 0L

        return http.prepareGet(sourceUrl) {
            if (existing > 0) {
                header(HttpHeaders.Range, "bytes=$existing-")
                resumeValidator?.let { header(HttpHeaders.IfRange, it) }
            }
        }.execute { response ->
            val startAt = resumeOffset(response, existing, partFile)
            val validator = response.validator()
            val total = declaredTotal(response, startAt)
            val written = stream(response, partFile, startAt, total, validator, onProgress)

            if (total > 0 && written != total) {
                throw DownloadTransferException(DownloadFailure(DownloadFailureCode.Network))
            }
            fileSystem.atomicMove(partFile, target)
            TransferProgress(downloadedBytes = written, totalBytes = if (total > 0) total else written, validator = validator)
        }
    }

    /**
     * Where the response's bytes belong in the file, which is not always where
     * they were asked for.
     *
     * A 200 to a ranged request means the source ignored the range, or the
     * `If-Range` guard rejected it: either way the body is the whole file and
     * the partial one is worthless. A 206 starting anywhere other than where it
     * was asked to cannot be placed, so the partial file goes and the next
     * attempt starts clean rather than writing bytes at the wrong offset.
     */
    private fun resumeOffset(response: HttpResponse, existing: Long, partFile: Path): Long {
        if (response.status == HttpStatusCode.OK) return 0L
        if (response.status != HttpStatusCode.PartialContent) {
            throw DownloadTransferException(statusFailure(response.status))
        }
        if (existing == 0L) return 0L
        val start = response.contentRangeStart()
        if (start == existing) return existing
        fileSystem.delete(partFile, mustExist = false)
        throw DownloadTransferException(DownloadFailure(DownloadFailureCode.InvalidRange))
    }

    private suspend fun stream(
        response: HttpResponse,
        partFile: Path,
        startAt: Long,
        total: Long,
        validator: String?,
        onProgress: suspend (TransferProgress) -> Unit,
    ): Long {
        val channel = response.bodyAsChannel()
        val sink = if (startAt > 0) {
            fileSystem.appendingSink(partFile).buffer()
        } else {
            fileSystem.sink(partFile).buffer()
        }
        var written = startAt
        var lastReport = clock.nowMs()
        // Reported before a single byte is read, because this is what carries
        // the validator: a transfer paused inside the throttle window would
        // otherwise have nothing to guard its own resume with.
        onProgress(TransferProgress(written, total, validator))
        try {
            val buffer = ByteArray(BufferSize)
            while (true) {
                val read = withTimeoutOrNull(stallTimeoutMs) {
                    channel.readAvailable(buffer, 0, buffer.size)
                } ?: throw DownloadTransferException(DownloadFailure(DownloadFailureCode.Network))
                if (read < 0) break
                if (read == 0) continue
                sink.write(buffer, 0, read)
                written += read
                val now = clock.nowMs()
                if (now - lastReport < progressIntervalMs) continue
                lastReport = now
                // Flushed before the number is published, so a process that dies
                // straight afterwards has the bytes the index claims it has.
                sink.flush()
                onProgress(TransferProgress(written, total, validator))
            }
        } finally {
            sink.close()
        }
        return written
    }

    /** `Content-Range: bytes 1024-2047/4096`, of which only the start is load-bearing here. */
    private fun HttpResponse.contentRangeStart(): Long? = headers[HttpHeaders.ContentRange]
        ?.substringAfter("bytes ", "")
        ?.substringBefore('-', "")
        ?.trim()
        ?.toLongOrNull()

    /**
     * The size of the whole file, from the range header when the source sent
     * one and from the body length plus the offset otherwise. Zero means the
     * source never said, which chunked responses do not.
     */
    private fun declaredTotal(response: HttpResponse, startAt: Long): Long {
        val fromRange = response.headers[HttpHeaders.ContentRange]
            ?.substringAfterLast('/')
            ?.takeIf { it != "*" }
            ?.toLongOrNull()
        if (fromRange != null && fromRange > 0) return fromRange
        val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return 0L
        return startAt + length
    }

    /** `ETag` if the source offers one, `Last-Modified` otherwise, and nothing if neither. */
    private fun HttpResponse.validator(): String? =
        headers[HttpHeaders.ETag]?.takeIf { it.isNotBlank() }
            ?: headers[HttpHeaders.LastModified]?.takeIf { it.isNotBlank() }

    private fun statusFailure(status: HttpStatusCode): DownloadFailure = when {
        status == HttpStatusCode.Forbidden || status == HttpStatusCode.Unauthorized ->
            DownloadFailure(DownloadFailureCode.SourceExpired)
        status == HttpStatusCode.RequestTimeout || status == HttpStatusCode.TooManyRequests || status.value >= 500 ->
            DownloadFailure(DownloadFailureCode.ServerUnavailable)
        status == HttpStatusCode.RequestedRangeNotSatisfiable ->
            DownloadFailure(DownloadFailureCode.InvalidRange)
        else -> DownloadFailure(DownloadFailureCode.SourceRejected)
    }

    private companion object {
        const val BufferSize = 64 * 1024
        const val DefaultStallTimeoutMs = 60_000L
        const val DefaultProgressIntervalMs = 500L
    }
}
