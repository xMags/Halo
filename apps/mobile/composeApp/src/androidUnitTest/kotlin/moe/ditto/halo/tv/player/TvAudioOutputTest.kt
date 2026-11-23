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
}
