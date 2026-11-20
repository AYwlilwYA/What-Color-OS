package com.coloros16.liquidglass.engine

/**
 * 揭开遮罩的中心：样式显式声明优先，未声明时保持屏幕正中。
 *
 * 样式默认值就是正中，与「用户指定正中」不可区分，故不做锚点推导：
 * 时钟锚点不等于内容中心（手绘时钟在花下方），推导会把圆心放到错位置。
 */
object MaskCenterPolicy {

    /** 屏幕正中（样式未声明时的取值）。 */
    const val SCREEN_CENTER = 0.5f

    /** 判定「显式声明」与「实质变化」的容差。 */
    const val EPS = 1e-4f

    /** 该样式是否显式声明了非正中的遮罩中心。 */
    fun declared(styleX: Float, styleY: Float): Boolean =
        changed(styleX, styleY, SCREEN_CENTER, SCREEN_CENTER)

    /**
     * 求遮罩中心并写入 [out] 的前两位；越界声明夹回 0..1，NaN 视为未声明。
     *
     * [out] 不足两位时不写。
     */
    fun resolve(styleX: Float, styleY: Float, out: FloatArray) {
        if (out.size < 2) return
        val x = if (styleX.isNaN()) SCREEN_CENTER else styleX.coerceIn(0f, 1f)
        val y = if (styleY.isNaN()) SCREEN_CENTER else styleY.coerceIn(0f, 1f)
        out[0] = x
        out[1] = y
    }

    /** 两组中心是否实质不同（用于只在真变时写配方）。 */
    fun changed(ax: Float, ay: Float, bx: Float, by: Float): Boolean =
        kotlin.math.abs(ax - bx) > EPS || kotlin.math.abs(ay - by) > EPS
}
