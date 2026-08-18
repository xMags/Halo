package moe.ditto.halo.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloRadius

/**
 * The states the player has to be reviewed in, most of which cannot be reached
 * by watching something.
 *
 * Buffering needs a stalling stream, the gesture readout needs gestures that do
 * not exist yet, up next needs an episode to end, and the error card needs a
 * source that fails. Waiting for each of those to happen naturally is not a
 * review process, so they are all one tap away here.
 */
internal enum class PlayerScene(val label: String) {
    Playing("Playing"),
    Scrubbing("Scrub"),
    Buffering("Buffering"),
    Rail("Rail"),
    Drawer("Episodes"),
    Brightness("Brightness"),
    Volume("Volume"),
    Locked("Locked"),
    UpNext("Up next"),
    Error("Error"),
}

private val SceneBarFill = Color(0xFF14161D)
private val SceneChipIdle = Color.White.copy(alpha = 0.07f)

private const val FixtureBufferPercent = 62
private const val FixtureThroughput = "1.8 MB/s"
private const val FixtureCached = "12 s cached"
private const val FixtureEngineError = "Failed to open https stream: connection reset by peer"

/**
 * The player's design, rendered over a placeholder instead of over the engine.
 *
 * It exists so the whole screen can be judged before the data behind it is
 * real, and it deliberately does not touch [moe.ditto.halo.PlaybackHost]: a
 * review harness that could disturb playback would be a liability rather than a
 * tool. It drives the same [PlayerScreenController] the real screen does, so the
 * chrome and panel behaviour being reviewed is the shipping behaviour.
 *
 * Debug builds only, alongside the rest of the diagnostics gate.
 */
@Composable
internal fun PlayerScenePreviewScreen(onBack: () -> Unit) {
    var scene by remember { mutableStateOf(PlayerScene.Playing) }
    val scope = rememberCoroutineScope()
    // A fresh controller per scene, so switching scenes is a clean reset rather
    // than an accumulation of whatever the previous one left set.
    val controller = remember(scene) { PlayerScreenController(scope) }

    LaunchedEffect(scene, controller) {
        when (scene) {
            PlayerScene.Playing -> Unit
            PlayerScene.Scrubbing -> controller.beginScrub(0.68f)
            PlayerScene.Rail -> controller.openRail(RailTab.Subtitles)
            PlayerScene.Drawer -> controller.toggleEpisodeDrawer()
            PlayerScene.Locked -> controller.lock()
            // These arrive while the viewer is watching rather than operating
            // the player, so the design shows them over bare video. Buffering,
            // the gesture readout and the error card have no controller trigger
            // yet either, so the scene renders those directly.
            PlayerScene.UpNext -> {
                controller.toggleChrome()
                controller.showUpNext { scene = PlayerScene.Playing }
            }
            PlayerScene.Buffering,
            PlayerScene.Brightness,
            PlayerScene.Volume,
            PlayerScene.Error,
            -> controller.toggleChrome()
        }
    }

    // The picker floats over the player rather than sitting above it. Giving it
    // a row of its own costs a fifth of the height, and vertical composition —
    // where the caption sits against the centre controls, how far the bottom bar
    // rises — is exactly what this harness exists to judge.
    var barVisible by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerSceneContent(scene = scene, controller = controller)

        if (barVisible) {
            SceneBar(
                current = scene,
                onSelect = { scene = it },
                onBack = onBack,
                onHide = { barVisible = false },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 6.dp),
            ) {
                SceneChip(label = "Scenes", active = false, onClick = { barVisible = true })
            }
        }
    }
}

@Composable
private fun SceneBar(
    current: PlayerScene,
    onSelect: (PlayerScene) -> Unit,
    onBack: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SceneBarFill)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SceneChip(label = "Back", active = false, onClick = onBack)
        SceneChip(label = "Hide", active = false, onClick = onHide)
        PlayerScene.entries.forEach { candidate ->
            SceneChip(
                label = candidate.label,
                active = candidate == current,
                onClick = { onSelect(candidate) },
            )
        }
    }
}

