package moe.ditto.halo.downloads

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

internal fun media(
    videoId: String = "tt0111161",
    type: String = "movie",
    metaId: String = "tt0111161",
    showTitle: String = "The Shawshank Redemption",
    episodeTag: String? = null,
    sourceUrl: String = "https://source.test/movie.mkv",
    videoSize: Long? = null,
): DownloadMedia = DownloadMedia(
    videoId = videoId,
    type = type,
    metaId = metaId,
    showTitle = showTitle,
    episodeTag = episodeTag,
    poster = "https://art.test/poster.jpg",
    sourceFingerprint = sourceFingerprint(sourceUrl),
    sourceUrl = sourceUrl,
    addonId = "addon-1",
    videoSize = videoSize,
)

internal fun entry(
    videoId: String = "tt0111161",
    status: DownloadStatus = DownloadStatus.Done,
    fileName: String = "$videoId.mkv",
    subtitle: DownloadSubtitle? = null,
    totalBytes: Long = 100,
    downloadedBytes: Long = 100,
    createdAt: Long = 1_000,
    jobId: String? = if (status == DownloadStatus.Done) null else "job-$videoId",
    failure: DownloadFailure? = null,
): DownloadEntry = DownloadEntry(
    media = media(videoId = videoId),
    fileName = fileName,
    subtitle = subtitle,
    status = status,
    jobId = jobId,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    failure = failure,
    createdAt = createdAt,
    updatedAt = createdAt,
)

internal class FixedDownloadSubtitles(private val subtitle: DownloadSubtitle?) : DownloadSubtitleSource {
    override suspend fun fetch(media: DownloadMedia): DownloadSubtitle? = subtitle
}

internal class FakeDownloadStorage(
    private val path: String? = "/downloads",
    private val free: Long? = null,
    private val total: Long? = null,
) : DownloadStoragePort {
    override fun directory(): String? = path
    override fun freeBytes(): Long? = free
    override fun totalBytes(): Long? = total
}

internal class FakeDownloadVault : DownloadRequestVault {
    val requests = linkedMapOf<String, ProtectedDownloadRequest>()
    var failWrites = false

    override fun write(request: ProtectedDownloadRequest): Boolean {
        if (failWrites) return false
        requests[request.jobId] = request
        return true
    }

    override fun delete(jobId: String) {
        requests.remove(jobId)
    }
}

internal class FakeBackgroundDownloadPort(
    override val resumesMigratedPartialFiles: Boolean = true,
) : BackgroundDownloadPort {
    private val channel = Channel<BackgroundDownloadEvent>(Channel.UNLIMITED)
    override val events: Flow<BackgroundDownloadEvent> = channel.receiveAsFlow()

    var reconciled = emptyList<BackgroundDownloadJob>()
    var reconcileFailure: Throwable? = null
    val enqueued = mutableListOf<ProtectedDownloadRequest>()
    val resumed = mutableListOf<String>()
    val paused = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    var cancelGate: CompletableDeferred<Unit>? = null

    override suspend fun reconcile(): List<BackgroundDownloadJob> {
        reconcileFailure?.let { throw it }
        return reconciled
    }

    override suspend fun enqueue(request: ProtectedDownloadRequest) {
        enqueued += request
    }

    override suspend fun pause(jobId: String) {
        paused += jobId
    }

    override suspend fun resume(jobId: String) {
        resumed += jobId
    }

    override suspend fun cancel(jobId: String) {
        cancelled += jobId
        cancelGate?.await()
    }

    fun emit(event: BackgroundDownloadEvent) {
        channel.trySend(event)
    }
}
