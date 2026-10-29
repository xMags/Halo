package moe.ditto.halo.downloads

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import moe.ditto.halo.auth.EpochClock
import okio.FileSystem
import okio.Path

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
    /** The source's HTTP status when it answered with an error; for diagnostics only. */
    val httpStatus: Int? = null,
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
     * already holds, and moves it onto [target] once it is whole. A part
     * written over several connections is described by a [SegmentSidecar]
     * beside it, which goes once the part is whole.
     *
     * Cancellation is how a pause is expressed: the partial file and its record
     * are left intact and the next call continues from them. Returns the final progress, which is
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
 * The shared implementation: ranged GETs, written to one partial file.
 *
 * Every download starts as one GET, exactly as a single-connection transfer
 * would. Resuming is a `Range` request guarded by `If-Range`. The guard is the
 * part that matters. Sources behind a resolver are re-minted links to files
 * that may have been replaced, and appending fresh bytes to a stale prefix
 * produces a file that is exactly the right length and unplayable. When the
 * validator no longer matches, the source answers 200 rather than 206 and this
 * restarts from the beginning instead.
 *
 * Debrid CDNs cap each connection's speed, so a source that has declared its
 * size, offered a validator and served a range in this run is then read over
 * up to [SegmentPlan.MaxConnections] connections, opened one at a time (see
 * [SegmentedTransferRun]). A source missing any of the three is read exactly as
 * before, over the one connection.
 */
internal class HttpRangeTransfer(
    private val http: HttpClient,
    private val fileSystem: FileSystem,
    private val clock: EpochClock,
    /**
     * How long a connection may receive nothing before it counts as dead.
     * Mobile links stall without closing, and no timeout is configured on the
     * client because a legitimate download runs for an hour.
     */
    private val stallTimeoutMs: Long = DefaultStallTimeoutMs,
    /** Progress is reported at most this often; it arrives far faster than a screen needs. */
    private val progressIntervalMs: Long = DefaultProgressIntervalMs,
    private val tuning: SegmentTuning = SegmentTuning(),
) : DownloadTransfer {

    override suspend fun transfer(
        sourceUrl: String,
        partFile: Path,
        target: Path,
        resumeValidator: String?,
        onProgress: suspend (TransferProgress) -> Unit,
    ): TransferProgress = SegmentedTransferRun(
        http = http,
        fileSystem = fileSystem,
        clock = clock,
        stallTimeoutMs = stallTimeoutMs,
        progressIntervalMs = progressIntervalMs,
        tuning = tuning,
        sourceUrl = sourceUrl,
        partFile = partFile,
        target = target,
        resumeValidator = resumeValidator,
        onProgress = onProgress,
    ).run()

    internal companion object {
        const val BufferSize = 64 * 1024
        const val DefaultStallTimeoutMs = 60_000L
        const val DefaultProgressIntervalMs = 500L
    }
}

/** `Content-Range: bytes 1024-2047/4096`: the start. */
internal fun HttpResponse.contentRangeStart(): Long? = headers[HttpHeaders.ContentRange]
    ?.substringAfter("bytes ", "")
    ?.substringBefore('-', "")
    ?.trim()
    ?.toLongOrNull()

/** `Content-Range: bytes 1024-2047/4096`: the whole file's size, or null when it says `*` or nothing. */
internal fun HttpResponse.contentRangeTotal(): Long? = headers[HttpHeaders.ContentRange]
    ?.substringAfterLast('/', "")
    ?.trim()
    ?.takeIf { it != "*" }
    ?.toLongOrNull()
    ?.takeIf { it > 0 }

/**
 * The size of the whole file, from the range header when the source sent
 * one and from the body length plus the offset otherwise. Zero means the
 * source never said, which chunked responses do not.
 */
internal fun HttpResponse.declaredTotal(startAt: Long): Long {
    contentRangeTotal()?.let { return it }
    val length = headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return 0L
    return startAt + length
}

/** `ETag` if the source offers one, `Last-Modified` otherwise, and nothing if neither. */
internal fun HttpResponse.validator(): String? =
    headers[HttpHeaders.ETag]?.takeIf { it.isNotBlank() }
        ?: headers[HttpHeaders.LastModified]?.takeIf { it.isNotBlank() }

/** `Retry-After` in its seconds form; the date form is rare enough to fall back to the default delays. */
internal fun HttpResponse.retryAfterSeconds(): Long? =
    headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it > 0 }

internal fun statusFailure(status: HttpStatusCode): DownloadFailure = when {
    status == HttpStatusCode.Forbidden || status == HttpStatusCode.Unauthorized ->
        DownloadFailure(DownloadFailureCode.SourceExpired)
    status == HttpStatusCode.RequestTimeout || status == HttpStatusCode.TooManyRequests || status.value >= 500 ->
        DownloadFailure(DownloadFailureCode.ServerUnavailable)
    status == HttpStatusCode.RequestedRangeNotSatisfiable ->
        DownloadFailure(DownloadFailureCode.InvalidRange)
    else -> DownloadFailure(DownloadFailureCode.SourceRejected)
}
