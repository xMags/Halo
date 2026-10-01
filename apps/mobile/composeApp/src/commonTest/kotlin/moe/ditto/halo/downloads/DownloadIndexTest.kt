package moe.ditto.halo.downloads

import moe.ditto.halo.storage.StorageKeys
import moe.ditto.halo.storage.KeyValueStore
import moe.ditto.halo.sync.FakeStore
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadIndexTest {
    @Test
    fun everythingAllowedInTheOrdinaryIndexSurvivesARoundTrip() {
        val store = FakeStore()
        val index = DownloadIndex(store)
        val original = entry(videoId = "tt1:1:2", status = DownloadStatus.Paused).copy(
            subtitle = DownloadSubtitle(fileName = "tt1.srt", lang = "eng", subId = "os-9"),
            resumeValidator = "\"abc\"",
            downloadedBytes = 40,
        )

        index.write(listOf(original))
        val snapshot = DownloadIndex(store).read()

        assertTrue(snapshot.complete)
        // HTTP validators are protected headers, not ordinary index data.
        assertEquals(
            listOf(
                original.copy(
                    media = original.media.copy(sourceUrl = ""),
                    resumeValidator = null,
                ),
            ),
            snapshot.entries,
        )
    }

    @Test
    fun anEmptyStoreReadsAsAnEmptyIndexThatIsStillCompleteEnoughToSweep() {
        val snapshot = DownloadIndex(FakeStore()).read()
        assertEquals(emptyList(), snapshot.entries)
        assertTrue(snapshot.complete)
    }

    @Test
    fun anUnreadableDocumentYieldsNothingAndBlocksTheSweep() {
        val store = FakeStore()
        store.write(StorageKeys.DownloadsV2, "{not json at all")

        val snapshot = DownloadIndex(store).read()

        assertEquals(emptyList(), snapshot.entries)
        assertFalse(snapshot.complete)
    }

    @Test
    fun oneUnreadableRecordKeepsTheOthersAndStillBlocksTheSweep() {
        val store = FakeStore()
        DownloadIndex(store).write(listOf(entry(videoId = "good")))
        val stored = store.values.getValue(StorageKeys.DownloadsV2)
        store.write(StorageKeys.DownloadsV2, stored.dropLast(2) + ",\"broken\":7}}")

        val snapshot = DownloadIndex(store).read()

        assertEquals(listOf("good"), snapshot.entries.map { it.videoId })
        assertFalse(snapshot.complete)
    }

    @Test
    fun oneEntryPerVideoIsHowTheDocumentIsShaped() {
        val store = FakeStore()
        val index = DownloadIndex(store)
        index.write(
            listOf(
                entry(videoId = "same", status = DownloadStatus.Paused),
                entry(videoId = "same", status = DownloadStatus.Done),
            ),
        )

        val snapshot = index.read()

        assertEquals(1, snapshot.entries.size)
        assertEquals(DownloadStatus.Done, snapshot.entries.single().status)
    }

    @Test
    fun v1MigrationProtectsTheRequestBeforeRemovingEveryOrdinaryUrl() {
        val store = FakeStore().apply { write(StorageKeys.Downloads, legacyIndex(status = "paused")) }
        val vault = FakeDownloadVault()
        val index = DownloadIndex(store, vault) { "migration-job" }

        val snapshot = index.read("/downloads".toPath())

        assertTrue(snapshot.complete)
        assertEquals("migration-job", snapshot.entries.single().jobId)
        assertEquals(sourceFingerprint(SecretUrl), snapshot.entries.single().media.sourceFingerprint)
        assertNull(store.read(StorageKeys.Downloads))
        val v2 = assertNotNull(store.read(StorageKeys.DownloadsV2))
        assertFalse(v2.contains(SecretUrl))
        assertFalse(v2.contains("secret-token"))
        assertEquals(SecretUrl, vault.requests.getValue("migration-job").sourceUrl)
        assertEquals("/downloads/tt1.mkv.part", vault.requests.getValue("migration-job").partFilePath)
    }

    @Test
    fun failedProtectedWriteLeavesV1AuthoritativeForCrashSafeRetry() {
        val store = FakeStore().apply { write(StorageKeys.Downloads, legacyIndex(status = "downloading")) }
        val vault = FakeDownloadVault().apply { failWrites = true }

        val snapshot = DownloadIndex(store, vault) { "migration-job" }
            .read("/downloads".toPath())

        assertFalse(snapshot.complete)
        assertNotNull(store.read(StorageKeys.Downloads))
        assertNull(store.read(StorageKeys.DownloadsV2))
    }

    @Test
    fun aCrashAfterV2WriteUsesV2AndOnlyCleansTheStaleV1Copy() {
        val store = FakeStore()
        val vault = FakeDownloadVault()
        val index = DownloadIndex(store, vault) { "unused" }
        index.write(listOf(entry(videoId = "tt1", status = DownloadStatus.Paused, jobId = "kept-job")))
        store.write(StorageKeys.Downloads, legacyIndex(status = "paused"))

        val snapshot = index.read("/downloads".toPath())

        assertEquals("kept-job", snapshot.entries.single().jobId)
        assertNull(store.read(StorageKeys.Downloads))
        assertTrue(vault.requests.isEmpty())
    }

    @Test
    fun interruptedMigrationReusesItsUrlFreeJournalJobInsteadOfCreatingOrphans() {
        val store = FailingMigrationStore().apply {
            write(StorageKeys.Downloads, legacyIndex(status = "downloading"))
            failV2Writes = true
        }
        val vault = FakeDownloadVault()
        var nextJob = 0
        val index = DownloadIndex(store, vault) { "migration-${++nextJob}" }

        val interrupted = index.read("/downloads".toPath())

        assertFalse(interrupted.complete)
        assertTrue(vault.requests.isEmpty())
        val journal = assertNotNull(store.read(StorageKeys.DownloadsMigration))
        assertFalse(journal.contains(SecretUrl))
        assertFalse(journal.contains("secret-token"))

        store.failV2Writes = false
        val recovered = index.read("/downloads".toPath())

        assertTrue(recovered.complete)
        assertEquals("migration-1", recovered.entries.single().jobId)
        assertEquals(setOf("migration-1"), vault.requests.keys)
        assertEquals(1, nextJob)
        assertNull(store.read(StorageKeys.DownloadsMigration))
    }

    @Test
    fun failedLegacyCleanupCannotDeleteRequestsAlreadyReferencedByV2() {
        val store = FailingMigrationStore().apply {
            write(StorageKeys.Downloads, legacyIndex(status = "paused"))
            failLegacyDeletes = true
        }
        val vault = FakeDownloadVault()

        val snapshot = DownloadIndex(store, vault) { "migration-job" }
            .read("/downloads".toPath())

        assertTrue(snapshot.complete)
        assertNotNull(store.read(StorageKeys.DownloadsV2))
        assertNotNull(store.read(StorageKeys.Downloads))
        assertEquals(SecretUrl, vault.requests.getValue("migration-job").sourceUrl)

        store.failLegacyDeletes = false
        val reread = DownloadIndex(store, vault).read("/downloads".toPath())
        assertTrue(reread.complete)
        assertNull(store.read(StorageKeys.Downloads))
        assertEquals(SecretUrl, vault.requests.getValue("migration-job").sourceUrl)
    }

    @Test
    fun iosMigrationKeepsOldPartialStatePausedUntilManualResume() {
        val store = FakeStore().apply { write(StorageKeys.Downloads, legacyIndex(status = "downloading")) }
        val vault = FakeDownloadVault()

        val snapshot = DownloadIndex(store, vault) { "ios-job" }
            .read("/downloads".toPath(), resumeMigratedPartialFiles = false)

        assertEquals(DownloadStatus.Paused, snapshot.entries.single().status)
        assertEquals("/downloads/tt1.mkv.part", vault.requests.getValue("ios-job").partFilePath)
    }

    private companion object {
        const val SecretUrl = "https://source.test/movie.mkv?token=secret-token"

        fun legacyIndex(status: String): String =
            """{"tt1":{"media":{"videoId":"tt1","type":"movie","metaId":"tt1","showTitle":"Movie","sourceUrl":"$SecretUrl","addonId":"addon"},"fileName":"tt1.mkv","status":"$status","downloadedBytes":4,"createdAt":1,"updatedAt":2}}"""
    }
}

private class FailingMigrationStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    var failV2Writes = false
    var failLegacyDeletes = false

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        if (key == StorageKeys.DownloadsV2 && failV2Writes) error("v2 write failed")
        values[key] = value
    }

    override fun delete(key: String) {
        if (key == StorageKeys.Downloads && failLegacyDeletes) error("legacy delete failed")
        values.remove(key)
    }
}
