package moe.ditto.halo.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.formatSpeed
import moe.ditto.halo.ui.monoStyle

/**
 * The queue panel at the top of the list: how fast bytes are arriving, how
 * much room they are taking, and what is still on its way.
 *
 * The throughput figure alone says little: a link at 2 MB/s may be a slow
 * connection or a fast one falling over. The minute of history beside it is what
 * answers that, which is why the chart is part of the same panel rather than a
 * decoration on it.
 */

/** Bars in the chart, and the window they cover between them. */
private const val ChartBars = 28
private const val ChartWindowSeconds = 60
private const val SampleIntervalMs = ChartWindowSeconds * 1_000L / ChartBars

/** A bar at the very bottom of the track reads as a gap; the floor keeps the row whole. */
private const val MinBarFraction = 0.04f

/** The newest bars are lit; everything behind them fades back. */
private const val LitBars = 4

private const val ChartAnimationMs = 500

private val ChartHeight = 76.dp
/** A phone's chart: the same bars, at a height that leaves the list on the first screen. */
private val ChartHeightCompact = 52.dp
private val BarCorner = HaloRadius.Sm / 2f
private val BarGap = 3.dp

private val StorageBarHeight = HaloSpacing.Sm
private val StorageBarHeightCompact = 6.dp
private val LegendSwatch = 8.dp
private val StorageColumnWidth = 220.dp
private val OtherAppsFill = HaloColors.Primary.copy(alpha = 0.34f)

private val PanelShape = RoundedCornerShape(HaloRadius.Lg)
private val PanelPadding = HaloSpacing.Md + HaloSpacing.Xs
private val PanelPaddingCompact = HaloSpacing.Md

/** Between the readouts and the divider over the first queue row. */
private val QueueDividerGap = 18.dp

/** The queue's square controls: the design's 44dp, which is also a thumb's target. */
internal val ControlTarget = 44.dp
internal val ControlShape = RoundedCornerShape(HaloRadius.Md)

/**
 * A rolling minute of transfer rate, in [ChartBars] samples.
 *
 * Session state, held by the screen and never persisted, for the same reason
 * `DownloadEntry.bytesPerSecond` is transient: a chart restored from disk would
 * describe a connection that no longer exists.
 */
@Stable
internal class ThroughputHistory {
    private val samples = mutableStateListOf<Long>().apply { repeat(ChartBars) { add(0L) } }

    val bars: List<Long> get() = samples

    /** The window's own peak, which is what every bar is drawn against. */
    val peak: Long get() = samples.maxOrNull() ?: 0L

    fun record(bytesPerSecond: Long) {
        samples.removeAt(0)
        samples.add(bytesPerSecond.coerceAtLeast(0))
    }
}

/**
 * Samples [rate] on the chart's cadence for as long as the screen is open.
 *
 * The lambda is read fresh on every tick rather than captured, so the loop is
 * started once and never restarted by a rate that changes several times a
 * second. Restarting it would drop samples and make the window lie about how
 * much time it covers.
 */
@Composable
internal fun rememberThroughputHistory(rate: () -> Long): ThroughputHistory {
    val history = remember { ThroughputHistory() }
    val current by rememberUpdatedState(rate)
    LaunchedEffect(history) {
        while (true) {
            delay(SampleIntervalMs)
            history.record(current())
        }
    }
    return history
}

/**
 * The queue: the aggregate rate and the minute of history under it, the room
 * the downloads take beside them, and below a divider every download that has
 * not finished yet.
 *
 * One card rather than three, because the three answer one question between
 * them: how the downloads are going. [rows] is null when nothing is waiting,
 * and the panel closes up to the readouts rather than holding an empty band.
 *
 * [wide] sets storage beside the chart; otherwise it stacks under it, which is
 * what a phone has room for.
 */
@Composable
internal fun QueuePanel(
    bytesPerSecond: Long,
    /** Connections receiving, or null when unknown or nothing is running. */
    connections: Int?,
    /** Nothing is downloading, so the rate is a resting zero rather than a stall. */
    idle: Boolean,
    history: ThroughputHistory,
    /** Null where the platform cannot measure the volume: no meter beats a guessed one. */
    meter: StorageMeter?,
    wide: Boolean,
    modifier: Modifier = Modifier,
    rows: (@Composable ColumnScope.() -> Unit)?,
) {
    val padding = if (wide) PanelPadding else PanelPaddingCompact
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(HaloColors.PanelSunken)
            .border(1.dp, HaloColors.Border, PanelShape)
            .padding(
                start = padding,
                end = padding,
                top = padding,
                // Rows carry their own vertical padding, so the card's foot is
                // only a sliver under the last one.
                bottom = if (rows != null) HaloSpacing.Xs else padding,
            ),
    ) {
        if (wide && meter != null) {
            // Intrinsic height so the divider between the columns runs the
            // height of the taller one rather than collapsing to nothing.
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Lg),
            ) {
                ThroughputColumn(
                    bytesPerSecond = bytesPerSecond,
                    connections = connections,
                    idle = idle,
                    history = history,
                    compact = false,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(1.dp).fillMaxHeight().background(HaloColors.Border))
                StorageColumn(meter, Modifier.width(StorageColumnWidth).fillMaxHeight())
            }
        } else {
            ThroughputColumn(
                bytesPerSecond = bytesPerSecond,
                connections = connections,
                idle = idle,
                history = history,
                compact = !wide,
                modifier = Modifier.fillMaxWidth(),
            )
            meter?.let {
                Box(
                    Modifier
                        .padding(vertical = if (wide) HaloSpacing.Md else HaloSpacing.Sm + 4.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HaloColors.Border),
                )
                if (wide) StorageColumn(it, Modifier.fillMaxWidth()) else StorageStrip(it)
            }
        }
        if (rows != null) {
            Box(
                Modifier
                    .padding(top = if (wide) QueueDividerGap else HaloSpacing.Sm + 4.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(HaloColors.Border),
            )
            rows()
        }
    }
}

