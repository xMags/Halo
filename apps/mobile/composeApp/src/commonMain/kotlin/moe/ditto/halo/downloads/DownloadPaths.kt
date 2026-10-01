package moe.ditto.halo.downloads

import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path

/**
 * File naming for downloads, and the sweep that removes files nothing points
 * at any more.
 *
 * Names are derived, never taken: a video id comes from an addon and a URL from
 * a resolver, and neither may contribute a path component. What survives is an
 * ASCII-safe prefix of the id, for a directory a human can still read, plus a
 * hash of the whole id, because the sanitising is lossy and two different ids
 * could otherwise sanitise to one name and overwrite each other's file.
 */
internal object DownloadPaths {

    /** Container formats real sources serve. Anything else is stored as `.mkv`. */
    private val VideoExtensions = setOf("mkv", "mp4", "webm", "avi", "ts", "m4v", "mov")

    /** Subtitle formats libmpv renders directly; the file is kept exactly as served. */
    private val SubtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub")

    private const val MaxPrefixLength = 48
    private const val HashLength = 8

    fun videoFileName(videoId: String, sourceUrl: String): String =
        fileName(videoId, extensionOf(sourceUrl, VideoExtensions, fallback = "mkv"))

    fun subtitleFileName(videoId: String, sourceUrl: String): String =
        fileName(videoId, extensionOf(sourceUrl, SubtitleExtensions, fallback = "srt"))

    private fun fileName(videoId: String, extension: String): String {
        val prefix = videoId
            .map { if (it.isAsciiSafe()) it else '_' }
            .joinToString("")
            .take(MaxPrefixLength)
            .trim('_')
            .ifEmpty { "video" }
        val digest = videoId.encodeUtf8().sha256().hex().take(HashLength)
        return "$prefix-$digest.$extension"
    }

    private fun Char.isAsciiSafe(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '.' || this == '-'

    /**
     * The extension the URL's own path ends in, when it is one this app expects
     * to store. Query strings and fragments are dropped first: signed source
     * URLs routinely end in a token, not a file name.
     */
    private fun extensionOf(url: String, allowed: Set<String>, fallback: String): String =
        url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in allowed }
            ?: fallback

    /**
     * Deletes everything in [directory] that no entry claims, including partial
     * files left by a transfer that was interrupted for good.
     *
     * Only ever called with a complete index (see [DownloadIndexSnapshot]). Run
     * against a partial one it would read every unlisted file as an orphan.
     */
    fun sweepOrphans(fileSystem: FileSystem, directory: Path, entries: List<DownloadEntry>) {
        if (!fileSystem.exists(directory)) return
        val referenced = buildSet {
            entries.forEach { entry ->
                add(entry.fileName)
                add(entry.partFileName)
                entry.subtitle?.let { add(it.fileName) }
                addAll(entry.retainedFileNames)
            }
        }
        val present = try {
            fileSystem.list(directory)
        } catch (_: okio.IOException) {
            return
        }
        present.forEach { path ->
            if (path.name in referenced) return@forEach
            try {
                fileSystem.delete(path, mustExist = false)
            } catch (_: okio.IOException) {
                // A file that will not delete is not worth failing startup over;
                // it costs space until the next sweep, and nothing reads it.
            }
        }
    }
}
