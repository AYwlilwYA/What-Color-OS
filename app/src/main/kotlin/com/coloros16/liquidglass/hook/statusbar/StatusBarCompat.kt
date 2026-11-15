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

    /** 门禁用到的资源 id 名（缺一个只降级对应功能）。 */
    val ID_NAMES = listOf(
        "status_bar_contents",
        "status_bar_start_side_container",
        "status_bar_end_side_container",
        "status_bar_end_side_content",
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
        val d = VersionPool.statusBar(PKG_SYSTEMUI, ver)
        decision = d
        return d
    }

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
    fun gate(classLoader: ClassLoader): Gates {
        val fragment = present(classLoader, FQCN_FRAGMENT, "class.fragment")
        val container = present(classLoader, FQCN_ICON_CONTAINER, "class.container")
        val controller = present(classLoader, FQCN_ICON_CONTROLLER, "class.controller")
        val dark = present(classLoader, FQCN_DARK_DISPATCHER, "class.darkDispatcher")
        val mobileView = present(classLoader, FQCN_MOBILE_VIEW, "class.mobileView")
        val batteryView = present(classLoader, FQCN_BATTERY_VIEW, "class.batteryView")
        var idsOk = 0
        ID_NAMES.forEach { name ->
            if (id(name) != 0) idsOk++
        }
        val idsMiss = ID_NAMES.size - idsOk
        val structure = structure()
        val version = VersionPool.readVersion(PKG_SYSTEMUI)
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
        VersionPool.notePatch(
            PKG_SYSTEMUI,
            PATCH_STATUS_BAR_LAYOUT,
            if (idsOk == 0) "unknown" else "rev${structure.profile.layoutRev}",
        )
        Lg.i(TAG, "sb-gate ${gates.summary()}")
        return gates
    }

    /** 状态栏布局结构补丁键（值 = rev1 / unknown）。 */
    const val PATCH_STATUS_BAR_LAYOUT = "statusBarLayout"

    private fun present(classLoader: ClassLoader, fqcn: String, label: String): Boolean {
        val ok = try {
            Class.forName(fqcn, false, classLoader)
            true
        } catch (_: Throwable) {
            false
        }
        Lg.i(TAG, "sb-gate $label ${if (ok) "ok" else "miss"} $fqcn")
        return ok
    }
}
