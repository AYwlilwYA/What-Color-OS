package com.coloros16.liquidglass.engine

/**
 * 三态舞台在 SystemUI 里的宿主锚点：资源 id 名、类全名、类名后缀。
 *
 * 只存字符串常量、无平台类型依赖；hook 侧按名解析，离线单测按名核对。
 */
object AodStageAnchors {

    const val PKG_SYSTEMUI = "com.android.systemui"

    const val ID_AOD_ROOT = "aod_off_layout"
    const val ID_LOCK_ROOT = "keyguard_root_view"
    const val ID_LOCK_CLOCK = "keyguard_style_clock"
    const val ID_BOTTOM_AREA = "keyguard_bottom_area"
    const val ID_START_BUTTON = "start_button"
    const val ID_END_BUTTON = "end_button"

    const val CLASS_AOD_ROOT = "com.oplus.systemui.aod.aodclock.off.AodRootLayout"
    const val CLASS_AOD_BASE = "com.oplus.systemui.aod.aodclock.off.BaseRootLayout"
    const val CLASS_SHADE_ROOT = "com.android.systemui.shade.NotificationShadeWindowView"

    const val SUFFIX_AOD_CLOCK = ".AodClockLayout"
    const val SUFFIX_LOCK_CLOCK = ".OplusKeyguardStyleClock"

    /** 息屏时钟容器类名后缀：宿主容器与真实时钟 View 分离时代之以容器。 */
    const val SUFFIX_AOD_CLOCK_INNER = ".ClockLayout"

    const val ID_AOD_CLOCK_ROOT = "clock_root_view"
    const val ID_AOD_THEME_LAYOUT = "aod_global_theme_layout"
    const val ID_AOD_CLOCK_LAYOUT = "clock_layout"
    const val ID_AOD_DEFAULT_CLOCK = "aod_default_clock_view"
    const val ID_AOD_ANALOG_CLOCK = "aod_analog_clock_view"

    /**
     * 息屏时钟锚点候选 id 名，按优先级排列。
     *
     * 时钟内容可能由插件提供（类名不可控），故优先按 id 认容器，类名后缀只作兜底。
     */
    val AOD_CLOCK_IDS = arrayOf(
        ID_AOD_CLOCK_LAYOUT, ID_AOD_DEFAULT_CLOCK, ID_AOD_ANALOG_CLOCK,
        ID_AOD_THEME_LAYOUT, ID_AOD_CLOCK_ROOT,
    )

    /** 安全输入视图类名后缀：命中且可见即撤效果。 */
    val SECURE_SUFFIXES = arrayOf(
        ".KeyguardSecurityContainer",
        ".KeyguardPasswordView",
        ".KeyguardPinBasedInputView",
        ".KeyguardPatternView",
        ".FingerprintTipsView",
    )

    /** 全部资源 id 名，供契约核对遍历。 */
    val IDS = arrayOf(
        ID_AOD_ROOT, ID_LOCK_ROOT, ID_LOCK_CLOCK,
        ID_BOTTOM_AREA, ID_START_BUTTON, ID_END_BUTTON,
    )

    /** 全部类全名，供契约核对遍历。 */
    val CLASSES = arrayOf(CLASS_AOD_ROOT, CLASS_AOD_BASE, CLASS_SHADE_ROOT)

    /** 类名是否属于息屏根布局（决定该舞台归 AOD 面还是 LOCK 面）。 */
    fun isAodRoot(className: String): Boolean =
        className == CLASS_AOD_ROOT || className == CLASS_AOD_BASE

    /** 按舞台类名判面：息屏根布局 = AOD，其余（窗口根）= LOCK。 */
    fun surfaceOfStage(className: String): SceneState =
        if (isAodRoot(className)) SceneState.AOD else SceneState.LOCK

    /** 类名后缀是否命中安全输入视图。 */
    fun isSecureInput(className: String): Boolean =
        SECURE_SUFFIXES.any { className.endsWith(it) }
}