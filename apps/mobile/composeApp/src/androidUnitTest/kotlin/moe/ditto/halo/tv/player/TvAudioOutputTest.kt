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
