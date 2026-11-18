package com.coloros16.liquidglass.engine.edit

import java.util.Locale

/**
 * 锁屏 / 息屏编辑配置（纯 JVM）。
 *
 * 位置一律归一化比例，两轴都可拖动；缩放范围由 [MIN_CLOCK_SCALE] / [MAX_CLOCK_SCALE] 约束。
 * 持久化用版本化文本，未知键忽略、非法值回退默认。
 */
class LockLayoutSettings(val surface: EditSurface = EditSurface.LOCK) {

    var clock: ClockStyle = ClockStyle()
    var clockX: Float = 0.5f
    var clockY: Float = if (surface == EditSurface.AOD) AOD_DEFAULT_CLOCK_Y else LOCK_DEFAULT_CLOCK_Y
    var clockScale: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
        }
    var snapEnabled: Boolean = true

    private val widgetMap: MutableMap<String, LockWidget> = LinkedHashMap()
    private val pillMap: MutableMap<String, PillPlacement> = LinkedHashMap()
    private val pillContentMap: MutableMap<String, String> = LinkedHashMap()

    fun pillContent(slotId: String): String = pillContentMap[slotId] ?: slotId

    fun setPillContent(slotId: String, contentId: String): Boolean {
        if (PillType.from(slotId) == null || PillType.from(contentId) == null) return false
        pillContentMap[slotId] = contentId
        return true
    }

    init {
        if (surface == EditSurface.LOCK) {
            setPill(PillPlacement(PillType.FLASHLIGHT.id, x = 0.2f, y = 0.88f))
            setPill(PillPlacement(PillType.CAMERA.id, x = 0.8f, y = 0.88f))
        }
    }

    fun widgets(): List<LockWidget> = widgetMap.values.toList()

    fun widget(typeId: String): LockWidget? = widgetMap[typeId]

    /** 添加或更新小组件；未知类型拒绝并返回 false。 */
    fun setWidget(widget: LockWidget): Boolean {
        val def = WidgetCatalog.find(widget.typeId) ?: return false
        val fixed = if (def.sizes.contains(widget.size)) widget else widget.copy(size = def.defaultSize)
        widgetMap[widget.typeId] = fixed.sanitized()
        return true
    }

    fun removeWidget(typeId: String): Boolean = widgetMap.remove(typeId) != null

    fun setWidgetEnabled(typeId: String, enabled: Boolean): Boolean {
        val current = widgetMap[typeId] ?: return false
        widgetMap[typeId] = current.copy(enabled = enabled)
        return true
    }

    fun widgetsOf(size: WidgetSize): List<LockWidget> = widgetMap.values.filter { it.size == size }

    fun pills(): List<PillPlacement> = pillMap.values.toList()

    fun pill(typeId: String): PillPlacement? = pillMap[typeId]

    fun setPill(placement: PillPlacement): Boolean {
        if (PillType.from(placement.typeId) == null) return false
        pillMap[placement.typeId] = placement.sanitized()
        return true
    }

    /** 拖动时钟；按编辑面轴约束采纳结果，FREE 两轴都写回。 */
    fun moveClock(x: Float, y: Float, threshold: Float = SnapEngine.DEFAULT_THRESHOLD): SnapResult {
        val targetY = if (surface.axis == EditAxis.FREE) y else clockY
        val result = SnapEngine.snap(x, targetY, SnapEngine.guides(surface), surface.axis, threshold)
        clockX = result.x
        if (surface.axis == EditAxis.FREE) clockY = result.y
        return result
    }

    fun toText(): String {
        val out = StringBuilder(512)
        out.append(VERSION).append('\n')
        val style = clock.sanitized()
        out.append("clock.style=").append(style.styleId).append('\n')
        out.append("clock.x=").append(clockX).append('\n')
        out.append("clock.y=").append(clockY).append('\n')
        out.append("clock.scale=").append(clockScale).append('\n')
        out.append("clock.font=").append(style.fontFamily).append('\n')
        out.append("clock.weight=").append(style.weight).append('\n')
        out.append("clock.letter_spacing=").append(style.letterSpacing).append('\n')
        out.append("clock.color=").append(formatColor(style.colorArgb)).append('\n')
        out.append("clock.glass_tint=").append(flag(style.glassTint)).append('\n')
        out.append("clock.info=").append(style.info.name.lowercase(Locale.ROOT)).append('\n')
        out.append("snap=").append(flag(snapEnabled)).append('\n')
        for (widget in widgetMap.values) {
            val prefix = "w." + widget.typeId + "."
            out.append(prefix).append("on=").append(flag(widget.enabled)).append('\n')
            out.append(prefix).append("x=").append(widget.x).append('\n')
            out.append(prefix).append("y=").append(widget.y).append('\n')
            out.append(prefix).append("size=").append(WidgetSize.token(widget.size)).append('\n')
        }
        for (pill in pillMap.values) {
            val prefix = pill.typeId + "."
            out.append(prefix).append("on=").append(flag(pill.enabled)).append('\n')
            out.append(prefix).append("x=").append(pill.x).append('\n')
            out.append(prefix).append("y=").append(pill.y).append('\n')
            out.append(prefix).append("scale=").append(pill.scale).append('\n')
            // 快捷入口内容也要落盘：只写摆放会让换过的内容重进即回退
            pillContentMap[pill.typeId]?.let {
                out.append(prefix).append("content=").append(it).append('\n')
            }
        }
        return out.toString()
    }

    override fun toString(): String = toText()

    companion object {

        const val VERSION = "v1"
        const val LOCK_DEFAULT_CLOCK_Y = 0.28f
        const val AOD_DEFAULT_CLOCK_Y = 0.45f
        const val MIN_CLOCK_SCALE = 0.6f
        const val MAX_CLOCK_SCALE = 1.6f

        fun defaults(surface: EditSurface): LockLayoutSettings = LockLayoutSettings(surface)

        fun parse(text: String?, surface: EditSurface): LockLayoutSettings {
            val settings = LockLayoutSettings(surface)
            if (text.isNullOrBlank()) return settings
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line == VERSION || (line.startsWith("v") && line.length <= 4 && line.drop(1).toIntOrNull() != null)) {
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                applyLine(settings, line.substring(0, eq).trim(), line.substring(eq + 1).trim())
            }
            settings.clock = settings.clock.sanitized()
            settings.clockX = settings.clockX.coerceIn(0f, 1f)
            settings.clockY = if (surface == EditSurface.AOD) {
                AOD_DEFAULT_CLOCK_Y
            } else {
                settings.clockY.coerceIn(0f, 1f)
            }
            settings.clockScale = settings.clockScale
            return settings
        }

        private fun applyLine(settings: LockLayoutSettings, key: String, value: String) {
            when {
                key.startsWith("w.") -> applyWidgetLine(settings, key, value)
                key.startsWith("pill.") -> applyPillLine(settings, key, value)
                else -> applyClockLine(settings, key, value)
            }
        }

        private fun applyClockLine(settings: LockLayoutSettings, key: String, value: String) {
            when (key) {
                "clock.style" -> parseToken(value)?.let { settings.clock = settings.clock.copy(styleId = it) }
                "clock.x" -> value.toFloatOrNull()?.let { settings.clockX = it }
                "clock.y" -> value.toFloatOrNull()?.let { settings.clockY = it }
                "clock.scale" -> value.toFloatOrNull()?.let { settings.clockScale = it }
                "clock.font" -> parseToken(value)?.let { settings.clock = settings.clock.copy(fontFamily = it) }
                "clock.weight" -> value.toIntOrNull()?.let { settings.clock = settings.clock.copy(weight = it) }
                "clock.letter_spacing" -> value.toFloatOrNull()
                    ?.let { settings.clock = settings.clock.copy(letterSpacing = it) }
                "clock.color" -> parseColor(value)?.let { settings.clock = settings.clock.copy(colorArgb = it) }
                "clock.glass_tint" -> parseFlag(value)
                    ?.let { settings.clock = settings.clock.copy(glassTint = it) }
                "clock.info" -> parseClockInfo(value)?.let { settings.clock = settings.clock.copy(info = it) }
                "snap" -> parseFlag(value)?.let { settings.snapEnabled = it }
            }
        }

        private fun applyWidgetLine(settings: LockLayoutSettings, key: String, value: String) {
            val rest = key.substring(2)
            val dot = rest.lastIndexOf('.')
            if (dot <= 0) return
            val typeId = rest.substring(0, dot)
            val field = rest.substring(dot + 1)
            if (!WidgetCatalog.isKnown(typeId)) return
            var widget = settings.widgetMap[typeId] ?: WidgetCatalog.create(typeId) ?: return
            when (field) {
                "on" -> parseFlag(value)?.let { widget = widget.copy(enabled = it) }
                "x" -> value.toFloatOrNull()?.let { widget = widget.copy(x = it) }
                "y" -> value.toFloatOrNull()?.let { widget = widget.copy(y = it) }
                "size" -> WidgetSize.fromToken(value)?.let { widget = widget.copy(size = it) }
            }
            settings.setWidget(widget)
        }

        private fun applyPillLine(settings: LockLayoutSettings, key: String, value: String) {
            val dot = key.lastIndexOf('.')
            if (dot <= 0) return
            val typeId = key.substring(0, dot)
            val field = key.substring(dot + 1)
            if (PillType.from(typeId) == null) return
            var pill = settings.pillMap[typeId] ?: return
            when (field) {
                "on" -> parseFlag(value)?.let { pill = pill.copy(enabled = it) }
                "x" -> value.toFloatOrNull()?.let { pill = pill.copy(x = it) }
                "y" -> value.toFloatOrNull()?.let { pill = pill.copy(y = it) }
                "scale" -> value.toFloatOrNull()?.let { pill = pill.copy(scale = it) }
                "content" -> settings.setPillContent(typeId, value)
            }
            settings.setPill(pill)
        }

        private fun flag(value: Boolean): String = if (value) "1" else "0"

        private fun parseFlag(value: String): Boolean? = when (value.lowercase(Locale.ROOT)) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }

        private fun parseToken(value: String): String? =
            value.trim().takeIf { ClockStyle.validToken(it) }

        private fun parseClockInfo(value: String): ClockInfo? = when (value.lowercase(Locale.ROOT)) {
            "none", "off" -> ClockInfo.NONE
            "lunar", "农历" -> ClockInfo.LUNAR
            "weather", "天气" -> ClockInfo.WEATHER
            else -> null
        }

        private fun parseColor(value: String): Int? {
            val text = value.trim()
            if (!text.startsWith("#")) return null
            val hex = text.substring(1)
            val parsed = hex.toLongOrNull(16) ?: return null
            return when (hex.length) {
                6 -> (0xFF000000L or parsed).toInt()
                8 -> parsed.toInt()
                else -> null
            }
        }

        private fun formatColor(argb: Int): String = "#%08X".format(argb)
    }
}
