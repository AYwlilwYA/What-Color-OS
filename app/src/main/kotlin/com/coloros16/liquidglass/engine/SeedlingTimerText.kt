package com.coloros16.liquidglass.engine

/**
 * 计时文案求值（纯 JVM）：倒计时/正计时按毫秒格式化 mm:ss 或 h:mm:ss，
 * 倒计时钳 0、正计时不钳；到点判定供调用方一次性收尾。
 */
object SeedlingTimerText {

    fun format(remainingMs: Long): String {
        val totalSec = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** 倒计时：endAt-Now 钳 0；endAt<=0 视为未配置返回 null。 */
    fun countdownLabel(endAtElapsedMs: Long, nowElapsedMs: Long, prefix: String = "计时"): String? {
        if (endAtElapsedMs <= 0L) return null
        return "$prefix ${format(endAtElapsedMs - nowElapsedMs)}"
    }

    /** 正计时（秒表/录音）：now-base；base<=0 视为未配置返回 null。 */
    fun countUpLabel(baseElapsedMs: Long, nowElapsedMs: Long, prefix: String): String? {
        if (baseElapsedMs <= 0L) return null
        return "$prefix ${format(nowElapsedMs - baseElapsedMs)}"
    }

    fun countdownDone(endAtElapsedMs: Long, nowElapsedMs: Long): Boolean =
        endAtElapsedMs > 0L && nowElapsedMs >= endAtElapsedMs
}
