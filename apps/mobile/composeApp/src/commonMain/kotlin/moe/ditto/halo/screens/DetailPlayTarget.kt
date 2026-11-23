package moe.ditto.halo.screens

import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.browse.episodeTag

/** What the page plays: Resume or Play, the episode, time left and how far in. */
internal data class PlayTarget(
    val label: String,
    /** "34 MIN LEFT", when partway through. */
    val left: String?,
    /** 0..1 when partway through. */
    val progress: Float?,
    val enabled: Boolean,
)

/** The newest unfinished row of this title whose video it still lists; a film's own, which it never lists. */
internal fun resumeStateOf(meta: MetaDetail, watchStates: List<WatchState>?, itemId: String): WatchState? {
    val videoIds = meta.videos.mapTo(mutableSetOf()) { it.id }
    return watchStates.orEmpty()
        .filter { it.itemId == itemId && !it.watched && it.durationSec > 0 && it.positionSec > 0 }
        .filter { videoIds.isEmpty() || it.videoId in videoIds }
        .maxByOrNull { it.updatedAt }
}

internal fun playTargetOf(isSeries: Boolean, video: MetaVideo?, resume: WatchState?, targetState: WatchState?): PlayTarget {
    val partway = targetState?.takeIf { !it.watched && it.durationSec > 0 && it.positionSec > 0 }
    val verb = if (partway != null) "Resume" else "Play"
    val label = when {
        !isSeries -> verb
        video != null -> "$verb ${episodeTag(video)}"
        else -> "No episodes yet"
    }
    return PlayTarget(
        label = label,
        left = partway?.let { "${timeLeft(it.positionSec, it.durationSec)} LEFT" },
        progress = partway?.let { (it.positionSec / it.durationSec).toFloat() },
        enabled = !isSeries || video != null || resume != null,
    )
}
