package moe.ditto.halo.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource
import moe.ditto.halo.PlaybackHost
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.AddonSubtitles
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.SubtitleOutline
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.api.VideoFitMode
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerState
import moe.ditto.halo.sync.FreshProgress
import moe.ditto.halo.ui.languageCodeVariants

/**
 * How far the caption steps up while the chrome is showing. Enough to clear the
 * transport row, which is what lands on it.
 */
private const val ChromeCaptionLiftPercent = 12

/**
 * How long an appearance change has to settle before it is written. A drag
 * across the size slider is one decision, not forty.
 */
private const val SettingsWriteDelayMillis = 800L

/**
 * How often progress is recorded while watching. Frequent enough that an app
 * killed mid-episode resumes somewhere useful, rare enough that a two-hour film
 * is a couple of hundred writes rather than thousands.
 */
private const val WatchStateReportMillis = 30_000L

/** How long opening a video may wait for the history it resumes from. */
private const val ResumeHistoryWaitMillis = 5_000L

/**
 * How long hashing the source may hold up the subtitle restore. Hashing is two
 * range reads, normally well under a second; a host that stalls them must not
 * keep the remembered subtitle from coming back, so past this the source is
 * treated as unhashable and the name-based results stand.
 */
private const val FingerprintTimeoutMillis = 10_000L

/**
 * What a download's own subtitle is called in the engine's track list. Without
 * a title mpv would name it after its file on disk.
 */
private const val DownloadedSubtitleTitle = "Downloaded subtitle"

/**
 * One item's playback, everything about it that is not drawn: loading it and
 * putting it back where it was left, its subtitles (the addon search, the
 * remembered choice, the look), progress reports, the next episode and the
 * up-next claim, the retry, and the wind-down before every way out.
 *
 * The touch player ([PlayerScreen]) and the TV player draw different controls
 * over the same session, so these rules exist once. Built by
 * [rememberPlayerSession]; the values it reads from playback and the caches are
 * refreshed on each composition of the screen that holds it.
 */
