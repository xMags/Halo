package moe.ditto.halo.downloads

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine

internal object AndroidDownloadWorkContract {
    const val AllWorkTag = "halo-background-download"
    const val JobTagPrefix = "halo-download-job:"
    const val UniqueWorkPrefix = "halo-download:"
    const val JobId = "job_id"
    const val DownloadedBytes = "downloaded_bytes"
    const val TotalBytes = "total_bytes"
    const val FailureCode = "failure_code"
    const val MaxRetries = 5
    const val InitialBackoffSeconds = 30L
}

internal object AndroidDownloadEventBus {
    private val channel = Channel<BackgroundDownloadEvent>(Channel.UNLIMITED)
    val events: Flow<BackgroundDownloadEvent> = channel.receiveAsFlow()

    fun send(event: BackgroundDownloadEvent) {
        channel.trySend(event)
    }
}

/** WorkManager adapter. Worker input and tags contain only an opaque job ID. */
internal class AndroidBackgroundDownloadPort(context: Context) : BackgroundDownloadPort {
    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var notificationPermissionRequester: (() -> Unit)? = null

    override val events: Flow<BackgroundDownloadEvent> = AndroidDownloadEventBus.events
    override val resumesMigratedPartialFiles: Boolean = true

    fun attachNotificationPermissionRequester(requester: (() -> Unit)?) {
        notificationPermissionRequester = requester
    }

    override suspend fun reconcile(): List<BackgroundDownloadJob> {
        val work = workManager.getWorkInfosByTag(AndroidDownloadWorkContract.AllWorkTag).awaitValue()
        return work
            .mapNotNull(::toJob)
            .groupBy { it.jobId }
            .mapNotNull { (_, attempts) -> attempts.maxByOrNull { it.state.reconcilePriority } }
    }

    override suspend fun enqueue(request: ProtectedDownloadRequest) {
        requestNotificationPermission()
        enqueueJob(request.jobId)
    }

    override suspend fun pause(jobId: String) {
        workManager.cancelUniqueWork(uniqueName(jobId)).result.awaitValue()
        AndroidDownloadJobLifetimes.awaitIdle(jobId)
    }

    override suspend fun resume(jobId: String) {
        requestNotificationPermission()
        enqueueJob(jobId)
    }

    override suspend fun cancel(jobId: String) {
        workManager.cancelUniqueWork(uniqueName(jobId)).result.awaitValue()
        AndroidDownloadJobLifetimes.awaitIdle(jobId)
    }

    private suspend fun enqueueJob(jobId: String) {
        val request = androidDownloadWorkRequest(jobId)
        workManager.enqueueUniqueWork(
            uniqueName(jobId),
            ExistingWorkPolicy.REPLACE,
            request,
        ).result.awaitValue()
    }

    private fun requestNotificationPermission() {
        val requester = notificationPermissionRequester ?: return
        mainHandler.post { requester() }
    }

    private fun toJob(info: WorkInfo): BackgroundDownloadJob? {
        val jobId = info.tags.firstNotNullOfOrNull { tag ->
            tag.takeIf { it.startsWith(AndroidDownloadWorkContract.JobTagPrefix) }
                ?.removePrefix(AndroidDownloadWorkContract.JobTagPrefix)
        } ?: return null
        val data = if (info.state.isFinished) info.outputData else info.progress
        val failure = data.getString(AndroidDownloadWorkContract.FailureCode)
            ?.let { value -> DownloadFailureCode.entries.firstOrNull { it.name == value } }
            ?.let(::DownloadFailure)
        return BackgroundDownloadJob(
            jobId = jobId,
            state = when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> BackgroundDownloadJobState.Enqueued
                WorkInfo.State.RUNNING -> BackgroundDownloadJobState.Running
                WorkInfo.State.SUCCEEDED -> BackgroundDownloadJobState.Succeeded
                WorkInfo.State.FAILED -> BackgroundDownloadJobState.Failed
                WorkInfo.State.CANCELLED -> BackgroundDownloadJobState.Cancelled
            },
            downloadedBytes = data.getLong(AndroidDownloadWorkContract.DownloadedBytes, 0),
            totalBytes = data.getLong(AndroidDownloadWorkContract.TotalBytes, 0),
            failure = failure,
        )
    }

    private fun uniqueName(jobId: String): String = AndroidDownloadWorkContract.UniqueWorkPrefix + jobId

    private val BackgroundDownloadJobState.reconcilePriority: Int
        get() = when (this) {
            BackgroundDownloadJobState.Running -> 6
            BackgroundDownloadJobState.Enqueued -> 5
            BackgroundDownloadJobState.Paused -> 4
            BackgroundDownloadJobState.Succeeded -> 3
            BackgroundDownloadJobState.Failed -> 2
            BackgroundDownloadJobState.Cancelled -> 1
        }
}

internal fun androidDownloadWorkRequest(jobId: String): OneTimeWorkRequest {
    val input = Data.Builder()
        .putString(AndroidDownloadWorkContract.JobId, jobId)
        .build()
    return OneTimeWorkRequestBuilder<HaloDownloadWorker>()
        .setInputData(input)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        )
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            AndroidDownloadWorkContract.InitialBackoffSeconds,
            TimeUnit.SECONDS,
        )
        .addTag(AndroidDownloadWorkContract.AllWorkTag)
        .addTag(AndroidDownloadWorkContract.JobTagPrefix + jobId)
        .build()
}

private val DirectExecutor = Executor { command -> command.run() }

private suspend fun <T> ListenableFuture<T>.awaitValue(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    if (continuation.isActive) continuation.resume(get())
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            },
            DirectExecutor,
        )
        continuation.invokeOnCancellation { cancel(true) }
    }
