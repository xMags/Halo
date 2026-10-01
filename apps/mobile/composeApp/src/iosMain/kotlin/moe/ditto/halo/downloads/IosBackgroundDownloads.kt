@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.ditto.halo.downloads

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/** Narrow Swift host boundary. All callback payloads are URL-free. */
interface HaloIosBackgroundDownloadHost {
    fun setDownloadEventSink(sink: HaloIosDownloadEventSink?)

    fun storeProtectedRequest(
        jobId: String,
        sourceUrl: String,
        partFilePath: String,
        targetFilePath: String,
        resumeValidator: String?,
    ): Boolean

    fun deleteProtectedRequest(jobId: String)
    fun reconcile(completion: (String) -> Unit)
    fun enqueue(jobId: String)
    fun pause(jobId: String, completion: () -> Unit)
    fun resume(jobId: String)
    fun cancel(jobId: String, completion: () -> Unit)
    fun requestNotificationAuthorization()
}

interface HaloIosDownloadEventSink {
    fun onProgress(jobId: String, downloadedBytes: Long, totalBytes: Long)
    fun onCompleted(jobId: String, downloadedBytes: Long, totalBytes: Long)
    fun onPaused(jobId: String, downloadedBytes: Long, totalBytes: Long)
    fun onFailed(jobId: String, failureCode: String)
}

@Serializable
private data class IosJobSnapshotJson(
    val jobId: String,
    val state: String,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val failureCode: String? = null,
)

internal class IosDownloadRequestVault(
    private val host: HaloIosBackgroundDownloadHost,
) : DownloadRequestVault {
    override fun write(request: ProtectedDownloadRequest): Boolean = host.storeProtectedRequest(
        jobId = request.jobId,
        sourceUrl = request.sourceUrl,
        partFilePath = request.partFilePath,
        targetFilePath = request.targetFilePath,
        resumeValidator = request.resumeValidator,
    )

    override fun delete(jobId: String) {
        host.deleteProtectedRequest(jobId)
    }
}

internal class IosBackgroundDownloadPort(
    private val host: HaloIosBackgroundDownloadHost,
) : BackgroundDownloadPort, HaloIosDownloadEventSink {
    private val channel = Channel<BackgroundDownloadEvent>(Channel.UNLIMITED)

    override val events: Flow<BackgroundDownloadEvent> = channel.receiveAsFlow()
    override val resumesMigratedPartialFiles: Boolean = false

    init {
        host.setDownloadEventSink(this)
    }

    override suspend fun reconcile(): List<BackgroundDownloadJob> =
        suspendCancellableCoroutine { continuation ->
            host.reconcile { payload ->
                if (!continuation.isActive) return@reconcile
                val snapshots = runCatching {
                    BridgeJson.decodeFromString<List<IosJobSnapshotJson>>(payload)
                }.getOrDefault(emptyList())
                continuation.resume(snapshots.mapNotNull { it.toJob() })
            }
        }

    override suspend fun enqueue(request: ProtectedDownloadRequest) {
        host.requestNotificationAuthorization()
        host.enqueue(request.jobId)
    }

    override suspend fun pause(jobId: String) = suspendCancellableCoroutine { continuation ->
        host.pause(jobId) {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    override suspend fun resume(jobId: String) {
        host.requestNotificationAuthorization()
        host.resume(jobId)
    }

    override suspend fun cancel(jobId: String) = suspendCancellableCoroutine { continuation ->
        host.cancel(jobId) {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    override fun onProgress(jobId: String, downloadedBytes: Long, totalBytes: Long) {
        channel.trySend(BackgroundDownloadEvent.Progress(jobId, downloadedBytes, totalBytes))
    }

    override fun onCompleted(jobId: String, downloadedBytes: Long, totalBytes: Long) {
        channel.trySend(BackgroundDownloadEvent.Completed(jobId, downloadedBytes, totalBytes))
    }

    override fun onPaused(jobId: String, downloadedBytes: Long, totalBytes: Long) {
        channel.trySend(BackgroundDownloadEvent.Paused(jobId, downloadedBytes, totalBytes))
    }

    override fun onFailed(jobId: String, failureCode: String) {
        channel.trySend(
            BackgroundDownloadEvent.Failed(
                jobId,
                DownloadFailure(failureCode.toFailureCode()),
            ),
        )
    }

    private fun IosJobSnapshotJson.toJob(): BackgroundDownloadJob? {
        val jobState = when (state) {
            "enqueued" -> BackgroundDownloadJobState.Enqueued
            "running" -> BackgroundDownloadJobState.Running
            "paused" -> BackgroundDownloadJobState.Paused
            "succeeded" -> BackgroundDownloadJobState.Succeeded
            "failed" -> BackgroundDownloadJobState.Failed
            "cancelled" -> BackgroundDownloadJobState.Cancelled
            else -> return null
        }
        return BackgroundDownloadJob(
            jobId = jobId,
            state = jobState,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            failure = failureCode?.let { DownloadFailure(it.toFailureCode()) },
        )
    }

    private companion object {
        val BridgeJson = Json { ignoreUnknownKeys = true }
    }
}

private fun String.toFailureCode(): DownloadFailureCode = when (this) {
    "source_expired" -> DownloadFailureCode.SourceExpired
    "storage_full" -> DownloadFailureCode.StorageFull
    "protected_request_corrupt" -> DownloadFailureCode.ProtectedRequestCorrupt
    "invalid_range" -> DownloadFailureCode.InvalidRange
    "missing_file" -> DownloadFailureCode.MissingFile
    "network" -> DownloadFailureCode.Network
    "server_unavailable" -> DownloadFailureCode.ServerUnavailable
    "source_rejected" -> DownloadFailureCode.SourceRejected
    else -> DownloadFailureCode.Unknown
}
