package moe.ditto.halo.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.ditto.halo.api.HaloBackend
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.auth.EpochClock
import moe.ditto.halo.cache.HaloKey
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.cache.QueryState
import kotlin.math.floor

/**
 * Playback progress, synced last-write-wins per video.
 *
 * Rows carry a denormalised name and poster so the home screen can render
 * continue-watching entries without joining against the library — a video may
 * be watched without ever being saved.
 */
class WatchStateRepository(
    private val client: HaloBackend,
    private val cache: QueryCache,
    private val clock: EpochClock,
) {
    fun observe(): Flow<QueryState<List<WatchState>>> =
        cache.query(HaloKey.WatchStates, HaloKey.WatchStates.staleMs) { client.getWatchStates() }

    /** Progress for one video, from whatever is cached. */
    suspend fun progressFor(videoId: String): WatchState? =
        cache.peek<List<WatchState>>(HaloKey.WatchStates)?.firstOrNull { it.videoId == videoId }

    /**
     * Progress for one video, loading the history when nothing is cached yet,
     * for up to [waitMillis]. Null when there is none, or when the history could
     * not be had in time: playback must not wait on the server.
     */
    suspend fun loadedProgressFor(videoId: String, waitMillis: Long): WatchState? {
        val rows = cache.peek<List<WatchState>>(HaloKey.WatchStates)
            ?: withTimeoutOrNull(waitMillis) {
                observe().first { it.value != null || it.error != null }.value
            }
        return rows?.firstOrNull { it.videoId == videoId }
    }

    /**
     * Records a progress sample, or returns null when the sample is not worth
     * recording: anything shorter than [MinDurationSec] is a trailer or a clip
     * rather than something to resume, and the first [MinPositionSec] cover
     * opening the wrong thing and backing out. Writing those would fill the
     * continue-watching row with noise.
     *
     * [name] should be the show's name for an episode rather than the episode
     * title, because the row represents the show in the UI.
     */
    suspend fun report(
        videoId: String,
        itemId: String,
        positionSec: Double,
        durationSec: Double,
        name: String? = null,
        poster: String? = null,
    ): WatchState? {
        if (durationSec < MinDurationSec || positionSec < MinPositionSec) return null
        val state = WatchState(
            videoId = videoId,
            itemId = itemId,
            // Whole seconds are enough to resume, and keep the row stable when
            // the same position is reported twice.
            positionSec = floor(positionSec),
            durationSec = floor(durationSec),
            watched = positionSec / durationSec >= WatchedThreshold,
            name = SyncFields.name(name),
            poster = SyncFields.poster(poster),
            updatedAt = clock.nowMs(),
        )
        cache.mutate<List<WatchState>>(
            key = HaloKey.WatchStates,
            optimistic = { current -> current.orEmpty().upsert(state) },
            // Per-row merge server-side, so only the changed row is sent.
            put = { client.putWatchStates(listOf(state)) },
        )
        return state
    }

    /** The cached rows, or null while the history has not loaded: a mark then has nothing to build on. */
    fun cached(): List<WatchState>? = cache.peekNow<List<WatchState>>(HaloKey.WatchStates)

    /** "Mark as watched" for each target, first to last, against the rows as they are now. */
    suspend fun markWatched(targets: List<WatchMarkTarget>, runtimeSec: Double?) {
        if (targets.isEmpty()) return
        val existing = cached().orEmpty().associateBy { it.videoId }
        write(watchedRowsInOrder(targets, existing, runtimeSec, clock.nowMs()))
    }

    suspend fun markUnwatched(target: WatchMarkTarget) {
        val existing = cached()?.firstOrNull { it.videoId == target.videoId }
        write(listOf(unwatchedRow(target, existing, clock.nowMs())))
    }

    /**
     * Removes the rows from the watch history ([removedRows]). Servers leave
     * tombstones out of what they send back, so a removal they understood is
     * gone from the echo; one still there means a server that does not know
     * removals yet, and that is said rather than shown as done.
     */
    suspend fun remove(states: List<WatchState>) {
        if (states.isEmpty()) return
        val rows = removedRows(states, clock.nowMs())
        val removed = rows.mapTo(mutableSetOf()) { it.videoId }
        val echo = write(rows)
        if (echo.any { it.videoId in removed }) throw RemovalUnsupportedException()
    }

    /** Batches, so clearing a long history is not one huge request; the cache keeps the last echo. */
    private suspend fun write(rows: List<WatchState>): List<WatchState> {
        var echo: List<WatchState> = emptyList()
        for (batch in rows.chunked(WriteBatchSize)) {
            echo = cache.mutate<List<WatchState>>(
                key = HaloKey.WatchStates,
                optimistic = { current ->
                    batch.fold(current.orEmpty()) { list, row ->
                        if (row.removedAt != null) list.filterNot { it.videoId == row.videoId } else list.upsert(row)
                    }
                },
                put = { client.putWatchStates(batch) },
            )
        }
        return echo
    }

    private fun List<WatchState>.upsert(state: WatchState): List<WatchState> {
        val index = indexOfFirst { it.videoId == state.videoId }
        if (index < 0) return this + state
        return toMutableList().apply { set(index, state) }
    }

    companion object {
        /** Past this fraction the video counts as watched rather than in progress. */
        const val WatchedThreshold = 0.9

        const val MinDurationSec = 60.0
        const val MinPositionSec = 5.0

        private const val WriteBatchSize = 500
    }
}

/** The server kept rows it was asked to remove: it predates removals from the watch history. */
class RemovalUnsupportedException :
    IllegalStateException("Your Halo server cannot remove history yet. Nothing was lost; it needs updating first.")
