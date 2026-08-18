package moe.ditto.halo.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest

class PlayerExitTest {
    @Test
    fun failedWatchReportStillWindsDownBeforeNavigation() = runTest {
        val calls = mutableListOf<String>()

        windDownAndLeave(
            report = {
                calls += "report"
                throw IllegalStateException("fixture watch-state request failed")
            },
            windDown = { calls += "wind-down" },
            navigate = { calls += "navigate" },
        )

        assertEquals(listOf("report", "wind-down", "navigate"), calls)
    }

    /**
     * A report that never answers is the dangerous one: a failure at least
     * returns. Until this was bounded, a stalled uplink held the decoder open
     * behind it with the picture still running and back doing nothing.
     */
    @Test
    fun stalledWatchReportStillWindsDownBeforeNavigation() = runTest {
        val calls = mutableListOf<String>()

        windDownAndLeave(
            report = {
                calls += "report"
                awaitCancellation()
            },
            windDown = { calls += "wind-down" },
            navigate = { calls += "navigate" },
        )

        assertEquals(listOf("report", "wind-down", "navigate"), calls)
    }
}
