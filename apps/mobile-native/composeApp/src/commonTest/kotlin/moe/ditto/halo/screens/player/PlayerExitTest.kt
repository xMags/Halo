package moe.ditto.halo.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
