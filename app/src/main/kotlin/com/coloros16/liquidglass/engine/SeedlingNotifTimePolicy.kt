package com.coloros16.liquidglass.engine

/**
 * 通知计时/进度数据换算（纯 JVM）： chronometer 基准（墙钟 ms）→ elapsedRealtime 基准，
 * 倒计时 endAt / 正计时 base；通知 progress → 分数。供通知路上岛活动走时与进度条。
 */
object SeedlingNotifTimePolicy {

    /**
     * 墙钟时刻（Notification.when，System.currentTimeMillis 基准）→ elapsedRealtime 基准。
     * @param whenWallMs 通知 when（墙钟 ms）
     * @param nowWallMs 当前墙钟 ms
     * @param nowElapsedMs 当前 elapsedRealtime ms
     */
    fun wallToElapsed(whenWallMs: Long, nowWallMs: Long, nowElapsedMs: Long): Long =
        nowElapsedMs - (nowWallMs - whenWallMs)

    /**
     * 倒计时结束时刻（elapsed 基准）：chronometer 倒计时 when 即目标时刻。
     * 返回 <=0 表示未配置/已过期不可用（调用方据此决定是否走时）。
     */
    fun countdownEndElapsed(whenWallMs: Long, nowWallMs: Long, nowElapsedMs: Long): Long {
        if (whenWallMs <= 0L) return 0L
        return wallToElapsed(whenWallMs, nowWallMs, nowElapsedMs)
    }

    /** 正计时基准（elapsed 基准）：chronometer 正计时 when 即起点。 */
    fun countUpBaseElapsed(whenWallMs: Long, nowWallMs: Long, nowElapsedMs: Long): Long {
        if (whenWallMs <= 0L) return 0L
        return wallToElapsed(whenWallMs, nowWallMs, nowElapsedMs)
    }

    /** 通知 progress 分数：max<=0 或 indeterminate → null（不画进度条）。 */
    fun progressFraction(progress: Int, max: Int, indeterminate: Boolean): Float? {
        if (indeterminate || max <= 0) return null
        return (progress.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }
}
