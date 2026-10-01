package moe.ditto.halo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import moe.ditto.halo.resources.Res
import moe.ditto.halo.resources.jetbrainsmono_regular
import org.jetbrains.compose.resources.Font

/**
 * JetBrains Mono, used for every string on the player that is a number or comes
 * from the engine: timecodes, percentages, delays, bitrates, track ids, format
 * badges, mpv messages. Everything else stays on the platform's own sans, which
 * is what the rest of the app uses.
 *
 * The reason is alignment, not decoration. A running timecode or a cache
 * percentage re-measures on every tick in a proportional face, so the text
 * shifts under the reader several times a second; a monospaced face is tabular
 * by construction, so those digits hold their column.
 *
 * Only the regular weight is bundled (it is the one file the repo already
 * carries under the OFL). Asking for a heavier [FontWeight] therefore gets a
 * synthesised bold from the platform rather than a drawn one, which is fine at
 * the sizes used here, all 13sp and below.
 */
@Composable
fun rememberMonoFamily(): FontFamily {
    val regular = Font(Res.font.jetbrainsmono_regular)
    return remember(regular) { FontFamily(regular) }
}

/**
 * A mono [TextStyle]. Sizes and weights are passed per use site because the
 * player's numeric text spans 9sp badges up to 13sp timecodes with no shared
 * scale worth naming.
 */
@Composable
fun monoStyle(
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Medium,
    color: Color = HaloColors.Text,
    letterSpacing: TextUnit = 0.sp,
): TextStyle {
    val family = rememberMonoFamily()
    return remember(family, fontSize, fontWeight, color, letterSpacing) {
        TextStyle(
            fontFamily = family,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            letterSpacing = letterSpacing,
        )
    }
}
