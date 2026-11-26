package moe.ditto.halo.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull
import moe.ditto.halo.api.SubtitleOutline
import moe.ditto.halo.player.PlayerTrack
import moe.ditto.halo.player.PlayerTracks
import moe.ditto.halo.screens.player.AddonSubtitleOption
import moe.ditto.halo.screens.player.defaultExpandedSubtitleLanguages
import moe.ditto.halo.screens.player.formatRate
import moe.ditto.halo.screens.player.MaxSubtitleScalePercent
import moe.ditto.halo.screens.player.MinSubtitleScalePercent
import moe.ditto.halo.screens.player.PlaybackRates
import moe.ditto.halo.screens.player.PlayerEpisode
import moe.ditto.halo.screens.player.SubtitleFontChoices
import moe.ditto.halo.screens.player.subtitleFormat
import moe.ditto.halo.screens.player.SubtitleFormat
import moe.ditto.halo.screens.player.subtitleLanguageGroups
import moe.ditto.halo.screens.player.SubtitleOutlineChoices
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvChoiceRow
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvProgressBar
import moe.ditto.halo.tv.kit.TvRowSurface
import moe.ditto.halo.tv.kit.TvSidePanel
import moe.ditto.halo.tv.kit.TvSurface
import moe.ditto.halo.tv.kit.TvSurfaceColors
import moe.ditto.halo.tv.kit.TvSwitchRow
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.languageLabel

/** The player's side panels. */
internal enum class TvPlayerPanel { Audio, Subtitles, SubtitleStyle, Speed, Episodes }

/** "English · EAC3 · 5.1": what tells two audio tracks apart. */
internal fun audioTrackLabel(track: PlayerTrack): String {
    val channels = track.channels?.let {
        when (it) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$it channels"
        }
    }
    return listOfNotNull(track.language?.let(::languageLabel) ?: track.label, track.codec?.uppercase(), channels)
        .distinct()
        .joinToString(" · ")
}

@Composable
private fun Selected(selected: Boolean) {
    if (selected) Icon(HaloIcons.Check, contentDescription = null, tint = HaloColors.AccentLight)
}

/** A setting changed in steps: a label, its value, and buttons either side. */
@Composable
private fun StepRow(label: String, value: String, less: String, more: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$label  $value", style = TvType.Label, modifier = Modifier.weight(1f))
        TvButton(less, onClick = onLess)
        TvButton(more, onClick = onMore)
    }
}

@Composable
internal fun TvAudioPanel(
    tracks: PlayerTracks,
    delaySeconds: Double,
    /** False while the sound is passed through to a receiver, which no delay reaches. */
    delayAvailable: Boolean,
    onSelect: (String) -> Unit,
    onDelay: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    TvSidePanel(title = "Audio", onDismiss = onDismiss) {
        LazyColumn(Modifier.weight(1f), contentPadding = FocusRoom, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tracks.audio.isEmpty()) item { Text("This video has no audio tracks.", style = TvType.Body) }
            items(tracks.audio, key = { it.id }) { track ->
                val selected = track.id == (tracks.selectedAudioId ?: tracks.audio.firstOrNull()?.id)
                TvRowSurface(
                    title = audioTrackLabel(track),
                    hint = track.label.takeIf { it != track.language && it.isNotBlank() },
                    onClick = { onSelect(track.id) },
                    trailing = { Selected(selected) },
                )
            }
        }
        if (delayAvailable) {
            StepRow(
                label = "Delay",
                value = formatDelay(delaySeconds),
                less = "Earlier",
                more = "Later",
                onLess = { onDelay(delaySeconds - DelayStep) },
                onMore = { onDelay(delaySeconds + DelayStep) },
            )
        }
    }
}

/**
 * Which subtitles show: off, the file's own tracks, then the addons' results
 * folded into one group per language, the preferred language first, as on
 * the phone. A popular film brings back twenty-odd results, mostly in a few
 * languages; flat, that is a long walk with the arrows. The group holding the
 * current choice starts open. Size, timing and style are one panel further.
 */
