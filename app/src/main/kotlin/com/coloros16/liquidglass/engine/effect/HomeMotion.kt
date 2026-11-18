package com.coloros16.liquidglass.engine.effect

import com.coloros16.liquidglass.engine.ContentLayerConfig
import com.coloros16.liquidglass.engine.Easing
import com.coloros16.liquidglass.engine.MaskLayerConfig
import com.coloros16.liquidglass.engine.SceneLayerConfig
import com.coloros16.liquidglass.engine.SlideDirection
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionProfile

/** 单个桌面图标入场的姿态；复用实例，逐帧不分配。 */
class IconEntranceState {
    /** 横向偏移，屏幕宽比例（调用方乘屏宽）。 */
    var x: Float = 0f

    /** 纵向偏移，屏幕高比例（调用方乘屏高）。 */
    var y: Float = 0f
    var scale: Float = 1f
    var alpha: Float = 1f
    var rotationDeg: Float = 0f

    fun reset() {
        x = 0f
        y = 0f
        scale = 1f
        alpha = 1f
        rotationDeg = 0f
    }
}

/**
 * 桌面侧效果：锁屏→桌面过渡选择 + 图标入场。
 *
 * 图标入场按 index 错峰，位置/偏移一律用「屏高/屏宽比例」，纯 JVM 不拿屏幕尺寸。
 */
object HomeMotion {

    /**
     * 过渡选择：
     * - `AOD_TO_HOME` 按需求「aod 直接进桌面就用 aod 那边样式」，返回息屏样式 id；
     * - 其余边按桌面设置（`home.transition.none` = 不接管，保持系统）。
     */
    fun transitionForEdge(edge: TransitionEdge, settings: EffectSettings): String = when (edge) {
        TransitionEdge.AOD_TO_HOME -> settings.aodStyleId
        else -> settings.homeTransition
    }

    /**
     * 桌面过渡配方：`none` 返回 null（不接管）；`crossfade` 用内容层淡出淡入。
     * 时长不写进配方，由调用方的转场时钟按 `home.transition.duration_ms` 使用。
     */
    fun profileFor(transitionId: String): TransitionProfile? = when (transitionId) {
        EffectCatalog.HOME_TRANSITION_CROSSFADE -> TransitionProfile(
            scene = SceneLayerConfig(enabled = false),
            mask = MaskLayerConfig(enabled = false),
            content = ContentLayerConfig(
                enabled = true,
                easing = Easing.STANDARD,
                direction = SlideDirection.NONE,
                slideFraction = 0f,
            ),
        )
        else -> null
    }

    /**
     * 图标入场求值。
     *
     * @param travelPercent 0..100，位移距离占屏高的百分比
     * @param staggerMs     相邻图标启动间隔
     */
    fun iconEntrance(
        effectId: String,
        index: Int,
        count: Int,
        elapsedMs: Long,
        durationMs: Float,
        staggerMs: Float,
        travelPercent: Float,
        out: IconEntranceState,
    ) {
        out.reset()
        val duration = if (durationMs <= 0f) 1f else durationMs
        val safeCount = count.coerceAtLeast(1)
        val safeIndex = index.coerceIn(0, safeCount - 1)
        val delay = safeIndex * staggerMs.coerceAtLeast(0f)
        val local = ((elapsedMs - delay) / duration).coerceIn(0f, 1f)
        val eased = Easing.DECELERATE.map(local)
        val travel = (travelPercent / 100f).coerceIn(0f, 1f)

        when (effectId) {
            EffectCatalog.HOME_ICON_FOREGROUND_FLY -> {
                out.y = travel * (1f - eased)
                out.alpha = eased
                out.scale = 0.92f + 0.08f * eased
            }

            EffectCatalog.HOME_ICON_BACKGROUND_FLY -> {
                out.y = -travel * 0.6f * (1f - eased)
                out.alpha = eased
                out.scale = 1.08f - 0.08f * eased
            }

            EffectCatalog.HOME_ICON_ROTATE_FLY -> {
                out.rotationDeg = -25f * (1f - eased)
                out.x = -travel * 0.3f * (1f - eased)
                out.alpha = eased
                out.scale = 0.9f + 0.1f * eased
            }

            else -> {
                out.alpha = eased
            }
        }
    }
}
