package com.coloros16.liquidglass.engine

/**
 * 大卡自动收起计时（纯 JVM 时间轴，hook 侧一次性 postDelayed 驱动）：
 * 展开后 holdMs 收起；按住期间暂停计时；抬手按剩余时长续排。
 * holdMs=null（长按临时展开）→ 永不自动收起。
 */
class SeedlingAutoCollapse(val holdMs: Long? = 5000L) {

    private var armedAtMs = -1L
    private var remainingMs = 0L
    private var paused = false

    val enabled: Boolean get() = holdMs != null

    fun arm(nowMs: Long) {
        val hold = holdMs ?: return
        armedAtMs = nowMs
        remainingMs = hold
        paused = false
    }

    fun cancel() {
        armedAtMs = -1L
        remainingMs = 0L
        paused = false
    }

    fun pause(nowMs: Long) {
        if (armedAtMs < 0 || paused || !enabled) return
        remainingMs = (remainingMs - (nowMs - armedAtMs)).coerceAtLeast(0L)
        paused = true
    }

    fun resume(nowMs: Long) {
        if (!paused || !enabled) return
        armedAtMs = nowMs
        paused = false
    }

    /** 距自动收起还剩多少毫秒；null=不自动收（未排程/长按保持/已暂停）。 */
    fun dueInMs(nowMs: Long): Long? {
        if (!enabled || armedAtMs < 0 || paused) return null
        return (remainingMs - (nowMs - armedAtMs)).coerceAtLeast(0L)
    }

    fun isArmed(): Boolean = enabled && armedAtMs >= 0
}