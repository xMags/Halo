package moe.ditto.halo.tv.player

import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.Stream
import moe.ditto.halo.browse.RankingDevice
import moe.ditto.halo.browse.SourceEntry
import moe.ditto.halo.browse.SourceFit
import moe.ditto.halo.browse.resolveSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvSourceFallbackTest {

    private fun stream(id: String, title: String) = Stream(url = "https://cdn.test/$id", title = title)

    /** Ranked as the sources page ranks them: cached first, then picture. */
    private val pool: List<SourceEntry> = resolveSources(
        groups = listOf(
            AddonStreams(
                AddonSource("a", "Addon A"),
                listOf(
                    stream("best", "Film.2160p.HEVC [RD+]"),
                    stream("second", "Film.1080p.x264 [RD+]"),
                    stream("unnamed", "Film.720p [RD+]"),
                    stream("download", "Film.2160p.HEVC [RD download]"),
                ),
            ),
        ),
        errors = emptyList(),
        runtimeSeconds = 0.0,
    ).pool

    private fun next(
        failed: String,
        failedNeedsDownload: Boolean = false,
        tried: Set<String> = emptySet(),
        switches: Int = 0,
    ) = nextFallbackSource(pool, "https://cdn.test/$failed", failedNeedsDownload, tried.mapTo(HashSet()) { "https://cdn.test/$it" }, switches)
        ?.stream?.url?.removePrefix("https://cdn.test/")

    @Test
    fun theNextIsTheBestRankedSourceOtherThanTheFailedOne() {
        assertEquals("best", next(failed = "elsewhere"))
        assertEquals("second", next(failed = "best"))
    }

    @Test
    fun sourcesAlreadyTriedArePassedOver() {
        assertEquals("unnamed", next(failed = "second", tried = setOf("best", "second")))
    }

    @Test
    fun aSourceThatMustDownloadIsTakenOnlyAfterOneThatHadTo() {
        assertNull(next(failed = "unnamed", tried = setOf("best", "second")))
        assertEquals("download", next(failed = "unnamed", failedNeedsDownload = true, tried = setOf("best", "second")))
    }

    @Test
    fun threeSwitchesAreTheMost() {
        assertEquals("second", next(failed = "best", switches = MaxAutomaticSourceSwitches - 1))
        assertNull(next(failed = "best", switches = MaxAutomaticSourceSwitches))
    }

    /**
     * An hour on a 10 Mbps line (budget 7): the cached remux needs 40 Mbps and
     * the cached 720p 2, the uncached 1080p 4. Cached first whatever the fit,
     * so the order is 720p, remux, 1080p.
     */
    private val slowLinePool: List<SourceEntry> = resolveSources(
        groups = listOf(
            AddonStreams(
                AddonSource("a", "Addon A"),
                listOf(
                    stream("remux", "Film.2160p.HEVC.Remux [RD+] 16.76 GB"),
                    stream("light", "Film.720p.x264 [RD+] 0.84 GB"),
                    stream("waits", "Film.1080p.x264 [RD download] 1.68 GB"),
                ),
            ),
        ),
        errors = emptyList(),
        runtimeSeconds = 3600.0,
        device = RankingDevice(lineMbps = 10.0),
    ).pool

    private fun nextOnSlowLine(failed: String, failedNeedsDownload: Boolean, tried: Set<String> = emptySet()) =
        nextFallbackSource(slowLinePool, "https://cdn.test/$failed", failedNeedsDownload, tried.mapTo(HashSet()) { "https://cdn.test/$it" }, 0)
            ?.stream?.url?.removePrefix("https://cdn.test/")

    @Test
    fun theSlowLinePoolIsRankedAsTheRuleSays() {
        assertEquals(listOf("light", "remux", "waits"), slowLinePool.map { it.stream.url!!.removePrefix("https://cdn.test/") })
        assertEquals(listOf(SourceFit.Fits, SourceFit.OverBudget, SourceFit.Fits), slowLinePool.map { it.fit })
    }

    @Test
    fun aSourceThatMayBufferIsPassedOverWhileAnotherIsLeft() {
        // The remux ranks above the 1080p, but the 1080p plays smoothly.
        assertEquals("waits", nextOnSlowLine(failed = "light", failedNeedsDownload = true))
    }

    @Test
    fun aSourceThatMayBufferIsStillTakenWhenNothingElseIsLeft() {
        // The 1080p must download first and the failed source did not, so only the remux is left.
        assertEquals("remux", nextOnSlowLine(failed = "light", failedNeedsDownload = false))
        assertEquals("remux", nextOnSlowLine(failed = "light", failedNeedsDownload = true, tried = setOf("waits")))
    }

    @Test
    fun anEmptyPoolHasNothingNext() {
        assertNull(nextFallbackSource(emptyList(), "https://cdn.test/best", false, emptySet(), 0))
    }
}
