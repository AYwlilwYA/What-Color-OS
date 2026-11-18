package com.coloros16.liquidglass.engine.edit

import kotlin.math.min

/** 时钟排版口径：App 画布与真机渲染共用同一套换算。 */
object ClockLayout {

    /** 时钟字号 = 屏短边 × 该比例 × 缩放。 */
    const val BASE_FRACTION = 0.26f

    /**
     * 时钟字号（像素）；非正尺寸返回 0。
     *
     * [scale] 是已合成的最终缩放（转场姿态 × 用户配置），只做正数守卫，
     * 不再套用户可拖范围——否则动画真值会被配置下限钳住导致大小突变。
     */
    fun fontPx(widthPx: Float, heightPx: Float, scale: Float): Float {
        if (widthPx <= 0f || heightPx <= 0f || scale <= 0f) return 0f
        return min(widthPx, heightPx) * BASE_FRACTION * scale
    }

    /** 用户可拖缩放范围钳位，只在写回配置时用。 */
    fun clampUserScale(scale: Float): Float =
        scale.coerceIn(LockLayoutSettings.MIN_CLOCK_SCALE, LockLayoutSettings.MAX_CLOCK_SCALE)

    /**
     * 右下角手柄拖动求缩放：左上角钉死，位移换算成字号增量。
     *
     * [startScale] 为按下时缩放，[dx]/[dy] 为手指位移，[shortEdgePx] 为画布短边。
     * 结果按用户可拖范围钳位；框左上角不动由调用方按新字号反算中心实现。
     */
    fun resizeScale(startScale: Float, dx: Float, dy: Float, shortEdgePx: Float): Float {
        if (shortEdgePx <= 0f) return clampUserScale(startScale)
        val delta = (dx + dy) / (shortEdgePx * RESIZE_TRAVEL)
        return clampUserScale(startScale + delta)
    }

    /**
     * 缩放后保持左上角不动的中心位移（归一化）。
     *
     * 框半宽/半高随字号线性变化，中心须同量右移/下移，左上角才不漂。
     */
    fun anchorShift(
        oldHalfWPx: Float,
        oldHalfHPx: Float,
        newHalfWPx: Float,
        newHalfHPx: Float,
        widthPx: Float,
        heightPx: Float,
    ): FloatArray {
        if (widthPx <= 0f || heightPx <= 0f) return floatArrayOf(0f, 0f)
        return floatArrayOf(
            (newHalfWPx - oldHalfWPx) / widthPx,
            (newHalfHPx - oldHalfHPx) / heightPx,
        )
    }

    /** 手柄拖动行程系数：短边的该倍数对应缩放 1.0。 */
    const val RESIZE_TRAVEL = 0.35f

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
