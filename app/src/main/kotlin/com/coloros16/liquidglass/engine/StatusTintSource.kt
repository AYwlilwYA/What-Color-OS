package com.coloros16.liquidglass.engine

/**
 * 状态栏取色来源身份判据：只有系统状态图标与状态栏自身文字随暗色分发染色。
 *
 * 动画图标 tintList 恒白、面板内容文字不随图标色，取它们会误判。
 * 纯 JVM 类名/资源名判定，hook 侧传入实际类名与资源名。
 */
object StatusTintSource {

    /** 沿继承链上溯的最大层数。 */
    const val MAX_DEPTH = 6

    /** 类名链里是否有系统状态图标（StatusBarIconView / StatIconView / OplusStatusBarIcon）。 */
    fun isSystemStatusIconChain(chain: List<String>): Boolean {
        for ((i, n) in chain.withIndex()) {
            if (i >= MAX_DEPTH) break
            if (n.contains("StatusBarIconView") || n.contains("StatIconView") ||
                n.contains("OplusStatusBarIcon")
            ) return true
        }
        return false
    }

    /** 类名是否状态栏自身文字（时钟 / 运营商 / 电量百分比）。 */
    fun isStatusTextClassName(name: String): Boolean =
        name.contains("Clock") || name.contains("CarrierText") || name.contains("BatteryPercent")

    /** 资源名是否状态栏自身文字。 */
    fun isStatusTextResName(entry: String?): Boolean {
        if (entry.isNullOrEmpty()) return false
        return entry == "clock" || entry == "battery_percent_view" || entry.contains("carrier")
    }

    /** 文字来源综合判定：类名或资源名任一命中即可。 */
    fun isStatusText(className: String, resEntry: String?): Boolean =
        isStatusTextClassName(className) || isStatusTextResName(resEntry)
}