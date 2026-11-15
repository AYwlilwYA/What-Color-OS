package com.coloros16.liquidglass.hook.statusbar

import android.content.SharedPreferences
import android.os.SystemClock
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface

/**
 * 状态栏配置快照（不可变）。所有字段默认 = 系统原样。
 * [enabled] 是状态栏功能线总闸，其余字段只在它为 true 时才被读取消费。
 */
data class StatusBarConfig(
    val enabled: Boolean = false,
    val simplify: Boolean = false,
    val hideNotificationIcons: Boolean = false,
    val hideNetworkType: Boolean = false,
    val typeRightOfSignal: Boolean = false,
    val hideRoaming: Boolean = false,
    val hideDataActivity: Boolean = false,
    val hideWifiActivity: Boolean = false,
    val hideWifiTech: Boolean = false,
    val hideMobileWhenDataOff: Boolean = false,
    val hideMobileWhenWifi: Boolean = false,
    val keepSim2FiveG: Boolean = false,
    val dualSimMerge: Boolean = false,
    val dualRowGap: Float = 0.08f,
    val secondaryScale: Float = 0.25f,
    val iconOrder: List<String> = emptyList(),
    val marginLeftDp: Int = -1,
    val marginRightDp: Int = -1,
    val clockShowSeconds: Boolean = false,
    val clockShowDate: Boolean = false,
    val clockGravity: Int = 0,
    val notificationIconsRight: Boolean = false,
    val duoEnable: Boolean = false,
) {
    /** 是否有任一功能实际开启（总闸关闭时恒 false）。 */
    val anyFeature: Boolean
        get() = enabled && (
            simplify ||
                hideNotificationIcons ||
                hideNetworkType ||
                typeRightOfSignal ||
                hideRoaming ||
                hideDataActivity ||
                hideWifiActivity ||
                hideWifiTech ||
                hideMobileWhenDataOff ||
                hideMobileWhenWifi ||
                keepSim2FiveG ||
                dualSimMerge ||
                iconOrder.isNotEmpty() ||
                marginLeftDp >= 0 ||
                marginRightDp >= 0 ||
                clockShowSeconds ||
                clockShowDate ||
                clockGravity != 0 ||
                notificationIconsRight ||
                duoEnable
            )

    companion object {
        /** 全关快照（还原用）。 */
        val OFF = StatusBarConfig()
    }
}

/** 配置读取：500ms TTL 热路径缓存；读失败保持上次快照（fail-open 不误关用户功能）。 */
object StatusBarSettings {

    private const val TAG = "LgStatusBar"
    private const val TTL_MS = 500L

    @Volatile
    private var cache: StatusBarConfig = StatusBarConfig.OFF

    @Volatile
    private var cacheAtMs = 0L

    fun snapshot(api: XposedInterface?): StatusBarConfig {
        if (api == null) return StatusBarConfig.OFF
        val now = SystemClock.uptimeMillis()
        val cached = cache
        if (cacheAtMs != 0L && now - cacheAtMs < TTL_MS) return cached
        cacheAtMs = now
        val next = try {
            from(Prefs.read(api))
        } catch (t: Throwable) {
            Lg.w(TAG, "sb-config 读键失败 ${t.javaClass.simpleName}，保持上次快照 enabled=${cached.enabled}", t)
            cached
        }
        if (next.enabled != cached.enabled) {
            Lg.i(TAG, "sb-config enabled=${next.enabled}")
        }
        cache = next
        return next
    }

