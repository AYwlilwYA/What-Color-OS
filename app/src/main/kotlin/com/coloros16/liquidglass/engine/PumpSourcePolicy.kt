package com.coloros16.liquidglass.engine

/**
 * 进度源归属：判断某条边是否由系统逐帧喂进度，据此决定内部时间轴是否接管。
 *
 * 系统对一部分边只发状态不发中间帧，此时桥里会留着该边的终值；
 * 若把终值当成「系统正在喂」，内部时间轴永远不接管，该边整段动画静默丢失。
 */
object PumpSourcePolicy {

    /** 系统发中间帧的边：两端含息屏态的三条有向边。 */
    fun systemDriven(edge: TransitionEdge): Boolean =
        edge.from == SceneState.AOD || edge.to == SceneState.AOD

    /**
     * 系统是否真的在喂这条边：既要是系统驱动的边，也要有非终值进度。
     *
     * 终值（≥1）说明这条边已经走完，不是「正在喂」。
     */
    fun systemFeeding(edge: TransitionEdge, t: Float): Boolean =
        systemDriven(edge) && !t.isNaN() && t < 1f

    /**
     * 内部时间轴是否应接管：系统没在喂就接管。
     *
     * 同一条边已在播放时不重复起播（由调用方的时钟状态判定，此处只判源）。
     */
    fun shouldSelfTime(edge: TransitionEdge, sourceIsSystem: Boolean, t: Float): Boolean =
        !sourceIsSystem || !systemFeeding(edge, t)
}