@Composable
internal fun TvSubtitlesPanel(
    tracks: PlayerTracks,
    addonSubtitles: List<AddonSubtitleOption>,
    selectedAddonId: String?,
    preferredLang: String?,
    expandedLanguages: Set<String>?,
    loading: Boolean,
    loadError: String?,
    onSelectTrack: (String?) -> Unit,
    onSelectAddon: (AddonSubtitleOption) -> Unit,
    onToggleLanguage: (language: String, currentlyExpanded: Set<String>) -> Unit,
    onOpenStyle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val noneShown = tracks.selectedSubtitleId == null && selectedAddonId == null
    val groups = remember(addonSubtitles, preferredLang) { subtitleLanguageGroups(addonSubtitles, preferredLang) }
    // Worked out each time: results arrive while the panel is open, and the
    // group holding the choice is only known once they have.
    val expanded = expandedLanguages ?: defaultExpandedSubtitleLanguages(groups, selectedAddonId)
    // A file can carry thirty tracks; past a handful they fold by language
    // too, with the one in use open, so the list stays a few presses long.
    val fileGroups = remember(tracks.subtitles) { fileTrackGroups(tracks.subtitles) }
    val grouped = tracks.subtitles.size > FileTracksShownFlat
    val currentFileLanguage = tracks.subtitles.firstOrNull { it.id == tracks.selectedSubtitleId }?.let(::trackLanguage)
    var openFileLanguages by remember { mutableStateOf(setOfNotNull(currentFileLanguage.takeIf { selectedAddonId == null })) }
    TvSidePanel(title = "Subtitles", onDismiss = onDismiss, width = 480.dp) {
        LazyColumn(Modifier.weight(1f), contentPadding = FocusRoom, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // First, so it is one press away however long the lists below are.
            item(key = "style") {
                TvRowSurface(
                    title = "Size, timing and style",
                    hint = "Size, delay, outline, shadow and font",
                    onClick = onOpenStyle,
                    trailing = { Icon(HaloIcons.ChevronLeft, contentDescription = null, tint = HaloColors.TextDim, modifier = Modifier.size(16.dp).graphicsLayer { scaleX = -1f }) },
                )
            }
            item(key = "off") { TvRowSurface(title = "Off", onClick = { onSelectTrack(null) }, trailing = { Selected(noneShown) }) }
            if (tracks.subtitles.isNotEmpty()) item(key = "file-label") { PanelLabel("In the file") }
            if (!grouped) {
                items(tracks.subtitles, key = { "track/${it.id}" }) { track ->
                    FileTrackRow(track, tracks, selectedAddonId, onSelectTrack)
                }
            }
            if (grouped) fileGroups.forEach { (language, inGroup) ->
                val open = language in openFileLanguages
                val holdsChoice = selectedAddonId == null && inGroup.any { it.id == tracks.selectedSubtitleId }
                item(key = "file-group/$language") {
                    GroupRow(
                        language = language,
                        count = inGroup.size,
                        open = open,
                        holdsChoice = holdsChoice,
                        onToggle = { openFileLanguages = if (open) openFileLanguages - language else openFileLanguages + language },
                    )
                }
                if (open) {
                    items(inGroup, key = { "track/${it.id}" }) { track ->
                        FileTrackRow(track, tracks, selectedAddonId, onSelectTrack, Modifier.padding(start = 20.dp), inGroup = true)
                    }
                }
            }
            if (groups.isNotEmpty()) item(key = "addons-label") { PanelLabel("From addons") }
            groups.forEach { group ->
                val open = group.language in expanded
                val holdsChoice = group.options.any { it.id == selectedAddonId }
                item(key = "group/${group.language}") {
                    GroupRow(
                        language = group.language,
                        count = group.options.size,
                        open = open,
                        holdsChoice = holdsChoice,
                        onToggle = { onToggleLanguage(group.language, expanded) },
                    )
                }
                if (!open) return@forEach
                items(group.options, key = { "addon/${it.id}" }) { option ->
                    // The language is the group's; the addon is what tells
                    // two results apart inside it.
                    TvRowSurface(
                        title = option.addonName,
                        hint = listOfNotNull(option.subId, option.format).joinToString(" · ").ifEmpty { null },
                        onClick = { onSelectAddon(option) },
                        modifier = Modifier.padding(start = 20.dp),
                        trailing = { Selected(option.id == selectedAddonId) },
                    )
                }
            }
            if (loading) item(key = "loading") { Text("Asking your addons for more…", style = TvType.Caption) }
            if (!loading && addonSubtitles.isEmpty()) {
                item(key = "no-addons") { Text("No addon offered subtitles for this file.", style = TvType.Caption) }
            }
            loadError?.let { item(key = "error") { Text(it, style = TvType.Caption.copy(color = HaloColors.Danger)) } }
        }
    }
}

