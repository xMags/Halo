package moe.ditto.halo.tv.player

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import coil3.SingletonImageLoader
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.Stream
import moe.ditto.halo.browse.Medal
import moe.ditto.halo.NativePlayerSurface
import moe.ditto.halo.PlaybackHost
import moe.ditto.halo.player.PlaybackFailure
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.playerQualityMedal
import moe.ditto.halo.player.VideoFormat
import moe.ditto.halo.player.PlayerSystemPort
import moe.ditto.halo.screens.player.clampedDelay
import moe.ditto.halo.screens.player.EpisodeChoice
import moe.ditto.halo.screens.player.formatRate
import moe.ditto.halo.screens.player.formatTimecode
import moe.ditto.halo.screens.player.PlaybackContext
import moe.ditto.halo.screens.player.PlayerScreenController
import moe.ditto.halo.screens.player.PlayerSession
import moe.ditto.halo.screens.player.rememberPlayerSession
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.tv.kit.TvAction
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvIconButton
import moe.ditto.halo.tv.kit.TvKeyMap
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.QualityMedal
import moe.ditto.halo.ui.QualityMedalWidth

/**
 * Playback on a TV, driven by the remote. The same [PlayerSession] as the
 * touch player loads, resumes, reports progress, restores subtitles and
 * offers the next episode; this screen is only the remote's way of working it.
 *
 * With the controls hidden: select or play/pause pauses and shows them,
 * Left and Right seek (10 seconds a tap, larger steps while held, one seek at
 * the release), Up or Down shows them, Back leaves. With the controls shown,
 * focus starts on the seek bar, where Left and Right move a target and select
 * seeks to it; below it are play, Audio, Subtitles, Speed, Episodes and Next
 * episode. Back hides them. Media keys work throughout.
 *
 * Not here, by design: brightness and volume gestures, pinch to fill, the
 * lock, picture in picture, and scrub-preview pictures (a second decoder is a
 * risk on TV chips).
 */
