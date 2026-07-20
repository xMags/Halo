package moe.ditto.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.ditto.halo.auth.LoginPhase
import moe.ditto.halo.auth.LoginPresenter
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerState
import moe.ditto.halo.player.PlayerTrack
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloDimensions
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloTheme
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.classifyWindow

private enum class ShellScreen {
    Login,
    Gate,
    Player,
}

@Composable
internal fun HaloGateApp(dependencies: PlatformDependencies) {
    HaloTheme {
        val session = remember(dependencies) { GateSession(dependencies.playerPort) }
        var screen by remember { mutableStateOf(ShellScreen.Login) }
        var hostSnapshot by remember { mutableStateOf(dependencies.nativeHostDiagnostics.snapshot()) }
        val refreshHostSnapshot = { hostSnapshot = dependencies.nativeHostDiagnostics.snapshot() }
        val recreatePlayerCore = {
            dependencies.nativeHostDiagnostics.destroyAndRecreatePlayerCore()
            refreshHostSnapshot()
        }

        // Native playback events feed the presenter for the whole app lifetime,
        // not just while the player screen is composed — otherwise events that
        // arrive on the gate screen would only apply after re-entry.
        var playerState by remember(session) { mutableStateOf(session.playerPresenter.state) }
        LaunchedEffect(session) {
            dependencies.playerEvents.collect { event ->
                session.playerPresenter.onEvent(event)
                playerState = session.playerPresenter.state
            }
        }

        Box(Modifier.fillMaxSize().background(HaloColors.Background)) {
            when (screen) {
                ShellScreen.Login -> LoginScreen(
                    dependencies = dependencies,
                    onOpenGate = {
                        refreshHostSnapshot()
                        screen = ShellScreen.Gate
                    },
                )
                ShellScreen.Gate -> GateScreen(
                    hostSnapshot = hostSnapshot,
                    onRefresh = refreshHostSnapshot,
                    onLogin = { screen = ShellScreen.Login },
                    onPlayer = {
                        refreshHostSnapshot()
                        screen = ShellScreen.Player
                    },
                )
                ShellScreen.Player -> PlayerShellScreen(
                    session = session,
                    state = playerState,
                    onStateChanged = { playerState = session.playerPresenter.state },
                    mediaHttpBase = dependencies.mediaHttpBase,
                    mediaLocalBase = dependencies.mediaLocalBase,
                    nativePlayerSurface = dependencies.nativePlayerSurface,
                    hostSnapshot = hostSnapshot,
                    onRefresh = refreshHostSnapshot,
                    onRecreateCore = recreatePlayerCore,
                    onBack = {
                        refreshHostSnapshot()
                        screen = ShellScreen.Gate
                    },
                )
            }
        }
    }
}

