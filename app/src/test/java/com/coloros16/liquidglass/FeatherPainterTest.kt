package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.FeatherPainter
import com.coloros16.liquidglass.engine.FeatherPainter.Mode
import com.coloros16.liquidglass.engine.MaskGeometry
import com.coloros16.liquidglass.engine.MaskShape
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatherPainterTest {

    @Test
    fun circleWithFeatherUsesRadial() {
        assertEquals(Mode.RADIAL, FeatherPainter.mode(MaskShape.CIRCLE, 300f, 40f))
    }

    @Test
    fun roundedRectWithFeatherMustNotUseRadial() {
        assertEquals(
            "径向渐变会把方角画成圆弧",
            Mode.PATH_RING,
            FeatherPainter.mode(MaskShape.ROUNDED_RECT, 300f, 40f),
        )
    }

    @Test
    fun everyShapeWithFeatherGetsADistinctCorrectMode() {
        assertEquals(Mode.RADIAL, FeatherPainter.mode(MaskShape.CIRCLE, 200f, 30f))
        assertEquals(Mode.PATH_RING, FeatherPainter.mode(MaskShape.ROUNDED_RECT, 200f, 30f))
    }

    @Test
    fun noFeatherMeansNoFeatherPainting() {
        for (s in MaskShape.values()) {
            assertEquals(Mode.NONE, FeatherPainter.mode(s, 300f, 0f))
            assertEquals(Mode.NONE, FeatherPainter.mode(s, 300f, 0.4f))
        }
    }

    @Test
    fun subPixelRadiusMeansNone() {
        for (s in MaskShape.values()) {
            assertEquals(Mode.NONE, FeatherPainter.mode(s, 0f, 40f))
            assertEquals(Mode.NONE, FeatherPainter.mode(s, 0.5f, 40f))
        }
    }

    @Test
    fun featherAtLeastRadiusStillPaintsWholeShapeGradient() {
        assertEquals(
            "内径归零但外径仍在 ⇒ 整个形状都是渐变，不是不画",
            Mode.RADIAL,
            FeatherPainter.mode(MaskShape.CIRCLE, 30f, 40f),
        )
        assertEquals(Mode.PATH_RING, FeatherPainter.mode(MaskShape.ROUNDED_RECT, 30f, 30f))
        assertEquals(0f, FeatherPainter.radialStop(30f, 40f), 0.0001f)
    }

    @Test
    fun radialStopMatchesInnerOverOuter() {
        val stop = FeatherPainter.radialStop(300f, 60f)
        assertEquals(240f / 300f, stop, 0.0001f)
    }

    @Test
    fun radialStopClampedBelowOne() {
        assertTrue(FeatherPainter.radialStop(300f, 0.6f) < 1f)
        assertEquals(0f, FeatherPainter.radialStop(0f, 40f), 0.0001f)
    }

    @Test
    fun ringStepsCoverInnerToOuterMonotonically() {
        var lastT = 0f
        for (i in 0 until FeatherPainter.RING_STEPS) {
            val s = FeatherPainter.ringStep(i)
            assertTrue("t 必须递增", s[0] > lastT)
            assertTrue(s[0] <= 1f)
            lastT = s[0]
        }
        assertEquals("最后一层必须到外径", 1f, lastT, 0.0001f)
    }

    @Test
    fun ringAlphaIncreasesOutward() {
        var lastA = -1f
        for (i in 0 until FeatherPainter.RING_STEPS) {
            val a = FeatherPainter.ringStep(i)[1]
            assertTrue("越外层越不透明", a > lastA)
            assertTrue(a in 0f..1f)
            lastA = a
        }
        assertEquals("最外层完全不透明", 1f, lastA, 0.0001f)
    }

    @Test
    fun accumulatedAlphaMatchesSmoothstepAtEveryRingBoundary() {
        for (i in 0 until FeatherPainter.RING_STEPS) {
            val t = FeatherPainter.ringStep(i)[0]
            val target = FeatherPainter.targetAlphaAt(t)
            assertEquals(
                "第 ${i + 1} 层画完后累计必须等于目标",
                target,
                FeatherPainter.accumulatedAlpha(i + 1),
                0.0005f,
            )
        }
    }

    @Test
    fun perLayerAlphaIsNotTheTargetBecauseSrcOverAccumulates() {
        val mid = FeatherPainter.RING_STEPS / 2
        val s = FeatherPainter.ringStep(mid)
        val target = FeatherPainter.targetAlphaAt(s[0])
        assertTrue(
            "每层自身 alpha 必须小于目标，否则叠加后过暗",
            s[1] < target,
        )
    }

    @Test
    fun positiveControlNaivePerLayerAlphaOvershoots() {
        val steps = FeatherPainter.RING_STEPS
        var opaque = 1f
        for (i in 0 until steps) {
            val t = (i + 1f) / steps
            opaque *= 1f - FeatherPainter.targetAlphaAt(t)
        }
        val naive = 1f - opaque
        val mid = steps / 2
        var naiveMidOpaque = 1f
        for (i in 0..mid) naiveMidOpaque *= 1f - FeatherPainter.targetAlphaAt((i + 1f) / steps)
        val naiveMid = 1f - naiveMidOpaque
        val targetMid = FeatherPainter.targetAlphaAt((mid + 1f) / steps)
        assertTrue("朴素取目标值在中段会明显过暗", naiveMid > targetMid + 0.1f)
        assertEquals("末层两者都到 1", 1f, naive, 0.0005f)
    }

    @Test
    fun accumulatedAlphaIsMonotonicAndBounded() {
        var last = -1f
        for (c in 0..FeatherPainter.RING_STEPS) {
            val a = FeatherPainter.accumulatedAlpha(c)
            assertTrue(a >= last)
            assertTrue(a in 0f..1f)
            last = a
        }
        assertEquals(0f, FeatherPainter.accumulatedAlpha(0), 0.0001f)
        assertEquals(1f, FeatherPainter.accumulatedAlpha(FeatherPainter.RING_STEPS), 0.0005f)
    }

    @Test
    fun ringStepClampsOutOfRangeIndex() {
        val last = FeatherPainter.ringStep(FeatherPainter.RING_STEPS - 1)
        assertArrayEquals(last, FeatherPainter.ringStep(999), 0.0001f)
        assertArrayEquals(FeatherPainter.ringStep(0), FeatherPainter.ringStep(-5), 0.0001f)
    }

    @Test
    fun nonPositiveStepsTreatedAsOne() {
        val s = FeatherPainter.ringStep(0, 0)
        assertEquals(1f, s[0], 0.0001f)
        assertEquals(1f, s[1], 0.0001f)
    }

    @Test
    fun positiveControlOldLogicWouldPickRadialForRoundedRect() {
        val radius = 300f
        val feather = 40f
        val outer = MaskGeometry.featherOuterPx(radius, feather)
        val inner = MaskGeometry.featherInnerPx(radius, feather)
        val oldBranchTaken = MaskGeometry.hasFeather(feather) && outer > inner
        assertTrue("旧判据只看羽化不看形状 ⇒ 圆角矩形也进径向分支", oldBranchTaken)
        assertEquals(
            "新判据必须把它排除出径向",
            Mode.PATH_RING,
            FeatherPainter.mode(MaskShape.ROUNDED_RECT, radius, feather),
        )
    }

    @Test
    fun modeTableIsExhaustiveOverShapes() {
        val modes = MaskShape.values().map { FeatherPainter.mode(it, 300f, 40f) }
        assertEquals(MaskShape.values().size, modes.size)
        assertTrue(modes.contains(Mode.RADIAL))
        assertTrue(modes.contains(Mode.PATH_RING))
    }
}