@Composable
internal fun TvPlayerScreen(
    graph: SessionGraph,
    playback: PlaybackHost,
    surface: NativePlayerSurface,
    system: PlayerSystemPort,
    preferences: TvPlaybackPreferences,
    audioOutput: TvAudioOutput,
    /** Where a source found to be Dolby Vision this TV shows wrongly is remembered. */
    dolbyVision: TvDolbyVisionSources,
    context: PlaybackContext,
    onSelectEpisode: (EpisodeChoice) -> Unit,
    /** Null for a pasted link, which has no list to pick another source from. */
    onPickAnotherSource: (() -> Unit)?,
    /**
     * Moves on from a source whose picture this TV cannot play; null for a
     * pasted link, which has no other sources.
     */
    sourceFallback: TvSourceFallback?,
    /** Plays another source of the same video, in place of this player. */
    onPlaySource: (AddonSource, Stream) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { PlayerScreenController(scope) }
    val session = rememberPlayerSession(
        graph = graph,
        playback = playback,
        scope = scope,
        controller = controller,
        context = context,
        onSelectEpisode = onSelectEpisode,
        onBack = onBack,
        // The TV's controls stand taller than the phone's.
        captionLiftPercent = CaptionLiftPercent,
        resumes = preferences.resumePlayback,
    )
    TvPlaybackSystem(playback = playback, session = session, scope = scope, onPausedFromOutside = controller::showChrome)
    val state = session.state
    val paused = state.status != PlaybackStatus.Playing
    TvFrameRateMatching(frameRate = state.videoFormat.frameRate, enabled = preferences.matchFrameRate)
    // Surround formats the receiver takes as they are: decided for every file
    // as the player loads it (TvAudioOutput), and read back here only for this
    // item's own file; the previous one's lingers until the load lands.
    val passthroughState by audioOutput.passthroughState.collectAsState()
    val ownPassthrough = passthroughState?.takeIf { it.item == session.item }
    val spdifCodecs = ownPassthrough?.spdifCodecs
    val audioTrack = state.tracks.audio.firstOrNull { it.id == (state.tracks.selectedAudioId ?: state.tracks.audio.firstOrNull()?.id) }
    // Sound sent on untouched cannot be delayed or sped up here. The engine's
    // own decoder says whether it is, once this file has one.
    val passthrough = passthroughActive(
        decoder = ownPassthrough?.decoder?.takeIf { session.ownState && state.opened },
        spdifCodecs = spdifCodecs,
        codec = audioTrack?.codec,
    )
    // An output can accept a format and still never start it, which would
    // hold the first frame forever; this file's start is watched, and decoded
    // here if it does not come. Only with a track actually selected: none
    // selected plays no sound, and the first track is only a stand-in above.
    val watchStart = session.ownState && state.opened && passthrough && state.tracks.selectedAudioId != null
    LaunchedEffect(session.item, watchStart, spdifCodecs, audioTrack?.id) {
        if (!watchStart) return@LaunchedEffect
        val codecs = spdifCodecs ?: return@LaunchedEffect
        audioOutput.guardStart(codecs, audioTrack?.codec)
    }
    // The medal of the picture playing, once the decoder reports it, and none
    // before: it stays on screen the whole time, so a guess from the source's
    // name would be shown as fact. Only this item's own picture counts: the
    // previous file's lingers for a moment.
    val sourceInfo = remember(context) { context.sourceInfo() }
    val decodedFormat = if (session.ownState) state.videoFormat else VideoFormat()
    val medal = remember(decodedFormat, sourceInfo) {
        decodedFormat.takeIf { it.width > 0 && it.height > 0 }?.let { playerQualityMedal(it, sourceInfo) }
    }
    var panel by remember { mutableStateOf<TvPlayerPanel?>(null) }
    val seek = remember(session.item) { TvSeekTarget() }
    // The target shown in the bubble while a seek is built; mirrors [seek].
    var seekShown by remember { mutableStateOf<Double?>(null) }
    val rootFocus = remember { FocusRequester() }
    val barFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val upNextFocus = remember { FocusRequester() }

    // A picture this TV cannot play: the next playable source takes over by
    // itself, and only when there is none does the viewer see a card. Gated on
    // this item's own state, since the previous player's failure is still in
    // the shared state for a moment after this one takes over.
    val unsupported = session.ownState && state.status == PlaybackStatus.Failed &&
        state.failure == PlaybackFailure.UnsupportedVideo
    var unsupportedCard by remember { mutableStateOf(false) }
    LaunchedEffect(unsupported) {
        if (!unsupported) return@LaunchedEffect
        val next = sourceFallback?.next(context)
        if (next == null) {
            unsupportedCard = true
            return@LaunchedEffect
        }
        session.leaveTo { onPlaySource(next.first, next.second) }
    }
    val switchingSource = sourceFallback?.switching == true
    LaunchedEffect(session.ownState, state.opened, state.status) {
        val settled = state.opened || (state.status == PlaybackStatus.Failed && !unsupported)
        // A player on its way out to the next source has not settled anything:
        // its pause on the way out must not take the notice down early.
        if (session.ownState && settled && !session.leaving) sourceFallback?.settled()
    }

    // A Dolby Vision picture the engine was seen not to process on this TV
    // ([dolbyVisionShownWrongly]): remembered on this device, so its list
    // leaves it out from now on, and moved on from like any picture this TV
    // cannot play. With no other source it plays on as it is, back at full
    // size on the direct path, which is better than nothing. Where the engine
    // does process it, none of this happens and the file plays.
    val ownFormat = if (session.ownState && state.opened) state.videoFormat else VideoFormat()
    val wrongColours = dolbyVisionShownWrongly(ownFormat)
    LaunchedEffect(session.item, wrongColours) {
        val profile = ownFormat.dolbyVisionProfile
        if (!wrongColours || profile == null) return@LaunchedEffect
        dolbyVision.remember(context.url, profile)
        val next = sourceFallback?.next(context)
        if (next == null) {
            Log.i(LogTag, "Dolby Vision profile $profile is not processed on this TV; no other source, playing it as it is")
            dolbyVision.playOnAsIs()
            return@LaunchedEffect
        }
        Log.i(LogTag, "Dolby Vision profile $profile is not processed on this TV; moving to another source")
        session.leaveTo { onPlaySource(next.first, next.second) }
    }

    // The posters and backdrops of the screens behind sit in the image cache,
    // in the same memory the video's GPU work needs; on a 2 GB box that is
    // the margin between a picture and none. Nothing on screen now shows
    // them, and they load again from disk when the viewer comes back.
    val platformContext = LocalPlatformContext.current
    LaunchedEffect(Unit) { SingletonImageLoader.get(platformContext).memoryCache?.clear() }

    // A TV never sleeps mid-film, and no system bar shows over it.
    DisposableEffect(system) {
        system.keepScreenOn()
        system.hideSystemBars()
        onDispose {
            system.releaseScreenOn()
            system.releaseSystemBars()
        }
    }

    // Focus follows the controls: the seek bar while they show, the screen
    // itself (which reads the remote directly) while they are hidden. Not
    // keyed on the panel: a closing panel hands focus back to its button.
    LaunchedEffect(controller.chromeVisible, controller.upNextSecondsRemaining) {
        if (panel != null || controller.upNextSecondsRemaining != null) return@LaunchedEffect
        val target = if (controller.chromeVisible) barFocus else rootFocus
        // The controls fade in, and the bar can be placed a frame or two after
        // this runs; a request made before then fails without a word.
        withTimeoutOrNull(FocusClaimMs) {
            while (!runCatching { target.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) withFrameNanos { }
        }
    }
    LaunchedEffect(controller.upNextSecondsRemaining != null) {
        if (controller.upNextSecondsRemaining != null) runCatching { upNextFocus.requestFocus() }
    }

    // Back from a panel lands on the controls it was opened from.
    fun closePanel() {
        panel = null
        controller.showChrome()
    }

    fun commitSeek() {
        val target = seek.commit() ?: return
        seekShown = null
        scope.launch { playback.seekTo(target) }
    }

    fun nudge(forward: Boolean, repeatCount: Int) {
        val step = TvSeekSteps.stepFor(repeatCount)
        seekShown = seek.nudge(state.positionSeconds, if (forward) step else -step, state.durationSeconds)
    }

    BackHandler(enabled = true) {
        when {
            session.leaving -> Unit
            controller.upNextSecondsRemaining != null -> controller.dismissUpNext()
            seek.target != null -> {
                seek.cancel()
                seekShown = null
            }
            controller.chromeVisible -> controller.toggleChrome()
            else -> session.leave()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics {
                contentDescription = "Playback"
                stateDescription = formatTimecode(state.positionSeconds)
            }
            .onPreviewKeyEvent { event ->
                // Moving between the controls is using them: the idle timer
                // starts again, or they would hide while being read.
                if (controller.chromeVisible && event.type == KeyEventType.KeyDown) controller.armAutoHide()
                handleMediaKey(event, session, scope, playback, controller, paused, ::nudge, ::commitSeek)
            },
    ) {
        surface.Content(Modifier.fillMaxSize())

        // The screen itself holds focus while the controls are hidden, and
        // reads the remote: seek, pause, or bring the controls back.
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(rootFocus)
                // Only while the controls are hidden; otherwise a move from
                // the seek bar could land on it instead of on a button.
                .focusProperties { canFocus = !controller.chromeVisible }
                .onKeyEvent { event -> handleBareKey(event, scope, playback, controller, paused, ::nudge, ::commitSeek) }
                .focusable(),
        )

        AnimatedVisibility(visible = controller.chromeVisible, enter = fadeIn(tween(FadeMs)), exit = fadeOut(tween(FadeMs))) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxWidth().height(140.dp).background(
                        Brush.verticalGradient(0f to Color(0xB8040508), 1f to Color.Transparent),
                    ),
                )
                Box(
                    Modifier.fillMaxWidth().height(250.dp).align(Alignment.BottomCenter).background(
                        Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color(0xD1040508), 1f to Color(0xE6040508)),
                    ),
                )
                TopBar(session, leaveRoomForMedal = medal != null)
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = TvMetrics.SideMargin, vertical = TvMetrics.VerticalMargin + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    SeekBar(
                        position = state.positionSeconds,
                        duration = state.durationSeconds,
                        buffered = state.bufferedPositionSeconds,
                        target = seekShown,
                        modifier = Modifier
                            .focusRequester(barFocus)
                            .focusProperties { down = playFocus }
                            .onFocusChanged {
                                if (!it.isFocused && seek.target != null) {
                                    seek.cancel()
                                    seekShown = null
                                }
                            }
                            .onKeyEvent { event -> handleBarKey(event, controller, ::nudge, ::commitSeek) }
                            .focusable(),
                    )
                    Row(
                        Modifier.focusProperties { up = barFocus },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvIconButton(
                            icon = if (paused) HaloIcons.Play else HaloIcons.Pause,
                            contentDescription = if (paused) "Play" else "Pause",
                            modifier = Modifier.focusRequester(playFocus),
                            onClick = {
                                controller.onTransportUsed()
                                scope.launch { playback.setPaused(!paused) }
                            },
                        )
                        TvButton("Audio", onClick = { panel = TvPlayerPanel.Audio })
                        TvButton("Subtitles", onClick = { panel = TvPlayerPanel.Subtitles })
                        // Kept while a speed other than normal is in force,
                        // so a title that turns to passthrough mid-way (a
                        // track switch) can still be put back to normal.
                        if (!passthrough || state.playbackRate != 1.0) {
                            TvButton("Speed ${formatRate(state.playbackRate)}", onClick = { panel = TvPlayerPanel.Speed })
                        }
                        // Letterboxed to fit, or cropped to fill the screen: wide
                        // films and old 4:3 shows on a 16:9 TV. Remembered for the
                        // next video, as on the phone.
                        TvButton(
                            if (state.videoFillsScreen) "Fill" else "Fit",
                            icon = HaloIcons.FitScreen,
                            onClick = { scope.launch { playback.setVideoFillsScreen(!state.videoFillsScreen) } },
                            modifier = Modifier.semantics {
                                contentDescription = if (state.videoFillsScreen) "Picture fills the screen" else "Picture fits the screen"
                            },
                        )
                        if (context.isEpisode && session.episodes.isNotEmpty()) {
                            TvButton("Episodes", onClick = { panel = TvPlayerPanel.Episodes })
                        }
                        Box(Modifier.weight(1f))
                        if (session.upNext != null) {
                            TvButton("Next episode", onClick = { session.playNext() })
                        }
                    }
                }
            }
        }

        // Above the controls' shade, below every notice, card and panel.
        medal?.let { PlayerMedal(it) }

        seekShown?.takeIf { !controller.chromeVisible }?.let { target ->
            SeekBubble(target, delta = target - state.positionSeconds, modifier = Modifier.align(Alignment.Center))
        }

        when {
            switchingSource || (unsupported && !unsupportedCard) -> StatusPill(SwitchingSourceNotice, Modifier.align(Alignment.Center))
            state.status == PlaybackStatus.Loading -> StatusPill("Loading…", Modifier.align(Alignment.Center))
            state.buffering != null -> StatusPill("Buffering ${state.buffering?.percent ?: 0}%", Modifier.align(Alignment.Center))
        }

        controller.upNextSecondsRemaining?.let { remaining ->
            val next = session.upNext ?: return@let
            UpNextCard(
                tag = next.episodeTag.orEmpty(),
                name = next.episodeName ?: next.showTitle,
                thumbnail = next.episodeThumbnail,
                secondsRemaining = remaining,
                playFocus = upNextFocus,
                onPlayNow = controller::advanceToNext,
                onCancel = controller::dismissUpNext,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        if (unsupported) {
            if (unsupportedCard) {
                UnsupportedVideoCard(
                    onPickAnotherSource = onPickAnotherSource?.let { pick -> { session.leaveTo(pick) } },
                    onBack = session::leave,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        } else if (state.status == PlaybackStatus.Failed) {
            ErrorCard(
                message = state.error,
                onRetry = session::retry,
                onPickAnotherSource = onPickAnotherSource?.let { pick -> { session.leaveTo(pick) } },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        when (panel) {
            TvPlayerPanel.Audio -> TvAudioPanel(
                tracks = state.tracks,
                delaySeconds = state.audioDelaySeconds,
                // As for speed: a delay set before passthrough began can still be undone.
                delayAvailable = !passthrough || state.audioDelaySeconds != 0.0,
                onSelect = { id -> scope.launch { playback.selectAudioTrack(id) } },
                onDelay = { seconds -> scope.launch { playback.setAudioDelay(clampedDelay(seconds)) } },
                onDismiss = ::closePanel,
            )
            TvPlayerPanel.Subtitles -> TvSubtitlesPanel(
                tracks = state.tracks,
                addonSubtitles = session.addonSubtitles,
                selectedAddonId = controller.selectedAddonSubtitleId,
                preferredLang = session.preferredSubtitleLang,
                expandedLanguages = controller.expandedSubtitleLanguages,
                loading = session.addonSubtitlesFetching,
                loadError = session.subtitleLoadError,
                onSelectTrack = session::selectSubtitleTrack,
                onSelectAddon = session::selectAddonSubtitle,
                onToggleLanguage = controller::toggleSubtitleLanguage,
                onOpenStyle = { panel = TvPlayerPanel.SubtitleStyle },
                onDismiss = ::closePanel,
            )
            TvPlayerPanel.SubtitleStyle -> TvSubtitleStylePanel(
                tracks = state.tracks,
                scale = state.subtitleScale,
                delaySeconds = state.subtitleDelaySeconds,
                font = state.subtitleFont,
                outline = session.subtitleOutline,
                shadow = session.subtitleShadow,
                trackStyling = state.subtitleTrackStyling,
                onScale = { scale -> scope.launch { playback.setSubtitleScale(scale) } },
                onDelay = { seconds -> scope.launch { playback.setSubtitleDelay(clampedDelay(seconds)) } },
                onFont = { font -> scope.launch { playback.setSubtitleFont(font) } },
                onOutline = session::changeSubtitleOutline,
                onShadow = session::changeSubtitleShadow,
                onTrackStyling = { keep -> scope.launch { playback.setSubtitleTrackStyling(keep) } },
                // Back returns to the list it was opened from.
                onDismiss = { panel = TvPlayerPanel.Subtitles },
            )
            TvPlayerPanel.Speed -> TvSpeedPanel(
                rate = state.playbackRate,
                onSelect = { rate ->
                    panel = null
                    scope.launch { playback.setPlaybackRate(rate) }
                },
                onDismiss = ::closePanel,
            )
            TvPlayerPanel.Episodes -> TvEpisodesPanel(
                showTitle = context.showTitle,
                episodes = session.episodes,
                currentVideoId = context.videoId,
                onSelect = { episode ->
                    panel = null
                    session.selectEpisode(episode.videoId)
                },
                onDismiss = ::closePanel,
            )
            null -> Unit
        }
    }
}

/** Media keys, whatever has focus. Returns true when the key was used. */
private fun handleMediaKey(
    event: KeyEvent,
    session: PlayerSession,
    scope: CoroutineScope,
    playback: PlaybackHost,
    controller: PlayerScreenController,
    paused: Boolean,
    nudge: (forward: Boolean, repeatCount: Int) -> Unit,
    commitSeek: () -> Unit,
): Boolean {
    val native = event.nativeKeyEvent
    val action = TvKeyMap.actionFor(native.keyCode)
    val down = event.type == KeyEventType.KeyDown
    when (action) {
        TvAction.PlayPause -> if (down && native.repeatCount == 0) scope.launch { playback.setPaused(!paused) }
        TvAction.Play -> if (down) scope.launch { playback.setPaused(false) }
        TvAction.Pause -> if (down) {
            controller.showChrome()
            scope.launch { playback.setPaused(true) }
        }
        TvAction.Stop -> if (down) session.leave()
        TvAction.FastForward, TvAction.Rewind -> if (down) nudge(action == TvAction.FastForward, native.repeatCount) else commitSeek()
        TvAction.Next -> if (down) session.playNext()
        TvAction.Previous -> if (down) scope.launch { playback.seekTo(0.0) }
        else -> return false
    }
    return true
}

/** The remote while the controls are hidden. */
private fun handleBareKey(
    event: KeyEvent,
    scope: CoroutineScope,
    playback: PlaybackHost,
    controller: PlayerScreenController,
    paused: Boolean,
    nudge: (forward: Boolean, repeatCount: Int) -> Unit,
    commitSeek: () -> Unit,
): Boolean {
    val native = event.nativeKeyEvent
    val down = event.type == KeyEventType.KeyDown
    return when (TvKeyMap.actionFor(native.keyCode)) {
        TvAction.Select -> {
            if (!down) {
                scope.launch { playback.setPaused(!paused) }
                controller.showChrome()
            }
            true
        }
        TvAction.Left, TvAction.Right -> {
            if (down) nudge(TvKeyMap.actionFor(native.keyCode) == TvAction.Right, native.repeatCount) else commitSeek()
            true
        }
        TvAction.Up, TvAction.Down, TvAction.Menu -> {
            if (!down) controller.showChrome()
            true
        }
        else -> false
    }
}

/** The seek bar: Left and Right move the target, select seeks to it. */
private fun handleBarKey(
    event: KeyEvent,
    controller: PlayerScreenController,
    nudge: (forward: Boolean, repeatCount: Int) -> Unit,
    commitSeek: () -> Unit,
): Boolean {
    val native = event.nativeKeyEvent
    val down = event.type == KeyEventType.KeyDown
    return when (TvKeyMap.actionFor(native.keyCode)) {
        TvAction.Left, TvAction.Right -> {
            if (down) {
                controller.onTransportUsed()
                nudge(TvKeyMap.actionFor(native.keyCode) == TvAction.Right, native.repeatCount)
            }
            true
        }
        TvAction.Select -> {
            if (!down) commitSeek()
            true
        }
        else -> false
    }
}

@Composable
private fun TopBar(session: PlayerSession, leaveRoomForMedal: Boolean) {
    val context = session.context
    Row(
        Modifier.fillMaxWidth().padding(TopBarPadding),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(context.showTitle, color = HaloColors.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOfNotNull(context.episodeTag, context.episodeName).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = TvType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // The medal itself is drawn outside the controls ([PlayerMedal]), in
        // this spot; the title only keeps clear of it.
        if (leaveRoomForMedal) Spacer(Modifier.width(QualityMedalWidth))
    }
}

/**
 * The picture's medal, on screen the whole time and not only with the
 * controls: where the top bar puts it, at the same size, so it does not move
 * when they fade in or out. It holds no focus. Its metal and dark plate are
 * opaque, so it reads over a bright picture without the controls' shade.
 */
@Composable
private fun PlayerMedal(medal: Medal) {
    Box(Modifier.fillMaxWidth().padding(TopBarPadding), contentAlignment = Alignment.TopEnd) {
        QualityMedal(medal)
    }
}

private val TopBarPadding = PaddingValues(horizontal = TvMetrics.SideMargin, vertical = TvMetrics.VerticalMargin + 4.dp)

@Composable
private fun SeekBar(position: Double, duration: Double?, buffered: Double?, target: Double?, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val total = duration?.takeIf { it.isFinite() && it > 0 }
    fun fraction(seconds: Double): Float = if (total == null) 0f else (seconds / total).toFloat().coerceIn(0f, 1f)
    val shown = target ?: position
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(formatTimecode(position), style = TvType.Label, modifier = Modifier.width(76.dp))
        Box(
            // Ahead of the caller's focusable: a focus listener sees only the
            // focus targets after it in the chain.
            Modifier
                .onFocusChanged { focused = it.isFocused }
                .then(modifier)
                .weight(1f)
                .height(28.dp)
                .onSizeChanged { width = it }
                .semantics {
                    contentDescription = "Position"
                    stateDescription = formatTimecode(position)
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val track = Modifier.fillMaxWidth().height(if (focused) 8.dp else 5.dp).clip(RoundedCornerShape(4.dp))
            Box(track.background(Color.White.copy(alpha = 0.2f)))
            Box(Modifier.fillMaxWidth(fraction(buffered ?: 0.0)).height(if (focused) 8.dp else 5.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.3f)))
            Box(
                Modifier.fillMaxWidth(fraction(shown)).height(if (focused) 8.dp else 5.dp).clip(RoundedCornerShape(4.dp))
                    .background(Brush.horizontalGradient(listOf(HaloColors.Accent, HaloColors.AccentLight))),
            )
            val knob = if (focused) 20.dp else 12.dp
            val x = with(density) { (width.width * fraction(shown)).toDp() } - knob / 2
            Box(
                Modifier
                    .offset(x = x)
                    .size(knob)
                    .background(Color.White, CircleShape),
            )
            if (target != null) {
                SeekBubble(
                    target,
                    delta = target - position,
                    // Drawn at its own size above the bar, not squeezed into it.
                    modifier = Modifier.wrapContentSize(unbounded = true).offset(x = x - 30.dp, y = (-62).dp),
                )
            }
        }
        Text(total?.let(::formatTimecode) ?: "Live", style = TvType.Label.copy(color = HaloColors.TextMeta), modifier = Modifier.width(76.dp))
    }
}

@Composable
private fun SeekBubble(target: Double, delta: Double, modifier: Modifier = Modifier) {
    val seconds = round(delta).toInt()
    Column(
        modifier
            .background(Color(0xDB05070C), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(formatTimecode(target), color = HaloColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            (if (seconds >= 0) "+" else "−") + formatTimecode(abs(seconds).toDouble()),
            color = HaloColors.AccentLight,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = HaloColors.Text,
        fontSize = 17.sp,
        modifier = modifier
            .background(Color(0xD105070C), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun UpNextCard(
    tag: String,
    name: String,
    thumbnail: String?,
    secondsRemaining: Int,
    playFocus: FocusRequester,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(end = TvMetrics.SideMargin, bottom = TvMetrics.VerticalMargin + 120.dp)
            .width(460.dp)
            .background(HaloColors.Surface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaloAsyncImage(url = thumbnail, contentDescription = null, modifier = Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("UP NEXT IN $secondsRemaining", style = TvType.Overline)
            Text(listOf(tag, name).filter { it.isNotBlank() }.joinToString(" · "), style = TvType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Play now", onClick = onPlayNow, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(playFocus))
                TvButton("Cancel", onClick = onCancel)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String?, onRetry: () -> Unit, onPickAnotherSource: (() -> Unit)?, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier
            .width(520.dp)
            .background(HaloColors.Surface, RoundedCornerShape(20.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("This source didn't play", style = TvType.Title)
        Text(message?.takeIf { it.isNotBlank() } ?: "The file could not be opened.", style = TvType.Body, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("Try again", onClick = onRetry, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
            onPickAnotherSource?.let { TvButton("Choose another source", onClick = it) }
        }
    }
}

/**
 * The file opened, but its picture is in a format this TV cannot play and no
 * other source could take over. Retrying the same file cannot help, so the
 * only ways on are another source or back.
 */
@Composable
private fun UnsupportedVideoCard(onPickAnotherSource: (() -> Unit)?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier
            .width(520.dp)
            .background(HaloColors.Surface, RoundedCornerShape(20.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("This TV can't play this video", style = TvType.Title)
        Text("Its picture format isn't supported here. Choose another source.", style = TvType.Body, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (onPickAnotherSource != null) {
                TvButton("Choose another source", onClick = onPickAnotherSource, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
            } else {
                TvButton("Back", onClick = onBack, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
            }
        }
    }
}

private const val SwitchingSourceNotice = "That source isn't supported on this TV. Trying another one."

private const val FadeMs = 180

/** How long a control keeps asking for focus while it fades in. */
private const val FocusClaimMs = 600L
private const val CaptionLiftPercent = 30
private const val LogTag = "HALO_TV"
