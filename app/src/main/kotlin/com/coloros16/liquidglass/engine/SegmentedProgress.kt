package com.coloros16.liquidglass.engine

/** 分段采样结果（复用实例）。 */
class SegmentSample {
    var edge: TransitionEdge? = null
    var t: Float = 0f
}

/**
 * 单边进度 → 分段边进度：宿主只给「息屏↔锁屏」「锁屏↔桌面」两段时，
 * 直连边（息屏↔桌面）在这里映射成一条连续 t，外部只看到一条边。
 */
class SegmentedProgress(private val split: Float = 0.5f) {

    private val pivot = split.coerceIn(0.01f, 0.99f)

    fun sample(edge: TransitionEdge, t: Float, out: SegmentSample): SegmentSample {
        val progress = if (t.isNaN()) 0f else t.coerceIn(0f, 1f)
        out.t = progress
        out.edge = edge
        when (edge) {
            TransitionEdge.AOD_TO_HOME -> {
                if (progress < pivot) {
                    out.edge = TransitionEdge.AOD_TO_LOCK
                    out.t = progress / pivot
                } else {
                    out.edge = TransitionEdge.LOCK_TO_HOME
                    out.t = (progress - pivot) / (1f - pivot)
                }
            }
            TransitionEdge.HOME_TO_AOD -> {
                if (progress < pivot) {
                    out.edge = TransitionEdge.HOME_TO_LOCK
                    out.t = progress / pivot
                } else {
                    out.edge = TransitionEdge.LOCK_TO_AOD
                    out.t = (progress - pivot) / (1f - pivot)
                }
            }
            else -> Unit
        }
        return out
    }
}
