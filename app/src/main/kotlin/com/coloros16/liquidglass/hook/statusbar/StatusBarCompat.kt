package com.coloros16.liquidglass.hook.statusbar

import com.coloros16.liquidglass.hook.StatusBarDecision
import com.coloros16.liquidglass.hook.VersionPool
import com.coloros16.liquidglass.log.Lg
import java.util.concurrent.ConcurrentHashMap

/**
 * 状态栏适配唯一出口：类名 / 方法名 / 资源 id / 版本结构档。
 * 版本判定只在本文件调 [VersionPool]，功能代码内禁止再写版本 if。
 */
object StatusBarCompat {

    const val PKG_SYSTEMUI = "com.android.systemui"

    const val FQCN_FRAGMENT = "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment"
    const val FQCN_ICON_CONTAINER = "com.android.systemui.statusbar.phone.StatusIconContainer"
    const val FQCN_ICON_CONTROLLER = "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl"
    const val FQCN_DARK_DISPATCHER = "com.android.systemui.statusbar.phone.DarkIconDispatcherImpl"
    const val FQCN_MOBILE_VIEW = "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView"
    const val FQCN_BATTERY_VIEW = "com.oplus.systemui.statusbar.pipeline.battery.ui.view.StatBatteryMeterView"

    /** 下拉面板运营商文本控件：新世代继承 AnimateChangeTextView，旧世代直接继承 AppCompatTextView。 */
    const val FQCN_CARRIER_ANIM = "com.oplus.systemui.qs.widget.AnimateChangeTextView"
    const val FQCN_CARRIER_SECOND = "com.oplus.systemui.qs.widget.OplusSecondCarrierText"
    const val FQCN_CARRIER_LEGACY = "com.oplus.systemui.qs.widget.OplusQSCarrierText"
    const val FQCN_CARRIER_CONTROLLER = "com.oplus.systemui.qs.OplusQSCarrierTextController"

    /** 下拉大时钟与日期（分离式通知中心面板）。 */
    const val FQCN_QS_SIMPLE_HEADER = "com.oplus.systemui.separate.OplusQSSimpleHeader"

    /** 合并式面板的 QS 页脚：大时钟与日期挂在这里，与分离式 header 是两套树。 */
    const val FQCN_QS_FOOTER = "com.oplus.systemui.qs.OplusQSFooterImpl"

    /** 门禁用到的资源 id 名（缺一个只降级对应功能）。 */
    val ID_NAMES = listOf(
        "status_bar_contents",
        "status_bar_start_side_container",
        "status_bar_end_side_container",
        "status_bar_end_side_content",
        "status_icon_area",
        "keyguard_header",
        "quick_qs_status_icons",
        "icons",
        "batteryRemainingIcon",
        "qs_clock_container",
        "qs_footer",
        "qs_footer_clock",
        "oplus_date",
        "settings_button_container",
        "qs_carrier_text",
        "header",
        "statusIcons",
        "notification_icon_area",
        "clock",
        "battery",
        "mobile_group",
        "mobile_signal",
        "mobile_type",
        "mobile_roaming",
        "mobile_roaming_large",
        "mobile_roaming_space",
        "data_inout",
        "inout_container",
        "mobile_in",
        "mobile_out",
        "wifi_group",
        "wifi_combo",
        "wifi_signal",
        "wifi_inout",
        "wifi_left",
        "wifi_in",
        "wifi_out",
    )

    private val ids = ConcurrentHashMap<String, Int>()
    private val missLogged = ConcurrentHashMap<String, Long>()
    private const val TAG = "LgStatusBar"

    @Volatile
    private var decision: StatusBarDecision? = null

    /** 资源 id：解析到就永久缓存；解析不到每次重试（Application context 可能晚于首次调用就绪）。 */
    fun id(name: String): Int {
        ids[name]?.let { return it }
        val ctx = Lg.processContext() ?: return 0
        val value = try {
            ctx.resources.getIdentifier(name, "id", PKG_SYSTEMUI)
        } catch (_: Throwable) {
            0
        }
        if (value != 0) {
            ids[name] = value
        } else {
            val now = android.os.SystemClock.uptimeMillis()
            val last = missLogged[name]
            if (last == null || now - last > 10_000L) {
                missLogged[name] = now
                Lg.i(TAG, "sb-gate id.$name miss")
            }
        }
        return value
    }

    /** 宿主结构档（结果缓存；版本读取失败时按池的 VerUnknown 语义取最小已知点）。 */
    fun structure(): StatusBarDecision {
        decision?.let { return it }
        val ver = VersionPool.readVersion(PKG_SYSTEMUI)
        cachedVersion = ver
        val d = VersionPool.statusBar(PKG_SYSTEMUI, ver)
        decision = d
        return d
    }

    /** 零探测定档：只用已缓存决策，否则按未知版本纯查表（不取 context / 不读 PM）。 */
    fun structureCached(): StatusBarDecision {
        decision?.let { return it }
        val d = VersionPool.statusBar(PKG_SYSTEMUI, cachedVersion)
        decision = d
        return d
    }

    @Volatile
    private var cachedVersion: Long = -1L

