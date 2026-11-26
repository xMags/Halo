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

    @Test
    fun aDeviceThatLearnedNothingListsSourcesAsBefore() {
        val adjusted = tvPlayableSources(answer, learned = false) { true }
        assertSame(answer, adjusted.resolve)
        assertEquals(0, adjusted.leftOut)
        assertEquals(0, adjusted.movedDown)
    }

    @Test
    fun aLearnedDeviceLeavesOutWhatItSawAndMovesTheNameDown() {
        val before = ids(answer)
        val adjusted = tvPlayableSources(answer, learned = true) { it.endsWith("/dv") }
        // The shared order is kept among the rest.
        assertEquals(before - "dv", ids(adjusted.resolve))
        assertEquals(1, adjusted.leftOut)
        assertEquals(answer.unplayableCount + 1, adjusted.resolve.unplayableCount)
        assertEquals(listOf(0, 1, 2), adjusted.resolve.pool.map { it.rank })
        assertEquals("3 sources · 1 addon", adjusted.resolve.summary)
    }

    @Test
    fun aLearnedDeviceMovesAnUnopenedDolbyVisionNameBelowTheRest() {
        val adjusted = tvPlayableSources(answer, learned = true) { false }
        assertEquals(ids(answer) - "dv" + "dv", ids(adjusted.resolve))
        assertEquals(1, adjusted.movedDown)
        assertEquals(0, adjusted.leftOut)
        assertEquals(4, adjusted.resolve.pool.size)
        assertEquals(0, adjusted.resolve.pool.first().rank)
    }

    @Test
    fun theOnlySourceIsKeptEvenWhenItIsKnownWrong() {
        val only = resolve(stream("dv", "Show.2160p.DV.HEVC [RD+]"))
        val adjusted = tvPlayableSources(only, learned = true) { true }
        assertEquals(listOf("dv"), ids(adjusted.resolve))
        assertEquals(0, adjusted.leftOut)
        assertEquals(0, adjusted.movedDown)
    }

    @Test
    fun theRecordRoundTripsAndMalformedOnesAreIgnored() {
        val print = sourceFingerprint("https://cdn.test/dv")
        assertEquals(64, print.length)
        assertEquals(print, sourceFingerprint("https://cdn.test/dv"))
        val record = DolbyVisionRecord("app:1;hardware:true", 5L, listOf(print))
        assertEquals(record, decodeDolbyVisionRecord(encodeDolbyVisionRecord(record)))
        listOf(
            null,
            "",
            "v1\napp:1;hardware:true\n5",
            "v2\napp:1;hardware:true\n5\n$print",
            "v1\n \n5\n$print",
            "v1\napp:1;hardware:true\nsoon\n$print",
            "v1\napp:1;hardware:true\n5\nnot-a-fingerprint",
        ).forEach { assertNull(decodeDolbyVisionRecord(it), "accepted: $it") }
    }

    @Test
    fun aRecordHoldsOnlyForTheSameSetupAndNotForever() {
        val record = DolbyVisionRecord("app:1;hardware:true", 1_000L, listOf(sourceFingerprint("u")))
        assertEquals(record, liveDolbyVisionRecord(record, "app:1;hardware:true", 2_000L))
        // An app update or software decoding may process it: learn again.
        assertNull(liveDolbyVisionRecord(record, "app:2;hardware:true", 2_000L))
        assertNull(liveDolbyVisionRecord(record, "app:1;hardware:false", 2_000L))
        assertEquals(record, liveDolbyVisionRecord(record, "app:1;hardware:true", 1_000L + DolbyVisionMemoryMs - 1))
        assertNull(liveDolbyVisionRecord(record, "app:1;hardware:true", 1_000L + DolbyVisionMemoryMs))
        assertNull(liveDolbyVisionRecord(record, "app:1;hardware:true", 500L))
        assertNull(liveDolbyVisionRecord(null, "app:1;hardware:true", 2_000L))
    }
}
