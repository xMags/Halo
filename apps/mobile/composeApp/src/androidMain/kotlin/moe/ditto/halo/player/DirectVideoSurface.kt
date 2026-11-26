package moe.ditto.halo.player

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The player surface on a profile with the direct video path.
 *
 * On the direct path mpv does no scaling: the decoder's picture is stretched
 * to the SurfaceView's bounds. So the view itself is sized to the picture's
 * aspect ratio (fit or fill, centred, cropped to the box, black around it),
 * and the current subtitle is drawn over the whole box, because mpv draws
 * none on that output. On the composed path the view fills the box and mpv
 * draws everything, exactly as on a profile without the direct path.
 *
 * [surface] hands back the host's one long-lived SurfaceView; the same view
 * stays in place across path switches, only its size changes.
 */
@Composable
internal fun DirectVideoSurface(
    frame: StateFlow<DirectVideoFrame>,
    subtitles: StateFlow<AppSubtitleState>,
    surface: () -> SurfaceView,
    modifier: Modifier,
) {
    val current by frame.collectAsState()
    Box(modifier.background(Color.Black).clipToBounds()) {
        AndroidView(
            factory = { surface() },
            modifier = Modifier.fillMaxSize().videoFrameBounds { current },
        )
        if (current.direct) AppSubtitleOverlay(subtitles, Modifier.fillMaxSize())
    }
}

/**
 * Lays the view out at the picture's size inside the incoming (box) size and
 * centres it, letting a filled picture overflow on two sides. On the composed
 * path, or before the box is bounded, it measures as it would without this.
 */
private fun Modifier.videoFrameBounds(frame: () -> DirectVideoFrame): Modifier = layout { measurable, constraints ->
    val current = frame()
    if (!current.direct || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val box = PixelSize(constraints.maxWidth, constraints.maxHeight)
    val size = fitVideoToBox(box.width, box.height, current.videoWidth, current.videoHeight, current.fillsScreen)
    val placeable = measurable.measure(Constraints.fixed(size.width.coerceAtLeast(0), size.height.coerceAtLeast(0)))
    layout(box.width, box.height) {
        placeable.place((box.width - placeable.width) / 2, (box.height - placeable.height) / 2)
    }
}

/**
 * The current cue, drawn the way mpv draws text subtitles: white fill, black
 * outline under it, an optional black shadow down and to the right, centred
 * lines bottom-anchored above the margin. It is a plain drawing with no
 * semantics, so it takes no focus or keys and accessibility does not read it.
 */
@Composable
private fun AppSubtitleOverlay(state: StateFlow<AppSubtitleState>, modifier: Modifier) {
    val subtitles by state.collectAsState()
    val face = rememberSubtitleFace(subtitles.font)
    val measurer = rememberTextMeasurer()
    if (subtitles.text.isBlank()) return
    Canvas(modifier.clearAndSetSemantics { }) {
        drawSubtitle(measurer, subtitles, face)
    }
}

private fun DrawScope.drawSubtitle(measurer: TextMeasurer, subtitles: AppSubtitleState, face: SubtitleFace) {
    val geometry = subtitleGeometry(size.width, size.height, subtitles)
    val width = geometry.textWidthPixels.toInt()
    if (width <= 0 || geometry.fontPixels <= 0f) return
    // The measurer caches layouts by their inputs, so redrawing an unchanged
    // cue does not lay it out again.
    val layout = measurer.measure(
        text = subtitles.text,
        style = TextStyle(
            fontFamily = face.family,
            fontSize = (geometry.fontPixels / face.heightPerSize).toSp(),
            textAlign = TextAlign.Center,
        ),
        constraints = Constraints.fixedWidth(width),
    )
    // A lift tall enough to push the block off the top keeps it on screen,
    // as libass does.
    val top = (geometry.bottomPixels - layout.size.height).coerceAtLeast(0f)
    val origin = Offset(geometry.sideMarginPixels, top)
    // A stroke is centred on the glyph's edge, so twice the outline width
    // reaches the outline's distance outside it; the inner half is under the fill.
    val outline = if (geometry.outlinePixels > 0f) {
        Stroke(width = geometry.outlinePixels * 2f, join = StrokeJoin.Round)
    } else {
        null
    }
    // Every pass names its style and colour. A null drawStyle does not mean
    // fill: the layout's shared text paint keeps the style it was last given,
    // so after a stroke pass an unstyled fill would be stroked as well.
    if (geometry.shadowPixels > 0f) {
        // libass casts the shadow from the outlined shape, not the bare glyphs.
        val shadowOrigin = origin + Offset(geometry.shadowPixels, geometry.shadowPixels)
        if (outline != null) drawText(layout, color = Color.Black, topLeft = shadowOrigin, drawStyle = outline)
        drawText(layout, color = Color.Black, topLeft = shadowOrigin, drawStyle = Fill)
    }
    if (outline != null) drawText(layout, color = Color.Black, topLeft = origin, drawStyle = outline)
    drawText(layout, color = Color.White, topLeft = origin, drawStyle = Fill)
}

/**
 * A subtitle family ready to draw, and its line height (ascent to descent)
 * per unit of font size.
 *
 * libass sizes a font by that height rather than by its em, so the app's em
 * is the wanted height divided by [heightPerSize]. Android reports the face's
 * own vertical metrics, which libass reads from the font's tables as well, so
 * the two agree closely if not to the pixel for every font.
 */
private class SubtitleFace(val family: FontFamily, val heightPerSize: Float)

/** Loads the chosen family off the main thread, once per process, from the files mpv reads. */
@Composable
private fun rememberSubtitleFace(family: String?): SubtitleFace {
    val context = LocalContext.current.applicationContext
    val face by produceState(initialValue = SubtitleFaces.cachedOrDefault(family), family) {
        value = withContext(Dispatchers.IO) { SubtitleFaces.load(context, family) }
    }
    return face
}

/**
 * Process-wide cache of loaded subtitle faces, keyed by family name. Faces
 * are immutable once built, so readers on any thread may share them.
 */
private object SubtitleFaces {
    private const val MetricsTextSize = 1000f
    private val loaded = ConcurrentHashMap<String, SubtitleFace>()

    /** The renderer's "no particular font", which fontconfig resolves to the system sans-serif. */
    private val default: SubtitleFace by lazy { faceOf(Typeface.SANS_SERIF) }

    fun cachedOrDefault(family: String?): SubtitleFace = family?.let { loaded[it] } ?: default

    /**
     * Reads the font file: call off the main thread. A family that is not
     * bundled, or whose file will not load, falls back to the default, which
     * is what libass does with a name it cannot find.
     */
    fun load(context: Context, family: String?): SubtitleFace {
        if (family == null) return default
        loaded[family]?.let { return it }
        val file = SubtitleFontLibrary.regularFace(context, family) ?: return default
        val typeface = try {
            Typeface.createFromFile(file)
        } catch (failure: RuntimeException) {
            // The platform's way of saying the file is not a font it can read.
            Log.w(MpvCore.LOG_TAG, "subtitle fonts: $family unreadable (${failure.message})")
            return default
        }
        val face = faceOf(typeface)
        loaded[family] = face
        return face
    }

    private fun faceOf(typeface: Typeface): SubtitleFace {
        val metrics = Paint().apply {
            this.typeface = typeface
            textSize = MetricsTextSize
        }.fontMetrics
        val heightPerSize = (metrics.descent - metrics.ascent) / MetricsTextSize
        return SubtitleFace(FontFamily(typeface), heightPerSize.takeIf { it.isFinite() && it > 0f } ?: 1f)
    }
}
