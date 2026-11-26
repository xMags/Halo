package moe.ditto.halo.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.Stream
import moe.ditto.halo.browse.resolveSources
import moe.ditto.halo.browse.SourcesResolve
import moe.ditto.halo.player.VideoFormat

class TvDolbyVisionTest {

    private fun stream(id: String, title: String) = Stream(url = "https://cdn.test/$id", title = title)

    private fun resolve(vararg streams: Stream): SourcesResolve = resolveSources(
        groups = listOf(AddonStreams(AddonSource("a", "Addon A"), streams.toList())),
        errors = emptyList(),
        runtimeSeconds = 0.0,
    )

    private val answer = resolve(
        stream("dv", "Show.S01E01.2160p.DV.HEVC [RD+]"),
        stream("hybrid", "Show.S01E01.2160p.DV.HDR10.HEVC [RD+]"),
        stream("hdr", "Show.S01E01.2160p.HDR.HEVC [RD+]"),
        stream("fhd", "Show.S01E01.1080p.x264 [RD+]"),
    )

    private fun ids(resolve: SourcesResolve) = resolve.pool.map { it.stream.url!!.substringAfterLast('/') }

    @Test
    fun onlyAnObservedUnprocessedPictureIsWrong() {
        assertTrue(dolbyVisionShownWrongly(VideoFormat(dolbyVisionProfile = 5, dolbyVisionProcessed = false)))
        // The engine processed it: the file is fine.
        assertFalse(dolbyVisionShownWrongly(VideoFormat(dolbyVisionProfile = 5, dolbyVisionProcessed = true)))
        // Not judged yet.
        assertFalse(dolbyVisionShownWrongly(VideoFormat(dolbyVisionProfile = 5)))
        // A profile with a base layer never needs it.
        assertFalse(dolbyVisionShownWrongly(VideoFormat(dolbyVisionProfile = 8, dolbyVisionProcessed = false)))
        assertFalse(dolbyVisionShownWrongly(VideoFormat()))
    }

    @Test
    fun onlyANameWithDolbyVisionAloneCounts() {
        assertTrue(statesDolbyVisionOnly(stream("a", "Show.2160p.DV.HEVC")))
        assertTrue(statesDolbyVisionOnly(stream("a", "Show 2160p Dolby Vision WEB-DL")))
        assertTrue(statesDolbyVisionOnly(stream("a", "Show.2160p.DoVi.mkv")))
        assertFalse(statesDolbyVisionOnly(stream("a", "Show.2160p.DV.HDR10.HEVC")))
        assertFalse(statesDolbyVisionOnly(stream("a", "Show.2160p.DoVi.HDR.mkv")))
        assertFalse(statesDolbyVisionOnly(stream("a", "Show.2160p.HDR.HEVC")))
        // Lower-case "dv" is not claimed, as on the sources page.
        assertFalse(statesDolbyVisionOnly(stream("a", "Show.2160p.dvgroup.HEVC")))
    }

    private fun adjust(
        resolve: SourcesResolve = answer,
        learned: Boolean = true,
        usable: Boolean = false,
        wrong: (String) -> Boolean = { false },
        failed: (String) -> Boolean = { false },
    ) = tvPlayableSources(resolve, learned, usable, wrong, failed)

    @Test
    fun aDeviceThatLearnedNothingListsSourcesAsBefore() {
        val adjusted = adjust(learned = false, wrong = { true }, failed = { true })
        assertSame(answer, adjusted.resolve)
        assertEquals(0, adjusted.leftOut)
        assertEquals(0, adjusted.movedDown)
    }

    @Test
    fun withoutTheSecondEngineALearnedDeviceLeavesOutWhatItSawAndMovesTheNameDown() {
        val before = ids(answer)
        val adjusted = adjust(wrong = { it.endsWith("/dv") })
        // The shared order is kept among the rest.
        assertEquals(before - "dv", ids(adjusted.resolve))
        assertEquals(1, adjusted.leftOut)
        assertEquals(answer.unplayableCount + 1, adjusted.resolve.unplayableCount)
        assertEquals(listOf(0, 1, 2), adjusted.resolve.pool.map { it.rank })
        assertEquals("3 sources · 1 addon", adjusted.resolve.summary)
    }

    @Test
    fun withoutTheSecondEngineAnUnopenedDolbyVisionNameMovesBelowTheRest() {
        val adjusted = adjust()
        assertEquals(ids(answer) - "dv" + "dv", ids(adjusted.resolve))
        assertEquals(1, adjusted.movedDown)
        assertEquals(0, adjusted.leftOut)
        assertEquals(4, adjusted.resolve.pool.size)
        assertEquals(0, adjusted.resolve.pool.first().rank)
    }

