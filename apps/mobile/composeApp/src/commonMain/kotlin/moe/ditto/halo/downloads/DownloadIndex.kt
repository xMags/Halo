package moe.ditto.halo.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.storage.KeyValueStore
import moe.ditto.halo.storage.StorageKeys
import okio.ByteString.Companion.encodeUtf8
import okio.Path

/** What was read, and whether it is complete enough for an orphan sweep. */
internal data class DownloadIndexSnapshot(
    val entries: List<DownloadEntry>,
    val complete: Boolean,
)

@Serializable
private data class DownloadIndexV2(
    val version: Int,
    val entries: Map<String, DownloadEntry>,
)

/** URL-free restart point for an interrupted v1 migration. */
@Serializable
private data class DownloadMigrationJournal(
    val version: Int,
    val sourceDigest: String,
    val jobIds: Map<String, String>,
)

/** Exact v1 shape, retained only for the one-way protected-request migration. */
@Serializable
private data class DownloadMediaV1(
    val videoId: String,
    val type: String,
    val metaId: String,
    val showTitle: String,
    val episodeTag: String? = null,
    val episodeName: String? = null,
    val episodeThumbnail: String? = null,
    val poster: String? = null,
    val sourceUrl: String,
    val addonId: String,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
    val streamName: String? = null,
    val streamTitle: String? = null,
)

