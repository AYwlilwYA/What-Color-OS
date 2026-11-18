package com.coloros16.liquidglass.diagnostics

import android.util.Log

/** 诊断事件出口：按 key 节流写 logcat，避免逐帧调用刷屏。 */
object RuntimeDiagnostics {
    private const val TAG = "LiquidGlass"
    private const val MIN_INTERVAL_MS = 300L
    private val lastAt = HashMap<String, Long>()

    @Volatile
    var enabled = true

    private fun allow(key: String): Boolean {
        if (!enabled) return false
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(lastAt) {
            val last = lastAt[key] ?: -MIN_INTERVAL_MS
            if (now - last < MIN_INTERVAL_MS) return false
            lastAt[key] = now
            return true
        }
    }

    fun mark(key: String, message: String) {
        if (allow(key)) Log.i(TAG, "kg:$key $message")
    }

    fun update(key: String, value: String) {
        if (allow(key)) Log.i(TAG, "kg:$key $value")
    }
}
