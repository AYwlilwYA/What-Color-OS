package com.coloros16.liquidglass.engine

/** 时间曲线：输入输出都在 0..1。 */
fun interface Easing {
    fun map(t: Float): Float

    companion object {
        val LINEAR = Easing { t -> t.coerceIn(0f, 1f) }
        val STANDARD = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val FAST_OUT_SLOW_IN = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
        val DECELERATE = CubicBezierEasing(0f, 0f, 0.2f, 1f)
        val ACCELERATE = CubicBezierEasing(0.4f, 0f, 1f, 1f)
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
