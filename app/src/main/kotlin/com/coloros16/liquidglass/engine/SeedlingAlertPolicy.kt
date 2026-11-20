package com.coloros16.liquidglass.engine

/**
 * 新活动弹卡判定（纯 JVM）：哪些活动到达时自动展开大卡。
 * 参考语义：alertOnStart（新增弹卡）/ alertOnUpdate（更新弹卡）+ 独占 kind 强弹；
 * 普通活动只上胶囊不弹卡（防打扰）。
 */
object SeedlingAlertPolicy {

    /** 新活动是否应自动展开大卡。 */
    fun shouldAutoExpand(
        activity: SeedlingActivity,
        isNewActivity: Boolean,
        previousMainId: String?,
    ): Boolean {
        if (!activity.expandable) return false
        // 独占 kind（通话/人脸）到达即弹
        if (activity.exclusive && isNewActivity) return true
        // 里程碑/高提醒（priority≥85）更新也弹
        if (activity.expanded.milestone || activity.priority >= 85) return true
        // 验证码到达即弹（看一眼就走）
        if (activity.kind == SeedlingKind.CODE && isNewActivity) return true
        return false
    }

    /** 弹卡保持时长：alertHoldMs 优先，否则默认 5000（expanded_auto_collapse_ms 真值）。 */
    fun holdMs(alertHoldMs: Long?): Long = alertHoldMs ?: 5000L
}

/**
 * 无操作隐藏计时（纯 JVM）：idle_hide 开启后 idleMs 无交互隐藏岛；
 * 任何交互（点按/展开/内容更新）重置计时。事件驱动一次性闹钟消费。
 */
class SeedlingIdleHide(val idleMs: Long = 180_000L, var enabled: Boolean = false) {

    private var lastInteractionMs = -1L

    fun touch(nowMs: Long) {
        lastInteractionMs = nowMs
    }

    /** 距隐藏还剩多少毫秒；null=不隐藏（未启用/未交互过）。 */
    fun dueInMs(nowMs: Long): Long? {
        if (!enabled || lastInteractionMs < 0) return null
        return (lastInteractionMs + idleMs - nowMs).coerceAtLeast(0L)
    }

    fun shouldHide(nowMs: Long): Boolean = dueInMs(nowMs) == 0L

    fun reset() {
        lastInteractionMs = -1L
    }
}
