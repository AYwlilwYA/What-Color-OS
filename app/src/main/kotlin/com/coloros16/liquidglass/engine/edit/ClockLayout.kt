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

    /** 横纵拉伸范围钳位。 */
    fun clampStretch(stretch: Float): Float =
        stretch.coerceIn(LockLayoutSettings.MIN_CLOCK_STRETCH, LockLayoutSettings.MAX_CLOCK_STRETCH)

    /**
     * 右下角手柄自由变形：横向位移改横向拉伸、纵向位移改纵向拉伸，两轴互不影响。
     *
     * [startX]/[startY] 为按下时两轴拉伸，[dx]/[dy] 为手指位移，
     * [halfWPx]/[halfHPx] 为按下时框的半宽半高——位移按框自身尺寸折算，
     * 拖过一个框宽即拉伸翻倍，手感与框大小无关。
     */
    fun resizeStretch(
        startX: Float,
        startY: Float,
        dx: Float,
        dy: Float,
        halfWPx: Float,
        halfHPx: Float,
    ): FloatArray {
        val sx = if (halfWPx > 0f) startX * (1f + dx / (halfWPx * 2f)) else startX
        val sy = if (halfHPx > 0f) startY * (1f + dy / (halfHPx * 2f)) else startY
        return floatArrayOf(clampStretch(sx), clampStretch(sy))
    }

    /**
     * 超框即按比例收：框宽含固定留白，故按 (bound−pad)/(measured−pad) 收；直接乘 bound/measured 会欠收。
     */
    fun fitStretch(
        stretchX: Float,
        stretchY: Float,
        measuredW: Float,
        measuredH: Float,
        padW: Float,
        boundW: Float,
        boundH: Float,
    ): FloatArray {
        val nx = if (measuredW > boundW && measuredW > padW && boundW > padW) {
            stretchX * (boundW - padW) / (measuredW - padW)
        } else {
            stretchX
        }
        val ny = if (measuredH > boundH && measuredH > 0f && boundH > 0f) {
            stretchY * boundH / measuredH
        } else {
            stretchY
        }
        return floatArrayOf(nx.coerceIn(LockLayoutSettings.MIN_CLOCK_STRETCH, LockLayoutSettings.MAX_CLOCK_STRETCH),
            ny.coerceIn(LockLayoutSettings.MIN_CLOCK_STRETCH, LockLayoutSettings.MAX_CLOCK_STRETCH),)
    }

    /**
     * 视觉中心 ↔ 配置中心换算（逐轴，位置是「替换」不是「平移」）。
     *
     * 配置值仍是缺省值 = 这一轴没被编辑过 ⇒ 视觉落在场景锚点；
     * 一旦编辑过，配置值就是屏幕归一化的目标位置，视觉直接取它。
     * 编辑手势与绘制必须共用这套换算，否则手柄与画面不同源。
     */
    fun configToVisual(config: Float, anchor: Float, default: Float): Float =
        if (EditAnchorOverlay.isDefaultAxis(config, default)) anchor else config

    fun visualToConfig(visual: Float, anchor: Float, default: Float): Float =
        if (EditAnchorOverlay.isDefaultAxis(visual, anchor)) default else visual

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
