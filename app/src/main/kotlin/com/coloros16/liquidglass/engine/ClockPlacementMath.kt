package com.coloros16.liquidglass.engine

/**
 * 时钟落位的像素级反算：把视图几何与已写入的变换折回「屏幕上实际中心」。
 *
 * 纯 JVM，供诊断日志与契约测共用；预览与 hook 的落位口径差异靠它逐项对账，
 * 不靠肉眼估。pivot 取视图中心，故缩放不改变中心，中心只由平移决定。
 */
object ClockPlacementMath {

    /** 屏幕上实际中心（像素）：视图本地中心 + 父容器屏幕偏移 + 平移。 */
    fun screenCenter(
        viewLeft: Float,
        viewTop: Float,
        viewWidth: Float,
        viewHeight: Float,
        parentScreenX: Float,
        parentScreenY: Float,
        translationX: Float,
        translationY: Float,
    ): FloatArray = floatArrayOf(
        parentScreenX + viewLeft + viewWidth * 0.5f + translationX,
        parentScreenY + viewTop + viewHeight * 0.5f + translationY,
    )

    /** 锚点期望的屏幕中心（像素）。 */
    fun targetCenter(centerX: Float, centerY: Float, screenW: Float, screenH: Float): FloatArray =
        floatArrayOf(centerX * screenW, centerY * screenH)

    /** 由实际中心与期望中心之差反推「等效锚点 y」，用于判断差值来自锚点还是几何。 */
    fun impliedAnchorY(screenCenterY: Float, screenH: Float): Float =
        if (screenH <= 0f) 0f else screenCenterY / screenH

    /**
     * 漂移能否解释纵向差值：差值绝对值超过漂移幅度即**不可能**只由漂移造成。
     *
     * [observedMinusExpectedPx] 为实测中心减期望中心（负值表示实测偏上）。
     */
    fun driftCanExplain(observedMinusExpectedPx: Float, screenH: Float): Boolean =
        kotlin.math.abs(observedMinusExpectedPx) <= AodDrift.AMPLITUDE * screenH + 0.5f

    /** 漂移能给出的最大像素位移（单侧）。 */
    fun driftMaxPx(screenH: Float): Float = AodDrift.AMPLITUDE * screenH

    /**
     * 亮像素包围盒中心 vs 视图中心：字形在视图内不一定居中，
     * 故包围盒中心与视图中心可以不等，差值即字形在视图内的偏心。
     */
    fun glyphOffsetInBox(
        boxLeft: Float,
        boxTop: Float,
        boxRight: Float,
        boxBottom: Float,
        viewCenterX: Float,
        viewCenterY: Float,
    ): FloatArray = floatArrayOf(
        (boxLeft + boxRight) * 0.5f - viewCenterX,
        (boxTop + boxBottom) * 0.5f - viewCenterY,
    )
}