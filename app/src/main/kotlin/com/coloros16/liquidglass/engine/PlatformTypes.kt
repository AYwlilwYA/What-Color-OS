package com.coloros16.liquidglass.engine

/** 三态两两切换的六条有向边；息屏↔桌面可直连，也可内部分段映射。 */
enum class TransitionEdge {
    AOD_TO_LOCK,
    LOCK_TO_AOD,
    LOCK_TO_HOME,
    HOME_TO_LOCK,
    AOD_TO_HOME,
    HOME_TO_AOD,
}

/** 三态：息屏 / 锁屏 / 桌面。 */
enum class SceneState {
    AOD,
    LOCK,
    HOME,
}

val TransitionEdge.from: SceneState
    get() = when (this) {
        TransitionEdge.AOD_TO_LOCK, TransitionEdge.AOD_TO_HOME -> SceneState.AOD
        TransitionEdge.LOCK_TO_AOD, TransitionEdge.LOCK_TO_HOME -> SceneState.LOCK
        TransitionEdge.HOME_TO_LOCK, TransitionEdge.HOME_TO_AOD -> SceneState.HOME
    }

val TransitionEdge.to: SceneState
    get() = when (this) {
        TransitionEdge.AOD_TO_LOCK, TransitionEdge.HOME_TO_LOCK -> SceneState.LOCK
        TransitionEdge.LOCK_TO_AOD, TransitionEdge.HOME_TO_AOD -> SceneState.AOD
        TransitionEdge.LOCK_TO_HOME, TransitionEdge.AOD_TO_HOME -> SceneState.HOME
    }

/** 任意两态取边；同态返回 null。 */
fun transitionEdgeOf(from: SceneState, to: SceneState): TransitionEdge? = when {
    from == to -> null
    from == SceneState.AOD && to == SceneState.LOCK -> TransitionEdge.AOD_TO_LOCK
    from == SceneState.LOCK && to == SceneState.AOD -> TransitionEdge.LOCK_TO_AOD
    from == SceneState.LOCK && to == SceneState.HOME -> TransitionEdge.LOCK_TO_HOME
    from == SceneState.HOME && to == SceneState.LOCK -> TransitionEdge.HOME_TO_LOCK
    from == SceneState.AOD && to == SceneState.HOME -> TransitionEdge.AOD_TO_HOME
    else -> TransitionEdge.HOME_TO_AOD
}

/** 进度来源：系统进度泵 / 预览时间轴 / 手动滑杆。 */
enum class ClockSource {
    SYSTEM_PUMP,
    /** 宿主无单一生进度时，由本模块按帧推进的时间轴。 */
    INTERNAL_TIMELINE,
    PREVIEW,
    MANUAL,
}

/** 进度快照：同一求值器的唯一输入。 */
data class ClockState(
    val edge: TransitionEdge?,
    val source: ClockSource,
    val t: Float,
    val playing: Boolean,
    val revision: Long,
)

/** 能力探测三态。 */
enum class CapabilityState {
    OK,
    DEGRADED,
    UNSUPPORTED,
}

/** 能力探测结果：状态 + 原因码 + 文案键。 */
data class CapabilityResult(
    val state: CapabilityState,
    val reasonCode: String? = null,
    val textKey: String? = null,
)

/** 对外能力报告。 */
data class CapabilityReport(
    val id: String,
    val state: CapabilityState,
    val reasonCode: String?,
    val textKey: String?,
)

/** 熔断状态。 */
enum class FuseState {
    CLOSED,
    TRIPPED,
}

/** 对外熔断报告。 */
data class FuseReport(
    val domain: String,
    val state: FuseState,
    val failures: Int,
    val lastFailureAtMs: Long,
    val lastRollbackAtMs: Long,
    val reason: String?,
)

/** 生效时机：由 hook 侧逐项声明，UI 只显示。 */
enum class EffectTiming {
    NOW,
    NEXT_DOZE,
    RESTART_SYSUI,
}

/** 平台状态总快照（事件推送，不轮询）。 */
data class PlatformSnapshot(
    val revision: Long,
    val clock: ClockState,
    val capabilities: List<CapabilityReport>,
    val fuses: List<FuseReport>,
)
