package com.coloros16.liquidglass.engine

/**
 * 边沿事件去抖：同一次解锁的多个触发点共用一个时刻，紧随其后的反向边视为同一次。
 *
 * 纯求值，时间由调用方传入。
 */
object EdgeDebounce {

    /** 该反向边是否应被吞掉（距上次解锁太近）。 */
    fun suppress(nowMs: Long, lastUnlockMs: Long, windowMs: Long): Boolean {
        if (lastUnlockMs <= 0L) return false
        if (windowMs <= 0L) return false
        return nowMs - lastUnlockMs < windowMs
    }

    /** 计数自增：每个触发点各自记账，不共用。 */
    fun bump(count: Int): Int = count + 1

    /**
     * 触发点来源标签：诊断日志要能分清是哪个入口打来的。
     *
     * 两个入口共用一个计数器会让日志说谎。
     */
    fun sourceTag(hideHits: Int, exitHits: Int, fromExit: Boolean): String =
        if (fromExit) "exit=$exitHits hide=$hideHits" else "hide=$hideHits exit=$exitHits"
}