/** More of the file's own tracks than this and they fold by language. */
private const val FileTracksShownFlat = 6

/** A track's language as a name, or its own label when it has none. */
private fun trackLanguage(track: PlayerTrack): String =
    track.language?.takeIf { it.isNotBlank() }?.let(::languageLabel) ?: track.label.ifBlank { "Unknown" }

/** The file's tracks by language, in the order the file first lists each. */
private fun fileTrackGroups(tracks: List<PlayerTrack>): List<Pair<String, List<PlayerTrack>>> =
    tracks.groupBy(::trackLanguage).toList()

@Composable
private fun FileTrackRow(
    track: PlayerTrack,
    tracks: PlayerTracks,
    selectedAddonId: String?,
    onSelectTrack: (String?) -> Unit,
    modifier: Modifier = Modifier,
    /** Under a language heading the track's own name leads; the language is the heading's. */
    inGroup: Boolean = false,
) {
    val name = track.label.takeIf { it.isNotBlank() && it != track.language }
    TvRowSurface(
        title = if (inGroup) name ?: trackLanguage(track) else trackLanguage(track),
        hint = if (inGroup) null else name,
        onClick = { onSelectTrack(track.id) },
        modifier = modifier,
        trailing = { Selected(selectedAddonId == null && track.id == tracks.selectedSubtitleId) },
    )
}

/** A language heading that opens and closes its group; checked when it hides the current choice. */
@Composable
private fun GroupRow(language: String, count: Int, open: Boolean, holdsChoice: Boolean, onToggle: () -> Unit) {
    TvRowSurface(
        title = language,
        hint = if (count == 1) "1 subtitle" else "$count subtitles",
        onClick = onToggle,
        modifier = Modifier.semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (holdsChoice && !open) Selected(true)
                Icon(
                    HaloIcons.ChevronDown,
                    contentDescription = null,
                    tint = HaloColors.TextDim,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (open) 180f else 0f },
                )
            }
        },
    )
}

/**
 * How the captions look and when they show. Steps for size and delay; the
 * outline and font move to their next choice with each press, which is
 * quicker with a remote than a list for four options. Font, outline and
 * shadow do nothing to a picture track or to a script keeping its own look,
 * so they say so rather than seem broken.
 */
