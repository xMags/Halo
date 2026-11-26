package moe.ditto.halo.browse

import kotlinx.serialization.json.JsonElement
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamBehaviorHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How the sources page takes its order: from a server's `halo` annotations
 * when every one is valid and was ranked for this device, else from the same
 * rule run here. Mirrors the desktop's `resolveSources` cases.
 */
class SourcesRankingTest {
    private val gb = 1_000_000_000L

    private fun halo(rank: Int, fit: String): JsonElement =
        HaloJson.parseToJsonElement("""{"rank":$rank,"fit":"$fit"}""")

    private fun stream(id: String, title: String, sizeBytes: Long, halo: JsonElement? = null) = Stream(
        url = "https://cdn.test/$id",
        title = title,
        behaviorHints = StreamBehaviorHints(videoSize = sizeBytes),
        halo = halo,
    )

    /** An hour, a 20 Mbps line (budget 14) and a 1080-pixel screen. */
    private val runtime = 3600.0
    private val device = RankingDevice(lineMbps = 20.0, screenHeight = 1080.0)
    private val rankedFor = StreamRankingInput(20.0, 1080.0, 3600.0)

    /**
     * Locally: the 1080p (9 GB, 20 Mbps) is over budget, the 2160p (4.5 GB,
     * 10 Mbps) is too large for the screen, the 720p (2 GB) fits. So the
     * local order is 720p, 2160p, 1080p.
     */
    private fun groups(
        fhdHalo: JsonElement? = null,
        uhdHalo: JsonElement? = null,
        hdHalo: JsonElement? = null,
    ) = listOf(
        AddonStreams(
            AddonSource("a", "Addon A"),
            listOf(
                stream("fhd", "Film.1080p.x264 [RD+]", 9 * gb, fhdHalo),
                stream("uhd", "Film.2160p.HEVC [RD+]", 9 * gb / 2, uhdHalo),
            ),
        ),
        AddonStreams(AddonSource("b", "Addon B"), listOf(stream("hd", "Film.720p.x264 [RD+]", 2 * gb, hdHalo))),
    )

    private fun order(resolve: SourcesResolve) = resolve.pool.map { it.stream.url!!.removePrefix("https://cdn.test/") }

    private fun fits(resolve: SourcesResolve) = resolve.pool.associate { it.stream.url!!.removePrefix("https://cdn.test/") to it.fit }

    private val localOrder = listOf("hd", "uhd", "fhd")
    private val localFits = mapOf("hd" to SourceFit.Fits, "uhd" to SourceFit.ExceedsScreen, "fhd" to SourceFit.OverBudget)

    /** A server answer that disagrees with the local rule, so the test can tell which was used. */
    private val served = groups(
        fhdHalo = halo(0, "fits"),
        uhdHalo = halo(4, "exceedsScreen"),
        hdHalo = halo(9, "overBudget"),
    )

    @Test
    fun withoutAServerTheRuleRunsHere() {
        val resolve = resolveSources(groups(), emptyList(), runtime, device = device)
        assertEquals(localOrder, order(resolve))
        assertEquals(listOf(0, 1, 2), resolve.pool.map { it.rank })
        assertEquals(localFits, fits(resolve))
        assertEquals(20.0, resolve.pool.single { it.key == "a:0" }.neededMbps, 1e-9)
    }

    @Test
    fun validAnnotationsForThisDeviceAreUsedAndRenumberedDensely() {
        val resolve = resolveSources(served, emptyList(), runtime, device = device, annotatedFor = rankedFor)
        assertEquals(listOf("fhd", "uhd", "hd"), order(resolve))
        // The server's 0, 4, 9 become positions 0, 1, 2.
        assertEquals(listOf(0, 1, 2), resolve.pool.map { it.rank })
        assertEquals(
            mapOf("fhd" to SourceFit.Fits, "uhd" to SourceFit.ExceedsScreen, "hd" to SourceFit.OverBudget),
            fits(resolve),
        )
    }

    @Test
    fun anAnswerRankedForOtherInputsIsRankedHere() {
        for (other in listOf(
            StreamRankingInput(30.0, 1080.0, 3600.0),
            StreamRankingInput(20.0, 2160.0, 3600.0),
            StreamRankingInput(20.0, 1080.0, 0.0),
            StreamRankingInput.Unknown,
        )) {
            val resolve = resolveSources(served, emptyList(), runtime, device = device, annotatedFor = other)
            assertEquals(localOrder, order(resolve), "$other")
            assertEquals(localFits, fits(resolve), "$other")
        }
    }

