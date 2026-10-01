package moe.ditto.halo.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferRateTest {

    @Test
    fun theFirstSampleHasNothingToCompareAgainstAndSaysSo() {
        val rate = TransferRate()

        assertEquals(0, rate.sample("tt1", downloadedBytes = 0, atMs = 1_000))
    }

    @Test
    fun aSteadyTransferReportsTheRateItIsActuallyRunningAt() {
        val rate = TransferRate()
        rate.sample("tt1", 0, 1_000)

        // 1 MB every 500 ms is 2 MB/s, and staying steady must converge on it
        // rather than drift.
        var last = 0L
        var bytes = 0L
        var now = 1_000L
        repeat(20) {
            bytes += 1_000_000
            now += 500
            last = rate.sample("tt1", bytes, now)
        }

        assertTrue(last in 1_900_000..2_100_000, "rate was $last")
    }

    @Test
    fun oneBurstDoesNotThrowTheReadout() {
        val rate = TransferRate()
        rate.sample("tt1", 0, 0)
        rate.sample("tt1", 1_000_000, 1_000)
        val steady = rate.sample("tt1", 2_000_000, 2_000)

        // A buffer draining all at once is ten times the rate for one sample.
        val afterBurst = rate.sample("tt1", 12_000_000, 3_000)

        assertTrue(afterBurst < steady * 5, "a single burst moved the rate to $afterBurst from $steady")
    }

    @Test
    fun aDifferentDownloadStartsMeasuringAgain() {
        val rate = TransferRate()
        rate.sample("tt1", 0, 0)
        rate.sample("tt1", 5_000_000, 1_000)

        assertEquals(0, rate.sample("tt2", 0, 2_000))
    }

    @Test
    fun aRestartedTransferDoesNotReportANegativeRate() {
        val rate = TransferRate()
        rate.sample("tt1", 0, 0)
        rate.sample("tt1", 5_000_000, 1_000)

        // The part file was dropped and the transfer began again from zero.
        assertEquals(0, rate.sample("tt1", 0, 2_000))
    }

    @Test
    fun twoReportsInsideOneMillisecondDoNotDivideByNothing() {
        val rate = TransferRate()
        rate.sample("tt1", 0, 0)
        val measured = rate.sample("tt1", 1_000_000, 1_000)

        assertEquals(measured, rate.sample("tt1", 1_000_001, 1_000))
    }

    @Test
    fun clearingForgetsTheTransferSoTheNextOneStartsClean() {
        val rate = TransferRate()
        rate.sample("tt1", 0, 0)
        rate.sample("tt1", 5_000_000, 1_000)

        rate.clear()

        assertEquals(0, rate.sample("tt1", 5_000_000, 2_000))
    }
}
