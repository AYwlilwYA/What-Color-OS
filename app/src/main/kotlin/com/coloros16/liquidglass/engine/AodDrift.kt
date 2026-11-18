package com.coloros16.liquidglass.engine

/**
 * 息屏防烧屏纵向漂移：按时间推进的阶梯位移，每步之间缓入缓出。
 * 纯 JVM，预览与 hook 共用同一份取值。
 */
object AodDrift {

    /** 漂移幅度（屏幕高度归一化）。 */
    const val AMPLITUDE = 0.022f

    /** 每一步停留时长。 */
    const val STEP_MS = 60_000L

    /** 每一步的过渡时长（缓入缓出）。 */
    const val GLIDE_MS = 4_000L

    private val steps = floatArrayOf(0f, -1f, 0.45f, -0.5f, 1f, 0f)

    /** 当前时刻的纵向偏移（屏幕高度归一化，负值向上）。 */
    fun offsetY(nowMs: Long): Float {
        val cycle = STEP_MS * steps.size
        val time = Math.floorMod(nowMs, cycle)
        val index = (time / STEP_MS).toInt()
        val from = steps[(index + steps.size - 1) % steps.size]
        val to = steps[index % steps.size]
        val phase = (time % STEP_MS).toFloat()
        return (from + (to - from) * smoothstep(phase / GLIDE_MS)) * AMPLITUDE
    }

    private fun smoothstep(t: Float): Float {
        val q = t.coerceIn(0f, 1f)
        return q * q * (3f - 2f * q)
    }
}
