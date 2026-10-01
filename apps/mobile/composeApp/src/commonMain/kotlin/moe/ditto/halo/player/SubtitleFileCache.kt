package moe.ditto.halo.player

import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ditto.halo.api.HaloClient
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.ByteString.Companion.encodeUtf8

internal open class SubtitleFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class UnsupportedSubtitleUrlException(scheme: String?) : SubtitleFileException(
    if (scheme.isNullOrBlank()) {
        "This subtitle URL has no supported scheme."
    } else {
        "The $scheme subtitle URL scheme is not supported."
    },
)

internal class SubtitleFileTooLargeException : SubtitleFileException("This subtitle is larger than 10 MiB.")

/**
 * Resolves addon subtitles into files libmpv can open without credentials.
 *
 * Remote files always pass through Halo's authenticated proxy. They are
 * streamed into a temporary file, bounded while streaming even when the server
 * omits Content-Length, and become visible only through an atomic move.
 */
internal class SubtitleFileCache(
    private val client: HaloClient,
    cacheDirectory: String,
    private val fileSystem: FileSystem = subtitleFileSystem(),
    private val maxBytes: Long = MaxSubtitleBytes,
) {
    private val directory: Path = cacheDirectory.toPath()
    private val writeMutex = Mutex()

    suspend fun resolve(identity: String, sourceUrl: String): String {
        val url = try {
            Url(sourceUrl)
        } catch (_: IllegalArgumentException) {
            throw UnsupportedSubtitleUrlException(null)
        }
        val scheme = url.protocol.name.lowercase()
        if (scheme == "file") return sourceUrl
        if (scheme != "http" && scheme != "https") throw UnsupportedSubtitleUrlException(scheme)

        return writeMutex.withLock {
            fileSystem.createDirectories(directory)
            val fileName = cacheFileName(identity, sourceUrl)
            val target = directory / fileName
            if (fileSystem.exists(target)) return@withLock target.toString()

            val temporary = directory / ".$fileName.part"
            fileSystem.delete(temporary, mustExist = false)
            try {
                download(sourceUrl, temporary)
                fileSystem.atomicMove(temporary, target)
                target.toString()
            } catch (failure: Throwable) {
                fileSystem.delete(temporary, mustExist = false)
                throw failure
            }
        }
    }

    private suspend fun download(sourceUrl: String, temporary: Path) {
        val response = client.getAddonProxyResponse(sourceUrl)
        val declaredSize = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredSize != null && declaredSize > maxBytes) throw SubtitleFileTooLargeException()

        val channel = response.bodyAsChannel()
        val sink = fileSystem.sink(temporary).buffer()
        try {
            val bytes = ByteArray(BufferSize)
            var total = 0L
            while (true) {
                val count = channel.readAvailable(bytes, 0, bytes.size)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > maxBytes) throw SubtitleFileTooLargeException()
                sink.write(bytes, 0, count)
            }
        } finally {
            sink.close()
        }
    }

    private fun cacheFileName(identity: String, sourceUrl: String): String {
        val digest = "$identity\u0000$sourceUrl".encodeUtf8().sha256().hex()
        val extension = sourceUrl.substringBefore('?').substringBefore('#')
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in SubtitleExtensions }
            ?: "subtitle"
        return "$digest.$extension"
    }

    private companion object {
        const val MaxSubtitleBytes = 10L * 1024L * 1024L
        const val BufferSize = 8 * 1024
        val SubtitleExtensions = setOf("srt", "vtt", "webvtt", "ass", "ssa", "sub")
    }
}

internal expect fun subtitleFileSystem(): FileSystem
