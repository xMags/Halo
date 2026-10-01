package moe.ditto.halo.player

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.auth.TokenProvider
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubtitleFileCacheTest {
    @Test
    fun localFileUrlsRemainDirect() = runTest {
        var requests = 0
        val cache = cache(FakeFileSystem(), handler = {
            requests += 1
            "unused"
        })

        val resolved = cache.resolve("local", "file:///data/user/0/halo/files/caption.srt")

        assertEquals("file:///data/user/0/halo/files/caption.srt", resolved)
        assertEquals(0, requests)
    }

    @Test
    fun remoteSubtitleIsStreamedThroughTheProxyIntoThePrivateCache() = runTest {
        val fileSystem = FakeFileSystem()
        val cache = cache(fileSystem) { "caption body" }

        val resolved = cache.resolve("video|addon|subtitle", "https://subs.example/caption.srt")

        assertTrue(resolved.startsWith("/cache/"))
        assertTrue(resolved.endsWith(".srt"))
        assertEquals("caption body", fileSystem.read(resolved.toPath()) { readUtf8() })
    }

    @Test
    fun oversizedSubtitleIsRejectedWithoutLeavingAPartialFile() = runTest {
        val fileSystem = FakeFileSystem()
        val cache = cache(fileSystem, maxBytes = 4) { "12345" }

        assertFailsWith<SubtitleFileTooLargeException> {
            cache.resolve("large", "https://subs.example/large.srt")
        }

        assertEquals(emptyList(), fileSystem.list("/cache".toPath()))
    }

    @Test
    fun failedAtomicMoveLeavesNoFileThatLibmpvCouldOpen() = runTest {
        val delegate = FakeFileSystem()
        val failing = object : ForwardingFileSystem(delegate) {
            override fun atomicMove(source: Path, target: Path) {
                throw IOException("fixture atomic move failed")
            }
        }
        val cache = cache(failing) { "caption body" }

        assertFailsWith<IOException> {
            cache.resolve("atomic", "https://subs.example/caption.ass")
        }

        assertEquals(emptyList(), delegate.list("/cache".toPath()))
    }

    @Test
    fun unsupportedSchemesFailWithAVisibleReason() = runTest {
        val failure = assertFailsWith<UnsupportedSubtitleUrlException> {
            cache(FakeFileSystem()) { "unused" }
                .resolve("bad", "content://provider/subtitle/1")
        }

        assertEquals("The content subtitle URL scheme is not supported.", failure.message)
    }

    private fun cache(
        fileSystem: FileSystem,
        maxBytes: Long = 10L * 1024L * 1024L,
        handler: () -> String,
    ): SubtitleFileCache {
        val engine = MockEngine {
            respond(
                content = handler(),
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
            )
        }
        val client = HaloClient("https://halo.example", StaticTokens, HttpClient(engine))
        return SubtitleFileCache(client, "/cache", fileSystem, maxBytes)
    }

    private object StaticTokens : TokenProvider {
        override suspend fun accessToken(): String = "access"
        override suspend fun refreshAccessToken(): String = "access"
    }
}
