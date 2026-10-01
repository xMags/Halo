package moe.ditto.halo.screens.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloPlayerColors
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.monoStyle

// The rail sits on its own dark panel rather than over video, so its hairlines
// and fills are lighter than the chrome's.
private val RowSelectedFill = Color.White.copy(alpha = 0.08f)
private val RowLabel = Color.White.copy(alpha = 0.90f)
private val RowDetail = Color.White.copy(alpha = 0.45f)
private val BadgeFill = Color.White.copy(alpha = 0.05f)
private val BadgeBorder = Color.White.copy(alpha = 0.10f)
private val CardFill = Color.White.copy(alpha = 0.055f)
private val CardBorder = Color.White.copy(alpha = 0.08f)
private val Hairline = Color.White.copy(alpha = 0.09f)
private val StepperButtonFill = Color.White.copy(alpha = 0.10f)
private val StepperValueFill = Color.White.copy(alpha = 0.07f)
private val SliderTrack = Color.White.copy(alpha = 0.16f)
private val ChipIdleFill = Color.White.copy(alpha = 0.08f)

/** How far a control fades when it is present but cannot do anything. */
private const val InertAlpha = 0.40f
private const val DisabledAlpha = 0.45f

@Composable
internal fun RailSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = HaloColors.TextDim,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = modifier,
    )
}

@Composable
internal fun RailHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

/**
 * A track's own format, which is the single most useful thing to know about it:
 * it predicts whether styling applies, whether the text can be restyled at all,
 * and how many channels the audio has. Colour-coded so the list can be scanned
 * without reading.
 */
@Composable
internal fun FormatBadge(format: String, modifier: Modifier = Modifier) {
    if (format.isBlank()) return
    val tone = when (format) {
        "ASS" -> HaloColors.Gold
        "SRT" -> HaloColors.Success
        "PGS" -> HaloColors.TextDim
        else -> HaloColors.TextMeta
    }
    Text(
        text = format,
        style = monoStyle(fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, color = tone, letterSpacing = 0.4.sp),
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(BadgeFill)
            .border(1.dp, BadgeBorder, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/**
 * One selectable track. Shared by the subtitle and audio lists so a row cannot
 * come to mean two different things depending on which tab it is in.
 */
@Composable
internal fun RailSelectableRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    format: String? = null,
    onDisk: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HaloRadius.Md))
            .background(if (selected) RowSelectedFill else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) Color.White else RowLabel,
                fontSize = 14.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    color = RowDetail,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // Download, not a tick: a tick already means "watched" everywhere else
        // in the app, and two meanings for one glyph is worse than a busier one.
        if (onDisk) {
            Icon(
                imageVector = HaloIcons.Download,
                contentDescription = "Downloaded",
                tint = HaloColors.Success,
                modifier = Modifier.size(15.dp),
            )
        }
        if (format != null) FormatBadge(format)
        if (selected) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(HaloRadius.Pill))
                    .background(HaloColors.Accent),
            )
        }
    }
}

/**
 * One language's worth of addon results, folded away.
 *
 * The count is on the header because it is what decides whether opening the
 * group is worth anything, and the dot marks the group holding what is playing
 * so a folded rail still says where the current subtitle came from.
 */
@Composable
internal fun RailGroupHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    holdsSelection: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HaloRadius.Md))
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One glyph turned rather than two glyphs swapped, so the state reads as
        // the same control in two positions.
        Icon(
            imageVector = HaloIcons.ChevronDown,
            contentDescription = null,
            tint = HaloColors.TextDim,
            modifier = Modifier.size(14.dp).rotate(if (expanded) 0f else -90f),
        )
        Text(
            text = label,
            color = RowLabel,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = count.toString(),
            style = monoStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HaloColors.TextMeta),
        )
        Box(Modifier.weight(1f))
        if (holdsSelection) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(HaloRadius.Pill))
                    .background(HaloColors.Accent),
            )
        }
    }
}

/** The chrome shared by the four appearance cards. */
@Composable
internal fun RailCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HaloRadius.Md))
            .background(CardFill)
            .border(1.dp, CardBorder, RoundedCornerShape(HaloRadius.Md))
            .padding(12.dp),
        content = content,
    )
}

/**
 * A card's title row: what the control is, what it currently reads, and a hint
 * saying what it actually does to the engine. The hint is not decoration — the
 * difference between restyling a text track and scaling a bitmap one is the
 * whole reason some of these controls sometimes do nothing.
 */
