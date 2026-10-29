package moe.ditto.halo.downloads

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ditto.halo.auth.EpochClock
import okio.FileHandle
import okio.FileSystem
import okio.Path
import kotlin.time.TimeSource

/** Timings and sizes of the connection strategy. Production uses the defaults; tests shorten them. */
internal data class SegmentTuning(
    val minSplitBytes: Long = SegmentPlan.MinSplitBytes,
    val rampIntervalMs: Long = SegmentPlan.RampIntervalMs,
    val retryDelaysMs: List<Long> = SegmentPlan.RetryDelaysMs,
)

/**
 * One call of [HttpRangeTransfer.transfer]: the first GET, the ramp of extra
 * connections, splitting, overlap checks, refused connections and the segment
 * record, over one partial file.
 *
 * Each connection is a child coroutine of the run, so cancelling the run (which
 * is how a pause arrives) stops every connection and leaves the part and its
 * record resumable. The segment table is guarded by [tableLock], which is never
 * held across a network suspension; disk writes happen under it, which costs no
 * parallelism because the file handle serialises its writes anyway.
 *
 * The design is the desktop download manager's (Last Download Manager): start
 * with one connection, open one more every [SegmentTuning.rampIntervalMs] once
 * the source has proven it serves ranges, give each new connection the largest
 * idle segment or half of the largest busy one, check the bytes where two
 * connections meet, and never go past the number of connections the source
 * has shown it will take.
 */
