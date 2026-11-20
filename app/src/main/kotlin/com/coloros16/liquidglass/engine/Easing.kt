package com.coloros16.liquidglass.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** 时间曲线：输入输出都在 0..1。 */
fun interface Easing {
    fun map(t: Float): Float

    companion object {
        val LINEAR = Easing { t -> t.coerceIn(0f, 1f) }
        val STANDARD = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val FAST_OUT_SLOW_IN = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
        val DECELERATE = CubicBezierEasing(0f, 0f, 0.2f, 1f)
        val ACCELERATE = CubicBezierEasing(0.4f, 0f, 1f, 1f)

        /** 与平台同名插值器一致：余弦式先加速后减速。 */
        val ACCELERATE_DECELERATE = Easing { t ->
            ((cos((t.coerceIn(0f, 1f) + 1f) * PI) / 2.0) + 0.5).toFloat()
        }

        /** 弹出过冲：冲过 1 再回落，用于「转着弹出来」的落位。 */
        val OVERSHOOT = Easing { t ->
            val q = t.coerceIn(0f, 1f) - 1f
            q * q * ((OVERSHOOT_TENSION + 1f) * q + OVERSHOOT_TENSION) + 1f
        }

        /** 过冲张力：越大冲得越远，2.0 对应峰值约 1.10。 */
        const val OVERSHOOT_TENSION = 2.0f
    }
}

/**
 * 阻尼弹簧缓动：单自由度简谐解析解，t 为段内归一化进度。
 * stiffness 沿用系统同名字段，wn = sqrt(stiffness)；dampingRatio < 1 时允许过冲。
 */
class SpringEasing(
    private val stiffness: Float,
    private val dampingRatio: Float,
    private val durationMs: Float,
) : Easing {

    override fun map(t: Float): Float {
        val q = t.coerceIn(0f, 1f)
        if (q <= 0f) return 0f
        if (q >= 1f) return 1f
        val wn = sqrt(stiffness.toDouble())
        val zeta = dampingRatio.toDouble().coerceAtLeast(0.01)
        val seconds = durationMs / 1000.0 * q
        val value = if (zeta < 0.999) {
            val wd = wn * sqrt(1.0 - zeta * zeta)
            val decay = exp(-zeta * wn * seconds)
            1.0 - decay * (cos(wd * seconds) + (zeta * wn / wd) * sin(wd * seconds))
        } else {
            1.0 - exp(-wn * seconds) * (1.0 + wn * seconds)
        }
        return value.toFloat().coerceAtLeast(0f)
    }
}

/** 三次贝塞尔缓动：按 x 解 u 再取 y，牛顿迭代为主、不收敛时退化为线性近似。 */
class CubicBezierEasing(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
) : Easing {

    override fun map(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        var u = t
        var solved = false
        for (i in 0 until 6) {
            val x = bezier(u, x1, x2)
            val dx = derivative(u, x1, x2)
            if (dx < 1e-4f) break
            val next = u - (x - t) / dx
            if (next < 0f || next > 1f) break
            u = next
            solved = true
        }
        if (!solved) {
            var lo = 0f
            var hi = 1f
            u = t
            repeat(12) {
                val x = bezier(u, x1, x2)
                if (x < t) lo = u else hi = u
                u = (lo + hi) * 0.5f
            }
        }
        return bezier(u, y1, y2)
    }

    private fun bezier(u: Float, p1: Float, p2: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * u * p1 + 3f * inv * u * u * p2 + u * u * u
    }

    private fun derivative(u: Float, p1: Float, p2: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * p1 + 6f * inv * u * (p2 - p1) + 3f * u * u * (1f - p2)
    }
}
