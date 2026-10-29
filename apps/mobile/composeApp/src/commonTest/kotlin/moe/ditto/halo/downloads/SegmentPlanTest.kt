package moe.ditto.halo.downloads

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MiB = 1024L * 1024

class SegmentPlanTest {

    @Test
    fun anIdleSegmentIsServedBeforeAnyIsSplitLargestFirst() {
        val segments = listOf(
            PlanSegment(start = 0, end = 100 * MiB, position = 10 * MiB, assigned = true),
            PlanSegment(start = 100 * MiB, end = 110 * MiB, position = 100 * MiB, assigned = false),
            PlanSegment(start = 110 * MiB, end = 130 * MiB, position = 112 * MiB, assigned = false),
        )

        assertEquals(PlanStep.Resume(2), chooseWork(segments, SegmentPlan.MinSplitBytes))
    }

    @Test
    fun withNothingIdleTheBusiestSegmentIsSplitAtTheMiddleOfWhatRemains() {
        val segments = listOf(
            PlanSegment(start = 0, end = 50 * MiB, position = 40 * MiB, assigned = true),
            PlanSegment(start = 50 * MiB, end = 100 * MiB, position = 60 * MiB, assigned = true),
        )

        assertEquals(PlanStep.Split(1, 60 * MiB + 20 * MiB), chooseWork(segments, SegmentPlan.MinSplitBytes))
    }

    @Test
    fun aSegmentTooSmallToHalveGetsNoSecondConnection() {
        val segments = listOf(
            PlanSegment(start = 0, end = 4 * MiB, position = 1, assigned = true),
        )

        assertNull(chooseWork(segments, SegmentPlan.MinSplitBytes))
    }

    @Test
    fun finishedSegmentsAreNeverChosen() {
        val segments = listOf(
            PlanSegment(start = 0, end = 10, position = 10, assigned = false),
            PlanSegment(start = 10, end = 20, position = 20, assigned = true),
        )

        assertNull(chooseWork(segments, minSplitBytes = 1))
    }

    @Test
    fun theOverlapComparesOnlyTheBytesBothSidesHave() {
        val overlap = byteArrayOf(1, 2, 3, 4)

        assertTrue(overlapAgrees(overlap, 4, byteArrayOf(1, 2, 0, 0), 2))
        assertTrue(overlapAgrees(overlap, 0, byteArrayOf(9), 1))
        assertFalse(overlapAgrees(overlap, 4, byteArrayOf(1, 9, 3, 4), 4))
    }

    @Test
    fun aRefusedSegmentWaitsLongerEachTimeAndHonoursRetryAfterUpToAMinute() {
        assertEquals(1_000, retryDelayMs(attempt = 1, retryAfterSeconds = null))
        assertEquals(2_000, retryDelayMs(attempt = 2, retryAfterSeconds = null))
        assertEquals(4_000, retryDelayMs(attempt = 3, retryAfterSeconds = null))
        assertEquals(4_000, retryDelayMs(attempt = 9, retryAfterSeconds = null))
        assertEquals(7_000, retryDelayMs(attempt = 1, retryAfterSeconds = 7))
        assertEquals(60_000, retryDelayMs(attempt = 1, retryAfterSeconds = 3_600))
    }

    @Test
    fun theRampOpensOneConnectionPerIntervalUpToTheLimit() {
        val ramp = ConnectionRamp(maxConnections = 3, intervalMs = 250)
        ramp.start(nowMs = 0)

        ramp.tick(nowMs = 100)
        assertEquals(1, ramp.allowed)
        ramp.tick(nowMs = 250)
        assertEquals(2, ramp.allowed)
        // One step per interval, however late the tick.
        ramp.tick(nowMs = 2_000)
        assertEquals(3, ramp.allowed)
        ramp.tick(nowMs = 9_000)
        assertEquals(3, ramp.allowed)
    }

    @Test
    fun aRefusalSetsACeilingTheRampNeverPassesAgain() {
        val ramp = ConnectionRamp(maxConnections = 8, intervalMs = 1)
        ramp.start(nowMs = 0)
        (1..5L).forEach { ramp.tick(nowMs = it) }
        assertEquals(6, ramp.allowed)

        ramp.refused(othersLive = 4)

        assertEquals(4, ramp.ceiling)
        assertEquals(4, ramp.allowed)
        (10..20L).forEach { ramp.tick(nowMs = it) }
        assertEquals(4, ramp.allowed)
    }

    @Test
    fun untilRangesAreProvenOnlyOneExtraConnectionIsAllowed() {
        val ramp = ConnectionRamp(maxConnections = 8, intervalMs = 1)
        ramp.start(nowMs = 0)
        (1..5L).forEach { ramp.tick(nowMs = it) }

        assertEquals(2, ramp.limit(rangesProven = false))
        assertEquals(6, ramp.limit(rangesProven = true))
    }

    @Test
    fun theRecordRoundTripsAndNeverHoldsTheValidatorItself() {
        val sidecar = SegmentSidecar(
            total = 300,
            validatorSha256 = SegmentSidecar.validatorDigest("\"v1\""),
            segments = listOf(SidecarSegment(0, 100, 100), SidecarSegment(100, 300, 20)),
        )

        val text = SegmentSidecar.encode(sidecar)

        assertFalse(text.contains("v1"))
        val decoded = assertNotNull(SegmentSidecar.decode(text))
        assertEquals(sidecar, decoded)
        assertEquals(120, decoded.doneBytes)
        assertTrue(decoded.matches("\"v1\""))
        assertFalse(decoded.matches("\"v2\""))
        assertFalse(decoded.matches(null))
    }

    @Test
    fun aRecordThatDoesNotCoverTheFileExactlyIsNotTrusted() {
        val digest = SegmentSidecar.validatorDigest("\"v1\"")
        fun record(vararg segments: SidecarSegment, total: Long = 300, version: Int = 1) =
            SegmentSidecar.encode(SegmentSidecar(version, total, digest, segments.toList()))

        assertNull(SegmentSidecar.decode(record(SidecarSegment(0, 100, 0), SidecarSegment(150, 300, 0))))
        assertNull(SegmentSidecar.decode(record(SidecarSegment(0, 200, 0), SidecarSegment(100, 300, 0))))
        assertNull(SegmentSidecar.decode(record(SidecarSegment(0, 300, 301))))
        assertNull(SegmentSidecar.decode(record(SidecarSegment(0, 299, 0))))
        assertNull(SegmentSidecar.decode(record(SidecarSegment(0, 300, 0), version = 2)))
        assertNull(SegmentSidecar.decode("not json"))
    }

    @Test
    fun theRecordLivesBesideThePartAndIsReplacedWhole() {
        val fileSystem = FakeFileSystem().apply { createDirectories("/downloads".toPath()) }
        val part = "/downloads/movie.mkv.part".toPath()
        val sidecar = SegmentSidecar(
            total = 10,
            validatorSha256 = SegmentSidecar.validatorDigest("\"v1\""),
            segments = listOf(SidecarSegment(0, 10, 4)),
        )

        SegmentSidecar.write(fileSystem, part, sidecar)

        assertEquals("/downloads/movie.mkv.part.segments".toPath(), SegmentSidecar.pathFor(part))
        assertEquals(listOf("movie.mkv.part.segments"), fileSystem.list("/downloads".toPath()).map { it.name })
        assertEquals(sidecar, SegmentSidecar.read(fileSystem, part))

        SegmentSidecar.delete(fileSystem, part)

        assertTrue(fileSystem.list("/downloads".toPath()).isEmpty())
        assertNull(SegmentSidecar.read(fileSystem, part))
    }
}
