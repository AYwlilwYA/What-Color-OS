package com.coloros16.liquidglass.engine

/**
 * 转场起手停顿：从息屏出发的边先停一拍再播，与参考实现同值。
 *
 * 停顿只推迟起播时刻，不占播放时长，故各通道 fraction 不被稀释。
 */
object TransitionStartDelay {

    /** 从息屏展开的起手停顿（毫秒）。 */
    const val FROM_AOD_MS = 150L

    /** 其余边不停顿。 */
    const val NONE_MS = 0L

    /**
     * 该边该不该停顿：只看起点是不是息屏。
     *
     * 参考实现判的是「上一态是息屏且本次没被打断」；被打断的情形由调用方
     * 用 [forRestart] 传入是否续播来区分。
     */
    fun forEdge(edge: TransitionEdge): Long =
        if (edge.from == SceneState.AOD) FROM_AOD_MS else NONE_MS

    /**
     * 续播（上一次动画仍在播时换目标）不停顿：参考实现 interrupted 时跳过 setStartDelay。
     */
    fun forRestart(edge: TransitionEdge, interrupted: Boolean): Long =
        if (interrupted) NONE_MS else forEdge(edge)
}
