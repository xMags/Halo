package moe.ditto.halo.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.storage.StorageKeys
import moe.ditto.halo.sync.FakeClock
import moe.ditto.halo.sync.FakeStore
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceDownloadRuntimeTest {
    @Test
    fun oneActiveJobRunsAndCompletionPumpsTheOldestQueuedEntry() = runTest {
        val world = world()
        testScheduler.advanceUntilIdle()

        world.runtime.start(media(videoId = "first", sourceUrl = "https://source.test/1.mkv"))
        world.clock.now += 10
        world.runtime.start(media(videoId = "second", sourceUrl = "https://source.test/2.mkv"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("job-1"), world.port.enqueued.map { it.jobId })
        assertEquals(DownloadStatus.Downloading, world.status("first"))
        assertEquals(DownloadStatus.Queued, world.status("second"))

        world.port.emit(BackgroundDownloadEvent.Completed("job-1", 100, 100))
        testScheduler.advanceUntilIdle()

        assertEquals(DownloadStatus.Done, world.status("first"))
        assertEquals(DownloadStatus.Downloading, world.status("second"))
        assertEquals(listOf("job-1", "job-2"), world.port.enqueued.map { it.jobId })
    }

    @Test
    fun reconciliationKeepsSystemOwnedWorkRunningAcrossProcessRecreation() = runTest {
        val store = FakeStore()
        val vault = FakeDownloadVault()
        val active = entry(videoId = "movie", status = DownloadStatus.Downloading, jobId = "durable-job")
        DownloadIndex(store, vault).write(listOf(active))
        vault.write(request("durable-job", "movie.mkv"))
        val port = FakeBackgroundDownloadPort().apply {
            reconciled = listOf(
                BackgroundDownloadJob(
                    jobId = "durable-job",
                    state = BackgroundDownloadJobState.Running,
                    downloadedBytes = 40,
                    totalBytes = 100,
                ),
            )
        }

        val world = world(store = store, vault = vault, port = port)
        testScheduler.advanceUntilIdle()

        assertEquals(DownloadStatus.Downloading, world.status("movie"))
        assertEquals(40, world.entry("movie").downloadedBytes)
        assertEquals(emptyList(), port.resumed)
    }

    @Test
    fun crashBetweenIndexWriteAndOsEnqueueIsRestartedAutomatically() = runTest {
        val store = FakeStore()
        val vault = FakeDownloadVault()
        DownloadIndex(store, vault).write(
            listOf(entry(videoId = "movie", status = DownloadStatus.Downloading, jobId = "recover-job")),
        )
        vault.write(request("recover-job", "movie.mkv"))

        val world = world(store = store, vault = vault)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("recover-job"), world.port.resumed)
        assertEquals(DownloadStatus.Downloading, world.status("movie"))
    }

    @Test
    fun aCompletionThatLandsAfterAPauseFinishesTheDownload() = runTest {
        val world = world()
        testScheduler.advanceUntilIdle()
        world.runtime.start(media(videoId = "movie"))
        testScheduler.advanceUntilIdle()

        // The pause reached the platform as the last bytes arrived: the file
        // is complete, so the row must not stay paused over it.
        world.runtime.pause("movie")
        world.port.emit(BackgroundDownloadEvent.Completed("job-1", 100, 100))
        testScheduler.advanceUntilIdle()

        assertEquals(DownloadStatus.Done, world.status("movie"))
        assertEquals(listOf("job-1"), world.port.paused)
        assertEquals(DownloadStatus.Done, DownloadIndex(world.store, world.vault).read().entries.single().status)
    }

    @Test
    fun aNotificationCancelAfterTheDownloadFinishedKeepsTheVideo() = runTest {
        val world = world()
        testScheduler.advanceUntilIdle()
        world.runtime.start(media(videoId = "movie"))
        testScheduler.advanceUntilIdle()
        val entry = world.entry("movie")
        world.fileSystem.write("/downloads/${entry.fileName}".toPath()) { writeUtf8("video") }
        world.port.emit(BackgroundDownloadEvent.Completed("job-1", 5, 5))
        testScheduler.advanceUntilIdle()

        world.runtime.cancelJob("job-1")
        testScheduler.advanceUntilIdle()

        assertEquals(DownloadStatus.Done, world.status("movie"))
        assertTrue(world.fileSystem.exists("/downloads/${entry.fileName}".toPath()))
    }

    @Test
    fun aPausedRowWhoseTransferSucceededIsFinishedAtLaunch() = runTest {
        val store = FakeStore()
        val vault = FakeDownloadVault()
        DownloadIndex(store, vault).write(
            listOf(entry(videoId = "movie", status = DownloadStatus.Paused, jobId = "done-job")),
        )
        val port = FakeBackgroundDownloadPort().apply {
            reconciled = listOf(
                BackgroundDownloadJob("done-job", BackgroundDownloadJobState.Succeeded, downloadedBytes = 100, totalBytes = 100),
            )
        }
        val world = world(store = store, vault = vault, port = port, files = listOf("movie.mkv"))
        testScheduler.advanceUntilIdle()

        assertEquals(DownloadStatus.Done, world.status("movie"))
        assertEquals(emptyList(), world.port.paused)
    }

    @Test
    fun signOutPausesBothActiveAndQueuedButOnlyStopsSubmittedWork() = runTest {
        val world = world()
        testScheduler.advanceUntilIdle()
        world.runtime.start(media(videoId = "first"))
        world.runtime.start(media(videoId = "second", sourceUrl = "https://source.test/2.mkv"))
        testScheduler.advanceUntilIdle()

        world.runtime.pauseAllForSignOut()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(DownloadStatus.Paused, DownloadStatus.Paused), world.runtime.entries.value.map { it.status })
        assertEquals(listOf("job-1"), world.port.paused)
        assertEquals(emptyList(), world.port.resumed)
    }

    @Test
    fun replacementRejectsTheCancelledJobsLateEvents() = runTest {
        val world = world()
        testScheduler.advanceUntilIdle()
        world.runtime.start(media(videoId = "movie", sourceUrl = "https://source.test/old.mkv"))
        testScheduler.advanceUntilIdle()

        world.runtime.replace(media(videoId = "movie", sourceUrl = "https://source.test/new.mkv"))
        testScheduler.advanceUntilIdle()
        world.port.emit(BackgroundDownloadEvent.Completed("job-1", 100, 100))
        testScheduler.advanceUntilIdle()

        assertEquals("job-2", world.entry("movie").jobId)
        assertEquals(DownloadStatus.Downloading, world.status("movie"))
        assertEquals(listOf("job-1"), world.port.cancelled)
    }

    @Test
    fun replacementKeepsOldFilesUntilTheNewAttemptSucceeds() = runTest {
        val store = FakeStore()
        val vault = FakeDownloadVault()
        DownloadIndex(store, vault).write(listOf(entry(videoId = "movie", fileName = "old.mkv")))
        val world = world(store = store, vault = vault, files = listOf("old.mkv"))
        testScheduler.advanceUntilIdle()

        world.runtime.replace(media(videoId = "movie", sourceUrl = "https://source.test/new.mp4"))
        testScheduler.advanceUntilIdle()
        assertTrue(world.fileSystem.exists("/downloads/old.mkv".toPath()))

        val replacement = world.entry("movie")
        world.fileSystem.write("/downloads/${replacement.fileName}".toPath()) { writeUtf8("new") }
        world.port.emit(BackgroundDownloadEvent.Completed(replacement.jobId!!, 3, 3))
        testScheduler.advanceUntilIdle()

        assertFalse(world.fileSystem.exists("/downloads/old.mkv".toPath()))
        assertEquals(DownloadStatus.Done, world.status("movie"))
    }

    /** Like the real cache: the same subtitle for the same video is one file, fetched only when absent. */
    private class CachedSubtitle(private val world: () -> World) : DownloadSubtitleSource {
        var gate: CompletableDeferred<Unit>? = null
        var downloads = 0

        override suspend fun fetch(media: DownloadMedia): DownloadSubtitle? {
            gate?.await()
            val path = "/downloads/sub.srt".toPath()
            if (!world().fileSystem.exists(path)) {
                downloads++
                world().fileSystem.write(path) { writeUtf8("sub") }
            }
            return DownloadSubtitle("sub.srt", "eng")
        }
    }

    @Test
    fun aReplacementKeepsTheSubtitleFileItSharesWithTheOldDownload() = runTest {
        val store = FakeStore()
        val vault = FakeDownloadVault()
        DownloadIndex(store, vault).write(
            listOf(entry(videoId = "movie", fileName = "old.mkv", subtitle = DownloadSubtitle("sub.srt", "eng"))),
        )
        lateinit var world: World
        val source = CachedSubtitle { world }
        world = world(store = store, vault = vault, files = listOf("old.mkv", "sub.srt"))
        world.runtime.bindSubtitleSource(source)
        testScheduler.advanceUntilIdle()

        world.runtime.replace(media(videoId = "movie", sourceUrl = "https://source.test/new.mp4"))
        testScheduler.advanceUntilIdle()
        val replacement = world.entry("movie")
        assertEquals("sub.srt", replacement.subtitle?.fileName)
        world.fileSystem.write("/downloads/${replacement.fileName}".toPath()) { writeUtf8("new") }
        world.port.emit(BackgroundDownloadEvent.Completed(replacement.jobId!!, 3, 3))
        testScheduler.advanceUntilIdle()

        assertFalse(world.fileSystem.exists("/downloads/old.mkv".toPath()))
        assertTrue(world.fileSystem.exists("/downloads/sub.srt".toPath()))
        assertEquals("sub.srt", world.entry("movie").subtitle?.fileName)
        assertEquals(0, source.downloads)
    }

    @Test
    fun aSubtitleDeletedWithTheOldDownloadBeforeItIsAttachedIsFetchedAgain() = runTest {
        val store = FakeStore()
        val vault = FakeDownloadVault()
        DownloadIndex(store, vault).write(
            listOf(entry(videoId = "movie", fileName = "old.mkv", subtitle = DownloadSubtitle("sub.srt", "eng"))),
        )
        lateinit var world: World
        val source = CachedSubtitle { world }
        world = world(store = store, vault = vault, files = listOf("old.mkv", "sub.srt"))
        world.runtime.bindSubtitleSource(source)
        testScheduler.advanceUntilIdle()

        // The lookup hands back the old file, but only after the replacement finished and deleted it.
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        world.runtime.replace(media(videoId = "movie", sourceUrl = "https://source.test/new.mp4"))
        testScheduler.advanceUntilIdle()
        val replacement = world.entry("movie")
        world.fileSystem.write("/downloads/${replacement.fileName}".toPath()) { writeUtf8("new") }
        world.port.emit(BackgroundDownloadEvent.Completed(replacement.jobId!!, 3, 3))
        testScheduler.advanceUntilIdle()
        assertFalse(world.fileSystem.exists("/downloads/sub.srt".toPath()))
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue(world.fileSystem.exists("/downloads/sub.srt".toPath()))
        assertEquals("sub.srt", world.entry("movie").subtitle?.fileName)
        assertEquals(1, source.downloads)
    }

    @Test
    fun filesBesideAnIndexThatIsNotThereAreNeitherSweptNorWrittenOver() = runTest {
        val store = FakeStore()
        val world = world(store = store, files = listOf("kept.mkv", "kept.srt"))
        testScheduler.advanceUntilIdle()

        assertTrue(world.fileSystem.exists("/downloads/kept.mkv".toPath()))
        assertTrue(world.fileSystem.exists("/downloads/kept.srt".toPath()))
        assertNull(store.read(StorageKeys.DownloadsV2))

        // Still absent when someone downloads: the list starts from here.
        assertIs<DownloadStartResult.Started>(world.runtime.start(media(videoId = "movie")))
        testScheduler.advanceUntilIdle()
        assertTrue(store.read(StorageKeys.DownloadsV2) != null)
    }

    @Test
    fun anIndexThatTurnsUpAfterLaunchIsNotWrittenOver() = runTest {
        val store = FakeStore()
        val world = world(store = store, files = listOf("kept.mkv"))
        testScheduler.advanceUntilIdle()

        // Storage that answered nothing at launch answers now.
        DownloadIndex(store).write(listOf(entry(videoId = "kept", fileName = "kept.mkv")))
        val written = store.read(StorageKeys.DownloadsV2)

        assertIs<DownloadStartResult.Failed>(world.runtime.start(media(videoId = "movie")))
        testScheduler.advanceUntilIdle()
        assertEquals(written, store.read(StorageKeys.DownloadsV2))
        assertTrue(world.fileSystem.exists("/downloads/kept.mkv".toPath()))
    }

    @Test
    fun expiredSourceCannotBeResumedAndRequiresAReplacement() = runTest {
        val world = world()
        testScheduler.advanceUntilIdle()
        world.runtime.start(media(videoId = "movie"))
        testScheduler.advanceUntilIdle()
        world.port.emit(
            BackgroundDownloadEvent.Failed(
                "job-1",
                DownloadFailure(DownloadFailureCode.SourceExpired),
            ),
        )
        testScheduler.advanceUntilIdle()

        world.runtime.resume("movie")
        testScheduler.advanceUntilIdle()
        assertEquals(DownloadStatus.Failed, world.status("movie"))
        assertEquals(emptyList(), world.port.resumed)

        world.runtime.replace(media(videoId = "movie", sourceUrl = "https://source.test/fresh.mkv"))
        testScheduler.advanceUntilIdle()
        assertEquals("job-2", world.entry("movie").jobId)
    }

    @Test
    fun subtitleLookupNeverBlocksVideoSubmission() = runTest {
        val gate = CompletableDeferred<DownloadSubtitle?>()
        val source = object : DownloadSubtitleSource {
            override suspend fun fetch(media: DownloadMedia): DownloadSubtitle? = gate.await()
        }
        val world = world()
        world.runtime.bindSubtitleSource(source)
        testScheduler.advanceUntilIdle()

        world.runtime.start(media(videoId = "movie"))
        testScheduler.runCurrent()

        assertEquals(listOf("job-1"), world.port.enqueued.map { it.jobId })
        assertNull(world.entry("movie").subtitle)
        world.fileSystem.write("/downloads/movie.srt".toPath()) { writeUtf8("sub") }
        gate.complete(DownloadSubtitle("movie.srt", "eng"))
        testScheduler.advanceUntilIdle()
        assertEquals("movie.srt", world.entry("movie").subtitle?.fileName)
    }

    @Test
    fun failedSubtitleLookupDoesNotFailOrCancelTheVideoJob() = runTest {
        val source = object : DownloadSubtitleSource {
            override suspend fun fetch(media: DownloadMedia): DownloadSubtitle? {
                error("subtitle lookup failed")
            }
        }
        val world = world()
        world.runtime.bindSubtitleSource(source)
        testScheduler.advanceUntilIdle()

        world.runtime.start(media(videoId = "movie"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("job-1"), world.port.enqueued.map { it.jobId })
        assertEquals(DownloadStatus.Downloading, world.status("movie"))
        assertNull(world.entry("movie").subtitle)
    }

    @Test
    fun failedPlatformReconciliationDoesNotSubmitPotentialDuplicateWork() = runTest {
        val port = FakeBackgroundDownloadPort().apply {
            reconcileFailure = IllegalStateException("platform unavailable")
        }
        val world = world(port = port)
        testScheduler.advanceUntilIdle()

        val result = world.runtime.start(media(videoId = "movie"))

        assertIs<DownloadStartResult.Failed>(result)
        assertTrue(port.enqueued.isEmpty())
        assertTrue(port.resumed.isEmpty())
    }

    @Test
    fun ordinaryIndexAndPlatformEventsNeverContainTheSourceUrl() = runTest {
        val world = world()
        testScheduler.advanceUntilIdle()
        val secretUrl = "https://source.test/movie.mkv?token=never-persist-this"

        world.runtime.start(media(videoId = "movie", sourceUrl = secretUrl))
        testScheduler.advanceUntilIdle()

        val raw = world.store.values.getValue(StorageKeys.DownloadsV2)
        assertFalse(raw.contains(secretUrl))
        assertFalse(raw.contains("never-persist-this"))
        assertEquals(secretUrl, world.vault.requests.values.single().sourceUrl)
        assertEquals("job-1", world.port.enqueued.single().jobId)
    }

    @Test
    fun removeCancelsBeforeDeletingOwnedFilesAndPumpsTheQueue() = runTest {
        val world = world()
        testScheduler.advanceUntilIdle()
        world.runtime.start(media(videoId = "first"))
        world.runtime.start(media(videoId = "second", sourceUrl = "https://source.test/2.mkv"))
        testScheduler.advanceUntilIdle()
        val first = world.entry("first")
        world.fileSystem.write("/downloads/${first.partFileName}".toPath()) { writeUtf8("partial") }
        world.fileSystem.write("/downloads/${first.partFileName}.segments".toPath()) { writeUtf8("{}") }
        val cancelGate = CompletableDeferred<Unit>()
        world.port.cancelGate = cancelGate

        val removal = async { world.runtime.remove("first") }
        testScheduler.runCurrent()

        assertEquals(listOf("job-1"), world.port.cancelled)
        assertTrue(world.fileSystem.exists("/downloads/${first.partFileName}".toPath()))

        cancelGate.complete(Unit)
        removal.await()
        testScheduler.advanceUntilIdle()

        assertFalse(world.fileSystem.exists("/downloads/${first.partFileName}".toPath()))
        assertFalse(world.fileSystem.exists("/downloads/${first.partFileName}.segments".toPath()))
        assertEquals(DownloadStatus.Downloading, world.status("second"))
    }

    @Test
    fun knownStorageShortfallIsRefusedBeforeWritingARequest() = runTest {
        val world = world(storage = FakeDownloadStorage(free = 2_000_000_000))
        testScheduler.advanceUntilIdle()

        val result = world.runtime.start(media(videoId = "movie", videoSize = 1_900_000_000))

        assertIs<DownloadStartResult.NotEnoughSpace>(result)
        assertTrue(world.vault.requests.isEmpty())
    }
}

