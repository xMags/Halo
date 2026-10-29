package moe.ditto.halo.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.api.movieVideoId
import moe.ditto.halo.api.playableFromPreview
import moe.ditto.halo.browse.ContinueEntry
import moe.ditto.halo.browse.HistoryEntry
import moe.ditto.halo.browse.historyVideoTag
import moe.ditto.halo.cache.HaloKey
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.shell.StreamsRoute
import moe.ditto.halo.sync.LibraryRepository
import moe.ditto.halo.sync.RemovalUnsupportedException
import moe.ditto.halo.sync.WatchMarkTarget
import moe.ditto.halo.sync.earlierEpisodes
import moe.ditto.halo.sync.runtimeMinutes
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.MenuAction
import moe.ditto.halo.ui.TitleMenu

/** Where a title menu's actions lead, supplied by the shell that owns navigation. */
internal class TitleNavigation(
    val openDetail: (MetaRef) -> Unit,
    val openSources: (StreamsRoute) -> Unit,
    val playMovie: (MetaCard) -> Unit,
    val playDownload: (DownloadEntry) -> Unit,
)

/** A question an action asks before it destroys something. Always drawn destructive. */
internal data class MenuConfirmation(
    val title: String,
    val subject: String?,
    val body: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
)

/**
 * The long-press menus for everything that stands for a title: a poster or the
 * featured banner, a Continue watching card, a History row, an episode row, a
 * download. They carry the desktop app's right-click menus
 * (`src/browse/titleMenu.ts`) as a sheet: the library as the header's toggle,
 * then a list with the main actions (play, watched, details) first and what
 * destroys something (removals, the download) last and in red.
 *
 * Menus are built when the press lands, from whatever the cache and the
 * downloads hold then, so an action whose state is not loaded yet (library,
 * watch history) is left out rather than guessed, and a long press never
 * fetches.
 */
