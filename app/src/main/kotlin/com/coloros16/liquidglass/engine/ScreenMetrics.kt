package com.coloros16.liquidglass.engine

/** 屏幕尺寸（px）；遮挡半径与羽化都按它折算。 */
data class ScreenMetrics(
    val widthPx: Float,
    val heightPx: Float,
    val density: Float = 1f,
) {

    val shortSide: Float
        get() = minOf(widthPx, heightPx)

    val longSide: Float
        get() = maxOf(widthPx, heightPx)

    /** 指定中心到四角的最大距离。 */
    fun maxCornerDistance(centerX: Float, centerY: Float): Float {
        val dx = maxOf(centerX, widthPx - centerX)
        val dy = maxOf(centerY, heightPx - centerY)
        return kotlin.math.hypot(dx, dy)
    }
}
