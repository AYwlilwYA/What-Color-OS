package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingTouchTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 触摸热区与绘制变换同步契约：正逆变换互逆 + 静止态恒等，含阳性对照。 */
class SeedlingTouchTransformContractTest {

    private val vw = 1080f
    private val iw = 360f
    private val ih = 120f
    private val it = 20f
    private val baseLeft = (vw - iw) / 2f // 360：居中岛左缘（签名已改 baseLeft）

    private fun local(x: Float, y: Float, shake: Float, drop: Float, scale: Float) =
        SeedlingTouchTransform.toLocal(x, y, baseLeft, iw, ih, it, shake, drop, scale)

    private fun screen(l: Float, t: Float, r: Float, b: Float, shake: Float, drop: Float, scale: Float) =
        SeedlingTouchTransform.toScreen(l, t, r, b, baseLeft, iw, ih, it, shake, drop, scale)

    @Test
    fun identityWhenStatic() {
        val (lx, ly) = local(500f, 80f, 0f, 0f, 1f)
        assertEquals(500f, lx, 1e-3f)
        assertEquals(80f, ly, 1e-3f)
        val s = screen(360f, 20f, 720f, 140f, 0f, 0f, 1f)
        assertEquals(360f, s[0], 1e-3f)
        assertEquals(20f, s[1], 1e-3f)
        assertEquals(720f, s[2], 1e-3f)
        assertEquals(140f, s[3], 1e-3f)
    }

    @Test
    fun shakeTranslationInverse() {
        // 绘制右移 16px：屏幕上 x=516 的点，body 局部应是 500
        val (lx, _) = local(516f, 80f, 16f, 0f, 1f)
        assertEquals(500f, lx, 1e-3f)
        // 正变换：body 局部 500 → 屏幕 516
        val s = screen(500f, 80f, 500f, 80f, 16f, 0f, 1f)
        assertEquals(516f, s[0], 1e-3f)
    }

    @Test
    fun dropTranslationInverse() {
        val (_, ly) = local(500f, 92f, 0f, 12f, 1f)
        assertEquals(80f, ly, 1e-3f)
        val s = screen(500f, 80f, 500f, 80f, 0f, 12f, 1f)
        assertEquals(92f, s[1], 1e-3f)
    }

    @Test
    fun scaleInverse_positiveControl() {
        // 阳性对照：缩放 0.96 绕中心，中心点不动，边缘点逆变换后回到原局部坐标
        val pivotX = (vw - iw) / 2f + iw / 2f // 540
        val pivotY = it + ih / 2f // 80
        val (cx, cy) = local(pivotX, pivotY, 0f, 0f, 0.96f)
        assertEquals("缩放中心不动", pivotX, cx, 1e-3f)
        assertEquals(pivotY, cy, 1e-3f)
        // 屏幕上中心右 100px 的点，局部应是 100/0.96
        val (lx, _) = local(pivotX + 100f, pivotY, 0f, 0f, 0.96f)
        assertEquals(pivotX + 100f / 0.96f, lx, 1e-3f)
    }

    @Test
    fun roundTripScreenToLocal() {
        // 正变换后逆变换必回到原点（含晃动+drop+缩放复合）
        val shake = 10f; val drop = 6f; val scale = 0.97f
        val origL = 400f; val origT = 30f
        val s = screen(origL, origT, origL + 50f, origT + 20f, shake, drop, scale)
        val (lx, ly) = local(s[0], s[1], shake, drop, scale)
        assertEquals(origL, lx, 1e-2f)
        assertEquals(origT, ly, 1e-2f)
        val (rx, ry) = local(s[2], s[3], shake, drop, scale)
        assertEquals(origL + 50f, rx, 1e-2f)
        assertEquals(origT + 20f, ry, 1e-2f)
    }

    @Test
    fun screenRectOrderPreserved() {
        val s = screen(360f, 20f, 720f, 140f, 8f, 4f, 0.96f)
        assertTrue("变换后仍 left<right", s[0] < s[2])
        assertTrue("变换后仍 top<bottom", s[1] < s[3])
    }
}
