package moe.ditto.halo.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.player.PlayerTrack
import moe.ditto.halo.player.PlayerTracks
import moe.ditto.halo.resources.Res
import moe.ditto.halo.resources.inter_bold
import moe.ditto.halo.resources.inter_regular
import moe.ditto.halo.resources.jetbrainsmono_regular
import moe.ditto.halo.resources.sourceserif4_bold
import moe.ditto.halo.resources.sourceserif4_regular
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloPlayerColors
import moe.ditto.halo.ui.HaloRadius
import org.jetbrains.compose.resources.Font

private val PreviewStripFill = Color(0xFF12141B)
private val PreviewStripBorder = Color.White.copy(alpha = 0.08f)
private val NoteCardFill = Color.White.copy(alpha = 0.05f)
private val SpeedIdleFill = Color.White.copy(alpha = 0.05f)
private val SpeedIdleBorder = Color.White.copy(alpha = 0.08f)

/** How much smaller the preview draws the caption than the video does. */
private const val PreviewCaptionRatio = 0.82f

internal val PlaybackRates = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

/**
 * What a subtitle track is made of, which decides which appearance controls can
 * do anything at all. Text tracks can be restyled; a bitmap track is a picture
 * of text and can only be scaled.
 */
internal enum class SubtitleFormat {
    Ass,
    Text,
    Bitmap,
    Unknown,
}

/**
 * Every subtitle there is to choose from: the file's own tracks, then what the
 * addons offered, folded by language.
 */
