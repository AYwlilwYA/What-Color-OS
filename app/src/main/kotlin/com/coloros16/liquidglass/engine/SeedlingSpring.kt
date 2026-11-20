package com.coloros16.liquidglass.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 接管岛统一弹簧积分器（纯 JVM，App 预览与 hook 共用）。
 * 半隐式欧拉 + 2ms 子步；收敛判据=位置epsilon+速度epsilon；超时直落。
 */
class SeedlingSpring(
    initial: Float = 0f,
    target: Float = initial,
    var stiffness: Float = 380f,
    var dampingRatio: Float = 0.62f,
    var posEpsilon: Float = 0.35f,
    var velEpsilon: Float = 8f,
    var timeoutMs: Long = 2000L,
) {
    var value: Float = initial
        private set
    var velocity: Float = 0f
        private set
    var target: Float = target
        private set
    private var elapsedMs: Float = 0f

    val settled: Boolean
        get() = abs(value - target) < posEpsilon && abs(velocity) < velEpsilon

    fun setTarget(t: Float) {
        if (t != target) elapsedMs = 0f
        target = t
    }

    fun snap(v: Float) {
        value = v
        target = v
        velocity = 0f
        elapsedMs = 0f
    }

    fun shift(delta: Float) {
        value += delta
        target += delta
    }

    fun fineTune() {
        posEpsilon = 0.004f
        velEpsilon = 0.05f
    }

    /** 推进 dtSec（帧间隔），返回是否仍活跃（未收敛）。 */
    fun advance(dtSec: Float): Boolean {
        if (settled) {
            value = target
            velocity = 0f
            return false
        }
        elapsedMs += dtSec * 1000f
        if (elapsedMs >= timeoutMs) {
            snap(target)
            return false
        }
        var remaining = dtSec.coerceIn(0f, MAX_FRAME_SEC)
        val c = 2f * dampingRatio * sqrt(stiffness)
        while (remaining > 0f) {
            val h = min(remaining, SUB_STEP_SEC)
            val a = -stiffness * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            remaining -= h
        }
        if (settled) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }

    companion object {
        const val MAX_FRAME_SEC = 0.064f
        const val SUB_STEP_SEC = 0.002f

        /** 回弹档位 dp → 阻尼比：{0,1,2,5,8} → {1.0,0.8,0.75,0.62,0.42}，档间线性插值，clamp [0,8]。 */
        fun bounceDpToDamping(bounceDp: Float): Float {
            val b = bounceDp.coerceIn(0f, 8f)
            val stops = floatArrayOf(0f, 1f, 2f, 5f, 8f)
            val zetas = floatArrayOf(1.0f, 0.8f, 0.75f, 0.62f, 0.42f)
            for (i in 0 until stops.size - 1) {
                if (b <= stops[i + 1]) {
                    val t = (b - stops[i]) / (stops[i + 1] - stops[i])
                    return zetas[i] + (zetas[i + 1] - zetas[i]) * t
                }
            }
            return zetas.last()
        }

        /** k = (speed × animatorScale)² × 基准刚度；speed clamp [0.4,2.0]。 */
        fun scaledStiffness(base: Float, speed: Float, animatorScale: Float): Float {
            val s = speed.coerceIn(0.4f, 2.0f) * max(animatorScale, 0f)
            return base * s * s
        }

        /** timeout = 2000 / clamp(speed, 0.1, 1)。 */
        fun scaledTimeoutMs(speed: Float): Long =
            (2000f / speed.coerceIn(0.1f, 1f)).toLong()
    }
}

/** 岛矩形四通道弹簧组（x/y/w/h 各一颗，同参数）。 */
class SeedlingRectSpring(
    left: Float = 0f,
    top: Float = 0f,
    width: Float = 0f,
    height: Float = 0f,
    stiffness: Float = 380f,
    dampingRatio: Float = 0.62f,
) {
    val x = SeedlingSpring(left, left, stiffness, dampingRatio)
    val y = SeedlingSpring(top, top, stiffness, dampingRatio)
    val w = SeedlingSpring(width, width, stiffness, dampingRatio)
    val h = SeedlingSpring(height, height, stiffness, dampingRatio)

    fun setTarget(left: Float, top: Float, width: Float, height: Float) {
        x.setTarget(left)
        y.setTarget(top)
        w.setTarget(width)
        h.setTarget(height)
    }

    fun snap(left: Float, top: Float, width: Float, height: Float) {
        x.snap(left)
        y.snap(top)
        w.snap(width)
        h.snap(height)
    }

    /** 返回是否仍有活跃通道（帧泵据此决定要不要下一帧）。 */
    fun advance(dtSec: Float): Boolean {
        var active = x.advance(dtSec)
        active = y.advance(dtSec) || active
        active = w.advance(dtSec) || active
        active = h.advance(dtSec) || active
        return active
    }
}