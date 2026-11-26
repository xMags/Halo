package moe.ditto.halo.tv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.Stream
import moe.ditto.halo.browse.RankingDevice
import moe.ditto.halo.browse.SourceEntry
import moe.ditto.halo.browse.SourceFit
import moe.ditto.halo.browse.StartSpeed
import moe.ditto.halo.browse.resolveSources
import moe.ditto.halo.screens.StreamsContentState
import moe.ditto.halo.screens.player.PlaybackContext
import moe.ditto.halo.screens.streamsContentState
import moe.ditto.halo.sync.runtimeMinutes

/** At most this many automatic source changes for one video before the viewer is asked. */
internal const val MaxAutomaticSourceSwitches = 3

/**
 * The source to move on to after [failedUrl] turned out unplayable, or null
 * when there is none or [switchesUsed] has reached [MaxAutomaticSourceSwitches].
 *
 * [pool] is the sources page's own ranked pool, already without the formats
 * this TV cannot play, and the next is the best of it under Recommended that
 * has not failed and was not tried in this run. A source that must download
 * before it plays is passed over unless the failed one was such a source too
 * ([failedNeedsDownload]): the viewer chose to wait for that one, not for a
 * different file. A source whose name does not say its codec may be taken;
 * if it fails as well, it joins [tried] and costs one of the switches.
 *
 * Among those, a source that may buffer on this connection
 * ([SourceFit.OverBudget]) is taken only when no other is left: the rank
 * puts a cached file ahead of an uncached one whatever its fit, but a switch
 * the viewer did not ask for should not trade a format failure for stalls.
 */
internal fun nextFallbackSource(
    pool: List<SourceEntry>,
    failedUrl: String,
    failedNeedsDownload: Boolean,
    tried: Set<String>,
    switchesUsed: Int,
): SourceEntry? {
    if (switchesUsed >= MaxAutomaticSourceSwitches) return null
    val candidates = pool.sortedBy { it.rank }.filter { entry ->
        val url = entry.stream.url
        url != null && url != failedUrl && url !in tried &&
            (failedNeedsDownload || entry.speed != StartSpeed.NeedsDownload)
    }
    return candidates.firstOrNull { it.fit != SourceFit.OverBudget } ?: candidates.firstOrNull()
}

/**
 * Moves playback on by itself when an opened source turns out to be in a
 * format this TV cannot play, so the viewer is not asked to fix what the app
 * can. It asks the same cached answer the sources page showed, so no addon is
 * asked again unless that answer has expired.
 *
 * One run covers one video: the sources tried and the switches used are
 * forgotten when another video plays or the viewer picks a source
 * themselves ([reset]). Main thread only, like the screens that call it.
 */
internal class TvSourceFallback(
    private val graph: SessionGraph,
    /** The setting the running player was built with; see [tvVideoDecoders]. */
    private val hardwareDecodingAtStart: Boolean,
    /** The panel's shorter side in physical pixels, for ranking as the sources page does; 0 when unknown. */
    private val screenHeight: () -> Int,
) {
    private var videoKey: String? = null
    private val tried = HashSet<String>()
    private var switches = 0

    /**
     * True from an automatic switch until the next source opens or fails, so
     * the player that takes over can say why it is loading again.
     */
    var switching: Boolean by mutableStateOf(false)
        private set

    /** The viewer chose a source: a new run starts from it. */
    fun reset() {
        videoKey = null
        tried.clear()
        switches = 0
        switching = false
    }

    /** The source that took over has opened or failed. */
    fun settled() {
        switching = false
    }

    /**
     * Records [context]'s source as failed and returns the next one to play,
     * or null when the viewer has to choose.
     */
    suspend fun next(context: PlaybackContext): Pair<AddonSource, Stream>? {
        val key = "${context.type}/${context.videoId}"
        if (key != videoKey) {
            reset()
            videoKey = key
        }
        tried += context.url
        val query = withTimeoutOrNull(LookupTimeoutMs) {
            graph.browse.streams(context.type, context.videoId).first { it.value != null || it.error != null }
        }
        val answer = query?.let(::streamsContentState) as? StreamsContentState.Sources
        if (answer == null) {
            switching = false
            return null
        }
        // The runtime turns sizes into bitrates, so the fits match the sources
        // page; the title's details are normally cached by now, and without
        // them every bitrate is unknown and every source fits.
        val runtimeSeconds = withTimeoutOrNull(MetaLookupTimeoutMs) {
            graph.browse.meta(context.type, context.metaId).first { it.value != null || it.error != null }
        }?.value?.runtime?.let(::runtimeMinutes)?.let { it * 60.0 } ?: 0.0
        val resolve = resolveSources(
            answer.groups,
            answer.failures,
            runtimeSeconds,
            tvVideoDecoders(hardwareDecodingAtStart),
            device = RankingDevice(graph.lineSpeed.current(), screenHeight().toDouble()),
            annotatedFor = answer.rankedFor,
        )
        val chosen = nextFallbackSource(
            pool = resolve.pool,
            failedUrl = context.url,
            failedNeedsDownload = failedNeedsDownload(resolve.pool, context),
            tried = tried,
            switchesUsed = switches,
        )
        if (chosen == null) {
            switching = false
            return null
        }
        switches += 1
        switching = true
        return chosen.addon to chosen.stream
    }

    /**
     * Read off the failed source's row when the pool still has it; otherwise
     * from its name as the route kept it, where only a source the addon calls
     * cached plays without a download.
     */
    private fun failedNeedsDownload(pool: List<SourceEntry>, context: PlaybackContext): Boolean {
        pool.firstOrNull { it.stream.url == context.url }?.let { return it.speed == StartSpeed.NeedsDownload }
        return context.sourceInfo().cached != true
    }

    private companion object {
        /** Past this the cached answer is not coming, and the viewer is asked instead. */
        const val LookupTimeoutMs = 15_000L

        /** The runtime only refines the order, so it is not waited on for long. */
        const val MetaLookupTimeoutMs = 3_000L
    }
}
