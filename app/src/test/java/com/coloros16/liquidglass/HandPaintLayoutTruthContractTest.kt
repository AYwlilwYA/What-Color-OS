package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.HandPaintSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandPaintLayoutTruthContractTest {

    @Test
    fun layoutIndexRangeIsWiderThanStyleRange() {
        assertEquals(0, HandPaintSource.LAYOUT_INDEX_MIN)
        assertEquals(9, HandPaintSource.LAYOUT_INDEX_MAX)
        assertEquals(1, HandPaintSource.FIRST_STYLE)
        assertTrue(HandPaintSource.LAYOUT_INDEX_MAX - HandPaintSource.LAYOUT_INDEX_MIN + 1 == 10)
        assertTrue(HandPaintSource.LAST_STYLE - HandPaintSource.FIRST_STYLE + 1 == 9)
    }

    @Test
    fun indexZeroHasNoPreset() {
        assertEquals(0, HandPaintSource.styleOfLayoutIndex(0))
        assertFalse(HandPaintSource.hasPreset(0))
    }

    @Test
    fun indexOneToNineMapToSameStyle() {
        for (i in 1..9) {
            assertEquals(i, HandPaintSource.styleOfLayoutIndex(i))
            assertTrue(HandPaintSource.hasPreset(i))
        }
    }

    @Test
    fun outOfRangeIndexHasNoPreset() {
        assertFalse(HandPaintSource.hasPreset(-1))
        assertFalse(HandPaintSource.hasPreset(10))
        assertEquals(0, HandPaintSource.styleOfLayoutIndex(99))
    }

    @Test
    fun layoutIndexOfStyleRoundTrips() {
        for (i in 1..9) {
            assertEquals(i, HandPaintSource.layoutIndexOfStyle(HandPaintSource.styleOfLayoutIndex(i)))
        }
    }

    @Test
    fun invalidStyleMapsToIndexZero() {
        assertEquals(HandPaintSource.LAYOUT_INDEX_MIN, HandPaintSource.layoutIndexOfStyle(0))
        assertEquals(HandPaintSource.LAYOUT_INDEX_MIN, HandPaintSource.layoutIndexOfStyle(42))
    }

    @Test
    fun officialCanvasIs180dp() {
        assertEquals(180f, HandPaintSource.CANVAS_DP, 0f)
        assertEquals(540f, HandPaintSource.canvasPx(3f), 0.001f)
        assertEquals(472.5f, HandPaintSource.canvasPx(2.625f), 0.001f)
    }

    @Test
    fun officialCanvasIsSmallerThanFullScreenShortSide() {
        val official = HandPaintSource.canvasPx(3f)
        val fullShortSide = 1080f
        assertTrue("official=$official", official < fullShortSide)
    }

    @Test
    fun clockHiddenDuringDelay() {
        assertEquals(0f, HandPaintSource.clockFadeAt(0L), 0f)
        assertEquals(0f, HandPaintSource.clockFadeAt(1000L), 0f)
        assertEquals(0f, HandPaintSource.clockFadeAt(HandPaintSource.CLOCK_DELAY_MS), 0f)
    }

    @Test
    fun clockFadesLinearlyAfterDelay() {
        val mid = HandPaintSource.CLOCK_DELAY_MS + HandPaintSource.CLOCK_DURATION_MS / 2
        assertEquals(0.5f, HandPaintSource.clockFadeAt(mid), 0.001f)
    }

    @Test
    fun clockFullyVisibleAfterDelayPlusDuration() {
        val end = HandPaintSource.CLOCK_DELAY_MS + HandPaintSource.CLOCK_DURATION_MS
        assertEquals(1f, HandPaintSource.clockFadeAt(end), 0f)
        assertEquals(1f, HandPaintSource.clockFadeAt(end + 5000L), 0f)
    }

    @Test
    fun officialClockTimingMatchesVendorValues() {
        assertEquals(1500L, HandPaintSource.CLOCK_DELAY_MS)
        assertEquals(600L, HandPaintSource.CLOCK_DURATION_MS)
    }

    @Test
    fun clockFadeMonotonicAcrossWholeWindow() {
        var last = -1f
        var t = 0L
        while (t <= 3000L) {
            val cur = HandPaintSource.clockFadeAt(t)
            assertTrue("t=$t cur=$cur last=$last", cur >= last)
            last = cur
            t += 50L
        }
        assertEquals(1f, last, 0f)
    }

    @Test
    fun negativeElapsedHidesClock() {
        assertEquals(0f, HandPaintSource.clockFadeAt(-100L), 0f)
    }

    @Test
    fun centerYFractionIsSharedConstant() {
        assertEquals(0.38f, HandPaintSource.CENTER_Y, 0f)
        val r = com.coloros16.liquidglass.engine.HandPaintGeometry.presentRect(
            HandPaintSource.canvasPx(3f), HandPaintSource.CENTER_Y, 1080f, 2376f,
        )
        assertEquals(540f, r[2], 0.001f)
        assertEquals(270f, r[0], 0.001f)
        assertEquals(2376f * 0.38f - 270f, r[1], 0.01f)
    }

    @Test
    fun hookAndPreviewUseSameRectHelper() {
        val hook = java.io.File(
            "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt",
        ).readText()
        val preview = java.io.File(
            "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
        ).readText()
        assertTrue("hook 必须走共用矩形换算", hook.contains("HandPaintGeometry.presentRect"))
        assertTrue("预览必须走共用矩形换算", preview.contains("HandPaintGeometry.presentRect"))
        assertTrue("hook 必须用共用中心常量", hook.contains("HandPaintSource.CENTER_Y"))
        assertTrue("预览必须用共用中心常量", preview.contains("HandPaintSource.CENTER_Y"))
    }

    @Test
    fun hookNoLongerKeepsPrivateCenterConstant() {
        val hook = java.io.File(
            "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt",
        ).readText()
        assertFalse("私有中心常量必须撤掉避免两份漂移", hook.contains("HANDPAINT_CENTER_Y"))
    }
}