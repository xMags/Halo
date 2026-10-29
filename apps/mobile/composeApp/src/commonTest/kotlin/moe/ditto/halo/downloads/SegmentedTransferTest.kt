package moe.ditto.halo.downloads

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.ditto.halo.sync.FakeClock
import okio.ByteString.Companion.toByteString
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val Url = "https://source.test/movie.mkv?token=secret-token"
private val TargetFile: Path = "/downloads/movie.mkv".toPath()
private val PartFile: Path = "/downloads/movie.mkv.part".toPath()
private val SidecarFile: Path = "/downloads/movie.mkv.part.segments".toPath()

/** Small splits and fast ramps, so a file of a few hundred KiB exercises every path. */
private val TestTuning = SegmentTuning(
    minSplitBytes = 32 * 1024,
    rampIntervalMs = 5,
    retryDelaysMs = listOf(50, 50, 50),
)

/**
 * These run on [Dispatchers.Default] rather than the test scheduler: the mock
 * engine streams bodies from real threads, and virtual time would otherwise
 * fire stall timeouts and ramp ticks before a byte had a chance to arrive.
 */
class SegmentedTransferTest {

    @Test
    fun manyConnectionsAssembleExactlyTheSourcesBytes() = realTime {
        val source = FakeSource(content(700 * 1024 + 123))
        val fileSystem = downloadsFileSystem()

        val result = source.transfer(fileSystem)

        assertContentEquals(source.bytes, fileSystem.read(TargetFile) { readByteArray() })
        assertEquals(TransferProgress(source.bytes.size.toLong(), source.bytes.size.toLong(), "\"v1\""), result)
        assertTrue(source.maxActive > 1, "only ${source.maxActive} connection(s) were used")
        assertTrue(source.maxActive <= SegmentPlan.MaxConnections)
        val extras = source.seen.drop(1)
        assertTrue(extras.all { it.range?.matches(Regex("bytes=\\d+-")) == true })
        assertTrue(extras.all { it.ifRange == "\"v1\"" })
        assertFalse(fileSystem.exists(PartFile))
        assertFalse(fileSystem.exists(SidecarFile))
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun progressCountsTheConnectionsThatAreReceiving() = realTime {
        val source = FakeSource(content(700 * 1024 + 123))
        val fileSystem = downloadsFileSystem()
        // Reports arrive one at a time, under the run's own report lock.
        val reports = mutableListOf<TransferProgress>()

        val result = source.transfer(fileSystem, progressIntervalMs = 0, onProgress = { reports += it })

        assertEquals(1, reports.first().connections)
        assertTrue(reports.any { it.connections > 1 }, "every report counted one connection")
        assertTrue(reports.all { it.connections in 1..SegmentPlan.MaxConnections })
        // The finished result describes a file, not a link.
        assertEquals(0, result.connections)
    }

    @Test
    fun aSourceThatIgnoresRangesIsFinishedOverTheOneConnection() = realTime {
        val source = FakeSource(content(400 * 1024), honoursRanges = false)
        val fileSystem = downloadsFileSystem()

        source.transfer(fileSystem)

        assertContentEquals(source.bytes, fileSystem.read(TargetFile) { readByteArray() })
        // The first connection, and at most the one extra that found out.
        assertTrue(source.seen.size <= 2, "${source.seen.size} requests")
        assertFalse(fileSystem.exists(SidecarFile))
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun refusedExtraConnectionsSetACeilingAndTheDownloadStillCompletes() = realTime {
        val source = FakeSource(content(700 * 1024), maxConcurrentBodies = 3)
        val fileSystem = downloadsFileSystem()

        source.transfer(fileSystem)

        assertContentEquals(source.bytes, fileSystem.read(TargetFile) { readByteArray() })
        assertTrue(source.refusals >= 1, "the source never refused a connection")
        // Without the ceiling the ramp asks again every few milliseconds and
        // is refused every time; with it, a few refusals settle the count. A
        // few, not one: the source counts a body as open until its producer
        // notices the hang-up, slightly after the client let go of it.
        assertTrue(source.refusals <= 6, "${source.refusals} refusals")
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun aRecordResumesEachUnfinishedSegmentWhereItStopped() = realTime {
        val source = FakeSource(content(512 * 1024))
        val fileSystem = downloadsFileSystem()
        val segments = listOf(
            SidecarSegment(0, 128 * 1024, 128 * 1024),
            SidecarSegment(128 * 1024, 384 * 1024, 64 * 1024),
            SidecarSegment(384 * 1024, 512 * 1024, 0),
        )
        seedPart(fileSystem, source.bytes, segments, "\"v1\"")

        source.transfer(fileSystem, resumeValidator = "\"v1\"")

        assertContentEquals(source.bytes, fileSystem.read(TargetFile) { readByteArray() })
        val first = source.seen.first()
        // The largest unfinished stretch is asked for first, guarded by the validator.
        assertEquals("bytes=${192 * 1024}-", first.range)
        assertEquals("\"v1\"", first.ifRange)
        assertTrue(source.seen.none { it.range == null || it.range == "bytes=0-" })
        assertFalse(fileSystem.exists(SidecarFile))
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun aRecordForAnotherVersionOfTheFileStartsTheDownloadAgain() = realTime {
        val source = FakeSource(content(256 * 1024))
        val fileSystem = downloadsFileSystem()
        seedPart(fileSystem, ByteArray(source.bytes.size) { 7 }, listOf(SidecarSegment(0, 256 * 1024, 100_000)), "\"v0\"")

        source.transfer(fileSystem, resumeValidator = "\"v1\"")

        assertNull(source.seen.first().range)
        assertContentEquals(source.bytes, fileSystem.read(TargetFile) { readByteArray() })
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun aFileReplacedUnderItsRecordIsDownloadedAgainFromTheWholeBody() = realTime {
        val source = FakeSource(content(256 * 1024, seed = 2), etag = "\"v2\"")
        val fileSystem = downloadsFileSystem()
        val stale = content(256 * 1024, seed = 1)
        seedPart(fileSystem, stale, listOf(SidecarSegment(0, 128 * 1024, 64 * 1024), SidecarSegment(128 * 1024, 256 * 1024, 0)), "\"v1\"")

        val result = source.transfer(fileSystem, resumeValidator = "\"v1\"")

        // If-Range carried the old validator, so the source answered with the whole new file.
        assertEquals("\"v1\"", source.seen.first().ifRange)
        assertContentEquals(source.bytes, fileSystem.read(TargetFile) { readByteArray() })
        assertEquals("\"v2\"", result.validator)
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun connectionsThatDisagreeAboutTheSameBytesFailAndDropThePart() = realTime {
        val good = content(512 * 1024)
        val other = ByteArray(good.size) { (good[it].toInt() xor 0x5A).toByte() }
        // The first connection gets the real file; every ranged answer comes from another one.
        val source = FakeSource(good, bodyFor = { start -> if (start == 0L) good else other })
        val fileSystem = downloadsFileSystem()

        val failure = assertFailsWith<DownloadTransferException> { source.transfer(fileSystem) }

        assertEquals(DownloadFailureCode.InvalidRange, failure.failure.code)
        assertFalse(fileSystem.exists(PartFile))
        assertFalse(fileSystem.exists(SidecarFile))
        assertFalse(fileSystem.exists(TargetFile))
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun progressNeverClaimsMoreThanTheRecordOnDisk() = realTime {
        val source = FakeSource(content(700 * 1024))
        val fileSystem = downloadsFileSystem()
        val claims = mutableListOf<Pair<Long, Long?>>()

        source.transfer(fileSystem, progressIntervalMs = 0) { progress ->
            claims += progress.downloadedBytes to SegmentSidecar.read(fileSystem, PartFile)?.doneBytes
        }

        // Every report made once the record existed is backed by it.
        assertTrue(claims.any { it.second != null })
        claims.forEach { (reported, recorded) ->
            if (recorded != null) assertTrue(reported <= recorded, "reported $reported, recorded $recorded")
        }
    }
}

private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
    withContext(Dispatchers.Default) { block() }
}

private fun downloadsFileSystem(): FakeFileSystem = FakeFileSystem().apply {
    createDirectories("/downloads".toPath())
}

/** Deterministic bytes in which no 64-byte window repeats at a nearby offset. */
private fun content(size: Int, seed: Int = 1): ByteArray {
    var state = 0x2545F491 * seed
    return ByteArray(size) {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        state.toByte()
    }
}

/** A part as an earlier run would have left it: the recorded bytes real, everything else zero. */
private fun seedPart(
    fileSystem: FakeFileSystem,
    bytes: ByteArray,
    segments: List<SidecarSegment>,
    validator: String,
) {
    val part = ByteArray(bytes.size)
    segments.forEach { segment ->
        bytes.copyInto(part, segment.start.toInt(), segment.start.toInt(), (segment.start + segment.done).toInt())
    }
    fileSystem.write(PartFile) { write(part.toByteString()) }
    SegmentSidecar.write(
        fileSystem,
        PartFile,
        SegmentSidecar(
            total = bytes.size.toLong(),
            validatorSha256 = SegmentSidecar.validatorDigest(validator),
            segments = segments,
        ),
    )
}

private data class SeenRequest(val range: String?, val ifRange: String?)

/**
 * A source that streams its bytes slowly enough for several connections to
 * overlap, honours `Range` under `If-Range` the way a CDN does, and can refuse
 * connections past a limit.
 */
private class FakeSource(
    val bytes: ByteArray,
    private val etag: String = "\"v1\"",
    private val honoursRanges: Boolean = true,
    private val maxConcurrentBodies: Int = Int.MAX_VALUE,
    private val bodyFor: (start: Long) -> ByteArray = { bytes },
) {
    private val lock = Mutex()
    private val producers = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var active = 0
    var maxActive = 0
        private set
    var refusals = 0
        private set
    val seen = mutableListOf<SeenRequest>()

    suspend fun transfer(
        fileSystem: FakeFileSystem,
        resumeValidator: String? = null,
        progressIntervalMs: Long = 500,
        onProgress: suspend (TransferProgress) -> Unit = {},
    ): TransferProgress {
        val http = HttpClient(
            MockEngine { request ->
                val range = request.headers[HttpHeaders.Range]
                val ifRange = request.headers[HttpHeaders.IfRange]
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toLong()
                val refused = lock.withLock {
                    seen += SeenRequest(range, ifRange)
                    if (active >= maxConcurrentBodies) {
                        refusals += 1
                        true
                    } else {
                        false
                    }
                }
                if (refused) return@MockEngine respond(ByteReadChannel(""), HttpStatusCode.ServiceUnavailable)
                val partial = start != null && honoursRanges && (ifRange == null || ifRange == etag)
                val from = if (partial) start else 0L
                val body = bodyFor(from)
                val channel = ByteChannel(autoFlush = true)
                lock.withLock {
                    active += 1
                    maxActive = maxOf(maxActive, active)
                }
                producers.launch {
                    try {
                        var position = from.toInt()
                        while (position < body.size) {
                            val end = minOf(position + ChunkBytes, body.size)
                            channel.writeFully(body, position, end)
                            position = end
                            delay(1)
                        }
                        channel.flushAndClose()
                    } catch (_: Throwable) {
                        // The client hung up: a connection stopped at its segment's end.
                    } finally {
                        withContext(NonCancellable) { lock.withLock { active -= 1 } }
                    }
                }
                val headers = buildList {
                    add(HttpHeaders.ContentLength to listOf((bytes.size - from).toString()))
                    add(HttpHeaders.ETag to listOf(etag))
                    if (partial) add(HttpHeaders.ContentRange to listOf("bytes $from-${bytes.size - 1}/${bytes.size}"))
                }
                respond(
                    content = channel,
                    status = if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                    headers = headersOf(*headers.toTypedArray()),
                )
            },
        )
        try {
            return HttpRangeTransfer(
                http = http,
                fileSystem = fileSystem,
                clock = FakeClock(),
                progressIntervalMs = progressIntervalMs,
                tuning = TestTuning,
            ).transfer(
                sourceUrl = Url,
                partFile = PartFile,
                target = TargetFile,
                resumeValidator = resumeValidator,
                onProgress = onProgress,
            )
        } finally {
            http.close()
            producers.cancel()
        }
    }

    private companion object {
        const val ChunkBytes = 8 * 1024
    }
}
