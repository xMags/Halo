package moe.ditto.halo.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.auth.EpochClock
import moe.ditto.halo.cache.HaloKey
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.cache.QueryState

/**
 * The user's saved titles, synced last-write-wins per row.
 *
 * Removals are tombstones rather than deletions: a device that has been
 * offline still holds the item, and without a dated removal to compare against
 * its next write would resurrect it.
 */
class LibraryRepository(
    private val client: HaloClient,
    private val cache: QueryCache,
    private val clock: EpochClock,
) {
    /** Everything the server holds, tombstones included. */
    fun observe(): Flow<QueryState<List<LibraryItem>>> =
        cache.query(HaloKey.Library, HaloKey.Library.staleMs) { client.getLibrary() }

    /** What the library screens show; tombstones are sync bookkeeping, not content. */
    fun observeActive(): Flow<QueryState<List<LibraryItem>>> = observe().map { state ->
        QueryState(
            value = state.value?.filterNot { it.isRemoved },
            isFetching = state.isFetching,
            error = state.error,
        )
    }

    /**
     * Saves [meta]. Re-adding a previously removed title clears its tombstone
     * and restarts its added date, which is what "add to library" means to
     * someone who removed it a year ago.
     */
    suspend fun add(meta: MetaCard): LibraryItem {
        val now = clock.nowMs()
        val item = LibraryItem(
            id = itemId(meta.type, meta.id),
            type = meta.type,
            // The server requires a name; an addon that omits one should cost
            // the title its label, not its place in the library.
            name = SyncFields.name(meta.name) ?: meta.id,
            poster = SyncFields.poster(meta.poster),
            addedAt = now,
            removedAt = null,
            updatedAt = now,
        )
        write(item)
        return item
    }

    /**
     * Tombstones [itemId] if it is known. Nothing is written for an unknown
     * id: a tombstone invented without the original row would carry a
     * fabricated name and added date into every other device.
     */
    suspend fun remove(itemId: String): LibraryItem? {
        val existing = cache.peek<List<LibraryItem>>(HaloKey.Library)?.firstOrNull { it.id == itemId } ?: return null
        val now = clock.nowMs()
        val tombstone = existing.copy(removedAt = now, updatedAt = now)
        write(tombstone)
        return tombstone
    }

    private suspend fun write(item: LibraryItem) {
        cache.mutate<List<LibraryItem>>(
            key = HaloKey.Library,
            optimistic = { current -> current.orEmpty().upsert(item) },
            // Only the changed row is sent, unlike a whole-blob write: the
            // server merges per row, so a request carrying one row cannot drop
            // another row's newer edit. The response is the full merged
            // collection and replaces the cache.
            put = { client.putLibrary(listOf(item)) },
        )
    }

    private fun List<LibraryItem>.upsert(item: LibraryItem): List<LibraryItem> {
        val index = indexOfFirst { it.id == item.id }
        if (index < 0) return this + item
        return toMutableList().apply { set(index, item) }
    }

    companion object {
        /** Library rows are keyed by type and meta id, e.g. "movie:tt0111161". */
        fun itemId(type: String, metaId: String): String = "$type:$metaId"
    }
}