@Composable
private fun SceneChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) HaloColors.OnAccent else HaloColors.TextDim,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(if (active) HaloColors.Accent else SceneChipIdle)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun PlayerSceneContent(scene: PlayerScene, controller: PlayerScreenController) {
    val metrics = rememberPlayerMetrics()
    val state = PlayerFixtures.State
    val chromeUp = controller.chromeVisible
    var trackStylingPreview by remember { mutableStateOf(state.subtitleTrackStyling) }

    Box(Modifier.fillMaxSize()) {
        // Stands in for the picture. The real screen has a native surface here,
        // which is also why the caption below is drawn only in this harness:
        // during playback libmpv renders subtitles itself.
        Box(Modifier.fillMaxSize().placeholderStripes())

        // No caption where the picture itself is not the subject: failing to
        // play replaces the video rather than sitting over it.
        if (scene != PlayerScene.Error) {
            Text(
                text = PlayerFixtures.CaptionSample,
                color = Color.White,
                fontSize = metrics.captionSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = metrics.hPad + 40.dp,
                        end = metrics.hPad + 40.dp,
                        bottom = if (chromeUp) 148.dp else 40.dp,
                    ),
            )
        }

        if (chromeUp) {
            PlayerScrims()
            PlayerTopBar(
                metrics = metrics,
                showTitle = PlayerFixtures.ShowTitle,
                episodeTag = PlayerFixtures.CurrentEpisode.tag,
                episodeName = PlayerFixtures.EpisodeName,
                streamBadges = PlayerFixtures.StreamBadges,
                locked = controller.locked,
                onBack = {},
                // The gallery shows the pill as a device with native PiP draws
                // it; the handoff itself is the system's, so there is nothing
                // for the harness to stand in for.
                onPictureInPicture = {},
                onToggleFit = {},
                onToggleLock = controller::lock,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            PlayerCentreControls(
                metrics = metrics,
                paused = false,
                onSeekBack = {},
                onTogglePlay = {},
                onSeekForward = {},
                modifier = Modifier.align(Alignment.Center),
            )
            PlayerBottomBar(
                metrics = metrics,
                chips = scenePreviewChips(controller),
                positionSeconds = state.positionSeconds,
                durationSeconds = state.durationSeconds,
                bufferedFraction = progressFraction(state.positionSeconds, state.durationSeconds) +
                    PlayerFixtures.BufferedLeadFraction,
                scrubFraction = controller.scrubFraction,
                // The harness owns no source to read frames out of, so the
                // scrub card is reviewed in the state it also has on a real
                // source whose frames the device cannot decode.
                scrubPreviewFrame = null,
                onScrubStart = controller::beginScrub,
                onScrubMove = controller::updateScrub,
                onScrubEnd = { controller.endScrub() },
                onScrubCancel = controller::cancelScrub,
                onSeekToFraction = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (controller.episodeDrawerOpen) {
            PlayerEpisodeDrawer(
                metrics = metrics,
                seasonTitle = PlayerFixtures.SeasonTitle,
                episodes = PlayerFixtures.Episodes,
                currentTag = PlayerFixtures.CurrentEpisode.tag,
                onClose = controller::closeEpisodeDrawer,
                onSelectEpisode = { controller.closeEpisodeDrawer() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        controller.rail?.let { tab ->
            PlayerRail(
                tab = tab,
                subtitlePane = controller.subtitlePane,
                metrics = metrics,
                tracks = state.tracks,
                subtitleScale = state.subtitleScale,
                subtitleDelaySeconds = state.subtitleDelaySeconds,
                subtitleFont = state.subtitleFont,
                trackStyling = trackStylingPreview,
                selectedAddonSubtitleId = controller.selectedAddonSubtitleId,
                bundledSubtitleFonts = PlayerFixtures.BundledSubtitleFonts,
                addonSubtitles = PlayerFixtures.AddonSubtitles,
                addonSubtitlesFetching = false,
                subtitleLoadError = null,
                // The harness reviews the folded list, so it names a preference
                // the fixtures actually contain rather than leaving it null.
                preferredSubtitleLang = "eng",
                expandedSubtitleLanguages = controller.expandedSubtitleLanguages,
                audioDelaySeconds = state.audioDelaySeconds,
                playbackRate = state.playbackRate,
                onSelectTab = controller::openRail,
                onSelectSubtitlePane = controller::selectSubtitlePane,
                onClose = controller::closeRail,
                onSelectSubtitleTrack = {},
                onSelectAddonSubtitle = { controller.selectAddonSubtitle(it.id) },
                onToggleSubtitleLanguage = controller::toggleSubtitleLanguage,
                onSubtitleScaleChange = {},
                onSubtitleDelayChange = {},
                // Held locally rather than sent anywhere: the switch changes the
                // card's hint text for every format, which is the thing being
                // reviewed here, and the harness owns no engine to send it to.
                onTrackStylingChange = { trackStylingPreview = it },
                onSubtitleFontChange = {},
                onSelectAudioTrack = {},
                onAudioDelayChange = {},
                // The harness has no PlaybackHost by design; speed is fixed at
                // the fixture state's 1× and the real screen exercises the call.
                onPlaybackRateChange = {},
            )
        }

        if (scene == PlayerScene.Buffering) {
            BufferingOverlay(
                percent = FixtureBufferPercent,
                throughput = FixtureThroughput,
                cached = FixtureCached,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (scene == PlayerScene.Brightness || scene == PlayerScene.Volume) {
            GestureHudOverlay(
                GestureHudValue(
                    kind = if (scene == PlayerScene.Brightness) GestureHudKind.Brightness else GestureHudKind.Volume,
                    value = if (scene == PlayerScene.Brightness) 0.34f else 0.72f,
                ),
            )
        }

        controller.upNextSecondsRemaining?.let { remaining ->
            UpNextCard(
                metrics = metrics,
                episodeTag = PlayerFixtures.NextEpisode.tag,
                episodeName = PlayerFixtures.NextEpisode.name,
                episodeThumbnail = PlayerFixtures.NextEpisode.thumbnail,
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

        if (scene == PlayerScene.Error) {
            PlaybackErrorCard(
                engineMessage = FixtureEngineError,
                onRetry = {},
                onPickAnotherSource = {},
            )
        }
    }
}

private fun scenePreviewChips(controller: PlayerScreenController): List<PlayerChip> = listOf(
    PlayerChip(
        kicker = "SUBTITLES",
        value = "very-long-subtitle-filename-that-must-not-grow-the-chip.srt",
        badge = ".SRT",
        active = controller.rail == RailTab.Subtitles,
        onClick = { controller.openRail(RailTab.Subtitles) },
        width = SubtitleChipWidth,
    ),
    PlayerChip(
        kicker = "AUDIO",
        value = "English",
        active = controller.rail == RailTab.Audio,
        onClick = { controller.openRail(RailTab.Audio) },
    ),
    PlayerChip(
        kicker = "SPEED",
        value = formatRate(PlayerFixtures.State.playbackRate),
        active = controller.rail == RailTab.Speed,
        onClick = { controller.openRail(RailTab.Speed) },
    ),
    PlayerChip(
        kicker = "EPISODES",
        value = PlayerFixtures.CurrentEpisode.tag,
        active = controller.episodeDrawerOpen,
        onClick = controller::toggleEpisodeDrawer,
    ),
)
