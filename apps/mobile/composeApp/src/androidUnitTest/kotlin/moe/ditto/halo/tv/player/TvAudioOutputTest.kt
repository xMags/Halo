package moe.ditto.halo.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvAudioOutputTest {
    @Test
    fun autoSendsOnWhatTheOutputTakes() {
        val receiver = setOf(PassthroughFormat.Ac3, PassthroughFormat.Eac3, PassthroughFormat.Dts)
        assertEquals("ac3,eac3,dts", spdifCodecsFor(receiver, SurroundSound.Auto))
        assertEquals("ac3,eac3,dts,dts-hd,truehd", spdifCodecsFor(PassthroughFormat.entries.toSet(), SurroundSound.Auto))
    }

    @Test
    fun aStereoOutputOrOffDecodesEverythingHere() {
        assertNull(spdifCodecsFor(emptySet(), SurroundSound.Auto))
        assertNull(spdifCodecsFor(PassthroughFormat.entries.toSet(), SurroundSound.Off))
    }

    @Test
    fun onlyTracksInTheListGoThroughUntouched() {
        assertTrue(passesThrough("ac3,eac3", "eac3"))
        assertTrue(passesThrough("ac3,eac3", "AC3"))
        assertFalse(passesThrough("ac3,eac3", "aac"))
        assertFalse(passesThrough("ac3,eac3", "truehd"))
        assertFalse(passesThrough(null, "ac3"))
        assertFalse(passesThrough("ac3", null))
        // A DTS-HD track sends its DTS core when only DTS is allowed.
        assertTrue(passesThrough("dts", "dts"))
        assertTrue(passesThrough("dts-hd", "dts"))
    }

    @Test
    fun theEnginesDecoderDecidesWhetherSoundPassesThrough() {
        assertTrue(passthroughActive(decoder = "spdif_eac3", spdifCodecs = "ac3,eac3", codec = "eac3"))
        // mpv fell back to decoding here although passthrough was asked for.
        assertFalse(passthroughActive(decoder = "eac3", spdifCodecs = "ac3,eac3", codec = "eac3"))
        // A pass-through decoder is the truth even when the list was already emptied.
        assertTrue(passthroughActive(decoder = "spdif_ac3", spdifCodecs = null, codec = "ac3"))
        assertFalse(passthroughActive(decoder = "aac", spdifCodecs = "ac3,eac3", codec = "aac"))
    }

    @Test
    fun untilTheDecoderIsKnownWhatWasAskedDecides() {
        assertTrue(passthroughActive(decoder = null, spdifCodecs = "ac3,eac3", codec = "eac3"))
        assertFalse(passthroughActive(decoder = null, spdifCodecs = null, codec = "eac3"))
        assertFalse(passthroughActive(decoder = null, spdifCodecs = "ac3", codec = "eac3"))
    }

    @Test
    fun aFormatCountsOnlyWhenListedAndItOpens() {
        val listed = setOf(PassthroughFormat.Ac3, PassthroughFormat.Eac3)
        assertEquals(listed, confirmedFormats(listed, encodedSurroundOutput = null) { true })
        assertEquals(setOf(PassthroughFormat.Ac3), confirmedFormats(listed, encodedSurroundOutput = 0) { it == PassthroughFormat.Ac3 })
        assertEquals(emptySet(), confirmedFormats(listed, encodedSurroundOutput = null) { false })
        // Opening a format the output does not list never makes it count.
        assertEquals(emptySet(), confirmedFormats(emptySet(), encodedSurroundOutput = null) { true })
    }

    @Test
    fun theSystemSwitchSetToNeverTurnsEverythingOff() {
        val listed = PassthroughFormat.entries.toSet()
        var asked = false
        assertEquals(emptySet(), confirmedFormats(listed, EncodedSurroundNever) { asked = true; true })
        assertFalse(asked)
        // Other values are not special-cased.
        assertEquals(listed, confirmedFormats(listed, encodedSurroundOutput = 2) { true })
        assertEquals(listed, confirmedFormats(listed, encodedSurroundOutput = 3) { true })
    }

    @Test
    fun theFailedFormatsAreTheOnesCarryingTheTrack() {
        assertEquals(setOf(PassthroughFormat.Eac3), formatsCarrying("ac3,eac3", "eac3"))
        assertEquals(setOf(PassthroughFormat.Dts, PassthroughFormat.DtsHd), formatsCarrying("ac3,dts,dts-hd", "DTS"))
        assertEquals(setOf(PassthroughFormat.DtsHd), formatsCarrying("dts-hd", "dts"))
        assertEquals(emptySet(), formatsCarrying("ac3,eac3", "aac"))
        assertEquals(emptySet(), formatsCarrying(null, "eac3"))
    }
}
