package com.coloros16.liquidglass.engine.edit

import kotlin.math.min

/** 时钟排版口径：App 画布与真机渲染共用同一套换算。 */
object ClockLayout {

    /** 时钟字号 = 屏短边 × 该比例 × 缩放。 */
    const val BASE_FRACTION = 0.26f

    /** 时钟字号（像素）；非正尺寸返回 0。 */
    fun fontPx(widthPx: Float, heightPx: Float, scale: Float): Float {
        if (widthPx <= 0f || heightPx <= 0f) return 0f
        val clamped = scale.coerceIn(LockLayoutSettings.MIN_CLOCK_SCALE, LockLayoutSettings.MAX_CLOCK_SCALE)
        return min(widthPx, heightPx) * BASE_FRACTION * clamped
    }

    /**
     * 按整串时间宽度再钳一次缩放，避免大字号时横向溢出。
     *
     * [textWidthPx] 为当前 [currentScale] 下实测的字串宽度；[availableWidthPx] 为可用横向空间。
     */
    fun fitScale(
        textWidthPx: Float,
        currentScale: Float,
        availableWidthPx: Float,
        minScale: Float = LockLayoutSettings.MIN_CLOCK_SCALE,
        maxScale: Float = LockLayoutSettings.MAX_CLOCK_SCALE,
    ): Float {
        if (textWidthPx <= 0f || currentScale <= 0f || availableWidthPx <= 0f) {
            return currentScale.coerceIn(minScale, maxScale)
        }
        val widthAtUnitScale = textWidthPx / currentScale
        return (availableWidthPx / widthAtUnitScale).coerceIn(minScale, maxScale)
    }
}
