package com.coloros16.liquidglass.engine

/**
 * 流体云接管 kind 显示规则求值（纯 JVM，零 android.*，App 预览与 hook 共用）。
 *
 * 默认表取自参考默认配置表（12 kind 序号映射 of_key:196-223 + 8 布尔默认 + 显示规则 9 项）。
 * 内容源是系统流体云事件，kind 字段识别待真机取证；本求值器「有则控、无则空转不炸」——
 * 未知 kind 序号回落 [SeedlingKind.UNKNOWN]，显示规则仍生效，不硬造内容。
 */
enum class SeedlingKind {
    FACE_UNLOCK, CALL, RECORDING, STATUS, TIMER, LIVE_UPDATE, MEDIA, MESSAGE, CODE, UNKNOWN;

    /** 默认优先级（参考实现 activity/v.java:29-52：face100 call90 recording80 status20 timer60 live55 media50 message40 code66）。 */
    val defaultPriority: Int
        get() = when (this) {
            FACE_UNLOCK -> 100
            CALL -> 90
            RECORDING -> 80
            STATUS -> 20
            TIMER -> 60
            LIVE_UPDATE -> 55
            MEDIA -> 50
            MESSAGE -> 40
            CODE -> 66
            UNKNOWN -> 30
        }

    /** 独占 kind（通话/人脸抢占主岛）。 */
    val exclusive: Boolean
        get() = this == FACE_UNLOCK || this == CALL

    companion object {
        /** 参考序号映射（0=face_unlock 1=call 2=recording 7/8/10=status 3=timer 4/9=live_update 5=media 6=message 11=code）。 */
        fun ofOrdinal(ordinal: Int): SeedlingKind = when (ordinal) {
            0 -> FACE_UNLOCK
            1 -> CALL
            2 -> RECORDING
            7, 8, 10 -> STATUS
            3 -> TIMER
            4, 9 -> LIVE_UPDATE
            5 -> MEDIA
            6 -> MESSAGE
            11 -> CODE
            else -> UNKNOWN
        }
    }
}

/** kind 级开关（8 项，默认照参考实现；UNKNOWN 无独立键，由显示规则与总闸决定）。 */
data class SeedlingKindSwitches(
    val media: Boolean = true,
    val message: Boolean = false,
    val call: Boolean = true,
    val timer: Boolean = true,
    val status: Boolean = true,
    val faceUnlock: Boolean = false,
    val liveUpdate: Boolean = true,
    val code: Boolean = false,
) {
    fun allows(kind: SeedlingKind): Boolean = when (kind) {
        SeedlingKind.MEDIA -> media
        SeedlingKind.MESSAGE -> message
        SeedlingKind.CALL -> call
        SeedlingKind.TIMER -> timer
        SeedlingKind.RECORDING -> status
        SeedlingKind.STATUS -> status
        SeedlingKind.FACE_UNLOCK -> faceUnlock
        SeedlingKind.LIVE_UPDATE -> liveUpdate
        SeedlingKind.CODE -> code
        SeedlingKind.UNKNOWN -> true
    }
}

/** 显示规则（9 项，默认照参考默认配置表 with_key 组）。 */
data class SeedlingDisplayRules(
    val showOnLockscreen: Boolean = true,
    val showOnAod: Boolean = false,
    val showInLandscape: Boolean = true,
    val hideOnShade: Boolean = true,
    val hideInSourceApp: Boolean = true,
    val satellites: Boolean = true,
    val statusBarAvoid: Boolean = true,
    val cardSource: Boolean = false,
    val cardSwipeFloating: Boolean = true,
)

/** 当前场景状态（由调用方探针提供，求值器不碰 Android）。 */
data class SeedlingSceneState(
    val onLockscreen: Boolean = false,
    val onAod: Boolean = false,
    val landscape: Boolean = false,
    val shadeExpanded: Boolean = false,
    val inSourceApp: Boolean = false,
)

object SeedlingKindPolicy {

    /**
     * 岛内容是否应显示：总闸 → kind 开关 → 显示规则逐条。
     * 任一规则不满足即隐藏（返回 false）；UNKNOWN kind 跳过 kind 开关仍受显示规则约束。
     */
    fun shouldShow(
        kind: SeedlingKind,
        switches: SeedlingKindSwitches,
        rules: SeedlingDisplayRules,
        scene: SeedlingSceneState,
        masterEnabled: Boolean = true,
    ): Boolean {
        if (!masterEnabled) return false
        if (!switches.allows(kind)) return false
        if (scene.onAod && !rules.showOnAod) return false
        if (scene.onLockscreen && !rules.showOnLockscreen) return false
        if (scene.landscape && !rules.showInLandscape) return false
        if (scene.shadeExpanded && rules.hideOnShade) return false
        if (scene.inSourceApp && rules.hideInSourceApp) return false
        return true
    }

    /** 副岛是否显示：显示规则 satellites 开 + 岛本体应显示。 */
    fun shouldShowSatellites(
        kind: SeedlingKind,
        switches: SeedlingKindSwitches,
        rules: SeedlingDisplayRules,
        scene: SeedlingSceneState,
        masterEnabled: Boolean = true,
    ): Boolean = rules.satellites && shouldShow(kind, switches, rules, scene, masterEnabled)
}