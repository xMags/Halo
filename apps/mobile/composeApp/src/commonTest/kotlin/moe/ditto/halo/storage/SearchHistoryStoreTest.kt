package moe.ditto.halo.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class RecordingStore(seed: Map<String, String> = emptyMap()) : KeyValueStore {
    val values = seed.toMutableMap()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}

class SearchHistoryStoreTest {
    @Test
    fun keepsTheMostRecentTermFirst() {
        val store = SearchHistoryStore(RecordingStore())

        store.add("dune")
        store.add("arrival")

        assertEquals(listOf("arrival", "dune"), store.terms.value)
    }

    @Test
    fun searchingAgainMovesTheTermUpRatherThanDuplicatingIt() {
        val store = SearchHistoryStore(RecordingStore())
        store.add("dune")
        store.add("arrival")

        store.add("dune")

        assertEquals(listOf("dune", "arrival"), store.terms.value)
    }

    @Test
    fun retypingWithDifferentCasingKeepsTheNewSpelling() {
        val store = SearchHistoryStore(RecordingStore())
        store.add("dune")

        store.add("DUNE")

        assertEquals(listOf("DUNE"), store.terms.value)
    }

    @Test
    fun ignoresTermsTooShortToBeAQuery() {
        val store = SearchHistoryStore(RecordingStore())

        store.add("d")
        store.add("  ")

        assertTrue(store.terms.value.isEmpty())
    }

    @Test
    fun trimsSurroundingWhitespace() {
        val store = SearchHistoryStore(RecordingStore())

        store.add("  blade runner  ")

        assertEquals(listOf("blade runner"), store.terms.value)
    }

    @Test
    fun dropsTheOldestTermsPastTheLimit() {
        val store = SearchHistoryStore(RecordingStore())
        repeat(25) { store.add("term $it") }

        assertEquals(20, store.terms.value.size)
        assertEquals("term 24", store.terms.value.first())
        assertTrue(store.terms.value.none { it == "term 0" })
    }

    @Test
    fun removingAndClearingTakeEffect() {
        val store = SearchHistoryStore(RecordingStore())
        store.add("dune")
        store.add("arrival")

        store.remove("dune")
        assertEquals(listOf("arrival"), store.terms.value)

        store.clear()
        assertTrue(store.terms.value.isEmpty())
    }

    @Test
    fun historySurvivesRestart() {
        val backing = RecordingStore()
        SearchHistoryStore(backing).add("dune")

        assertEquals(listOf("dune"), SearchHistoryStore(backing).terms.value)
    }

    @Test
    fun unreadableHistoryStartsEmptyRatherThanFailing() {
        val backing = RecordingStore(mapOf(StorageKeys.SearchHistory to "{not a list"))

        val store = SearchHistoryStore(backing)

        assertTrue(store.terms.value.isEmpty())
        assertEquals(listOf("dune"), store.add("dune"))
    }
}
