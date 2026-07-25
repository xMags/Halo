package moe.ditto.halo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlin.math.floor

/**
 * The single source of truth for phone/tablet branching.
 *
 * Device class is decided by the *smallest* window edge — the rotation-invariant
 * analog of Android's `sw600dp` qualifier. Keying off bare width would misfire on
 * a phone held in landscape (844x390 reads as a tablet). Column counts, by
 * contrast, key off the *current* width on purpose, so portrait and landscape
 * pack a different number of posters.
 */

enum class DeviceClass {
    Phone,
    Tablet,
    LargeTablet,
}

/** Android sw600dp — catches every 7"+ tablet. */
private val TabletMinEdge = 600.dp

/** iPad-mini portrait / large-tablet tier for optional extra density. */
private val LargeTabletMinEdge = 768.dp

/** Comfortable reading width for single-column, form-like content on tablets. */
private val ContentMaxWidth = 700.dp

data class ResponsiveInfo(
    val width: Dp,
    val height: Dp,
) {
    val shortestEdge: Dp = min(width, height)

    val deviceClass: DeviceClass = when {
        shortestEdge < TabletMinEdge -> DeviceClass.Phone
        shortestEdge < LargeTabletMinEdge -> DeviceClass.Tablet
        else -> DeviceClass.LargeTablet
    }

    val isTablet: Boolean = deviceClass != DeviceClass.Phone

    val isLandscape: Boolean = width > height

    /** Poster-grid columns for the *current* window width (portrait != landscape). */
    val posterColumns: Int = posterColumnsFor(width)

    /**
     * Max width for single-column reading content (settings forms, stream lists,
     * synopsis); `null` on phone means full-bleed. Screens opt in — poster rows
     * and grids deliberately stay full-width to use the extra space.
     */
    val contentMaxWidth: Dp? = if (isTablet) ContentMaxWidth else null

    /** Pick a value by device class without re-deriving it at the call site. */
    fun <T> pick(phone: T, tablet: T, large: T = tablet): T = when (deviceClass) {
        DeviceClass.Phone -> phone
        DeviceClass.Tablet -> tablet
        DeviceClass.LargeTablet -> large
    }
}

fun classifyWindow(width: Dp, height: Dp): ResponsiveInfo {
    require(width.value >= 0f && height.value >= 0f) { "Window dimensions must be non-negative" }
    return ResponsiveInfo(width = width, height = height)
}

/** Poster columns scale with available width, not device class. */
private fun posterColumnsFor(width: Dp): Int = when {
    width >= 1_400.dp -> 7
    width >= 1_100.dp -> 6
    width >= 820.dp -> 5
    width >= 600.dp -> 4
    else -> 3
}

/**
 * Exact width of one cell in a fixed-column poster grid, so cells keep a fixed
 * size instead of flex-filling. This is what makes a partial final row
 * left-align at its natural width rather than stretching to fill it — egregious
 * at high column counts.
 */
fun gridItemWidth(
    windowWidth: Dp,
    columns: Int,
    horizontalPadding: Dp,
    gap: Dp,
): Dp {
    require(columns > 0) { "A grid needs at least one column" }
    val inner = windowWidth - horizontalPadding * 2 - gap * (columns - 1)
    return floor((inner / columns).value).dp
}

/**
 * Window metrics for the current composition. Reads [LocalWindowInfo], which
 * recomposes on rotation, split-screen, and foldable unfold — a one-shot
 * measurement taken at startup would silently go stale on the first rotation.
 */
@Composable
fun rememberResponsive(): ResponsiveInfo {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(containerSize, density) {
        with(density) {
            classifyWindow(containerSize.width.toDp(), containerSize.height.toDp())
        }
    }
}