    @Test
    fun whereTheSecondEnginePlaysThemNothingIsHiddenOrMoved() {
        val adjusted = adjust(usable = true, wrong = { it.endsWith("/dv") })
        assertSame(answer, adjusted.resolve)
        assertEquals(0, adjusted.leftOut)
        assertEquals(0, adjusted.movedDown)
    }

    @Test
    fun whereTheSecondEnginePlaysThemOnlyAFileThatFailedInBothIsLeftOut() {
        val adjusted = adjust(usable = true, wrong = { true }, failed = { it.endsWith("/dv") })
        assertEquals(ids(answer) - "dv", ids(adjusted.resolve))
        assertEquals(1, adjusted.leftOut)
        assertEquals(0, adjusted.movedDown)
    }

    @Test
    fun theOnlySourceIsKeptEvenWhenItIsKnownWrong() {
        val only = resolve(stream("dv", "Show.2160p.DV.HEVC [RD+]"))
        val adjusted = adjust(resolve = only, wrong = { true })
        assertEquals(listOf("dv"), ids(adjusted.resolve))
        assertEquals(0, adjusted.leftOut)
        assertEquals(0, adjusted.movedDown)
        assertEquals(listOf("dv"), ids(adjust(resolve = only, usable = true, failed = { true }).resolve))
    }

    private val print = sourceFingerprint("https://cdn.test/dv")
    private val other = sourceFingerprint("https://cdn.test/other")

    @Test
    fun theRecordRoundTripsAndMalformedOnesAreIgnored() {
        assertEquals(64, print.length)
        assertEquals(print, sourceFingerprint("https://cdn.test/dv"))
        val record = DolbyVisionRecord("app:1;hardware:true", 5L, mapOf(print to 5, other to 0), SecondEngineState.Works, setOf(other))
        assertEquals(record, decodeDolbyVisionRecord(encodeDolbyVisionRecord(record)))
        listOf(
            null,
            "",
            "v1\napp:1;hardware:true\n5",
            "v3\napp:1;hardware:true\n5\nengine works\nwrong $print 5",
            "v1\n \n5\n$print",
            "v1\napp:1;hardware:true\nsoon\n$print",
            "v1\napp:1;hardware:true\n5\nnot-a-fingerprint",
            "v2\napp:1;hardware:true\n5\nengine works",
            "v2\napp:1;hardware:true\n5\nengine maybe\nwrong $print 5",
            "v2\napp:1;hardware:true\n5\nengine works\nwrong $print five",
            "v2\napp:1;hardware:true\n5\nengine works\nwrong $print 5\nnoise",
        ).forEach { assertNull(decodeDolbyVisionRecord(it), "accepted: $it") }
    }

    @Test
    fun aFirstRecordReadsAsSourcesMpvShowedWronglyWithTheSecondEngineUntried() {
        // As the first version wrote it: version, setup, time, fingerprints.
        val record = decodeDolbyVisionRecord("v1\napp:1;hardware:true\n5\n$print\n$other")
        assertEquals(DolbyVisionRecord("app:1;hardware:true", 5L, mapOf(print to 5, other to 5), SecondEngineState.Untried), record)
        // Written back in the new form.
        assertTrue(encodeDolbyVisionRecord(record!!).startsWith("v2\n"))
    }

    @Test
    fun aRecordHoldsOnlyForTheSameSetupAndNotForever() {
        val record = DolbyVisionRecord("app:1;hardware:true", 1_000L, mapOf(sourceFingerprint("u") to 5))
        assertEquals(record, liveDolbyVisionRecord(record, "app:1;hardware:true", 2_000L))
        // An app update or software decoding may process it: learn again.
        assertNull(liveDolbyVisionRecord(record, "app:2;hardware:true", 2_000L))
        assertNull(liveDolbyVisionRecord(record, "app:1;hardware:false", 2_000L))
        assertEquals(record, liveDolbyVisionRecord(record, "app:1;hardware:true", 1_000L + DolbyVisionMemoryMs - 1))
        assertNull(liveDolbyVisionRecord(record, "app:1;hardware:true", 1_000L + DolbyVisionMemoryMs))
        assertNull(liveDolbyVisionRecord(record, "app:1;hardware:true", 500L))
        assertNull(liveDolbyVisionRecord(null, "app:1;hardware:true", 2_000L))
    }

