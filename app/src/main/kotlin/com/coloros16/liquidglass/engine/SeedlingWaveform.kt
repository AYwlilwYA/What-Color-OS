package com.coloros16.liquidglass.engine

import kotlin.math.pow
import kotlin.math.sin

/**
 * 胶囊音乐波形求值（纯 JVM，渲染端只消费高度）：4 根条，三重正弦叠加 pow1.35，
 * 呼吸 0.9~1.0，幅值低于 0.5 的条退化为圆点。相位/幅度常数取自参考绘制器。
 */
object SeedlingWaveform {

    private val PHASE_A = floatArrayOf(3.7f, 4.3f, 3.1f, 4.9f)
    private val PHASE_B = floatArrayOf(2.3f, 1.9f, 2.7f, 2.1f)
    private val PHASE_C = floatArrayOf(1.1f, 1.37f, 0.93f, 1.21f)
    private val GAIN = floatArrayOf(0.74f, 1.0f, 0.9f, 0.68f)

    /** 呼吸系数：0.9 + 0.1×(sin(1.9t)×0.5+0.5)，t 为秒。 */
    fun breath(timeSec: Double): Float =
        (((sin(1.9 * timeSec).toFloat() * 0.5f) + 0.5f) * 0.1f) + 0.9f

    /** 第 bar 根条的幅值系数（0.2~1.0+），timeSec 为秒。 */
    fun amplitude(bar: Int, timeSec: Double): Float {
        val i = bar.coerceIn(0, 3)
        val d = i.toDouble()
        val mixed = sin(d * 0.6 + PHASE_A[i] * timeSec) * 0.15 +
            sin(2.9 * d + PHASE_B[i] * timeSec) * 0.3 +
            sin(1.7 * d + PHASE_C[i] * timeSec) * 0.55
        val shaped = (mixed * 0.5 + 0.5).toFloat().pow(1.35f)
        return shaped * 0.8f * GAIN[i] * breath(timeSec) + 0.2f
    }

    /** 幅值低于 0.5 → 画圆点而非竖条。 */
    fun isDot(bar: Int, timeSec: Double): Boolean = amplitude(bar, timeSec) < 0.5f
}