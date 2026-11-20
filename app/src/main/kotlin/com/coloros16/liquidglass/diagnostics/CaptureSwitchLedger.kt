package com.coloros16.liquidglass.diagnostics

/**
 * 一键抓取的开关代管账本：只记「本次为记录而打开」的键，结束时只关这些。
 * 用户原本自己开着的开关不入账，因此不会被结束动作关掉。
 */
class CaptureSwitchLedger {

    private val autoEnabled = linkedSetOf<String>()

    /** 记录前的开关现值：关着才代开并入账；已开着不动也不入账。 */
    fun ensureOn(key: String, currentlyOn: Boolean): Boolean {
        if (currentlyOn) return false
        autoEnabled.add(key)
        return true
    }

    /** 本次代开的键（顺序稳定，便于日志核对）。 */
    fun owned(): List<String> = autoEnabled.toList()

    /** 结束：交出要关回去的键并清空账本；重复调用返回空。 */
    fun releaseForRestore(): List<String> {
        val keys = autoEnabled.toList()
        autoEnabled.clear()
        return keys
    }
}
