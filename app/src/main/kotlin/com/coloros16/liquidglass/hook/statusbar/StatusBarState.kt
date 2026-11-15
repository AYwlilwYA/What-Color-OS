package com.coloros16.liquidglass.hook.statusbar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.coloros16.liquidglass.log.Lg

/**
 * 状态栏数据源：只提供「Wi-Fi 是否连接 / 默认数据是否开启 / 默认数据 subId」三个事实。
 * 2s TTL 缓存（布局回调里会被高频问，不能每次都走系统服务）；
 * 任一查询失败一律按「不确定」处理（= 不隐藏、不拦截），不让权限/时序问题改变用户状态栏。
 */
object StatusBarState {

    private const val TAG = "LgStatusBar"
    private const val TTL_MS = 2000L

    @Volatile private var wifiCache = false
    @Volatile private var wifiAt = 0L

    @Volatile private var dataCache = true
    @Volatile private var dataAt = 0L

    @Volatile private var subIdCache = -1
    @Volatile private var subIdAt = 0L

    fun wifiConnected(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (wifiAt != 0L && now - wifiAt < TTL_MS) return wifiCache
        wifiAt = now
        wifiCache = try {
            val ctx = Lg.processContext() ?: return wifiCache
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-state wifi 查询失败 ${t.javaClass.simpleName}，按未连接处理")
            false
        }
        return wifiCache
    }

    /** 默认数据是否开启；查询失败按 true（不隐藏）。 */
    fun dataEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (dataAt != 0L && now - dataAt < TTL_MS) return dataCache
        dataAt = now
        dataCache = try {
            val ctx = Lg.processContext() ?: return dataCache
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return dataCache
            val subId = defaultDataSubId()
            val target = if (subId >= 0) tm.createForSubscriptionId(subId) else tm
            target.isDataEnabled
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-state data 查询失败 ${t.javaClass.simpleName}，按开启处理")
            true
        }
        return dataCache
    }

    /** 默认数据 subId；失败 -1（调用方按「无法区分主副卡」处理）。 */
    fun defaultDataSubId(): Int {
        val now = SystemClock.uptimeMillis()
        if (subIdAt != 0L && now - subIdAt < TTL_MS) return subIdCache
        subIdAt = now
        subIdCache = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-state subId 查询失败 ${t.javaClass.simpleName}")
            -1
        }
        return subIdCache
    }
}
