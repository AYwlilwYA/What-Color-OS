package com.coloros16.liquidglass.engine

import com.coloros16.liquidglass.engine.edit.ClockLayout
import com.coloros16.liquidglass.engine.edit.EditAnchorOverlay
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings

/**
 * 样式时钟锚点 + 用户编辑配置 → 状态机锚点。
 *
 * 位置逐轴替换：该轴仍是缺省值就落在样式锚点，编辑过就取用户值；
 * 缩放与两轴拉伸相乘：样式给基线大小，用户再按比例调。
 * 没有样式锚点时基线取缺省位与原样大小，等价于纯编辑配置。
 */
object ClockAnchorCompose {

    /** 无样式锚点时的基线（缺省位 + 原样大小）。 */
    fun baseOf(surface: EditSurface): ClockAnchor = ClockAnchor(
        centerX = EditAnchorOverlay.defaultX(),
        centerY = EditAnchorOverlay.defaultYOf(surface),
    )

    /** 合成锚点；[base] 为 null 表示该样式没有世界契约。 */
    fun compose(base: ClockAnchor?, settings: LockLayoutSettings): ClockAnchor {
        val b = base ?: baseOf(settings.surface)
        return ClockAnchor(
            centerX = ClockLayout.configToVisual(
                settings.clockX, b.centerX, EditAnchorOverlay.defaultX(),
            ),
            centerY = ClockLayout.configToVisual(
                settings.clockY, b.centerY, EditAnchorOverlay.defaultYOf(settings.surface),
            ),
            scale = b.scale * settings.clockScale,
            alpha = b.alpha,
            rotationDeg = b.rotationDeg,
            stretchX = b.stretchX * settings.clockStretchX,
            stretchY = b.stretchY * settings.clockStretchY,
        )
    }
}
