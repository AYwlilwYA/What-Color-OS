package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.home.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    private val density = 3f
    private val width = 1080f
    private val height = 2400f

    private fun textHeight(): Float = HomeLayout.textHeightPx(44f)

    private fun cellHeight(): Float = HomeLayout.cellHeightPx(
        width, height, density, textHeight(),
        HomeLayout.HOTSEAT_HEIGHT_DP * density,
        HomeLayout.HOTSEAT_MARGIN_BOTTOM_DP * density,
        HomeLayout.INDICATOR_HEIGHT_DP * density,
    )

    @Test
    fun cellWidthMatchesSystemFormula() {
        val inner = width - 2f * 16f * density
        assertEquals(inner / 4f, HomeLayout.cellWidthPx(width, density), 0.001f)
    }

    @Test
    fun cellHeightRespectsContentLowerBound() {
        val content = HomeLayout.contentHeightPx(56f * density, 4f * density, textHeight())
        val byContent = 2f * 7f * density + content
        val cellWidth = HomeLayout.cellWidthPx(width, density)
        val lower = maxOf(byContent, cellWidth + 8f * density)
        val viewport = (height - (26f + 78f + 28f) * density) / 6f
        assertEquals(minOf(lower, viewport), cellHeight(), 0.001f)
    }

    @Test
    fun shortViewportCapsCellHeight() {
        val short = HomeLayout.cellHeightPx(
            width, 900f, density, textHeight(),
            HomeLayout.HOTSEAT_HEIGHT_DP * density,
            HomeLayout.HOTSEAT_MARGIN_BOTTOM_DP * density,
            HomeLayout.INDICATOR_HEIGHT_DP * density,
        )
        val viewport = (900f - (26f + 78f + 28f) * density) / 6f
        assertEquals(viewport, short, 0.001f)
        assertTrue(short < cellHeight())
    }

    @Test
    fun workspaceHeightIsRowsTimesCellHeight() {
        assertEquals(cellHeight() * 6f, HomeLayout.workspaceHeightPx(cellHeight(), density), 0.001f)
    }

    @Test
    fun workspacePaddingTopUsesSystemPercentage() {
        val ws = HomeLayout.workspaceHeightPx(cellHeight(), density)
        val extra = height - ws - (78f + 28f + 26f) * density
        val expected = Math.round(extra * 0.333f).toFloat()
        assertEquals(
            expected,
            HomeLayout.workspacePaddingTopPx(height, ws, 78f * density, 26f * density, 28f * density),
            0.001f,
        )
    }

    @Test
    fun workspacePaddingTopAddsInsetBack() {
        val ws = HomeLayout.workspaceHeightPx(cellHeight(), density)
        val inset = 90f
        val extra = height - ws - (78f + 28f + 26f) * density
        val expected = Math.round((extra - inset) * 0.333f) + inset
        assertEquals(
            expected.toFloat(),
            HomeLayout.workspacePaddingTopPx(height, ws, 78f * density, 26f * density, 28f * density, inset),
            0.001f,
        )
    }

    @Test
    fun iconTopInsetIsHalfOfSlack() {
        val content = HomeLayout.contentHeightPx(56f * density, 4f * density, textHeight())
        val slack = cellHeight() - content
        assertEquals(maxOf(0f, slack * 0.5f), HomeLayout.iconTopInsetPx(cellHeight(), content), 0.001f)
    }

    @Test
    fun iconTopInsetNeverNegative() {
        assertEquals(0f, HomeLayout.iconTopInsetPx(50f, 200f), 0.001f)
    }

    @Test
    fun hotseatItemWidthUsesCellPadding() {
        assertEquals(16f * density, HomeLayout.hotseatPaddingHorPx(density), 0.001f)
        val expected = (width - 2f * 16f * density) / 4f
        assertEquals(expected, HomeLayout.hotseatItemWidthPx(width, density), 0.001f)
    }

    @Test
    fun hotseatIconCappedByRatioAndDesktopSize() {
        val item = HomeLayout.hotseatItemWidthPx(width, density)
        assertEquals(minOf(56f * density, 0.8f * item), HomeLayout.hotseatIconPx(width, density), 0.001f)
        val narrow = 480f
        val narrowItem = HomeLayout.hotseatItemWidthPx(narrow, density)
        assertEquals(0.8f * narrowItem, HomeLayout.hotseatIconPx(narrow, density), 0.001f)
    }

    @Test
    fun hotseatRadiusRatios() {
        val h = 78f * density
        assertEquals(h * 0.26f, HomeLayout.hotseatRadiusPx(h), 0.001f)
        assertEquals(h * 0.31f, HomeLayout.hotseatBlurRadiusPx(h), 0.001f)
        assertTrue(HomeLayout.hotseatBlurRadiusPx(h) > HomeLayout.hotseatRadiusPx(h))
    }

    @Test
    fun indicatorGeometry() {
        val expected = (2f * 14f + 3 * 6f + 2 * 6f) * density
        assertEquals(expected, HomeLayout.indicatorWidthPx(3, density), 0.001f)
        assertEquals(14f * density, HomeLayout.indicatorItemLeftPx(0, density), 0.001f)
        assertEquals((14f + 12f) * density, HomeLayout.indicatorItemLeftPx(1, density), 0.001f)
        assertEquals(11f * density, HomeLayout.indicatorItemTopPx(density), 0.001f)
    }

    @Test
    fun indicatorWidthClampsPageCount() {
        assertEquals(HomeLayout.indicatorWidthPx(1, density), HomeLayout.indicatorWidthPx(0, density), 0.001f)
        assertEquals(HomeLayout.indicatorWidthPx(1, density), HomeLayout.indicatorWidthPx(-5, density), 0.001f)
    }

    @Test
    fun indicatorMarginBottomIsMidpoint() {
        val expected = (26f * density + 0f + 120f + 78f * density - 28f * density) / 2f
        assertEquals(
            expected,
            HomeLayout.indicatorMarginBottomPx(26f * density, 120f, 78f * density, density),
            0.001f,
        )
    }

    @Test
    fun iconCornerFollowsMaskFraction() {
        val icon = 56f * density
        assertEquals(icon * 25f / 150f, HomeLayout.iconCornerRadiusPx(icon), 0.001f)
    }

    @Test
    fun textHeightCeilsAndClamps() {
        assertEquals(44f, HomeLayout.textHeightPx(43.2f), 0.001f)
        assertEquals(0f, HomeLayout.textHeightPx(-10f), 0.001f)
    }

    @Test
    fun indicatorAlphasMatchDecodedPixels() {
        assertEquals(76, HomeLayout.INDICATOR_NORMAL_ALPHA)
        assertEquals(217, HomeLayout.INDICATOR_ACTIVE_ALPHA)
    }
}
