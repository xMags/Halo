package moe.ditto.halo.player

import androidx.media3.common.C
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStartTest {

    @Test
    fun aFileOpensAtItsStartPlace() {
        assertEquals(listOf("loadfile", "https://cdn.test/a.mkv", "replace", "-1", "start=59.000"), loadfileArguments("https://cdn.test/a.mkv", 59.0))
        assertEquals(listOf("loadfile", "https://cdn.test/a.mkv", "replace", "-1", "start=3725.250"), loadfileArguments("https://cdn.test/a.mkv", 3725.25))
    }

    @Test
    fun noPlaceOpensAtTheTop() {
        assertEquals(listOf("loadfile", "u"), loadfileArguments("u", null))
        assertEquals(listOf("loadfile", "u"), loadfileArguments("u", 0.0))
        assertEquals(listOf("loadfile", "u"), loadfileArguments("u", -3.0))
        assertEquals(listOf("loadfile", "u"), loadfileArguments("u", Double.NaN))
    }

    @Test
    fun theDolbyVisionVerdictWaitsForBothTheDecoderAndThePicture() {
        // Whichever came first, nothing is decided until both are there.
        assertEquals(null, dolbyVisionVerdict(onComposedOutput = true, videoEnabled = true, decoder = null, matrix = "bt.709"))
        assertEquals(null, dolbyVisionVerdict(onComposedOutput = true, videoEnabled = true, decoder = "mediacodec-copy", matrix = null))
        assertEquals(false, dolbyVisionVerdict(onComposedOutput = true, videoEnabled = true, decoder = "mediacodec-copy", matrix = "bt.709"))
        assertEquals(true, dolbyVisionVerdict(onComposedOutput = true, videoEnabled = true, decoder = "no", matrix = "dolbyvision"))
        // The direct output draws nothing of the engine's, and video off says nothing.
        assertEquals(null, dolbyVisionVerdict(onComposedOutput = false, videoEnabled = true, decoder = "mediacodec", matrix = "bt.709"))
        assertEquals(null, dolbyVisionVerdict(onComposedOutput = true, videoEnabled = false, decoder = "mediacodec-copy", matrix = "bt.709"))
        assertEquals(null, dolbyVisionVerdict(onComposedOutput = true, videoEnabled = true, decoder = "", matrix = ""))
    }

    @Test
    fun dolbyEncodingsAreLetThroughAsAllowed() {
        assertEquals(C.ENCODING_AC3, Media3Encodings.Ac3)
        assertEquals(C.ENCODING_E_AC3, Media3Encodings.Eac3)
        assertEquals(C.ENCODING_E_AC3_JOC, Media3Encodings.Eac3Joc)
        assertTrue(Media3Encodings.isPassthrough(Media3Encodings.Eac3))
        assertTrue(Media3Encodings.isPassthrough(Media3Encodings.Eac3Joc))
        assertFalse(Media3Encodings.isPassthrough(2))
        assertTrue(Media3Encodings.allows(setOf(Media3Encodings.Eac3), Media3Encodings.Eac3))
        // An Atmos stream goes out as plain E-AC-3 where only that is taken.
        assertTrue(Media3Encodings.allows(setOf(Media3Encodings.Eac3), Media3Encodings.Eac3Joc))
        assertFalse(Media3Encodings.allows(setOf(Media3Encodings.Ac3), Media3Encodings.Eac3))
        assertFalse(Media3Encodings.allows(emptySet(), Media3Encodings.Ac3))
    }
}
