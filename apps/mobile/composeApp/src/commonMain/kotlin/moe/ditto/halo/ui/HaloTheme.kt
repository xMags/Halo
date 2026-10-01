package moe.ditto.halo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object HaloColors {
    val Background = Color(0xFF0A0C11)
    val Surface = Color(0xFF14161D)
    val SurfaceHigh = Color(0xFF1C202A)
    val Border = Color(0xFF252A35)
    val Text = Color(0xFFF4F6FB)
    val TextDim = Color(0xFF8B93A5)
    val Accent = Color(0xFF0A84FF)

    /**
     * The light end of the accent. Only ever the far stop of a gradient that
     * starts at [Accent] — a progress fill, a throughput bar — so that a moving
     * figure reads as lit rather than flat. Never a text or icon colour.
     */
    val AccentLight = Color(0xFF4CC2FF)
    val Danger = Color(0xFFFF6B6B)
    val Success = Color(0xFF5DD39E)
    val Primary = Color.White

    /**
     * The bottom stop of a white button's fill. Paired with [Primary] it gives
     * the button a lit top edge; a flat white one reads as a disabled surface at
     * this size.
     */
    val PrimaryShade = Color(0xFFDDE2EC)
    val OnPrimary = Color.Black
    val OnAccent = Color.White
    val Glass = Color(red = 1f, green = 1f, blue = 1f, alpha = 0.07f)
    val GlassBorder = Color(red = 1f, green = 1f, blue = 1f, alpha = 0.11f)
    val Hairline = Color(red = 1f, green = 1f, blue = 1f, alpha = 0.11f)
    val FieldFill = Color(red = 1f, green = 1f, blue = 1f, alpha = 0.09f)
    val TabBarTint = Color(red = 15f / 255f, green = 17f / 255f, blue = 23f / 255f, alpha = 0.72f)
    val SheetTint = Color(red = 20f / 255f, green = 22f / 255f, blue = 30f / 255f, alpha = 0.72f)
    val OverlayPill = Color(red = 5f / 255f, green = 7f / 255f, blue = 12f / 255f, alpha = 0.82f)
    val Gold = Color(0xFFFFD479)

    /** Secondary line under a title: dot-separated metadata, chip values. */
    val TextMeta = Color(0xFFC7CDD9)
}

/**
 * Colours that exist only inside the player. They are darker and more opaque
 * than the app's glass system on purpose: the player's chrome sits over moving
 * video rather than over the app canvas, so it has to stay legible against an
 * arbitrary frame instead of blending with a known background.
 *
 * Kept apart from [HaloColors] so nothing outside the player reaches for them.
 */
object HaloPlayerColors {
    /** Vertical wash behind the top bar. */
    val ScrimTop = Color(red = 4f / 255f, green = 5f / 255f, blue = 8f / 255f, alpha = 0.72f)

    /** Vertical wash behind the bottom bar; heavier, it carries more controls. */
    val ScrimBottom = Color(red = 4f / 255f, green = 5f / 255f, blue = 8f / 255f, alpha = 0.82f)

    // One smoked glass in four densities. Which one a control gets tracks how
    // much it has to survive being over a bright frame: a lone circular button
    // needs more cover than a chip sitting in a row of them, and the utility
    // pill holds three targets so it carries the most.
    /** Seek buttons. */
    val SeekButtonFill = Color(red = 9f / 255f, green = 11f / 255f, blue = 16f / 255f, alpha = 0.72f)

    /** State chips. */
    val ChipFill = Color(red = 9f / 255f, green = 11f / 255f, blue = 16f / 255f, alpha = 0.62f)

    /** Standalone circular buttons, such as back. */
    val CircleButtonFill = Color(red = 9f / 255f, green = 11f / 255f, blue = 16f / 255f, alpha = 0.50f)

    /** The three-button utility pill. */
    val UtilityPillFill = Color(red = 9f / 255f, green = 11f / 255f, blue = 16f / 255f, alpha = 0.68f)

    /** Play/pause needs the strongest cover because no edge scrim sits behind it. */
    val PlayButtonFill = Color(red = 9f / 255f, green = 11f / 255f, blue = 16f / 255f, alpha = 0.76f)

    /** Audio/subtitles/speed rail. */
    val RailFill = Color(red = 18f / 255f, green = 20f / 255f, blue = 27f / 255f, alpha = 0.90f)

    /** Episode drawer; a shade heavier than the rail because it covers more. */
    val DrawerFill = Color(red = 18f / 255f, green = 20f / 255f, blue = 27f / 255f, alpha = 0.92f)

    // A selected chip is tinted rather than filled, so the video stays readable
    // behind it. The label lifts to a light blue instead of white.
    val ChipActiveFill = Color(red = 10f / 255f, green = 132f / 255f, blue = 255f / 255f, alpha = 0.16f)
    val ChipActiveBorder = Color(red = 10f / 255f, green = 132f / 255f, blue = 255f / 255f, alpha = 0.55f)
    val ChipActiveLabel = Color(0xFF7EC0FF)

    /** Engine strings: mpv messages, track ids, cache figures. */
    val DiagnosticText = Color(0xFF6F7789)

    /** Endpoint labels under a slider's scale. */
    val TickLabel = Color(0xFF565E70)

    /** Track of a switch in its off position. */
    val SwitchOff = Color(0xFF424753)
}

object HaloSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}

object HaloRadius {
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Pill = 999.dp
}

object HaloType {
    val LargeTitle = TextStyle(
        color = HaloColors.Text,
        fontSize = 30.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.6).sp,
    )
    val Title = TextStyle(
        color = HaloColors.Text,
        fontSize = 24.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.2.sp,
    )
    val Heading = TextStyle(
        color = HaloColors.Text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
    )
    val Body = TextStyle(color = HaloColors.Text, fontSize = 14.sp, lineHeight = 20.sp)
    val Callout = TextStyle(color = HaloColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val Caption = TextStyle(color = HaloColors.TextDim, fontSize = 12.5.sp)
    val Overline = TextStyle(
        color = HaloColors.TextDim,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
    )
    const val OverlineUppercase = true
}

object HaloDimensions {
    val PosterWidth = 112.dp
    val PosterHeight = 168.dp
    const val PosterRatio = 1.5f
    val TabBarSpace = 96.dp
    val LoginMaxWidth = 480.dp
}

val HaloHeroScrim = listOf(
    Color(red = 10f / 255f, green = 12f / 255f, blue = 17f / 255f, alpha = 0.25f),
    Color(red = 10f / 255f, green = 12f / 255f, blue = 17f / 255f, alpha = 0f),
    Color(red = 10f / 255f, green = 12f / 255f, blue = 17f / 255f, alpha = 0.85f),
    HaloColors.Background,
)
val HaloHeroScrimLocations = listOf(0f, 0.4f, 0.82f, 1f)

private val HaloColorScheme = darkColorScheme(
    primary = HaloColors.Accent,
    onPrimary = HaloColors.OnAccent,
    background = HaloColors.Background,
    onBackground = HaloColors.Text,
    surface = HaloColors.Surface,
    onSurface = HaloColors.Text,
    surfaceVariant = HaloColors.SurfaceHigh,
    outline = HaloColors.Border,
    error = HaloColors.Danger,
)

@Composable
fun HaloTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HaloColorScheme, content = content)
}
