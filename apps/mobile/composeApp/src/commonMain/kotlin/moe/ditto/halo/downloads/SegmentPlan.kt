package moe.ditto.halo.downloads

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The pure half of a segmented download: which byte ranges exist, which one a
 * newly opened connection should serve, how many connections a source has
 * shown it will take, and the record that lets a later run resume them.
 *
 * Nothing here touches the network. The transfer (see [HttpRangeTransfer])
 * holds one of these tables behind a lock and acts on the answers.
 */
internal object SegmentPlan {
    /** Connections one download may use at once. One active download at a time stays the rule. */
    const val MaxConnections = 8

    /**
     * A busy segment is split only when at least this much of it remains, so
     * both halves stay worth a request. Larger than the desktop download
     * manager's 1 MiB: video files are big, and every connection is one more
     * request against a debrid account.
     */
    const val MinSplitBytes = 4L * 1024 * 1024

    /** Bytes compared where two connections meet, to prove they served the same file. */
    const val OverlapBytes = 64

    /** How often one more connection may open while the source keeps accepting them. */
    const val RampIntervalMs = 250L

    /** Before ranges are proven only one extra connection is in flight: the one that proves them. */
    const val UnprovenConnectionLimit = 2

    /** Retry delays for a refused connection's segment, by consecutive attempt. */
    val RetryDelaysMs: List<Long> = listOf(1_000L, 2_000L, 4_000L)

    /** A source's own `Retry-After` is honoured up to this long. */
    const val MaxRetryAfterSeconds = 60L
}

/** The planner's view of one segment. [end] is exclusive; [position] is the next byte to receive. */
internal data class PlanSegment(
    val start: Long,
    val end: Long,
    val position: Long,
    /** A connection serves it, or it is waiting out a retry delay. */
    val assigned: Boolean,
) {
    val remaining: Long get() = (end - position).coerceAtLeast(0)
}

/** Work for one newly available connection. */
internal sealed interface PlanStep {
    /** Serve the unassigned segment at [index] from its position. */
    data class Resume(val index: Int) : PlanStep

    /** Shorten the segment at [index] to end at [splitAt]; the new connection takes the rest. */
    data class Split(val index: Int, val splitAt: Long) : PlanStep
}

/**
 * Chooses work for one newly available connection:
 * 1. an unassigned, incomplete segment, largest remaining first;
 * 2. otherwise split the assigned segment with the most remaining at the
 *    midpoint of what remains, if that is at least [minSplitBytes];
 * 3. otherwise nothing, because another connection would not help.
 */
internal fun chooseWork(segments: List<PlanSegment>, minSplitBytes: Long): PlanStep? {
    var bestIdle = -1
    var bestBusy = -1
    segments.forEachIndexed { index, segment ->
        if (segment.remaining == 0L) return@forEachIndexed
        if (segment.assigned) {
            if (bestBusy < 0 || segment.remaining > segments[bestBusy].remaining) bestBusy = index
        } else {
            if (bestIdle < 0 || segment.remaining > segments[bestIdle].remaining) bestIdle = index
        }
    }
    if (bestIdle >= 0) return PlanStep.Resume(bestIdle)
    if (bestBusy < 0) return null
    val busy = segments[bestBusy]
    if (busy.remaining < minSplitBytes) return null
    return PlanStep.Split(bestBusy, busy.position + busy.remaining / 2)
}

/**
 * Whether the bytes one connection read past its segment's end agree with the
 * first bytes the next segment's own connection received. Only the length both
 * sides have is compared; the rest is checked once the shorter side catches up.
 */
internal fun overlapAgrees(overlap: ByteArray, overlapLength: Int, head: ByteArray, headLength: Int): Boolean {
    val compared = minOf(overlapLength, headLength)
    for (i in 0 until compared) {
        if (overlap[i] != head[i]) return false
    }
    return true
}

/**
 * How long a refused connection's segment waits before it is asked for again:
 * the source's `Retry-After` when it sent one (capped), else 1, 2, then 4 s.
 */
internal fun retryDelayMs(
    attempt: Int,
    retryAfterSeconds: Long?,
    delaysMs: List<Long> = SegmentPlan.RetryDelaysMs,
): Long {
    if (retryAfterSeconds != null && retryAfterSeconds > 0) {
        return retryAfterSeconds.coerceAtMost(SegmentPlan.MaxRetryAfterSeconds) * 1_000
    }
    return delaysMs[(attempt - 1).coerceIn(0, delaysMs.lastIndex)]
}

/**
 * How many connections may be open, ramped one at a time.
 *
 * The ceiling is what a source has shown it will take: an extra connection
 * refused while others still receive says the source limits connections, not
 * that the file is gone, so the run keeps to the connections it has and never
 * ramps past that number again. Not thread-safe; the transfer guards it.
 */
