package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.PaintOccupancy
import com.coloros16.liquidglass.engine.PaintOccupancy.Box
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintOccupancyTest {

    private fun box(l: Float, t: Float, w: Float = 100f, h: Float = 100f) = Box(l, t, w, h)

    @Test
    fun identicalBoxesKeepOnlyFirst() {
        val keep = PaintOccupancy.accept(listOf(box(0f, 0f), box(0f, 0f)))
        assertArrayEquals(intArrayOf(0), keep)
    }

    @Test
    fun disjointBoxesAllKept() {
        val keep = PaintOccupancy.accept(listOf(box(0f, 0f), box(200f, 0f), box(0f, 200f)))
        assertArrayEquals(intArrayOf(0, 1, 2), keep)
    }

    @Test
    fun touchingEdgesAreNotOverlap() {
        assertFalse(PaintOccupancy.intersects(box(0f, 0f), box(100f, 0f)))
        assertArrayEquals(intArrayOf(0, 1), PaintOccupancy.accept(listOf(box(0f, 0f), box(100f, 0f))))
    }

    @Test
    fun onePixelOverlapIsDetected() {
        assertTrue(PaintOccupancy.intersects(box(0f, 0f), box(99f, 0f)))
        assertArrayEquals(intArrayOf(0), PaintOccupancy.accept(listOf(box(0f, 0f), box(99f, 0f))))
    }

    @Test
    fun containmentCountsAsOverlap() {
        assertTrue(PaintOccupancy.intersects(box(0f, 0f, 200f, 200f), box(50f, 50f, 20f, 20f)))
        assertArrayEquals(intArrayOf(0), PaintOccupancy.accept(listOf(box(0f, 0f, 200f, 200f), box(50f, 50f, 20f, 20f))))
    }

    @Test
    fun laterItemYieldsToEarlierRegardlessOfSize() {
        val keep = PaintOccupancy.accept(listOf(box(50f, 50f, 20f, 20f), box(0f, 0f, 200f, 200f)))
        assertArrayEquals("先来者优先，与大小无关", intArrayOf(0), keep)
    }

    @Test
    fun invalidBoxesSkipped() {
        val keep = PaintOccupancy.accept(listOf(box(0f, 0f, 0f, 0f), box(10f, 10f), box(0f, 0f, -5f, 10f)))
        assertArrayEquals(intArrayOf(1), keep)
    }

    @Test
    fun emptyInputYieldsEmpty() {
        assertEquals(0, PaintOccupancy.accept(emptyList()).size)
    }

    @Test
    fun keptOrderMatchesInputOrder() {
        val keep = PaintOccupancy.accept(
            listOf(box(0f, 0f), box(0f, 0f), box(200f, 0f), box(200f, 0f), box(400f, 0f)),
        )
        assertArrayEquals(intArrayOf(0, 2, 4), keep)
    }

    @Test
    fun chainOverlapKeepsOnlyNonIntersectingOnes() {
        val keep = PaintOccupancy.accept(listOf(box(0f, 0f), box(50f, 0f), box(100f, 0f), box(150f, 0f)))
        assertArrayEquals("0 与 100 相接不算重叠", intArrayOf(0, 2), keep)
    }

    @Test
    fun stackedAlphaIsNearlyDoubleForTranslucentPlate() {
        val plate = 56f / 255f
        val stacked = PaintOccupancy.stackedAlpha(plate)
        assertEquals(0.3910f, stacked, 0.0005f)
        assertTrue("叠两层比单层深 78%", stacked / plate > 1.7f)
    }

    @Test
    fun positiveControlTwoWidgetsSameCellMuchDarkerThanIntended() {
        val plate = 56f / 255f
        val stroke = 90f / 255f
        fun over(a: Float, b: Float) = 1f - (1f - a) * (1f - b)
        val intended = over(plate, stroke)
        var two = 0f
        for (x in floatArrayOf(plate, stroke, plate, stroke)) two = over(two, x)
        assertEquals(0.4950f, intended, 0.0005f)
        assertEquals(0.7450f, two, 0.0005f)
        assertTrue("同格两组件比设计深 60/255 以上", (two - intended) * 255f > 60f)
    }

    @Test
    fun dedupeRestoresIntendedAlpha() {
        val plate = 56f / 255f
        val stroke = 90f / 255f
        val keep = PaintOccupancy.accept(listOf(box(0f, 0f), box(0f, 0f)))
        assertEquals(1, keep.size)
        val intended = 1f - (1f - plate) * (1f - stroke)
        var got = 0f
        for (ignored in keep) {
            got = 1f - (1f - got) * (1f - plate)
            got = 1f - (1f - got) * (1f - stroke)
        }
        assertEquals(intended, got, 0.0005f)
    }

    @Test
    fun gridCellCollisionIsReachableWithFourByFourGrid() {
        val cols = 4
        val rows = 4
        val cells = HashSet<Int>()
        val xs = floatArrayOf(0.00f, 0.03f, 0.60f, 0.63f, 0.90f)
        val ys = floatArrayOf(0.00f, 0.04f, 0.60f, 0.62f, 0.90f)
        var collisions = 0
        for (i in xs.indices) {
            val c = Math.round(xs[i] * cols).coerceIn(0, cols - 1)
            val r = Math.round(ys[i] * rows).coerceIn(0, rows - 1)
            if (!cells.add(r * cols + c)) collisions++
        }
        assertTrue("5 个组件在 4x4 网格上取整后确实会撞格", collisions > 0)
    }
}