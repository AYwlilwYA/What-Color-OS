package com.coloros16.liquidglass.engine

/**
 * 过期引擎（纯 JVM）：算所有活动最近一个到期时刻（staleAt / 消息降级点），
 * 调用方按差值排一次性延迟重算——事件驱动闹钟，无轮询。
 */
object SeedlingExpiry {

    /**
     * 最近到期时刻（elapsedRealtime 域）；null=无未来到期。
     * 只收 >now 的时刻（已过期活动由 layout 评分即时降级，不需闹钟）。
     */
    fun nextDeadlineMs(activities: Collection<SeedlingActivity>, nowElapsedMs: Long): Long? {
        var min = Long.MAX_VALUE
        for (a in activities) {
            if (a.staleAtElapsedMs > nowElapsedMs) {
                min = minOf(min, a.staleAtElapsedMs)
            }
            val demote = a.demoteAfterMs
            if (demote != null && a.updatedAtElapsedMs > 0) {
                val demoteAt = a.updatedAtElapsedMs + demote
                if (demoteAt > nowElapsedMs) min = minOf(min, demoteAt)
            }
        }
        return if (min == Long.MAX_VALUE) null else min
    }

    /** 距最近到期的延迟（钳 ≥0）+20ms 余量（参考 postDelayed(delta+20) 真值）；null=不排。 */
    fun scheduleDelayMs(activities: Collection<SeedlingActivity>, nowElapsedMs: Long): Long? {
        val deadline = nextDeadlineMs(activities, nowElapsedMs) ?: return null
        return (deadline - nowElapsedMs).coerceAtLeast(0L) + 20L
    }
}
