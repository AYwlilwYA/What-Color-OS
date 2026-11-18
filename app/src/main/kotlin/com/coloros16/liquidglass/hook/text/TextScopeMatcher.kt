package com.coloros16.liquidglass.hook.text

import android.graphics.RectF
import android.view.View
import com.coloros16.liquidglass.hook.BlurDrawHook
import com.coloros16.liquidglass.log.Lg
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 文字链范围判定收口：状态栏排除 / 展开按钮数字 / 玻璃区域 / heads-up 窗口 / 近中性色。 */
object TextScopeMatcher {

    private const val TAG = "LiquidGlass"

    /** 类名 → 「是否状态栏区域宿主」判定缓存（类名判定恒定，避免父链逐层重复字符串搜索）。 */
    private val statusBarHostClassCache = ConcurrentHashMap<String, Boolean>()

    private var statusBarBottomPx = -1

    /** 判定屏幕坐标是否落在任一已登记玻璃区域（screenRegionMap，主线程读写）。
     *  状态栏区域（y < [statusBarBottom]）恒判"非玻璃"：screenRegionMap 中控件的 region 可能覆盖状态栏
     *  条带，状态栏文字（时钟/图标 label）被误判"玻璃区域内"会被文字变色误伤。 */
    fun isOverGlassRegion(screenX: Int, screenY: Int): Boolean {
        val sb = statusBarBottom()
        if (sb > 0 && screenY < sb) return false
        for (entry in BlurDrawHook.screenRegionMap.values) {
            val r = entry.region
            if (screenX >= r.left && screenX <= r.right && screenY >= r.top && screenY <= r.bottom) return true
        }
        return false
    }

    /** 命中的玻璃区域宿主类名（文字链按元素预设覆盖用）；未命中 → null。判定同 [isOverGlassRegion]。 */
    fun glassRegionHostAt(screenX: Int, screenY: Int): String? =
        glassRegionHostAt(screenX, screenY, null)

    /**
     * 带归属校验的宿主判定：坐标命中 region 后，若该 region 有宿主 View 引用，
     * 还须 textView 在该宿主的子树内才算命中——否则只是「坐标碰巧落在矩形里」的无关文字
     * （锁屏密码提示 / 紧急呼叫 / 对话框 / 输入框等叠在通知卡矩形上时会被误判）。
     * textView=null 时退化为纯坐标判定（供无 View 上下文的调用方）。
     */
    fun glassRegionHostAt(screenX: Int, screenY: Int, textView: View?): String? {
        val sb = statusBarBottom()
        if (sb > 0 && screenY < sb) {
            logNoRegion(screenX, screenY, textView, "状态栏条带排除 sb=$sb")
            return null
        }
        var rectHits = 0
        for (entry in BlurDrawHook.screenRegionMap.values) {
            val r = entry.region
            if (screenX < r.left || screenX > r.right || screenY < r.top || screenY > r.bottom) continue
            rectHits++
            val host = entry.hostViewRef?.get()
            if (textView != null && host != null && !isDescendantOf(textView, host)) {
                logMissOnce(entry.hostViewName, textView)
                continue
            }
            return entry.hostViewName
        }
        logNoRegion(screenX, screenY, textView, if (rectHits == 0) "无矩形命中" else "矩形命中但归属全不符")
        return null
    }

    private var regionDumpAt = 0L

    /**
     * 未命中原因诊断：区分「状态栏排除 / 无矩形命中 / 归属不符」，
     * 并每 10s 附一次区域全量快照（数量 + 各矩形 + 宿主名），用于判定是登记缺失还是坐标错位。
     */
    private fun logNoRegion(x: Int, y: Int, textView: View?, why: String) {
        val tv = textView?.javaClass?.simpleName ?: "?"
        val key = "noregion|$why|$tv"
        val now = android.os.SystemClock.uptimeMillis()
        val last = missLogged[key]
        if (last == null || now - last >= 10_000L) {
            missLogged[key] = now
            Lg.i(TAG, "text-scope miss $why tv=$tv x=$x y=$y regions=${BlurDrawHook.screenRegionMap.size}")
        }
        if (now - regionDumpAt < 10_000L) return
        regionDumpAt = now
        val sb = StringBuilder("text-scope dump n=${BlurDrawHook.screenRegionMap.size} probe=$x,$y")
        var i = 0
        for (entry in BlurDrawHook.screenRegionMap.values) {
            if (i++ >= 12) {
                sb.append(" …")
                break
            }
            val r = entry.region
            sb.append(" | ${entry.hostViewName}[${r.left.toInt()},${r.top.toInt()},${r.right.toInt()},${r.bottom.toInt()}]")
            val h = entry.hostViewRef?.get()
            sb.append(if (h == null) "(host=null)" else "(host=${h.javaClass.simpleName})")
        }
        Lg.i(TAG, sb.toString())
    }

