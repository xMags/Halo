package moe.ditto.halo.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.ditto.halo.storage.RecordingStore
import moe.ditto.halo.storage.StorageKeys

class PassthroughFailuresTest {
    private val receiver = setOf(PassthroughFormat.Ac3, PassthroughFormat.Eac3)
    private val monitor = outputIdentity(listOf(AudioOutputDevice("hdmi", "PC Monitor")))!!
    private val soundbar = outputIdentity(listOf(AudioOutputDevice("hdmi-arc", "Soundbar")))!!
    private var clock = 1_000_000_000_000L

    private fun failures(store: RecordingStore = RecordingStore()) = PassthroughFailures(store, now = { clock })

    @Test
    fun theIdentityIgnoresOrderDuplicatesAndLineBreaks() {
        assertNull(outputIdentity(emptyList()))
        val a = outputIdentity(listOf(AudioOutputDevice("hdmi", "TV"), AudioOutputDevice("hdmi-arc", "Bar")))
        val b = outputIdentity(listOf(AudioOutputDevice("hdmi-arc", "Bar"), AudioOutputDevice("hdmi", "TV"), AudioOutputDevice("hdmi", "TV")))
        assertEquals(a, b)
        assertEquals("hdmi:A B", outputIdentity(listOf(AudioOutputDevice("hdmi", "A\nB"))))
    }

    @Test
    fun aReplugOfTheSameOutputKeepsTheFailure() {
        val failures = failures()
        assertEquals(receiver, failures.usable(receiver, monitor))
        failures.record(setOf(PassthroughFormat.Eac3))
        // The output comes back after a refresh rate switch: same kind, same name.
        val replugged = outputIdentity(listOf(AudioOutputDevice("hdmi", "PC Monitor")))
        assertEquals(setOf(PassthroughFormat.Ac3), failures.usable(receiver, replugged))
        assertEquals("ac3", spdifCodecsFor(failures.usable(receiver, replugged), SurroundSound.Auto))
    }

    @Test
    fun anOutputMomentarilyMissingChangesNothing() {
        val store = RecordingStore()
        val failures = failures(store)
        failures.usable(receiver, monitor)
        failures.record(setOf(PassthroughFormat.Eac3))
        val stored = store.values[StorageKeys.TvPassthroughFailures]
        // Halfway through a re-plug nothing is listed, so nothing is supported either.
        assertEquals(emptySet(), failures.usable(emptySet(), output = null))
        assertEquals(setOf(PassthroughFormat.Ac3), failures.usable(receiver, output = null))
        assertEquals(stored, store.values[StorageKeys.TvPassthroughFailures])
        assertEquals(setOf(PassthroughFormat.Ac3), failures.usable(receiver, monitor))
    }

    @Test
    fun aDifferentOutputForgets() {
        val store = RecordingStore()
        val failures = failures(store)
        failures.usable(receiver, monitor)
        failures.record(setOf(PassthroughFormat.Eac3))
        assertEquals(receiver, failures.usable(receiver, soundbar))
        assertNull(store.values[StorageKeys.TvPassthroughFailures])
        // Going back does not bring the forgotten failure back.
        assertEquals(receiver, failures.usable(receiver, monitor))
    }

    @Test
    fun aFailureExpiresAfterAWeek() {
        val failures = failures()
        failures.usable(receiver, monitor)
        failures.record(setOf(PassthroughFormat.Eac3))
        clock += PassthroughFailureExpiryMs - 1
        assertEquals(setOf(PassthroughFormat.Ac3), failures.usable(receiver, monitor))
        clock += 1
        assertEquals(receiver, failures.usable(receiver, monitor))
    }

    @Test
    fun aFailureFromTheFutureIsExpired() {
        val failures = failures()
        failures.usable(receiver, monitor)
        failures.record(setOf(PassthroughFormat.Eac3))
        clock -= 60_000
        assertEquals(receiver, failures.usable(receiver, monitor))
    }

    @Test
    fun theFailureSurvivesARestart() {
        val store = RecordingStore()
        val first = failures(store)
        first.usable(receiver, monitor)
        first.record(setOf(PassthroughFormat.Eac3))
        clock += 60_000
        val afterRestart = failures(store)
        assertEquals(setOf(PassthroughFormat.Ac3), afterRestart.usable(receiver, monitor))
    }

    @Test
    fun aFailureBeforeTheOutputWasKnownIsAdoptedAndKept() {
        val store = RecordingStore()
        val failures = failures(store)
        failures.usable(receiver, output = null)
        failures.record(setOf(PassthroughFormat.Eac3))
        assertNull(store.values[StorageKeys.TvPassthroughFailures])
        assertEquals(setOf(PassthroughFormat.Ac3), failures.usable(receiver, monitor))
        assertEquals(setOf(PassthroughFormat.Ac3), failures(store).usable(receiver, monitor))
    }

    @Test
    fun readingWhatFailedChangesNothing() {
        val store = RecordingStore()
        val failures = failures(store)
        failures.usable(receiver, monitor)
        failures.record(setOf(PassthroughFormat.Eac3))
        val stored = store.values[StorageKeys.TvPassthroughFailures]
        assertEquals(setOf(PassthroughFormat.Eac3), failures.failedOn(monitor))
        // Mid re-plug: the remembered output's.
        assertEquals(setOf(PassthroughFormat.Eac3), failures.failedOn(null))
        // Another output has none, and asking does not forget this one's.
        assertEquals(emptySet(), failures.failedOn(soundbar))
        assertEquals(stored, store.values[StorageKeys.TvPassthroughFailures])
        assertEquals(setOf(PassthroughFormat.Eac3), failures.failedOn(monitor))
    }

    @Test
    fun theStoredTextRoundTrips() {
        val record = StoredPassthroughFailures(monitor, mapOf(PassthroughFormat.Eac3 to 5L, PassthroughFormat.DtsHd to 7L))
        assertEquals(record, decodePassthroughFailures(encodePassthroughFailures(record)))
    }

    @Test
    fun malformedStoredTextIsIgnored() {
        listOf(
            null,
            "",
            "v1",
            "v1\nhdmi:PC Monitor",
            "v2\nhdmi:PC Monitor\neac3 5",
            "v1\n \neac3 5",
            "v1\nhdmi:PC Monitor\nopus 5",
            "v1\nhdmi:PC Monitor\neac3 soon",
            "v1\nhdmi:PC Monitor\neac3 -5",
            "v1\nhdmi:PC Monitor\neac3 5 6",
            "v1\nhdmi:PC Monitor\neac3 5\ngarbage",
        ).forEach { text -> assertNull(decodePassthroughFailures(text), "accepted: $text") }
        val failures = failures(RecordingStore(mapOf(StorageKeys.TvPassthroughFailures to "v1\nhdmi:PC Monitor\neac3 soon")))
        assertEquals(receiver, failures.usable(receiver, monitor))
    }
}
