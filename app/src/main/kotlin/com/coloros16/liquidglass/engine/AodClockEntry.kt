package com.coloros16.liquidglass.engine

/**
 * 息屏时钟进场的独立时长：ROM 里它与蒙版动画各自计时，且比灭屏边更长。
 *
 * 边结束不代表时钟走完；本件把「边进度」换算成「时钟自己的进度」，
 * 并给出边结束后还需补多少毫秒。纯 JVM，预览与 hook 共用。
 */
object AodClockEntry {

    /** 该边是否有时钟进场（终点为息屏的两条边）。 */
    fun entering(edge: TransitionEdge): Boolean = edge.to == SceneState.AOD

    /**
     * 时钟自己的进度：边长不足时钟时长时按比例放慢，边更长时随时钟先走完。
     *
     * [edgeT] 为边进度 0..1，[edgeMs] 为该边总时长。
     */
    fun progress(edgeT: Float, edgeMs: Long): Float {
        if (edgeMs <= 0L) return 1f
        val span = SystemSceneMotion.AOD_CLOCK_IN_MS / edgeMs.toFloat()
        return (edgeT.coerceIn(0f, 1f) / span).coerceIn(0f, 1f)
    }

    /** 缩放：起始值到 1，按 [SystemSceneMotion.AOD_CLOCK] 曲线。 */
    fun scaleAt(progress: Float): Float {
        val e = SystemSceneMotion.AOD_CLOCK.map(progress.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        return SystemSceneMotion.AOD_CLOCK_IN_SCALE +
            (1f - SystemSceneMotion.AOD_CLOCK_IN_SCALE) * e
    }

    /** 边结束后时钟还差多少毫秒；边更长或已走完时为 0。 */
    fun tailMs(edgeMs: Long): Long {
        val need = SystemSceneMotion.AOD_CLOCK_IN_MS.toLong() - edgeMs
        return if (need > 0L) need else 0L
    }

    /**
     * 补尾期的时钟进度：[elapsedMs] 为边结束后经过的毫秒。
     *
     * 起点接边末进度，保证不跳变。
     */
    fun tailProgress(elapsedMs: Long, edgeMs: Long): Float {
        val tail = tailMs(edgeMs)
        if (tail <= 0L) return 1f
        val atEdgeEnd = progress(1f, edgeMs)
        val span = SystemSceneMotion.AOD_CLOCK_IN_MS
        val extra = elapsedMs.coerceAtLeast(0L).toFloat() / span
        return (atEdgeEnd + extra).coerceIn(0f, 1f)
    }

    /** 补尾是否已结束。 */
    fun tailDone(elapsedMs: Long, edgeMs: Long): Boolean =
        elapsedMs >= tailMs(edgeMs)

    /**
     * 进场系数按息屏权重混合：起点端仍是锁屏/桌面时钟，不该被息屏入场缩小。
     *
     * [aodWeight] 为当前姿态里息屏端的占比（0=纯锁屏/桌面，1=纯息屏）。
     * 权重走 [SystemSceneMotion.AOD_CLOCK] 同一条曲线，与入场系数同相，
     * 避免起步段线性权重压过缓入的姿态曲线导致字号先回缩。
     */
    fun blend(entryScale: Float, aodWeight: Float): Float {
        val w = SystemSceneMotion.AOD_CLOCK.map(aodWeight.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        return 1f + (entryScale - 1f) * w
    }
}