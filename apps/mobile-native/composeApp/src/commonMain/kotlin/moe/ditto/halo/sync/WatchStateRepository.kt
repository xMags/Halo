package moe.ditto.halo.sync

import kotlinx.coroutines.flow.Flow
import moe.ditto.halo.api.HaloClient
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
    private val client: HaloClient,
    private val cache: QueryCache,
    private val clock: EpochClock,
) {
    fun observe(): Flow<QueryState<List<WatchState>>> =
        cache.query(HaloKey.WatchStates, HaloKey.WatchStates.staleMs) { client.getWatchStates() }

    /** Progress for one video, from whatever is cached. */
    suspend fun progressFor(videoId: String): WatchState? =
        cache.peek<List<WatchState>>(HaloKey.WatchStates)?.firstOrNull { it.videoId == videoId }

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
    }
}