    /** 门禁结果。 */
    data class Gates(
        val fragment: Boolean,
        val container: Boolean,
        val controller: Boolean,
        val darkDispatcher: Boolean,
        val mobileView: Boolean,
        val batteryView: Boolean,
        val idsOk: Int,
        val idsMiss: Int,
        val version: Long,
        val structure: StatusBarDecision,
    ) {
        fun summary(): String =
            "fragment=$fragment container=$container controller=$controller " +
                "dark=$darkDispatcher mobileView=$mobileView battery=$batteryView " +
                "ids=$idsOk/${idsOk + idsMiss} ver=$version " +
                "match=${structure.match} rev=${structure.profile.layoutRev}"
    }

    /**
     * 只读探测：逐项打 `sb-gate`（永不改宿主行为）。
     * 侧写运行时真值到版本池补丁层（差异/缺失时功能自行降级）。
     */
    fun gate(classLoader: ClassLoader): Gates = gate(classLoader, allowProbe = true)

    /**
     * allowProbe=false：跳过 versionCode 读取（PM binder + 三级反射取 context）。
     * 热重载回调线程内做类加载/取 PM 会撞类初始化锁（实测 futex_wait 卡死整条安装链），
     * 该路径只用已缓存 id 与纯查表定档，版本留未知由后续主线程补。
     */
    fun gate(classLoader: ClassLoader, allowProbe: Boolean): Gates {
        val resolved = if (allowProbe) hostLoader(classLoader) else (cachedHostLoader ?: classLoader)
        Lg.i(
            TAG,
            "sb-gate paramLoader=${classLoader.javaClass.name} resolvedLoader=${resolved.javaClass.name} " +
                "probe=$allowProbe same=${resolved === classLoader}",
        )
        val fragment = present(classLoader, FQCN_FRAGMENT, "class.fragment", allowProbe)
        val container = present(classLoader, FQCN_ICON_CONTAINER, "class.container", allowProbe)
        val controller = present(classLoader, FQCN_ICON_CONTROLLER, "class.controller", allowProbe)
        val dark = present(classLoader, FQCN_DARK_DISPATCHER, "class.darkDispatcher", allowProbe)
        val mobileView = present(classLoader, FQCN_MOBILE_VIEW, "class.mobileView", allowProbe)
        val batteryView = present(classLoader, FQCN_BATTERY_VIEW, "class.batteryView", allowProbe)
        var idsOk = 0
        ID_NAMES.forEach { name ->
            val v = if (allowProbe) id(name) else (ids[name] ?: 0)
            if (v != 0) idsOk++
        }
        val idsMiss = ID_NAMES.size - idsOk
        val structure = if (allowProbe) structure() else structureCached()
        val version = if (allowProbe) VersionPool.readVersion(PKG_SYSTEMUI) else cachedVersion
        val gates = Gates(
            fragment = fragment,
            container = container,
            controller = controller,
            darkDispatcher = dark,
            mobileView = mobileView,
            batteryView = batteryView,
            idsOk = idsOk,
            idsMiss = idsMiss,
            version = version,
            structure = structure,
        )
        // 零探测路径 idsOk 恒可能为 0（缓存未热），不写补丁层以免把 unknown 当真值污染
        if (allowProbe) {
            VersionPool.notePatch(
                PKG_SYSTEMUI,
                PATCH_STATUS_BAR_LAYOUT,
                if (idsOk == 0) "unknown" else "rev${structure.profile.layoutRev}",
            )
        }
        Lg.i(TAG, "sb-gate ${gates.summary()}")
        return gates
    }

    /** 状态栏布局结构补丁键（值 = rev1 / unknown）。 */
    const val PATCH_STATUS_BAR_LAYOUT = "statusBarLayout"

    /**
     * 宿主可用的类加载器：优先进程 Application 的 classLoader，取不到才回退框架入参。
     * 框架入参在部分注入模式下无法加载 SystemUI 主 dex（实测同类同 dex 时玻璃线走 context 成功、纯入参全 CNFE）。
     */
    fun hostLoader(fallback: ClassLoader): ClassLoader {
        cachedHostLoader?.let { return it }
        val l = try {
            Lg.processContext()?.classLoader ?: fallback
        } catch (_: Throwable) {
            fallback
        }
        cachedHostLoader = l
        return l
    }

    /** 宿主 loader 缓存：避免每次探测都走三级反射取 context（热重载线程内会撞类初始化锁）。 */
    @Volatile
    private var cachedHostLoader: ClassLoader? = null

    /** 零反射取 loader：有缓存用缓存，否则用框架入参（安装关键路径专用）。 */
    fun cachedLoaderOr(fallback: ClassLoader): ClassLoader = cachedHostLoader ?: fallback

    private val classPresent = ConcurrentHashMap<String, Boolean>()

    /** allowProbe=false 时只读缓存（未探测过按 true 乐观放行，hook 自身有 try 兜底）。 */
    private fun present(classLoader: ClassLoader, fqcn: String, label: String, allowProbe: Boolean): Boolean {
        if (!allowProbe) return classPresent[fqcn] ?: true
        classPresent[fqcn]?.let {
            Lg.i(TAG, "sb-gate $label ${if (it) "ok" else "miss"} $fqcn (cached)")
            return it
        }
        val loader = hostLoader(classLoader)
        val ok = try {
            Class.forName(fqcn, false, loader)
            true
        } catch (_: Throwable) {
            false
        }
        classPresent[fqcn] = ok
        Lg.i(TAG, "sb-gate $label ${if (ok) "ok" else "miss"} $fqcn")
        return ok
    }
}
