package moe.ditto.halo.downloads

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import moe.ditto.halo.auth.SystemEpochClock
import okhttp3.Dispatcher
import okhttp3.Protocol
import okio.FileSystem
import okio.Path.Companion.toPath

/** Persistent ranged worker. Its input contains only an opaque job ID. */
internal class HaloDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val vault = AndroidDownloadRequestVault(appContext)
    private val notifications = AndroidDownloadNotifications(appContext)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(AndroidDownloadWorkContract.JobId)
            ?: return terminalFailure("", DownloadFailure(DownloadFailureCode.ProtectedRequestCorrupt))
        return AndroidDownloadJobLifetimes.run(jobId) { transfer(jobId) }
    }

    private suspend fun transfer(jobId: String): Result {
        val request = when (val stored = vault.read(jobId)) {
            is ProtectedRequestRead.Found -> stored.request
            ProtectedRequestRead.Missing,
            ProtectedRequestRead.Corrupt,
            -> return terminalFailure(jobId, DownloadFailure(DownloadFailureCode.ProtectedRequestCorrupt))
        }
        if (!request.isSafeFor(applicationContext)) {
            return terminalFailure(jobId, DownloadFailure(DownloadFailureCode.ProtectedRequestCorrupt))
        }

        setForeground(notifications.foreground(jobId, 0, 0))
        val http = HttpClient(OkHttp) {
            engine {
                config {
                    // OkHttp's own default gives a server 10 seconds to start
                    // answering, and a debrid link resolving a file for the
                    // first time often takes longer: every attempt then died
                    // before its first byte and was retried until the job
                    // failed. The transfer's stall limit is the one rule for
                    // silence, so the socket waits as long as it does.
                    connectTimeout(ConnectTimeoutSeconds, TimeUnit.SECONDS)
                    readTimeout(HttpRangeTransfer.DefaultStallTimeoutMs, TimeUnit.MILLISECONDS)
                    // Ktor sends every call through OkHttp's dispatcher, whose
                    // default of five calls per host would quietly hold a
                    // segmented transfer below its connection limit. Ktor
                    // installs its own dispatcher before this block runs, so
                    // this one is the one that applies.
                    dispatcher(
                        Dispatcher().apply {
                            maxRequestsPerHost = MaxRequestsPerHost
                            maxRequests = MaxRequests
                        },
                    )
                    // HTTP/2 would carry every connection to one host as a
                    // stream of a single TCP connection, which a CDN limiting
                    // speed per connection throttles as one. HTTP/1.1 gives
                    // each segment a socket of its own.
                    protocols(listOf(Protocol.HTTP_1_1))
                }
            }
        }
        return try {
            var protectedRequest = request
            val transfer = HttpRangeTransfer(
                http = http,
                fileSystem = FileSystem.SYSTEM,
                clock = SystemEpochClock,
            )
            val result = transfer.transfer(
                sourceUrl = request.sourceUrl,
                partFile = request.partFilePath.toPath(),
                target = request.targetFilePath.toPath(),
                resumeValidator = request.resumeValidator,
                onProgress = { progress ->
                    if (progress.validator != null && progress.validator != protectedRequest.resumeValidator) {
                        protectedRequest = protectedRequest.copy(resumeValidator = progress.validator)
                        if (!vault.write(protectedRequest)) {
                            throw DownloadTransferException(
                                DownloadFailure(DownloadFailureCode.ProtectedRequestCorrupt),
                            )
                        }
                    }
                    val data = progressData(progress.downloadedBytes, progress.totalBytes)
                    setProgress(data)
                    AndroidDownloadEventBus.send(
                        BackgroundDownloadEvent.Progress(
                            jobId = jobId,
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                        ),
                    )
                    notifications.updateProgress(jobId, progress.downloadedBytes, progress.totalBytes)
                },
            )
            AndroidDownloadEventBus.send(
                BackgroundDownloadEvent.Completed(
                    jobId = jobId,
                    downloadedBytes = result.downloadedBytes,
                    totalBytes = result.totalBytes,
                ),
            )
            notifications.complete(jobId)
            Result.success(progressData(result.downloadedBytes, result.totalBytes))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val sanitized = failure.toDownloadFailure()
            // The kind of failure only, never the message: it can carry the
            // source URL, whose query holds the debrid key.
            val status = (failure as? DownloadTransferException)?.httpStatus?.let { " (HTTP $it)" }.orEmpty()
            Log.w(LogTag, "Attempt $runAttemptCount failed: ${failure::class.simpleName} -> ${sanitized.code}$status")
            if (sanitized.isTransient && runAttemptCount < AndroidDownloadWorkContract.MaxRetries) {
                Result.retry()
            } else {
                terminalFailure(jobId, sanitized)
            }
        } finally {
            http.close()
        }
    }

    private fun terminalFailure(jobId: String, failure: DownloadFailure): Result {
        if (jobId.isNotEmpty()) {
            AndroidDownloadEventBus.send(BackgroundDownloadEvent.Failed(jobId, failure))
            notifications.failed(jobId, failure)
        }
        val output = Data.Builder()
            .putString(AndroidDownloadWorkContract.FailureCode, failure.code.name)
            .build()
        return Result.failure(output)
    }

    private fun progressData(downloadedBytes: Long, totalBytes: Long): Data = Data.Builder()
        .putLong(AndroidDownloadWorkContract.DownloadedBytes, downloadedBytes)
        .putLong(AndroidDownloadWorkContract.TotalBytes, totalBytes)
        .build()
}

private const val LogTag = "HaloDownload"
private const val ConnectTimeoutSeconds = 30L
private const val MaxRequestsPerHost = 16
private const val MaxRequests = 64

private val DownloadFailure.isTransient: Boolean
    get() = code == DownloadFailureCode.Network || code == DownloadFailureCode.ServerUnavailable

private fun Throwable.toDownloadFailure(): DownloadFailure {
    if (this is DownloadTransferException) return failure
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is ErrnoException && cause.errno == OsConstants.ENOSPC) {
            return DownloadFailure(DownloadFailureCode.StorageFull)
        }
        cause = cause.cause
    }
    return DownloadFailure(DownloadFailureCode.Network)
}

/** Rejects a corrupt protected record before it can choose a file destination. */
private fun ProtectedDownloadRequest.isSafeFor(context: Context): Boolean = try {
    val parsed = URI(sourceUrl)
    if (parsed.scheme != "http" && parsed.scheme != "https") return false
    val downloads = File(context.filesDir, "downloads").canonicalFile
    val part = File(partFilePath).canonicalFile
    val target = File(targetFilePath).canonicalFile
    part.parentFile == downloads &&
        target.parentFile == downloads &&
        part.name == "${target.name}.part" &&
        part.name != ".part"
} catch (_: Throwable) {
    false
}