    @Test
    fun onlyARememberedSourceStartsInTheSecondEngine() {
        val record = DolbyVisionRecord("s", 1L, mapOf(print to 5), SecondEngineState.Works, failed = setOf(other))
        assertEquals(PlaybackEngine.Media3, engineForSource(print, record) { true })
        // A device that learned nothing, an unknown file, no decoder, or a file that failed there: mpv.
        assertEquals(PlaybackEngine.Mpv, engineForSource(print, null) { true })
        assertEquals(PlaybackEngine.Mpv, engineForSource(sourceFingerprint("new"), record) { true })
        assertEquals(PlaybackEngine.Mpv, engineForSource(print, record) { false })
        val failedToo = record.copy(wrong = mapOf(print to 5, other to 5))
        assertEquals(PlaybackEngine.Mpv, engineForSource(other, failedToo) { true })
        // The profile asked about is the one remembered.
        var asked: Int? = null
        engineForSource(print, record.copy(wrong = mapOf(print to 3))) { profile -> asked = profile; true }
        assertEquals(3, asked)
    }

    @Test
    fun theSecondEngineIsUsableOnlyWithADecoderAndWhileItHasNotFailed() {
        assertTrue(secondEngineUsable(SecondEngineState.Untried, decoderTakes = true))
        assertTrue(secondEngineUsable(SecondEngineState.Works, decoderTakes = true))
        assertFalse(secondEngineUsable(SecondEngineState.Fails, decoderTakes = true))
        assertFalse(secondEngineUsable(SecondEngineState.Works, decoderTakes = false))
    }

    @Test
    fun aDecoderCountsForTheProfilesItStates() {
        assertEquals(0x20, dolbyVisionProfileFlag(5))
        assertEquals(0x1, dolbyVisionProfileFlag(0))
        assertEquals(0x100, dolbyVisionProfileFlag(8))
        assertNull(dolbyVisionProfileFlag(12))
        val dvhe = listOf(DolbyVisionDecoder("dvhe", setOf(0x20, 0x100)))
        assertTrue(decodesDolbyVisionProfile(dvhe, 5))
        assertFalse(decodesDolbyVisionProfile(dvhe, 3))
        assertFalse(decodesDolbyVisionProfile(emptyList(), 5))
        // One that states nothing is tried; a failed try is caught.
        assertTrue(decodesDolbyVisionProfile(listOf(DolbyVisionDecoder("quiet", emptySet())), 5))
    }

    private fun format(profile: Int?, processed: Boolean?) = VideoFormat(dolbyVisionProfile = profile, dolbyVisionProcessed = processed)

    @Test
    fun aDeviceThatShowedTheSecondEngineWorksHandsOverBeforeMpvsVerdict() {
        assertEquals(DolbyVisionStep.HandOver, dolbyVisionStep(format(5, null), PlaybackEngine.Mpv, secondEngineForFile = true, secondEngineWorks = true))
    }

    @Test
    fun anUntriedSecondEngineIsTriedAfterMpvsVerdict() {
        assertEquals(DolbyVisionStep.Wait, dolbyVisionStep(format(5, null), PlaybackEngine.Mpv, secondEngineForFile = true, secondEngineWorks = false))
        assertEquals(DolbyVisionStep.HandOver, dolbyVisionStep(format(5, false), PlaybackEngine.Mpv, secondEngineForFile = true, secondEngineWorks = false))
    }

    @Test
    fun withoutTheSecondEngineMpvsVerdictMovesOn() {
        assertEquals(DolbyVisionStep.MoveOn, dolbyVisionStep(format(5, false), PlaybackEngine.Mpv, secondEngineForFile = false, secondEngineWorks = true))
        assertEquals(DolbyVisionStep.Wait, dolbyVisionStep(format(5, null), PlaybackEngine.Mpv, secondEngineForFile = false, secondEngineWorks = true))
    }

    @Test
    fun aFileThatPlaysRightOrIsInTheSecondEngineIsLeftAlone() {
        // mpv processed it: a capable device never hands over.
        assertEquals(DolbyVisionStep.Wait, dolbyVisionStep(format(5, true), PlaybackEngine.Mpv, secondEngineForFile = true, secondEngineWorks = false))
        assertEquals(DolbyVisionStep.Wait, dolbyVisionStep(format(5, true), PlaybackEngine.Mpv, secondEngineForFile = true, secondEngineWorks = true))
        assertEquals(DolbyVisionStep.Wait, dolbyVisionStep(format(8, false), PlaybackEngine.Mpv, secondEngineForFile = true, secondEngineWorks = true))
        assertEquals(DolbyVisionStep.Wait, dolbyVisionStep(format(null, null), PlaybackEngine.Mpv, secondEngineForFile = true, secondEngineWorks = true))
        assertEquals(DolbyVisionStep.Wait, dolbyVisionStep(format(5, false), PlaybackEngine.Media3, secondEngineForFile = true, secondEngineWorks = true))
    }
}