    /** 纯读取（无缓存，单测可直接喂 SharedPreferences）。 */
    fun from(p: SharedPreferences): StatusBarConfig {
        val enabled = p.getBoolean(Prefs.KEY_MASTER, false) &&
            p.getBoolean(Prefs.KEY_STATUSBAR_ENABLE, Prefs.DEFAULT_STATUSBAR_ENABLE)
        val simplify = bool(p, Prefs.KEY_STATUSBAR_SIMPLIFY, Prefs.DEFAULT_STATUSBAR_SIMPLIFY)
        return StatusBarConfig(
            enabled = enabled,
            simplify = simplify,
            hideNotificationIcons = simplify || bool(p, Prefs.KEY_STATUSBAR_HIDE_NOTIFICATION_ICONS, Prefs.DEFAULT_STATUSBAR_HIDE_NOTIFICATION_ICONS),
            hideNetworkType = simplify || bool(p, Prefs.KEY_STATUSBAR_HIDE_NETWORK_TYPE, Prefs.DEFAULT_STATUSBAR_HIDE_NETWORK_TYPE),
            typeRightOfSignal = bool(p, Prefs.KEY_STATUSBAR_TYPE_RIGHT_OF_SIGNAL, Prefs.DEFAULT_STATUSBAR_TYPE_RIGHT_OF_SIGNAL),
            hideRoaming = simplify || bool(p, Prefs.KEY_STATUSBAR_HIDE_ROAMING, Prefs.DEFAULT_STATUSBAR_HIDE_ROAMING),
            hideDataActivity = simplify || bool(p, Prefs.KEY_STATUSBAR_HIDE_DATA_ACTIVITY, Prefs.DEFAULT_STATUSBAR_HIDE_DATA_ACTIVITY),
            hideWifiActivity = simplify || bool(p, Prefs.KEY_STATUSBAR_HIDE_WIFI_ACTIVITY, Prefs.DEFAULT_STATUSBAR_HIDE_WIFI_ACTIVITY),
            hideWifiTech = simplify || bool(p, Prefs.KEY_STATUSBAR_HIDE_WIFI_TECH, Prefs.DEFAULT_STATUSBAR_HIDE_WIFI_TECH),
            hideMobileWhenDataOff = bool(p, Prefs.KEY_STATUSBAR_HIDE_MOBILE_WHEN_DATA_OFF, Prefs.DEFAULT_STATUSBAR_HIDE_MOBILE_WHEN_DATA_OFF),
            hideMobileWhenWifi = bool(p, Prefs.KEY_STATUSBAR_HIDE_MOBILE_WHEN_WIFI, Prefs.DEFAULT_STATUSBAR_HIDE_MOBILE_WHEN_WIFI),
            keepSim2FiveG = bool(p, Prefs.KEY_STATUSBAR_KEEP_SIM2_5G, Prefs.DEFAULT_STATUSBAR_KEEP_SIM2_5G),
            dualSimMerge = bool(p, Prefs.KEY_STATUSBAR_DUAL_SIM_MERGE, Prefs.DEFAULT_STATUSBAR_DUAL_SIM_MERGE),
            dualRowGap = int(p, Prefs.KEY_STATUSBAR_DUAL_ROW_GAP, Prefs.DEFAULT_STATUSBAR_DUAL_ROW_GAP)
                .coerceIn(Prefs.STATUSBAR_DUAL_ROW_GAP_MIN, Prefs.STATUSBAR_DUAL_ROW_GAP_MAX) / 100f,
            secondaryScale = int(p, Prefs.KEY_STATUSBAR_SECONDARY_SCALE, Prefs.DEFAULT_STATUSBAR_SECONDARY_SCALE)
                .coerceIn(Prefs.STATUSBAR_SECONDARY_SCALE_MIN, Prefs.STATUSBAR_SECONDARY_SCALE_MAX) / 100f,
            iconOrder = parseOrder(p.getString(Prefs.KEY_STATUSBAR_ICON_ORDER, Prefs.DEFAULT_STATUSBAR_ICON_ORDER)),
            marginLeftDp = int(p, Prefs.KEY_STATUSBAR_MARGIN_LEFT, Prefs.DEFAULT_STATUSBAR_MARGIN_LEFT)
                .coerceIn(Prefs.STATUSBAR_MARGIN_MIN, Prefs.STATUSBAR_MARGIN_MAX),
            marginRightDp = int(p, Prefs.KEY_STATUSBAR_MARGIN_RIGHT, Prefs.DEFAULT_STATUSBAR_MARGIN_RIGHT)
                .coerceIn(Prefs.STATUSBAR_MARGIN_MIN, Prefs.STATUSBAR_MARGIN_MAX),
            clockShowSeconds = bool(p, Prefs.KEY_STATUSBAR_CLOCK_SECONDS, Prefs.DEFAULT_STATUSBAR_CLOCK_SECONDS),
            clockShowDate = bool(p, Prefs.KEY_STATUSBAR_CLOCK_DATE, Prefs.DEFAULT_STATUSBAR_CLOCK_DATE),
            clockGravity = int(p, Prefs.KEY_STATUSBAR_CLOCK_GRAVITY, Prefs.DEFAULT_STATUSBAR_CLOCK_GRAVITY).coerceIn(0, 2),
            notificationIconsRight = bool(p, Prefs.KEY_STATUSBAR_NOTIFICATION_ICONS_RIGHT, Prefs.DEFAULT_STATUSBAR_NOTIFICATION_ICONS_RIGHT),
            duoEnable = bool(p, Prefs.KEY_STATUSBAR_DUO, Prefs.DEFAULT_STATUSBAR_DUO),
        )
    }

    /** 槽名归一：把配置串里的历史短名对齐到系统真实槽名。 */
    fun normalizeSlot(name: String): String = when (name) {
        "alarm" -> "alarm_clock"
        else -> name
    }

    /** 是否为状态栏图标槽名：通知 key（形如 pkg/0x1a2b，含斜杠）不属于图标槽。 */
    fun isIconSlot(slot: String): Boolean = slot.isNotEmpty() && !slot.contains('/')

    /** 图标顺序解析：逗号分隔、去空、小写、槽名归一、去重（保序），通知 key 丢弃。空串 = 系统顺序。 */
    fun parseOrder(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        raw.split(',').forEach { part ->
            val name = normalizeSlot(part.trim().lowercase())
            if (isIconSlot(name) && seen.add(name)) out.add(name)
        }
        return out
    }

    private fun bool(p: SharedPreferences, key: String, def: Boolean): Boolean = try {
        p.getBoolean(key, def)
    } catch (_: Throwable) {
        def
    }

    private fun int(p: SharedPreferences, key: String, def: Int): Int = Prefs.readIntCompat(p, key, def)
}
