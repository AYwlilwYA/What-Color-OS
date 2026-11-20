package com.coloros16.liquidglass.engine

/**
 * 胶囊出现/内容变化的双段过冲包络（纯 JVM，App 预览与 hook 共用）。
 * 第一段冲到目标+过冲量，第二段回落精确目标；高度方向小过冲同步。
 * 大位移（胶囊↔大卡）禁用本包络（ζ<1 铁律），只用于胶囊态宽度弹动与出现。
 */
class SeedlingOvershootEnvelope(
    val fromWidth: Float,
    val toWidth: Float,
    val fromHeight: Float,
    val toHeight: Float,
    val extraWidthPx: Float,
    val extraHeightPx: Float,
    val phase1Ms: Long = 300L,
    val phase2Ms: Long = 500L,
) {
    val totalMs: Long = phase1Ms + phase2Ms

    data class Sample(val width: Float, val height: Float, val contentScale: Float, val contentBlurDp: Float, val done: Boolean)

    fun sample(elapsedMs: Long): Sample {
        if (elapsedMs >= totalMs) {
            return Sample(toWidth, toHeight, 1f, 0f, true)
        }
        return if (elapsedMs < phase1Ms) {
            val t = ease(elapsedMs.toFloat() / phase1Ms)
            val peakW = toWidth + extraWidthPx
            val peakH = toHeight + extraHeightPx
            Sample(
                width = fromWidth + (peakW - fromWidth) * t,
                height = fromHeight + (peakH - fromHeight) * t,
                contentScale = 1f + 0.1f * t,
                contentBlurDp = 9f * (1f - t),
                done = false,
            )
        } else {
            val t = ease((elapsedMs - phase1Ms).toFloat() / phase2Ms)
            val peakW = toWidth + extraWidthPx
            val peakH = toHeight + extraHeightPx
            Sample(
                width = peakW + (toWidth - peakW) * t,
                height = peakH + (toHeight - peakH) * t,
                contentScale = 1.1f + (1f - 1.1f) * t,
                contentBlurDp = 0f,
                done = false,
            )
        }
    }

    companion object {
        /** 原生主插值器 (0.3,0,0.1,1) 的三次贝塞尔近似（纯 JVM，二分求 t）。 */
        fun ease(x: Float): Float {
            val cx = x.coerceIn(0f, 1f)
            return cubicBezierY(cx, 0.3f, 0f, 0.1f, 1f)
        }

        private fun cubicBezierY(x: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            var lo = 0f
            var hi = 1f
            var t = x
            repeat(24) {
                val bx = bezier(t, x1, x2)
                if (bx < x) lo = t else hi = t
                t = (lo + hi) / 2f
            }
            return bezier(t, y1, y2)
        }

        private fun bezier(t: Float, p1: Float, p2: Float): Float {
            val u = 1f - t
            return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
        }
    }
}

/** 内容切换交叉淡化（200ms，(0.3,0,0.1,1)）：旧内容 alpha=1-t、新内容 alpha=t。 */
class SeedlingCrossfade(val durationMs: Long = 200L) {
    data class Sample(val fromAlpha: Float, val toAlpha: Float, val done: Boolean)

    fun sample(elapsedMs: Long): Sample {
        if (elapsedMs >= durationMs) return Sample(0f, 1f, true)
        val t = SeedlingOvershootEnvelope.ease(elapsedMs.toFloat() / durationMs)
        return Sample(1f - t, t, false)
    }
}