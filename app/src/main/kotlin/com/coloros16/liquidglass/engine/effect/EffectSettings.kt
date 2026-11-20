package com.coloros16.liquidglass.engine.effect

import java.util.Locale

/** 息屏显示模式。 */
enum class AodDisplayMode { ALWAYS, SCHEDULED, POWER_SAVE }

/**
 * 效果体系配置模型（纯 JVM）。
 *
 * - 布尔/枚举项是强类型字段；数值项的默认值来自 [EffectParams]，用户改动进 [paramOverrides]。
 * - 持久化用 [toText] / [parse]（版本化文本，容错：未知键忽略、非法值回退默认）。
 */
class EffectSettings {

    var allowAodDynamic: Boolean = false
    var aodAlwaysPlay: Boolean = false
    var aodFramePair: Boolean = true
    var aodFramePhoto: String = ""
    var aodPhoto: String = ""
    var aodStyleId: String = DEFAULT_STYLE_ID
    var lockStyleId: String = DEFAULT_STYLE_ID
    var lockWallpaperEffect: String = EffectCatalog.LOCK_WP_NONE
    var lockSpecialEffect: String = EffectCatalog.LOCK_SP_NONE
    var homeTransition: String = EffectCatalog.HOME_TRANSITION_NONE
    var homeIconEffect: String = EffectCatalog.HOME_ICON_FOREGROUND_FLY
    var homeFollowLock: Boolean = true
    var homeAodIndependent: Boolean = false
    var homeAodStyleId: String = DEFAULT_STYLE_ID
    var displayMode: AodDisplayMode = AodDisplayMode.ALWAYS
    var scheduleStartMinute: Int = 22 * 60
    var scheduleEndMinute: Int = 7 * 60
    var aodInfoBattery: Boolean = true
    var aodInfoNotificationIcon: Boolean = true
    var aodSceneInfo: Boolean = false
    var aodNewNotification: Boolean = true

    private val paramOverrides: MutableMap<String, Float> = LinkedHashMap()

    /** 当前生效的数值参数：用户覆盖优先，否则取表默认。 */
    fun param(id: String): Float =
        paramOverrides[id] ?: EffectParams.find(id)?.default ?: 0f

    /** 写参数；未知参数拒绝写入并返回 false。 */
    fun setParam(id: String, value: Float): Boolean {
        if (EffectParams.find(id) == null) return false
        paramOverrides[id] = EffectParams.clamp(id, value)
        return true
    }

    /** 恢复单个参数到表默认。 */
    fun resetParam(id: String) {
        paramOverrides.remove(id)
    }

    fun resetAllParams() {
        paramOverrides.clear()
    }

    /** 只读快照，便于测试与 UI 展示。 */
    fun paramOverrides(): Map<String, Float> = LinkedHashMap(paramOverrides)

    fun toText(): String {
        val out = StringBuilder(512)
        out.append(VERSION).append('\n')
        out.append("allow_aod_dynamic=").append(flag(allowAodDynamic)).append('\n')
        out.append("aod_always_play=").append(flag(aodAlwaysPlay)).append('\n')
        out.append("aod_frame_pair=").append(flag(aodFramePair)).append('\n')
        out.append("aod_frame_photo=").append(aodFramePhoto).append('\n')
        out.append("aod_photo=").append(aodPhoto).append('\n')
        out.append("aod_style=").append(aodStyleId).append('\n')
        out.append("lock_style=").append(lockStyleId).append('\n')
        out.append("lock_wp=").append(lockWallpaperEffect).append('\n')
        out.append("lock_sp=").append(lockSpecialEffect).append('\n')
        out.append("home_transition=").append(homeTransition).append('\n')
        out.append("home_icon=").append(homeIconEffect).append('\n')
        out.append("home_follow_lock=").append(flag(homeFollowLock)).append('\n')
        out.append("home_aod_independent=").append(flag(homeAodIndependent)).append('\n')
        out.append("home_aod_style=").append(homeAodStyleId).append('\n')
        out.append("aod_display_mode=").append(displayMode.name.lowercase(Locale.ROOT)).append('\n')
        out.append("aod_schedule=").append(scheduleStartMinute).append('-').append(scheduleEndMinute).append('\n')
        out.append("aod_info_battery=").append(flag(aodInfoBattery)).append('\n')
        out.append("aod_info_notification=").append(flag(aodInfoNotificationIcon)).append('\n')
        out.append("aod_scene_info=").append(flag(aodSceneInfo)).append('\n')
        out.append("aod_new_notification=").append(flag(aodNewNotification)).append('\n')
        for ((key, value) in paramOverrides) {
            out.append("p.").append(key).append('=').append(value).append('\n')
        }
        return out.toString()
    }

