package moe.ditto.halo.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineSpeedStoreTest {
    @Test
    fun nothingMeasuredYetReadsAsZero() {
        assertEquals(0.0, LineSpeedStore(RecordingStore()).current())
    }

    @Test
    fun anUnusableStoredValueReadsAsZero() {
        for (bad in listOf("abc", "-3", "NaN", "Infinity", "0")) {
            assertEquals(0.0, LineSpeedStore(RecordingStore(mapOf(StorageKeys.MeasuredLineSpeed to bad))).current(), bad)
        }
    }

    @Test
    fun aSessionFoldsIntoTheKeptReadingAndIsWrittenOnlyWhenItChanges() {
        val backing = RecordingStore()
        val store = LineSpeedStore(backing)

        assertEquals(30.0, store.record(sessionPeakMbps = 30.0, stalled = false))
        assertEquals("30.0", backing.values[StorageKeys.MeasuredLineSpeed])
        // A slower session without a stall says nothing about the line.
        assertNull(store.record(sessionPeakMbps = 10.0, stalled = false))
        assertEquals(30.0, store.current())
        // A stall does.
        assertEquals(10.0, store.record(sessionPeakMbps = 10.0, stalled = true))
        // A session that measured nothing changes nothing.
        assertNull(store.record(sessionPeakMbps = 0.0, stalled = true))
        assertEquals(10.0, store.current())
    }
}
