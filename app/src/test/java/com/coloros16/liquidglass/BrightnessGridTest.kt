package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.BrightnessGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局部亮度网格契约：整屏均值判不出局部浅底，按格取值必须能判对。
 * 每条都配阳性对照，证明「局部 vs 整屏」真的不同，而不是恒等。
 */
class BrightnessGridTest {

    private val w = 720
    private val h = 1584

    private fun argb(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** 造图：上半暗（v=dark）、下半亮（v=light），模拟暗壁纸 + 浅色面板。 */
    private fun splitScreen(dark: Int, light: Int): IntArray {
        val px = IntArray(w * h)
        val half = h / 2
        for (y in 0 until h) {
            val c = argb(if (y < half) dark else light)
            val row = y * w
            for (x in 0 until w) px[row + x] = c
        }
        return px
    }

    private fun wholeScreenAvg(px: IntArray): Int {
        var sum = 0L
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                sum += BrightnessGrid.luminance(px[y * w + x]); n++; x += BrightnessGrid.STEP
            }
            y += BrightnessGrid.STEP
        }
        return (sum / n).toInt()
    }

    // ---------- 核心缺陷复现与修复 ----------

    /** ★阳性对照：暗壁纸+浅色面板，整屏均值判暗底，而面板处局部值必须判浅底。 */
    @Test
    fun localBeatsWholeScreenAverageOnLightPanelOverDarkWallpaper() {
        val px = splitScreen(dark = 10, light = 245)
        val avg = wholeScreenAvg(px)
        val grid = BrightnessGrid.build(px, w, h)
        assertNotNull(grid)

        val panelY = h * 3 / 4
        val local = BrightnessGrid.at(grid, w / 2, panelY, w, h)

        assertTrue("整屏均值应被判暗底（缺陷复现）avg=$avg", avg <= 196)
        assertTrue("面板处局部值应被判浅底 local=$local", local > 196)
        assertTrue("两者必须真的不同，否则局部化无意义 avg=$avg local=$local", local - avg > 50)
    }

    /** 暗区局部值仍判暗底：局部化不能把暗底也刷成浅底。 */
    @Test
    fun darkRegionStaysDark() {
        val px = splitScreen(dark = 10, light = 245)
        val grid = BrightnessGrid.build(px, w, h)
        val local = BrightnessGrid.at(grid, w / 2, h / 4, w, h)
        assertTrue("暗区局部值应判暗底 local=$local", local <= 196)
    }

    /** 纯色图：局部值与整屏均值一致（证明网格没引入偏差）。 */
    @Test
    fun uniformImageLocalEqualsAverage() {
        val px = IntArray(w * h) { argb(128) }
        val grid = BrightnessGrid.build(px, w, h)
        val avg = wholeScreenAvg(px)
        for (y in intArrayOf(0, h / 3, h / 2, h - 1)) {
            for (x in intArrayOf(0, w / 2, w - 1)) {
                assertEquals("纯色图局部应等于整屏 avg=$avg", avg, BrightnessGrid.at(grid, x, y, w, h))
            }
        }
    }

    /** 左右分屏：横向也要分得开（不只是纵向）。 */
    @Test
    fun horizontalSplitIsResolved() {
        val px = IntArray(w * h)
        val half = w / 2
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) px[row + x] = argb(if (x < half) 10 else 245)
        }
        val grid = BrightnessGrid.build(px, w, h)
        val left = BrightnessGrid.at(grid, w / 4, h / 2, w, h)
        val right = BrightnessGrid.at(grid, w * 3 / 4, h / 2, w, h)
        assertTrue("左暗 local=$left", left <= 196)
        assertTrue("右亮 local=$right", right > 196)
    }

    // ---------- 边界与降级 ----------

    /** 越界坐标夹到边缘格，不抛不返 -1。 */
    @Test
    fun outOfRangeClampsToEdgeCell() {
        val px = splitScreen(dark = 10, light = 245)
        val grid = BrightnessGrid.build(px, w, h)
        val bottom = BrightnessGrid.at(grid, w / 2, h - 1, w, h)
        assertEquals(bottom, BrightnessGrid.at(grid, w / 2, h * 5, w, h))
        assertEquals(bottom, BrightnessGrid.at(grid, w * 5, h * 5, w, h))
        val top = BrightnessGrid.at(grid, 0, 0, w, h)
        assertEquals(top, BrightnessGrid.at(grid, -100, -100, w, h))
    }

    /** 无网格 / 尺寸非法 → -1，调用方据此回落整屏均值。 */
    @Test
    fun missingGridOrBadSizeReturnsMinusOne() {
        assertEquals(-1, BrightnessGrid.at(null, 10, 10, w, h))
        val grid = BrightnessGrid.build(IntArray(w * h) { argb(200) }, w, h)
        assertEquals(-1, BrightnessGrid.at(grid, 10, 10, 0, h))
        assertEquals(-1, BrightnessGrid.at(grid, 10, 10, w, 0))
        assertEquals(-1, BrightnessGrid.at(grid, 10, 10, -1, -1))
    }

    /** 尺寸非法或缓冲不足 → build 返回 null，不越界读。 */
    @Test
    fun buildRejectsBadInput() {
        assertNull(BrightnessGrid.build(IntArray(10), 0, 10))
        assertNull(BrightnessGrid.build(IntArray(10), 10, 0))
        assertNull(BrightnessGrid.build(IntArray(10), 100, 100))
    }

    /** 格下标行优先且落在 0..GRID²-1。 */
    @Test
    fun cellIndexIsRowMajorAndBounded() {
        assertEquals(0, BrightnessGrid.cellIndex(0, 0, w, h))
        assertEquals(BrightnessGrid.GRID * BrightnessGrid.GRID - 1, BrightnessGrid.cellIndex(w, h, w, h))
        for (y in intArrayOf(0, h / 2, h)) {
            for (x in intArrayOf(0, w / 2, w)) {
                val k = BrightnessGrid.cellIndex(x, y, w, h)
                assertTrue("下标越界 k=$k", k in 0 until BrightnessGrid.GRID * BrightnessGrid.GRID)
            }
        }
    }

    /** 亮度公式与 hook 侧一致（BT.601），白=255 黑=0。 */
    @Test
    fun luminanceMatchesBt601() {
        assertEquals(0, BrightnessGrid.luminance(argb(0)))
        assertEquals(255, BrightnessGrid.luminance(argb(255)))
        assertEquals(128, BrightnessGrid.luminance(argb(128)))
        assertEquals(76, BrightnessGrid.luminance((0xFF shl 24) or (255 shl 16)))
    }
}