@Composable
internal fun TvSubtitleStylePanel(
    tracks: PlayerTracks,
    scale: Double,
    delaySeconds: Double,
    font: String?,
    outline: SubtitleOutline,
    shadow: Boolean,
    trackStyling: Boolean,
    /** False where the engine playing cannot shift cues; the row is then left out. */
    delayAvailable: Boolean,
    /** False where the engine playing draws every cue as plain text, so a script's own styling cannot be kept. */
    keepsTrackStyling: Boolean,
    onScale: (Double) -> Unit,
    onDelay: (Double) -> Unit,
    onFont: (String?) -> Unit,
    onOutline: (SubtitleOutline) -> Unit,
    onShadow: (Boolean) -> Unit,
    onTrackStyling: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val format = subtitleFormat(tracks.subtitles.firstOrNull { it.id == tracks.selectedSubtitleId }?.codec)
    val bitmap = format == SubtitleFormat.Bitmap
    val looksInert = bitmap || (format == SubtitleFormat.Ass && trackStyling && keepsTrackStyling)
    val inertHint = if (bitmap) "Not for picture subtitles" else "The track's own styling is in use"
    TvSidePanel(title = "Subtitle style", onDismiss = onDismiss, width = 480.dp) {
        LazyColumn(Modifier.weight(1f), contentPadding = FocusRoom, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "size") {
                val percent = (scale * 100).roundToInt()
                StepRow(
                    label = "Size",
                    value = "$percent%",
                    less = "Smaller",
                    more = "Larger",
                    onLess = { onScale(((percent - ScaleStep).coerceAtLeast(MinSubtitleScalePercent)) / 100.0) },
                    onMore = { onScale(((percent + ScaleStep).coerceAtMost(MaxSubtitleScalePercent)) / 100.0) },
                )
            }
            if (delayAvailable) {
                item(key = "delay") {
                    StepRow(
                        label = "Delay",
                        value = formatDelay(delaySeconds),
                        less = "Earlier",
                        more = "Later",
                        onLess = { onDelay(delaySeconds - DelayStep) },
                        onMore = { onDelay(delaySeconds + DelayStep) },
                    )
                }
            }
            item(key = "outline") {
                val index = SubtitleOutlineChoices.indexOfFirst { it.second == outline }.coerceAtLeast(0)
                TvChoiceRow(
                    title = "Outline",
                    value = SubtitleOutlineChoices[index].first,
                    hint = if (looksInert) inertHint else "The edge around each letter",
                    onClick = { onOutline(SubtitleOutlineChoices[(index + 1) % SubtitleOutlineChoices.size].second) },
                )
            }
            item(key = "shadow") {
                TvSwitchRow(
                    title = "Shadow",
                    hint = if (looksInert) inertHint else null,
                    checked = shadow,
                    onCheckedChange = onShadow,
                )
            }
            item(key = "font") {
                val index = SubtitleFontChoices.indexOfFirst { it.family == font }.coerceAtLeast(0)
                TvChoiceRow(
                    title = "Font",
                    value = SubtitleFontChoices[index].label,
                    hint = if (looksInert) inertHint else null,
                    onClick = { onFont(SubtitleFontChoices[(index + 1) % SubtitleFontChoices.size].family) },
                )
            }
            // Meaningful only for styled scripts; a plain or picture track has
            // no styling of its own to keep.
            if (keepsTrackStyling && format != SubtitleFormat.Bitmap && format != SubtitleFormat.Text) {
                item(key = "track-styling") {
                    TvSwitchRow(
                        title = "Keep the track's own styling",
                        hint = "For styled subtitles: their fonts and positions",
                        checked = trackStyling,
                        onCheckedChange = onTrackStyling,
                    )
                }
            }
        }
    }
}

