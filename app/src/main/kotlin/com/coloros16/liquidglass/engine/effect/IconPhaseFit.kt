package com.coloros16.liquidglass.engine.effect

/**
 * 图标入场相位按边长自适应：末位必须在边结束前落位。
 *
 * 用户配的时长与错峰是「期望节奏」，边长是硬约束；
 * 期望超出边长时两者等比压缩，保持错峰形状不变、不改用户配置的默认值。
 */
object IconPhaseFit {

    /** 末位落位所需总时长。 */
    fun neededMs(count: Int, durationMs: Float, staggerMs: Float): Float {
        val last = (count - 1).coerceAtLeast(0)
        return last * staggerMs.coerceAtLeast(0f) + durationMs.coerceAtLeast(0f)
    }

    /**
     * 求边内可用的 (时长, 错峰)：放得下原样返回，放不下等比压缩。
     *
     * 边长非正视为无约束（静止态调用方传 -1），原样返回。
     */
    fun fit(edgeMs: Long, count: Int, durationMs: Float, staggerMs: Float): Pair<Float, Float> {
        val need = neededMs(count, durationMs, staggerMs)
        if (edgeMs <= 0L || need <= 0f || need <= edgeMs) return durationMs to staggerMs
        val k = edgeMs.toFloat() / need
        return (durationMs * k) to (staggerMs * k)
    }

    /** 是否发生了压缩（诊断用）。 */
    fun compressed(edgeMs: Long, count: Int, durationMs: Float, staggerMs: Float): Boolean {
        val (d, s) = fit(edgeMs, count, durationMs, staggerMs)
        return d != durationMs || s != staggerMs
    }
}