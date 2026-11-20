package com.coloros16.liquidglass.hook.text

import android.widget.TextView
import com.coloros16.liquidglass.hook.BlurDrawHook
import com.coloros16.liquidglass.hook.spec.EngineLog
import com.coloros16.liquidglass.log.Lg
import java.lang.reflect.Method

/**
 * 文字可读性颜色注入优先级链：反转取色 > 面板头部时钟 > 文字强制白 > heads-up 跟随系统取色。
 * 单一入口，杜绝各路径独立判定造成「一个白一个黑」。
 */
object TextColorPolicy {

    private const val TAG = "LiquidGlass"

    /** `QsColorUtil.getQsColorState()` 反射（挂载期由 BlurDrawHook 解析写入）。 */
    @Volatile
    internal var mQsColorUtilGetState: Method? = null

    private var lastLoggedQsColorState = Int.MIN_VALUE

    /** 给定 TextView + 原色 + 屏幕坐标，按优先级返回注入色；返回 null = 不注入（保持系统色）。
     *  调用方负责前置排除（状态栏 / 展开按钮 / app 彩色 / 未 attach）与缓存 / 防自循环注入尾。 */
    fun resolveReadableTextColor(textView: TextView, originalColor: Int, screenX: Int, screenY: Int): Int? {
        val tvTag = textView.javaClass.name.substringAfterLast('.')
        // 预设覆盖：文字所在玻璃区域的宿主类绑定预设时，反色/强制白按预设值（未设键回落全局开关）
        val hostCls = TextScopeMatcher.glassRegionHostAt(screenX, screenY, textView)
        val ov = com.coloros16.liquidglass.glass.GlassRegistry.overridesForClass(hostCls)
        val forceWhite = ov?.get(com.coloros16.liquidglass.config.Prefs.KEY_TEXT_FORCE_WHITE_ENABLE)
            ?.let { it > 0.5f } ?: BlurDrawHook.textForceWhiteEnabled
        val contrast = ov?.get(com.coloros16.liquidglass.config.Prefs.KEY_TEXT_CONTRAST_ENABLE)
            ?.let { it > 0.5f } ?: BlurDrawHook.textContrastEnabled
        // 1. 反转取色优先：开了反转就按背景亮度判黑白，避免强制白把浅底文字也刷白
        if (contrast && hostCls != null) {
            val injected = systemQsColorInjected(originalColor, screenX, screenY)
            EngineLog.log("TextColor", "decide rule=contrast tv=$tvTag x=$screenX y=$screenY color=0x${injected.toString(16)}")
            return injected
        }
        // 2. 面板头部时钟/日期：随面板整体背景亮度取色（头部无元素级玻璃区域）
        if (contrast && TextScopeMatcher.isQsPanelHeaderText(textView)) {
            val injected = systemQsColorInjected(originalColor, screenX, screenY)
            EngineLog.log("TextColor", "decide rule=panelHeader tv=$tvTag x=$screenX y=$screenY color=0x${injected.toString(16)}")
            return injected
        }
        // 3. 文字强制白：未开反转时，玻璃区域内 / heads-up 窗口内中性色文字恒白/灰
        if (forceWhite) {
            if (hostCls != null || TextScopeMatcher.isInHeadsUpWindow(textView)) {
                val a = (originalColor ushr 24) and 0xFF
                val injected = (a shl 24) or (BlurDrawHook.maskTextColor and 0x00FFFFFF)
                EngineLog.log("TextColor", "decide rule=forceWhite tv=$tvTag x=$screenX y=$screenY color=0x${injected.toString(16)}")
                return injected
            }
        }
        // 4. heads-up 跟随系统取色：heads-up 窗口内中性色文字按系统黑白通道（深底白字 / 浅底黑字）
        if (TextScopeMatcher.isHeadsUpTextFollowArea(textView)) {
            val injected = systemQsColorInjected(originalColor)
            EngineLog.log("TextColor", "decide rule=headsupFollow tv=$tvTag x=$screenX y=$screenY color=0x${injected.toString(16)}")
            return injected
        }
        EngineLog.log("TextColor", "decide rule=none tv=$tvTag x=$screenX y=$screenY")
        return null
    }

    /** 读取系统黑白通道决定注入色：0 → 深色字（保留原 alpha）；1 → 白色字（保留原 alpha）；
     *  2（null/无数据）/ 反射失败 → 返回 [original]（保持原样，不注入）。
     *  实测面板亮度可用时优先按亮度判，避免系统状态与实际背景不一致。 */
    private fun systemQsColorInjected(original: Int, screenX: Int = -1, screenY: Int = -1): Int {
        val a = (original ushr 24) and 0xFF
        val aPrefix = a shl 24
        val local = if (screenX >= 0 && screenY >= 0) BlurDrawHook.localBrightnessAt(screenX, screenY) else -1
        val b = if (local >= 0) local else BlurDrawHook.qsPanelBrightness
        if (b >= 0) {
            val light = b > BlurDrawHook.TEXT_CONTRAST_BRIGHTNESS_THRESHOLD
            logBrightnessDecision(b, light, local >= 0, screenX, screenY)
            return if (light) aPrefix or 0x000000 else aPrefix or 0xFFFFFF
        }
        return when (readQsColorState()) {
            0 -> aPrefix or 0x000000   // 亮背景 → 深色字
            1 -> aPrefix or 0xFFFFFF   // 暗背景 → 白色字
            else -> original           // 2（null/无数据）或反射失败 → 保持原样
        }
    }

    private var lastBrightnessLogged = Int.MIN_VALUE
    private var lastBrightnessLocalLogged = false

    /** 亮度取色判据（值或来源变才打，避免逐帧刷屏）。 */
    private fun logBrightnessDecision(brightness: Int, lightBackground: Boolean, isLocal: Boolean, x: Int, y: Int) {
        if (brightness == lastBrightnessLogged && isLocal == lastBrightnessLocalLogged) return
        lastBrightnessLogged = brightness
        lastBrightnessLocalLogged = isLocal
        Lg.i(
            TAG,
            "text-contrast pick by brightness=$brightness src=${if (isLocal) "local@$x,$y" else "panelAvg"} " +
                "thr=${BlurDrawHook.TEXT_CONTRAST_BRIGHTNESS_THRESHOLD} " +
                "light=$lightBackground → ${if (lightBackground) "dark text" else "white text"}",
        )
    }

    /** 反射读取 `QsColorUtil.getQsColorState()`。失败/未解析返回 -1。 */
    private fun readQsColorState(): Int {
        val m = mQsColorUtilGetState ?: return -1
        val v = try {
            (m.invoke(null) as? Number)?.toInt() ?: -1
        } catch (t: Throwable) {
            -1
        }
        // 系统黑白通道值跳变才打一次（0 亮背景→深色字 / 1 暗背景→白字 / 2 null→原样 / -1 反射失败）
        if (v != lastLoggedQsColorState) {
            lastLoggedQsColorState = v
            Lg.i(TAG, "text-contrast: QsColorUtil.getQsColorState()=$v (0 light→dark text / 1 dark→white text / 2 null / -1 reflect fail)")
        }
        return v
    }
}
