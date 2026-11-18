package com.coloros16.liquidglass.engine

import kotlin.math.max
import kotlin.math.min

/**
 * 揭开遮罩的几何：圆形与圆角矩形共用一套半径/羽化换算，预览与 hook 共用。
 *
 * 半径由求值器给（已含进度），本件只负责形状换算与羽化环带。
 */
object MaskGeometry {

    /** 圆角矩形的圆角占短边比例。 */
    const val ROUNDED_RECT_CORNER_FRACTION = 0.12f

    /** 圆角矩形的宽高比跟随屏幕，半径按正方形外接折算。 */
    fun roundedRectCornerPx(radiusPx: Float, shortSidePx: Float): Float {
        val cap = shortSidePx * ROUNDED_RECT_CORNER_FRACTION
        return min(max(0f, radiusPx), max(0f, cap))
    }

    /**
     * 圆角矩形遮罩的半边长：把「等效半径」折算成能覆盖同面积的矩形。
     *
     * 圆面积 πr² 与矩形 (2a)(2b) 等面积，按屏幕宽高比分配 a/b。
     */
    fun roundedRectHalfExtents(
        radiusPx: Float,
        widthPx: Float,
        heightPx: Float,
    ): FloatArray {
        if (radiusPx <= 0f || widthPx <= 0f || heightPx <= 0f) return floatArrayOf(0f, 0f)
        val aspect = widthPx / heightPx
        val area = Math.PI.toFloat() * radiusPx * radiusPx
        val halfH = kotlin.math.sqrt(area / (4f * aspect))
        val halfW = halfH * aspect
        return floatArrayOf(min(halfW, widthPx), min(halfH, heightPx))
    }

    /** 羽化内径：遮罩实心部分到此为止。 */
    fun featherInnerPx(radiusPx: Float, featherPx: Float): Float =
        max(0f, radiusPx - max(0f, featherPx))

    /** 羽化外径：到此完全透明。 */
    fun featherOuterPx(radiusPx: Float, featherPx: Float): Float =
        max(0f, radiusPx)

    /** 该半径是否值得画（亚像素直接跳过）。 */
    fun drawable(radiusPx: Float): Boolean = radiusPx > 0.5f

    /** 羽化是否生效（0 = 硬边）。 */
    fun hasFeather(featherPx: Float): Boolean = featherPx > 0.5f

    /** 归一化进度 → 羽化环内的不透明度（内 1 外 0，smoothstep）。 */
    fun featherAlpha(distancePx: Float, innerPx: Float, outerPx: Float): Float {
        if (outerPx <= innerPx) return if (distancePx <= outerPx) 1f else 0f
        val t = ((distancePx - innerPx) / (outerPx - innerPx)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }
}
