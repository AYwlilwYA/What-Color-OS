package com.coloros16.liquidglass.engine

import kotlin.math.max
import kotlin.math.min

/**
 * 壁纸效果叠加层的取样尺寸与强度：hook 与预览共用同一套折算。
 *
 * 长边封顶降采样，避免整屏逐像素在系统进程里跑满 CPU；
 * 强度跟锁屏层透明度，转场期与静止期同一口径。
 */
object EffectOverlayPolicy {

    /** 叠加层长边上限（px）。 */
    const val MAX_LONG_SIDE = 720

    /** 低于此不透明度不画（省一次整屏 drawBitmap）。 */
    const val MIN_ALPHA = 0.002f

    /** 长边封顶后的目标尺寸，保持宽高比；非正尺寸原样返回交由调用方守卫。 */
    fun targetSize(widthPx: Int, heightPx: Int): IntArray {
        if (widthPx <= 0 || heightPx <= 0) return intArrayOf(widthPx, heightPx)
        val long = max(widthPx, heightPx)
        if (long <= MAX_LONG_SIDE) return intArrayOf(widthPx, heightPx)
        val scale = MAX_LONG_SIDE.toFloat() / long
        return intArrayOf(
            max(1, (widthPx * scale).toInt()),
            max(1, (heightPx * scale).toInt()),
        )
    }

    /** 缓存键：效果 id + 折算后尺寸（原始尺寸不同但折算相同应复用）。 */
    fun keyOf(effectId: String?, widthPx: Int, heightPx: Int): String {
        val size = targetSize(widthPx, heightPx)
        return "$effectId|${size[0]}x${size[1]}"
    }

    /** 该强度是否值得画。 */
    fun drawable(alpha: Float): Boolean = alpha > MIN_ALPHA

    /** 强度 → 8bit alpha。 */
    fun alphaByte(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)

    /** 两次强度是否可视为相同（避免每帧 invalidate）。 */
    fun sameAlpha(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < MIN_ALPHA

    /** 效果只在锁屏可见时叠加：取锁屏层透明度，息屏/桌面为 0。 */
    fun strengthFor(lockAlpha: Float): Float = min(1f, max(0f, lockAlpha))
}