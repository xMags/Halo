package moe.ditto.halo.downloads

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The sanitized reason a platform-owned transfer stopped permanently.
 *
 * This type is deliberately closed. Platform exceptions, response bodies,
 * headers and source URLs must never cross into the ordinary download index or
 * a notification.
 */
@Serializable
internal enum class DownloadFailureCode {
    @SerialName("source_expired")
    SourceExpired,

    @SerialName("storage_full")
    StorageFull,

    @SerialName("protected_request_corrupt")
    ProtectedRequestCorrupt,

    @SerialName("invalid_range")
    InvalidRange,

    @SerialName("missing_file")
    MissingFile,

    @SerialName("network")
    Network,

    @SerialName("server_unavailable")
    ServerUnavailable,

    @SerialName("source_rejected")
    SourceRejected,

    @SerialName("unknown")
    Unknown,
}

@Serializable
internal data class DownloadFailure(val code: DownloadFailureCode) {
    val message: String
        get() = when (code) {
            DownloadFailureCode.SourceExpired ->
                "This source has expired. Choose a source again to continue."
            DownloadFailureCode.StorageFull ->
                "The device ran out of storage while downloading."
            DownloadFailureCode.ProtectedRequestCorrupt ->
                "The protected download request could not be read. Choose the source again."
            DownloadFailureCode.InvalidRange ->
                "The source could not safely resume this download."
            DownloadFailureCode.MissingFile ->
                "This download is no longer on the device."
            DownloadFailureCode.Network ->
                "The download could not continue after repeated network failures."
            DownloadFailureCode.ServerUnavailable ->
                "The source is still unavailable after repeated retries."
            DownloadFailureCode.SourceRejected ->
                "The source refused this download."
            DownloadFailureCode.Unknown ->
                "This download could not be completed."
        }

    val requiresNewSource: Boolean
        get() = code == DownloadFailureCode.SourceExpired ||
            code == DownloadFailureCode.ProtectedRequestCorrupt
}

/**
 * The only record containing a resolved source URL.
 *
 * Implementations persist this in Android Keystore-backed encrypted storage or
 * an iOS Keychain item. It must never be put in worker input, the ordinary
 * index, an event, a notification or a diagnostic.
 */
@Serializable
internal data class ProtectedDownloadRequest(
    val jobId: String,
    val sourceUrl: String,
    val partFilePath: String,
    val targetFilePath: String,
    val resumeValidator: String? = null,
)

/** The protected store is separate so v1 migration can write it before v2. */
internal interface DownloadRequestVault {
    /** False is a sanitized storage failure. Implementations must not log [request]. */
    fun write(request: ProtectedDownloadRequest): Boolean

    fun delete(jobId: String)
}

internal enum class BackgroundDownloadJobState {
    Enqueued,
    Running,
    Paused,
    Succeeded,
    Failed,
    Cancelled,
}

/** A URL-free view of work already owned by the operating system. */
internal data class BackgroundDownloadJob(
    val jobId: String,
    val state: BackgroundDownloadJobState,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val failure: DownloadFailure? = null,
)

/** Events are URL-free and keyed by job, so a replaced attempt cannot win. */
internal sealed interface BackgroundDownloadEvent {
    val jobId: String

    data class Progress(
        override val jobId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : BackgroundDownloadEvent

    data class Completed(
        override val jobId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : BackgroundDownloadEvent

    data class Paused(
        override val jobId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : BackgroundDownloadEvent

    data class Failed(
        override val jobId: String,
        val failure: DownloadFailure,
    ) : BackgroundDownloadEvent
}

/**
 * Platform ownership boundary for durable transfers.
 *
 * Only the active queue entry is submitted. Android maps it to unique
 * WorkManager work; iOS maps it to a background URLSession task whose
 * taskDescription is [ProtectedDownloadRequest.jobId].
 */
internal interface BackgroundDownloadPort {
    val events: Flow<BackgroundDownloadEvent>

    /**
     * True on Android, where the ranged worker can append an existing v1 part.
     * iOS keeps that part untouched and starts its background task from zero.
     */
    val resumesMigratedPartialFiles: Boolean

    suspend fun reconcile(): List<BackgroundDownloadJob>
    suspend fun enqueue(request: ProtectedDownloadRequest)
    suspend fun pause(jobId: String)
    suspend fun resume(jobId: String)
    suspend fun cancel(jobId: String)
}
