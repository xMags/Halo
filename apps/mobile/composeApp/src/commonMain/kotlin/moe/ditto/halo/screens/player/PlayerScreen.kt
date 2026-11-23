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
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import moe.ditto.halo.NativePlayerSurface
import moe.ditto.halo.PlaybackHost
import moe.ditto.halo.SessionGraph
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
    /** Null when there is no list to pick from (a pasted link): the error overlay leaves the button out. */
    onPickAnotherSource: (() -> Unit)?,
    /** What is being played and what to call it; see [PlaybackContext]. */
    context: PlaybackContext,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val metrics = rememberPlayerMetrics()
    val controller = remember(scope) { PlayerScreenController(scope) }
    val session = rememberPlayerSession(
        graph = graph,
        playback = playback,
        scope = scope,
        controller = controller,
        context = context,
        onSelectEpisode = onSelectEpisode,
        onBack = onBack,
    )
    val state = session.state
    val item = session.item
    val qualityMedal = remember(state.videoFormat) { classifyVideoQuality(state.videoFormat) }
    // Held across the frames of one drag; null between gestures, and null
    // during one the platform could not give a starting value for.
    var dragAdjustment by remember { mutableStateOf<VerticalDragAdjustment?>(null) }
    var dragTarget by remember { mutableStateOf<VerticalDragTarget?>(null) }
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
    val ownState = session.ownState
    val playbackOpen = ownState && (state.status == PlaybackStatus.Playing || state.status == PlaybackStatus.Paused)
    LaunchedEffect(scrubFrames, controller.chromeVisible, playbackOpen) {
        if (controller.chromeVisible && playbackOpen) {
            scrubFrames.warm(state.positionSeconds)
        } else {
            scrubFrames.idle()
        }
    }

    val leave: () -> Unit = session::leave
    val upNext = session.upNext
    val addonSubtitles = session.addonSubtitles
    val episodes = session.episodes

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
        if (session.leaving) return@BackHandler
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
                seasonTitle = playerSeasonTitle(session.meta, context.videoId, context.showTitle),
                episodes = episodes,
                currentTag = episodes.firstOrNull { it.videoId == context.videoId }?.tag.orEmpty(),
                onClose = controller::closeEpisodeDrawer,
                onSelectEpisode = { episode ->
                    controller.closeEpisodeDrawer()
                    session.selectEpisode(episode.videoId)
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
                subtitleOutline = session.subtitleOutline,
                subtitleShadow = session.subtitleShadow,
                trackStyling = state.subtitleTrackStyling,
                selectedAddonSubtitleId = controller.selectedAddonSubtitleId,
                bundledSubtitleFonts = bundledSubtitleFonts,
                audioDelaySeconds = state.audioDelaySeconds,
                playbackRate = state.playbackRate,
                onSelectTab = controller::openRail,
                onSelectSubtitlePane = controller::selectSubtitlePane,
                onClose = controller::closeRail,
                addonSubtitles = addonSubtitles,
                addonSubtitlesFetching = session.addonSubtitlesFetching,
                subtitleLoadError = session.subtitleLoadError,
                preferredSubtitleLang = session.preferredSubtitleLang,
                expandedSubtitleLanguages = controller.expandedSubtitleLanguages,
                onToggleSubtitleLanguage = controller::toggleSubtitleLanguage,
                onSelectSubtitleTrack = session::selectSubtitleTrack,
                onSelectAddonSubtitle = session::selectAddonSubtitle,
                onSubtitleScaleChange = { scale -> scope.launch { playback.setSubtitleScale(scale) } },
                onSubtitleDelayChange = { seconds ->
                    scope.launch { playback.setSubtitleDelay(clampedDelay(seconds)) }
                },
                onSubtitleOutlineChange = session::changeSubtitleOutline,
                onSubtitleShadowChange = session::changeSubtitleShadow,
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
                onRetry = session::retry,
                onPickAnotherSource = onPickAnotherSource?.let { pick -> { session.leaveTo(pick) } },
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
