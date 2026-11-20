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

    /** 前景深度：起点在屏幕前方（离观众近）故放大，落位收回原样。 */
    const val FOREGROUND_DEPTH = 0.35f

    /** 后景深度：起点在屏幕后方（离观众远）故缩小，落位放大到原样。 */
    const val BACKGROUND_DEPTH = 0.28f

    /** 旋转飞入起始角：从倾斜转正，配合过冲弹出。 */
    const val ROTATE_FLY_DEG = 90f

    /** 旋转飞入起始缩放：从小弹到原样并短暂过冲。 */
    const val ROTATE_FLY_START_SCALE = 0.3f

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

    /** 该效果是否逐图标错峰：只有旋转飞入依次弹出，其余三种整屏同相位。 */
    fun staggered(effectId: String): Boolean = effectId == EffectCatalog.HOME_ICON_ROTATE_FLY

    /**
     * 图标入场求值。
     *
     * 前景飞入从屏幕前方向后推入（起点放大），后景飞入从屏幕后方向前浮出（起点缩小），
     * 这两种与淡出淡入都是整屏一起动；旋转飞入逐个转着弹出。
     *
     * @param travelPercent 0..100，位移距离占屏高的百分比
     * @param staggerMs     相邻图标启动间隔，只对错峰效果生效
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
        val delay = if (staggered(effectId)) safeIndex * staggerMs.coerceAtLeast(0f) else 0f
        val local = ((elapsedMs - delay) / duration).coerceIn(0f, 1f)
        val travel = (travelPercent / 100f).coerceIn(0f, 1f)

        when (effectId) {
            EffectCatalog.HOME_ICON_FOREGROUND_FLY -> {
                val eased = Easing.DECELERATE.map(local)
                out.y = travel * (1f - eased)
                out.alpha = eased
                out.scale = 1f + FOREGROUND_DEPTH * (1f - eased)
            }

            EffectCatalog.HOME_ICON_BACKGROUND_FLY -> {
                val eased = Easing.DECELERATE.map(local)
                out.y = -travel * 0.6f * (1f - eased)
                out.alpha = eased
                out.scale = 1f - BACKGROUND_DEPTH * (1f - eased)
            }

            EffectCatalog.HOME_ICON_ROTATE_FLY -> {
                val pop = Easing.OVERSHOOT.map(local)
                out.rotationDeg = -ROTATE_FLY_DEG * (1f - pop)
                out.x = -travel * 0.3f * (1f - pop)
                out.alpha = Easing.DECELERATE.map(local)
                out.scale = ROTATE_FLY_START_SCALE + (1f - ROTATE_FLY_START_SCALE) * pop
            }

            else -> {
                out.alpha = Easing.DECELERATE.map(local)
            }
        }
    }
}
