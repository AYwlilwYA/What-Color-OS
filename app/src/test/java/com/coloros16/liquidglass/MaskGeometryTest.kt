package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.MaskGeometry
import com.coloros16.liquidglass.engine.MaskShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskGeometryTest {

    @Test
    fun drawableSkipsSubPixel() {
        assertFalse(MaskGeometry.drawable(0f))
        assertFalse(MaskGeometry.drawable(0.5f))
        assertTrue(MaskGeometry.drawable(0.6f))
    }

    @Test
    fun featherFlag() {
        assertFalse(MaskGeometry.hasFeather(0f))
        assertFalse(MaskGeometry.hasFeather(0.5f))
        assertTrue(MaskGeometry.hasFeather(1f))
    }

    @Test
    fun featherBandInnerNeverNegative() {
        assertEquals(80f, MaskGeometry.featherInnerPx(100f, 20f), 0.001f)
        assertEquals(0f, MaskGeometry.featherInnerPx(10f, 40f), 0.001f)
        assertEquals(100f, MaskGeometry.featherOuterPx(100f, 20f), 0.001f)
        assertEquals(0f, MaskGeometry.featherOuterPx(-5f, 20f), 0.001f)
    }

    @Test
    fun featherAlphaEndpointsAndMonotonic() {
        assertEquals(1f, MaskGeometry.featherAlpha(80f, 80f, 100f), 0.001f)
        assertEquals(0f, MaskGeometry.featherAlpha(100f, 80f, 100f), 0.001f)
        assertEquals(1f, MaskGeometry.featherAlpha(0f, 80f, 100f), 0.001f)
        assertEquals(0f, MaskGeometry.featherAlpha(200f, 80f, 100f), 0.001f)
        var last = 1f
        var d = 80f
        while (d <= 100f) {
            val a = MaskGeometry.featherAlpha(d, 80f, 100f)
            assertTrue("alpha must not increase outward", a <= last + 0.0001f)
            last = a
            d += 1f
        }
    }

    @Test
    fun featherAlphaHardEdgeWhenBandEmpty() {
        assertEquals(1f, MaskGeometry.featherAlpha(50f, 100f, 100f), 0.001f)
        assertEquals(0f, MaskGeometry.featherAlpha(101f, 100f, 100f), 0.001f)
    }

    @Test
    fun featherAlphaMidpointIsHalf() {
        assertEquals(0.5f, MaskGeometry.featherAlpha(90f, 80f, 100f), 0.001f)
    }

    @Test
    fun roundedRectCornerCappedByShortSide() {
        val short = 1080f
        val cap = short * MaskGeometry.ROUNDED_RECT_CORNER_FRACTION
        assertEquals(cap, MaskGeometry.roundedRectCornerPx(9999f, short), 0.001f)
        assertEquals(50f, MaskGeometry.roundedRectCornerPx(50f, short), 0.001f)
        assertEquals(0f, MaskGeometry.roundedRectCornerPx(-10f, short), 0.001f)
    }

    @Test
    fun roundedRectExtentsKeepScreenAspect() {
        val e = MaskGeometry.roundedRectHalfExtents(300f, 1080f, 2400f)
        assertTrue(e[0] > 0f && e[1] > 0f)
        assertEquals(1080f / 2400f, e[0] / e[1], 0.001f)
    }

    @Test
    fun roundedRectAreaMatchesCircle() {
        val r = 300f
        val e = MaskGeometry.roundedRectHalfExtents(r, 1080f, 2400f)
        val rectArea = (2f * e[0]) * (2f * e[1])
        val circleArea = Math.PI.toFloat() * r * r
        assertEquals(circleArea, rectArea, circleArea * 0.001f)
    }

    @Test
    fun roundedRectExtentsClampedToScreen() {
        val e = MaskGeometry.roundedRectHalfExtents(99999f, 1080f, 2400f)
        assertTrue(e[0] <= 1080f)
        assertTrue(e[1] <= 2400f)
    }

    @Test
    fun roundedRectDegenerateInputReturnsZero() {
        val a = MaskGeometry.roundedRectHalfExtents(0f, 1080f, 2400f)
        assertEquals(0f, a[0], 0.001f)
        assertEquals(0f, a[1], 0.001f)
        val b = MaskGeometry.roundedRectHalfExtents(100f, 0f, 2400f)
        assertEquals(0f, b[0], 0.001f)
    }

    @Test
    fun maskShapeEnumHasBothVariants() {
        assertEquals(2, MaskShape.values().size)
        assertEquals(MaskShape.CIRCLE, MaskShape.valueOf("CIRCLE"))
        assertEquals(MaskShape.ROUNDED_RECT, MaskShape.valueOf("ROUNDED_RECT"))
    }
}
