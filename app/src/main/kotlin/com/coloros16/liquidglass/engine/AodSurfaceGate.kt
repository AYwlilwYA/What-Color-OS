package com.coloros16.liquidglass.engine

/**
 * 三态舞台的分面门控：总闸 ∧ 衔接键 ∧ 该面开关。
 *
 * 息屏面读熄屏显示键、锁屏面读锁屏显示键；任一层为假该面不接管。
 * 纯求值，读键与缓存由调用方负责。
 */
object AodSurfaceGate {

    /** 全局层：总开关与息屏衔接开关，两者都为真才可能有任一面上岗。 */
    fun global(master: Boolean, handoff: Boolean): Boolean = master && handoff

    /** 分面层：全局为真且该面开关为真；桌面在另一进程无容器，本进程恒不接管。 */
    fun surface(globalEnabled: Boolean, surface: SceneState, screenOn: Boolean, lockShowOn: Boolean): Boolean {
        if (!globalEnabled) return false
        return when (surface) {
            SceneState.AOD -> screenOn
            SceneState.LOCK -> lockShowOn
            SceneState.HOME -> false
        }
    }

    /**
     * 转场面：只看本进程持有的那一面。
     *
     * 边两端只要有一端是本进程的面且该面开着就放行；
     * 另一端在别的进程（桌面）或已关，由那一侧自己决定，不在此处连坐。
     */
    fun edge(
        globalEnabled: Boolean,
        edge: TransitionEdge,
        screenOn: Boolean,
        lockShowOn: Boolean,
    ): Boolean {
        if (!globalEnabled) return false
        val a = surface(globalEnabled, edge.from, screenOn, lockShowOn)
        val b = surface(globalEnabled, edge.to, screenOn, lockShowOn)
        return a || b
    }

    /** 门控状态快照：用于只在真变时打日志。 */
    fun fingerprint(globalEnabled: Boolean, screenOn: Boolean, lockShowOn: Boolean): Int =
        (if (globalEnabled) 1 else 0) or (if (screenOn) 2 else 0) or (if (lockShowOn) 4 else 0)
}