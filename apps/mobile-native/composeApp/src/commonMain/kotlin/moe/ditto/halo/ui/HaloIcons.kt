package moe.ditto.halo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's icons, held as the raw 24dp path data from Google's Material Icons
 * (Apache-2.0) rather than taken as a dependency. The published icon artifacts
 * are either the ~2000-icon extended set or a Compose Multiplatform build that
 * stopped several versions behind the one this app uses; a handful of glyphs
 * does not justify either.
 *
 * The strings are the source files' path data verbatim, handed to Compose's own
 * [PathParser] — nothing is transcribed into the vector-builder DSL by hand, so
 * there is no step where a glyph can be silently mangled. Each source file also
 * carries a full-bounds rectangle ("M0 0h24v24H0z") as a `fill="none"` spacer;
 * those are dropped, since an ImageVector would paint them as a solid tile.
 *
 * Fill is opaque white only because it must be something: `Icon` replaces it
 * with its own tint.
 */
object HaloIcons {
    val Search: ImageVector by lazy {
        icon(
            "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 " +
                "9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 " +
                "0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
        )
    }

    /** Filled circle-with-cross; clears a populated field. */
    val CloseCircle: ImageVector by lazy {
        icon(
            "M12 2C6.47 2 2 6.47 2 12s4.47 10 10 10 10-4.47 10-10S17.53 2 12 2zm5 13.59L15.59 17 " +
                "12 13.41 8.41 17 7 15.59 10.59 12 7 8.41 8.41 7 12 10.59 15.59 7 17 8.41 13.41 12 " +
                "17 15.59z",
        )
    }

    val Close: ImageVector by lazy {
        icon(
            "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 " +
                "19 17.59 13.41 12z",
        )
    }

    val Check: ImageVector by lazy {
        icon("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z")
    }

    val Star: ImageVector by lazy {
        icon("M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z")
    }
}

private fun icon(pathData: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.White),
        )
    }.build()