internal class SegmentedTransferRun(
    private val http: HttpClient,
    private val fileSystem: FileSystem,
    private val clock: EpochClock,
    private val stallTimeoutMs: Long,
    private val progressIntervalMs: Long,
    private val tuning: SegmentTuning,
    private val sourceUrl: String,
    private val partFile: Path,
    private val target: Path,
    private val resumeValidator: String?,
    private val onProgress: suspend (TransferProgress) -> Unit,
) {
    private val tableLock = Mutex()
    private val reportLock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val started = TimeSource.Monotonic.markNow()
    private val ramp = ConnectionRamp(intervalMs = tuning.rampIntervalMs)

    // Guarded by tableLock once connections run.
    private val segments = mutableListOf<Segment>()
    private var total = 0L
    private var validator: String? = null
    private var accepted = false
    private var multiConnection = false
    private var rangesProven = false
    private var rangesUnsupported = false

    /** Opened when the first response is accepted, before any connection writes. */
    private var handle: FileHandle? = null

    // Guarded by reportLock.
    private var sidecarActive = false
    private var lastReportMs = 0L

    suspend fun run(): TransferProgress {
        partFile.parent?.let { fileSystem.createDirectories(it) }
        val first = firstRequest()
        return try {
            if (first is FirstRequest.FromSidecar && first.sidecar.isComplete) {
                // Every byte reached the disk last time and only the move to the
                // final name was missing; the source has nothing left to answer.
                return finish()
            }
            coroutineScope {
                launch { serve(Role.First(first)) }
                coordinate(this)
            }
            finish()
        } catch (mismatch: RangeMismatchException) {
            discardPartial()
            throw DownloadTransferException(DownloadFailure(DownloadFailureCode.InvalidRange))
        } catch (fatal: FatalRunException) {
            throw fatal.failure
        } finally {
            handle?.close()
        }
    }

    /**
     * What the first GET asks for: the whole file, the rest of a part written
     * in one piece, or one segment of a part described by its record.
     *
     * A part without a record is whole from its first byte to its length,
     * because a record is always written before any connection writes past the
     * contiguous prefix. A record that cannot be trusted, or that was written
     * for a different version of the file, leaves the part's holes unknowable,
     * so both go and the download starts again.
     */
    private fun firstRequest(): FirstRequest {
        val existing = fileSystem.metadataOrNull(partFile)?.size?.takeIf { it > 0 } ?: 0L
        if (!fileSystem.exists(SegmentSidecar.pathFor(partFile))) {
            return if (existing > 0) FirstRequest.Append(existing) else FirstRequest.Fresh
        }
        val stored = SegmentSidecar.read(fileSystem, partFile)
            ?.takeIf { existing > 0 && it.matches(resumeValidator) }
            ?.takeIf { sidecar -> sidecar.segments.all { it.done == 0L || it.start + it.done <= existing } }
        if (stored == null) {
            fileSystem.delete(partFile, mustExist = false)
            SegmentSidecar.delete(fileSystem, partFile)
            return FirstRequest.Fresh
        }
        stored.segments.forEach { segments += Segment(it.start, it.end, it.start + it.done) }
        total = stored.total
        validator = resumeValidator
        sidecarActive = true
        val step = chooseWork(segments.map { it.planView(assigned = false) }, tuning.minSplitBytes)
        val probe = (step as? PlanStep.Resume)?.let { segments[it.index] }
            ?: return FirstRequest.FromSidecar(stored, segments.first())
        return FirstRequest.FromSidecar(stored, probe)
    }

    /** One connection, from its request to its last byte. Failures that only cost this connection stay here. */
    private suspend fun serve(role: Role) {
        val job = currentCoroutineContext().job
        val connection = Connection(job, (role as? Role.Extra)?.segment)
        try {
            request(role, connection)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (mismatch: RangeMismatchException) {
            throw mismatch
        } catch (fatal: FatalRunException) {
            throw fatal
        } catch (failure: Throwable) {
            // A connection cancelled on purpose (its range taken back, or the
            // run paused) can still surface the engine's own failure, which must
            // not count as this connection's, let alone the run's.
            job.ensureActive()
            if (!requeue(connection, failure)) throw (failure as? ConnectionRefused)?.exception ?: failure
        } finally {
            withContext(NonCancellable) {
                tableLock.withLock {
                    val segment = connection.segment
                    if (segment != null && segment.connection === job) segment.connection = null
                }
            }
            wake.trySend(Unit)
        }
    }

    private suspend fun request(role: Role, connection: Connection) {
        val (rangeStart, ifRange) = when (role) {
            is Role.First -> when (val first = role.request) {
                FirstRequest.Fresh -> null to null
                is FirstRequest.Append -> first.existing to resumeValidator
                is FirstRequest.FromSidecar -> first.segment.position to resumeValidator
            }
            is Role.Extra -> role.requestStart to validator
        }
        http.prepareGet(sourceUrl) {
            if (rangeStart != null) {
                header(HttpHeaders.Range, "bytes=$rangeStart-")
                ifRange?.let { header(HttpHeaders.IfRange, it) }
            }
        }.execute { response ->
            val segment = when (role) {
                is Role.First -> acceptFirst(role.request, response, connection)
                is Role.Extra -> acceptExtra(role, response)
            } ?: return@execute
            receive(segment, response.bodyAsChannel())
        }
    }

    /**
     * Where the first response's bytes belong, and what it says about the file.
     *
     * A 200 to a ranged request means the source ignored the range, or the
     * `If-Range` guard rejected it: either way the body is the whole file and
     * the partial one is worthless. A 206 starting anywhere other than where it
     * was asked to cannot be placed, so the partial file goes and the next
     * attempt starts clean rather than writing bytes at the wrong offset.
     */
    private suspend fun acceptFirst(
        request: FirstRequest,
        response: HttpResponse,
        connection: Connection,
    ): Segment {
        val status = response.status
        if (status != HttpStatusCode.OK && status != HttpStatusCode.PartialContent) {
            throw DownloadTransferException(statusFailure(status), status.value)
        }
        val partial = status == HttpStatusCode.PartialContent
        val restart = when (request) {
            FirstRequest.Fresh -> true
            is FirstRequest.Append -> {
                if (partial && response.contentRangeStart() != request.existing) {
                    fileSystem.delete(partFile, mustExist = false)
                    throw DownloadTransferException(DownloadFailure(DownloadFailureCode.InvalidRange))
                }
                !partial
            }
            is FirstRequest.FromSidecar -> {
                val consistent = response.contentRangeStart() == request.segment.position &&
                    response.contentRangeTotal() == request.sidecar.total &&
                    response.validator().let { it == null || it == resumeValidator }
                if (partial && !consistent) throw RangeMismatchException()
                !partial
            }
        }
        val responseValidator = response.validator()
            ?: resumeValidator.takeIf { partial && !restart && request != FirstRequest.Fresh }

        val file = fileSystem.openReadWrite(partFile)
        handle = file
        val startAt = if (restart) 0L else (request as? FirstRequest.Append)?.existing ?: 0L
        if (restart) {
            // Shrinking is a truncate. Growing is not: okio's resize writes the
            // whole difference as zeros, so the part is never grown that way.
            file.resize(0)
            if (request is FirstRequest.FromSidecar) {
                // Truncated and synced before the record goes, so a crash in
                // between cannot leave a holey part that looks whole.
                file.flush()
                SegmentSidecar.delete(fileSystem, partFile)
            }
            reportLock.withLock { sidecarActive = false }
        }

        val segment = tableLock.withLock {
            val served = if (request is FirstRequest.FromSidecar && !restart) {
                request.segment
            } else {
                val declared = response.declaredTotal(startAt)
                total = declared
                segments.clear()
                Segment(0, if (declared > 0) declared else Long.MAX_VALUE, startAt).also { segments += it }
            }
            validator = responseValidator
            served.connection = connection.job
            connection.segment = served
            // A 200 to a plain GET proves nothing about ranges; a 206 at the asked offset does.
            rangesProven = partial && !restart
            multiConnection = total > 0 && responseValidator != null
            served.beginHead()
            served
        }
        reportLock.withLock {
            lastReportMs = clock.nowMs()
            // Reported before a single byte is read, because this is what carries
            // the validator: a transfer paused inside the throttle window would
            // otherwise have nothing to guard its own resume with.
            val done = tableLock.withLock { doneBytesLocked() }
            onProgress(TransferProgress(done, total, validator))
        }
        // Only now may extra connections open, so none can read a byte before
        // the validator has been reported.
        tableLock.withLock {
            accepted = true
            ramp.start(nowMs())
        }
        wake.trySend(Unit)
        return segment
    }

    /**
     * An extra connection's answer. A 206 must be the asked offset of the same
     * file; a 200 before any 206 means the source ignores ranges, and the
     * segment goes back to the connection already reading through it.
     */
    private suspend fun acceptExtra(role: Role.Extra, response: HttpResponse): Segment? {
        val segment = role.segment
        val status = response.status
        if (status == HttpStatusCode.PartialContent) {
            val consistent = response.contentRangeStart() == role.requestStart &&
                response.contentRangeTotal() == total &&
                response.validator().let { it == null || it == validator }
            if (!consistent) throw RangeMismatchException()
            return tableLock.withLock {
                if (segments.none { it === segment }) return@withLock null
                rangesProven = true
                segment.beginHead()
                segment
            }
        }
        if (status == HttpStatusCode.OK) {
            val absorbed = tableLock.withLock {
                if (segments.none { it === segment }) return@withLock true
                if (rangesProven) return@withLock false
                val previous = segments.firstOrNull { it.end == segment.start } ?: return@withLock false
                previous.end = segment.end
                segments.remove(segment)
                rangesUnsupported = true
                true
            }
            if (absorbed) return null
            // Ranges were already served in this run, so this is one server
            // that does not, or a file that just changed. Either way it costs
            // only this connection; the next run's first request finds out which.
            throw ConnectionRefused(DownloadTransferException(DownloadFailure(DownloadFailureCode.Network)), null)
        }
        throw ConnectionRefused(
            DownloadTransferException(statusFailure(status), status.value),
            response.retryAfterSeconds(),
        )
    }

    /** Streams one response into its segment, then compares the bytes past its end with the next segment's. */
    private suspend fun receive(segment: Segment, channel: ByteReadChannel) {
        val buffer = ByteArray(HttpRangeTransfer.BufferSize)
        var overlap: ByteArray? = null
        var overlapLength = 0
        while (true) {
            val read = withTimeoutOrNull(stallTimeoutMs) {
                channel.readAvailable(buffer, 0, buffer.size)
            }
            if (read == null) {
                // A segment already whole only waited for bytes to compare.
                if (overlap != null) break
                throw DownloadTransferException(DownloadFailure(DownloadFailureCode.Network))
            }
            if (read < 0) {
                if (overlap == null) endOfBody(segment)
                break
            }
            if (read == 0) continue
            if (overlap != null) {
                val take = minOf(overlap.size - overlapLength, read)
                buffer.copyInto(overlap, overlapLength, 0, take)
                overlapLength += take
                if (overlapLength >= overlap.size) break
                continue
            }
            val taken = tableLock.withLock { takeLocked(segment, buffer, read) } ?: return
            maybeReport()
            if (!taken.reachedEnd) continue
            if (taken.end >= endOfFile()) {
                // The last segment reads on to the end of the body: a source
                // sending more than it declared is not a finished download.
                if (taken.inRange < read) throw DownloadTransferException(DownloadFailure(DownloadFailureCode.Network))
                continue
            }
            val wanted = minOf(SegmentPlan.OverlapBytes.toLong(), endOfFile() - taken.end).toInt()
            overlap = ByteArray(wanted)
            val take = minOf(wanted, read - taken.inRange)
            buffer.copyInto(overlap, 0, taken.inRange, taken.inRange + take)
            overlapLength = take
            if (overlapLength >= wanted) break
        }
        if (overlap != null && overlapLength > 0) {
            val seen = overlap.copyOf(overlapLength)
            tableLock.withLock { compareWithNextLocked(segment, seen) }
        }
    }

    /**
     * Writes the part of one chunk that belongs to [segment]. Null when the
     * segment no longer exists, which happens only to a connection whose
     * segment was handed back to the one before it.
     */
    private fun takeLocked(segment: Segment, buffer: ByteArray, read: Int): Taken? {
        if (segments.none { it === segment }) return null
        var inRange = minOf(segment.end - segment.position, read.toLong()).toInt()
        if (inRange < read && segment.end < endOfFile() && !rangesProven) {
            // Before any 206 this connection does not stop at a split point: a
            // source that ignores ranges must still complete over it, so it
            // takes the next segment back and reads on.
            absorbNextLocked(segment)
            inRange = minOf(segment.end - segment.position, read.toLong()).toInt()
        }
        if (inRange > 0) {
            segment.recordHead(buffer, inRange)
            write(segment.position, buffer, inRange)
            segment.position += inRange
            segment.attempts = 0
        }
        return Taken(inRange = inRange, reachedEnd = segment.position >= segment.end, end = segment.end)
    }

    private fun absorbNextLocked(segment: Segment) {
        val next = segments.firstOrNull { it.start == segment.end } ?: return
        segment.end = next.end
        next.connection?.cancel()
        segments.remove(next)
    }

    private fun write(offset: Long, buffer: ByteArray, count: Int) {
        val file = checkNotNull(handle)
        try {
            file.write(offset, buffer, 0, count)
        } catch (failure: Throwable) {
            // A disk that will not take bytes is the run's failure, never one
            // connection's: retrying the range elsewhere would fail the same way.
            throw FatalRunException(failure)
        }
    }

    private suspend fun endOfBody(segment: Segment) {
        tableLock.withLock {
            if (segments.none { it === segment }) return
            if (segment.end == Long.MAX_VALUE) {
                // A source that never declared a size ends when it closes.
                segment.end = segment.position
                total = segment.position
                return
            }
            if (segment.position < segment.end) {
                throw DownloadTransferException(DownloadFailure(DownloadFailureCode.Network))
            }
        }
    }

    private fun compareWithNextLocked(segment: Segment, overlap: ByteArray) {
        val next = segments.firstOrNull { it.start == segment.end } ?: return
        if (!next.headFromStart) return
        next.overlapFromPrevious = overlap
        next.checkOverlap()
    }

    /**
     * Keeps a failed connection's segment for later when other connections are
     * still receiving: a source that refuses one more connection is limiting
     * connections, not saying anything about the file. The only connection
     * failing is the source's answer, and the run fails as it always has.
     */
    private suspend fun requeue(connection: Connection, failure: Throwable): Boolean {
        if (!failure.costsOnlyThisConnection()) return false
        return tableLock.withLock {
            val segment = connection.segment ?: return@withLock false
            // Taken back by the connection before it: nothing is left to retry.
            if (segments.none { it === segment }) return@withLock true
            val othersLive = segments.count { it !== segment && it.connection != null }
            if (othersLive == 0) return@withLock false
            // Without a proven range, bytes already taken cannot be continued from an offset.
            if (!rangesProven && segment.position > segment.start) return@withLock false
            segment.connection = null
            segment.attempts += 1
            val retryAfter = (failure as? ConnectionRefused)?.retryAfterSeconds
            segment.retryAtMs = nowMs() + retryDelayMs(segment.attempts, retryAfter, tuning.retryDelaysMs)
            ramp.refused(othersLive)
            true
        }
    }

    private fun Throwable.costsOnlyThisConnection(): Boolean = when (this) {
        is ConnectionRefused -> exception.failure.isTransient ||
            exception.httpStatus == HttpStatusCode.Forbidden.value ||
            exception.httpStatus == HttpStatusCode.NotFound.value
        is DownloadTransferException -> failure.isTransient
        // Socket and engine failures, which a worker attempt reports as Network.
        else -> true
    }

    private val DownloadFailure.isTransient: Boolean
        get() = code == DownloadFailureCode.Network || code == DownloadFailureCode.ServerUnavailable

    /** Opens connections as the ramp allows, until every segment is whole and every connection gone. */
    private suspend fun coordinate(scope: CoroutineScope) {
        while (true) {
            val launches = tableLock.withLock {
                if (accepted && segments.none { it.connection != null } && segments.all { it.isReceived }) {
                    return
                }
                planLocked(scope)
            }
            if (launches.isNotEmpty()) {
                recordBeforeSpreading()
                launches.forEach { it.start() }
            }
            withTimeoutOrNull(tuning.rampIntervalMs) { wake.receive() }
        }
    }

    private fun planLocked(scope: CoroutineScope): List<Job> {
        if (!accepted || !multiConnection || rangesUnsupported) return emptyList()
        val now = nowMs()
        ramp.tick(now)
        val limit = ramp.limit(rangesProven)
        val launches = mutableListOf<Job>()
        while (segments.count { it.connection != null } < limit) {
            val plan = segments.map { segment ->
                segment.planView(assigned = segment.connection != null || (segment.retryAtMs ?: 0L) > now)
            }
            val step = chooseWork(plan, tuning.minSplitBytes) ?: break
            val segment = when (step) {
                is PlanStep.Resume -> segments[step.index]
                is PlanStep.Split -> {
                    val busy = segments[step.index]
                    val added = Segment(step.splitAt, busy.end, step.splitAt)
                    busy.end = step.splitAt
                    segments.add(step.index + 1, added)
                    added
                }
            }
            segment.retryAtMs = null
            val role = Role.Extra(segment, segment.position)
            val job = scope.launch(start = CoroutineStart.LAZY) { serve(role) }
            segment.connection = job
            launches += job
        }
        return launches
    }

    /**
     * The first record is written before the first extra connection writes a
     * byte. From then on the part has holes, and a part without a record is
     * read as whole up to its length.
     */
    private suspend fun recordBeforeSpreading() {
        reportLock.withLock {
            if (sidecarActive) return
            val sidecar = tableLock.withLock { sidecarLocked() }
            checkNotNull(handle).flush()
            writeSidecar(sidecar)
            sidecarActive = true
        }
    }

    /**
     * Publishes progress at most every [progressIntervalMs], from whichever
     * connection gets there first. The part is flushed before the record and
     * the number are written, so neither claims bytes the disk does not have.
     */
    private suspend fun maybeReport() {
        if (!reportLock.tryLock()) return
        try {
            val now = clock.nowMs()
            if (now - lastReportMs < progressIntervalMs) return
            lastReportMs = now
            val (done, sidecar) = tableLock.withLock {
                doneBytesLocked() to sidecarLocked().takeIf { sidecarActive }
            }
            if (sidecar != null) {
                checkNotNull(handle).flush()
                writeSidecar(sidecar)
            }
            onProgress(TransferProgress(done, total, validator))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // The record, the disk or the caller failing is never one connection's problem.
            throw FatalRunException(failure)
        } finally {
            reportLock.unlock()
        }
    }

    /**
     * Under the table lock with the part's writes, so every change to the
     * directory is serialised; the flush before it stays outside, being the
     * slow half.
     */
    private suspend fun writeSidecar(sidecar: SegmentSidecar) {
        tableLock.withLock { SegmentSidecar.write(fileSystem, partFile, sidecar) }
    }

    private fun finish(): TransferProgress {
        val size = if (total > 0) total else segments.singleOrNull()?.position ?: 0L
        handle?.let { file ->
            // Synced before the record goes: without it, the part would be read
            // as whole up to its length even if the last writes never landed.
            if (sidecarActive) file.flush()
            file.close()
        }
        val written = fileSystem.metadataOrNull(partFile)?.size ?: 0L
        if (written != size) throw DownloadTransferException(DownloadFailure(DownloadFailureCode.Network))
        SegmentSidecar.delete(fileSystem, partFile)
        fileSystem.atomicMove(partFile, target)
        return TransferProgress(downloadedBytes = size, totalBytes = size, validator = validator ?: resumeValidator)
    }

    /** The part and its record go together: neither is any use without the other. */
    private fun discardPartial() {
        handle?.close()
        fileSystem.delete(partFile, mustExist = false)
        SegmentSidecar.delete(fileSystem, partFile)
    }

    private fun doneBytesLocked(): Long = segments.sumOf { it.position - it.start }

    private fun sidecarLocked(): SegmentSidecar = SegmentSidecar(
        total = total,
        validatorSha256 = SegmentSidecar.validatorDigest(checkNotNull(validator)),
        segments = segments.map { SidecarSegment(it.start, it.end, it.position - it.start) },
    )

    /** Read outside the lock too: [total] changes only while a single connection runs. */
    private fun endOfFile(): Long = if (total > 0) total else Long.MAX_VALUE

    private fun nowMs(): Long = started.elapsedNow().inWholeMilliseconds

    private sealed interface FirstRequest {
        data object Fresh : FirstRequest
        data class Append(val existing: Long) : FirstRequest
        data class FromSidecar(val sidecar: SegmentSidecar, val segment: Segment) : FirstRequest
    }

    private sealed interface Role {
        data class First(val request: FirstRequest) : Role
        data class Extra(val segment: Segment, val requestStart: Long) : Role
    }

    /** What one connection serves; set for the first connection once its response has said where. */
    private class Connection(val job: Job, var segment: Segment?)

    private class Taken(val inRange: Int, val reachedEnd: Boolean, val end: Long)

    /** One byte range of the file. Guarded by the run's table lock. */
    private class Segment(val start: Long, var end: Long, var position: Long) {
        var connection: Job? = null
        var retryAtMs: Long? = null
        var attempts = 0

        /** First bytes of the segment as its own connection received them, for the overlap check. */
        val head = ByteArray(SegmentPlan.OverlapBytes)
        var headLength = 0
        var headFromStart = false

        /** Bytes the previous segment's connection read past its end, until they are verified. */
        var overlapFromPrevious: ByteArray? = null

        val isReceived: Boolean get() = position >= end

        fun planView(assigned: Boolean) = PlanSegment(start, end, position, assigned)

        fun beginHead() {
            if (position != start) return
            headLength = 0
            headFromStart = true
        }

        fun recordHead(buffer: ByteArray, count: Int) {
            if (!headFromStart) return
            val into = position - start
            if (into >= head.size || into != headLength.toLong()) return
            val take = minOf(head.size - headLength, count)
            buffer.copyInto(head, headLength, 0, take)
            headLength += take
            checkOverlap()
        }

        /** Two connections that disagree about the same bytes mean the file is not one file. */
        fun checkOverlap() {
            val pending = overlapFromPrevious ?: return
            if (!overlapAgrees(pending, pending.size, head, headLength)) throw RangeMismatchException()
            if (headLength >= pending.size) overlapFromPrevious = null
        }
    }
}

/** Two responses disagreed about the file: the part cannot be trusted and goes. */
private class RangeMismatchException : Exception("The source served inconsistent ranges")

/** A failure that ends the whole run whichever connection met it. */
private class FatalRunException(val failure: Throwable) : Exception(failure)

/** An extra connection the source turned away, with how long it asked to wait. */
private class ConnectionRefused(
    val exception: DownloadTransferException,
    val retryAfterSeconds: Long?,
) : Exception(exception.message)
