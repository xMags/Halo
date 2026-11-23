package moe.ditto.halo.tv.kit

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import moe.ditto.halo.ui.HaloColors

/**
 * Fixed TV metrics, designed for a 960 x 540 dp screen (a 1080p TV at 320
 * dpi). TV never uses the phone's responsive classes: at that size they would
 * call it a phone.
 */
internal object TvMetrics {
    /** Left and right overscan margin. */
    val SideMargin = 48.dp

    /** Top and bottom overscan margin. */
    val VerticalMargin = 27.dp

    /** Width of the icon-only navigation rail. */
    val RailCollapsed = 104.dp

    /** Width of the rail while it has focus and shows its labels. */
    val RailExpanded = 248.dp

    /** Where screen content starts, just right of the collapsed rail. */
    val ContentStart = 128.dp

    val RowGap = 24.dp
    val CardGap = 16.dp

    val ButtonHeight = 44.dp
    val RowHeight = 64.dp

    val PosterWidth = 128.dp
    val PosterHeight = 192.dp
    val WideCardWidth = 200.dp
    val WideCardHeight = 112.dp

    /** Focused controls grow by this much, around their centre. */
    const val FocusScale = 1.06f

    /** Rows of text and settings grow less, since they are wide. */
    const val RowFocusScale = 1.02f
    val FocusRingWidth = 2.dp
    val FocusRingGap = 2.dp
}

/** TV type sizes: larger than the phone's, read from across a room. */
internal object TvType {
    val Hero = TextStyle(
        color = HaloColors.Text,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.02).em,
    )
    val Title = TextStyle(
        color = HaloColors.Text,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.01).em,
    )
    val Heading = TextStyle(color = HaloColors.Text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val Body = TextStyle(color = HaloColors.TextDim, fontSize = 18.sp, lineHeight = 26.sp)
    val Label = TextStyle(color = HaloColors.Text, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val Meta = TextStyle(color = HaloColors.TextMeta, fontSize = 15.sp, lineHeight = 20.sp)
    val Caption = TextStyle(color = HaloColors.TextDim, fontSize = 14.sp, lineHeight = 18.sp)
    val Overline = TextStyle(
        color = HaloColors.TextDim,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.1.em,
    )
}
