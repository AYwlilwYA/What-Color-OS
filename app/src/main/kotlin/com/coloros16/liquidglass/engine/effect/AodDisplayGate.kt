package com.coloros16.liquidglass.engine.effect

/** 息屏显示门控结果：[visible] 是否显示整层，[animate] 是否允许动态，[dim] 整体淡出系数 0..1。 */
class AodGateDecision(
    val visible: Boolean,
    val animate: Boolean,
    val dim: Float,
    val reason: String,
)

/**
 * 息屏显示门控。
 *
 * - 全天：始终可见；
 * - 定时：只在窗口内可见（支持跨夜，start==end 视为全天）；
 * - 节能：无交互超过超时后整层消失（对齐「一段时间消失」，时长可调，默认 30s）。
 *
 * 「允许 AOD 动态变化」只影响 [AodGateDecision.animate]，不影响可见性。
 */
object AodDisplayGate {

    const val DEFAULT_POWER_SAVE_TIMEOUT_MS = 30_000L

    private const val MINUTES_PER_DAY = 1440
    private const val MS_PER_MINUTE = 60_000L

    fun decide(
        mode: AodDisplayMode,
        startMinute: Int,
        endMinute: Int,
        nowMinute: Int,
        idleMs: Long,
        powerSaveTimeoutMs: Long,
        allowDynamic: Boolean,
    ): AodGateDecision = when (mode) {
        AodDisplayMode.ALWAYS -> AodGateDecision(true, allowDynamic, 1f, "always")

        AodDisplayMode.SCHEDULED -> {
            val visible = inWindow(startMinute, endMinute, nowMinute)
            AodGateDecision(visible, allowDynamic && visible, if (visible) 1f else 0f, if (visible) "schedule-in" else "schedule-out")
        }

        AodDisplayMode.POWER_SAVE -> {
            val timeout = effectiveTimeout(powerSaveTimeoutMs)
            val safeIdle = if (idleMs < 0L) 0L else idleMs
            val visible = safeIdle < timeout
            AodGateDecision(visible, allowDynamic && visible, if (visible) 1f else 0f, if (visible) "power-save-on" else "power-save-timeout")
        }
    }

    /** 分钟窗口判定；start > end 表示跨零点，start == end 表示全天。 */
    fun inWindow(startMinute: Int, endMinute: Int, nowMinute: Int): Boolean {
        val start = normalizeMinute(startMinute)
        val end = normalizeMinute(endMinute)
        val now = normalizeMinute(nowMinute)
        if (start == end) return true
        return if (start < end) now in start until end else now >= start || now < end
    }

    /**
     * 距下次可见性翻转的毫秒数（恒正）；全天模式返回 -1 表示永不翻转。
     *
     * 定时 → 窗口另一端；节能且仍可见 → 超时剩余；节能已超时 → 无自然翻转点（靠交互事件）。
     * 调用方据此排**一次**延迟唤醒，到点再算下一个，不是周期轮询。
     */
    fun nextFlipDelayMs(
        mode: AodDisplayMode,
        startMinute: Int,
        endMinute: Int,
        nowMinute: Int,
        idleMs: Long,
        powerSaveTimeoutMs: Long,
    ): Long = when (mode) {
        AodDisplayMode.ALWAYS -> -1L

        AodDisplayMode.SCHEDULED -> {
            val start = normalizeMinute(startMinute)
            val end = normalizeMinute(endMinute)
            if (start == end) {
                -1L
            } else {
                val boundary = if (inWindow(start, end, nowMinute)) end else start
                val now = normalizeMinute(nowMinute)
                val minutes = Math.floorMod(boundary - now, MINUTES_PER_DAY)
                (if (minutes == 0) MINUTES_PER_DAY else minutes) * MS_PER_MINUTE
            }
        }

        AodDisplayMode.POWER_SAVE -> {
            val timeout = effectiveTimeout(powerSaveTimeoutMs)
            val safeIdle = if (idleMs < 0L) 0L else idleMs
            if (safeIdle < timeout) timeout - safeIdle else -1L
        }
    }

    /** 生效超时：非正值回默认。 */
    fun effectiveTimeout(powerSaveTimeoutMs: Long): Long =
        if (powerSaveTimeoutMs > 0L) powerSaveTimeoutMs else DEFAULT_POWER_SAVE_TIMEOUT_MS

    private fun normalizeMinute(minute: Int): Int {
        val m = minute % MINUTES_PER_DAY
        return if (m < 0) m + MINUTES_PER_DAY else m
    }
}