@Composable
private fun LoginScreen(
    dependencies: PlatformDependencies,
    onOpenGate: () -> Unit,
) {
    val presenter = remember {
        LoginPresenter(dependencies.authConfigSource, dependencies.nativeHostRequests).also {
            it.editServerUrl(dependencies.initialServerUrl)
        }
    }
    var state by remember { mutableStateOf(presenter.state) }
    val scope = rememberCoroutineScope()

    // The native OIDC host reports its terminal outcome here. The login screen
    // stays composed for the whole flow (ASWebAuthenticationSession is a sheet
    // over it), so a screen-scoped collector never misses the event.
    LaunchedEffect(Unit) {
        dependencies.authEvents.collect { event ->
            presenter.onAuthEvent(event)
            state = presenter.state
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val responsive = classifyWindow(maxWidth.value, maxHeight.value)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = HaloDimensions.LoginMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = HaloSpacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
        ) {
            Text(
                text = "halo",
                color = HaloColors.Text,
                fontSize = 48.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
            Text(
                text = if (state.showsCredentials) {
                    "This server uses local accounts. Enter credentials to validate the native form; session exchange is intentionally not part of this gate."
                } else {
                    "Your Halo server decides whether the native host starts OIDC or reveals local credentials."
                },
                style = HaloType.Body.copy(color = HaloColors.TextDim, textAlign = TextAlign.Center),
            )
            Text(
                text = "${responsive.deviceClass} · shortest edge ${responsive.shortestEdgeDp.toInt()}dp",
                style = HaloType.Caption,
            )
            HaloTextField(
                value = state.serverUrl,
                onValueChange = {
                    presenter.editServerUrl(it)
                    state = presenter.state
                },
                label = "Server URL",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            )
            if (state.showsCredentials) {
                HaloTextField(
                    value = state.username,
                    onValueChange = {
                        presenter.editUsername(it)
                        state = presenter.state
                    },
                    label = "Username",
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                )
                HaloTextField(
                    value = state.password,
                    onValueChange = {
                        presenter.editPassword(it)
                        state = presenter.state
                    },
                    label = "Password",
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    password = true,
                )
            }
            state.error?.let {
                Text(text = it, color = HaloColors.Danger, style = HaloType.Body, textAlign = TextAlign.Center)
            }
            if (state.phase == LoginPhase.Server || state.phase == LoginPhase.Discovering) {
                HaloButton(
                    label = "Continue",
                    enabled = state.canContinue,
                    busy = state.isBusy,
                    onClick = {
                        scope.launch {
                            presenter.continueFromServer()
                            state = presenter.state
                        }
                    },
                )
            } else {
                when (val phase = state.phase) {
                    is LoginPhase.OidcRequested -> {
                        // Fake host parks here (counter bump only); the real host
                        // moves on via an AuthEvent. "Open gate menu" must stay so
                        // the fake-host suites keep their navigation path.
                        Text(
                            text = "Signing in with your provider…",
                            color = HaloColors.TextDim,
                            style = HaloType.Callout,
                        )
                        HaloButton(label = "Open gate menu", onClick = onOpenGate)
                    }
                    is LoginPhase.OidcSucceeded -> {
                        Text(
                            text = "OIDC signed in · ${phase.tokenProof}",
                            color = HaloColors.Success,
                            style = HaloType.Callout,
                            textAlign = TextAlign.Center,
                        )
                        HaloButton(label = "Open gate menu", onClick = onOpenGate)
                    }
                    is LoginPhase.OidcFailed -> {
                        Text(
                            text = "OIDC failed: ${phase.reason}",
                            color = HaloColors.Danger,
                            style = HaloType.Body,
                            textAlign = TextAlign.Center,
                        )
                        HaloButton(
                            label = "Retry",
                            onClick = {
                                presenter.retryOidc()
                                state = presenter.state
                            },
                        )
                    }
                    else -> {
                        Text(text = "Local mode discovered", color = HaloColors.Success, style = HaloType.Callout)
                        HaloButton(label = "Open gate menu", onClick = onOpenGate)
                    }
                }
            }
        }
    }
}

@Composable
private fun GateScreen(
    hostSnapshot: NativeHostSnapshot,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
    onPlayer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(HaloSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .background(HaloColors.Glass, RoundedCornerShape(HaloRadius.Xl))
                .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(HaloRadius.Xl))
                .padding(HaloSpacing.Xl),
            verticalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
        ) {
            Text("Debug gate", style = HaloType.LargeTitle)
            Text(
                "Swift owns one auth host, one player host, and one libmpv core. Navigate and resize, then confirm their ids and creation counts remain stable.",
                style = HaloType.Body.copy(color = HaloColors.TextDim),
            )
            NativeHostSummary(hostSnapshot)
            HaloButton(label = "Refresh host counters", onClick = onRefresh)
            HaloButton(label = "Login shell", onClick = onLogin)
            HaloButton(label = "Player shell", onClick = onPlayer)
        }
    }
}

