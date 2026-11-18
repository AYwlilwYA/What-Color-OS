package com.coloros16.liquidglass.engine

/**
 * 防烧屏漂移的下次唤醒时刻：给定当前时刻，算出下一次取值会变化的时间点。
 *
 * 事件驱动调度用（一次算一个时刻，到点再算下一个），不是周期轮询。
 * 纯 JVM，可单测。
 */
object AodDriftSchedule {

    /** 一个完整周期的步数。 */
    const val STEP_COUNT = 6

    /** 周期总长。 */
    val CYCLE_MS: Long = AodDrift.STEP_MS * STEP_COUNT

    /**
     * 下次取值可能变化的时刻（绝对毫秒）。
     *
     * 过渡期内 → 过渡结束时刻；停留期内 → 下一步起点。
     * 返回值恒大于 [nowMs]，调用方可直接用于单次延迟调度。
     */
    fun nextChangeAt(nowMs: Long): Long {
        val phase = Math.floorMod(nowMs, AodDrift.STEP_MS)
        val remain = if (phase < AodDrift.GLIDE_MS) {
            AodDrift.GLIDE_MS - phase
        } else {
            AodDrift.STEP_MS - phase
        }
        return nowMs + remain
    }

    /** 距下次变化的毫秒数（恒正）。 */
    fun delayToNext(nowMs: Long): Long = nextChangeAt(nowMs) - nowMs

    /**
     * 当前是否处于过渡期（取值正在变）。
     *
     * 过渡期需要逐帧跟随，停留期完全静止不需要任何回调。
     */
    fun gliding(nowMs: Long): Boolean =
        Math.floorMod(nowMs, AodDrift.STEP_MS) < AodDrift.GLIDE_MS

    /** 本次过渡还剩多少毫秒；不在过渡期返回 0。 */
    fun glideRemainMs(nowMs: Long): Long {
        val phase = Math.floorMod(nowMs, AodDrift.STEP_MS)
        return if (phase < AodDrift.GLIDE_MS) AodDrift.GLIDE_MS - phase else 0L
    }
}