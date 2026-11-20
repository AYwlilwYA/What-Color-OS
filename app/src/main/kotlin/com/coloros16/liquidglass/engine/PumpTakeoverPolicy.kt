package com.coloros16.liquidglass.engine

/**
 * 系统进度接管：区分「系统已宣告这条边」与「系统正在逐帧喂这条边」。
 *
 * 宿主对一部分边只发状态、不发中间帧，但状态与有帧的边走同一个汇聚点，
 * 单看来源分不出来。用「两次写入之间进度有没有真的动过」当见证：
 * 动过 = 有帧，该让系统主导；始终不动 = 无帧，内部时间轴必须接管。
 */
object PumpTakeoverPolicy {

    /** 进度是否真的推进了（NaN 与相等都算没动）。 */
    fun advanced(previous: Float, next: Float): Boolean =
        !previous.isNaN() && !next.isNaN() && next != previous

    /** 落到开区间内的写入才算「逐帧喂」，端点直跳（0→1）只算宣告完成。 */
    fun interior(previous: Float, next: Float): Boolean =
        advanced(previous, next) && next > 0f && next < 1f

    /**
     * 内部时间轴是否该接管这条边。
     *
     * [sawMovement] = 本次宣告以来是否见过进度推进；没见过就接管。
     * 非系统驱动的边一律接管。
     */
    fun shouldSelfTime(
        edge: TransitionEdge,
        sourceIsSystem: Boolean,
        sawMovement: Boolean,
    ): Boolean = !sourceIsSystem || !PumpSourcePolicy.systemDriven(edge) || !sawMovement
}