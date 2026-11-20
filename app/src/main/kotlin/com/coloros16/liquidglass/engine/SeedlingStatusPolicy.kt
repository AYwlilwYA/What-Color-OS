package com.coloros16.liquidglass.engine

/**
 * 常驻状态岛求值（纯 JVM，零 android.*）：充电岛 / 耳机岛。
 * 参考 residentcloud：状态类低优先活动（STATUS kind 优先级 20），事件来了自然被高优先压掉；
 * 电源/耳机连接事件驱动上岛，断开即撤，灭屏暂停由场景门控统一管。
 */
object SeedlingStatusPolicy {

    const val CHARGING_ID = "status:charging"
    const val HEADSET_ID = "status:headset"
    const val SOURCE = "system_status"

    /** 电量百分比钳到 0..100。 */
    fun clampLevel(level: Int): Int = level.coerceIn(0, 100)

    /** 充电胶囊文案：充电中 xx% / 已充满。 */
    fun chargingLabel(level: Int, full: Boolean): String =
        if (full) "已充满" else "充电中 ${clampLevel(level)}%"

    /** 充电大卡副题：电源类型 + 电量。 */
    fun chargingSubtitle(level: Int, plugType: Int): String {
        val src = when (plugType) {
            1 -> "交流电源"
            2 -> "USB"
            4 -> "无线充电"
            else -> "电源"
        }
        return "$src · ${clampLevel(level)}%"
    }

    /** 是否已充满（电量满且仍在充）。 */
    fun isFull(level: Int, charging: Boolean): Boolean = charging && clampLevel(level) >= 100

    /** 充电活动是否应上岛：插着电才上（拔线即撤）。 */
    fun chargingActive(plugged: Boolean): Boolean = plugged

    /** 耳机活动是否应上岛：有线或蓝牙音频设备连接才上。 */
    fun headsetActive(wiredConnected: Boolean, bluetoothAudioConnected: Boolean): Boolean =
        wiredConnected || bluetoothAudioConnected

    /** 耳机胶囊文案。 */
    fun headsetLabel(wired: Boolean): String = if (wired) "耳机已连接" else "蓝牙耳机已连接"

    /** 充电岛活动（STATUS 低优先，事件驱动；不可展开按钮回传，仅展示）。 */
    fun chargingActivity(level: Int, plugType: Int, nowElapsedMs: Long): SeedlingActivity {
        val full = isFull(level, charging = true)
        return SeedlingActivity(
            id = CHARGING_ID,
            source = SOURCE,
            kind = SeedlingKind.STATUS,
            startedAtElapsedMs = nowElapsedMs,
            updatedAtElapsedMs = nowElapsedMs,
            compact = SeedlingCompact(
                leading = SeedlingElement.Symbol("BATTERY"),
                label = chargingLabel(level, full),
                contentId = CHARGING_ID,
            ),
            expanded = SeedlingExpanded(
                template = SeedlingTemplate.STATUS,
                title = if (full) "已充满" else "充电中",
                subtitle = chargingSubtitle(level, plugType),
            ),
            expandable = false,
            hideWhenSourceForeground = false,
        )
    }

    /** 耳机岛活动（STATUS 低优先）。 */
    fun headsetActivity(wired: Boolean, nowElapsedMs: Long): SeedlingActivity =
        SeedlingActivity(
            id = HEADSET_ID,
            source = SOURCE,
            kind = SeedlingKind.STATUS,
            startedAtElapsedMs = nowElapsedMs,
            updatedAtElapsedMs = nowElapsedMs,
            compact = SeedlingCompact(
                leading = SeedlingElement.Symbol("HEADSET"),
                label = headsetLabel(wired),
                contentId = HEADSET_ID,
            ),
            expanded = SeedlingExpanded(
                template = SeedlingTemplate.STATUS,
                title = headsetLabel(wired),
                subtitle = if (wired) "有线耳机" else "蓝牙音频",
            ),
            expandable = false,
            hideWhenSourceForeground = false,
        )
}
