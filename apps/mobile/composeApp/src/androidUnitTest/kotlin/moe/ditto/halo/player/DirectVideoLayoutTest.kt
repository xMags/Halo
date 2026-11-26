package moe.ditto.halo.player

import kotlin.test.Test
import kotlin.test.assertEquals

class DirectVideoLayoutTest {

    @Test
    fun aWidePictureFitsWithBarsAboveAndBelow() {
        // 2.39:1 scope film on a 16:9 screen.
        assertEquals(PixelSize(1920, 803), fitVideoToBox(1920, 1080, 3840, 1606, fill = false))
    }

    @Test
    fun aWidePictureFillsByCroppingItsSides() {
        assertEquals(PixelSize(2582, 1080), fitVideoToBox(1920, 1080, 3840, 1606, fill = true))
    }

    @Test
    fun aTallPictureFitsWithBarsAtTheSides() {
        // 4:3 on 16:9.
        assertEquals(PixelSize(1440, 1080), fitVideoToBox(1920, 1080, 1440, 1080, fill = false))
    }

    @Test
    fun aTallPictureFillsByCroppingTopAndBottom() {
        assertEquals(PixelSize(1920, 1440), fitVideoToBox(1920, 1080, 640, 480, fill = true))
    }

    @Test
    fun theBoxsOwnAspectFillsItExactlyEitherWay() {
        assertEquals(PixelSize(1920, 1080), fitVideoToBox(1920, 1080, 3840, 2160, fill = false))
        assertEquals(PixelSize(1920, 1080), fitVideoToBox(1920, 1080, 3840, 2160, fill = true))
    }

    @Test
    fun anUnknownPictureSizeFillsTheBox() {
        assertEquals(PixelSize(1920, 1080), fitVideoToBox(1920, 1080, 0, 0, fill = false))
        assertEquals(PixelSize(1920, 1080), fitVideoToBox(1920, 1080, 1920, 0, fill = true))
        assertEquals(PixelSize(0, 0), fitVideoToBox(0, 0, 1920, 1080, fill = false))
    }

    @Test
    fun mpvUnitsScaleWithTheBoxHeight() {
        assertEquals(38f, mpvUnitsToPixels(38.0, 720f))
        assertEquals(57f, mpvUnitsToPixels(38.0, 1080f))
        assertEquals(114f, mpvUnitsToPixels(38.0, 2160f))
    }

    @Test
    fun theDefaultSubtitleSitsAtMpvsBottomMargin() {
        val geometry = subtitleGeometry(1920f, 1080f, AppSubtitleState(text = "Hello"))
        assertEquals(57f, geometry.fontPixels)
        assertEquals(4.5f, geometry.outlinePixels)
        assertEquals(0f, geometry.shadowPixels)
        assertEquals(37.5f, geometry.sideMarginPixels)
        assertEquals(1920f - 75f, geometry.textWidthPixels)
        assertEquals(1080f - 33f, geometry.bottomPixels)
    }

    @Test
    fun scaleOutlineShadowAndLiftFollowTheViewersStyle() {
        val geometry = subtitleGeometry(
            1920f,
            1080f,
            AppSubtitleState(text = "Hello", scale = 1.5, outlineUnits = 2.0, shadowUnits = 4.0, position = 80),
        )
        assertEquals(85.5f, geometry.fontPixels)
        assertEquals(3f, geometry.outlinePixels)
        assertEquals(6f, geometry.shadowPixels)
        // sub-pos 80 of 1080 px, less the 33 px margin.
        assertEquals(864f - 33f, geometry.bottomPixels)
    }

    @Test
    fun aNarrowBoxNeverGetsANegativeTextWidth() {
        assertEquals(0f, subtitleGeometry(40f, 1080f, AppSubtitleState()).textWidthPixels)
    }
}
