package com.coloros16.liquidglass.engine

/**
 * 羽化画法选择：径向渐变只对圆形精确，其它形状必须走路径。
 *
 * 纯求值；具体绘制由调用方做。
 */
object FeatherPainter {

    /** 羽化画法。 */
    enum class Mode {
        /** 不画羽化（硬边或半径为零）。 */
        NONE,

        /** 径向渐变：仅圆形精确，一次 drawRect 无 saveLayer。 */
        RADIAL,

        /** 路径环带：非圆形形状用，按内外轮廓差集画。 */
        PATH_RING,
    }

    /**
     * 选画法。
     *
     * 圆角矩形用径向渐变会把方角画成圆弧 ⇒ 必须走路径。
     */
    fun mode(shape: MaskShape, radiusPx: Float, featherPx: Float): Mode {
        if (!MaskGeometry.drawable(radiusPx)) return Mode.NONE
        if (!MaskGeometry.hasFeather(featherPx)) return Mode.NONE
        val outer = MaskGeometry.featherOuterPx(radiusPx, featherPx)
        val inner = MaskGeometry.featherInnerPx(radiusPx, featherPx)
        if (outer <= inner) return Mode.NONE
        return when (shape) {
            MaskShape.CIRCLE -> Mode.RADIAL
            MaskShape.ROUNDED_RECT -> Mode.PATH_RING
        }
    }

    /** 径向渐变的透明段占比（内径/外径）。 */
    fun radialStop(radiusPx: Float, featherPx: Float): Float {
        val outer = MaskGeometry.featherOuterPx(radiusPx, featherPx)
        if (outer <= 0f) return 0f
        val inner = MaskGeometry.featherInnerPx(radiusPx, featherPx)
        return (inner / outer).coerceIn(0f, 0.999f)
    }

    /**
     * 路径环带的分层数。
     *
     * 层数越多越接近 smoothstep；取固定小值避免逐帧大量绘制。
     */
    const val RING_STEPS = 6

    /**
     * 第 i 层（0=最内）的半径插值因子与该层自身不透明度。
     *
     * 返回 [t, alpha]。多层由外向内叠画走 src-over，累计不透明度是
     * `1 - Π(1-alpha_j)`，故每层不能直接取目标值，否则叠加后过暗。
     * 这里按「本层画完后累计恰好等于目标」反解。
     */
    fun ringStep(i: Int, steps: Int = RING_STEPS): FloatArray {
        val n = if (steps < 1) 1 else steps
        val k = i.coerceIn(0, n - 1)
        var opaque = 1f
        var alpha = 0f
        var t = 0f
        for (j in 0..k) {
            t = (j + 1f) / n
            val target = 1f - MaskGeometry.featherAlpha(t, 0f, 1f)
            alpha = if (opaque <= 0f) 1f else (1f - (1f - target) / opaque).coerceIn(0f, 1f)
            opaque *= 1f - alpha
        }
        return floatArrayOf(t, alpha)
    }

    /** 前 count 层叠画后的累计不透明度（src-over）。 */
    fun accumulatedAlpha(count: Int, steps: Int = RING_STEPS): Float {
        val n = if (steps < 1) 1 else steps
        var opaque = 1f
        for (j in 0 until count.coerceIn(0, n)) opaque *= 1f - ringStep(j, n)[1]
        return 1f - opaque
    }

    /** 该层的半径插值因子对应的目标累计不透明度。 */
    fun targetAlphaAt(t: Float): Float = 1f - MaskGeometry.featherAlpha(t, 0f, 1f)
}