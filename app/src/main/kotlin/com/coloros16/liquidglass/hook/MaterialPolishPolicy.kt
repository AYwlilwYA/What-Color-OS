package com.coloros16.liquidglass.hook

import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.log.Lg

/**
 * 材质精修（uSurfacePolish）宿主判定：只在 QS 滑杆 / 通知背景类宿主上启用精修材质。
 * Launcher / 锁屏时钟 / 独立音量面板类名不落名单 → 恒 false 不走该分支。
 * 版本差异（若有）一律收敛在本文件单点，不散落 if。
 */
object MaterialPolishPolicy {

    private const val TAG = "MaterialPolish"

    /** 宿主自身 + 父链上溯层数（宿主 1 层 + 祖先 8 层）。 */
    private const val MAX_PARENT_DEPTH = 8

    private const val HIT_LOG_INTERVAL_MS = 5000L
    private const val MISS_LOG_INTERVAL_MS = 10000L

    @Volatile
    private var hitTotal = 0

    @Volatile
    private var hitLastAtMs = 0L

    @Volatile
    private var hitLoggedOnce = false

    @Volatile
    private var missLastAtMs = 0L

    @Volatile
    private var missLoggedOnce = false

    /**
     * 宿主类名是否属于精修材质范围（纯字符串判定，无 View 依赖）。
     * 名单：QS 包路径三式 + 通知背景短名 + 通知包前缀 + OplusQS/OplusQs 短名前缀。
     */
    fun polishMaterial(host: String?): Boolean {
        if (host == null) return false
        val simple = host.substringAfterLast(':').substringAfterLast('.')
        return host.startsWith("com.oplus.systemui.qs.") ||
            host.contains(".plugins.qs.") ||
            host.contains(".systemui.qs.") ||
            simple.startsWith("NotificationBackgroundView") ||
            host.startsWith("com.oplus.systemui.notification.") ||
            simple.startsWith("OplusQS") ||
            simple.startsWith("OplusQs")
    }

    /** 宿主自身 + 父链上溯 8 层命中精修名单 → true；全链未命中 / 异常 → false 并记判定失败原因。 */
    fun usePolishedMaterial(host: View?): Boolean {
        if (host == null) {
            logMiss("null-host")
            return false
        }
        return try {
            var cursor: View? = host
            var depth = 0
            while (cursor != null && depth <= MAX_PARENT_DEPTH) {
                val name = cursor.javaClass.name
                if (polishMaterial(name)) {
                    logHit(name, depth)
                    return true
                }
                cursor = cursor.parent as? View
                depth++
            }
            logMiss("no-match:${host.javaClass.name}")
            false
        } catch (t: Throwable) {
            logMiss("throw:${t.javaClass.simpleName}")
            false
        }
    }

    /** 首次命中立即记一条，此后按间隔记节流命中计数；key 回显进文本供 grep。 */
    private fun logHit(name: String, depth: Int) {
        hitTotal++
        val now = SystemClock.uptimeMillis()
        val first = !hitLoggedOnce
        if (first) hitLoggedOnce = true
        if (first || now - hitLastAtMs >= HIT_LOG_INTERVAL_MS) {
            hitLastAtMs = now
            Lg.i(TAG, "usePolishedMaterial hit total=$hitTotal depth=$depth host=$name key=paint-polish-hit")
        }
    }

    /** 判定失败原因首次记一条，此后按间隔节流；key 回显进文本供 grep。 */
    private fun logMiss(reason: String) {
        val now = SystemClock.uptimeMillis()
        val first = !missLoggedOnce
        if (first) missLoggedOnce = true
        if (first || now - missLastAtMs >= MISS_LOG_INTERVAL_MS) {
            missLastAtMs = now
            Lg.i(TAG, "usePolishedMaterial miss reason=$reason key=paint-polish-miss")
        }
    }
}