internal class ConnectionRamp(
    private val maxConnections: Int = SegmentPlan.MaxConnections,
    private val intervalMs: Long = SegmentPlan.RampIntervalMs,
) {
    var allowed: Int = 1
        private set
    var ceiling: Int = maxConnections
        private set
    private var nextRampAtMs = Long.MAX_VALUE

    /** The first response was accepted: one connection now, the next after one interval. */
    fun start(nowMs: Long) {
        allowed = 1
        nextRampAtMs = nowMs + intervalMs
    }

    fun tick(nowMs: Long) {
        if (allowed >= ceiling || nowMs < nextRampAtMs) return
        allowed += 1
        nextRampAtMs = nowMs + intervalMs
    }

    /** A connection was refused while [othersLive] others were still open. */
    fun refused(othersLive: Int) {
        ceiling = minOf(ceiling, othersLive.coerceAtLeast(1))
        allowed = minOf(allowed, ceiling)
    }

    fun limit(rangesProven: Boolean): Int =
        if (rangesProven) allowed else minOf(allowed, SegmentPlan.UnprovenConnectionLimit)
}

/** One segment as the sidecar records it: [start, end) of which the first [done] bytes are on disk. */
@Serializable
internal data class SidecarSegment(val start: Long, val end: Long, val done: Long)

/**
 * The segment table of a partial file, kept beside it as `<part>.segments`.
 *
 * A part written by several connections has holes, so its length no longer
 * says how much of it is real; this record does. It is written only after the
 * part has been flushed, so it never claims bytes that are not on disk, and it
 * exists before any connection writes past the contiguous prefix, so a part
 * without one is always whole from its first byte to its length.
 *
 * The validator is stored as a SHA-256 digest: the HTTP validator itself lives
 * only in the protected request record, never in a plain file.
 */
@Serializable
internal data class SegmentSidecar(
    val version: Int = CurrentVersion,
    val total: Long,
    val validatorSha256: String,
    val segments: List<SidecarSegment>,
) {
    val doneBytes: Long get() = segments.sumOf { it.done }

    val isComplete: Boolean get() = segments.all { it.done == it.end - it.start }

    fun matches(validator: String?): Boolean =
        validator != null && validatorSha256 == validatorDigest(validator)

    /** A table that covers [0, total) without gaps or overlaps, every count within its segment. */
    fun isWellFormed(): Boolean {
        if (version != CurrentVersion || total <= 0 || segments.isEmpty()) return false
        var expectedStart = 0L
        for (segment in segments) {
            if (segment.start != expectedStart || segment.end <= segment.start) return false
            if (segment.done < 0 || segment.done > segment.end - segment.start) return false
            expectedStart = segment.end
        }
        return expectedStart == total
    }

    companion object {
        const val CurrentVersion = 1
        private const val Suffix = ".segments"
        private const val TempSuffix = ".segments.tmp"

        private val json = Json { encodeDefaults = true }

        fun fileNameFor(partFileName: String): String = partFileName + Suffix

        fun tempFileNameFor(partFileName: String): String = partFileName + TempSuffix

        fun pathFor(partFile: Path): Path = sibling(partFile, fileNameFor(partFile.name))

        fun validatorDigest(validator: String): String = validator.encodeUtf8().sha256().hex()

        /** The sidecar beside [partFile], or null when there is none or it cannot be trusted. */
        fun read(fileSystem: FileSystem, partFile: Path): SegmentSidecar? {
            val text = try {
                fileSystem.read(pathFor(partFile)) { readUtf8() }
            } catch (_: okio.IOException) {
                return null
            }
            return decode(text)
        }

        fun decode(text: String): SegmentSidecar? {
            val decoded = try {
                json.decodeFromString(serializer(), text)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            return decoded.takeIf { it.isWellFormed() }
        }

        fun encode(sidecar: SegmentSidecar): String = json.encodeToString(serializer(), sidecar)

        /** Written beside the part and moved into place, so a reader sees the old record or the new one. */
        fun write(fileSystem: FileSystem, partFile: Path, sidecar: SegmentSidecar) {
            val temp = sibling(partFile, tempFileNameFor(partFile.name))
            fileSystem.write(temp) { writeUtf8(encode(sidecar)) }
            fileSystem.atomicMove(temp, pathFor(partFile))
        }

        fun delete(fileSystem: FileSystem, partFile: Path) {
            fileSystem.delete(pathFor(partFile), mustExist = false)
            fileSystem.delete(sibling(partFile, tempFileNameFor(partFile.name)), mustExist = false)
        }

        private fun sibling(partFile: Path, name: String): Path =
            partFile.parent?.let { it / name } ?: name.toPath()
    }
}
