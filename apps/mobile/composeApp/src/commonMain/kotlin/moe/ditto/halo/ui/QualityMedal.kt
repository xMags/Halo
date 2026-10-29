package moe.ditto.halo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.browse.Medal
import moe.ditto.halo.browse.MedalFinish

/**
 * A source's quality as a medal: gold for 4K, silver for Full HD, bronze for
 * HD and a plain badge below that. The resolution sits on a dark plate in the
 * metal's own colour, and the tag beside it (`HDR`, `DV`, or the tier) is cut
 * into the metal. A badge with no tag gives its plate the whole width rather
 * than leaving an empty box beside it.
 */
@Composable
internal fun QualityMedal(medal: Medal, modifier: Modifier = Modifier, large: Boolean = false) {
    val finish = finishOf(medal.finish)
    val shape = RoundedCornerShape(6.dp)
    val description = listOfNotNull(medal.plate, medal.tag).joinToString(" ")
    Row(
        modifier = modifier
            .size(width = if (large) 112.dp else 96.dp, height = if (large) 36.dp else 30.dp)
            .clip(shape)
            .background(finish.rim)
            .border(1.dp, finish.edge, shape)
            .padding(3.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val plate = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(finish.plate)
            .padding(horizontal = 6.dp)
        Box(
            modifier = if (medal.tag == null) plate.weight(1f) else plate.widthIn(min = if (large) 56.dp else 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = medal.plate,
                style = monoStyle(if (large) 15.sp else 13.sp, FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                    .copy(brush = finish.ink),
                maxLines = 1,
            )
        }
        medal.tag?.let { tag ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = tag,
                    style = monoStyle(
                        if (large) 12.sp else 10.5.sp,
                        FontWeight.ExtraBold,
                        color = finish.tagInk,
                        letterSpacing = 1.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

private class Finish(
    val rim: Brush,
    val edge: Color,
    val plate: Color,
    val ink: Brush,
    val tagInk: Color,
)

/** A struck metal: light at the top, a hard fold at the middle, light again below. */
private fun metal(top: Long, upper: Long, lower: Long, bottom: Long) = Brush.verticalGradient(
    0f to Color(top),
    0.46f to Color(upper),
    0.54f to Color(lower),
    1f to Color(bottom),
)

private val MetalEdge = Color.Black.copy(alpha = 0.35f)
private val MetalPlate = Color(0xFF0B0B0D)

private val Gold = Finish(
    rim = metal(0xFFF8E7A6, 0xFFE7C25C, 0xFFC79A2E, 0xFFEBCC72),
    edge = MetalEdge,
    plate = MetalPlate,
    ink = Brush.verticalGradient(listOf(Color(0xFFF8E7A6), Color(0xFFD3A63A))),
    tagInk = Color(0xFF14100A),
)

private val Silver = Finish(
    rim = metal(0xFFF4F6F9, 0xFFC9CFD8, 0xFF959DAA, 0xFFDDE2E8),
    edge = MetalEdge,
    plate = MetalPlate,
    ink = Brush.verticalGradient(listOf(Color.White, Color(0xFFAEB6C2))),
    tagInk = Color(0xFF15181D),
)

private val Bronze = Finish(
    rim = metal(0xFFF3CFAC, 0xFFCF9468, 0xFFA2663D, 0xFFDDAA80),
    edge = MetalEdge,
    plate = MetalPlate,
    ink = Brush.verticalGradient(listOf(Color(0xFFF6D2AE), Color(0xFFC08356))),
    tagInk = Color(0xFF1C120B),
)

private val Plain = Finish(
    rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.10f))),
    edge = Color.White.copy(alpha = 0.12f),
    plate = Color(0xFF18181B),
    ink = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.6f))),
    tagInk = Color.White.copy(alpha = 0.45f),
)

private fun finishOf(finish: MedalFinish): Finish = when (finish) {
    MedalFinish.Gold -> Gold
    MedalFinish.Silver -> Silver
    MedalFinish.Bronze -> Bronze
    MedalFinish.Plain -> Plain
}