/**
 * The rate, what it is running over, the window's peak, and the chart.
 *
 * [compact] is the phone's shape: a smaller figure with the connection pill
 * still beside it, a shorter chart, and the peak moved to the chart's foot,
 * where it has the width a phone's top line does not.
 */
@Composable
private fun ThroughputColumn(
    bytesPerSecond: Long,
    connections: Int?,
    idle: Boolean,
    history: ThroughputHistory,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val figureSize = if (compact) 30.sp else 40.sp
    val peak = "Peak ${formatSpeed(history.peak) ?: rateFigure(0)}".uppercase()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) HaloSpacing.Sm else HaloSpacing.Sm + 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val (figure, unit) = rateParts(bytesPerSecond)
            Text(
                text = figure,
                style = monoStyle(fontSize = figureSize, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
                    .copy(lineHeight = figureSize),
                maxLines = 1,
            )
            Text(
                text = unit,
                style = monoStyle(fontSize = if (compact) 14.sp else 16.sp, color = HaloColors.TextMeta),
                maxLines = 1,
                modifier = Modifier.padding(bottom = if (compact) 2.dp else 3.dp),
            )
            QueueStateNote(connections, idle, Modifier.padding(start = if (compact) 2.dp else 6.dp, bottom = 2.dp))
            Spacer(Modifier.weight(1f))
            if (!compact) {
                Text(text = peak, style = kickerStyle(), maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            }
        }

        ThroughputChart(history, if (compact) ChartHeightCompact else ChartHeight)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
        ) {
            Text(text = "$ChartWindowSeconds s ago".uppercase(), style = kickerStyle(HaloColors.TextFaint))
            Spacer(Modifier.weight(1f))
            if (compact) Text(text = peak, style = kickerStyle(), maxLines = 1)
            Text(text = "Now".uppercase(), style = kickerStyle(HaloColors.TextFaint))
        }
    }
}

/**
 * Beside the rate: how many connections carry it, or that nothing is running.
 * Neither when a transfer runs on a platform that cannot count its connections,
 * since a guessed count would be worse than none.
 */
