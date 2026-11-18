package com.coloros16.liquidglass.engine

/**
 * 点按位置发光包络：按下快速点亮、按住恒亮、松手缓出淡灭。
 * 纯 JVM 时间驱动求值，预览与 hook 共用同一份曲线；调用方只存事件时刻，不存进度。
 */
object PressGlow {

    /** 按下点亮时长。 */
    const val ATTACK_MS = 90L

    /** 松手淡灭时长。 */
    const val RELEASE_MS = 320L

    /** 单元素发光状态（坐标 = 元素局部 px；时刻 = uptimeMillis）。 */
    data class State(
        val downAtMs: Long,
        val upAtMs: Long?,
        val x: Float,
        val y: Float,
    )

    /** 当前发光强度 0~1：按住段 ease-out 点亮到 1；松手段从松手瞬间值三次缓出到 0；到期恒 0。 */
    fun intensity(state: State?, nowMs: Long): Float {
        if (state == null) return 0f
        val up = state.upAtMs
        if (up == null || nowMs <= up) return attack((nowMs - state.downAtMs).coerceAtLeast(0L))
        val atRelease = attack((up - state.downAtMs).coerceAtLeast(0L))
        val t = ((nowMs - up).toFloat() / RELEASE_MS).coerceIn(0f, 1f)
        val remain = 1f - t
        return atRelease * remain * remain * remain
    }

    /** 状态是否已彻底结束（可清表）。 */
    fun finished(state: State, nowMs: Long): Boolean =
        state.upAtMs != null && nowMs - state.upAtMs >= RELEASE_MS

    /** 光斑半径 px：随元素短边收敛，小元素不过曝、大元素不成点。 */
    fun radiusPx(shortSidePx: Float): Float =
        (shortSidePx * 0.45f).coerceIn(48f, 240f)

    private fun attack(elapsedMs: Long): Float {
        val t = (elapsedMs.toFloat() / ATTACK_MS).coerceIn(0f, 1f)
        val remain = 1f - t
        return 1f - remain * remain
    }
}
