package com.coloros16.liquidglass.engine

/** 系统转场状态名 → 三态。名称来自宿主枚举的 toString，大小写不敏感、按子串判定。 */
object SceneStateMapper {

    private val AOD_TOKENS = listOf("DOZ", "AOD", "DREAM")
    private val HOME_TOKENS = listOf("UNLOCK", "HOME", "LAUNCHER")
    private val LOCK_TOKENS = listOf("LOCK", "OCCLUD", "BOUNCER")

    /** 无法判定返回 null（由调用方记日志、不猜边）。 */
    fun fromTransitionStateName(raw: String?): SceneState? {
        if (raw.isNullOrBlank()) return null
        val name = raw.uppercase()
        if (HOME_TOKENS.any { name.contains(it) }) return SceneState.HOME
        if (AOD_TOKENS.any { name.contains(it) }) return SceneState.AOD
        if (LOCK_TOKENS.any { name.contains(it) }) return SceneState.LOCK
        return null
    }

    fun resolveEdge(fromName: String?, toName: String?): TransitionEdge? {
        val from = fromTransitionStateName(fromName) ?: return null
        val to = fromTransitionStateName(toName) ?: return null
        return transitionEdgeOf(from, to)
    }
}
