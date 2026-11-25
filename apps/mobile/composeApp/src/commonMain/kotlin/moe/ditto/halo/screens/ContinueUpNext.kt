package moe.ditto.halo.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.browse.ContinueNextRequest
import moe.ditto.halo.browse.NextEpisodeLookup
import moe.ditto.halo.browse.episodeAfter

/**
 * What Continue watching knows of finished episodes' successors, keyed by the
 * finished episode's video id. Kept for as long as [graph] (the signed-in
 * session): an episode's successor does not change under the viewer.
 */
@Composable
internal fun rememberNextEpisodeAnswers(graph: SessionGraph): SnapshotStateMap<String, NextEpisodeLookup> =
    remember(graph) { mutableStateMapOf() }

/** How Home asks the shelf rules about a finished episode. */
internal fun SnapshotStateMap<String, NextEpisodeLookup>.lookup(request: ContinueNextRequest): NextEpisodeLookup =
    this[request.videoId] ?: NextEpisodeLookup.Unknown

/**
 * Looks up the successors of [requests] into [answers]. A lookup reads the
 * show's episode list (the cached title record every title page and continue
 * card already uses), so it costs nothing for a show seen recently. A failed
 * lookup is not recorded: the show is asked about again on the next visit
 * rather than dropped for the whole session.
 */
@Composable
internal fun LookUpNextEpisodes(
    graph: SessionGraph,
    answers: SnapshotStateMap<String, NextEpisodeLookup>,
    requests: List<ContinueNextRequest>,
) {
    // Keyed on what is still unknown: an answer arriving shrinks the set, and
    // the restart then only re-reads what is pending, from the cache.
    val pending = requests.filter { it.videoId !in answers }.toSet()
    LaunchedEffect(graph, pending) {
        pending.forEach { request ->
            launch {
                val state = graph.browse.meta(request.type, request.metaId).first { it.value != null || it.error != null }
                val meta = state.value ?: return@launch
                answers[request.videoId] = episodeAfter(meta.videos, request.videoId)
                    ?.let { NextEpisodeLookup.Resolved(it.id) }
                    ?: NextEpisodeLookup.None
            }
        }
    }
}
