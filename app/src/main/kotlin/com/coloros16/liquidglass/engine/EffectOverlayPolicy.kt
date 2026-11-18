package com.coloros16.liquidglass.engine

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import kotlin.math.max
import kotlin.math.min

/**
 * 壁纸效果叠加层的取样尺寸、可见度与强度：hook 与预览共用同一套折算。
 *
 * 长边封顶降采样，避免整屏逐像素在系统进程里跑满 CPU；
 * 效果作用在壁纸本身，锁屏与桌面都可见，息屏被镜片/照片层盖住不画。
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

    /** 效果层不透明度钳位。 */
    fun strengthFor(alpha: Float): Float = min(1f, max(0f, alpha))

    /**
     * 效果层跨三态可见度：壁纸层在锁屏与桌面都露出，息屏被镜片/照片层整屏盖住。
     *
     * 静止态按当前态取端值；转场期按两端可见度线性插值，
     * 不复用锁屏层透明度（否则锁屏→桌面会跟着锁屏内容一起淡出）。
     */
    fun visibilityOf(state: SceneState): Float = when (state) {
        SceneState.AOD -> 0f
        SceneState.LOCK, SceneState.HOME -> 1f
    }

    /** 转场期效果层可见度；edge 为 null 时取该态静止值。 */
    fun visibilityAt(state: SceneState, edge: TransitionEdge?, t: Float): Float {
        if (edge == null) return visibilityOf(state)
        val from = visibilityOf(edge.from)
        val to = visibilityOf(edge.to)
        val p = t.coerceIn(0f, 1f)
        return strengthFor(from + (to - from) * p)
    }

    /** 该效果 id 是否需要预处理（与当前态无关，选中即构建）。 */
    fun needsBuild(effectId: String?, specialId: String?): Boolean =
        (effectId != null && effectId != EffectCatalog.LOCK_WP_NONE) ||
            (specialId != null && specialId != EffectCatalog.LOCK_SP_NONE)
}