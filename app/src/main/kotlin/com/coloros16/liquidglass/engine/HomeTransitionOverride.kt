package com.coloros16.liquidglass.engine

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectParams
import com.coloros16.liquidglass.engine.effect.EffectSettings
import com.coloros16.liquidglass.engine.effect.HomeMotion

/**
 * 桌面过渡选项覆盖接缝配方：用户主动选了才覆盖，默认不接管保持既有配方。
 *
 * 息屏直接进桌面那条边由息屏样式决定，不受本选项控制。
 */
object HomeTransitionOverride {

    /**
     * 该边是否受桌面过渡选项控制：只锁屏↔桌面两条。
     *
     * 需求把本选项写成「锁屏到桌面过渡」，含息屏的四条边归各自样式。
     */
    fun governs(edge: TransitionEdge): Boolean =
        edge == TransitionEdge.LOCK_TO_HOME || edge == TransitionEdge.HOME_TO_LOCK

    /**
     * 取该边要覆盖的配方；不接管或选项为「无」时返回 null。
     *
     * 返回 null 表示保持调用方原有配方，不是「清空配方」。
     */
    fun profileFor(edge: TransitionEdge, settings: EffectSettings): TransitionProfile? {
        if (!governs(edge)) return null
        val id = HomeMotion.transitionForEdge(edge, settings)
        if (id == EffectCatalog.HOME_TRANSITION_NONE) return null
        return HomeMotion.profileFor(id)
    }

    /**
     * 该边时长：只有覆盖生效时才用选项时长，否则返回 0 表示交回原时长。
     *
     * 时长不写进配方，由调用方的转场时钟消费。
     */
    fun durationMs(edge: TransitionEdge, settings: EffectSettings): Long {
        if (profileFor(edge, settings) == null) return 0L
        return settings.param(EffectParams.HOME_TRANSITION_DURATION).toLong().coerceAtLeast(0L)
    }
}