    /** textView 是否在 host 子树内（含自身）；父链上溯，深度上限防病态树。 */
    private fun isDescendantOf(textView: View, host: View): Boolean {
        var v: View? = textView
        var depth = 0
        while (v != null && depth < 40) {
            if (v === host) return true
            v = v.parent as? View
            depth++
        }
        return false
    }

    private val missLogged = ConcurrentHashMap<String, Long>()

    /** 归属不符导致跳过的诊断（按 宿主类+文字类 节流 10s，避免逐帧刷屏）。 */
    private fun logMissOnce(hostName: String, textView: View) {
        val key = hostName + "|" + textView.javaClass.simpleName
        val now = android.os.SystemClock.uptimeMillis()
        val last = missLogged[key]
        if (last != null && now - last < 10_000L) return
        missLogged[key] = now
        Lg.i(TAG, "text-scope skip 非宿主子树 host=$hostName tv=${textView.javaClass.simpleName}")
    }

    /** 状态栏区域底边（物理 px）：framework `status_bar_height` dimen（Resources.getSystem()，
     *  设备密度生效）。首次调用缓存到 [statusBarBottomPx]；解析失败返回 -1（不启用排除，回退现状）。 */
    private fun statusBarBottom(): Int {
        var v = statusBarBottomPx
        if (v >= 0) return v
        v = try {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            if (id <= 0) -1 else res.getDimensionPixelSize(id)
        } catch (t: Throwable) {
            Lg.e(TAG, "text-contrast: resolve status_bar_height failed, status-bar strip exclusion disabled", t)
            -1
        }
        statusBarBottomPx = v
        if (v > 0) {
            Lg.i(TAG, "text-contrast: status_bar_height=$v px, status-bar strip excluded from glass-region (text never recolored)")
        }
        return v
    }

    /** 状态栏文字判定：沿祖先链向上（含自身），任一类名命中状态栏容器即返回 true。
     *  这些类名均为状态栏专属（不匹配 QS 容器/通知卡/别的布局的时钟），不会误伤玻璃区域内文字。 */
    fun isInStatusBarArea(textView: View): Boolean {
        var v: View? = textView
        while (v != null) {
            val name = v.javaClass.name
            val isHost = statusBarHostClassCache.getOrPut(name) {
                name.contains("PhoneStatusBarView") ||
                    name.contains("KeyguardStatusBarView") ||
                    name.contains("StatusBarWindowView") ||
                    name.contains("StatusBarContainer") ||
                    name.contains("StatusIconContainer")
            }
            if (isHost) return true
            val parent = v.parent
            v = if (parent is View) parent else null
        }
        return false
    }

    /** 判定 TextView 是否是折叠组头展开按钮数字（资源名匹配；无 id/非资源 id 抛异常返回 false）。
     *  该数字原色是灰色（中性色），保持系统原色，不参与文字变色。 */
    fun isExpandButtonNumber(tv: View): Boolean {
        if (tv.id <= 0) return false
        return try {
            tv.resources.getResourceEntryName(tv.id) == BlurDrawHook.EXPAND_BUTTON_NUMBER_RES_NAME
        } catch (t: Throwable) {
            false
        }
    }

    /** heads-up 窗口内文字判定：rootView=HeadsUpLayout/HeadsUpContainerWindow（type 2017）。
     *  heads-up 卡片背景即玻璃，窗口内文字恒视为玻璃区域文字——不受 screenRegionMap 登记时序/
     *  坐标/快照刷新影响。状态栏时钟 rootView=shade 根 → 不命中。 */
    fun isInHeadsUpWindow(textView: View): Boolean {
        return try {
            val root = textView.rootView
            root != null && BlurDrawHook.isHeadsUpRootView(root)
        } catch (t: Throwable) {
            false
        }
    }

    /** bgView 是否属于 heads-up 窗口（判定同 [isInHeadsUpWindow]）。 */
    fun isHeadsUpBgView(view: View): Boolean = isInHeadsUpWindow(view)

    /** heads-up 文字跟随整体颜色作用域：独立开关开启 && 在 heads-up 窗口内。
     *  开关关 → 恒 false → 守卫回退现状，保证与遮罩无关、可独立关闭。 */
    fun isHeadsUpTextFollowArea(textView: View): Boolean =
        BlurDrawHook.headsUpTextFollowEnabled && isInHeadsUpWindow(textView)

    /** 近中性文字色判定：alpha ≥ 0x80 且 RGB 通道差 ≤24（系统灰度）；
     *  饱和 app 自定义色不匹配 → 不覆盖，尊重 app。 */
    fun isNeutralTextColor(color: Int): Boolean {
        val a = (color ushr 24) and 0xFF
        if (a < 0x80) return false
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return abs(r - g) <= 24 && abs(g - b) <= 24 && abs(r - b) <= 24
    }
}