@Composable
internal fun RailCardHeader(
    label: String,
    hint: String,
    value: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = HaloColors.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Text(
                text = hint,
                color = HaloColors.TextDim,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (value != null) {
            Text(
                text = value,
                style = monoStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

private val SliderTrackHeight = 4.dp
private val SliderThumbSize = 16.dp
private val SliderHitHeight = 28.dp

/**
 * Continuous, and applied on every movement rather than on release.
 *
 * That is the point of the control: the caption on the video rescales under the
 * thumb, so the size is chosen by looking at the result instead of by guessing a
 * number. The old player could only offer fixed steps because they were
 * creation-time options that rebuilt the whole player.
 */
@Composable
internal fun RailSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var widthPx by remember { mutableIntStateOf(0) }

    fun emit(x: Float) {
        if (widthPx <= 0) return
        onValueChange(valueRange.start + (x / widthPx).coerceIn(0f, 1f) * span)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SliderHitHeight)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(widthPx) {
                if (widthPx <= 0) return@pointerInput
                detectTapGestures { offset -> emit(offset.x) }
            }
            .pointerInput(widthPx) {
                if (widthPx <= 0) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset -> emit(offset.x) },
                    onHorizontalDrag = { change, _ -> emit(change.position.x) },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SliderTrackHeight)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .background(SliderTrack),
        )
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(SliderTrackHeight)
                    .clip(RoundedCornerShape(HaloRadius.Pill))
                    .background(HaloColors.Accent),
            )
        }
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset((fraction * widthPx - SliderThumbSize.toPx() / 2f).roundToInt(), 0) }
                .size(SliderThumbSize)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .background(Color.White),
        )
    }
}

/**
 * Marks points on a slider's scale, each drawn where its value actually sits.
 *
 * Spacing them evenly would be a lie on any scale that is not centred on its
 * midpoint: on 50 to 200, the 100 mark belongs a third of the way along, and an
 * evenly spaced label puts it under the middle of the track where the thumb
 * never is at that value.
 */
@Composable
internal fun RailSliderTicks(
    ticks: List<Float>,
    valueRange: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    modifier: Modifier = Modifier,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    var widthPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width },
    ) {
        ticks.forEach { tick ->
            val fraction = ((tick - valueRange.start) / span).coerceIn(0f, 1f)
            Text(
                text = label(tick),
                style = monoStyle(fontSize = 10.sp, color = HaloPlayerColors.TickLabel),
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0))
                    layout(constraints.maxWidth, placeable.height) {
                        // Centred on the mark, then pulled inside the track so
                        // the first and last labels are not half cut off.
                        val centred = (fraction * widthPx - placeable.width / 2f).roundToInt()
                        placeable.place(centred.coerceIn(0, (widthPx - placeable.width).coerceAtLeast(0)), 0)
                    }
                },
            )
        }
    }
}

/**
 * The delay stepper, used for subtitles and for audio.
 *
 * Tapping the value resets it to zero, which is the only adjustment anyone makes
 * in a hurry: a delay that has been nudged too far is easier to abandon than to
 * walk back one step at a time.
 */
@Composable
internal fun RailDelayStepper(
    seconds: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.alpha(if (enabled) 1f else DisabledAlpha),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton(
            label = "−",
            contentDescription = "Decrease delay",
            enabled = enabled,
            onClick = { onChange(seconds - DelayStepSeconds) },
        )
        Text(
            text = formatDelay(seconds),
            style = monoStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier
                .widthIn(min = 74.dp)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .background(StepperValueFill)
                .clickable(enabled = enabled, role = Role.Button) { onChange(0.0) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        StepperButton(
            label = "+",
            contentDescription = "Increase delay",
            enabled = enabled,
            onClick = { onChange(seconds + DelayStepSeconds) },
        )
    }
}

@Composable
private fun StepperButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(StepperButtonFill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = HaloColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

private val SwitchWidth = 44.dp
private val SwitchHeight = 26.dp
private val SwitchKnob = 20.dp

@Composable
internal fun RailSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val knobOffset by animateDpAsState(
        targetValue = if (checked) SwitchWidth - SwitchKnob - 3.dp else 3.dp,
        animationSpec = tween(durationMillis = 120),
        label = "railSwitchKnob",
    )
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .size(width = SwitchWidth, height = SwitchHeight)
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(if (checked) HaloColors.Accent else HaloPlayerColors.SwitchOff)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(SwitchKnob)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .background(Color.White),
        )
    }
}

/**
 * The subtitle font choices. The families are the desktop client's list
 * verbatim, so a synced `subtitleFontFamily` means the same typeface on every
 * device rather than whatever each platform happens to resolve.
 */
internal data class SubtitleFontChoice(val label: String, val family: String?)

internal val SubtitleFontChoices = listOf(
    SubtitleFontChoice("Default", null),
    SubtitleFontChoice("Inter", "Inter"),
    SubtitleFontChoice("Serif", "Source Serif 4"),
    SubtitleFontChoice("Mono", "JetBrains Mono"),
)

@Composable
internal fun RailFontChips(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    inert: Boolean = false,
) {
    Row(
        modifier = modifier.alpha(if (inert) InertAlpha else 1f),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SubtitleFontChoices.forEach { choice ->
            val active = choice.family == selected
            Text(
                text = choice.label,
                color = if (active) Color.White else HaloColors.TextDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(HaloRadius.Pill))
                    .background(if (active) HaloColors.Accent else ChipIdleFill)
                    .clickable(enabled = !inert, role = Role.RadioButton) { onSelect(choice.family) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}
