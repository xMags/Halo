package moe.ditto.halo.screens.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.ditto.halo.SignedInGraph
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.player.PlayerState
import moe.ditto.halo.browse.episodeTag
import moe.ditto.halo.browse.episodesIn
import moe.ditto.halo.browse.seasonLabel

/**
 * One episode as the drawer draws it.
 *
 * [progress] is the fraction watched, which is what the card's bar shows; a
 * finished episode reads as full regardless of where its last sample landed,
 * because a viewer who stopped in the credits has finished it.
 */
internal data class PlayerEpisode(
    val videoId: String,
    val tag: String,
    val name: String,
    val thumbnail: String?,
    val progress: Float,
    val downloaded: Boolean,
)

/** The episodes of the season the current video belongs to, with their progress. */
internal fun playerEpisodes(
    meta: MetaDetail?,
    currentVideoId: String,
    watchStates: List<WatchState>?,
): List<PlayerEpisode> {
    val videos = meta?.videos.orEmpty()
    if (videos.isEmpty()) return emptyList()

    val season = videos.firstOrNull { it.id == currentVideoId }?.season
    val progressByVideo = watchStates.orEmpty().associateBy { it.videoId }

    return episodesIn(videos, season).map { video ->
        PlayerEpisode(
            videoId = video.id,
            tag = episodeTag(video),
            name = video.displayTitle ?: episodeTag(video),
            thumbnail = video.thumbnail,
            progress = progressOf(progressByVideo[video.id]),
            // Nothing on this platform downloads yet, and a tick that always
            // showed would be a claim about the device that is not true.
            downloaded = false,
        )
    }
}

/**
 * What the drawer's header calls the season, or the title itself when the
 * addon numbers nothing.
 */
internal fun playerSeasonTitle(meta: MetaDetail?, currentVideoId: String, fallback: String): String {
    val season = meta?.videos.orEmpty().firstOrNull { it.id == currentVideoId }?.season
    return season?.let(::seasonLabel) ?: fallback
}

private fun progressOf(state: WatchState?): Float {
    if (state == null) return 0f
    if (state.watched) return 1f
    if (state.durationSec <= 0.0) return 0f
    return (state.positionSec / state.durationSec).coerceIn(0.0, 1.0).toFloat()
}

/**
 * The source to play an episode from without asking again.
 *
 * Staying inside the same binge group from the same addon is what makes an
 * episode change feel like turning a page: it is the same release, so the same
 * audio track, the same subtitle timing and the same quality. Anything else is
 * a different file that the viewer should get to choose, which is why the
 * caller falls back to the picker rather than to whatever came first.
 */
internal fun sameReleaseStream(
    candidates: List<AddonStreams>,
    addonId: String,
    bingeGroup: String?,
): Pair<AddonSource, Stream>? {
    if (bingeGroup == null) return null
    val group = candidates.firstOrNull { it.addon.id == addonId } ?: return null
    val stream = group.streams.firstOrNull { it.behaviorHints?.bingeGroup == bingeGroup && it.url != null }
        ?: return null
    return group.addon to stream
}

/** The next episode in the same season, or null at the end of it. */
internal fun nextEpisodeAfter(videos: List<MetaVideo>, currentVideoId: String): MetaVideo? {
    val current = videos.firstOrNull { it.id == currentVideoId } ?: return null
    val ordered = episodesIn(videos, current.season)
    val index = ordered.indexOfFirst { it.id == currentVideoId }
    if (index < 0) return null
    return ordered.getOrNull(index + 1)
}

/**
 * What choosing another episode leads to.
 *
 * Two outcomes rather than one, because only one of them can happen without
 * asking: an episode with the same release available plays straight away, and
 * anything else is a genuine choice between files that belongs to the viewer.
 */
internal sealed interface EpisodeChoice {
    data class Resolved(val context: PlaybackContext) : EpisodeChoice
    data class NeedsSource(
        val videoId: String,
        val episodeTag: String?,
        val episodeName: String?,
        val episodeThumbnail: String?,
    ) : EpisodeChoice
}

/**
 * Looks for the same release of [video] and, failing that, says the viewer has
 * to pick.
 *
 * A failed lookup is deliberately not an error state: the picker is a perfectly
 * good answer to "we could not find the same release", and it is the same
 * screen a viewer would have reached by choosing the episode from the title.
 */
