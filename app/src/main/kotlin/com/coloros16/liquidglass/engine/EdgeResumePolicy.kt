package com.coloros16.liquidglass.engine

/**
 * 补武装判定：宿主每次重挂/失效回调都会问一次「要不要补起帧回调」。
 *
 * 只在**系统边还在跑**时才需要补：边已落位（t≥1 且没在播）时补武装只会白起一帧，
 * 而息屏时钟每两秒就失效一次，不判就会反复起帧。
 */
object EdgeResumePolicy {

    fun needsResume(edge: TransitionEdge?, state: ClockState?): Boolean {
        if (edge == null) return false
        if (state == null) return true
        if (state.edge != edge) return true
        return state.playing || state.t < 1f
    }
}
