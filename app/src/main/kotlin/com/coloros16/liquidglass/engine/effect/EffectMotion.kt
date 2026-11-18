package com.coloros16.liquidglass.engine.effect

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** 息屏动态求值结果；复用实例，逐帧不分配。 */
class AodMotionState {
    var active: Boolean = false
    var rotationDeg: Float = 0f
    var colorMix: Float = 0f
    var photoTiltDeg: Float = 0f

    fun reset() {
        active = false
        rotationDeg = 0f
        colorMix = 0f
        photoTiltDeg = 0f
    }
}

/**
 * 息屏动态：启航混色 / 旋转 / 照片倾斜。
 *
 * 「允许 AOD 动态变化」为 false 时全部冻结为静态首帧（旋转 0、混色 0、倾斜 0），
 * 与需求「总开关控制是否允许 aod 动态变化」一致。
 */
object AodMotion {

    private const val COLOR_MIX_PERIOD_MS = 8000f
    private const val TILT_PERIOD_MS = 9000f

    fun evaluate(
        timeMs: Long,
        allowDynamic: Boolean,
        colorMixPercent: Float,
        rotateSpeedDegPerSec: Float,
        photoTiltDeg: Float,
        out: AodMotionState,
    ) {
        out.reset()
        if (!allowDynamic || timeMs <= 0L) return
        val seconds = timeMs / 1000f
        out.active = true
        out.rotationDeg = wrapDegrees(rotateSpeedDegPerSec * seconds)
        val mixAmount = (colorMixPercent / 100f).coerceIn(0f, 1f)
        val mixPhase = tau(timeMs / COLOR_MIX_PERIOD_MS)
        out.colorMix = (mixAmount * (0.5f + 0.5f * sin(mixPhase))).coerceIn(0f, 1f)
        val tiltAmount = photoTiltDeg.coerceIn(0f, 15f)
        out.photoTiltDeg = tiltAmount * sin(tau(timeMs / TILT_PERIOD_MS))
    }

    private fun wrapDegrees(value: Float): Float {
        val mod = value % 360f
        return if (mod < 0f) mod + 360f else mod
    }

    private fun tau(cycles: Float): Float = 2f * PI.toFloat() * cycles
}

/** 锁屏壁纸效果求值结果；复用实例，逐帧不分配。 */
class WallpaperEffectState {
    var effectId: String = ""
    var active: Boolean = false
    var fallback: Boolean = false
    var scale: Float = 1f
    var rotationDeg: Float = 0f
    var offsetX: Float = 0f
    var offsetY: Float = 0f

    /** 模糊半径，按屏幕短边比例（0..0.08），渲染侧乘短边。 */
    var blurFraction: Float = 0f

    /** 循环相位 0..1，渲染侧自行映射到纹理/色相。 */
    var phase: Float = 0f

    /** 条纹/网格密度 0..1。 */
    var density: Float = 0f

    /** 强度 0..1。 */
    var intensity: Float = 0f

    /** true 表示渲染侧应按预处理贴图绘制，动态量只做贴图位移/缩放。 */
    var textureBased: Boolean = false

    fun reset() {
        effectId = ""
        active = false
        fallback = false
        scale = 1f
        rotationDeg = 0f
        offsetX = 0f
        offsetY = 0f
        blurFraction = 0f
        phase = 0f
        density = 0f
        intensity = 0f
        textureBased = false
    }
}

/**
 * 锁屏壁纸效果：11 种并入一套求值，输出统一姿态参数。
 *
 * 只算「该怎么动」，不规定画法；渲染侧按 [WallpaperEffectState.phase] / [WallpaperEffectState.density]
 * 映射到自己的纹理或色相即可。未知 id 置 fallback 并归零，调用方按「无」处理。
 */
object LockWallpaperMotion {

    fun evaluate(
        effectId: String,
        timeMs: Long,
        speed: Float,
        intensityPercent: Float,
        out: WallpaperEffectState,
    ) {
        out.reset()
        out.effectId = effectId
        out.textureBased = EffectCatalog.isTextureBased(effectId)
        val k = (intensityPercent / 100f).coerceIn(0f, 1f)
        val rate = speed.coerceIn(0.2f, 3f)
        val t = (timeMs.coerceAtLeast(0L) / 1000f) * rate
        when (effectId) {
            EffectCatalog.LOCK_WP_NONE -> Unit

            EffectCatalog.LOCK_WP_RAINBOW -> {
                out.active = true
                out.phase = frac(t / 10f)
                out.intensity = k
                out.density = k
                out.scale = 1f + 0.012f * k * sinTau(frac(t / 7f))
            }

            EffectCatalog.LOCK_WP_WAVE -> {
                out.active = true
                out.phase = frac(t / 6f)
                out.intensity = k
                out.offsetY = 0.028f * k * sinTau(out.phase)
                out.offsetX = 0.012f * k * cosTau(out.phase)
                out.scale = 1f + 0.02f * k
            }

            EffectCatalog.LOCK_WP_FOG -> {
                out.active = true
                out.phase = frac(t / 14f)
                out.intensity = k
                out.offsetX = 0.02f * k * sinTau(out.phase)
                out.offsetY = 0.012f * k * cosTau(out.phase)
                out.blurFraction = 0.05f * k * (0.6f + 0.4f * sinTau(frac(t / 9f)))
            }

            EffectCatalog.LOCK_WP_BLUR -> {
                out.active = true
                out.intensity = k
                out.blurFraction = 0.02f + 0.05f * k
                out.scale = 1.01f
            }

            EffectCatalog.LOCK_WP_STRIPES_FINE -> {
                out.active = true
                out.phase = frac(t / 4f)
                out.density = k
                out.intensity = k
                out.rotationDeg = -8f * k
            }

            EffectCatalog.LOCK_WP_STRIPES_COARSE -> {
                out.active = true
                out.phase = frac(t / 7f)
                out.density = 0.45f * k
                out.intensity = k
                out.rotationDeg = 6f * k
            }

            EffectCatalog.LOCK_WP_LIGHT_STRIPES -> {
                out.active = true
                out.phase = frac(t / 5f)
                out.density = k
                out.intensity = k
                out.rotationDeg = 12f * k * sinTau(out.phase)
            }

            EffectCatalog.LOCK_WP_GRID -> {
                out.active = true
                out.phase = frac(t / 8f)
                out.density = 0.6f * k
                out.intensity = k
                out.offsetX = 0.01f * k * sinTau(out.phase)
            }

            EffectCatalog.LOCK_WP_WATER -> {
                out.active = true
                out.phase = frac(t / 5.5f)
                out.intensity = k
                out.scale = 1f + 0.04f * k * (0.5f + 0.5f * sinTau(out.phase))
                out.offsetY = 0.01f * k * cosTau(out.phase)
            }

            EffectCatalog.LOCK_WP_RIPPLE -> {
                out.active = true
                out.phase = frac(t / 3.5f)
                out.intensity = k
                out.scale = 1f + 0.02f * k
                out.offsetY = 0.008f * k * sinTau(out.phase)
            }

            else -> out.fallback = true
        }
    }

    private fun frac(value: Float): Float {
        val f = value - floor(value)
        return if (f < 0f) f + 1f else f
    }

    private fun sinTau(phase: Float): Float = sin(2f * PI.toFloat() * phase)

    private fun cosTau(phase: Float): Float = cos(2f * PI.toFloat() * phase)
}
