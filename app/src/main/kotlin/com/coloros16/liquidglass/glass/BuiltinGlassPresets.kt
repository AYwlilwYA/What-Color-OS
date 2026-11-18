package com.coloros16.liquidglass.glass

import com.coloros16.liquidglass.config.Prefs

/**
 * 出厂风格预设（整页参数配方；classes 恒空 = 不绑类，选中后由用户勾选生效对象）。
 * 参数在 tools/glass-web-preview 网页渲染器（与 AGSL 同算法）上对照
 * iOS 26 真机帧与鸿蒙沉浸光感官方图逐轮调出；证据与截图见 doc/investigations 预设调参篇。
 */
object BuiltinGlassPresets {

    /** id 前缀：内置条目与用户 pN 序号命名空间隔离。 */
    const val ID_PREFIX = "builtin_"

    fun isBuiltin(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** 套用预设时默认全开的玻璃对象开关（不含全局总闸与诊断/探针类）。 */
    val OBJECT_SWITCH_KEYS = listOf(
        Prefs.KEY_VOLUME_SEEKBAR_GLASS,
        Prefs.KEY_VOLUME_PANEL_CLEAN,
        Prefs.KEY_CONTROL_PROGRESS_FILL,
        Prefs.KEY_NOTIFICATION_POPUP_GLASS,
        Prefs.KEY_POPUP_MENU_GLASS_ENABLE,
        Prefs.KEY_CLEAR_ALL_BTN_GLASS,
        Prefs.KEY_EDIT_TOOLBAR_GLASS,
        Prefs.KEY_EDIT_TOOLBTN_GLASS,
        Prefs.KEY_DOCK_GLASS_ENABLE,
        Prefs.KEY_CARD_GLASS_ENABLE,
        Prefs.KEY_PAGE_INDICATOR_GLASS,
        Prefs.KEY_SEEDLING_CARD_GLASS_ENABLE,
        Prefs.KEY_KEYGUARD_GLASS,
        Prefs.KEY_KEYGUARD_GLASS_CLOCK,
        Prefs.KEY_KEYGUARD_GLASS_DATE,
        Prefs.KEY_KEYGUARD_GLASS_CARDS,
        Prefs.KEY_KEYGUARD_GLASS_SHORTCUTS,
        Prefs.KEY_GALLERY_GLASS,
        Prefs.KEY_CALCULATOR_GLASS,
        Prefs.KEY_SMART_SIDEBAR_GLASS,
        Prefs.KEY_ASSISTANT_SCREEN_GLASS,
        Prefs.KEY_QUICK_SEARCH_GLASS,
    )

    /** 出厂五档（顺序即 UI 展示顺序）。 */
    fun all(): List<GlassRegistry.Preset> = listOf(
        preset("ios_clear", "iOS 通透", mapOf(
            Prefs.KEY_BLUR_RADIUS to 0f,
            Prefs.KEY_REFRACTION_AMOUNT to 48f,
            Prefs.KEY_REFRACTION_HEIGHT_RATIO to 0.12f,
            Prefs.KEY_DEPTH to 0.45f,
            Prefs.KEY_DISPERSION to 0.05f,
            Prefs.KEY_HIGHLIGHT to 0.42f,
            Prefs.KEY_HIGHLIGHT_WIDTH to 5f,
            Prefs.KEY_HIGHLIGHT_FALLOFF to 2f,
            Prefs.KEY_HIGHLIGHT_FLOOR to 0.25f,
            Prefs.KEY_HIGHLIGHT_INNER_ATT to 0.55f,
            Prefs.KEY_HIGHLIGHT_ENHANCE to 1f,
            Prefs.KEY_INNER_GLOW to 0.14f,
            Prefs.KEY_VIBRANCY to 1.55f,
            Prefs.KEY_LIGHT_ANGLE to 51f,
            Prefs.KEY_MASK_GLASS_DIM to 0.05f,
            Prefs.KEY_EDGE_STROKE to 0f,
            Prefs.KEY_MASK_ENABLE to 1f,
            Prefs.KEY_MASK_ALPHA to 0.45f,
            Prefs.KEY_MASK_DIM_FOLLOW_MASK to 1f,
            Prefs.KEY_TEXT_CONTRAST_ENABLE to 1f,
            Prefs.KEY_TEXT_FORCE_WHITE_ENABLE to 1f,
        )),
        preset("ios_frost", "iOS 磨砂", mapOf(
            Prefs.KEY_BLUR_RADIUS to 14f,
            Prefs.KEY_REFRACTION_AMOUNT to 56f,
            Prefs.KEY_REFRACTION_HEIGHT_RATIO to 0.2f,
            Prefs.KEY_DEPTH to 0.45f,
            Prefs.KEY_DISPERSION to 0f,
            Prefs.KEY_HIGHLIGHT to 0.42f,
            Prefs.KEY_HIGHLIGHT_WIDTH to 6f,
            Prefs.KEY_HIGHLIGHT_FALLOFF to 2f,
            Prefs.KEY_HIGHLIGHT_FLOOR to 0.3f,
            Prefs.KEY_HIGHLIGHT_INNER_ATT to 0.35f,
            Prefs.KEY_HIGHLIGHT_ENHANCE to 1f,
            Prefs.KEY_INNER_GLOW to 0.12f,
            Prefs.KEY_VIBRANCY to 1.5f,
            Prefs.KEY_LIGHT_ANGLE to 51f,
            Prefs.KEY_MASK_GLASS_DIM to 0.03f,
            Prefs.KEY_EDGE_STROKE to 0f,
            Prefs.KEY_MASK_ENABLE to 1f,
            Prefs.KEY_MASK_ALPHA to 0.45f,
            Prefs.KEY_MASK_DIM_FOLLOW_MASK to 1f,
            Prefs.KEY_TEXT_CONTRAST_ENABLE to 1f,
            Prefs.KEY_TEXT_FORCE_WHITE_ENABLE to 1f,
        )),
        preset("harmony_soft", "鸿蒙 柔光", mapOf(
            Prefs.KEY_BLUR_RADIUS to 20f,
            Prefs.KEY_REFRACTION_AMOUNT to 30f,
            Prefs.KEY_REFRACTION_HEIGHT_RATIO to 0.45f,
            Prefs.KEY_DEPTH to 0.3f,
            Prefs.KEY_DISPERSION to 0f,
            Prefs.KEY_HIGHLIGHT to 0.12f,
            Prefs.KEY_HIGHLIGHT_WIDTH to 10f,
            Prefs.KEY_HIGHLIGHT_FALLOFF to 1.2f,
            Prefs.KEY_HIGHLIGHT_FLOOR to 0.5f,
            Prefs.KEY_HIGHLIGHT_INNER_ATT to 0f,
            Prefs.KEY_HIGHLIGHT_ENHANCE to 0f,
            Prefs.KEY_INNER_GLOW to 0.22f,
            Prefs.KEY_VIBRANCY to 1.12f,
            Prefs.KEY_LIGHT_ANGLE to 90f,
            Prefs.KEY_MASK_GLASS_DIM to 0.04f,
            Prefs.KEY_EDGE_STROKE to 0f,
            Prefs.KEY_MASK_ENABLE to 1f,
            Prefs.KEY_MASK_ALPHA to 0.3f,
            Prefs.KEY_MASK_DIM_FOLLOW_MASK to 1f,
            Prefs.KEY_TEXT_CONTRAST_ENABLE to 1f,
            Prefs.KEY_TEXT_FORCE_WHITE_ENABLE to 0f,
        )),
        preset("crystal", "水晶 色散", mapOf(
            Prefs.KEY_BLUR_RADIUS to 0f,
            Prefs.KEY_REFRACTION_AMOUNT to 90f,
            Prefs.KEY_REFRACTION_HEIGHT_RATIO to 0.3f,
            Prefs.KEY_DEPTH to 0.8f,
            Prefs.KEY_DISPERSION to 0.22f,
            Prefs.KEY_HIGHLIGHT to 0.55f,
            Prefs.KEY_HIGHLIGHT_WIDTH to 10f,
            Prefs.KEY_HIGHLIGHT_FALLOFF to 3f,
            Prefs.KEY_HIGHLIGHT_FLOOR to 0.2f,
            Prefs.KEY_HIGHLIGHT_INNER_ATT to 0.2f,
            Prefs.KEY_HIGHLIGHT_ENHANCE to 1f,
            Prefs.KEY_INNER_GLOW to 0.05f,
            Prefs.KEY_VIBRANCY to 1.65f,
            Prefs.KEY_LIGHT_ANGLE to 45f,
            Prefs.KEY_MASK_GLASS_DIM to 0f,
            Prefs.KEY_EDGE_STROKE to 0.1f,
            Prefs.KEY_EDGE_STROKE_WIDTH to 2f,
            Prefs.KEY_MASK_ENABLE to 1f,
            Prefs.KEY_MASK_ALPHA to 0.35f,
            Prefs.KEY_MASK_DIM_FOLLOW_MASK to 1f,
            Prefs.KEY_TEXT_CONTRAST_ENABLE to 1f,
            Prefs.KEY_TEXT_FORCE_WHITE_ENABLE to 1f,
        )),
        preset("classic_frost", "经典 磨砂", mapOf(
            Prefs.KEY_BLUR_RADIUS to 24f,
            Prefs.KEY_REFRACTION_AMOUNT to 0f,
            Prefs.KEY_REFRACTION_HEIGHT_RATIO to 0.5f,
            Prefs.KEY_DEPTH to 0f,
            Prefs.KEY_DISPERSION to 0f,
            Prefs.KEY_HIGHLIGHT to 0.25f,
            Prefs.KEY_HIGHLIGHT_WIDTH to 6f,
            Prefs.KEY_HIGHLIGHT_FALLOFF to 2f,
            Prefs.KEY_HIGHLIGHT_FLOOR to 0.5f,
            Prefs.KEY_HIGHLIGHT_INNER_ATT to 0f,
            Prefs.KEY_HIGHLIGHT_ENHANCE to 0f,
            Prefs.KEY_INNER_GLOW to 0f,
            Prefs.KEY_VIBRANCY to 1.2f,
            Prefs.KEY_LIGHT_ANGLE to 90f,
            Prefs.KEY_MASK_GLASS_DIM to 0.1f,
            Prefs.KEY_EDGE_STROKE to 0f,
            Prefs.KEY_MASK_ENABLE to 1f,
            Prefs.KEY_MASK_ALPHA to 0.5f,
            Prefs.KEY_MASK_DIM_FOLLOW_MASK to 1f,
            Prefs.KEY_TEXT_CONTRAST_ENABLE to 1f,
            Prefs.KEY_TEXT_FORCE_WHITE_ENABLE to 1f,
        )),
    )

    /** 缺失的内置条目补进现有列表（按 id 判重；用户改过的同名条目不覆盖）。 */
    fun seedInto(existing: List<GlassRegistry.Preset>): List<GlassRegistry.Preset> {
        val ids = existing.mapTo(HashSet()) { it.id }
        val missing = all().filter { it.id !in ids }
        return if (missing.isEmpty()) existing else existing + missing
    }

    private fun preset(slug: String, name: String, params: Map<String, Float>) =
        GlassRegistry.Preset(ID_PREFIX + slug, name, emptyList(), params)
}