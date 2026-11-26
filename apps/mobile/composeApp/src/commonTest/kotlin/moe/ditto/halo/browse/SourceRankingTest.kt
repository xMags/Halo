package moe.ditto.halo.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.ditto.halo.api.HaloJson

/** The parts of the ranking rule the shared vectors do not cover: inputs, numbers and the server's annotation. */
class SourceRankingTest {
    private fun rank(json: String) = readSourceRank(HaloJson.parseToJsonElement(json))

    @Test
    fun aWellFormedAnnotationIsRead() {
        assertEquals(SourceRank(0, SourceFit.Fits), rank("""{"rank":0,"fit":"fits"}"""))
        assertEquals(SourceRank(7, SourceFit.OverBudget), rank("""{"rank":7,"fit":"overBudget"}"""))
        assertEquals(SourceRank(3, SourceFit.ExceedsScreen), rank("""{"rank":3.0,"fit":"exceedsScreen"}"""))
    }

    @Test
    fun anythingElseIsNoAnnotation() {
        assertNull(readSourceRank(null))
        for (bad in listOf(
            "null", "3", "\"fits\"", "[]", "{}",
            """{"rank":-1,"fit":"fits"}""",
            """{"rank":1.5,"fit":"fits"}""",
            """{"rank":"2","fit":"fits"}""",
            """{"rank":true,"fit":"fits"}""",
            """{"rank":null,"fit":"fits"}""",
            """{"rank":9007199254740993,"fit":"fits"}""",
            """{"rank":1e400,"fit":"fits"}""",
            """{"rank":1,"fit":"FITS"}""",
            """{"rank":1,"fit":"tooBig"}""",
            """{"rank":1,"fit":1}""",
            """{"rank":1}""",
        )) {
            assertNull(rank(bad), bad)
        }
    }

    @Test
    fun rankingInputsOutOfBoundsAreUnknown() {
        assertEquals(
            StreamRankingInput(0.0, 0.0, 0.0),
            normalizeRankingInput(StreamRankingInput(-5.0, Double.NaN, Double.POSITIVE_INFINITY)),
        )
        assertEquals(
            StreamRankingInput(0.0, 0.0, 0.0),
            normalizeRankingInput(StreamRankingInput(MaxLineMbps + 1, MaxScreenHeight + 1, MaxRuntimeSeconds + 1)),
        )
    }

    @Test
    fun rankingInputsAreRoundedAsTheSharedRuleRoundsThem() {
        // Half up, as JavaScript's Math.round, never half to even.
        // Every value below is exact in binary, so .5 really is a half.
        assertEquals(StreamRankingInput(12.13, 1081.0, 5401.0), normalizeRankingInput(StreamRankingInput(12.125, 1080.5, 5400.5)))
        assertEquals(StreamRankingInput(0.13, 2160.0, 3.0), normalizeRankingInput(StreamRankingInput(0.125, 2160.25, 2.5)))
        assertTrue(sameRankingInput(normalizeRankingInput(StreamRankingInput(40.001, 2160.0, 60.0)), StreamRankingInput(40.0, 2160.0, 60.0)))
    }

    @Test
    fun queryValuesArePlainDecimalsTheServerAccepts() {
        assertEquals("40", rankingQueryValue(40.0))
        assertEquals("12.5", rankingQueryValue(12.5))
        assertEquals("0.07", rankingQueryValue(0.07))
        assertEquals("100000", rankingQueryValue(100_000.0))
        assertEquals("2160", rankingQueryValue(2160.0))
    }

    @Test
    fun neededMbpsIsSizeInBitsOverTheRuntime() {
        assertEquals(20.0, neededMbps(9_000_000_000, 3600.0))
        assertEquals(0.0, neededMbps(null, 3600.0))
        assertEquals(0.0, neededMbps(9_000_000_000, 0.0))
        assertEquals(0.0, neededMbps(0, 3600.0))
    }
}
