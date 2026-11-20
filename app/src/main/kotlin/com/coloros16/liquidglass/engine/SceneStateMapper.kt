package com.coloros16.liquidglass.engine

/**
 * 系统转场状态名 → 三态。名称来自宿主枚举的 toString，大小写不敏感、按子串判定。
 *
 * 宿主 `KeyguardState` 用 `GONE` 表示「锁屏界面不在」：作为终点是桌面，
 * 作为起点则桌面与「锁屏被熄屏取代」都会出现，必须由调用方用真实态兜底。
 */
object SceneStateMapper {

    private val AOD_TOKENS = listOf("DOZ", "AOD", "DREAM")
    private val HOME_TOKENS = listOf("UNLOCK", "HOME", "LAUNCHER", "GONE")
    private val LOCK_TOKENS = listOf("LOCK", "OCCLUD", "BOUNCER")

    /** 只表示「锁屏界面不在」的名字：桌面上与「锁屏被熄屏取代」时都会出现，不能定起点。 */
    private val AMBIGUOUS_START_TOKENS = listOf("GONE")

    /** 无法判定返回 null（由调用方记日志、不猜边）。 */
    fun fromTransitionStateName(raw: String?): SceneState? {
        if (raw.isNullOrBlank()) return null
        val name = raw.uppercase()
        if (HOME_TOKENS.any { name.contains(it) }) return SceneState.HOME
        if (AOD_TOKENS.any { name.contains(it) }) return SceneState.AOD
        if (LOCK_TOKENS.any { name.contains(it) }) return SceneState.LOCK
        return null
    }

    /** 起点名是否只说明「锁屏界面不在」，不足以判定 HOME 还是 LOCK。 */
    fun isAmbiguousStart(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val name = raw.uppercase()
        return AMBIGUOUS_START_TOKENS.any { name.contains(it) }
    }

    /** 起点专用：不具判定力的名字返回 null，交给调用方用真实态兜底。 */
    fun startingStateOf(raw: String?): SceneState? {
        if (isAmbiguousStart(raw)) return null
        return fromTransitionStateName(raw)
    }

    /**
     * 两端映射到同一态 ⇒ 这是状态内的子步骤（如锁屏→密码盘），不是未识别边。
     *
     * 与 [resolveEdge] 返回 null 的另一种情形（名字认不出）必须分开：前者是正常流程，
     * 后者才是需要校准的未知边；混在一起会让人以为宿主换了枚举。
     */
    fun isSameScene(fromName: String?, toName: String?): Boolean {
        val from = startingStateOf(fromName) ?: return false
        val to = fromTransitionStateName(toName) ?: return false
        return from == to
    }

    /**
     * 起点名不具判定力时用上一次确定的真实态兜底。
     *
     * 作为**终点**时 `GONE` 仍是桌面（锁屏界面已消失）；只有作为起点才歧义。
     * 兜底态与终点同态 ⇒ 没有可播的边，返回 null（不造自环）；兜底为空同样返回 null（不猜）。
     */
    fun resolveEdge(
        fromName: String?,
        toName: String?,
        fallbackFrom: SceneState? = null,
    ): TransitionEdge? {
        val to = fromTransitionStateName(toName) ?: return null
        val direct = startingStateOf(fromName)
        val from = when {
            direct != null -> direct
            isAmbiguousStart(fromName) -> fallbackFrom?.takeIf { it != to } ?: return null
            else -> return null
        }
        return transitionEdgeOf(from, to)
    }
}
