package moe.ditto.halo.downloads

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.sync.FakeClock
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A resolved source URL looks like this: the credential is in the query. */
private const val SourceUrl = "https://source.test/movie.mkv?token=secret-token"

private val Target: Path = "/downloads/movie.mkv".toPath()
private val Part: Path = "/downloads/movie.mkv.part".toPath()

class HttpRangeTransferTest {

    @Test
    fun aFreshDownloadOnlyBecomesVisibleOnceItIsWhole() = runTest {
        val fileSystem = fileSystem()
        val transfer = transfer(fileSystem) {
            respond(
                content = ByteReadChannel("abcdef"),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf("6"),
                    HttpHeaders.ETag to listOf("\"v1\""),
                ),
            )
        }

        val result = transfer.fetch()

        assertEquals("abcdef", fileSystem.read(Target) { readUtf8() })
        assertFalse(fileSystem.exists(Part))
        assertEquals(TransferProgress(6, 6, "\"v1\""), result)
    }

    @Test
    fun aResumeAsksOnlyForTheRestAndAppendsIt() = runTest {
        val fileSystem = fileSystem()
        fileSystem.write(Part) { writeUtf8("abc") }
        var request: HttpRequestData? = null
        val transfer = transfer(fileSystem) { data ->
            request = data
            respond(
                content = ByteReadChannel("def"),
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentRange, "bytes 3-5/6"),
            )
        }

        val result = transfer.fetch(resumeValidator = "\"v1\"")

        val sent = assertNotNull(request)
        assertEquals("bytes=3-", sent.headers[HttpHeaders.Range])
        // Without If-Range a re-minted link appends fresh bytes to a stale
        // prefix, producing a file of exactly the right length that will not play.
        assertEquals("\"v1\"", sent.headers[HttpHeaders.IfRange])
        assertEquals("abcdef", fileSystem.read(Target) { readUtf8() })
        assertEquals(6, result.totalBytes)
    }

    @Test
    fun aSourceThatIgnoresTheRangeStartsTheFileAgain() = runTest {
        val fileSystem = fileSystem()
        fileSystem.write(Part) { writeUtf8("abc") }
        val transfer = transfer(fileSystem) {
            respond(
                content = ByteReadChannel("abcdef"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, "6"),
            )
        }

        transfer.fetch(resumeValidator = "\"v1\"")

        assertEquals("abcdef", fileSystem.read(Target) { readUtf8() })
    }

    @Test
    fun aResumeAtTheWrongOffsetIsRefusedAndTheStaleFileDropped() = runTest {
        val fileSystem = fileSystem()
        fileSystem.write(Part) { writeUtf8("abc") }
        val transfer = transfer(fileSystem) {
            respond(
                content = ByteReadChannel("abcdef"),
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentRange, "bytes 0-5/6"),
            )
        }

        assertFailsWith<DownloadTransferException> { transfer.fetch(resumeValidator = "\"v1\"") }

        // Dropped rather than kept: the next attempt has to be able to start clean.
        assertFalse(fileSystem.exists(Part))
        assertFalse(fileSystem.exists(Target))
    }

    @Test
    fun aBodyThatEndsShortIsNotPassedOffAsAFinishedDownload() = runTest {
        val fileSystem = fileSystem()
        val transfer = transfer(fileSystem) {
            respond(
                content = ByteReadChannel("abc"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, "6"),
            )
        }

        assertFailsWith<DownloadTransferException> { transfer.fetch() }

        assertFalse(fileSystem.exists(Target))
        // What did arrive is kept, so retrying resumes rather than starting over.
        assertEquals("abc", fileSystem.read(Part) { readUtf8() })
    }

    @Test
    fun aRefusedSourceExplainsItselfWithoutQuotingTheUrl() = runTest {
        val transfer = transfer(fileSystem()) {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.Forbidden)
        }

        val failure = assertFailsWith<DownloadTransferException> { transfer.fetch() }

        val message = assertNotNull(failure.message)
        assertContains(message, "expired")
        // Messages reach screens and logs, and a resolved URL carries an account token.
        assertFalse(message.contains("secret-token"))
        assertFalse(message.contains("source.test"))
    }

    @Test
    fun aSourceThatStopsSendingIsNotWaitedOnForever() = runTest {
        val transfer = transfer(fileSystem()) {
            respond(
                content = ByteChannel(autoFlush = true),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, "6"),
            )
        }

        val failure = assertFailsWith<DownloadTransferException> { transfer.fetch() }

        assertEquals(DownloadFailureCode.Network, failure.failure.code)
    }

    @Test
    fun progressCarriesTheValidatorTheNextResumeWillNeed() = runTest {
        val reports = mutableListOf<TransferProgress>()
        val transfer = transfer(fileSystem(), progressIntervalMs = 0) {
            respond(
                content = ByteReadChannel("abcdef"),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf("6"),
                    HttpHeaders.LastModified to listOf("Wed, 21 Oct 2026 07:28:00 GMT"),
                ),
            )
        }

        transfer.fetch(onProgress = { reports += it })

        assertTrue(reports.isNotEmpty())
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", reports.first().validator)
        assertEquals(6, reports.first().totalBytes)
        // The first report precedes the first byte, so a pause inside the
        // throttle window still leaves a validator to resume against.
        assertEquals(0, reports.first().downloadedBytes)
    }
}

private fun fileSystem(): FakeFileSystem = FakeFileSystem().apply {
    createDirectories("/downloads".toPath())
}

private fun transfer(
    fileSystem: FakeFileSystem,
    progressIntervalMs: Long = 500,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): HttpRangeTransfer = HttpRangeTransfer(
    http = HttpClient(MockEngine { request -> handler(request) }),
    fileSystem = fileSystem,
    clock = FakeClock(),
    progressIntervalMs = progressIntervalMs,
)

private suspend fun HttpRangeTransfer.fetch(
    resumeValidator: String? = null,
    onProgress: suspend (TransferProgress) -> Unit = {},
): TransferProgress = transfer(
    sourceUrl = SourceUrl,
    partFile = Part,
    target = Target,
    resumeValidator = resumeValidator,
    onProgress = onProgress,
)