private data class World(
    val runtime: DeviceDownloadRuntime,
    val port: FakeBackgroundDownloadPort,
    val vault: FakeDownloadVault,
    val store: FakeStore,
    val clock: FakeClock,
    val fileSystem: FakeFileSystem,
) {
    fun entry(videoId: String): DownloadEntry = requireNotNull(runtime.entryFor(videoId))
    fun status(videoId: String): DownloadStatus = entry(videoId).status
}

private fun TestScope.world(
    store: FakeStore = FakeStore(),
    vault: FakeDownloadVault = FakeDownloadVault(),
    port: FakeBackgroundDownloadPort = FakeBackgroundDownloadPort(),
    storage: DownloadStoragePort = FakeDownloadStorage(),
    files: List<String> = emptyList(),
): World {
    val fileSystem = FakeFileSystem().apply {
        createDirectories("/downloads".toPath())
        files.forEach { name -> write("/downloads/$name".toPath()) { writeUtf8("seed") } }
    }
    val clock = FakeClock()
    var nextJob = 0
    val runtime = DeviceDownloadRuntime(
        index = DownloadIndex(store, vault) { "migration-${++nextJob}" },
        storage = storage,
        port = port,
        vault = vault,
        clock = clock,
        scope = CoroutineScope(coroutineContext + SupervisorJob()),
        fileSystem = fileSystem,
        jobIdFactory = { "job-${++nextJob}" },
    )
    return World(runtime, port, vault, store, clock, fileSystem)
}

private fun request(jobId: String, fileName: String) = ProtectedDownloadRequest(
    jobId = jobId,
    sourceUrl = "https://source.test/$fileName",
    partFilePath = "/downloads/$fileName.part",
    targetFilePath = "/downloads/$fileName",
)
