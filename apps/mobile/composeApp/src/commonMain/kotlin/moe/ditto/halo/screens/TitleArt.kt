package moe.ditto.halo.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** How long a logo may keep the title's place before the name is shown instead. */
private const val LogoPatienceMs = 2_000L

/** The width a logo is measured at; plenty to find its margins, and cheap enough to do on the UI thread. */
private const val ProbeWidth = 128

/** Alpha below this counts as margin, so a faint glow does not defeat the trim. */
private const val VisibleAlpha = 0.1f

/**
 * Lets the loaded logo's pixels be read, which [visibleArea] needs. Android's
 * default hardware bitmaps cannot be drawn into a canvas that reads them back.
 */
internal expect fun ImageRequest.Builder.keepPixelsReadable(): ImageRequest.Builder

/** The request for a title's logo. Shared with what fetches logos ahead, so that work is reused. */
internal fun titleLogoRequest(context: PlatformContext, url: String): ImageRequest =
    ImageRequest.Builder(context).data(url).keepPixelsReadable().build()

/**
 * A title's logo art when its addon has one, else its name.
 *
 * The place is held empty while the logo is on its way rather than filled with
 * the name, because the name turning into the logo a moment later reads as the
 * page changing its mind. A logo that fails gives way to the name at once, and
 * one that is slow does after [LogoPatienceMs], so the title is never a lasting
 * gap.
 *
 * @param logoPending true while it is not known yet whether there is a logo.
 */
@Composable
internal fun TitleArt(
    name: String,
    logo: String?,
    logoMaxWidth: Dp,
    /** Also the height held for the logo, so what sits beside it does not move when it lands. */
    logoMaxHeight: Dp,
    logoPending: Boolean = false,
    nameText: @Composable () -> Unit,
) {
    val context = LocalPlatformContext.current
    val painter = logo?.takeIf { it.isNotBlank() }?.let { url ->
        rememberAsyncImagePainter(
            model = remember(context, url) { titleLogoRequest(context, url) },
            contentScale = ContentScale.Fit,
        )
    }
    val state = painter?.state?.collectAsState()?.value
    var outOfPatience by remember(name) { mutableStateOf(false) }
    LaunchedEffect(name) {
        delay(LogoPatienceMs)
        outOfPatience = true
    }

    val waiting = logoPending || (painter != null && state !is AsyncImagePainter.State.Error)
    if (!waiting) {
        nameText()
        return
    }
    Box(Modifier.height(logoMaxHeight), contentAlignment = Alignment.BottomStart) {
        if (outOfPatience && state !is AsyncImagePainter.State.Success) nameText()
        when {
            state is AsyncImagePainter.State.Success -> LoadedLogo(state.painter, name, logoMaxWidth, logoMaxHeight)
            // Stays composed until then: it is what asks for the picture.
            painter != null -> Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.widthIn(max = logoMaxWidth).height(logoMaxHeight),
            )
        }
    }
}

/**
 * Draws only the part of the logo that shows. Logo files carry uneven
 * transparent margins, so fitting the whole file makes the art look small and
 * leaves it adrift from the line under it, by a different amount for every title.
 */
@Composable
private fun LoadedLogo(painter: Painter, name: String, maxWidth: Dp, maxHeight: Dp) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val area = remember(painter) { visibleArea(painter, layoutDirection) }
    if (area == null) {
        Image(
            painter = painter,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            modifier = Modifier.widthIn(max = maxWidth).height(maxHeight),
        )
        return
    }

    val visibleWidth = area.width * painter.intrinsicSize.width
    val visibleHeight = area.height * painter.intrinsicSize.height
    val scale = with(density) { min(maxWidth.toPx() / visibleWidth, maxHeight.toPx() / visibleHeight) }
    Box(
        Modifier
            .size(
                width = with(density) { (visibleWidth * scale).toDp() },
                height = with(density) { (visibleHeight * scale).toDp() },
            )
            .semantics { contentDescription = name }
            .drawBehind {
                val whole = Size(size.width / area.width, size.height / area.height)
                translate(left = -area.left * whole.width, top = -area.top * whole.height) {
                    with(painter) { draw(whole) }
                }
            },
    )
}

/**
 * The part of [painter] that is not transparent, as fractions of its size. Null
 * when that cannot be told, for a painter with no size or nothing visible; the
 * caller then draws the whole file as it came.
 */
private fun visibleArea(painter: Painter, layoutDirection: LayoutDirection): Rect? {
    val size = painter.intrinsicSize
    if (size.isUnspecified() || size.width <= 0f || size.height <= 0f) return null
    val height = max(1, (ProbeWidth * size.height / size.width).roundToInt())
    return runCatching {
        val bitmap = ImageBitmap(ProbeWidth, height)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = layoutDirection,
            canvas = Canvas(bitmap),
            size = Size(ProbeWidth.toFloat(), height.toFloat()),
        ) {
            with(painter) { draw(this@draw.size) }
        }
        opaqueBounds(bitmap)
    }.getOrNull()
}

private fun Size.isUnspecified(): Boolean = this == Size.Unspecified

private fun opaqueBounds(bitmap: ImageBitmap): Rect? {
    val pixels = bitmap.toPixelMap()
    var left = bitmap.width
    var top = bitmap.height
    var right = -1
    var bottom = -1
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            if (pixels[x, y].alpha < VisibleAlpha) continue
            left = min(left, x)
            top = min(top, y)
            right = max(right, x)
            bottom = max(bottom, y)
        }
    }
    if (right < 0) return null
    return Rect(
        left = left.toFloat() / bitmap.width,
        top = top.toFloat() / bitmap.height,
        right = (right + 1).toFloat() / bitmap.width,
        bottom = (bottom + 1).toFloat() / bitmap.height,
    )
}
