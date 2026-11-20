package com.coloros16.liquidglass.engine

import kotlin.math.max

/**
 * 底图与叠加层的 cover 取景折算：同一张壁纸与它处理后的效果图必须落在同一个框里。
 *
 * 预览与 hook 共用本件，避免两侧各写一份 cover 公式后漂移。
 */
object BackdropFit {

    /** zoom 下限：0 或负值会把框收成一点，夹到极小正值而不是崩。 */
    const val MIN_ZOOM = 1e-3f

    /**
     * 返回目标框 left/top/right/bottom（px），中心对齐、按长边覆盖再乘 zoom。
     *
     * 尺寸或位图边长非正时返回 null，调用方据此跳过绘制。
     */
    fun coverRect(
        widthPx: Float,
        heightPx: Float,
        bitmapW: Int,
        bitmapH: Int,
        zoom: Float,
    ): FloatArray? {
        if (widthPx <= 0f || heightPx <= 0f || bitmapW <= 0 || bitmapH <= 0) return null
        val bw = bitmapW.toFloat()
        val bh = bitmapH.toFloat()
        val scale = max(widthPx / bw, heightPx / bh) * max(MIN_ZOOM, zoom)
        val dw = bw * scale
        val dh = bh * scale
        return floatArrayOf(
            (widthPx - dw) / 2f,
            (heightPx - dh) / 2f,
            (widthPx + dw) / 2f,
            (heightPx + dh) / 2f,
        )
    }
}