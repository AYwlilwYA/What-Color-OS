package com.coloros16.liquidglass.engine.edit

/** 一条可添加的小组件定义；[nameKey] 由 UI 侧提供文案。 */
data class WidgetDef(
    val id: String,
    val nameKey: String,
    val sizes: List<WidgetSize> = DEFAULT_SIZES,
    val defaultSize: WidgetSize = WidgetSize.SPAN_1X1,
    val defaultX: Float = 0.75f,
    val defaultY: Float = 0.35f,
) {
    companion object {
        val DEFAULT_SIZES: List<WidgetSize> = listOf(WidgetSize.SPAN_1X1, WidgetSize.SPAN_2X2, WidgetSize.SPAN_1X4)
    }
}

/** 可添加小组件目录：id 稳定，列表可继续追加。 */
object WidgetCatalog {

    const val WEATHER = "weather"
    const val MUSIC = "music"
    const val BATTERY = "battery"
    const val STEPS = "steps"

    val all: List<WidgetDef> = listOf(
        WidgetDef(WEATHER, "lock_widget_weather", defaultX = 0.78f, defaultY = 0.28f),
        WidgetDef(MUSIC, "lock_widget_music", defaultSize = WidgetSize.SPAN_1X4, defaultX = 0.5f, defaultY = 0.62f),
        WidgetDef(BATTERY, "lock_widget_battery", defaultX = 0.78f, defaultY = 0.4f),
        WidgetDef(STEPS, "lock_widget_steps", defaultX = 0.78f, defaultY = 0.5f),
    )

    private val byId: Map<String, WidgetDef> = all.associateBy { it.id }

    fun find(id: String?): WidgetDef? = if (id == null) null else byId[id]

    fun isKnown(id: String?): Boolean = find(id) != null

    /** 按目录默认值创建一个小组件；未知 id 返回 null。 */
    fun create(id: String?, enabled: Boolean = true): LockWidget? {
        val def = find(id) ?: return null
        return LockWidget(
            typeId = def.id,
            enabled = enabled,
            x = def.defaultX,
            y = def.defaultY,
            size = def.defaultSize,
        )
    }
}
