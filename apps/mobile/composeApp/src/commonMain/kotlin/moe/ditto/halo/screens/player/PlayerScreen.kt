package moe.ditto.halo.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.unit.dp
import moe.ditto.halo.NativePlayerSurface
import moe.ditto.halo.PlaybackHost
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.AddonSubtitles
import moe.ditto.halo.api.SubtitleOutline
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.api.VideoFitMode
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerState
import moe.ditto.halo.player.PlayerSystemPort
import moe.ditto.halo.player.classifyVideoQuality
import moe.ditto.halo.player.PlayerTracks
import moe.ditto.halo.player.VideoFrameSource
import moe.ditto.halo.ui.QualityMedal
import moe.ditto.halo.ui.QualityMedalWidth
import moe.ditto.halo.ui.HaloColors

/** How far the bottom bar rises as it appears. */
private const val ChromeFadeMillis = 180
private const val BottomBarRiseMillis = 220
private const val RailEnterMillis = 220
private const val DrawerEnterMillis = 240
private const val SeekStepSeconds = 10.0

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
 * The playback screen.
 *
 * Everything except the video is chrome layered over a native render surface,
 * in the z-order the design specifies: the surface itself, then the scrims, then
 * the top bar, centre transport and bottom bar, which appear and disappear
 * together.
 *
 * Above the chrome sit the panels and the transient states, each a sibling
 * rather than a child of it, so the chrome's own auto-hide cannot take one of
 * them down with it.
 *
 * What is driven by the engine: position, duration, play state, seeking, the
 * audio and subtitle track lists and the subtitle styling, and the error card.
 * The naming and the badges come from [context], which the source picker
 * resolved before playback was entered. What is neither, and is waiting on a
 * capability rather than on this screen: playback rate, audio delay, fit mode,
 * the buffering figures, the gesture readout, the episode list and the up-next
 * countdown's contents. Those are reachable from the debug player scene harness
 * so they can be reviewed before the data behind them exists.
 */
