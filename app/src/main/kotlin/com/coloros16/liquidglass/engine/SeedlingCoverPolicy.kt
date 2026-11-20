package com.coloros16.liquidglass.engine

/**
 * 媒体封面策略（纯 JVM）：URI scheme 白名单、采样倍数翻倍到 ≤512px、
 * 切歌防抖判定（同会话换曲且新封面未到 → 最多等 420ms 再发布）。
 */
object SeedlingCoverPolicy {

    private val ALLOWED_SCHEMES = setOf("content", "android.resource", "file")

    fun schemeAllowed(scheme: String?): Boolean =
        scheme != null && scheme.lowercase() in ALLOWED_SCHEMES

    /** 采样倍数：从 1 翻倍，首个使 max(w,h)/(2×sample)<512 的倍数即停（参考解码器逐位一致）。 */
    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int = 512): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    /**
     * 切歌防抖：同会话换曲、新封面未到、旧封面存在、距换曲 <400ms → 返回应再等的毫秒数；
     * 否则 0（立即发布）。上限 420ms。
     */
    fun coverDebounceMs(
        sameSession: Boolean,
        trackChanged: Boolean,
        newCoverPresent: Boolean,
        oldCoverPresent: Boolean,
        elapsedSinceChangeMs: Long,
    ): Long {
        if (!sameSession || !trackChanged || newCoverPresent || !oldCoverPresent) return 0L
        if (elapsedSinceChangeMs >= 400L) return 0L
        return (420L - elapsedSinceChangeMs).coerceIn(0L, 420L)
    }
}

/**
 * 胶囊长文字滚动求值（纯 JVM）：文字宽超出视口才滚，单向循环 28dp/s，
 * 两端各停 1s；未超宽恒 0（不滚）。
 */
class SeedlingLaneScroll(
    val textWidthPx: Float,
    val viewportWidthPx: Float,
    val speedPxPerSec: Float,
    val endPauseMs: Long = 1000L,
) {
    val scrollable: Boolean get() = textWidthPx > viewportWidthPx && speedPxPerSec > 0f

    private val travelPx: Float get() = (textWidthPx - viewportWidthPx).coerceAtLeast(0f)
    private val travelMs: Long get() = if (speedPxPerSec > 0f) (travelPx / speedPxPerSec * 1000f).toLong() else 0L
    val cycleMs: Long get() = endPauseMs + travelMs + endPauseMs

    /** 周期内偏移量（0..travelPx）；不可滚返回 0。 */
    fun offsetAt(elapsedMs: Long): Float {
        if (!scrollable) return 0f
        val t = elapsedMs % cycleMs
        return when {
            t < endPauseMs -> 0f
            t < endPauseMs + travelMs -> travelPx * (t - endPauseMs) / travelMs.toFloat()
            else -> travelPx
        }
    }
}
