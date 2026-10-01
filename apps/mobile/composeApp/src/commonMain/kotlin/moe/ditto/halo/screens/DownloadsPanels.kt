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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.formatSpeed
import moe.ditto.halo.ui.monoStyle

/**
 * The two readouts above the list: how fast bytes are arriving, and how much
 * room they are taking.
 *
 * The throughput figure alone says little — a link at 2 MB/s may be a slow
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

private val ChartHeight = 44.dp
private val BarCorner = HaloRadius.Sm / 2f
private val BarGap = 3.dp

private val StorageBarHeight = 9.dp
private val LegendSwatch = 8.dp

private val PanelPadding = HaloSpacing.Md
private val ButtonShape = RoundedCornerShape(HaloRadius.Md - 2.dp)

/** Where the throughput head stops stacking and sits on one line. */
private val WideHeadWidth = 700.dp

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
 * second — restarting it would drop samples and make the window lie about how
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
 * The aggregate rate, the queue behind it, the control that stops the lot, and
 * the minute of history under all three.
 */
@Composable
internal fun ThroughputPanel(
    bytesPerSecond: Long,
    queueLine: String,
    control: QueueControl?,
    onControl: () -> Unit,
    history: ThroughputHistory,
    availableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().glassPanel().padding(PanelPadding),
        verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm + 4.dp),
    ) {
        val figure: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
            ) {
                Text(
                    text = rateFigure(bytesPerSecond),
                    style = monoStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        .copy(brush = Brush.verticalGradient(listOf(HaloColors.Primary, HaloColors.TextMeta))),
                    maxLines = 1,
                )
                Text(
                    text = queueLine,
                    style = HaloType.Caption,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        val button: @Composable () -> Unit = {
            control?.let { QueueControlButton(it, onControl) }
        }

        // Side by side where there is room for both, stacked where the figure
        // would otherwise squeeze the button off the edge.
        if (availableWidth >= WideHeadWidth) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                figure()
                button()
            }
        } else {
            figure()
            button()
        }

        ThroughputChart(history)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val caption = monoStyle(fontSize = 10.sp, color = HaloColors.TextDim, letterSpacing = 0.6.sp)
            Text(text = "THROUGHPUT · LAST ${ChartWindowSeconds}s", style = caption)
            Text(text = "PEAK ${formatSpeed(history.peak)?.uppercase() ?: "—"}", style = caption)
        }
    }
}

@Composable
private fun QueueControlButton(control: QueueControl, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(ButtonShape)
            .background(
                Brush.verticalGradient(
                    listOf(HaloColors.Primary.copy(alpha = 0.1f), HaloColors.Primary.copy(alpha = 0.045f)),
                ),
            )
            .border(1.dp, HaloColors.GlassBorder, ButtonShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = HaloSpacing.Sm + 5.dp, vertical = HaloSpacing.Sm + 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (control == QueueControl.PauseAll) HaloIcons.Pause else HaloIcons.Play,
            contentDescription = null,
            tint = HaloColors.Text,
            modifier = Modifier.size(15.dp),
        )
        Text(text = control.label, color = HaloColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The window, normalised to its own peak.
 *
 * Against the peak rather than against a fixed ceiling: the question the chart
 * answers is whether the link is steady, and a steady 800 KB/s should read as
 * steady rather than as a flat line along the bottom of a scale drawn for
 * something faster.
 */
@Composable
private fun ThroughputChart(history: ThroughputHistory) {
    val bars = history.bars
    val peak = history.peak
    Row(
        modifier = Modifier.fillMaxWidth().height(ChartHeight),
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
 * What the downloads occupy, against everything else on the device.
 *
 * Drawn rather than composed for the same reason the progress bar is: three
 * segments in one rounded clip, where a row of weighted boxes would have to
 * divide the remaining width at each step and would not land on the same
 * fractions.
 */
@Composable
internal fun StorageMeterBlock(meter: StorageMeter, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = meter.usedLine, style = HaloType.Caption, maxLines = 1)
            Text(text = meter.freeLine, style = HaloType.Caption, maxLines = 1)
        }

        val downloadsFill = Brush.horizontalGradient(listOf(HaloColors.Accent, HaloColors.AccentLight))
        val otherFill = HaloColors.Primary.copy(alpha = 0.34f)
        val track = HaloColors.Primary.copy(alpha = 0.08f)
        Canvas(Modifier.fillMaxWidth().height(StorageBarHeight)) {
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
                    drawRect(color = otherFill, topLeft = Offset(downloads, 0f), size = Size(other, size.height))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md)) {
            LegendItem("Downloads", HaloColors.Accent)
            LegendItem("Other apps", HaloColors.Primary.copy(alpha = 0.34f))
            LegendItem("Free", HaloColors.Primary.copy(alpha = 0.12f))
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(LegendSwatch)
                .clip(RoundedCornerShape(BarCorner))
                .background(color),
        )
        Text(text = label, style = HaloType.Caption.copy(fontSize = 11.5.sp), maxLines = 1)
    }
}
