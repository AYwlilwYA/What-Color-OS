package com.coloros16.liquidglass.engine

/**
 * 世界层分支选择：镜片 → 照片类 → 程序化天空 → 无世界层。
 *
 * 预览与 hook 共用同一判定，避免两侧优先级漂移导致同一样式画出不同世界。
 */
object SkyBranchPolicy {

    enum class Branch { LENS, PHOTO, SKY, HANDPAINT, NONE }

    fun branchOf(lens: Boolean, hasPhoto: Boolean, sky: Boolean): Branch = when {
        lens -> Branch.LENS
        hasPhoto -> Branch.PHOTO
        sky -> Branch.SKY
        else -> Branch.NONE
    }

    /** 带手绘的分支判定：手绘优先级在天空之前、照片类之后。 */
    fun branchOf(lens: Boolean, hasPhoto: Boolean, sky: Boolean, handPaintStyle: Int): Branch =
        when {
            lens -> Branch.LENS
            hasPhoto -> Branch.PHOTO
            handPaintStyle > 0 -> Branch.HANDPAINT
            sky -> Branch.SKY
            else -> Branch.NONE
        }

    /** 月球位姿缺失时不画，不自造位姿。 */
    fun moonDrawable(hasPose: Boolean, layerAlpha: Float, minAlpha: Float): Boolean =
        hasPose && layerAlpha > minAlpha
}