@Stable
internal class PlayerSession internal constructor(
    private val graph: SessionGraph,
    private val playback: PlaybackHost,
    private val scope: CoroutineScope,
    private val controller: PlayerScreenController,
    val context: PlaybackContext,
    val item: MediaItem,
    private val onSelectEpisode: (EpisodeChoice) -> Unit,
    private val onBack: () -> Unit,
    /** Whether a video opens where it was left; false starts every video from the beginning. */
    private val resumes: Boolean = true,
) {
    /** The engine's state, as of the latest composition. */
    var state: PlayerState = playback.state.value
        internal set

    /** True once a way out has been taken; every later one is a no-op. */
    var leaving by mutableStateOf(false)
        private set

    // The synced outline and shadow as the rail shows them; read from the
    // settings when each item starts. Unset reads as the desktop shows it.
    var subtitleOutline by mutableStateOf(SubtitleOutline.Normal)
        internal set
    var subtitleShadow by mutableStateOf(true)
        internal set

    var subtitleLoadError by mutableStateOf<String?>(null)
        internal set

    /** What follows this episode, once looked up; null when nothing does. */
    var upNext by mutableStateOf<PlaybackContext?>(null)
        internal set

    var addonSubtitles: List<AddonSubtitleOption> = emptyList()
        internal set
    var addonSubtitlesFetching: Boolean = false
        internal set
    var preferredSubtitleLang: String? = null
        internal set

    /** The title's full record, for an episode's season; null for a film. */
    var meta: MetaDetail? = null
        internal set
    var episodes: List<PlayerEpisode> = emptyList()
        internal set

    internal val externalSubtitleSelection = ExternalSubtitleSelectionCoordinator()
    internal var externalSubtitleJob: Job? = null
    internal var openFollowUps: Job? = null

    /**
     * False while the resume place may still be corrected by the server's
     * answer (at most ResumeHistoryWaitMillis). No progress is reported until
     * then: a report of the cached place, stamped now, would overwrite newer
     * progress from another device that the answer was about to bring.
     */
    internal var resumeSettled by mutableStateOf(true)

    // Applied once the engine has opened this file and so reported its own
    // tracks (see [subtitleSelectionMayRun]), because the choice is between
    // those and the addon results and both have to be known to pick between
    // them. Re-running on a later track list would fight the viewer, so the
    // guard is a claim rather than a comparison.
    internal var selectionApplied by mutableStateOf(false)

    // Bumped when a retry hands the claim back, so the selection runs again
    // even if nothing else it watches has changed by then.
    internal var selectionRound by mutableStateOf(0)

    /** True when the engine's state describes this item rather than whatever played before. */
    val ownState: Boolean get() = state.current == item

    /**
     * Loads this item, then, once its file is open, puts it back where it was
     * left and adds a download's own subtitle. The first open and the error
     * card's retry both go through here, because a reload starts the file from
     * the top with none of its added subtitles. [onOpened] runs after those,
     * still only once the file is open.
     *
     * The follow-ups are launched into the caller's scope so that they never
     * hold up what the caller does next.
     */
    internal suspend fun CoroutineScope.openItem(onOpened: () -> Unit = {}) {
        openFollowUps?.cancel()
        playback.play(item)
        openFollowUps = launch {
            // Back to where this video was left, once its file is open. Playback
            // is not held for it: the cached place is used at once, and the
            // server is asked meanwhile, within a bound. Its answer moves the
            // video only when it differs and the viewer has not moved it
            // since. A stream of unknown length opens with no duration, which
            // resumePosition accepts.
            if (context.recordsHistory && resumes) {
                resumeSettled = false
                launch {
                    try {
                        val cached = graph.watchStates.progressFor(context.videoId)
                        val fresh = async { graph.watchStates.freshProgressFor(context.videoId, ResumeHistoryWaitMillis) }
                        val opened = playback.state.first { it.current == item && it.opened }
                        val placed = resumePosition(cached, opened.durationSeconds)
                        placed?.let { playback.seekTo(it) }
                        val placedAt = TimeSource.Monotonic.markNow()
                        val answer = fresh.await() as? FreshProgress.Answered ?: return@launch
                        val target = resumePosition(answer.state, opened.durationSeconds)
                        if (target == placed) return@launch
                        val now = playback.state.value
                        if (now.current != item) return@launch
                        if (!stillAtResumePoint(placed ?: 0.0, placedAt.elapsedNow().inWholeMilliseconds, now.positionSeconds, now.playbackRate)) {
                            return@launch
                        }
                        playback.seekTo(target ?: 0.0)
                    } finally {
                        resumeSettled = true
                    }
                }
            }
            // A downloaded video carries its subtitle beside it, added directly
            // rather than through the addon path: the file is already here, and
            // the network this would otherwise ask may not be. Only once the file
            // is open (the engine said so; a pause report alone can mark it
            // playing mid-load), because mpv refuses a subtitle while a load is
            // pending and nothing reports that refusal.
            playback.state.first { it.current == item && it.opened }
            context.localSubtitlePath?.let { path ->
                playback.addSubtitle(path, title = DownloadedSubtitleTitle, lang = context.localSubtitleLang)
            }
            onOpened()
        }
    }

    /**
     * Leaving is asynchronous on purpose, and every way out goes through here.
     *
     * The engine is wound down first and the navigation happens only once that
     * has finished. The screen is still on screen while it runs, which is the
     * one moment its render surface is guaranteed to still exist. Doing this
     * from a disposal callback instead cannot work: the surface is torn down
     * before those run.
     *
     * An episode change is a way out too. It replaces this screen rather than
     * stacking on it, so the engine has to be wound down for it exactly as it is
     * for back. Otherwise the decoder is still holding the surface Compose is
     * about to take away, which is the deadlock the bounded release exists for.
     *
     * Playback is paused rather than torn down. Teardown is terminal for the
     * presenter, and on iOS it shuts libmpv down for the rest of the process,
     * so a screen that tore down on the way out would play exactly once per
     * launch.
     */
    fun leaveTo(destination: () -> Unit) {
        if (leaving) return
        leaving = true
        scope.launch {
            // Before the wind-down: pausing moves nothing, but the state
            // read has to happen while the position is still the one the
            // viewer stopped at.
            windDownAndLeave(
                report = { if (resumeSettled) reportProgress(graph, context, playback.state.value, meta) },
                windDown = playback::windDownForExit,
                navigate = destination,
            )
        }
    }

    fun leave() = leaveTo(onBack)

    /** One of the file's own subtitle tracks, or none; a deliberate choice, so it is remembered. */
    fun selectSubtitleTrack(id: String?) {
        externalSubtitleSelection.supersede()
        externalSubtitleJob?.cancel()
        subtitleLoadError = null
        controller.selectAddonSubtitle(null)
        scope.launch { playback.selectSubtitleTrack(id) }
        // Only a deliberate choice is remembered. Restoring one is
        // not a new decision, and writing it back would let a
        // preference reinforce itself into looking like one.
        val track = state.tracks.subtitles.firstOrNull { it.id == id }
        if (context.recordsHistory) graph.subtitleChoices.remember(
            videoId = context.videoId,
            itemId = context.itemId,
            choice = track?.let(::embeddedChoice) ?: OffChoice,
        )
    }

    /** An addon's subtitle: fetched, added to the engine, shown and remembered. */
    fun selectAddonSubtitle(option: AddonSubtitleOption) {
        val attempt = externalSubtitleSelection.begin()
        externalSubtitleJob?.cancel()
        subtitleLoadError = null
        externalSubtitleJob = scope.launch {
            try {
                externalSubtitleSelection.load(
                    attempt = attempt,
                    resolve = {
                        graph.subtitleFiles.resolve(
                            identity = subtitleCacheIdentity(context, option),
                            sourceUrl = option.url,
                        )
                    },
                    addToPlayer = { path ->
                        playback.addSubtitle(path, title = option.id, lang = option.lang)
                    },
                    onLoaded = {
                        controller.selectAddonSubtitle(option.id)
                        if (context.recordsHistory) graph.subtitleChoices.remember(
                            videoId = context.videoId,
                            itemId = context.itemId,
                            choice = externalChoice(option),
                        )
                    },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (attempt == externalSubtitleSelection.currentAttempt()) {
                    subtitleLoadError = subtitleLoadErrorMessage(failure)
                }
            }
        }
    }

    fun changeSubtitleOutline(outline: SubtitleOutline) {
        subtitleOutline = outline
        scope.launch {
            playback.setSubtitleOutline(outlineWidthPixels(outline))
            saveSubtitleLooks { it.withSubtitleOutline(outline) }
        }
    }

    fun changeSubtitleShadow(shadow: Boolean) {
        subtitleShadow = shadow
        scope.launch {
            playback.setSubtitleShadow(shadowOffsetPixels(shadow))
            saveSubtitleLooks { it.withSubtitleShadow(shadow) }
        }
    }

    // Saved at once rather than debounced like the size slider: each is one
    // tap. A failed save leaves the look applied for this session only.
    private suspend fun saveSubtitleLooks(patch: (UserSettings) -> UserSettings) {
        try {
            graph.settings.update(patch)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
    }

    /** The error card's retry: the same open as the first one, from the top. */
    fun retry() {
        // The reload drops every subtitle the failed one had, so
        // whatever was being added or shown from an addon is over.
        externalSubtitleSelection.supersede()
        externalSubtitleJob?.cancel()
        subtitleLoadError = null
        controller.selectAddonSubtitle(null)
        scope.launch {
            openItem(
                // Handed back only once the file is open again: the
                // addon results are already in, so a claim handed
                // back at once would choose while the load is
                // pending, before the file's own tracks exist and
                // when mpv would refuse an addon subtitle.
                onOpened = {
                    selectionApplied = false
                    selectionRound += 1
                },
            )
        }
    }

    /** Plays the next episode now, as the up-next card does; false when nothing follows. */
    fun playNext(): Boolean {
        val next = upNext ?: return false
        leaveTo { onSelectEpisode(EpisodeChoice.Resolved(next)) }
        return true
    }

    /** Another episode of this title, from the episode list. */
    fun selectEpisode(videoId: String) {
        if (videoId == context.videoId) return
        val video = meta?.videos?.firstOrNull { it.id == videoId } ?: return
        // Resolved first, wound down second: the lookup needs the
        // engine's own position, and a second tap while it is in
        // flight is absorbed by the exit's single claim.
        scope.launch {
            val choice = resolveEpisodePlayback(graph, context, video)
            leaveTo { onSelectEpisode(choice) }
        }
    }
}

/**
 * Builds the [PlayerSession] for [context] and runs its effects for as long as
 * the calling screen is composed. [controller] is the screen's own: the session
 * tells it about pauses, the up-next card and the addon subtitle shown.
 */
@Composable
internal fun rememberPlayerSession(
    graph: SessionGraph,
    playback: PlaybackHost,
    scope: CoroutineScope,
    controller: PlayerScreenController,
    context: PlaybackContext,
    onSelectEpisode: (EpisodeChoice) -> Unit,
    onBack: () -> Unit,
    /** How far the caption steps up while the chrome shows, in percent of the picture. */
    captionLiftPercent: Int = ChromeCaptionLiftPercent,
    /** Whether videos open where they were left (a TV setting; the phone always resumes). */
    resumes: Boolean = true,
): PlayerSession {
    val state by playback.state.collectAsState()
    // The video is the item, so the video id names it. The URL is one way to
    // reach that item and not what it is. Two sources for the same episode are
    // the same thing being watched, which is what the slices reporting on the
    // session have to agree on.
    val item = remember(context) {
        MediaItem(
            // A pasted link has no video id; the link is all there is to name it by.
            id = if (context.recordsHistory) context.videoId else context.url,
            title = context.displayTitle,
            url = context.url,
            headers = if (context.isDownload) emptyMap() else context.requestHeaders,
        )
    }
    val session = remember(item) {
        PlayerSession(graph, playback, scope, controller, context, item, onSelectEpisode, onBack, resumes)
    }
    session.state = state
    var fitModeRestored by remember(item) { mutableStateOf(false) }
    val ownState = state.current == item

    // Stored appearance first, then the source: applying it afterwards would
    // show the first caption in the wrong size and correct it a frame later.
    // The write-back is debounced because the size slider emits continuously
    // and every settings write is a whole-document PUT.
    LaunchedEffect(item) {
        playback.setVideoFillsScreen(graph.videoFitMode.current() == VideoFitMode.Cover)
        fitModeRestored = true
        val settings = graph.settings.current()
        val stored = subtitleStyleOf(settings)
        session.subtitleOutline = settings.subtitleOutline ?: SubtitleOutline.Normal
        session.subtitleShadow = settings.subtitleShadow != false
        playback.applySubtitleStyle(stored)
        // Before the source, like the appearance: the engine picks the audio
        // track as the file opens, so the preferred language plays from the
        // first frame instead of the file's default one.
        playback.setPreferredAudioLanguages(languageCodeVariants(settings.preferredAudioLang))
        with(session) { openItem() }

        val storedPreference = SubtitlePreference(stored.scale, stored.font)
        playback.state
            .map { SubtitlePreference(it.subtitleScale, it.subtitleFont) }
            .distinctUntilChanged()
            .debounce(SettingsWriteDelayMillis)
            .collect { preference ->
                if (preference == storedPreference) return@collect
                graph.settings.update { it.withSubtitlePreference(preference) }
            }
    }

    // Fit mode is written back the moment it changes rather than debounced:
    // unlike the size slider it is one decision per gesture, not a stream.
    LaunchedEffect(item, fitModeRestored) {
        if (!fitModeRestored) return@LaunchedEffect
        var persisted = graph.videoFitMode.current()
        playback.state
            .map { it.videoFillsScreen }
            .distinctUntilChanged()
            .collect { fills ->
                val mode = if (fills) VideoFitMode.Cover else VideoFitMode.Contain
                if (mode == persisted) return@collect
                graph.videoFitMode.update(mode)
                persisted = mode
            }
    }

    // Addon subtitles, matched to this exact file when the source can be
    // hashed. The hash comes from the addon's own hints when it supplied them,
    // and otherwise from two range requests over the source; either way it is
    // best effort, and a failure falls back to a name-based search rather than
    // to no subtitles.
    var fingerprint by remember(item) { mutableStateOf(context.fingerprint()) }
    // Whether the fingerprint is as good as it is going to get: given, not
    // needed, or asked for and answered (with a hash or with nothing). The
    // subtitle restore waits on it; see [subtitleSelectionMayRun].
    var fingerprintSettled by remember(item) {
        mutableStateOf(!context.asksAddons || fingerprint != null)
    }
    LaunchedEffect(item) {
        // Nothing to hash for a file already on the device, and nobody to ask
        // about it: the point of a download is that it plays with no network.
        // A fingerprint the addon supplied needs no hashing either.
        if (fingerprintSettled) return@LaunchedEffect
        // The size the addon declared is handed over rather than rediscovered:
        // hashing already costs two range reads against the same host the engine
        // is streaming from, and asking it for a number we were given is two
        // more requests that a rate-limited resolver counts against playback.
        fingerprint = withTimeoutOrNull(FingerprintTimeoutMillis) {
            graph.videoHasher.fingerprint(context.url, context.videoSize, context.requestHeaders)
        }
        fingerprintSettled = true
    }
    // A fingerprint arriving restarts the query with the hash. The key block is
    // what makes the restart start from nothing: collectAsState keeps its value
    // across a change of flow, so without it the name-based answer would stand
    // in for the hash-matched one until that arrived, and the restore would
    // run on it.
    val addonSubtitleState by key(item, fingerprint) {
        remember {
            if (!context.asksAddons) return@remember flowOf(QueryState<List<AddonSubtitles>>())
            graph.browse.subtitles(
                type = context.type,
                videoId = context.videoId,
                videoHash = fingerprint?.hash,
                videoSize = fingerprint?.sizeBytes,
                filename = context.filename,
            )
        }.collectAsState(QueryState())
    }
    val addonSubtitles = remember(addonSubtitleState.value) {
        addonSubtitleOptions(addonSubtitleState.value.orEmpty())
    }
    session.addonSubtitles = addonSubtitles
    session.addonSubtitlesFetching = addonSubtitleState.isFetching

    // The standing language preference, read here only to order the rail's
    // languages. Applying it to a track is a separate decision, made once when
    // the file loads and never re-made from this value.
    val settingsState by remember(graph) { graph.settings.observe() }.collectAsState(QueryState())
    session.preferredSubtitleLang = settingsState.value?.preferredSubtitleLang

    // A download has no addon query; otherwise its first answer has to be in,
    // since the query starts out as an empty result that is not fetching yet.
    val addonSubtitlesKnown = !context.asksAddons || addonSubtitleState.value != null || addonSubtitleState.error != null
    LaunchedEffect(
        item,
        session.selectionRound,
        ownState,
        state.opened,
        state.tracks,
        fingerprintSettled,
        addonSubtitleState.isFetching,
        addonSubtitlesKnown,
    ) {
        val mayRun = subtitleSelectionMayRun(
            ownState = ownState,
            opened = ownState && state.opened,
            alreadyApplied = session.selectionApplied,
            fingerprintSettled = fingerprintSettled,
            addonSubtitlesFetching = addonSubtitleState.isFetching,
            addonSubtitlesKnown = addonSubtitlesKnown,
            hasEmbeddedSubtitles = state.tracks.subtitles.isNotEmpty(),
            hasAddonSubtitles = addonSubtitles.isNotEmpty(),
        )
        if (!mayRun) return@LaunchedEffect

        val selection = resolveSubtitleSelection(
            remembered = if (context.recordsHistory) graph.subtitleChoices.choiceFor(context.videoId, context.itemId) else null,
            tracks = state.tracks,
            addonSubtitles = addonSubtitles,
            preferredLang = graph.settings.current().preferredSubtitleLang,
        )
        // Claimed after the reads, not before them: reading settings suspends,
        // and the engine reporting its tracks again in that window cancels this
        // effect. A claim taken first would be kept by the cancelled run and
        // the restart would decline to do the work.
        session.selectionApplied = true
        val external = session.externalSubtitleSelection
        if (selection is SubtitleSelection.External) {
            val attempt = external.begin()
            try {
                external.load(
                    attempt = attempt,
                    resolve = {
                        graph.subtitleFiles.resolve(
                            identity = subtitleCacheIdentity(context, selection.option),
                            sourceUrl = selection.option.url,
                        )
                    },
                    addToPlayer = { path ->
                        playback.addSubtitle(path, title = selection.option.id, lang = selection.option.lang)
                    },
                    onLoaded = { controller.selectAddonSubtitle(selection.option.id) },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (attempt == external.currentAttempt()) {
                    session.subtitleLoadError = subtitleLoadErrorMessage(failure)
                }
            }
        } else {
            external.supersede()
            applySubtitleSelection(selection, playback, controller::selectAddonSubtitle)
        }
    }

    // The bottom bar sits exactly where captions do, so the caption steps up
    // out of its way while the chrome is up and drops back when it goes.
    LaunchedEffect(controller.chromeVisible) {
        playback.setSubtitleLift(if (controller.chromeVisible) captionLiftPercent else 0)
    }

    // The season this episode belongs to, for the episode list. Films have no
    // list, so nothing is fetched for them.
    val metaState by remember(context) {
        graph.browse.meta(context.type, context.metaId, enabled = context.isEpisode)
    }.collectAsState(QueryState())
    val watchStateList by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())
    val episodes = remember(metaState.value, watchStateList.value, context.videoId) {
        playerEpisodes(metaState.value, context.videoId, watchStateList.value)
    }
    session.meta = metaState.value
    session.episodes = episodes

    // Progress is reported while watching, when it pauses, and once on the way
    // out. The periodic sample is what survives the app being killed; the pause
    // and exit samples are what make the common cases exact rather than up to
    // half a minute stale.
    LaunchedEffect(item) {
        while (true) {
            delay(WatchStateReportMillis)
            if (!session.resumeSettled) continue
            reportProgressBestEffort {
                reportProgress(graph, context, playback.state.value, session.meta)
            }
        }
    }
    LaunchedEffect(item, ownState, state.status) {
        if (ownState && state.status != PlaybackStatus.Playing && session.resumeSettled) {
            reportProgressBestEffort {
                reportProgress(graph, context, playback.state.value, session.meta)
            }
        }
    }

    // What follows this episode, looked up while it is still playing so the
    // card has something to offer the moment it ends. Null covers every "there
    // is nothing to autoplay" case at once: films, the last episode, and a
    // lookup that failed.
    LaunchedEffect(item) {
        if (!context.isEpisode) return@LaunchedEffect
        // A download plays with no network, and the next episode found by the
        // lookup would stream; so a download offers nothing to follow it.
        if (context.isDownload) return@LaunchedEffect
        session.upNext = nextEpisodePlayback(graph, context)
    }

    // The engine reaching the end is what raises the card; the countdown, both
    // buttons and the advance all run through the controller's single claim, so
    // whichever happens first wins and the rest become no-ops.
    // Leaving the end (a seek back) withdraws the card and lets a later end
    // raise it again.
    var wasEnded by remember(item) { mutableStateOf(false) }
    LaunchedEffect(ownState, state.status, session.upNext) {
        if (!ownState) return@LaunchedEffect
        val ended = state.status == PlaybackStatus.Ended
        if (wasEnded && !ended) controller.onLeftEnd()
        wasEnded = ended
        val next = session.upNext ?: return@LaunchedEffect
        if (!ended) return@LaunchedEffect
        controller.showUpNext { session.leaveTo { onSelectEpisode(EpisodeChoice.Resolved(next)) } }
    }

    DisposableEffect(item) {
        onDispose {
            session.externalSubtitleJob?.cancel()
            session.externalSubtitleSelection.supersede()
        }
    }

    // The chrome starts up and hides itself once playback settles; pausing
    // suppresses that, which is why the controller is told about it rather than
    // reading playback state itself.
    LaunchedEffect(Unit) { controller.armAutoHide() }
    LaunchedEffect(state.status) {
        controller.onPausedChanged(state.status != PlaybackStatus.Playing)
    }

    // Choosing an episode is a stop in watching, so the picture waits. Keyed to
    // the list rather than to whatever opened it, so every way of opening it
    // pauses, and closing it leaves playback paused until the viewer resumes it.
    LaunchedEffect(controller.episodeDrawerOpen) {
        if (controller.episodeDrawerOpen) playback.setPaused(true)
    }

    return session
}
