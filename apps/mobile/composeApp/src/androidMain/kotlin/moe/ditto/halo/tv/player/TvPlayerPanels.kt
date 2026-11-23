package moe.ditto.halo.tv.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import moe.ditto.halo.player.PlayerTrack
import moe.ditto.halo.player.PlayerTracks
import moe.ditto.halo.screens.player.AddonSubtitleOption
import moe.ditto.halo.screens.player.MaxSubtitleScalePercent
import moe.ditto.halo.screens.player.MinSubtitleScalePercent
import moe.ditto.halo.screens.player.PlaybackRates
import moe.ditto.halo.screens.player.PlayerEpisode
import moe.ditto.halo.screens.player.formatRate
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvRowSurface
import moe.ditto.halo.tv.kit.TvSidePanel
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.languageLabel

/** The player's side panels. */
internal enum class TvPlayerPanel { Audio, Subtitles, Speed, Episodes }

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

@Composable
internal fun TvSubtitlesPanel(
    tracks: PlayerTracks,
    addonSubtitles: List<AddonSubtitleOption>,
    selectedAddonId: String?,
    loading: Boolean,
    loadError: String?,
    scale: Double,
    delaySeconds: Double,
    onSelectTrack: (String?) -> Unit,
    onSelectAddon: (AddonSubtitleOption) -> Unit,
    onScale: (Double) -> Unit,
    onDelay: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val noneShown = tracks.selectedSubtitleId == null && selectedAddonId == null
    TvSidePanel(title = "Subtitles", onDismiss = onDismiss, width = 480.dp) {
        LazyColumn(Modifier.weight(1f), contentPadding = FocusRoom, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "off") { TvRowSurface(title = "Off", onClick = { onSelectTrack(null) }, trailing = { Selected(noneShown) }) }
            items(tracks.subtitles, key = { "track/${it.id}" }) { track ->
                TvRowSurface(
                    title = track.language?.let(::languageLabel) ?: track.label,
                    hint = "In the file" + (track.label.takeIf { it.isNotBlank() && it != track.language }?.let { " · $it" } ?: ""),
                    onClick = { onSelectTrack(track.id) },
                    trailing = { Selected(selectedAddonId == null && track.id == tracks.selectedSubtitleId) },
                )
            }
            items(addonSubtitles, key = { "addon/${it.id}" }) { option ->
                TvRowSurface(
                    title = languageLabel(option.lang),
                    hint = listOfNotNull(option.addonName, option.subId, option.format).joinToString(" · "),
                    onClick = { onSelectAddon(option) },
                    trailing = { Selected(option.id == selectedAddonId) },
                )
            }
            if (loading) item(key = "loading") { Text("Asking your addons for more…", style = TvType.Caption) }
            loadError?.let { item(key = "error") { Text(it, style = TvType.Caption.copy(color = HaloColors.Danger)) } }
        }
        val percent = (scale * 100).roundToInt()
        StepRow(
            label = "Size",
            value = "$percent%",
            less = "Smaller",
            more = "Larger",
            onLess = { onScale(((percent - ScaleStep).coerceAtLeast(MinSubtitleScalePercent)) / 100.0) },
            onMore = { onScale(((percent + ScaleStep).coerceAtMost(MaxSubtitleScalePercent)) / 100.0) },
        )
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

@Composable
internal fun TvEpisodesPanel(
    title: String,
    episodes: List<PlayerEpisode>,
    currentVideoId: String,
    onSelect: (PlayerEpisode) -> Unit,
    onDismiss: () -> Unit,
) {
    TvSidePanel(title = "Episodes", subtitle = title, onDismiss = onDismiss, width = 480.dp) {
        LazyColumn(Modifier.weight(1f), contentPadding = FocusRoom, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(episodes, key = { it.videoId }) { episode ->
                TvRowSurface(
                    title = "${episode.tag} · ${episode.name}",
                    hint = when {
                        episode.videoId == currentVideoId -> "Playing now"
                        episode.progress >= 0.95f -> "Watched"
                        episode.progress > 0f -> "${(episode.progress * 100).roundToInt()}% watched"
                        else -> null
                    },
                    onClick = { onSelect(episode) },
                    trailing = { Selected(episode.videoId == currentVideoId) },
                )
            }
        }
    }
}

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
