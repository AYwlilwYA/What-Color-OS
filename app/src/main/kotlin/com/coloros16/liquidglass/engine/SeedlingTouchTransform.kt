package com.coloros16.liquidglass.engine

/**
 * 触摸热区与绘制变换同步（纯 JVM）：onDraw 对 body 施加平移(shakeX/dropY)+缩放(绕中心)，
 * 触摸命中需逆变换回 body 局部坐标，触摸区并集需正变换到屏幕坐标，否则动画期热区与视觉错位。
 */
object SeedlingTouchTransform {

    /** 屏幕触摸点→body 局部坐标（撤销平移+缩放，与绘制变换互逆）。baseLeft=岛左缘屏幕 px。 */
    fun toLocal(
        x: Float, y: Float,
        baseLeft: Float, islandW: Float, islandH: Float, islandTop: Float,
        shakeX: Float, dropY: Float, scale: Float,
    ): Pair<Float, Float> {
        val pivotX = baseLeft + shakeX + islandW / 2f
        val pivotY = islandTop + dropY + islandH / 2f
        val lx = if (scale != 1f) (x - pivotX) / scale + pivotX else x
        val ly = if (scale != 1f) (y - pivotY) / scale + pivotY else y
        return Pair(lx - shakeX, ly - dropY)
    }

    /** body 局部矩形→屏幕坐标（正变换，触摸区并集用）。返回 [left,top,right,bottom]。 */
    fun toScreen(
        left: Float, top: Float, right: Float, bottom: Float,
        baseLeft: Float, islandW: Float, islandH: Float, islandTop: Float,
        shakeX: Float, dropY: Float, scale: Float,
    ): FloatArray {
        val pivotX = baseLeft + shakeX + islandW / 2f
        val pivotY = islandTop + dropY + islandH / 2f
        fun sx(x: Float) = if (scale != 1f) (x + shakeX - pivotX) * scale + pivotX else x + shakeX
        fun sy(y: Float) = if (scale != 1f) (y + dropY - pivotY) * scale + pivotY else y + dropY
        return floatArrayOf(sx(left), sy(top), sx(right), sy(bottom))
    }
}
