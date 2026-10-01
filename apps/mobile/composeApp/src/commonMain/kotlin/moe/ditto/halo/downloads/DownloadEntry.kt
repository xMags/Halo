package moe.ditto.halo.downloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import moe.ditto.halo.sync.LibraryRepository
import okio.ByteString.Companion.encodeUtf8

/** Where a download has got to. */
@Serializable
internal enum class DownloadStatus {
    /** Accepted, waiting for the one active transfer to finish. */
    @SerialName("queued")
    Queued,

    @SerialName("downloading")
    Downloading,

    /** Stopped by the viewer, or by the app going away mid-transfer. */
    @SerialName("paused")
    Paused,

    @SerialName("done")
    Done,

    @SerialName("failed")
    Failed,
    ;

    val isActive: Boolean get() = this == Queued || this == Downloading
}

/**
 * What is being downloaded, as opposed to how far along it is.
 *
 * This is the same context the player is entered with, and deliberately so: an
 * entry played offline goes through the ordinary player route, so it has to be
 * able to name what it is showing, parse its own source badges and ask for the
 * next episode without a network. Anything missing here is something the
 * offline player would have to invent.
 */
@Serializable
internal data class DownloadMedia(
    val videoId: String,
    /** `"movie"` or `"series"`, as the addon protocol spells it. */
    val type: String,
    val metaId: String,
    val showTitle: String,
    /** Null for films: they are one video, and there is no episode to tag. */
    val episodeTag: String? = null,
    val episodeName: String? = null,
    val episodeThumbnail: String? = null,
    /** The title's own art, for the section header this entry is grouped under. */
    val poster: String? = null,
    /**
     * SHA-256 of the resolved source URL, used only to recognize the selected
     * source row without persisting its credential-bearing URL.
     */
    val sourceFingerprint: String = "",
    /**
     * Present only while a newly selected source is handed to the runtime.
     * Serialization deliberately replaces it with an empty value, so a source
     * URL can never enter the ordinary download index.
     */
    @Transient
    val sourceUrl: String = "",
    /** The addon that offered the source, for asking the same one what follows. */
    val addonId: String,
    val bingeGroup: String? = null,
    /** Behaviour hints the addon attached to the source, all optional to it. */
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
    /** Raw source naming, for badge parsing only. Never displayed as given. */
    val streamName: String? = null,
    val streamTitle: String? = null,
) {
    /**
     * The library item this video belongs to, and the key the Downloads screen
     * groups by. Scoped by type, like every other reader of a library row.
     */
    val itemId: String get() = LibraryRepository.itemId(type, metaId)

    val isEpisode: Boolean get() = episodeTag != null

    /** One line naming the video, for places with room for only one. */
    val displayTitle: String
        get() = if (episodeTag == null) showTitle else "$showTitle · $episodeTag"
}

/** A subtitle kept beside the video, in the format the addon served it in. */
@Serializable
internal data class DownloadSubtitle(
    /** Relative to the downloads directory, for the reason [DownloadEntry.fileName] is. */
    val fileName: String,
    val lang: String? = null,
    /** The addon's own subtitle id, so a remembered choice can match it exactly. */
    val subId: String? = null,
)

/**
 * One downloaded video, one entry, keyed by video id.
 *
 * Paths are stored relative to the downloads directory and joined when read.
 * The Expo client stored absolute URIs and needed re-anchoring logic to survive
 * iOS rotating the app container's UUID on every reinstall, which made every
 * stored path a guess about whether it was still true. A file name cannot go
 * stale that way.
 */
@Serializable
internal data class DownloadEntry(
    val media: DownloadMedia,
    /** Relative to the downloads directory. The transfer writes `<fileName>.part`. */
    val fileName: String,
    val subtitle: DownloadSubtitle? = null,
    val status: DownloadStatus,
    /** Opaque identity of the current platform-owned attempt. */
    val jobId: String? = null,
    /** Zero when the source never declared a size, which some hosts do not. */
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    /**
     * The source's `ETag` or `Last-Modified`, sent back as `If-Range` when
     * resuming. A source that changed answers 200 instead of 206, and the
     * transfer restarts rather than splicing two different files together.
     */
    @Transient
    val resumeValidator: String? = null,
    /** Set only while [status] is [DownloadStatus.Failed]. */
    val failure: DownloadFailure? = null,
    /**
     * Files from a replaced attempt. They remain until the replacement has
     * completed, so cancellation or a stale callback cannot destroy the only
     * bytes the user already had.
     */
    val retainedFileNames: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * How fast bytes are currently arriving, smoothed over the last few
     * samples. Deliberately not persisted: it describes a transfer that is
     * running right now, and a speed restored from disk would be a number about
     * a connection that no longer exists.
     */
    @Transient
    val bytesPerSecond: Long = 0,
) {
    val videoId: String get() = media.videoId
    val itemId: String get() = media.itemId

    /** The partially transferred file, which is what resume appends to. */
    val partFileName: String get() = "$fileName.part"

    /** Progress as 0..1, or null when the total is unknown and there is nothing to divide by. */
    val fraction: Float?
        get() {
            if (totalBytes <= 0) return null
            return (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }

    /**
     * Seconds until this finishes at the current rate, or null when either half
     * of that division is unknown. A guess made from no measurement is worse
     * than no guess.
     */
    val secondsRemaining: Long?
        get() {
            if (bytesPerSecond <= 0 || totalBytes <= 0) return null
            val remaining = totalBytes - downloadedBytes
            if (remaining <= 0) return null
            return remaining / bytesPerSecond
        }

    val failureMessage: String? get() = failure?.message
}

/** A stable, URL-free identity for matching a picker row to an entry. */
internal fun sourceFingerprint(sourceUrl: String): String =
    sourceUrl.encodeUtf8().sha256().hex()
