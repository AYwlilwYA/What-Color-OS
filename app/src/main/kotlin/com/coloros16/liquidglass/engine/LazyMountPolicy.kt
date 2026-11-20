package com.coloros16.liquidglass.engine

/**
 * 锁屏面惰性挂载：只有「不含息屏」的边才需要另找舞台。
 *
 * 含 AOD 的边由息屏根承载；锁屏↔桌面两条边在息屏根摘掉后没有舞台，
 * 必须挂到窗口根上，否则效果一个都不跑。
 */
object LazyMountPolicy {

    fun needsLockStage(edge: TransitionEdge?): Boolean {
        if (edge == null) return false
        return edge.from != SceneState.AOD && edge.to != SceneState.AOD
    }
}
