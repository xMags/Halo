package moe.ditto.halo.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponsiveTest {
    @Test
    fun classifiesBoundaryShortestEdges() {
        assertEquals(DeviceClass.Phone, classifyWindow(599.dp, 1_024.dp).deviceClass)
        assertEquals(DeviceClass.Tablet, classifyWindow(600.dp, 1_024.dp).deviceClass)
        assertEquals(DeviceClass.Tablet, classifyWindow(767.dp, 1_024.dp).deviceClass)
        assertEquals(DeviceClass.LargeTablet, classifyWindow(768.dp, 1_024.dp).deviceClass)
    }

    @Test
    fun rotationDoesNotChangeDeviceClass() {
        assertEquals(
            classifyWindow(834.dp, 1_194.dp).deviceClass,
            classifyWindow(1_194.dp, 834.dp).deviceClass,
        )
    }

    @Test
    fun loginWidthUsesTheTabletReadingCap() {
        assertEquals(480.dp, HaloDimensions.LoginMaxWidth)
    }

    @Test
    fun rejectsNegativeDimensions() {
        assertFailsWith<IllegalArgumentException> { classifyWindow((-1).dp, 800.dp) }
    }

    @Test
    fun phonePosterColumnsStepAtWidthBoundaries() {
        assertEquals(3, classifyWindow(320.dp, 568.dp).posterColumns)
        assertEquals(3, classifyWindow(599.dp, 400.dp).posterColumns)
        assertEquals(4, classifyWindow(600.dp, 400.dp).posterColumns)
        assertEquals(4, classifyWindow(819.dp, 400.dp).posterColumns)
        assertEquals(5, classifyWindow(820.dp, 400.dp).posterColumns)
    }

    /**
     * The counts the tablet layout is drawn for: seven across in landscape at
     * both tiers, stepping down for the narrower portrait frames.
     */
    @Test
    fun tabletPosterColumnsMatchTheDrawnFrames() {
        assertEquals(7, classifyWindow(1_280.dp, 800.dp).posterColumns)
        assertEquals(5, classifyWindow(800.dp, 1_280.dp).posterColumns)
        assertEquals(7, classifyWindow(1_366.dp, 1_024.dp).posterColumns)
        assertEquals(6, classifyWindow(1_024.dp, 1_366.dp).posterColumns)
    }

    /**
     * Columns are counted against the content, not the window: the rail's column
     * is chrome, and posters laid out as if it were theirs would each come out a
     * fraction wide and push the last one under it.
     */
    @Test
    fun posterColumnsDiscountTheNavigationRail() {
        // 1238 wide is 1150 of content — the seventh column's threshold exactly.
        assertEquals(7, classifyWindow(1_238.dp, 800.dp).posterColumns)
        assertEquals(6, classifyWindow(1_237.dp, 800.dp).posterColumns)
    }

    /**
     * The load-bearing asymmetry: device class is rotation-invariant so a
     * landscape phone never presents as a tablet, but column count tracks the
     * live width so the same phone does pack more posters on its side.
     */
    @Test
    fun rotationChangesColumnsButNotDeviceClass() {
        val portrait = classifyWindow(390.dp, 844.dp)
        val landscape = classifyWindow(844.dp, 390.dp)

        assertEquals(DeviceClass.Phone, portrait.deviceClass)
        assertEquals(DeviceClass.Phone, landscape.deviceClass)
        assertEquals(3, portrait.posterColumns)
        assertEquals(5, landscape.posterColumns)
        assertTrue(landscape.isLandscape)
        assertTrue(!portrait.isLandscape)
    }

    @Test
    fun contentIsFullBleedOnPhoneAndCappedOnTablet() {
        assertNull(classifyWindow(390.dp, 844.dp).contentMaxWidth)
        assertEquals(700.dp, classifyWindow(834.dp, 1_194.dp).contentMaxWidth)
    }

    /**
     * The rail is a landscape-tablet affordance and nothing else: a phone on its
     * side has no room for one, and a tablet held upright keeps the bottom bar.
     */
    @Test
    fun onlyLandscapeTabletsUseTheNavigationRail() {
        assertTrue(classifyWindow(1_280.dp, 800.dp).usesNavigationRail)
        assertTrue(!classifyWindow(800.dp, 1_280.dp).usesNavigationRail)
        assertTrue(!classifyWindow(844.dp, 390.dp).usesNavigationRail)
    }

    @Test
    fun contentIsInsetAndMeasuredAgainstTheRail() {
        val landscape = classifyWindow(1_280.dp, 800.dp)
        assertEquals(HaloLayout.NavRailWidth, landscape.contentInsetStart)
        assertEquals(1_280.dp - HaloLayout.NavRailWidth, landscape.contentWidth)

        val portrait = classifyWindow(800.dp, 1_280.dp)
        assertEquals(0.dp, portrait.contentInsetStart)
        assertEquals(800.dp, portrait.contentWidth)
    }

    /**
     * Nothing floats over the bottom of a screen beside the rail, so the tab
     * bar's allowance there would be dead space rather than clearance.
     */
    @Test
    fun bottomPaddingClearsTheBarOnlyWhereThereIsOne() {
        assertEquals(HaloLayout.TabletBottomPadding, classifyWindow(1_280.dp, 800.dp).bottomContentPadding)
        assertEquals(HaloDimensions.TabBarSpace, classifyWindow(800.dp, 1_280.dp).bottomContentPadding)
        assertEquals(HaloDimensions.TabBarSpace, classifyWindow(390.dp, 844.dp).bottomContentPadding)
    }

    /**
     * The layout's two tablet frames divide at a wider edge than the device
     * class does — 1280x800 and 1366x1024 are both "large tablets" by density.
     */
    @Test
    fun layoutTierFollowsTheDrawnFramesNotTheDeviceClass() {
        assertEquals(LayoutTier.TabletLandscape, classifyWindow(1_280.dp, 800.dp).tier)
        assertEquals(LayoutTier.TabletPortrait, classifyWindow(800.dp, 1_280.dp).tier)
        assertEquals(LayoutTier.LargeLandscape, classifyWindow(1_366.dp, 1_024.dp).tier)
        assertEquals(LayoutTier.LargePortrait, classifyWindow(1_024.dp, 1_366.dp).tier)
        assertEquals(LayoutTier.Phone, classifyWindow(390.dp, 844.dp).tier)

        assertEquals(DeviceClass.LargeTablet, classifyWindow(1_280.dp, 800.dp).deviceClass)
    }

    @Test
    fun tierValuesMatchTheHandoffTable() {
        val tablet = classifyWindow(1_280.dp, 800.dp)
        val tabletPortrait = classifyWindow(800.dp, 1_280.dp)
        val large = classifyWindow(1_366.dp, 1_024.dp)
        val largePortrait = classifyWindow(1_024.dp, 1_366.dp)

        assertEquals(listOf(32.dp, 24.dp, 40.dp, 32.dp), listOf(tablet, tabletPortrait, large, largePortrait).map { it.gutter })
        assertEquals(listOf(340.dp, 300.dp, 380.dp, 340.dp), listOf(tablet, tabletPortrait, large, largePortrait).map { it.homeHeroHeight })
        assertEquals(listOf(420.dp, 400.dp, 500.dp, 460.dp), listOf(tablet, tabletPortrait, large, largePortrait).map { it.detailHeroHeight })
        assertEquals(listOf(2, 1, 3, 2), listOf(tablet, tabletPortrait, large, largePortrait).map { it.episodeGridColumns })
        assertEquals(listOf(440.dp, 400.dp, 480.dp, 440.dp), listOf(tablet, tabletPortrait, large, largePortrait).map { it.sourcesRailWidth })
    }

    /** The phone frame is untouched by the tablet work; these are its old values. */
    @Test
    fun phoneMetricsAreUnchanged() {
        val phone = classifyWindow(390.dp, 844.dp)

        assertEquals(HaloSpacing.Md, phone.gutter)
        assertEquals(210.dp, phone.homeHeroHeight)
        assertEquals(460.dp, phone.detailHeroHeight)
        assertEquals(BrowseHeaderLayout.Stacked, phone.browseHeaderLayout)
        assertEquals(HaloDimensions.PosterWidth, phone.catalogRowPosterWidth)
        assertEquals(132.dp, phone.shelfPosterWidth)
    }

    @Test
    fun browseHeaderGoesBesideTheTitleOnlyWhereAllThreeFit() {
        assertEquals(BrowseHeaderLayout.Beside, classifyWindow(1_280.dp, 800.dp).browseHeaderLayout)
        assertEquals(BrowseHeaderLayout.Below, classifyWindow(800.dp, 1_280.dp).browseHeaderLayout)
    }

    @Test
    fun pickFallsBackToTheTabletValueForLargeTablets() {
        val largeTablet = classifyWindow(1_024.dp, 1_366.dp)

        assertEquals("large", largeTablet.pick(phone = "phone", tablet = "tablet", large = "large"))
        assertEquals("tablet", largeTablet.pick(phone = "phone", tablet = "tablet"))
    }

}
