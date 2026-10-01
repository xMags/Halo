package moe.ditto.halo.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ditto.halo.auth.EpochClock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** What asking for a download did. */
internal sealed interface DownloadStartResult {
    data class Started(val entry: DownloadEntry) : DownloadStartResult
    data class AlreadyExists(val entry: DownloadEntry) : DownloadStartResult
    data object Unavailable : DownloadStartResult
    data class NotEnoughSpace(val requiredBytes: Long, val freeBytes: Long) : DownloadStartResult
    data class Failed(val message: String) : DownloadStartResult
}

/** The files a finished download consists of, as absolute paths. */
internal data class DownloadFiles(val videoPath: String, val subtitlePath: String?)

/** The volume holding downloads. Both figures are measured, never guessed. */
internal data class StorageSpace(val freeBytes: Long, val totalBytes: Long)

private data class SubtitleBinding(val token: Any, val source: DownloadSubtitleSource)

/**
 * Application-scoped owner of the index and the one-at-a-time queue.
 *
 * The operating system owns byte transfer. This runtime owns only durable
 * product state and is the sole writer of the v2 index. Exact job matching is
 * the concurrency boundary: cancellation, replacement and sign-out can all
 * race a platform callback, but a stale job can never update a newer entry.
 */
internal class DeviceDownloadRuntime(
    private val index: DownloadIndex,
    private val storage: DownloadStoragePort,
    private val port: BackgroundDownloadPort,
    private val vault: DownloadRequestVault,
    private val clock: EpochClock,
    private val scope: CoroutineScope,
    private val fileSystem: FileSystem = downloadFileSystem(),
    private val jobIdFactory: () -> String = ::newDownloadJobId,
) {
    private val directory: Path? = storage.directory()?.toPath()
    private val mutex = Mutex()
    private val rate = TransferRate()
    private val pendingRequests = mutableMapOf<String, ProtectedDownloadRequest>()
    private val subtitleBinding = MutableStateFlow<SubtitleBinding?>(null)
    private val ready = CompletableDeferred<Unit>()
    private var indexWritable = true
    private var platformReady = true

    private val _entries: MutableStateFlow<List<DownloadEntry>>
    val entries: StateFlow<List<DownloadEntry>>

    val isAvailable: Boolean get() = directory != null

    init {
        val snapshot = index.read(
            directory = directory,
            resumeMigratedPartialFiles = port.resumesMigratedPartialFiles,
        )
        _entries = MutableStateFlow(snapshot.entries)
        entries = _entries.asStateFlow()
        indexWritable = snapshot.complete
        scope.launch { initialize(snapshot.complete) }
    }

    fun entryFor(videoId: String): DownloadEntry? =
        _entries.value.firstOrNull { it.videoId == videoId }

    fun playbackFiles(entry: DownloadEntry): DownloadFiles? {
        val root = directory ?: return null
        if (entry.status != DownloadStatus.Done) return null
        return DownloadFiles(
            videoPath = (root / entry.fileName).toString(),
            subtitlePath = entry.subtitle?.let { (root / it.fileName).toString() },
        )
    }

    fun storageSpace(): StorageSpace? {
        val free = storage.freeBytes()?.takeIf { it >= 0 } ?: return null
        val total = storage.totalBytes()?.takeIf { it > 0 } ?: return null
        return StorageSpace(freeBytes = free.coerceAtMost(total), totalBytes = total)
    }

    /**
     * Supplies signed-in subtitle lookup without transferring runtime ownership
     * back to the session graph. Closing an older graph cannot detach a newer
     * graph's source because the registration identity must still match.
     */
    fun bindSubtitleSource(source: DownloadSubtitleSource): () -> Unit {
        val binding = SubtitleBinding(Any(), source)
        subtitleBinding.value = binding
        return {
            subtitleBinding.compareAndSet(binding, null)
        }
    }

    suspend fun start(media: DownloadMedia): DownloadStartResult {
        ready.await()
        return begin(media = media, replaceExisting = false)
    }

    /** Called only after the picker has confirmed destructive replacement. */
    suspend fun replace(media: DownloadMedia): DownloadStartResult {
        ready.await()
        return begin(media = media, replaceExisting = true)
    }

    private suspend fun begin(
        media: DownloadMedia,
        replaceExisting: Boolean,
    ): DownloadStartResult {
        val root = directory ?: return DownloadStartResult.Unavailable
        if (!indexWritable) {
            return DownloadStartResult.Failed("The download index could not be read safely.")
        }
        if (!platformReady) {
            return DownloadStartResult.Failed("Background downloads are temporarily unavailable.")
        }
        if (media.sourceUrl.isBlank()) {
            return DownloadStartResult.Failed("Choose the source again before downloading.")
        }
        val fingerprint = sourceFingerprint(media.sourceUrl)
        val normalizedMedia = media.copy(sourceFingerprint = fingerprint)

        val existing = mutex.withLock { current(media.videoId) }
        if (existing != null) {
            val sameSource = existing.media.sourceFingerprint == fingerprint
            val retryableFailure = existing.status == DownloadStatus.Failed &&
                existing.failure?.requiresNewSource != true
            if (!replaceExisting && sameSource && retryableFailure) {
                resume(existing.videoId)
                val resumed = entryFor(existing.videoId)
                    ?: return DownloadStartResult.Failed("Download state changed. Try again.")
                return DownloadStartResult.Started(resumed)
            }
            if (!replaceExisting && !(sameSource && retryableFailure)) {
                return DownloadStartResult.AlreadyExists(existing)
            }
        }
        spaceShortfall(normalizedMedia)?.let { return it }

        val jobId = jobIdFactory()
        val reusableFileName = existing
            ?.takeIf { it.media.sourceFingerprint == fingerprint }
            ?.fileName
        val reusePartial = reusableFileName != null
        val fileName = reusableFileName
            ?: DownloadPaths.videoFileName("${media.videoId}-${fingerprint.take(12)}", media.sourceUrl)
        val request = ProtectedDownloadRequest(
            jobId = jobId,
            sourceUrl = media.sourceUrl,
            partFilePath = (root / "$fileName.part").toString(),
            targetFilePath = (root / fileName).toString(),
            resumeValidator = null,
        )
        if (!vault.write(request)) {
            return DownloadStartResult.Failed("The protected download request could not be saved.")
        }

        val now = clock.nowMs()
        val created = mutex.withLock {
            val latest = current(media.videoId)
            if (latest != existing) {
                vault.delete(jobId)
                return@withLock null
            }
            val retained = latest
                ?.ownedFileNames()
                .orEmpty()
                .filterNot { it == fileName || it == "$fileName.part" }
                .distinct()
            val next = DownloadEntry(
                media = normalizedMedia,
                fileName = fileName,
                subtitle = latest?.subtitle?.takeIf { reusePartial },
                status = DownloadStatus.Queued,
                jobId = jobId,
                totalBytes = latest?.takeIf { reusePartial }?.totalBytes ?: 0,
                downloadedBytes = latest?.takeIf { reusePartial }?.downloadedBytes ?: 0,
                failure = null,
                retainedFileNames = retained,
                createdAt = latest?.createdAt ?: now,
                updatedAt = now,
            )
            pendingRequests[jobId] = request
            putLocked(next)
            next to latest?.jobId
        } ?: return entryFor(media.videoId)
            ?.let { DownloadStartResult.AlreadyExists(it) }
            ?: DownloadStartResult.Failed("Download state changed. Try again.")
        val (entry, oldJobId) = created

        if (oldJobId != null && oldJobId != jobId) {
            port.cancel(oldJobId)
            vault.delete(oldJobId)
        }
        scope.launch { attachSubtitle(jobId, normalizedMedia) }
        pumpNext()
        return DownloadStartResult.Started(entry)
    }

    private fun spaceShortfall(media: DownloadMedia): DownloadStartResult.NotEnoughSpace? {
        val required = media.videoSize?.takeIf { it > 0 } ?: return null
        val free = storage.freeBytes()?.takeIf { it >= 0 } ?: return null
        if (free >= required + SpaceReserveBytes) return null
        return DownloadStartResult.NotEnoughSpace(requiredBytes = required, freeBytes = free)
    }

    private suspend fun attachSubtitle(jobId: String, media: DownloadMedia) {
        val source = subtitleBinding.value?.source ?: return
        val found = try {
            source.fetch(media)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return
        } ?: return
        mutex.withLock {
            val entry = current(media.videoId) ?: return
            if (entry.jobId != jobId || entry.subtitle != null) return
            putLocked(entry.copy(subtitle = found, updatedAt = clock.nowMs()))
        }
    }

    suspend fun pause(videoId: String) {
        ready.await()
        val jobId = mutex.withLock {
            val entry = current(videoId) ?: return
            if (!entry.status.isActive) return
            rate.clear()
            putLocked(
                entry.copy(
                    status = DownloadStatus.Paused,
                    updatedAt = clock.nowMs(),
                    bytesPerSecond = 0,
                ),
            )
            entry.jobId.takeIf { entry.status == DownloadStatus.Downloading }
        }
        if (jobId != null) port.pause(jobId)
        pumpNext()
    }

    suspend fun pauseJob(jobId: String) {
        val videoId = mutex.withLock { currentJob(jobId)?.videoId } ?: return
        pause(videoId)
    }

    /** Sign-out is a durable user pause, including entries not yet submitted. */
    suspend fun pauseAllForSignOut() {
        ready.await()
        val activeJobs = mutex.withLock {
            val jobs = _entries.value
                .filter { it.status == DownloadStatus.Downloading }
                .mapNotNull { it.jobId }
            val now = clock.nowMs()
            val next = _entries.value.map { entry ->
                if (!entry.status.isActive) entry else entry.copy(
                    status = DownloadStatus.Paused,
                    updatedAt = now,
                    bytesPerSecond = 0,
                )
            }
            if (next != _entries.value) publishLocked(next)
            rate.clear()
            jobs
        }
        activeJobs.forEach { port.pause(it) }
    }

    suspend fun resume(videoId: String) {
        ready.await()
        if (!indexWritable || !platformReady) return
        mutex.withLock {
            val entry = current(videoId) ?: return
            if (entry.status == DownloadStatus.Done || entry.status.isActive) return
            if (entry.failure?.requiresNewSource == true || entry.jobId == null) return
            putLocked(
                entry.copy(
                    status = DownloadStatus.Queued,
                    failure = null,
                    updatedAt = clock.nowMs(),
                ),
            )
        }
        pumpNext()
    }

    suspend fun remove(videoId: String) {
        ready.await()
        if (!indexWritable) return
        val removed = mutex.withLock {
            val entry = current(videoId) ?: return
            publishLocked(_entries.value.filterNot { it.videoId == videoId })
            pendingRequests.remove(entry.jobId)
            entry
        }
        removed.jobId?.let {
            port.cancel(it)
            vault.delete(it)
        }
        directory?.let { discardFiles(removed, it) }
        pumpNext()
    }

    suspend fun cancelJob(jobId: String) {
        val videoId = mutex.withLock { currentJob(jobId)?.videoId } ?: return
        remove(videoId)
    }

    private suspend fun initialize(sweep: Boolean) {
        try {
            initializeUnsafe(sweep)
        } finally {
            ready.complete(Unit)
        }
    }

    private suspend fun initializeUnsafe(sweep: Boolean) {
        val jobs = try {
            port.reconcile()
        } catch (_: Throwable) {
            platformReady = false
            return
        }
        val jobsById = jobs.associateBy { it.jobId }
        val completedJobs = mutableListOf<String>()
        val retainedToDelete = mutableListOf<String>()
        val jobsToPause = mutableListOf<String>()

        mutex.withLock {
            jobs.forEach { job ->
                val entry = currentJob(job.jobId) ?: return@forEach
                if (entry.status == DownloadStatus.Paused) {
                    if (job.state == BackgroundDownloadJobState.Running ||
                        job.state == BackgroundDownloadJobState.Enqueued
                    ) {
                        jobsToPause += job.jobId
                    }
                    return@forEach
                }
                when (job.state) {
                    BackgroundDownloadJobState.Enqueued,
                    BackgroundDownloadJobState.Running,
                    -> applyProgressLocked(
                        entry,
                        job.downloadedBytes,
                        job.totalBytes,
                        markDownloading = true,
                    )
                    BackgroundDownloadJobState.Paused ->
                        putLocked(entry.copy(status = DownloadStatus.Paused, bytesPerSecond = 0))
                    BackgroundDownloadJobState.Succeeded -> {
                        retainedToDelete += entry.retainedFileNames
                        completeLocked(entry, job.downloadedBytes, job.totalBytes)
                        completedJobs += job.jobId
                    }
                    BackgroundDownloadJobState.Failed -> failLocked(
                        entry,
                        job.failure ?: DownloadFailure(DownloadFailureCode.Unknown),
                    )
                    BackgroundDownloadJobState.Cancelled -> {
                        if (entry.status.isActive) {
                            putLocked(entry.copy(status = DownloadStatus.Queued, bytesPerSecond = 0))
                        }
                    }
                }
            }

            // An active index row without an OS job is the crash window between
            // persisting the row and enqueueing it. Put it back through the
            // ordinary queue instead of converting it to a user pause.
            _entries.value.filter { it.status == DownloadStatus.Downloading }.forEach { entry ->
                val state = entry.jobId?.let(jobsById::get)?.state
                if (state != BackgroundDownloadJobState.Running &&
                    state != BackgroundDownloadJobState.Enqueued
                ) {
                    putLocked(entry.copy(status = DownloadStatus.Queued, bytesPerSecond = 0))
                }
            }
            settleLocked(sweep)
        }

        retainedToDelete.forEach { name -> directory?.let { deleteFile(it / name) } }
        completedJobs.forEach(vault::delete)
        jobsToPause.forEach { port.pause(it) }

        scope.launch {
            port.events.collect { event -> handle(event) }
        }
        if (indexWritable) pumpNext()
    }

    private suspend fun handle(event: BackgroundDownloadEvent) {
        var completedJob: String? = null
        var retained: List<String> = emptyList()
        mutex.withLock {
            val entry = currentJob(event.jobId) ?: return
            when (event) {
                is BackgroundDownloadEvent.Progress -> {
                    if (!entry.status.isActive) return
                    applyProgressLocked(
                        entry,
                        event.downloadedBytes,
                        event.totalBytes,
                        markDownloading = true,
                    )
                }
                is BackgroundDownloadEvent.Completed -> {
                    if (!entry.status.isActive) return
                    retained = entry.retainedFileNames
                    completeLocked(
                        entry,
                        event.downloadedBytes,
                        event.totalBytes,
                    )
                    pendingRequests.remove(event.jobId)
                    completedJob = event.jobId
                }
                is BackgroundDownloadEvent.Paused -> {
                    if (!entry.status.isActive) return
                    putLocked(
                        entry.copy(
                            status = DownloadStatus.Paused,
                            downloadedBytes = event.downloadedBytes.coerceAtLeast(0),
                            totalBytes = event.totalBytes.coerceAtLeast(0),
                            updatedAt = clock.nowMs(),
                            bytesPerSecond = 0,
                        ),
                    )
                }
                is BackgroundDownloadEvent.Failed -> {
                    if (!entry.status.isActive) return
                    failLocked(entry, event.failure)
                }
            }
        }
        retained.forEach { name -> directory?.let { deleteFile(it / name) } }
        completedJob?.let {
            vault.delete(it)
        }
        pumpNext()
    }

    /** Starts the oldest queued entry when no OS-owned transfer is active. */
    private suspend fun pumpNext() {
        if (!platformReady) return
        val submission = mutex.withLock {
            if (_entries.value.any { it.status == DownloadStatus.Downloading }) return
            val next = _entries.value
                .filter { it.status == DownloadStatus.Queued }
                .minWithOrNull(compareBy<DownloadEntry> { it.createdAt }.thenBy { it.videoId })
                ?: return
            val jobId = next.jobId ?: run {
                failLocked(next, DownloadFailure(DownloadFailureCode.ProtectedRequestCorrupt))
                return@withLock null
            }
            putLocked(next.copy(status = DownloadStatus.Downloading, updatedAt = clock.nowMs()))
            jobId to pendingRequests.remove(jobId)
        } ?: return

        val (jobId, request) = submission
        try {
            if (request != null) port.enqueue(request) else port.resume(jobId)
        } catch (_: Throwable) {
            mutex.withLock {
                val current = currentJob(jobId) ?: return@withLock
                if (current.status == DownloadStatus.Downloading) {
                    failLocked(current, DownloadFailure(DownloadFailureCode.Unknown))
                }
            }
            pumpNext()
        }
    }

    private fun applyProgressLocked(
        entry: DownloadEntry,
        downloadedBytes: Long,
        totalBytes: Long,
        markDownloading: Boolean,
    ) {
        val now = clock.nowMs()
        putLocked(
            entry.copy(
                status = if (markDownloading) DownloadStatus.Downloading else entry.status,
                downloadedBytes = downloadedBytes.coerceAtLeast(0),
                totalBytes = totalBytes.coerceAtLeast(0),
                updatedAt = now,
                bytesPerSecond = rate.sample(entry.jobId ?: entry.videoId, downloadedBytes, now),
            ),
        )
    }

    private fun completeLocked(
        entry: DownloadEntry,
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        rate.clear()
        val written = downloadedBytes.coerceAtLeast(0)
        putLocked(
            entry.copy(
                status = DownloadStatus.Done,
                downloadedBytes = written,
                totalBytes = totalBytes.takeIf { it > 0 } ?: written,
                failure = null,
                retainedFileNames = emptyList(),
                updatedAt = clock.nowMs(),
                bytesPerSecond = 0,
            ),
        )
    }

    private fun failLocked(entry: DownloadEntry, failure: DownloadFailure) {
        rate.clear()
        putLocked(
            entry.copy(
                status = DownloadStatus.Failed,
                failure = failure,
                updatedAt = clock.nowMs(),
                bytesPerSecond = 0,
            ),
        )
    }

    private fun settleLocked(sweep: Boolean) {
        val root = directory ?: return
        fileSystem.createDirectories(root)
        var next = _entries.value.map { entry ->
            if (entry.status == DownloadStatus.Done) return@map entry
            val written = fileSystem.metadataOrNull(root / entry.partFileName)?.size ?: return@map entry
            entry.copy(downloadedBytes = written)
        }
        next = next.map { entry ->
            if (entry.status != DownloadStatus.Done || fileSystem.exists(root / entry.fileName)) return@map entry
            entry.copy(
                status = DownloadStatus.Failed,
                failure = DownloadFailure(DownloadFailureCode.MissingFile),
                downloadedBytes = 0,
                updatedAt = clock.nowMs(),
            )
        }
        publishLocked(next)
        if (sweep) DownloadPaths.sweepOrphans(fileSystem, root, next)
    }

    private fun current(videoId: String): DownloadEntry? =
        _entries.value.firstOrNull { it.videoId == videoId }

    private fun currentJob(jobId: String): DownloadEntry? =
        _entries.value.firstOrNull { it.jobId == jobId }

    private fun putLocked(entry: DownloadEntry) {
        val existing = _entries.value.indexOfFirst { it.videoId == entry.videoId }
        val next = if (existing < 0) {
            _entries.value + entry
        } else {
            _entries.value.toMutableList().apply { set(existing, entry) }
        }
        publishLocked(next)
    }

    private fun publishLocked(entries: List<DownloadEntry>) {
        _entries.value = entries
        if (indexWritable) index.write(entries)
    }

    private fun DownloadEntry.ownedFileNames(): List<String> =
        buildList {
            add(fileName)
            add(partFileName)
            subtitle?.let { add(it.fileName) }
            addAll(retainedFileNames)
        }

    private fun discardFiles(entry: DownloadEntry, root: Path) {
        entry.ownedFileNames().distinct().forEach { deleteFile(root / it) }
    }

    private fun deleteFile(path: Path) {
        try {
            fileSystem.delete(path, mustExist = false)
        } catch (_: okio.IOException) {
            // A later sweep can retry. State changes must not be rolled back by
            // an uncooperative filesystem delete.
        }
    }

    private companion object {
        const val SpaceReserveBytes = 256L * 1024 * 1024
    }
}

/** Blocking file access, on whichever dispatcher the caller provides. */
internal expect fun downloadFileSystem(): FileSystem