internal suspend fun resolveEpisodePlayback(
    graph: SignedInGraph,
    current: PlaybackContext,
    video: MetaVideo,
): EpisodeChoice {
    val tag = episodeTag(video)
    val name = video.displayTitle?.takeIf { it != tag }
    val needsSource = EpisodeChoice.NeedsSource(video.id, tag, name, video.thumbnail)

    val results = try {
        graph.client.getStreams(current.type, video.id).results
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        return needsSource
    }

    val (addon, stream) = sameReleaseStream(results, current.addonId, current.bingeGroup) ?: return needsSource
    val resolved = episodePlaybackContext(current, video, stream, addon.id) ?: return needsSource
    return EpisodeChoice.Resolved(resolved)
}

/**
 * Records where the viewer has got to.
 *
 * The show's name rather than the episode's, because the continue-watching row
 * represents the show. The title poster travels with the playback route, while
 * a loaded episode metadata record can provide a fresher copy.
 * The repository decides what is too short or too early to be worth recording,
 * so this does not second-guess it.
 */
internal suspend fun reportProgress(
    graph: SignedInGraph,
    context: PlaybackContext,
    state: PlayerState,
    meta: MetaDetail?,
) {
    val duration = state.durationSeconds ?: return
    graph.watchStates.report(
        videoId = context.videoId,
        itemId = context.itemId,
        positionSec = state.positionSeconds,
        durationSec = duration,
        name = context.showTitle,
        poster = meta?.poster ?: context.poster,
    )
}

/**
 * Watch state is useful but never safety-critical. A failed report must not
 * cancel periodic sampling, prevent a pause from settling, or strand a player
 * on screen before its decoder can release the render surface.
 */
internal suspend fun reportProgressBestEffort(report: suspend () -> Unit) {
    try {
        report()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // The next periodic, pause, or exit sample gets another opportunity.
    }
}

/**
 * The exit order is load-bearing: report, decoder wind-down, then navigation.
 *
 * The report is bounded because it is the only step that talks to the network.
 * A stalled uplink must not hold the decoder open behind it: the viewer has
 * already asked to leave, the picture is still on screen until the wind-down
 * runs, and the back control does nothing for as long as this waits. Losing the
 * sample to a timeout costs one sampling interval of progress at worst, because
 * the periodic sampler has been writing all along.
 */
internal suspend fun windDownAndLeave(
    report: suspend () -> Unit,
    windDown: suspend () -> Unit,
    navigate: () -> Unit,
) {
    withTimeoutOrNull(ExitReportTimeoutMillis) { reportProgressBestEffort(report) }
    windDown()
    navigate()
}

/** Long enough for a healthy round trip, short enough not to feel like a hang. */
private const val ExitReportTimeoutMillis = 2_000L

/**
 * What follows the episode playing, ready to start.
 *
 * The server answers with the next video and, when it can, a stream from the
 * same addon in the same binge group. Null here means there is nothing to
 * autoplay, and every reason for that is the same to the caller: a film, the
 * last episode of a season, an addon that offers nothing matching, or a lookup
 * that failed. Autoplay is a convenience, so none of those is worth an error
 * state on top of a picture that has just ended.
 */
internal suspend fun nextEpisodePlayback(
    graph: SignedInGraph,
    current: PlaybackContext,
): PlaybackContext? {
    val result = try {
        graph.client.getNextEpisode(
            type = current.type,
            metaId = current.metaId,
            videoId = current.videoId,
            addonId = current.addonId,
            bingeGroup = current.bingeGroup,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        return null
    }

    val video = result.video ?: return null
    val stream = result.stream ?: return null
    return nextPlaybackContext(current, video, stream)
}

/**
 * Replaces every source-shaped field when advancing, while retaining the title
 * identity and the addon that the server matched within. Keeping even one hint
 * from the previous episode would hash, badge or restore subtitles against the
 * wrong file.
 */
internal fun nextPlaybackContext(
    current: PlaybackContext,
    video: MetaVideo,
    stream: Stream,
): PlaybackContext? = episodePlaybackContext(current, video, stream, current.addonId)

/** Shared propagation path for drawer selection and the Up Next response. */
internal fun episodePlaybackContext(
    current: PlaybackContext,
    video: MetaVideo,
    stream: Stream,
    addonId: String,
): PlaybackContext? {
    val url = stream.url ?: return null
    val tag = episodeTag(video)
    val hints = stream.behaviorHints
    return current.copy(
        url = url,
        videoId = video.id,
        episodeTag = tag,
        episodeName = video.displayTitle?.takeIf { it != tag },
        episodeThumbnail = video.thumbnail,
        addonId = addonId,
        bingeGroup = hints?.bingeGroup,
        filename = hints?.filename,
        videoSize = hints?.videoSize,
        videoHash = hints?.videoHash,
        streamName = stream.name,
        streamTitle = stream.title ?: stream.description,
    )
}
