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
    val Danger = Color(0xFFFF6B6B)
    val Success = Color(0xFF5DD39E)
    val Primary = Color.White
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
