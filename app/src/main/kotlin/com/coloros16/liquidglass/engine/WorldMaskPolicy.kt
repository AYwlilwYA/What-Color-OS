package com.coloros16.liquidglass.engine

/**
 * 世界层与几何遮罩的分工：世界层自己画满整屏时，几何遮罩不再叠一层压暗。
 *
 * 世界层各分支都已把当前压暗量画进自己的像素（镜片铺底后画压暗矩形、
 * 照片类在 shader 里混夜色、手绘铺底后画压暗矩形、天空收尾画压暗矩形），
 * 再叠一层同量遮罩会把该处压暗两次。
 */
object WorldMaskPolicy {

    /**
     * 该分支是否自带整屏压暗（自带则几何遮罩必须让位）。
     *
     * NONE 表示没有世界层，压暗仍由遮罩或整屏压暗承担。
     */
    fun ownsDim(branch: SkyBranchPolicy.Branch): Boolean = when (branch) {
        SkyBranchPolicy.Branch.LENS -> true
        SkyBranchPolicy.Branch.PHOTO -> true
        SkyBranchPolicy.Branch.SKY -> true
        SkyBranchPolicy.Branch.HANDPAINT -> true
        SkyBranchPolicy.Branch.NONE -> false
    }

    /**
     * 几何遮罩是否该画：只有世界层没画成时才画。
     *
     * [worldDrawn] 是世界层本帧的返回值，不是「该样式有世界层」——
     * 素材未就绪时世界层返回 false，此时必须回落到遮罩路径，否则整屏没有揭开效果。
     * 色键帧档的形状来自素材，故该档也不画几何遮罩。
     */
    fun veilDrawable(
        worldDrawn: Boolean,
        maskEnabled: Boolean,
        radiusDrawable: Boolean,
        maskByFrame: Boolean,
    ): Boolean = !worldDrawn && !maskByFrame && maskEnabled && radiusDrawable

    /**
     * 整屏压暗是否该画：世界层画成或遮罩已承担时都不画。
     *
     * 色键帧档的压暗由 shader 承担，故该档也不画。
     */
    fun dimDrawable(worldDrawn: Boolean, maskByFrame: Boolean, veilDrawn: Boolean): Boolean =
        !worldDrawn && !maskByFrame && !veilDrawn
}
