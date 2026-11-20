package com.coloros16.liquidglass.engine

/**
 * 世界层圆心（镜片中心 / 照片展开起点）跟随时钟有效位。
 *
 * 时钟有效位 = 样式锚点 + 用户编辑配置；圆心必须取有效位而非原始样式锚点，
 * 否则用户挪动时钟后时钟走了圆心还在老地方。
 */
object WorldCenterPolicy {

    /** 该样式的照片圆心是否跟随时钟：留光款有自己的构图，不跟。 */
    fun photoFollowsClock(styleId: Int): Boolean = styleId != 1

    /**
     * 求圆心：跟随则取时钟有效位，不跟随则留样式锚点。
     *
     * 有效位为 NaN 视为未就绪，回落样式锚点，避免把圆心甩到屏幕角。
     */
    fun center(
        follows: Boolean,
        styleX: Float,
        styleY: Float,
        clockX: Float,
        clockY: Float,
    ): Pair<Float, Float> {
        if (!follows || clockX.isNaN() || clockY.isNaN()) return styleX to styleY
        return clockX to clockY
    }
}