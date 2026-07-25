package moe.ditto.halo.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import moe.ditto.halo.api.HaloJson

/**
 * Recent search terms, most recent first, kept on the device only.
 *
 * Exposed as state rather than a read call because the search screen shows the
 * list and edits it in the same breath — searching, or clearing a term, has to
 * be visible immediately.
 */
class SearchHistoryStore(private val store: KeyValueStore) {
    private val _terms = MutableStateFlow(read())
    val terms: StateFlow<List<String>> = _terms

    /**
     * Moves [term] to the front, or adds it. Matching ignores case but the new
     * spelling wins: someone who retypes a title with different capitalisation
     * means the newer one.
     */
    fun add(term: String): List<String> {
        val trimmed = term.trim()
        if (trimmed.length < MinLength) return _terms.value
        val rest = _terms.value.filterNot { it.equals(trimmed, ignoreCase = true) }
        return write((listOf(trimmed) + rest).take(Limit))
    }

    fun remove(term: String): List<String> = write(_terms.value.filterNot { it == term })

    fun clear(): List<String> = write(emptyList())

    private fun write(terms: List<String>): List<String> {
        _terms.value = terms
        store.write(StorageKeys.SearchHistory, HaloJson.encodeToString(Serializer, terms))
        return terms
    }

    private fun read(): List<String> {
        val raw = store.read(StorageKeys.SearchHistory) ?: return emptyList()
        return try {
            HaloJson.decodeFromString(Serializer, raw)
        } catch (_: SerializationException) {
            // Written by an incompatible build; search history is not worth a
            // crash, and the next search repopulates it.
            emptyList()
        }
    }

    private companion object {
        val Serializer = ListSerializer(String.serializer())

        /** A single character matches almost everything and is never a real query. */
        const val MinLength = 2
        const val Limit = 20
    }
}
