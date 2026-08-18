package moe.ditto.halo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/**
 * The single source of truth for phone/tablet branching.
 *
 * Device class is decided by the *smallest* window edge — the rotation-invariant
 * analog of Android's `sw600dp` qualifier. Keying off bare width would misfire on
 * a phone held in landscape (844x390 reads as a tablet). Column counts, by
 * contrast, key off the *current* width on purpose, so portrait and landscape
 * pack a different number of posters.
 *
 * The tablet layout is specified against four frames — 1280x800 and 1366x1024,
 * each way up — so anything that differs between them is expressed as a
 * [LayoutTier] lookup rather than as a threshold invented at the call site.
 */

enum class DeviceClass {
    Phone,
    Tablet,
    LargeTablet,
}

/**
 * The five frames the layout is drawn for: the phone, and each tablet tier in
 * each orientation. Orientation matters as much as size here — a tablet in
 * landscape swaps the floating tab bar for a rail and lays surfaces out
 * side by side, which portrait has no room for.
 */
enum class LayoutTier {
    Phone,
    TabletLandscape,
    TabletPortrait,
    LargeLandscape,
    LargePortrait,
}

/**
 * How a browse screen's title, search field and filter are arranged.
 *
 * [Stacked] is the phone's: one control per line. The tablet arrangements put
 * the two controls on one row, either beside the title where there is width for
 * all three, or under it where there is not.
 */
enum class BrowseHeaderLayout {
    Stacked,
    Beside,
    Below,
}

/** Android sw600dp — catches every 7"+ tablet. */
private val TabletMinEdge = 600.dp

/** iPad-mini portrait / large-tablet tier for optional extra density. */
private val LargeTabletMinEdge = 768.dp

/**
 * Where the tablet layout's two frames divide: 1280x800 below, 1366x1024 above.
 *
 * Deliberately not [LargeTabletMinEdge]. That threshold splits 7-8" tablets from
 * everything larger, which is the right cut for *density* — how much a surface
 * shows — and the wrong one for these frames, where both drawn tiers sit above
 * it. Measured on the shortest edge for the same reason the device class is:
 * a 12.9" tablet is the large tier whichever way up it is held.
 */
private val LargeLayoutMinEdge = 900.dp

/** Comfortable reading width for single-column, form-like content on tablets. */
private val ContentMaxWidth = 700.dp

/**
 * Fixed metrics of the tablet frame, kept here beside the tier lookups rather
 * than inline in the screens that draw them, so the whole layout is legible in
 * one file. Anything that changes between tiers is a [ResponsiveInfo] property
 * instead of a constant.
 */
object HaloLayout {
    /** The landscape navigation rail, in place of the floating tab bar. */
    val NavRailWidth = 88.dp

    /** One rail tab's target; narrower than the rail, which centres it. */
    val NavRailTabWidth = 72.dp

    /** Poster grid spacing on a tablet. Rows are further apart than columns
     *  because a poster is half again as tall as it is wide. */
    val PosterGridGap = 14.dp
    val PosterGridRowGap = 20.dp

    /** Phone poster-grid spacing, unchanged. */
    val PhonePosterGridGap = 11.dp
    val PhonePosterGridRowGap = HaloSpacing.Md

    /** Gap between cards in a horizontally scrolling row. */
    val CatalogRowGap = 14.dp
    val PhoneCatalogRowGap = 11.dp

    /** A search field that ran the full width of a tablet would put its clear
     *  button a hand's width from the text being typed. */
    val SearchFieldMaxWidth = 720.dp

    /** The search field and filter, as a group beside a browse screen's title. */
    val BrowseHeaderControlsWidth = 620.dp

    /** The detail screen's facts card, beside the synopsis. */
    val FactsCardWidth = 300.dp

    /** The synopsis and action buttons; the same cap as any reading column. */
    val DetailBodyMaxWidth = ContentMaxWidth

    val EpisodeGridGap = 12.dp
    val EpisodeCardThumbWidth = 148.dp
    val EpisodeCardThumbHeight = 84.dp