/** A small label over a group of rows in a panel. */
@Composable
private fun PanelLabel(text: String) {
    Text(text.uppercase(), style = TvType.Overline, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
internal fun TvSpeedPanel(rate: Double, onSelect: (Double) -> Unit, onDismiss: () -> Unit) {
    TvSidePanel(title = "Speed", onDismiss = onDismiss) {
        PlaybackRates.forEach { option ->
            TvRowSurface(
                title = formatRate(option),
                onClick = { onSelect(option) },
                trailing = { Selected(option == rate) },
            )
        }
    }
}

/**
 * The season's episodes as cards: each still with its progress or watched
 * mark, then its number, name and status. The episode playing has focus when
 * the panel opens, with the one before it in view above, so the list reads
 * from where the viewer is.
 */
@Composable
internal fun TvEpisodesPanel(
    showTitle: String,
    episodes: List<PlayerEpisode>,
    currentVideoId: String,
    onSelect: (PlayerEpisode) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentIndex = remember(episodes, currentVideoId) { episodes.indexOfFirst { it.videoId == currentVideoId } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    val playing = remember { FocusRequester() }
    // The panel moves focus to its first row as it opens; the playing row
    // takes it from there once it is placed, a frame or two later.
    LaunchedEffect(Unit) {
        if (currentIndex < 0) return@LaunchedEffect
        withTimeoutOrNull(EpisodeFocusClaimMs) {
            while (!runCatching { playing.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) withFrameNanos { }
        }
    }
    TvSidePanel(
        title = showTitle,
        subtitle = episodesPanelSubtitle(episodes, currentVideoId),
        onDismiss = onDismiss,
        width = EpisodesPanelWidth,
    ) {
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = FocusRoom,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(episodes, key = { it.videoId }) { episode ->
                EpisodeRow(
                    episode = episode,
                    state = episodeRowState(episode, currentVideoId),
                    status = episodeStatusLine(episode, currentVideoId),
                    onClick = { onSelect(episode) },
                    modifier = if (episode.videoId == currentVideoId) Modifier.focusRequester(playing) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: PlayerEpisode,
    state: EpisodeRowState,
    status: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tag = episodeTagLabel(episode)
    TvSurface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = listOfNotNull(tag, episode.name, status).joinToString(", ") },
        cornerRadius = 14.dp,
        colors = TvSurfaceColors(Color.White.copy(alpha = 0.03f), HaloColors.SurfaceHigh),
        focusScale = TvMetrics.RowFocusScale,
        ringBackground = HaloColors.Surface,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EpisodeStill(episode, watched = state == EpisodeRowState.Watched)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tag?.let { Text(it, style = TvType.Overline, maxLines = 1) }
                Text(
                    episode.name,
                    color = if (focused) HaloColors.Text else HaloColors.TextMeta,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                status?.let { EpisodeStatus(it, state) }
            }
        }
    }
}

/** The still, or a plate naming the episode when it has none or it fails to load. */
@Composable
private fun EpisodeStill(episode: PlayerEpisode, watched: Boolean) {
    Box(Modifier.size(StillWidth, StillHeight).clip(RoundedCornerShape(10.dp))) {
        HaloAsyncImage(
            url = episode.thumbnail,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            placeholder = { StillPlate(episodePlateLabel(episode)) },
            failure = { StillPlate(episodePlateLabel(episode)) },
        )
        if (watched) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            Icon(
                HaloIcons.Check,
                contentDescription = null,
                tint = HaloColors.Success,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(HaloColors.OverlayPill)
                    .padding(3.dp)
                    .size(14.dp),
            )
        }
        if (episodeShowsProgress(episode)) {
            TvProgressBar(episode.progress, Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 7.dp))
        }
    }
}

@Composable
private fun BoxScope.StillPlate(label: String) {
    Box(
        Modifier.matchParentSize().background(Brush.linearGradient(listOf(HaloColors.SurfaceHigh, HaloColors.Surface))),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = HaloColors.TextMeta.copy(alpha = 0.6f), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The status line; the episode playing says so in the accent colour, with a play glyph. */
@Composable
private fun EpisodeStatus(text: String, state: EpisodeRowState) {
    if (state != EpisodeRowState.Playing) {
        Text(text, style = TvType.Caption, maxLines = 1)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(HaloIcons.Play, contentDescription = null, tint = HaloColors.AccentLight, modifier = Modifier.size(14.dp))
        Text(text, style = TvType.Caption.copy(color = HaloColors.AccentLight, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

private val EpisodesPanelWidth = 560.dp
private val StillWidth = 176.dp
private val StillHeight = 99.dp

/** How long the playing row keeps asking for focus while the panel lays out. */
private const val EpisodeFocusClaimMs = 600L

/** "+0.2 s", "0.0 s". */
private fun formatDelay(seconds: Double): String {
    val tenths = (seconds * 10).roundToInt()
    val sign = if (tenths > 0) "+" else if (tenths < 0) "−" else ""
    val abs = abs(tenths)
    return "$sign${abs / 10}.${abs % 10} s"
}

/** Room above and below a list for a focused row's enlarged ring. */
private val FocusRoom = PaddingValues(vertical = 8.dp)

private const val DelayStep = 0.1
private const val ScaleStep = 10