    override fun toString(): String = toText()

    companion object {

        const val VERSION = "v1"
        const val DEFAULT_STYLE_ID = "aod.sail"

        /** id 上限：照片键存的是 SAF content URI，长路径编码后可达数百字符。 */
        const val MAX_ID_LEN = 512

        fun defaults(): EffectSettings = EffectSettings()

        /** 容错解析；空文本 / 非法内容一律回默认，不抛异常。 */
        fun parse(text: String?): EffectSettings {
            val settings = EffectSettings()
            if (text.isNullOrBlank()) return settings
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line == VERSION || (line.startsWith("v") && line.length <= 4 && line.drop(1).toIntOrNull() != null)) {
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                applyLine(settings, key, value)
            }
            return settings
        }

        private fun applyLine(settings: EffectSettings, key: String, value: String) {
            if (key.startsWith("p.")) {
                val paramId = key.substring(2)
                val parsed = value.toFloatOrNull() ?: return
                settings.setParam(paramId, parsed)
                return
            }
            when (key) {
                "allow_aod_dynamic" -> parseFlag(value)?.let { settings.allowAodDynamic = it }
                "aod_always_play" -> parseFlag(value)?.let { settings.aodAlwaysPlay = it }
                "aod_frame_pair" -> parseFlag(value)?.let { settings.aodFramePair = it }
                "aod_frame_photo" -> parseId(value)?.let { settings.aodFramePhoto = it }
                "aod_photo" -> parseId(value)?.let { settings.aodPhoto = it }
                "aod_style" -> parseId(value)?.let { settings.aodStyleId = it }
                "lock_style" -> parseId(value)?.let { settings.lockStyleId = it }
                "lock_wp" -> parseId(value)?.let { settings.lockWallpaperEffect = it }
                "lock_sp" -> parseId(value)?.let { settings.lockSpecialEffect = migrateLockSpecial(it) }
                "home_transition" -> parseId(value)?.let { settings.homeTransition = it }
                "home_icon" -> parseId(value)?.let { settings.homeIconEffect = it }
                "home_follow_lock" -> parseFlag(value)?.let { settings.homeFollowLock = it }
                "home_aod_independent" -> parseFlag(value)?.let { settings.homeAodIndependent = it }
                "home_aod_style" -> parseId(value)?.let { settings.homeAodStyleId = it }
                "aod_display_mode" -> parseDisplayMode(value)?.let { settings.displayMode = it }
                "aod_schedule" -> parseSchedule(value)?.let { (start, end) ->
                    settings.scheduleStartMinute = start
                    settings.scheduleEndMinute = end
                }
                "aod_info_battery" -> parseFlag(value)?.let { settings.aodInfoBattery = it }
                "aod_info_notification" -> parseFlag(value)?.let { settings.aodInfoNotificationIcon = it }
                "aod_scene_info" -> parseFlag(value)?.let { settings.aodSceneInfo = it }
                "aod_new_notification" -> parseFlag(value)?.let { settings.aodNewNotification = it }
            }
        }

        private fun flag(value: Boolean): String = if (value) "1" else "0"

        private fun parseFlag(value: String): Boolean? = when (value.lowercase(Locale.ROOT)) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }

        private fun parseId(value: String): String? {
            val id = value.trim()
            if (id.isEmpty() || id.length > MAX_ID_LEN) return null
            if (id.any { it == '\n' || it == '\r' || it == '=' }) return null
            return id
        }

        private val legacyLockSpecial: Map<String, String> = mapOf(
            "lock.sp.vivo.liquid" to EffectCatalog.LOCK_SP_LIQUID,
            "lock.sp.vivo.lenticular" to EffectCatalog.LOCK_SP_LENTICULAR,
            "lock.sp.vivo.flash_card" to EffectCatalog.LOCK_SP_FLASH_CARD,
        )

        private fun migrateLockSpecial(id: String): String = legacyLockSpecial[id] ?: id

        private fun parseDisplayMode(value: String): AodDisplayMode? = when (value.lowercase(Locale.ROOT)) {
            "always", "all_day", "allday" -> AodDisplayMode.ALWAYS
            "scheduled", "schedule", "timed" -> AodDisplayMode.SCHEDULED
            "power_save", "powersave", "eco" -> AodDisplayMode.POWER_SAVE
            else -> null
        }

        private fun parseSchedule(value: String): Pair<Int, Int>? {
            val parts = value.split('-')
            if (parts.size != 2) return null
            val start = parts[0].trim().toIntOrNull() ?: return null
            val end = parts[1].trim().toIntOrNull() ?: return null
            if (start !in 0..1439 || end !in 0..1439) return null
            return start to end
        }
    }
}
