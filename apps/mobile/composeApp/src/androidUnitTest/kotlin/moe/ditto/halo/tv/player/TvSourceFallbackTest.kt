package moe.ditto.halo.tv.player

import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.Stream
import moe.ditto.halo.browse.SourceEntry
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

    @Test
    fun anEmptyPoolHasNothingNext() {
        assertNull(nextFallbackSource(emptyList(), "https://cdn.test/best", false, emptySet(), 0))
    }
}