    /**
     * What a screen leaves under its last row where the navigation rail is in
     * use. Nothing floats over the content there, so this is breathing room
     * rather than clearance — see [ResponsiveInfo.bottomContentPadding].
     */
    val TabletBottomPadding = 32.dp
}

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

    val tier: LayoutTier = when {
        !isTablet -> LayoutTier.Phone
        shortestEdge >= LargeLayoutMinEdge ->
            if (isLandscape) LayoutTier.LargeLandscape else LayoutTier.LargePortrait
        else -> if (isLandscape) LayoutTier.TabletLandscape else LayoutTier.TabletPortrait
    }

    /**
     * A tablet in landscape trades the floating bottom bar for a left rail: the
     * bar's four tabs cost a full-width strip of a screen that is already short,
     * and a rail costs a column of one that is wide.
     */
    val usesNavigationRail: Boolean = isTablet && isLandscape

    /**
     * The leading inset a screen owes the navigation rail.
     *
     * The rail floats over content exactly as the tab bar does, so a screen
     * carries this the way it carries [HaloDimensions.TabBarSpace] at the
     * bottom: content that ignores it scrolls under the glass instead of
     * beginning beside it.
     */
    val contentInsetStart: Dp = if (usesNavigationRail) HaloLayout.NavRailWidth else 0.dp

    /**
     * The width screens actually lay out in. Anything measured against the
     * window — column counts, a centred reading column — would otherwise be a
     * rail's width too wide.
     */
    val contentWidth: Dp = width - contentInsetStart

    /** Horizontal inset every browse surface starts from. */
    val gutter: Dp = byTier(
        phone = HaloSpacing.Md,
        tabletLandscape = 32.dp,
        tabletPortrait = 24.dp,
        largeLandscape = 40.dp,
        largePortrait = 32.dp,
    )

    /** Poster-grid columns for the *current* content width (portrait != landscape). */
    val posterColumns: Int =
        if (deviceClass == DeviceClass.Phone) phonePosterColumns(width) else tabletPosterColumns(contentWidth)

    val posterGridGap: Dp = if (isTablet) HaloLayout.PosterGridGap else HaloLayout.PhonePosterGridGap

    val posterGridRowGap: Dp = if (isTablet) HaloLayout.PosterGridRowGap else HaloLayout.PhonePosterGridRowGap

    val catalogRowGap: Dp = if (isTablet) HaloLayout.CatalogRowGap else HaloLayout.PhoneCatalogRowGap

    val browseHeaderLayout: BrowseHeaderLayout = when {
        !isTablet -> BrowseHeaderLayout.Stacked
        isLandscape -> BrowseHeaderLayout.Beside
        // Title, search field and a three-segment filter do not fit across 800dp
        // without the title being squeezed to an ellipsis.
        else -> BrowseHeaderLayout.Below
    }

    /** Home's featured card. */
    val homeHeroHeight: Dp = byTier(
        phone = 210.dp,
        tabletLandscape = 340.dp,
        tabletPortrait = 300.dp,
        largeLandscape = 380.dp,
        largePortrait = 340.dp,
    )

    /**
     * A title's own art. Shorter in landscape than the phone's 460dp despite the
     * extra width, because the whole screen is only 800dp tall there and the
     * episodes have to start above the fold.
     */
    val detailHeroHeight: Dp = byTier(
        phone = 460.dp,
        tabletLandscape = 420.dp,
        tabletPortrait = 400.dp,
        largeLandscape = 500.dp,
        largePortrait = 460.dp,
    )

    val episodeGridColumns: Int = byTier(
        phone = 1,
        tabletLandscape = 2,
        tabletPortrait = 1,
        largeLandscape = 3,
        largePortrait = 2,
    )

    /** The sources picker, as a rail over the title it belongs to. */
    val sourcesRailWidth: Dp = byTier(
        // Unused on a phone, which pushes the picker as its own screen.
        phone = 0.dp,
        tabletLandscape = 440.dp,
        tabletPortrait = 400.dp,
        largeLandscape = 480.dp,
        largePortrait = 440.dp,
    )

    /** Personal shelves on Home: a handful of cards, so they run larger. */
    val shelfPosterWidth: Dp = byTier(
        phone = 132.dp,
        tabletLandscape = 168.dp,
        tabletPortrait = 156.dp,
        largeLandscape = 178.dp,
        largePortrait = 168.dp,
    )

    /** Catalog rows: an endless strip, so they run narrower than the shelves. */
    val catalogRowPosterWidth: Dp = byTier(
        phone = HaloDimensions.PosterWidth,
        tabletLandscape = 156.dp,
        tabletPortrait = 144.dp,
        largeLandscape = 164.dp,
        largePortrait = 156.dp,
    )

    /** Search results, which carry a label under the art at every size. */
    val searchPosterWidth: Dp = if (isTablet) 150.dp else 132.dp

    /**
     * Room a screen leaves under its last row.
     *
     * The tab bar floats over content, so a screen that keeps it has to stop
     * short of it or its last row sits behind the glass. The rail takes its own
     * column instead, so a screen beside it owes the bottom nothing but margin.
     */
    val bottomContentPadding: Dp =
        if (usesNavigationRail) HaloLayout.TabletBottomPadding else HaloDimensions.TabBarSpace

    /**
     * Clearance an in-tree overlay needs for chrome drawn on top of it. Sheets
     * are siblings of the navigation host and the tab bar draws after both, so
     * an overlay's last row lands under the bar unless it is pushed clear.
     */
    val floatingBarClearance: Dp = if (usesNavigationRail) 0.dp else HaloDimensions.TabBarSpace

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

    /**
     * Pick a value by [LayoutTier], for the metrics that differ by orientation
     * as well as by size. The large tiers default to their smaller counterparts,
     * which is the common case: most values change once, between phone and
     * tablet, and again only where the extra 86dp of width is worth spending.
     */
    fun <T> byTier(
        phone: T,
        tabletLandscape: T,
        tabletPortrait: T,
        largeLandscape: T = tabletLandscape,
        largePortrait: T = tabletPortrait,
    ): T = when (tier) {
        LayoutTier.Phone -> phone
        LayoutTier.TabletLandscape -> tabletLandscape
        LayoutTier.TabletPortrait -> tabletPortrait
        LayoutTier.LargeLandscape -> largeLandscape
        LayoutTier.LargePortrait -> largePortrait
    }
}

fun classifyWindow(width: Dp, height: Dp): ResponsiveInfo {
    require(width.value >= 0f && height.value >= 0f) { "Window dimensions must be non-negative" }
    return ResponsiveInfo(width = width, height = height)
}

/** Poster columns on a phone, which scale with the width it is held at. */
private fun phonePosterColumns(width: Dp): Int = when {
    width >= 1_400.dp -> 7
    width >= 1_100.dp -> 6
    width >= 820.dp -> 5
    width >= 600.dp -> 4
    else -> 3
}

/**
 * Poster columns on a tablet: seven across at both landscape tiers, which is
 * what the layout is drawn for, stepping down for the narrower portrait frames
 * and for the 7" tablets that are nowhere near either.
 *
 * Measured against content width rather than window width, so the navigation
 * rail's column is not counted as room for posters.
 */
private fun tabletPosterColumns(contentWidth: Dp): Int = when {
    contentWidth >= 1_150.dp -> 7
    contentWidth >= 950.dp -> 6
    contentWidth >= 740.dp -> 5
    else -> 4
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