@Serializable
private data class DownloadEntryV1(
    val media: DownloadMediaV1,
    val fileName: String,
    val subtitle: DownloadSubtitle? = null,
    val status: DownloadStatus,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val resumeValidator: String? = null,
    val failureMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * The device-local v2 index and its crash-idempotent v1 migration.
 *
 * Migration ordering is security-critical: every recoverable source request is
 * encrypted first, then the URL-free v2 document is written, and only then is
 * v1 removed. A crash at any boundary either leaves v1 authoritative or leaves
 * a complete v2 document that the next read uses before deleting stale v1.
 */
internal class DownloadIndex(
    private val store: KeyValueStore,
    private val vault: DownloadRequestVault? = null,
    private val jobIdFactory: () -> String = ::newDownloadJobId,
) {

    fun read(
        directory: Path? = null,
        resumeMigratedPartialFiles: Boolean = true,
    ): DownloadIndexSnapshot {
        store.read(StorageKeys.DownloadsV2)?.let { raw ->
            val snapshot = decodeV2(raw)
            if (snapshot.complete) cleanMigrationKeys()
            return snapshot
        }

        val rawV1 = store.read(StorageKeys.Downloads)
            ?: return DownloadIndexSnapshot(emptyList(), complete = true).also {
                runCatching { store.delete(StorageKeys.DownloadsMigration) }
            }
        return migrateV1(rawV1, directory, resumeMigratedPartialFiles)
    }

    fun write(entries: List<DownloadEntry>) {
        val document = DownloadIndexV2(version = 2, entries = entries.associateBy { it.videoId })
        store.write(StorageKeys.DownloadsV2, HaloJson.encodeToString(DownloadIndexV2.serializer(), document))
    }

    private fun decodeV2(raw: String): DownloadIndexSnapshot {
        val root = try {
            HaloJson.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return DownloadIndexSnapshot(emptyList(), complete = false)
        if (root["version"] == null || root["entries"] !is JsonObject) {
            return DownloadIndexSnapshot(emptyList(), complete = false)
        }

        val document = try {
            HaloJson.decodeFromJsonElement(DownloadIndexV2.serializer(), root)
        } catch (_: SerializationException) {
            return decodeV2EntriesIndividually(root["entries"] as JsonObject)
        } catch (_: IllegalArgumentException) {
            return decodeV2EntriesIndividually(root["entries"] as JsonObject)
        }
        if (document.version != 2) return DownloadIndexSnapshot(emptyList(), complete = false)
        return DownloadIndexSnapshot(document.entries.values.toList(), complete = true)
    }

    private fun decodeV2EntriesIndividually(entries: JsonObject): DownloadIndexSnapshot {
        var complete = true
        val decoded = entries.mapNotNull { (_, element) ->
            try {
                HaloJson.decodeFromJsonElement(DownloadEntry.serializer(), element)
            } catch (_: SerializationException) {
                complete = false
                null
            } catch (_: IllegalArgumentException) {
                complete = false
                null
            }
        }
        return DownloadIndexSnapshot(decoded, complete)
    }

    private fun migrateV1(
        raw: String,
        directory: Path?,
        resumeMigratedPartialFiles: Boolean,
    ): DownloadIndexSnapshot {
        val root = try {
            HaloJson.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return DownloadIndexSnapshot(emptyList(), complete = false)

        var complete = true
        val oldEntries = root.mapNotNull { (_, element) ->
            try {
                HaloJson.decodeFromJsonElement(DownloadEntryV1.serializer(), element)
            } catch (_: SerializationException) {
                complete = false
                null
            } catch (_: IllegalArgumentException) {
                complete = false
                null
            }
        }
        if (!complete) {
            return DownloadIndexSnapshot(oldEntries.map { it.toV2(jobId = null, resumeMigratedPartialFiles) }, false)
        }

        val protectedStore = vault
        val migrated = mutableListOf<DownloadEntry>()
        val writtenJobs = mutableListOf<String>()
        val requestEntries = oldEntries.filter {
            it.status != DownloadStatus.Done && it.media.sourceUrl.isNotBlank()
        }
        if (requestEntries.isNotEmpty() && (protectedStore == null || directory == null)) {
            return DownloadIndexSnapshot(oldEntries.map { it.toV2(jobId = null, resumeMigratedPartialFiles) }, false)
        }
        val sourceDigest = raw.encodeUtf8().sha256().hex()
        val migrationJobs = if (requestEntries.isEmpty()) {
            emptyMap()
        } else {
            migrationJobs(sourceDigest, requestEntries.map { it.media.videoId })
                ?: return DownloadIndexSnapshot(
                    oldEntries.map { it.toV2(jobId = null, resumeMigratedPartialFiles) },
                    false,
                )
        }
        for (old in oldEntries) {
            val needsRequest = old.status != DownloadStatus.Done && old.media.sourceUrl.isNotBlank()
            val jobId = if (needsRequest) migrationJobs[old.media.videoId] else null
            if (jobId != null) {
                val request = ProtectedDownloadRequest(
                    jobId = jobId,
                    sourceUrl = old.media.sourceUrl,
                    partFilePath = (directory!! / "${old.fileName}.part").toString(),
                    targetFilePath = (directory / old.fileName).toString(),
                    resumeValidator = old.resumeValidator,
                )
                if (!protectedStore!!.write(request)) {
                    writtenJobs.forEach(protectedStore::delete)
                    return DownloadIndexSnapshot(
                        oldEntries.map { it.toV2(jobId = null, resumeMigratedPartialFiles) },
                        false,
                    )
                }
                writtenJobs += jobId
            }
            migrated += old.toV2(jobId, resumeMigratedPartialFiles)
        }

        val v2IsDurable = try {
            write(migrated)
            true
        } catch (_: Throwable) {
            val recovered = runCatching { store.read(StorageKeys.DownloadsV2) }
                .getOrNull()
                ?.let(::decodeV2)
            recovered?.complete == true && recovered.entries == migrated
        }
        if (!v2IsDurable) {
            writtenJobs.forEach { protectedStore?.delete(it) }
            return DownloadIndexSnapshot(migrated, complete = false)
        }
        cleanMigrationKeys()
        return DownloadIndexSnapshot(migrated, complete = true)
    }

    private fun migrationJobs(sourceDigest: String, videoIds: List<String>): Map<String, String>? {
        val expected = videoIds.toSet()
        val existing = store.read(StorageKeys.DownloadsMigration)?.let { raw ->
            runCatching {
                HaloJson.decodeFromString(DownloadMigrationJournal.serializer(), raw)
            }.getOrNull()
        }
        if (existing?.version == 1 &&
            existing.sourceDigest == sourceDigest &&
            existing.jobIds.keys == expected &&
            existing.jobIds.values.all { it.isNotBlank() } &&
            existing.jobIds.values.toSet().size == existing.jobIds.size
        ) {
            return existing.jobIds
        }

        val created = videoIds.associateWith { jobIdFactory() }
        val journal = DownloadMigrationJournal(
            version = 1,
            sourceDigest = sourceDigest,
            jobIds = created,
        )
        return try {
            store.write(
                StorageKeys.DownloadsMigration,
                HaloJson.encodeToString(DownloadMigrationJournal.serializer(), journal),
            )
            created
        } catch (_: Throwable) {
            null
        }
    }

    private fun cleanMigrationKeys() {
        runCatching { store.delete(StorageKeys.Downloads) }
        runCatching { store.delete(StorageKeys.DownloadsMigration) }
    }

    private fun DownloadEntryV1.toV2(
        jobId: String?,
        resumeMigratedPartialFiles: Boolean,
    ): DownloadEntry {
        val migratedStatus = when {
            status.isActive && !resumeMigratedPartialFiles -> DownloadStatus.Paused
            else -> status
        }
        return DownloadEntry(
            media = DownloadMedia(
                videoId = media.videoId,
                type = media.type,
                metaId = media.metaId,
                showTitle = media.showTitle,
                episodeTag = media.episodeTag,
                episodeName = media.episodeName,
                episodeThumbnail = media.episodeThumbnail,
                poster = media.poster,
                sourceFingerprint = sourceFingerprint(media.sourceUrl),
                addonId = media.addonId,
                bingeGroup = media.bingeGroup,
                filename = media.filename,
                videoSize = media.videoSize,
                videoHash = media.videoHash,
                streamName = media.streamName,
                streamTitle = media.streamTitle,
            ),
            fileName = fileName,
            subtitle = subtitle,
            status = migratedStatus,
            jobId = jobId,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            resumeValidator = resumeValidator,
            failure = failureMessage?.let { DownloadFailure(DownloadFailureCode.Unknown) },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