@Stable
internal class TitleMenus(
    private val graph: SessionGraph,
    private val scope: CoroutineScope,
    private val navigation: TitleNavigation,
    private val present: (TitleMenu) -> Unit,
    private val confirm: (MenuConfirmation) -> Unit,
    private val report: (String) -> Unit,
) {
    /** Opens the menu for a poster or the featured banner. */
    fun openPoster(meta: MetaCard) = present(forPoster(meta))

    fun openContinue(entry: ContinueEntry) = present(forContinue(entry))

    fun openHistory(entry: HistoryEntry) = present(forHistory(entry))

    /** [play] is the episode row's own tap, so the menu plays it the same way. */
    fun openEpisode(meta: MetaDetail, video: MetaVideo, play: () -> Unit) = present(forEpisode(meta, video, play))

    fun openDownload(entry: DownloadEntry) = present(forDownload(entry))

    /** Plays a finished download, as the Downloads tab and "Play offline" do. */
    fun playDownload(entry: DownloadEntry) = navigation.playDownload(entry)

    /**
     * A film plays from here. A series needs its title page to pick an
     * episode, unless it was left partway, which it resumes.
     */
    private fun forPoster(meta: MetaCard): TitleMenu {
        val itemId = LibraryRepository.itemId(meta.type, meta.id)
        val details = detailsAction(meta.type, meta.id)
        if (meta.type == SeriesType) {
            val latest = latestUnfinished(itemId)
            val main = listOfNotNull(
                latest?.let { state ->
                    val tag = episodeTagOf(state.videoId, meta.id)
                    MenuAction(if (tag != null) "Resume $tag" else "Resume", HaloIcons.Play) {
                        navigation.openSources(resumeRoute(meta.type, meta.id, state.videoId, meta.name, meta.poster))
                    }
                },
                details,
            )
            return header(meta, subtitle = joinParts("SERIES", meta.releaseInfo), main = main, rows = emptyList())
        }

        val cached = cachedMeta(meta.type, meta.id)
        val videoId = (cached ?: meta).movieVideoId
        val playable = videoId != meta.id || meta.playableFromPreview
        val state = stateOf(videoId)
        val target = WatchMarkTarget(videoId, itemId, meta.name, meta.poster)
        val play = MenuAction(if (state != null && isResumable(state)) "Resume" else "Play", HaloIcons.Play) {
            if (playable) navigation.playMovie(cached ?: meta) else openDetails(meta.type, meta.id)
        }
        return header(
            meta,
            subtitle = joinParts("MOVIE", meta.releaseInfo, progressPart(state)),
            main = listOfNotNull(play, playOffline(videoId), watchedAction(target, cached?.runtime), details),
            rows = downloadActions(videoId),
        )
    }

    /** A Continue watching card: the show or film, by the video it was left partway through. */
    private fun forContinue(entry: ContinueEntry): TitleMenu {
        val meta = entry.meta
        val tag = episodeTagOf(entry.videoId, meta.id)
        val target = WatchMarkTarget(entry.videoId, entry.itemId, meta.name, meta.poster)
        val resume = MenuAction(if (tag != null) "Resume $tag" else "Resume", HaloIcons.Play) {
            navigation.openSources(resumeRoute(meta.type, meta.id, entry.videoId, meta.name, meta.poster))
        }
        val remove = graph.watchStates.cached()?.let {
            MenuAction("Remove from Continue watching", HaloIcons.Trash, danger = true) {
                removeTitle(entry.itemId, meta.name)
            }
        }
        return header(
            meta,
            subtitle = joinParts(tag ?: typeLabel(meta.type), progressPart(stateOf(entry.videoId))),
            main = listOfNotNull(
                resume,
                playOffline(entry.videoId),
                watchedAction(target, cachedMeta(meta.type, meta.id)?.runtime),
                detailsAction(meta.type, meta.id),
            ),
            rows = downloadActions(entry.videoId) + listOfNotNull(remove),
        )
    }

    /** A History row: one video played. Finished, it plays again from the start, which is what the player does with a watched row. */
    private fun forHistory(entry: HistoryEntry): TitleMenu {
        val tag = episodeTagOf(entry.videoId, entry.metaId)
        val meta = cachedMeta(entry.type, entry.metaId)
        val video = meta?.videos?.firstOrNull { it.id == entry.videoId }
        val play = MenuAction(
            label = when {
                entry.finished -> "Play again"
                else -> if (tag != null) "Resume $tag" else "Resume"
            },
            icon = if (entry.finished) HaloIcons.Refresh else HaloIcons.Play,
        ) { navigation.openSources(resumeRoute(entry.type, entry.metaId, entry.videoId, entry.name, entry.poster)) }
        val target = WatchMarkTarget(entry.videoId, entry.itemId, entry.name, entry.poster)
        return header(
            MetaPreview(id = entry.metaId, type = entry.type, name = entry.name, poster = entry.poster),
            subtitle = joinParts(tag ?: typeLabel(entry.type), progressPart(stateOf(entry.videoId))),
            main = listOfNotNull(
                play,
                playOffline(entry.videoId),
                watchedAction(target, meta?.runtime),
                if (meta != null && video != null) earlierAction(meta, entry.itemId, video) else null,
                detailsAction(entry.type, entry.metaId),
            ),
            rows = downloadActions(entry.videoId) + MenuAction("Remove from history", HaloIcons.Trash, danger = true) {
                removeVideo(entry.videoId)
            },
        )
    }

    /** An episode row on the title page. The header names the episode; its toggle saves the show. */
    private fun forEpisode(meta: MetaDetail, video: MetaVideo, play: () -> Unit): TitleMenu {
        val itemId = LibraryRepository.itemId(meta.type, meta.id)
        val state = stateOf(video.id)
        val tag = episodeTagOf(video.id, meta.id)
        val target = WatchMarkTarget(video.id, itemId, meta.name, meta.poster)
        return TitleMenu(
            title = video.displayTitle ?: tag ?: meta.name,
            subtitle = joinParts(meta.name.uppercase(), tag, progressPart(state)),
            poster = meta.poster,
            saved = isSaved(itemId),
            onToggleSaved = { toggleSaved(meta) },
            actions = listOfNotNull(
                MenuAction(if (state != null && isResumable(state)) "Resume" else "Play", HaloIcons.Play, onSelect = play),
                playOffline(video.id),
                watchedAction(target, meta.runtime),
                earlierAction(meta, itemId, video),
            ) + downloadActions(video.id),
        )
    }

    /** A download in the Downloads tab, finished or not. */
    private fun forDownload(entry: DownloadEntry): TitleMenu {
        val media = entry.media
        val transfer = when (entry.status) {
            DownloadStatus.Queued, DownloadStatus.Downloading ->
                MenuAction("Pause", HaloIcons.Pause) { act { graph.downloads.pause(entry.videoId) } }
            DownloadStatus.Paused, DownloadStatus.Failed ->
                MenuAction("Resume download", HaloIcons.Download) { act { graph.downloads.resume(entry.videoId) } }
            DownloadStatus.Done -> null
        }
        return header(
            MetaPreview(id = media.metaId, type = media.type, name = media.showTitle, poster = media.poster),
            subtitle = joinParts(media.episodeTag ?: typeLabel(media.type), statusPart(entry.status)),
            main = listOfNotNull(playOffline(entry.videoId), transfer, detailsAction(media.type, media.metaId)),
            rows = listOf(deleteDownloadAction(entry)),
        )
    }

    private fun header(meta: MetaCard, subtitle: String?, main: List<MenuAction>, rows: List<MenuAction>): TitleMenu {
        val itemId = LibraryRepository.itemId(meta.type, meta.id)
        return TitleMenu(
            title = meta.name,
            subtitle = subtitle,
            poster = meta.poster,
            saved = isSaved(itemId),
            onToggleSaved = { toggleSaved(meta) },
            actions = main + rows,
        )
    }

    private fun openDetails(type: String, metaId: String) = navigation.openDetail(MetaRef(type, metaId))

    private fun detailsAction(type: String, metaId: String) =
        MenuAction("Open details", HaloIcons.Info) { openDetails(type, metaId) }

    private fun cachedMeta(type: String, metaId: String): MetaDetail? =
        graph.cache.peekNow<MetaDetail>(HaloKey.Meta(type, metaId))

    private fun stateOf(videoId: String): WatchState? = graph.watchStates.cached()?.firstOrNull { it.videoId == videoId }

    /** The show's newest video left partway, which is what resuming it plays. */
    private fun latestUnfinished(itemId: String): WatchState? = graph.watchStates.cached().orEmpty()
        .filter { it.itemId == itemId && isResumable(it) }
        .maxByOrNull { it.updatedAt }

    private fun isResumable(state: WatchState) = !state.watched && state.durationSec > 0 && state.positionSec > 0

    /** "Play offline", re-read when chosen: the file may have been deleted while the menu was open. */
    private fun playOffline(videoId: String): MenuAction? {
        if (graph.downloads.entryFor(videoId)?.status != DownloadStatus.Done) return null
        return MenuAction("Play offline", HaloIcons.Download) {
            graph.downloads.entryFor(videoId)?.takeIf { it.status == DownloadStatus.Done }?.let(navigation.playDownload)
        }
    }

    /** The video's download, if there is one in any state. */
    private fun downloadActions(videoId: String): List<MenuAction> =
        listOfNotNull(graph.downloads.entryFor(videoId)?.let(::deleteDownloadAction))

    private fun deleteDownloadAction(entry: DownloadEntry): MenuAction {
        val finished = entry.status == DownloadStatus.Done
        return MenuAction(if (finished) "Delete from device" else "Cancel download", HaloIcons.Trash, danger = true) {
            confirm(
                MenuConfirmation(
                    title = if (finished) "Delete this download?" else "Cancel this download?",
                    subject = entry.media.displayTitle,
                    body = "The video and its subtitle are removed from this device. " +
                        "Nothing on the server changes, so it can be downloaded again.",
                    confirmLabel = if (finished) "Delete from device" else "Cancel download",
                    onConfirm = { act { graph.downloads.remove(entry.videoId) } },
                ),
            )
        }
    }

    /** Null while the library has not loaded, which leaves the header's toggle out. */
    private fun isSaved(itemId: String): Boolean? =
        graph.library.cached()?.any { it.id == itemId && !it.isRemoved }

    /** Adds or removes against the library as it is when chosen. */
    private fun toggleSaved(meta: MetaCard) {
        val itemId = LibraryRepository.itemId(meta.type, meta.id)
        val saved = isSaved(itemId) ?: return
        act { if (saved) graph.library.remove(itemId) else graph.library.add(meta) }
    }

    private fun watchedAction(target: WatchMarkTarget, runtime: String?): MenuAction? {
        val states = graph.watchStates.cached() ?: return null
        val watched = states.firstOrNull { it.videoId == target.videoId }?.watched == true
        return if (watched) {
            MenuAction("Mark unwatched", HaloIcons.Undo) { act { graph.watchStates.markUnwatched(target) } }
        } else {
            MenuAction("Mark watched", HaloIcons.Check) {
                act { graph.watchStates.markWatched(listOf(target), runtimeSeconds(runtime)) }
            }
        }
    }

    /** The unwatched episodes before [video], counted when the menu opens and re-read when chosen. */
    private fun earlierAction(meta: MetaDetail, itemId: String, video: MetaVideo): MenuAction? {
        val count = unwatchedEarlier(meta, video)?.size ?: return null
        if (count == 0) return null
        val label = "Mark $count earlier episode${if (count == 1) "" else "s"} as watched"
        return MenuAction(label, HaloIcons.DoneAll) {
            act {
                val targets = unwatchedEarlier(meta, video).orEmpty()
                    .map { WatchMarkTarget(it.id, itemId, meta.name, meta.poster) }
                graph.watchStates.markWatched(targets, runtimeSeconds(meta.runtime))
            }
        }
    }

    private fun unwatchedEarlier(meta: MetaDetail, video: MetaVideo): List<MetaVideo>? {
        val states = graph.watchStates.cached() ?: return null
        val watched = states.filter { it.watched }.mapTo(mutableSetOf()) { it.videoId }
        return earlierEpisodes(meta.videos, video).filterNot { it.id in watched }
    }

    /** Re-read when chosen, so a row removed since the menu opened is not written again. */
    private fun removeVideo(videoId: String) = act {
        graph.watchStates.remove(graph.watchStates.cached().orEmpty().filter { it.videoId == videoId })
    }

    /**
     * Clears the whole watch history, whatever the filter shows, after asking:
     * it forgets where every video stopped and what was watched, on every
     * device the history syncs to.
     */
    fun clearHistory() {
        val count = graph.watchStates.cached()?.size ?: return
        if (count == 0) return
        confirm(
            MenuConfirmation(
                title = "Clear your watch history?",
                subject = null,
                body = "This forgets all $count videos you have played: where you stopped, what you watched, " +
                    "and everything in Continue watching. It cannot be undone.",
                confirmLabel = "Clear history",
                onConfirm = { act { graph.watchStates.remove(graph.watchStates.cached().orEmpty()) } },
            ),
        )
    }

    /**
     * Takes a title off Continue watching by forgetting every video of it: a
     * show is offered by its latest unfinished episode, so forgetting that one
     * alone would only bring an earlier one back. Asks first when that is more
     * than the one video on the card.
     */
    private fun removeTitle(itemId: String, name: String) {
        val count = graph.watchStates.cached().orEmpty().count { it.itemId == itemId }
        val remove = { act { graph.watchStates.remove(graph.watchStates.cached().orEmpty().filter { it.itemId == itemId }) } }
        if (count <= 1) {
            remove()
            return
        }
        confirm(
            MenuConfirmation(
                title = "Remove from Continue watching?",
                subject = name,
                body = "This forgets all $count videos of it in your history: where you stopped and what you watched. " +
                    "Playing it again starts over.",
                confirmLabel = "Remove",
                onConfirm = { remove() },
            ),
        )
    }

    /** Runs a write, and says so when it fails: the title would otherwise just stay as it was. */
    private fun act(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { failure ->
                report(
                    if (failure is RemovalUnsupportedException) {
                        failure.message.orEmpty()
                    } else {
                        "That could not be saved. Check your connection and try again."
                    },
                )
            }
        }
    }

    private fun runtimeSeconds(runtime: String?): Double? = runtimeMinutes(runtime)?.let { it * 60.0 }

    private companion object {
        const val SeriesType = "series"

        fun typeLabel(type: String) = if (type == SeriesType) "SERIES" else "MOVIE"

        fun joinParts(vararg parts: String?): String? =
            parts.filterNot { it.isNullOrBlank() }.joinToString(" · ").ifEmpty { null }

        /** "WATCHED", "42 MIN LEFT", or nothing for a video never started. */
        fun progressPart(state: WatchState?): String? = when {
            state == null -> null
            state.watched -> "WATCHED"
            state.durationSec > 0 && state.positionSec > 0 -> "${timeLeft(state.positionSec, state.durationSec)} LEFT"
            else -> null
        }

        fun statusPart(status: DownloadStatus): String = when (status) {
            DownloadStatus.Queued -> "QUEUED"
            DownloadStatus.Downloading -> "DOWNLOADING"
            DownloadStatus.Paused -> "PAUSED"
            DownloadStatus.Failed -> "FAILED"
            DownloadStatus.Done -> "ON THIS DEVICE"
        }
    }
}

/** The menus of the shell around this screen; null outside one, where long presses do nothing. */
internal val LocalTitleMenus = staticCompositionLocalOf<TitleMenus?> { null }

/** The episode a video id names ("S01E02"), or null for a film or an unnumbered episode. */
internal fun episodeTagOf(videoId: String, metaId: String): String? =
    historyVideoTag(videoId, metaId).takeIf { it != "MOVIE" && it != "SERIES" }

/** The sources of a video left partway (or played before), to carry on or play it again. */
internal fun resumeRoute(type: String, metaId: String, videoId: String, name: String, poster: String?) = StreamsRoute(
    type = type,
    metaId = metaId,
    videoId = videoId,
    showTitle = name,
    episodeTag = episodeTagOf(videoId, metaId),
    poster = poster,
)
