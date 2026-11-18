package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.HandPaintMotion
import com.coloros16.liquidglass.engine.HandPaintPoint
import com.coloros16.liquidglass.engine.HandPaintSilk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手绘完整重放契约：去重只折叠停笔、进度按有效点推进、对称复制份数正确。 */
class HandPaintMotionContractTest {

    private fun p(x: Float, y: Float, a: Float = 0.09f) = HandPaintPoint(x, y, 0.3f, 0.4f, 1f, a)

    private fun silk(
        rot: Int = 6,
        mirror: Boolean = true,
        spirals: Int = 2,
    ) = HandPaintSilk(0, true, mirror, rot, spirals, 2f, 510f, 510f, emptyList())

    @Test
    fun emptyInputStaysEmpty() {
        assertTrue(HandPaintMotion.dedupe(emptyList()).isEmpty())
        assertEquals(0, HandPaintMotion.drawnCount(0.5f, 0))
        assertTrue(HandPaintMotion.finished(0f, 0))
        assertEquals(0f, HandPaintMotion.keptRatio(0, 0), 0.0001f)
    }

    @Test
    fun singlePointIsKept() {
        val out = HandPaintMotion.dedupe(listOf(p(1f, 2f)))
        assertEquals(1, out.size)
    }

    /** 停笔：原地连续重复必须折叠成一个。 */
    @Test
    fun consecutiveStillPointsCollapse() {
        val raw = listOf(p(10f, 10f), p(10f, 10f), p(10f, 10f), p(20f, 10f))
        val out = HandPaintMotion.dedupe(raw)
        assertEquals(2, out.size)
        assertEquals(10f, out[0].x, 0.0001f)
        assertEquals(20f, out[1].x, 0.0001f)
    }

    /** 亚阈值抖动也算停笔。 */
    @Test
    fun subEpsilonJitterCountsAsStill() {
        val raw = listOf(p(10f, 10f), p(10.001f, 10.001f), p(10.002f, 10f))
        assertEquals(1, HandPaintMotion.dedupe(raw).size)
    }

    /** 阳性对照：离开原位再回来是真笔画，不能被折叠。 */
    @Test
    fun returningToSameSpotIsNotCollapsed() {
        val raw = listOf(p(10f, 10f), p(50f, 50f), p(10f, 10f))
        val out = HandPaintMotion.dedupe(raw)
        assertEquals("对称复制会让笔画回到同一位置，全局去重会丢笔画", 3, out.size)
    }

    /** 阳性对照：折叠只作用于相邻点，不是全局去重。 */
    @Test
    fun dedupeIsAdjacentOnlyNotGlobal() {
        val raw = listOf(p(1f, 1f), p(2f, 2f), p(1f, 1f), p(2f, 2f))
        assertEquals(4, HandPaintMotion.dedupe(raw).size)
    }

    @Test
    fun progressMapsToValidPointCount() {
        assertEquals(0, HandPaintMotion.drawnCount(0f, 100))
        assertEquals(50, HandPaintMotion.drawnCount(0.5f, 100))
        assertEquals(100, HandPaintMotion.drawnCount(1f, 100))
    }

    @Test
    fun progressIsClamped() {
        assertEquals(0, HandPaintMotion.drawnCount(-3f, 100))
        assertEquals(100, HandPaintMotion.drawnCount(9f, 100))
    }

    @Test
    fun finishedOnlyAtFullCoverage() {
        assertFalse(HandPaintMotion.finished(0.99f, 100))
        assertTrue(HandPaintMotion.finished(1f, 100))
    }

    /** 停笔剔除后播放时长按有效点走，不按原始点数。 */
    @Test
    fun stillTimeIsRemovedFromPlayback() {
        val raw = ArrayList<HandPaintPoint>()
        for (i in 0 until 10) {
            raw.add(p(i.toFloat(), 0f))
            repeat(9) { raw.add(p(i.toFloat(), 0f)) }
        }
        val kept = HandPaintMotion.dedupe(raw)
        assertEquals(100, raw.size)
        assertEquals(10, kept.size)
        assertEquals(0.1f, HandPaintMotion.keptRatio(raw.size, kept.size), 0.0001f)
        assertEquals(5, HandPaintMotion.drawnCount(0.5f, kept.size))
        assertEquals(50, HandPaintMotion.drawnCount(0.5f, raw.size))
    }

    @Test
    fun copiesMultiplyRotateMirrorSpiral() {
        assertEquals(24, HandPaintMotion.copiesOf(silk(rot = 6, mirror = true, spirals = 2)))
        assertEquals(6, HandPaintMotion.copiesOf(silk(rot = 6, mirror = false, spirals = 1)))
        assertEquals(12, HandPaintMotion.copiesOf(silk(rot = 6, mirror = true, spirals = 1)))
    }

    @Test
    fun nonPositiveSymmetryFallsBackToOne() {
        assertEquals(2, HandPaintMotion.copiesOf(silk(rot = 0, mirror = true, spirals = 0)))
        assertEquals(1, HandPaintMotion.copiesOf(silk(rot = -3, mirror = false, spirals = -1)))
    }

    @Test
    fun copyAnglesAreEvenlySpaced() {
        val s = silk(rot = 6)
        for (i in 0 until 6) {
            assertEquals(60f * i, HandPaintMotion.copyAngleDeg(s, i), 0.0001f)
        }
    }

    @Test
    fun copyAngleHandlesNonPositiveRotate() {
        assertEquals(0f, HandPaintMotion.copyAngleDeg(silk(rot = 0), 0), 0.0001f)
    }

    /** 颜色不参与去重判定：同坐标不同色仍属停笔。 */
    @Test
    fun colorDoesNotAffectStillDetection() {
        val raw = listOf(p(5f, 5f, 0.02f), p(5f, 5f, 0.09f))
        assertEquals(1, HandPaintMotion.dedupe(raw).size)
    }
}