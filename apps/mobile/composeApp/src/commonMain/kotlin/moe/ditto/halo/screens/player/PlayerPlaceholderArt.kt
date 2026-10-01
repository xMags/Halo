package moe.ditto.halo.screens.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val StripeDark = Color(0xFF101319)
private val StripeLight = Color(0xFF161A22)
private val StripeWidth = 10.dp

/**
 * Diagonal stripes standing in for artwork that is absent: an episode with no
 * still, and the scrub card before its frame has been decoded or on a source
 * whose frames cannot be read at all.
 *
 * It is deliberately not a flat fill or a spinner. A blank rectangle is
 * indistinguishable from an image that failed to load, and that difference
 * matters here: this says "nothing is meant to be here" at a glance.
 *
 * The angle approximates the design's, which is not exactly 45 degrees. It is a
 * placeholder, so the band spacing is what carries the meaning, not the tilt.
 */
internal fun Modifier.placeholderStripes(): Modifier = drawBehind {
    drawRect(StripeDark)

    val band = StripeWidth.toPx()
    val period = band * 2f
    // Each band is drawn as a thick line running corner to corner, so the run
    // has to start a full height's worth off-screen for the first stripe to
    // cover the top-left corner.
    var x = -size.height
    while (x < size.width + size.height) {
        drawLine(
            color = StripeLight,
            start = Offset(x, size.height),
            end = Offset(x + size.height, 0f),
            strokeWidth = band,
        )
        x += period
    }
}