// BackHandler is still marked experimental in Compose 1.11; the opt-in is
// scoped to this screen rather than turned on for the whole module, so a future
// signature change surfaces here and nowhere else.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerScreen(
    graph: SessionGraph,
    playback: PlaybackHost,
    surface: NativePlayerSurface,
    /** See [moe.ditto.halo.PlatformDependencies.bundledSubtitleFonts]. */
    bundledSubtitleFonts: Set<String>,
    /** Brightness, volume, orientation and the sleep timer. */
    system: PlayerSystemPort,
    /** Frames behind the scrub preview; see [VideoFrameSource]. */
    videoFrames: VideoFrameSource,
    /**
     * Where choosing another episode goes. Resolved here rather than by the
     * caller because finding the same release is a question about the source
     * that is playing, which only this screen holds.
     */
    onSelectEpisode: (EpisodeChoice) -> Unit,
    /** Opens the picker for [context] after the player has safely wound down. */
    onPickAnotherSource: () -> Unit,
    /** What is being played and what to call it; see [PlaybackContext]. */
    context: PlaybackContext,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by playback.state.collectAsState()
    val qualityMedal = remember(state.videoFormat) { classifyVideoQuality(state.videoFormat) }
    val scope = rememberCoroutineScope()
    val metrics = rememberPlayerMetrics()
    val controller = remember(scope) { PlayerScreenController(scope) }
    var leaving by remember { mutableStateOf(false) }
    // Held across the frames of one drag; null between gestures, and null
    // during one the platform could not give a starting value for.
    var dragAdjustment by remember { mutableStateOf<VerticalDragAdjustment?>(null) }
    var dragTarget by remember { mutableStateOf<VerticalDragTarget?>(null) }
    // The synced outline and shadow as the rail shows them; read from the
    // settings when each item starts. Unset reads as the desktop shows it.
    var subtitleOutline by remember { mutableStateOf(SubtitleOutline.Normal) }
    var subtitleShadow by remember { mutableStateOf(true) }

    // Saved at once rather than debounced like the size slider: each is one
    // tap. A failed save leaves the look applied for this session only.
    suspend fun saveSubtitleLooks(patch: (UserSettings) -> UserSettings) {
        try {
            graph.settings.update(patch)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
    }
    // The video is the item, so the video id names it. The URL is one way to
    // reach that item and not what it is. Two sources for the same episode are
    // the same thing being watched, which is what the slices reporting on the
    // session have to agree on.
    val item = remember(context) {
        MediaItem(id = context.videoId, title = context.displayTitle, url = context.url)
    }
    val externalSubtitleSelection = remember(item) { ExternalSubtitleSelectionCoordinator() }
    var externalSubtitleJob by remember(item) { mutableStateOf<Job?>(null) }
    var subtitleLoadError by remember(item) { mutableStateOf<String?>(null) }
    var fitModeRestored by remember(item) { mutableStateOf(false) }
    // Frames behind the scrub card, read out of band from playback and sized to
    // the card so nothing decodes a picture larger than what is shown. Keyed to
    // the item: changing episode must drop the previous source's frames rather
    // than leave them under the new one's timecodes.
    val density = LocalDensity.current
    val scrubFrames = remember(item, videoFrames, density) {
        ScrubPreviewFrames(
            source = videoFrames,
            url = context.url,
            frameWidthPx = with(density) { ScrubPreviewWidth.roundToPx() },
            frameHeightPx = with(density) { ScrubPreviewHeight.roundToPx() },
            scope = scope,
            local = context.isDownload,
        )
    }
    DisposableEffect(scrubFrames) {
        onDispose { scrubFrames.close() }
    }

    // The reader is warmed when the transport appears and let go when it
    // leaves, because its first frame costs about a second and every frame
    // after it about a tenth of that. Warmed on the first scrub instead, that
    // second lands inside the gesture it was meant to serve.
    //
    // Not before the engine has the file open: the warm-up decode reads the
    // same source playback is starting on, and racing it there would trade a
    // smooth first scrub for a stuttering first second.
    // The playback owner outlives this screen, so until this item's load is
    // published its state still describes whatever played before. Everything
    // here that acts on playback state for this item checks it is this item's.
    val ownState = state.current == item
    val playbackOpen = ownState && (state.status == PlaybackStatus.Playing || state.status == PlaybackStatus.Paused)
    LaunchedEffect(scrubFrames, controller.chromeVisible, playbackOpen) {
        if (controller.chromeVisible && playbackOpen) {
            scrubFrames.warm(state.positionSeconds)
        } else {
            scrubFrames.idle()
        }
    }

    // What waits for this item's file to be open, from the latest open. A
    // retry cancels it before loading again: after a failure the engine
    // reports nothing more for the failed load, so a wait left behind would
    // only ever fire on the retry's load, alongside the retry's own.
    var openFollowUps by remember(item) { mutableStateOf<Job?>(null) }

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
    suspend fun CoroutineScope.openItem(onOpened: () -> Unit = {}) {
        openFollowUps?.cancel()
        playback.play(item)
        openFollowUps = launch {
            // Back to where this video was left, once its file is open. Playback
            // is not held for it: the history is normally cached already, and
            // when it is not the wait for it is bounded. A stream of unknown
            // length opens with no duration, which resumePosition accepts.
            launch {
                val saved = graph.watchStates.loadedProgressFor(context.videoId, ResumeHistoryWaitMillis)
                    ?: return@launch
                val opened = playback.state.first { it.current == item && it.opened }
                resumePosition(saved, opened.durationSeconds)?.let { playback.seekTo(it) }
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

    // Stored appearance first, then the source: applying it afterwards would
    // show the first caption in the wrong size and correct it a frame later.
    // The write-back is debounced because the size slider emits continuously
    // and every settings write is a whole-document PUT.
    LaunchedEffect(item) {
        playback.setVideoFillsScreen(graph.videoFitMode.current() == VideoFitMode.Cover)
        fitModeRestored = true
        val settings = graph.settings.current()
        val stored = subtitleStyleOf(settings)
        subtitleOutline = settings.subtitleOutline ?: SubtitleOutline.Normal
        subtitleShadow = settings.subtitleShadow != false
        playback.applySubtitleStyle(stored)
        openItem()

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
        mutableStateOf(context.isDownload || fingerprint != null)
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
            graph.videoHasher.fingerprint(context.url, context.videoSize)
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
            if (context.isDownload) return@remember flowOf(QueryState<List<AddonSubtitles>>())
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

    // The standing language preference, read here only to order the rail's
    // languages. Applying it to a track is a separate decision, made once when
    // the file loads and never re-made from this value.
    val settingsState by remember(graph) { graph.settings.observe() }.collectAsState(QueryState())
    val preferredSubtitleLang = settingsState.value?.preferredSubtitleLang

    // Applied once the engine has opened this file and so reported its own
    // tracks (see [subtitleSelectionMayRun]), because the choice is between
    // those and the addon results and both have to be known to pick between
    // them. Re-running on a later track list would fight the viewer, so the
    // guard is a claim rather than a comparison.
    var selectionApplied by remember(item) { mutableStateOf(false) }
    // Bumped when a retry hands the claim back, so the selection runs again
    // even if nothing else it watches has changed by then.
    var selectionRound by remember(item) { mutableStateOf(0) }
    // A download has no addon query; otherwise its first answer has to be in,
    // since the query starts out as an empty result that is not fetching yet.
    val addonSubtitlesKnown = context.isDownload || addonSubtitleState.value != null || addonSubtitleState.error != null
    LaunchedEffect(
        item,
        selectionRound,
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
            alreadyApplied = selectionApplied,
            fingerprintSettled = fingerprintSettled,
            addonSubtitlesFetching = addonSubtitleState.isFetching,
            addonSubtitlesKnown = addonSubtitlesKnown,
            hasEmbeddedSubtitles = state.tracks.subtitles.isNotEmpty(),
            hasAddonSubtitles = addonSubtitles.isNotEmpty(),
        )
        if (!mayRun) return@LaunchedEffect

        val selection = resolveSubtitleSelection(
            remembered = graph.subtitleChoices.choiceFor(context.videoId, context.itemId),
            tracks = state.tracks,
            addonSubtitles = addonSubtitles,
            preferredLang = graph.settings.current().preferredSubtitleLang,
        )
        // Claimed after the reads, not before them: reading settings suspends,
        // and the engine reporting its tracks again in that window cancels this
        // effect. A claim taken first would be kept by the cancelled run and
        // the restart would decline to do the work.
        selectionApplied = true
        if (selection is SubtitleSelection.External) {
            val attempt = externalSubtitleSelection.begin()
            try {
                externalSubtitleSelection.load(
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
                if (attempt == externalSubtitleSelection.currentAttempt()) {
                    subtitleLoadError = subtitleLoadErrorMessage(failure)
                }
            }
        } else {
            externalSubtitleSelection.supersede()
            applySubtitleSelection(selection, playback, controller::selectAddonSubtitle)
        }
    }

    // The bottom bar sits exactly where captions do, so the caption steps up
    // out of its way while the chrome is up and drops back when it goes.
    LaunchedEffect(controller.chromeVisible) {
        playback.setSubtitleLift(if (controller.chromeVisible) ChromeCaptionLiftPercent else 0)
    }

    // The season this episode belongs to, for the drawer. Films have no
    // drawer, so nothing is fetched for them.
    val metaState by remember(context) {
        graph.browse.meta(context.type, context.metaId, enabled = context.isEpisode)
    }.collectAsState(QueryState())
    val watchStateList by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())
    val episodes = remember(metaState.value, watchStateList.value, context.videoId) {
        playerEpisodes(metaState.value, context.videoId, watchStateList.value)
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
     * for back — otherwise the decoder is still holding the surface Compose is
     * about to take away, which is the deadlock the bounded release exists for.
     *
     * Playback is paused rather than torn down. Teardown is terminal for the
     * presenter, and on iOS it shuts libmpv down for the rest of the process,
     * so a screen that tore down on the way out would play exactly once per
     * launch.
     */
    val leaveTo: (() -> Unit) -> Unit = { destination ->
        if (!leaving) {
            leaving = true
            scope.launch {
                // Before the wind-down: pausing moves nothing, but the state
                // read has to happen while the position is still the one the
                // viewer stopped at.
                windDownAndLeave(
                    report = { reportProgress(graph, context, playback.state.value, metaState.value) },
                    windDown = playback::windDownForExit,
                    navigate = destination,
                )
            }
        }
    }
    val leave: () -> Unit = { leaveTo(onBack) }

    // Progress is reported while watching, when it pauses, and once on the way
    // out. The periodic sample is what survives the app being killed; the pause
    // and exit samples are what make the common cases exact rather than up to
    // half a minute stale.
    LaunchedEffect(item) {
        while (true) {
            delay(WatchStateReportMillis)
            reportProgressBestEffort {
                reportProgress(graph, context, playback.state.value, metaState.value)
            }
        }
    }
    LaunchedEffect(item, ownState, state.status) {
        if (ownState && state.status != PlaybackStatus.Playing) {
            reportProgressBestEffort {
                reportProgress(graph, context, playback.state.value, metaState.value)
            }
        }
    }

    // What follows this episode, looked up while it is still playing so the
    // card has something to offer the moment it ends. Null covers every "there
    // is nothing to autoplay" case at once: films, the last episode, and a
    // lookup that failed.
    var upNext by remember(item) { mutableStateOf<PlaybackContext?>(null) }
    LaunchedEffect(item) {
        if (!context.isEpisode) return@LaunchedEffect
        // A download plays with no network, and the next episode found by the
        // lookup would stream; so a download offers nothing to follow it.
        if (context.isDownload) return@LaunchedEffect
        upNext = nextEpisodePlayback(graph, context)
    }

    // The engine reaching the end is what raises the card; the countdown, both
    // buttons and the advance all run through the controller's single claim, so
    // whichever happens first wins and the rest become no-ops.
    // Leaving the end (a seek back) withdraws the card and lets a later end
    // raise it again.
    var wasEnded by remember(item) { mutableStateOf(false) }
    LaunchedEffect(ownState, state.status, upNext) {
        if (!ownState) return@LaunchedEffect
        val ended = state.status == PlaybackStatus.Ended
        if (wasEnded && !ended) controller.onLeftEnd()
        wasEnded = ended
        val next = upNext ?: return@LaunchedEffect
        if (!ended) return@LaunchedEffect
        controller.showUpNext { leaveTo { onSelectEpisode(EpisodeChoice.Resolved(next)) } }
    }

    // Landscape, a display that will not sleep and no system bars, for as long
    // as this screen exists. Disposal rather than the back handler, because the
    // error card's exit and a system-initiated one leave the same way and would
    // otherwise strand the device in landscape with the screen pinned on and
    // the rest of the app with nothing to reach the clock by.
    DisposableEffect(system) {
        system.lockLandscape()
        system.keepScreenOn()
        system.hideSystemBars()
        onDispose {
            system.releaseLandscape()
            system.releaseScreenOn()
            system.releaseSystemBars()
            // The window's brightness override belongs to the player, not to
            // the app: leaving it set would dim every other screen.
            system.clearScreenBrightnessOverride()
        }
    }

    DisposableEffect(item) {
        onDispose {
            externalSubtitleJob?.cancel()
            externalSubtitleSelection.supersede()
        }
    }

    // The chrome starts up and hides itself once playback settles; pausing
    // suppresses that, which is why the controller is told about it rather than
    // reading playback state itself.
    LaunchedEffect(Unit) { controller.armAutoHide() }
    LaunchedEffect(state.status) {
        controller.onPausedChanged(state.status != PlaybackStatus.Playing)
    }

    // Android reports both ends of the native PiP transition through its
    // Activity callback. Entering keeps the video bare; returning restores the
    // full-screen chrome that was deliberately hidden before the handoff.
    LaunchedEffect(system) {
        system.pictureInPictureChanges.collect { active ->
            if (active) {
                controller.prepareForPictureInPicture()
            } else {
                controller.exitPictureInPicture()
            }
        }
    }

    // The system gesture and button take the same path as the button drawn
    // here; a back that skipped the wind-down would hang the app just as
    // reliably as no wind-down at all.
    //
    // Enabled for the whole life of the screen, including the wind-down. A
    // disabled handler hands the next press to the navigation host, which pops
    // this screen at once (taking the render surface away before the decoder
    // has let go of it) and then lets the deferred navigation pop the page
    // beneath it as well. A press while leaving is swallowed instead: the exit
    // already under way is the answer to it.
    BackHandler(enabled = true) {
        if (leaving) return@BackHandler
        leave()
    }

    val paused = state.status != PlaybackStatus.Playing

    // Which tab the rail was last showing, so its exit animation still has
    // something to draw after the rail itself has been closed.
    var lastRailTab by remember { mutableStateOf(RailTab.Subtitles) }
    LaunchedEffect(controller.rail) {
        controller.rail?.let { lastRailTab = it }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        // The engine paints the whole box; everything else sits on top of it.
        surface.Content(Modifier.fillMaxSize())

        // The video is also the control surface. This sits below the chrome, so
        // a tap on a button reaches the button rather than the video. A swipe
        // that starts on a system edge is the system's, so it adjusts nothing.
        val edgeGuard = rememberSystemEdgeTouchGuard()
        Box(
            Modifier
                .fillMaxSize()
                .ignoreTouchesFromSystemEdges(edgeGuard)
                .playerGestures(
                    onTap = controller::toggleChrome,
                    onDoubleTapLeft = {
                        scope.launch { playback.seekTo(state.positionSeconds - SeekStepSeconds) }
                    },
                    onDoubleTapRight = {
                        scope.launch { playback.seekTo(state.positionSeconds + SeekStepSeconds) }
                    },
                    onDragStart = { target, heightPx ->
                        val baseline = when (target) {
                            VerticalDragTarget.Brightness -> system.screenBrightness()
                            VerticalDragTarget.Volume -> system.volume()
                        }
                        // A platform that will not report the current value has
                        // nothing to adjust from, and guessing one would make
                        // the first movement of the drag a jump.
                        dragAdjustment = baseline?.let { VerticalDragAdjustment(it, heightPx) }
                        dragTarget = target
                        if (baseline != null) controller.showHud(target.hudKind(), baseline)
                    },
                    onDrag = { totalDragPx ->
                        val value = dragAdjustment?.advance(totalDragPx) ?: return@playerGestures
                        val target = dragTarget ?: return@playerGestures
                        when (target) {
                            VerticalDragTarget.Brightness -> system.setScreenBrightness(value)
                            VerticalDragTarget.Volume -> system.setVolume(value)
                        }
                        controller.showHud(target.hudKind(), value)
                    },
                    onDragEnd = {
                        dragAdjustment = null
                        dragTarget = null
                    },
                    onDragCancel = {
                        // A second finger turned the drag into a pinch, so
                        // what the first finger already moved is put back.
                        val adjustment = dragAdjustment
                        val target = dragTarget
                        if (adjustment != null && target != null) {
                            when (target) {
                                VerticalDragTarget.Brightness -> system.setScreenBrightness(adjustment.baseline)
                                VerticalDragTarget.Volume -> system.setVolume(adjustment.baseline)
                            }
                            controller.showHud(target.hudKind(), adjustment.baseline)
                        }
                        dragAdjustment = null
                        dragTarget = null
                    },
                    onFillScreenChange = { fills ->
                        scope.launch { playback.setVideoFillsScreen(fills) }
                    },
                ),
        )

        AnimatedVisibility(
            visible = controller.chromeVisible,
            enter = fadeIn(tween(ChromeFadeMillis)),
            exit = fadeOut(tween(ChromeFadeMillis)),
        ) {
            PlayerScrims()
        }

        AnimatedVisibility(
            visible = controller.chromeVisible,
            enter = fadeIn(tween(ChromeFadeMillis)),
            exit = fadeOut(tween(ChromeFadeMillis)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PlayerTopBar(
                metrics = metrics,
                showTitle = context.showTitle,
                episodeTag = context.episodeTag,
                episodeName = context.episodeName,
                reserveEnd = if (qualityMedal == null) 0.dp else QualityMedalWidth + 14.dp,
                locked = controller.locked,
                onBack = leave,
                // Absent where the platform has no native PiP window to hand
                // the video to. A refusal from a platform that does have one is
                // rare and recoverable: the chrome comes back and the viewer is
                // left where they were.
                onPictureInPicture = if (!system.supportsPictureInPicture) {
                    null
                } else {
                    {
                        controller.prepareForPictureInPicture()
                        if (!system.enterPictureInPicture()) controller.showChrome()
                    }
                },
                // Fit mode is the one utility control still waiting on
                // something that does not exist: an mpv panscan call.
                onToggleFit = {
                    controller.showChrome()
                    scope.launch { playback.setVideoFillsScreen(!state.videoFillsScreen) }
                },
                onToggleLock = controller::lock,
            )
        }

        AnimatedVisibility(
            visible = controller.chromeVisible,
            enter = fadeIn(tween(ChromeFadeMillis)),
            exit = fadeOut(tween(ChromeFadeMillis)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            PlayerCentreControls(
                metrics = metrics,
                paused = paused,
                onSeekBack = {
                    controller.onTransportUsed()
                    scope.launch { playback.seekTo(state.positionSeconds - SeekStepSeconds) }
                },
                onTogglePlay = {
                    controller.onTransportUsed()
                    scope.launch { playback.setPaused(!paused) }
                },
                onSeekForward = {
                    controller.onTransportUsed()
                    scope.launch { playback.seekTo(state.positionSeconds + SeekStepSeconds) }
                },
            )
        }

        AnimatedVisibility(
            visible = controller.chromeVisible,
            enter = fadeIn(tween(ChromeFadeMillis)) +
                slideInVertically(tween(BottomBarRiseMillis)) { height -> height / 8 },
            exit = fadeOut(tween(ChromeFadeMillis)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerBottomBar(
                metrics = metrics,
                chips = playerChips(
                    state = state,
                    controller = controller,
                    context = context,
                    subtitleChip = subtitleChipText(
                        tracks = state.tracks,
                        addonSubtitles = addonSubtitles,
                        selectedAddonId = controller.selectedAddonSubtitleId,
                    ),
                ),
                positionSeconds = state.positionSeconds,
                durationSeconds = state.durationSeconds,
                // The cache's own reach. Before the engine reports one there is
                // no lead to draw, and the bar's own floor keeps the fill from
                // ever sitting behind the playhead after a backwards seek.
                bufferedFraction = progressFraction(
                    state.bufferedPositionSeconds ?: 0.0,
                    state.durationSeconds,
                ),
                scrubFraction = controller.scrubFraction,
                scrubPreviewFrame = controller.scrubFraction?.let { fraction ->
                    scrubFrames.frameFor(secondsAt(fraction, state.durationSeconds))
                },
                // Requested as the finger moves rather than when it stops. A
                // frame takes long enough to decode that waiting for the end of
                // the gesture would put the picture on screen after the scrub it
                // was meant to aim.
                onScrubStart = { fraction ->
                    controller.beginScrub(fraction)
                    scrubFrames.request(secondsAt(fraction, state.durationSeconds))
                },
                onScrubMove = { fraction ->
                    controller.updateScrub(fraction)
                    scrubFrames.request(secondsAt(fraction, state.durationSeconds))
                },
                onScrubEnd = {
                    val committed = controller.endScrub()
                    if (committed != null) {
                        scope.launch { playback.seekTo(secondsAt(committed, state.durationSeconds)) }
                    }
                },
                onScrubCancel = controller::cancelScrub,
                onSeekToFraction = { fraction ->
                    controller.showChrome()
                    scope.launch { playback.seekTo(secondsAt(fraction, state.durationSeconds)) }
                },
            )
        }

        AnimatedVisibility(
            visible = controller.episodeDrawerOpen,
            enter = fadeIn(tween(DrawerEnterMillis)) +
                slideInVertically(tween(DrawerEnterMillis)) { height -> height / 6 },
            exit = fadeOut(tween(DrawerEnterMillis)) +
                slideOutVertically(tween(DrawerEnterMillis)) { height -> height / 6 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerEpisodeDrawer(
                metrics = metrics,
                seasonTitle = playerSeasonTitle(metaState.value, context.videoId, context.showTitle),
                episodes = episodes,
                currentTag = episodes.firstOrNull { it.videoId == context.videoId }?.tag.orEmpty(),
                onClose = controller::closeEpisodeDrawer,
                onSelectEpisode = { episode ->
                    controller.closeEpisodeDrawer()
                    if (episode.videoId == context.videoId) return@PlayerEpisodeDrawer
                    val video = metaState.value?.videos?.firstOrNull { it.id == episode.videoId }
                        ?: return@PlayerEpisodeDrawer
                    // Resolved first, wound down second: the lookup needs the
                    // engine's own position, and a second tap while it is in
                    // flight is absorbed by the exit's single claim.
                    scope.launch {
                        val choice = resolveEpisodePlayback(graph, context, video)
                        leaveTo { onSelectEpisode(choice) }
                    }
                },
            )
        }

        // Describes the decoded picture for the whole file, so it does not fade
        // with the controls; the top bar leaves its corner free instead. It
        // gives way to the rail, which takes that side of the screen.
        if (qualityMedal != null && controller.rail == null) {
            QualityMedal(
                medal = qualityMedal,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Centred on the top bar's 38dp row.
                    .padding(top = metrics.chromeTopPadding + 4.dp, end = metrics.hPad),
            )
        }

        // The rail covers the chrome, so it is a sibling above it rather than a
        // child of it: the chrome's own auto-hide must not be able to take the
        // rail down with it.
        AnimatedVisibility(
            visible = controller.rail != null,
            enter = fadeIn(tween(RailEnterMillis)) +
                slideInHorizontally(tween(RailEnterMillis)) { width -> width / 8 },
            exit = fadeOut(tween(RailEnterMillis)) +
                slideOutHorizontally(tween(RailEnterMillis)) { width -> width / 8 },
        ) {
            // Held across the exit animation so the panel does not blank out as
            // it slides away.
            val tab = controller.rail ?: lastRailTab
            PlayerRail(
                tab = tab,
                subtitlePane = controller.subtitlePane,
                metrics = metrics,
                tracks = state.tracks,
                subtitleScale = state.subtitleScale,
                subtitleDelaySeconds = state.subtitleDelaySeconds,
                subtitleFont = state.subtitleFont,
                subtitleOutline = subtitleOutline,
                subtitleShadow = subtitleShadow,
                trackStyling = state.subtitleTrackStyling,
                selectedAddonSubtitleId = controller.selectedAddonSubtitleId,
                bundledSubtitleFonts = bundledSubtitleFonts,
                audioDelaySeconds = state.audioDelaySeconds,
                playbackRate = state.playbackRate,
                onSelectTab = controller::openRail,
                onSelectSubtitlePane = controller::selectSubtitlePane,
                onClose = controller::closeRail,
                addonSubtitles = addonSubtitles,
                addonSubtitlesFetching = addonSubtitleState.isFetching,
                subtitleLoadError = subtitleLoadError,
                preferredSubtitleLang = preferredSubtitleLang,
                expandedSubtitleLanguages = controller.expandedSubtitleLanguages,
                onToggleSubtitleLanguage = controller::toggleSubtitleLanguage,
                onSelectSubtitleTrack = { id ->
                    externalSubtitleSelection.supersede()
                    externalSubtitleJob?.cancel()
                    subtitleLoadError = null
                    controller.selectAddonSubtitle(null)
                    scope.launch { playback.selectSubtitleTrack(id) }
                    // Only a deliberate choice is remembered. Restoring one is
                    // not a new decision, and writing it back would let a
                    // preference reinforce itself into looking like one.
                    val track = state.tracks.subtitles.firstOrNull { it.id == id }
                    graph.subtitleChoices.remember(
                        videoId = context.videoId,
                        itemId = context.itemId,
                        choice = track?.let(::embeddedChoice) ?: OffChoice,
                    )
                },
                onSelectAddonSubtitle = { option ->
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
                                    graph.subtitleChoices.remember(
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
                },
                onSubtitleScaleChange = { scale -> scope.launch { playback.setSubtitleScale(scale) } },
                onSubtitleDelayChange = { seconds ->
                    scope.launch { playback.setSubtitleDelay(clampedDelay(seconds)) }
                },
                onSubtitleOutlineChange = { outline ->
                    subtitleOutline = outline
                    scope.launch {
                        playback.setSubtitleOutline(outlineWidthPixels(outline))
                        saveSubtitleLooks { it.withSubtitleOutline(outline) }
                    }
                },
                onSubtitleShadowChange = { shadow ->
                    subtitleShadow = shadow
                    scope.launch {
                        playback.setSubtitleShadow(shadowOffsetPixels(shadow))
                        saveSubtitleLooks { it.withSubtitleShadow(shadow) }
                    }
                },
                onTrackStylingChange = { keep -> scope.launch { playback.setSubtitleTrackStyling(keep) } },
                onSubtitleFontChange = { font -> scope.launch { playback.setSubtitleFont(font) } },
                onSelectAudioTrack = { id -> scope.launch { playback.selectAudioTrack(id) } },
                onAudioDelayChange = { seconds ->
                    scope.launch { playback.setAudioDelay(clampedDelay(seconds)) }
                },
                onPlaybackRateChange = { rate -> scope.launch { playback.setPlaybackRate(rate) } },
            )
        }

        // A load in progress is not the same overlay as a stalled buffer: the
        // engine reports no cache figures until it has opened the source, so
        // there is no percentage to show yet.
        if (state.status == PlaybackStatus.Loading) {
            CircularProgressIndicator(
                color = HaloColors.Accent,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        state.buffering?.let { buffering ->
            BufferingOverlay(
                percent = buffering.percent,
                throughput = formatThroughput(buffering.bytesPerSecond),
                cached = formatCachedAhead(buffering.cachedSeconds),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        controller.hud?.let { hud ->
            GestureHudOverlay(hud)
        }

        controller.upNextSecondsRemaining?.let { remaining ->
            val next = upNext ?: return@let
            UpNextCard(
                metrics = metrics,
                episodeTag = next.episodeTag.orEmpty(),
                episodeName = next.episodeName ?: next.showTitle,
                episodeThumbnail = next.episodeThumbnail,
                secondsRemaining = remaining,
                totalSeconds = UpNextSeconds,
                onCancel = controller::dismissUpNext,
                onPlayNow = controller::advanceToNext,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        if (controller.locked) {
            LockedOverlay(
                metrics = metrics,
                pillVisible = controller.unlockPillVisible,
                onScrimTap = controller::revealUnlockPill,
                onUnlock = controller::unlock,
            )
        }

        if (state.status == PlaybackStatus.Failed) {
            PlaybackErrorCard(
                engineMessage = state.error,
                onRetry = {
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
                },
                onPickAnotherSource = { leaveTo(onPickAnotherSource) },
            )
        }
    }
}

private fun secondsAt(fraction: Float, durationSeconds: Double?): Double {
    val duration = durationSeconds ?: return 0.0
    if (!duration.isFinite() || duration <= 0.0) return 0.0
    return duration * fraction.coerceIn(0f, 1f)
}

/**
 * The state chips. Subtitles and audio read what the engine actually has
 * selected, and episodes what is being watched; speed is still a fixture and
 * goes live with the engine call behind it.
 *
 * A film gets three chips rather than four. The fourth opens a list of episodes,
 * and a film has none. An inert chip reading its own title would be a control
 * that does nothing, which is worse than an absent one.
 */
private fun playerChips(
    state: PlayerState,
    controller: PlayerScreenController,
    context: PlaybackContext,
    subtitleChip: SubtitleChipText,
): List<PlayerChip> = listOfNotNull(
    PlayerChip(
        kicker = "SUBTITLES",
        value = subtitleChip.value,
        badge = subtitleChip.badge,
        active = controller.rail == RailTab.Subtitles,
        onClick = { controller.openRail(RailTab.Subtitles) },
        width = SubtitleChipWidth,
    ),
    PlayerChip(
        kicker = "AUDIO",
        value = audioChipValue(state.tracks),
        active = controller.rail == RailTab.Audio,
        onClick = { controller.openRail(RailTab.Audio) },
    ),
    PlayerChip(
        kicker = "SPEED",
        value = formatRate(state.playbackRate),
        active = controller.rail == RailTab.Speed,
        onClick = { controller.openRail(RailTab.Speed) },
    ),
    context.episodeTag?.let { tag ->
        PlayerChip(
            kicker = "EPISODES",
            value = tag,
            active = controller.episodeDrawerOpen,
            onClick = controller::toggleEpisodeDrawer,
        )
    },
)

private fun audioChipValue(tracks: PlayerTracks): String {
    val selected = tracks.audio.firstOrNull { it.id == tracks.selectedAudioId }
        ?: tracks.audio.firstOrNull()
        ?: return "None"
    return listOfNotNull(selected.language ?: selected.label, selected.codec?.uppercase())
        .distinct()
        .joinToString(" · ")
}