@Composable
private fun PlayerShellScreen(
    session: GateSession,
    state: PlayerState,
    onStateChanged: () -> Unit,
    mediaHttpBase: String,
    mediaLocalBase: String,
    nativePlayerSurface: NativePlayerSurface,
    hostSnapshot: NativeHostSnapshot,
    onRefresh: () -> Unit,
    onRecreateCore: () -> Unit,
    onBack: () -> Unit,
) {
    val presenter = session.playerPresenter
    var recompositionProbe by remember { mutableStateOf(0) }
    // Mid-playback layout-resize driver (mpvkit/MPVKit#3 regression): cycling the
    // surface height forces drawableSize changes without touching the core.
    val surfaceHeights = remember { listOf(180, 320, 120) }
    var surfaceHeightIndex by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // The fixture server (fixtures/fixture_server.py) serves these over HTTP
    // with Range support; the local variants read the same files directly.
    var httpBase by remember { mutableStateOf(mediaHttpBase) }
    var localBase by remember { mutableStateOf(mediaLocalBase) }
    // Titles carry the transport so automation can prove which load actually
    // happened — HTTP and local variants of the same sample are otherwise
    // indistinguishable in the UI.
    val transportOf = { base: String -> if (base.startsWith("file:")) "local" else "HTTP" }
    val assItem = { base: String ->
        MediaItem("sample-ass", "4K ASS (${transportOf(base)})", "${base.trimEnd('/')}/sample4k-ass.mkv")
    }
    val bitmapItem = { base: String ->
        MediaItem("sample-bitmap", "4K bitmap (${transportOf(base)})", "${base.trimEnd('/')}/sample4k-bitmap.mkv")
    }

    LaunchedEffect(session) {
        // The 60s ASS sample with the bitmap sample queued next makes natural
        // end exercise load-next-on-the-same-core for real.
        session.ensurePlayerStarted(assItem(httpBase), bitmapItem(httpBase))
        onStateChanged()
        onRefresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(HaloSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Player shell", style = HaloType.Title)
            HaloButton(label = "Gate", onClick = onBack, compact = true)
        }
        nativePlayerSurface.Content(
            Modifier
                .fillMaxWidth()
                .height(surfaceHeights[surfaceHeightIndex].dp)
                .border(1.dp, HaloColors.Border, RoundedCornerShape(HaloRadius.Lg)),
        )
        Text(
            "Recomposition probe: $recompositionProbe · surface ${surfaceHeights[surfaceHeightIndex]}dp",
            style = HaloType.Caption,
        )
        // Tappable controls stay near the top: XCUITest taps after a fling
        // scroll are unreliable (Compose consumes them as stop-fling), so the
        // layout keeps everything automation presses within the first screen.
        Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
            HaloButton(
                label = "Recompose",
                compact = true,
                onClick = { recompositionProbe += 1 },
            )
            HaloButton(label = "Refresh counters", compact = true, onClick = onRefresh)
            HaloButton(
                label = "Cycle surface size",
                compact = true,
                onClick = { surfaceHeightIndex = (surfaceHeightIndex + 1) % surfaceHeights.size },
            )
        }
        HaloButton(label = "Explicitly recreate player core", onClick = onRecreateCore)

        Text("Playback", style = HaloType.Heading)
        Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
            HaloButton(
                label = if (state.status == PlaybackStatus.Paused) "Resume" else "Pause",
                compact = true,
                onClick = {
                    scope.launch {
                        presenter.setPaused(state.status != PlaybackStatus.Paused)
                        onStateChanged()
                    }
                },
            )
            HaloButton(
                label = "-10s",
                compact = true,
                onClick = {
                    scope.launch {
                        presenter.seekTo(state.positionSeconds - 10.0)
                        onStateChanged()
                    }
                },
            )
            HaloButton(
                label = "+10s",
                compact = true,
                onClick = {
                    scope.launch {
                        presenter.seekTo(state.positionSeconds + 10.0)
                        onStateChanged()
                    }
                },
            )
            HaloButton(
                label = "Teardown",
                compact = true,
                onClick = {
                    scope.launch {
                        presenter.close()
                        onStateChanged()
                    }
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
            HaloButton(
                label = "Load ASS (HTTP)",
                compact = true,
                onClick = {
                    scope.launch {
                        presenter.start(assItem(httpBase), bitmapItem(httpBase))
                        onStateChanged()
                    }
                },
            )
            HaloButton(
                label = "Load bitmap (HTTP)",
                compact = true,
                onClick = {
                    scope.launch {
                        presenter.start(bitmapItem(httpBase))
                        onStateChanged()
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
            HaloButton(
                label = "Load ASS (local)",
                compact = true,
                onClick = {
                    scope.launch {
                        presenter.start(assItem(localBase), bitmapItem(localBase))
                        onStateChanged()
                    }
                },
            )
            HaloButton(
                label = "Load bitmap (local)",
                compact = true,
                onClick = {
                    scope.launch {
                        presenter.start(bitmapItem(localBase))
                        onStateChanged()
                    }
                },
            )
        }

        Text("Subtitles (live, no core recreation)", style = HaloType.Heading)
        Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
            HaloButton(
                label = "Delay -0.5s",
                compact = true,
                onClick = {
                    scope.launch {
                        presenter.setSubtitleDelay(state.subtitleDelaySeconds - 0.5)
                        onStateChanged()
                    }
                },
            )
            HaloButton(
                label = "Delay +0.5s",
                compact = true,
                onClick = {
                    scope.launch {
                        presenter.setSubtitleDelay(state.subtitleDelaySeconds + 0.5)
                        onStateChanged()
                    }
                },
            )
            HaloButton(
                label = "Scale ${state.subtitleScale}",
                compact = true,
                onClick = {
                    scope.launch {
                        val nextScale = if (state.subtitleScale >= 2.0) 0.5 else state.subtitleScale + 0.5
                        presenter.setSubtitleScale(nextScale)
                        onStateChanged()
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
            HaloButton(
                label = "Font: ${state.subtitleFont ?: "default"}",
                compact = true,
                onClick = {
                    scope.launch {
                        val nextFont = when (state.subtitleFont) {
                            null -> "Courier New"
                            "Courier New" -> "Avenir Next"
                            else -> null
                        }
                        presenter.setSubtitleFont(nextFont)
                        onStateChanged()
                    }
                },
            )
            HaloButton(
                label = "Add external sub",
                compact = true,
                onClick = {
                    scope.launch {
                        presenter.addSubtitle("${httpBase.trimEnd('/')}/sample4k.ass")
                        onStateChanged()
                    }
                },
            )
        }

        TrackSelector(
            title = "Subtitle tracks",
            tracks = state.tracks.subtitles,
            selectedId = state.tracks.selectedSubtitleId,
            onSelect = { id ->
                scope.launch {
                    presenter.selectSubtitleTrack(id)
                    onStateChanged()
                }
            },
        )
        TrackSelector(
            title = "Audio tracks",
            tracks = state.tracks.audio,
            selectedId = state.tracks.selectedAudioId,
            onSelect = { id ->
                scope.launch {
                    presenter.selectAudioTrack(id)
                    onStateChanged()
                }
            },
        )

        // Read-only diagnostics and rarely-edited fields live below the fold;
        // XCUITest asserts them by existence, which does not require scrolling.
        PlayerStateSummary(state)
        NativeHostSummary(hostSnapshot)
        Text("Media source", style = HaloType.Heading)
        HaloTextField(
            value = httpBase,
            onValueChange = { httpBase = it },
            label = "HTTP media base",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        )
        HaloTextField(
            value = localBase,
            onValueChange = { localBase = it },
            label = "Local media base",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        )
    }
}

@Composable
private fun TrackSelector(
    title: String,
    tracks: List<PlayerTrack>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    if (tracks.isEmpty()) return
    Text(title, style = HaloType.Heading)
    Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
        HaloButton(label = "$title off", compact = true, onClick = { onSelect(null) })
    }
    tracks.forEach { track ->
        val marker = if (track.id == selectedId) "● " else ""
        HaloButton(
            label = "$marker${track.label}${track.language?.let { " ($it)" } ?: ""} [${track.id}]",
            compact = true,
            onClick = { onSelect(track.id) },
        )
    }
}

@Composable
private fun NativeHostSummary(snapshot: NativeHostSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HaloColors.SurfaceHigh, RoundedCornerShape(HaloRadius.Md))
            .padding(HaloSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(HaloSpacing.Xs),
    ) {
        Text("Auth host: ${snapshot.authHostId}", style = HaloType.Caption)
        Text("Player host: ${snapshot.playerHostId}", style = HaloType.Caption)
        Text("Core instance: ${snapshot.playerInstanceId}", style = HaloType.Caption)
        Text("Player view instance: ${snapshot.playerViewInstanceId}", style = HaloType.Caption)
        Text(
            "Core lifecycle: create ${snapshot.coreCreationCount} · destroy ${snapshot.coreDestructionCount}",
            style = HaloType.Caption,
        )
        Text(
            "Player view creations: ${snapshot.playerViewCreationCount}",
            style = HaloType.Caption,
        )
        Text(
            "Surface: attach ${snapshot.attachCount} · resize ${snapshot.resizeCount} · detach ${snapshot.detachCount}",
            style = HaloType.Caption,
        )
        Text(
            "Playback: load ${snapshot.loadCount} · teardown ${snapshot.teardownCount}",
            style = HaloType.Caption,
        )
        Text("OIDC requests: ${snapshot.oidcRequestCount}", style = HaloType.Caption)
    }
}

@Composable
private fun PlayerStateSummary(state: PlayerState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HaloColors.Surface, RoundedCornerShape(HaloRadius.Md))
            .padding(HaloSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(HaloSpacing.Xs),
    ) {
        Text(state.current?.title ?: "No media", style = HaloType.Heading)
        Text("Status: ${state.status}", style = HaloType.Body)
        Text(
            "Position: ${state.positionSeconds}s / ${state.durationSeconds ?: "unknown"}s",
            style = HaloType.Caption,
        )
        Text(
            "Tracks: ${state.tracks.audio.size} audio · ${state.tracks.subtitles.size} subtitle",
            style = HaloType.Caption,
        )
        Text(
            "Sub style: delay ${state.subtitleDelaySeconds}s · scale ${state.subtitleScale} · font ${state.subtitleFont ?: "default"}",
            style = HaloType.Caption,
        )
        if (state.status == PlaybackStatus.Failed) {
            Text(state.error.orEmpty(), color = HaloColors.Danger, style = HaloType.Body)
        }
    }
}

@Composable
private fun HaloTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    password: Boolean = false,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = HaloColors.Text,
            unfocusedTextColor = HaloColors.Text,
            focusedContainerColor = HaloColors.FieldFill,
            unfocusedContainerColor = HaloColors.FieldFill,
            focusedBorderColor = HaloColors.Accent,
            unfocusedBorderColor = HaloColors.Border,
            focusedLabelColor = HaloColors.Accent,
            unfocusedLabelColor = HaloColors.TextDim,
            cursorColor = HaloColors.Accent,
        ),
        shape = RoundedCornerShape(HaloRadius.Md),
    )
}

@Composable
private fun HaloButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    compact: Boolean = false,
) {
    val modifier = (if (compact) Modifier else Modifier.fillMaxWidth()).semantics {
        contentDescription = label
    }
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = HaloColors.Accent,
            contentColor = HaloColors.OnAccent,
            disabledContainerColor = HaloColors.Accent.copy(alpha = 0.6f),
            disabledContentColor = HaloColors.OnAccent,
        ),
        shape = RoundedCornerShape(HaloRadius.Md),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.height(18.dp),
                color = HaloColors.OnAccent,
                strokeWidth = 2.dp,
            )
        } else {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}
