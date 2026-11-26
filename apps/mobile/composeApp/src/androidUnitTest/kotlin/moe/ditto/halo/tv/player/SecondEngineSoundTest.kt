package moe.ditto.halo.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.ditto.halo.storage.RecordingStore

class SecondEngineSoundTest {
    private val all = SecondEngineSoundFacts(
        surroundAllowed = true,
        mpvFailed = true,
        outputTakes = true,
        decoderFallback = true,
        result = null,
    )

    @Test
    fun everyConditionMustHold() {
        assertTrue(secondEngineMayPassThrough(all))
        assertTrue(secondEngineMayPassThrough(all.copy(result = SoundResult.Works)))
        assertFalse(secondEngineMayPassThrough(all.copy(surroundAllowed = false)), "Surround Off or the system set to never")
        assertFalse(secondEngineMayPassThrough(all.copy(mpvFailed = false)), "mpv passes it through here: nothing to fix")
        assertFalse(secondEngineMayPassThrough(all.copy(outputTakes = false)), "the output does not take it")
        assertFalse(secondEngineMayPassThrough(all.copy(decoderFallback = false)), "no decoder to fall back to")
        assertFalse(secondEngineMayPassThrough(all.copy(result = SoundResult.Fails)), "failed there before")
    }

    @Test
    fun aFileIsHandedOverOnlyWhenNothingElseIsLost() {
        assertTrue(soundHandover(PassthroughFormat.Eac3, all, pictureFromHardware = true, subtitleDrawnByApp = true))
        assertTrue(soundHandover(PassthroughFormat.Ac3, all, pictureFromHardware = true, subtitleDrawnByApp = true))
        assertFalse(soundHandover(PassthroughFormat.Eac3, all, pictureFromHardware = false, subtitleDrawnByApp = true))
        // A picture subtitle or a script keeping its styling stays with mpv.
        assertFalse(soundHandover(PassthroughFormat.Eac3, all, pictureFromHardware = true, subtitleDrawnByApp = false))
        assertFalse(soundHandover(PassthroughFormat.Eac3, all.copy(result = SoundResult.Fails), pictureFromHardware = true, subtitleDrawnByApp = true))
        // Dolby only in this round.
        assertFalse(soundHandover(PassthroughFormat.Dts, all, pictureFromHardware = true, subtitleDrawnByApp = true))
        assertFalse(soundHandover(null, all, pictureFromHardware = true, subtitleDrawnByApp = true))
    }

    @Test
    fun onlyDolbyTracksAreNamed() {
        assertEquals(PassthroughFormat.Eac3, dolbyFormatOf("eac3"))
        assertEquals(PassthroughFormat.Ac3, dolbyFormatOf("AC3"))
        assertNull(dolbyFormatOf("dts"))
        assertNull(dolbyFormatOf("aac"))
        assertNull(dolbyFormatOf(null))
    }

    @Test
    fun theRecordRoundTripsAndMalformedOnesAreIgnored() {
        val results = mapOf(PassthroughFormat.Eac3 to SoundRecord(SoundResult.Works, 5L), PassthroughFormat.Ac3 to SoundRecord(SoundResult.Fails, 7L))
        assertEquals("hdmi:AFTR" to results, decodeSecondEngineSound(encodeSecondEngineSound("hdmi:AFTR", results)))
        listOf(
            null,
            "",
            "v1\nhdmi:AFTR",
            "v2\nhdmi:AFTR\neac3 works 5",
            "v1\n \neac3 works 5",
            "v1\nhdmi:AFTR\nopus works 5",
            "v1\nhdmi:AFTR\neac3 maybe 5",
            "v1\nhdmi:AFTR\neac3 works soon",
            "v1\nhdmi:AFTR\neac3 works 5 6",
        ).forEach { assertNull(decodeSecondEngineSound(it), "accepted: $it") }
    }

    private var clock = 1_000_000_000_000L

    private fun memory(store: RecordingStore = RecordingStore()) = SecondEngineSoundMemory(store, now = { clock })

    @Test
    fun aResultHoldsForTheSameOutputAndSurvivesARestart() {
        val store = RecordingStore()
        val first = memory(store)
        assertNull(first.resultFor(PassthroughFormat.Eac3, "hdmi:AFTR"))
        first.record(PassthroughFormat.Eac3, SoundResult.Fails, "hdmi:AFTR")
        assertEquals(SoundResult.Fails, first.resultFor(PassthroughFormat.Eac3, "hdmi:AFTR"))
        // Mid re-plug the output is not listed: what was seen still holds.
        assertEquals(SoundResult.Fails, first.resultFor(PassthroughFormat.Eac3, null))
        assertEquals(SoundResult.Fails, memory(store).resultFor(PassthroughFormat.Eac3, "hdmi:AFTR"))
        assertNull(first.resultFor(PassthroughFormat.Ac3, "hdmi:AFTR"))
    }

    @Test
    fun anotherOutputStartsAfresh() {
        val store = RecordingStore()
        val memory = memory(store)
        memory.record(PassthroughFormat.Eac3, SoundResult.Fails, "hdmi:AFTR")
        assertNull(memory.resultFor(PassthroughFormat.Eac3, "hdmi-arc:Soundbar"))
        memory.record(PassthroughFormat.Eac3, SoundResult.Works, "hdmi-arc:Soundbar")
        assertEquals(SoundResult.Works, memory.resultFor(PassthroughFormat.Eac3, "hdmi-arc:Soundbar"))
        assertNull(memory.resultFor(PassthroughFormat.Eac3, "hdmi:AFTR"))
    }

    @Test
    fun bothResultsExpireLikeMpvsFailures() {
        val memory = memory()
        memory.record(PassthroughFormat.Eac3, SoundResult.Works, "hdmi:AFTR")
        clock += PassthroughFailureExpiryMs - 1
        assertEquals(SoundResult.Works, memory.resultFor(PassthroughFormat.Eac3, "hdmi:AFTR"))
        clock += 1
        assertNull(memory.resultFor(PassthroughFormat.Eac3, "hdmi:AFTR"))
        memory.record(PassthroughFormat.Ac3, SoundResult.Fails, "hdmi:AFTR")
        clock -= 60_000
        // From the future (the clock moved back): expired too.
        assertNull(memory.resultFor(PassthroughFormat.Ac3, "hdmi:AFTR"))
    }

    @Test
    fun malformedStoredTextIsIgnored() {
        val memory = memory(RecordingStore(mapOf("halo.tv.secondEngineSound.v1" to "v1\nhdmi:AFTR\neac3 maybe 5")))
        assertNull(memory.resultFor(PassthroughFormat.Eac3, "hdmi:AFTR"))
    }
}