    @Test
    fun theInputsAreComparedNormalized() {
        // 20.001 Mbps rounds to the 20 the page itself ranks for.
        val resolve = resolveSources(served, emptyList(), runtime, device = device, annotatedFor = StreamRankingInput(20.001, 1080.0, 3600.0))
        assertEquals(listOf("fhd", "uhd", "hd"), order(resolve))
    }

    @Test
    fun annotationsNobodySaidWereRankedForThisAnswerAreIgnored() {
        // The device backend leaves rankedFor null; an addon's own `halo` is never trusted.
        val resolve = resolveSources(served, emptyList(), runtime, device = device, annotatedFor = null)
        assertEquals(localOrder, order(resolve))
    }

    @Test
    fun oneMissingOrMalformedAnnotationRanksTheWholeAnswerHere() {
        val malformed = listOf(
            null,
            HaloJson.parseToJsonElement("""{"rank":-1,"fit":"fits"}"""),
            HaloJson.parseToJsonElement("""{"rank":1,"fit":"great"}"""),
            HaloJson.parseToJsonElement("""{"rank":"1","fit":"fits"}"""),
            HaloJson.parseToJsonElement("""[1,"fits"]"""),
        )
        for (bad in malformed) {
            val answer = groups(fhdHalo = halo(0, "fits"), uhdHalo = halo(1, "fits"), hdHalo = bad)
            val resolve = resolveSources(answer, emptyList(), runtime, device = device, annotatedFor = rankedFor)
            assertEquals(localOrder, order(resolve), "$bad")
            assertEquals(localFits, fits(resolve), "$bad")
        }
    }

    @Test
    fun theTvDecoderFilterRunsFirstAndRanksStayDense() {
        val uhd10 = HardwareVideoDecoder(maxWidth = 3840, maxHeight = 2160, tenBit = true)
        val noAv1 = VideoDecoderSupport(mapOf(VideoCodecFamily.H264 to uhd10, VideoCodecFamily.Hevc to uhd10))
        val answer = listOf(
            AddonStreams(
                AddonSource("a", "Addon A"),
                listOf(
                    stream("av1", "Film.2160p.AV1 [RD+]", 3 * gb, halo(0, "fits")),
                    stream("hevc", "Film.2160p.HEVC [RD+]", 4 * gb, halo(2, "fits")),
                    stream("avc", "Film.1080p.x264 [RD+]", 3 * gb, halo(5, "overBudget")),
                ),
            ),
        )
        val served = resolveSources(answer, emptyList(), runtime, noAv1, device, rankedFor)
        assertEquals(1, served.unplayableCount)
        assertEquals(listOf("hevc", "avc"), order(served))
        assertEquals(listOf(0, 1), served.pool.map { it.rank })
        assertEquals(SourceFit.OverBudget, served.pool.last().fit)

        // The same with the rule run here, on a 4K screen: the dropped source never takes a place.
        val local = resolveSources(answer, emptyList(), runtime, noAv1, RankingDevice(20.0, 2160.0))
        assertEquals(listOf("hevc", "avc"), order(local))
        assertEquals(listOf(0, 1), local.pool.map { it.rank })
    }

    @Test
    fun onlyAnOverBudgetSourceCarriesTheWarning() {
        val resolve = resolveSources(groups(), emptyList(), runtime, device = device)
        val warnings = resolve.pool.associate { it.key to sourceWarning(it) }
        assertEquals("May buffer on this connection", warnings["a:0"])
        assertNull(warnings["a:1"]) // too large for the screen: ranked lower, said nothing
        assertNull(warnings["b:0"])
    }

    @Test
    fun whenNothingFitsThePickItselfCarriesTheWarning() {
        val slow = RankingDevice(lineMbps = 2.0, screenHeight = 0.0)
        val resolve = resolveSources(groups(), emptyList(), runtime, device = slow)
        val listing = buildListing(resolve, SourceFilter.All, SourceSort.Recommended, revealAll = false)
        // The lightest file leads when every one is over budget.
        assertEquals("b:0", listing.pick?.key)
        assertEquals(MayBufferWarning, listing.pick?.let(::sourceWarning))
        // Nothing is hidden because of its fit.
        assertEquals(3, resolve.pool.size)
    }
}
