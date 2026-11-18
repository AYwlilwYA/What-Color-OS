package com.coloros16.liquidglass.engine.edit

import com.coloros16.liquidglass.engine.ClockAnchor

/**
 * 编辑配置 → 时钟锚点：把用户拖出来的位置与缩放叠到转场锚点上。
 *
 * 位置是「替换」（用户放哪就在哪），缩放是「相乘」（转场缩放 × 用户缩放），
 * 透明度与旋转一律由转场决定，编辑不参与。
 */
object EditAnchorOverlay {

    /** 未编辑时的缺省位置：与 [LockLayoutSettings] 默认一致视为「不改」。 */
    fun isDefaultPosition(settings: LockLayoutSettings): Boolean =
        nearly(settings.clockX, 0.5f) && nearly(settings.clockY, defaultY(settings.surface))

    /** 未编辑时的缺省缩放。 */
    fun isDefaultScale(settings: LockLayoutSettings): Boolean = nearly(settings.clockScale, 1f)

    /** 该配置是否会改变锚点（全默认则整条链可短路）。 */
    fun active(settings: LockLayoutSettings): Boolean =
        !isDefaultPosition(settings) || !isDefaultScale(settings)

    /**
     * 叠加后的锚点；[base] 为转场/静止态锚点。
     *
     * 息屏纵向不随编辑改变（[EditAxis] 约束已在配置层生效，这里再兜一次）。
     */
    fun apply(base: ClockAnchor, settings: LockLayoutSettings): ClockAnchor {
        if (!active(settings)) return base
        val x = settings.clockX.coerceIn(0f, 1f)
        val y = if (settings.surface == EditSurface.AOD) {
            base.centerY
        } else {
            settings.clockY.coerceIn(0f, 1f)
        }
        val scale = base.scale * settings.clockScale
        return base.copy(centerX = x, centerY = y, scale = scale)
    }

    private fun defaultY(surface: EditSurface): Float =
        if (surface == EditSurface.AOD) {
            LockLayoutSettings.AOD_DEFAULT_CLOCK_Y
        } else {
            LockLayoutSettings.LOCK_DEFAULT_CLOCK_Y
        }

    private fun nearly(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < 0.0005f
}