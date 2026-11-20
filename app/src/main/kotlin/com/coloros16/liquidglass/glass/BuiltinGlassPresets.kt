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

    /** params 里以 0/1 浮点编码、落全局时要写回 Boolean 的键（hook 侧按 getBoolean 读）。 */
    val BOOLEAN_PARAM_KEYS = setOf(
        Prefs.KEY_MASK_ENABLE,
        Prefs.KEY_MASK_DIM_FOLLOW_MASK,
        Prefs.KEY_TEXT_CONTRAST_ENABLE,
        Prefs.KEY_TEXT_FORCE_WHITE_ENABLE,
    )

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

    /** 出厂四档（顺序即 UI 展示顺序）。 */
    fun all(): List<GlassRegistry.Preset> = listOf(
        preset("ios", "iOS", mapOf(
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

    /**
     * 风格的跨功能配套参数（顶层一键套用时连画质/抓屏与倾斜透视一起改）：
     * iOS 双档开动态倾斜（iOS 观感的一部分），其余风格关；画质按风格开销分档。
     * Int 键单列（写入走 writePrefInt），Float 键与 params 同路。
     */
    data class Companion(
        val ints: Map<String, Int> = emptyMap(),
        val floats: Map<String, Float> = emptyMap(),
        val bools: Map<String, Boolean> = emptyMap(),
    )

    /**
     * 鸿蒙官方弹簧真值 → 桌面动画参数（LauncherAnimHook 键空间）。
     * ArkUI springMotion 无参默认 response=0.55s / dampingFraction=0.825（规格文档+System API 双源）；
     * 官方问答弹性起调推荐 (0.6, 0.8)。刚度=(2π/response)²（COUISpringForce.setResponse 同款换算），
     * 阻尼比×100 取整（hook 运行时 ÷100）。打开/打断用推荐弹性档，关闭用系统默认档稍稳。
     */
    fun harmonySpringInts(): Map<String, Int> {
        fun stiffness(responseS: Double): Int =
            ((2.0 * Math.PI / responseS) * (2.0 * Math.PI / responseS)).toInt()
        val openStiffness = stiffness(0.6)
        val closeStiffness = stiffness(0.55)
        val out = LinkedHashMap<String, Int>()
        for (group in Prefs.ANIM_GROUPS) {
            val close = group == Prefs.ANIM_GROUP_CLOSE
            val stiff = if (close) closeStiffness else openStiffness
            val damping = if (close) 83 else 80
            for (param in Prefs.ANIM_SPRING_PARAMS) {
                out[Prefs.animKey(group, param)] =
                    if (param.endsWith("_damping")) damping else stiff
            }
            out[Prefs.animKey(group, Prefs.ANIM_PARAM_FADE_DURATION)] = if (close) 420 else 340
        }
        return out
    }

    /**
     * 性能与稳定性键：抓屏帧率/采样/间隔、倾斜强度与透视等一律**不由风格改动**。
     * 这些默认值调坏会让本来正常的场景失效，风格只管观感。
     */
    val PERFORMANCE_KEYS = setOf(
        Prefs.KEY_PANEL_CAPTURE_HZ,
        Prefs.KEY_BG_CAPTURE_SCALE,
        Prefs.KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS,
        Prefs.KEY_BG_PERIODIC_CAPTURE_ENABLE,
        Prefs.KEY_ANIM_TILT_STRENGTH,
        Prefs.KEY_ANIM_TILT_PERSPECTIVE,
    )

    fun companionOf(id: String): Companion = when (id.removePrefix(ID_PREFIX)) {
        "ios", "crystal" -> Companion(
            bools = mapOf(Prefs.KEY_ANIM_TILT_ENABLED to true),
        )
        "harmony_soft" -> Companion(
            ints = harmonySpringInts(),
            bools = Prefs.ANIM_GROUPS.associate { Prefs.animEnabledKey(it) to true },
        )
        else -> Companion()
    }

    /**
     * 按当前全局键值推断命中的风格档（与画质档 currentPresetTier 同口径）：
     * 配方里每个键都相等才算命中；用户手改任一键后不匹配任何档 = 返回 null。
     * [valueOf] 由调用方注入读键实现（Float 键读 float、BOOLEAN_PARAM_KEYS 读 boolean 转 0/1）。
     */
    fun matchCurrent(valueOf: (String) -> Float?): String? {
        for (p in all()) {
            val hit = p.params.all { (key, want) ->
                val got = valueOf(key) ?: return@all false
                kotlin.math.abs(got - want) < 1e-4f
            }
            if (hit) return p.id
        }
        return null
    }

    /** 已下线的内置 id：存量条目直接丢弃，避免列表里留死档。 */
    private val RETIRED_IDS = setOf(ID_PREFIX + "ios_clear", ID_PREFIX + "ios_frost")

    /** 清掉已下线内置条目 + 补齐缺失内置条目（按 id 判重；用户改过的同名条目不覆盖）。 */
    fun seedInto(existing: List<GlassRegistry.Preset>): List<GlassRegistry.Preset> {
        val kept = existing.filterNot { it.id in RETIRED_IDS }
        val ids = kept.mapTo(HashSet()) { it.id }
        val missing = all().filter { it.id !in ids }
        return kept + missing
    }

    private fun preset(slug: String, name: String, params: Map<String, Float>) =
        GlassRegistry.Preset(ID_PREFIX + slug, name, emptyList(), params)
}