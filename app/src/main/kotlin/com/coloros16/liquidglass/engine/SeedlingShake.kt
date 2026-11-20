package com.coloros16.liquidglass.engine

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 胶囊晃动求值（纯 JVM）：欠阻尼弹簧横向偏移（参考 k=1200 ζ=0.18 真值），
 * 里程碑/高优先更新触发，振幅随时间指数衰减，收敛后归零。
 */
class SeedlingShake(
    val stiffness: Float = 1200f,
    val dampingRatio: Float = 0.18f,
    val initialPx: Float = 16f,
) {
    private val omega = sqrt(stiffness.toDouble())
    private val zeta = dampingRatio.toDouble()
    private val omegaD = omega * sqrt(1.0 - zeta * zeta)

    /** 触发后 elapsedMs 的横向偏移 px；衰减到 <0.35px 视为收敛返回 0。 */
    fun offsetAt(elapsedMs: Long): Float {
        if (elapsedMs < 0) return 0f
        val t = elapsedMs / 1000.0
        val envelope = initialPx * exp(-zeta * omega * t)
        if (envelope < 0.35) return 0.0f
        return (envelope * cos(omegaD * t)).toFloat()
    }

    fun settled(elapsedMs: Long): Boolean = offsetAt(elapsedMs) == 0f && elapsedMs > 0
}

/**
 * 大卡入场/按压变换（纯 JVM）：t=入场完成度 0→1，scale 0.96→1、drop 4dp→0；
 * 胶囊（morphU≤0.5）t 恒 1（无变换）；按压 scale 0.9~1.0 真值 clamp。
 */
object SeedlingCardTransform {

    /** morphU→入场完成度：胶囊（≤0.5）恒 1（无变换）；大卡 0.5→1 从 0 升满（0.96→1）。 */
    fun enterProgress(morphU: Float): Float =
        if (morphU <= 0.5f) 1f else ((morphU - 0.5f) * 2f).coerceIn(0f, 1f)

    fun enterScale(t: Float, enterScaleStart: Float = 0.96f): Float {
        val u = t.coerceIn(0f, 1f)
        return enterScaleStart + (1f - enterScaleStart) * u
    }

    fun enterDropPx(t: Float, dropDp: Float, density: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return dropDp * density * (1f - u)
    }

    fun pressScale(pressed: Boolean, pressScale: Float = 0.96f): Float =
        if (pressed) pressScale.coerceIn(0.9f, 1.0f) else 1f
}