@Composable
internal fun SubtitleTracksTab(
    tracks: PlayerTracks,
    selectedAddonId: String?,
    addonSubtitles: List<AddonSubtitleOption>,
    addonSubtitlesFetching: Boolean,
    subtitleLoadError: String?,
    preferredLang: String?,
    expandedLanguages: Set<String>?,
    onSelectTrack: (String?) -> Unit,
    onSelectAddonSubtitle: (AddonSubtitleOption) -> Unit,
    onToggleLanguage: (String, Set<String>) -> Unit,
) {
    RailSectionLabel("IN THIS FILE")
    RailSelectableRow(
        label = "Off",
        detail = "No subtitles",
        selected = tracks.selectedSubtitleId == null && selectedAddonId == null,
        onClick = { onSelectTrack(null) },
    )
    tracks.subtitles.forEach { track ->
        RailSelectableRow(
            label = track.label,
            detail = subtitleDetail(track),
            selected = track.id == tracks.selectedSubtitleId && selectedAddonId == null,
            onClick = { onSelectTrack(track.id) },
            format = subtitleBadge(track.codec),
        )
    }

    RailSectionLabel("FROM ADDONS", modifier = Modifier.padding(top = 4.dp))
    // Three states, and they are not the same thing: still asking, asked and
    // told nothing, and told something. Showing an empty section while the
    // request is in flight reads as "there are none".
    if (addonSubtitles.isEmpty()) {
        Text(
            text = if (addonSubtitlesFetching) {
                "Looking for subtitles…"
            } else {
                "No addon offered subtitles for this file."
            },
            color = HaloColors.TextDim,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }

    val groups = remember(addonSubtitles, preferredLang) {
        subtitleLanguageGroups(addonSubtitles, preferredLang)
    }
    // Recomputed rather than remembered: results arrive after the rail is
    // already open, and the group holding the current choice is only knowable
    // once they have.
    val expanded = expandedLanguages ?: defaultExpandedSubtitleLanguages(groups, selectedAddonId)
    groups.forEach { group ->
        val open = group.language in expanded
        RailGroupHeader(
            label = group.language,
            count = group.options.size,
            expanded = open,
            holdsSelection = group.options.any { it.id == selectedAddonId },
            onToggle = { onToggleLanguage(group.language, expanded) },
        )
        if (!open) return@forEach
        group.options.forEach { subtitle ->
            RailSelectableRow(
                // The addon is the useful name inside a language group: the
                // language is the header, so repeating it in every row would
                // leave the addon and its id to fight for one line.
                label = subtitle.addonName,
                detail = subtitle.subId,
                selected = subtitle.id == selectedAddonId,
                onClick = { onSelectAddonSubtitle(subtitle) },
                format = subtitle.format,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
    if (subtitleLoadError != null) {
        Text(
            text = subtitleLoadError,
            color = HaloColors.Danger,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * How the captions look, which is judged against the picture and so lives one
 * tap away rather than below however many results an addon returned.
 */
@Composable
internal fun SubtitleAppearanceTab(
    tracks: PlayerTracks,
    subtitleScale: Double,
    subtitleDelaySeconds: Double,
    subtitleFont: String?,
    trackStyling: Boolean,
    bundledFonts: Set<String>,
    captionBaseSize: TextUnit,
    onScaleChange: (Double) -> Unit,
    onDelayChange: (Double) -> Unit,
    onTrackStylingChange: (Boolean) -> Unit,
    onFontChange: (String?) -> Unit,
) {
    val selectedTrack = tracks.subtitles.firstOrNull { it.id == tracks.selectedSubtitleId }
    val format = subtitleFormat(selectedTrack?.codec)
    val bitmap = format == SubtitleFormat.Bitmap

    RailCard {
        RailCardHeader(
            label = "Size",
            hint = if (bitmap) "Bitmap track, scaled but not restyled" else "mpv sub-scale, live",
            value = formatScalePercent(subtitleScale),
        )
        RailSlider(
            value = subtitleScale.toFloat(),
            valueRange = 0.5f..2f,
            onValueChange = { onScaleChange(it.toDouble()) },
            modifier = Modifier.padding(top = 6.dp),
        )
        RailSliderTicks(
            ticks = listOf(0.5f, 1f, 2f),
            valueRange = 0.5f..2f,
            label = { scale -> (scale * 100f).toInt().toString() },
            modifier = Modifier.padding(top = 2.dp),
        )
    }

    RailCard {
        RailCardHeader(label = "Delay", hint = "Shifts the track against the picture")
        RailDelayStepper(
            seconds = subtitleDelaySeconds,
            onChange = onDelayChange,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    RailCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Track styling",
                    color = HaloColors.Text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = trackStylingHint(format, trackStyling),
                    color = HaloColors.TextDim,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // Disabled only where the format makes it meaningless. A track
            // whose codec the engine has not reported yet may well be styled,
            // so the switch stays usable rather than dead.
            RailSwitch(
                checked = trackStyling,
                onCheckedChange = onTrackStylingChange,
                enabled = format != SubtitleFormat.Bitmap && format != SubtitleFormat.Text,
            )
        }
        // With a script's own styling in force, a font choice is ignored: the
        // chips stay so the current selection is still readable, but they are
        // dimmed to say they are not in effect.
        val chipsInert = bitmap || (format == SubtitleFormat.Ass && trackStyling)
        RailFontChips(
            selected = subtitleFont,
            onSelect = onFontChange,
            modifier = Modifier.padding(top = 10.dp),
            inert = chipsInert,
        )
        // A family the app does not ship is a request the caption renderer
        // substitutes for silently, so the chip would look applied and change
        // nothing. Only said when it is actually the case.
        if (!chipsInert) {
            unbundledFontNotice(subtitleFont, bundledFonts)?.let { notice ->
                Text(
                    text = notice,
                    color = HaloColors.TextDim,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    RailCard {
        RailSectionLabel("PREVIEW")
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(HaloRadius.Sm))
                .background(PreviewStripFill)
                .border(1.dp, PreviewStripBorder, RoundedCornerShape(HaloRadius.Sm))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val previewFamily = rememberSubtitlePreviewFamily(subtitleFont)
            Text(
                text = PlayerFixtures.CaptionSample,
                color = HaloColors.Text,
                fontSize = captionBaseSize * subtitleScale.toFloat() * PreviewCaptionRatio,
                fontFamily = previewFamily,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal enum class SubtitlePreviewFamily {
    Default,
    Inter,
    SourceSerif4,
    JetBrainsMono,
}

/** The same family mapping used by the chips and by libass. */
internal fun subtitlePreviewFamily(font: String?): SubtitlePreviewFamily = when (font) {
    "Inter" -> SubtitlePreviewFamily.Inter
    "Source Serif 4" -> SubtitlePreviewFamily.SourceSerif4
    "JetBrains Mono" -> SubtitlePreviewFamily.JetBrainsMono
    else -> SubtitlePreviewFamily.Default
}

@Composable
private fun rememberSubtitlePreviewFamily(font: String?): FontFamily = when (subtitlePreviewFamily(font)) {
    SubtitlePreviewFamily.Default -> FontFamily.Default
    SubtitlePreviewFamily.Inter -> FontFamily(
        Font(Res.font.inter_regular, weight = FontWeight.Normal),
        Font(Res.font.inter_bold, weight = FontWeight.Bold),
    )
    SubtitlePreviewFamily.SourceSerif4 -> FontFamily(
        Font(Res.font.sourceserif4_regular, weight = FontWeight.Normal),
        Font(Res.font.sourceserif4_bold, weight = FontWeight.Bold),
    )
    SubtitlePreviewFamily.JetBrainsMono -> FontFamily(
        Font(Res.font.jetbrainsmono_regular, weight = FontWeight.Normal),
    )
}

/**
 * The switch means different things per format, and for two of them it means
 * nothing at all. Saying so is the only way the control is honest: a disabled
 * switch with no explanation reads as a bug.
 */
private fun trackStylingHint(format: SubtitleFormat, enabled: Boolean): String = when (format) {
    SubtitleFormat.Ass ->
        if (enabled) "Keeping the script's own fonts and positions"
        else "Overriding the script with Halo styling"
    SubtitleFormat.Text -> "Plain text track: Halo styling always applies"
    SubtitleFormat.Bitmap -> "PGS is rendered images: font and outline do not apply"
    SubtitleFormat.Unknown -> "Applies to styled tracks only"
}

/**
 * Null when the choice will be honoured, which is the ordinary case and gets no
 * text at all. "Default" is always honoured: it asks for nothing in particular.
 *
 * The substitute is deliberately not named. The renderer picks it, and what it
 * picks depends on what else is loaded, so promising a particular typeface here
 * would be a guess. Observed on Android: an unbundled name falls back to
 * whichever bundled font is present, not to a system face.
 */
internal fun unbundledFontNotice(selected: String?, bundled: Set<String>): String? {
    val family = selected ?: return null
    if (family in bundled) return null
    return "$family is not bundled, so captions use a substitute typeface."
}

internal fun subtitleFormat(codec: String?): SubtitleFormat = when (codec?.lowercase()) {
    "ass", "ssa", "ass-text" -> SubtitleFormat.Ass
    "subrip", "srt", "webvtt", "vtt", "text" -> SubtitleFormat.Text
    "hdmv_pgs_subtitle", "pgssub", "pgs" -> SubtitleFormat.Bitmap
    null -> SubtitleFormat.Unknown
    else -> SubtitleFormat.Unknown
}

internal fun subtitleBadge(codec: String?): String? = when (subtitleFormat(codec)) {
    SubtitleFormat.Ass -> "ASS"
    SubtitleFormat.Text -> "SRT"
    SubtitleFormat.Bitmap -> "PGS"
    SubtitleFormat.Unknown -> codec?.uppercase()?.takeIf { it.length <= 8 }
}

internal fun subtitleDetail(track: PlayerTrack): String? = listOfNotNull(
    track.language,
    subtitleBadge(track.codec),
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

internal fun audioBadge(track: PlayerTrack): String? {
    val codec = track.codec?.uppercase()?.takeIf { it.length <= 8 } ?: return null
    val layout = track.channels?.let(::channelLayout) ?: return codec
    return "$codec $layout"
}

internal fun audioDetail(track: PlayerTrack): String? = listOfNotNull(
    track.language,
    track.sampleRateHz?.let { "${it / 1000} kHz" },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

private fun channelLayout(channels: Int): String = when (channels) {
    1 -> "mono"
    2 -> "stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels ch"
}

@Composable
internal fun AudioTab(
    tracks: PlayerTracks,
    audioDelaySeconds: Double,
    onSelectTrack: (String?) -> Unit,
    onDelayChange: (Double) -> Unit,
) {
    RailSectionLabel("TRACKS")
    if (tracks.audio.isEmpty()) {
        Text(
            text = "This source reports no audio tracks.",
            color = HaloColors.TextDim,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
    tracks.audio.forEach { track ->
        RailSelectableRow(
            label = track.label,
            detail = audioDetail(track),
            selected = track.id == tracks.selectedAudioId,
            onClick = { onSelectTrack(track.id) },
            format = audioBadge(track),
        )
    }

    RailHairline(Modifier.padding(vertical = 2.dp))

    RailCard {
        RailCardHeader(label = "Delay", hint = "Shifts the sound against the picture")
        RailDelayStepper(
            seconds = audioDelaySeconds,
            onChange = onDelayChange,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
internal fun SpeedTab(
    rate: Double,
    onRateChange: (Double) -> Unit,
) {
    RailSectionLabel("SPEED")
    PlaybackRates.chunked(3).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { candidate ->
                SpeedCell(
                    rate = candidate,
                    selected = candidate == rate,
                    onClick = { onRateChange(candidate) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HaloRadius.Md))
            .background(NoteCardFill)
            .padding(12.dp),
    ) {
        Text(
            text = "Pitch is corrected up to 2×. Subtitle timing follows the rate automatically.",
            color = HaloColors.TextDim,
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun SpeedCell(
    rate: Double,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(HaloRadius.Md)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) HaloPlayerColors.ChipActiveFill else SpeedIdleFill)
            .border(
                width = 1.dp,
                color = if (selected) HaloPlayerColors.ChipActiveBorder else SpeedIdleBorder,
                shape = shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatRate(rate),
            color = if (selected) Color.White else HaloColors.TextMeta,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = rateDescription(rate),
            color = HaloColors.TextDim,
            fontSize = 10.5.sp,
        )
    }
}

/** Says what a rate does, so the number does not have to be converted mentally. */
private fun rateDescription(rate: Double): String {
    if (rate == 1.0) return "Normal"
    val percent = ((rate - 1.0) * 100.0).let { if (it < 0) -it else it }
    val rounded = percent.toInt()
    return if (rate < 1.0) "$rounded% slower" else "$rounded% faster"
}