@Composable
private fun QueueStateNote(connections: Int?, idle: Boolean, modifier: Modifier = Modifier) {
    when {
        idle -> Text(
            text = "Idle",
            color = HaloColors.TextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = modifier.padding(vertical = 4.dp),
        )

        connections != null -> Row(
            modifier = modifier
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .background(HaloColors.Success.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(HaloRadius.Pill))
                    .background(HaloColors.Success),
            )
            Text(
                text = connectionsLabel(connections),
                color = HaloColors.Success,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** Stops everything that is running, or starts everything that was stopped. */
@Composable
internal fun QueueControlButton(
    control: QueueControl,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** A phone's header: the glyph alone in a square, the label kept for screen readers. */
    iconOnly: Boolean = false,
) {
    Row(
        modifier = modifier
            .height(ControlTarget)
            .then(if (iconOnly) Modifier.width(ControlTarget) else Modifier)
            .clip(ControlShape)
            .background(HaloColors.SurfaceHigh)
            .border(1.dp, HaloColors.Border, ControlShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (iconOnly) 0.dp else HaloSpacing.Md - 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (control == QueueControl.PauseAll) HaloIcons.Pause else HaloIcons.Play,
            contentDescription = if (iconOnly) control.label else null,
            tint = HaloColors.Text,
            modifier = Modifier.size(16.dp),
        )
        if (!iconOnly) {
            Text(text = control.label, color = HaloColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The mono, spaced capitals the design labels a readout or a section with. Callers pass the text in capitals. */
@Composable
internal fun kickerStyle(color: Color = HaloColors.TextDim) =
    monoStyle(fontSize = 12.sp, color = color, letterSpacing = 0.96.sp)

/**
 * The window, normalised to its own peak.
 *
 * Against the peak rather than against a fixed ceiling: the question the chart
 * answers is whether the link is steady, and a steady 800 KB/s should read as
 * steady rather than as a flat line along the bottom of a scale drawn for
 * something faster.
 */
@Composable
private fun ThroughputChart(history: ThroughputHistory, height: Dp) {
    val bars = history.bars
    val peak = history.peak
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(BarGap),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEachIndexed { index, sample ->
            val target = if (peak > 0) (sample.toFloat() / peak).coerceIn(0f, 1f) else 0f
            val height by animateFloatAsState(
                targetValue = target.coerceAtLeast(MinBarFraction),
                animationSpec = tween(durationMillis = ChartAnimationMs, easing = FastOutSlowInEasing),
                label = "chart-bar",
            )
            val lit = index >= bars.size - LitBars
            // Older bars recede: the ramp is what gives the row its direction,
            // so the newest end reads as now without needing an axis.
            val depth = if (lit) 1f else 0.35f + (index.toFloat() / bars.size) * 0.45f
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(height)
                    .clip(RoundedCornerShape(topStart = BarCorner, topEnd = BarCorner))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                HaloColors.AccentLight.copy(alpha = depth),
                                HaloColors.Accent.copy(alpha = depth),
                            ),
                        ),
                    ),
            )
        }
    }
}

/**
 * What the downloads occupy, against everything else on the device: the
 * figure, the bar, and a legend that gives each segment its number.
 */
@Composable
private fun StorageColumn(meter: StorageMeter, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Text(text = "STORAGE", style = kickerStyle())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = meter.downloadsFigure,
                style = monoStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                text = meter.ofTotalLine,
                style = monoStyle(fontSize = 13.sp, color = HaloColors.TextMeta),
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
        StorageBar(meter, StorageBarHeight)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            LegendRow("Downloads", meter.downloadsLabel, HaloColors.TextMeta) {
                Modifier.background(HaloColors.Accent)
            }
            LegendRow("Other apps", meter.otherLabel, HaloColors.TextDim) {
                Modifier.background(OtherAppsFill)
            }
            LegendRow("Free", meter.freeLabel, HaloColors.TextDim) {
                Modifier.border(1.dp, HaloColors.TextFaint, RoundedCornerShape(HaloRadius.Pill))
            }
        }
    }
}

/**
 * Drawn rather than composed: two segments in one rounded clip, where a row of
 * weighted boxes would have to divide the remaining width at each step and
 * would not land on the same fractions.
 */
@Composable
private fun StorageBar(meter: StorageMeter, height: Dp) {
    val downloadsFill = Brush.horizontalGradient(listOf(HaloColors.Accent, HaloColors.AccentLight))
    val track = HaloColors.Primary.copy(alpha = 0.08f)
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val radius = CornerRadius(size.height / 2f)
        val clip = Path().apply {
            addRoundRect(
                RoundRect(left = 0f, top = 0f, right = size.width, bottom = size.height, cornerRadius = radius),
            )
        }
        clipPath(clip) {
            drawRect(color = track)
            val downloads = size.width * meter.downloadsFraction
            val other = size.width * meter.otherFraction
            if (downloads > 0f) drawRect(brush = downloadsFill, size = Size(downloads, size.height))
            if (other > 0f) {
                drawRect(color = OtherAppsFill, topLeft = Offset(downloads, 0f), size = Size(other, size.height))
            }
        }
    }
}

/**
 * Storage on a phone: the same three numbers as the tablet's column, in a
 * caption line, a thin bar and one legend line, so the list stays on screen.
 */
@Composable
private fun StorageStrip(meter: StorageMeter) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "STORAGE", style = kickerStyle(), modifier = Modifier.weight(1f))
            Text(
                text = "${meter.downloadsFigure} ${meter.ofTotalLine}",
                style = monoStyle(fontSize = 12.sp, color = HaloColors.TextMeta),
                maxLines = 1,
            )
        }
        StorageBar(meter, StorageBarHeightCompact)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StripLegend("Downloads", meter.downloadsLabel) { Modifier.background(HaloColors.Accent) }
            StripLegend("Other", meter.otherLabel) { Modifier.background(OtherAppsFill) }
            StripLegend("Free", meter.freeLabel) {
                Modifier.border(1.dp, HaloColors.TextFaint, RoundedCornerShape(HaloRadius.Pill))
            }
        }
    }
}

@Composable
private fun StripLegend(label: String, value: String, swatch: () -> Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(LegendSwatch)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .then(swatch()),
        )
        Text(text = label, color = HaloColors.TextDim, fontSize = 12.sp, maxLines = 1)
        Text(text = value, style = monoStyle(fontSize = 12.sp, color = HaloColors.TextMeta), maxLines = 1)
    }
}

@Composable
private fun LegendRow(label: String, value: String, color: Color, swatch: () -> Modifier) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(LegendSwatch)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .then(swatch()),
        )
        Text(text = label, color = color, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(text = value, style = monoStyle(fontSize = 13.sp, color = color), maxLines = 1)
    }
}
