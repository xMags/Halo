package moe.ditto.halo.tv.player

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.round
import moe.ditto.halo.NativePlayerSurface
import moe.ditto.halo.PlaybackHost
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.browse.Medal
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerSystemPort
import moe.ditto.halo.player.classifyVideoQuality
import moe.ditto.halo.screens.player.EpisodeChoice
import moe.ditto.halo.screens.player.PlaybackContext
import moe.ditto.halo.screens.player.PlayerScreenController
import moe.ditto.halo.screens.player.PlayerSession
import moe.ditto.halo.screens.player.clampedDelay
import moe.ditto.halo.screens.player.formatRate
import moe.ditto.halo.screens.player.formatTimecode
import moe.ditto.halo.screens.player.rememberPlayerSession
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
    context: PlaybackContext,
    onSelectEpisode: (EpisodeChoice) -> Unit,
    /** Null for a pasted link, which has no list to pick another source from. */
    onPickAnotherSource: (() -> Unit)?,
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
    )
    TvPlaybackSystem(playback = playback, session = session, scope = scope, onPausedFromOutside = controller::showChrome)
    val state = session.state
    val paused = state.status != PlaybackStatus.Playing
    TvFrameRateMatching(frameRate = state.videoFormat.frameRate, enabled = preferences.matchFrameRate)
    // Surround formats the receiver takes as they are, asked again per title.
    var spdifCodecs by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session.item, preferences.surroundSound) { spdifCodecs = audioOutput.apply(preferences.surroundSound) }
    val audioTrack = state.tracks.audio.firstOrNull { it.id == (state.tracks.selectedAudioId ?: state.tracks.audio.firstOrNull()?.id) }
    // Sound sent on untouched cannot be delayed or sped up here.
    val passthrough = passesThrough(spdifCodecs, audioTrack?.codec)
    val medal = remember(state.videoFormat) { classifyVideoQuality(state.videoFormat) }
    var panel by remember { mutableStateOf<TvPlayerPanel?>(null) }
    val seek = remember(session.item) { TvSeekTarget() }
    // The target shown in the bubble while a seek is built; mirrors [seek].
    var seekShown by remember { mutableStateOf<Double?>(null) }
    val rootFocus = remember { FocusRequester() }
    val barFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val upNextFocus = remember { FocusRequester() }

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
                TopBar(session, medal)
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
                        if (!passthrough) {
                            TvButton("Speed ${formatRate(state.playbackRate)}", onClick = { panel = TvPlayerPanel.Speed })
                        }
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

        seekShown?.takeIf { !controller.chromeVisible }?.let { target ->
            SeekBubble(target, delta = target - state.positionSeconds, modifier = Modifier.align(Alignment.Center))
        }

        when {
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

        if (state.status == PlaybackStatus.Failed) {
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
                delayAvailable = !passthrough,
                onSelect = { id -> scope.launch { playback.selectAudioTrack(id) } },
                onDelay = { seconds -> scope.launch { playback.setAudioDelay(clampedDelay(seconds)) } },
                onDismiss = ::closePanel,
            )
            TvPlayerPanel.Subtitles -> TvSubtitlesPanel(
                tracks = state.tracks,
                addonSubtitles = session.addonSubtitles,
                selectedAddonId = controller.selectedAddonSubtitleId,
                loading = session.addonSubtitlesFetching,
                loadError = session.subtitleLoadError,
                scale = state.subtitleScale,
                delaySeconds = state.subtitleDelaySeconds,
                onSelectTrack = session::selectSubtitleTrack,
                onSelectAddon = session::selectAddonSubtitle,
                onScale = { scale -> scope.launch { playback.setSubtitleScale(scale) } },
                onDelay = { seconds -> scope.launch { playback.setSubtitleDelay(clampedDelay(seconds)) } },
                onDismiss = ::closePanel,
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
                title = listOfNotNull(context.showTitle, context.episodeTag).joinToString(" · "),
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
private fun TopBar(session: PlayerSession, medal: Medal?) {
    val context = session.context
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TvMetrics.SideMargin, vertical = TvMetrics.VerticalMargin + 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(context.showTitle, color = HaloColors.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOfNotNull(context.episodeTag, context.episodeName).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = TvType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        medal?.let { QualityMedal(it) }
    }
}

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

private const val FadeMs = 180

/** How long a control keeps asking for focus while it fades in. */
private const val FocusClaimMs = 600L
private const val CaptionLiftPercent = 30
