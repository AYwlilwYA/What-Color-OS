package com.coloros16.liquidglass.engine

/** 三态在场景时间轴上的位置：息屏 0 / 锁屏 0.5 / 桌面 1。 */
object SceneTimeline {

    const val AOD = 0f
    const val LOCK = 0.5f
    const val HOME = 1f

    fun anchor(state: SceneState): Float = when (state) {
        SceneState.AOD -> AOD
        SceneState.LOCK -> LOCK
        SceneState.HOME -> HOME
    }

    /** 边 + 进度 → 场景时间；静态态直接取锚点。 */
    fun uOf(
        edge: TransitionEdge?,
        t: Float,
        state: SceneState,
        easing: Easing = Easing.STANDARD,
    ): Float {
        if (edge == null) return anchor(state)
        val e = easing.map(t.coerceIn(0f, 1f))
        return when (edge) {
            TransitionEdge.AOD_TO_LOCK -> 0.5f * e
            TransitionEdge.LOCK_TO_HOME -> 0.5f + 0.5f * e
            TransitionEdge.AOD_TO_HOME -> e
            TransitionEdge.LOCK_TO_AOD -> 0.5f * (1f - e)
            TransitionEdge.HOME_TO_LOCK -> 0.5f + 0.5f * (1f - e)
            TransitionEdge.HOME_TO_AOD -> 1f - e
        }
    }
}
