package com.coloros16.liquidglass.engine.effect

import kotlin.math.roundToInt

/** 参数分组：高级设置页按组平铺。 */
enum class EffectParamGroup { AOD_MOTION, LOCK_WALLPAPER, LOCK_SPECIAL, HOME_TRANSITION, HOME_ICON }

/** 一条可调数值参数。 */
data class EffectParam(
    val id: String,
    val group: EffectParamGroup,
    val labelKey: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val step: Float = 1f,
)

/** 数值参数表：默认值、范围与夹紧的唯一来源。 */
object EffectParams {

    const val AOD_COLOR_MIX = "aod.color_mix"
    const val AOD_ROTATE_SPEED = "aod.rotate_speed"
    const val AOD_PHOTO_TILT = "aod.photo_tilt"
    const val AOD_POWER_SAVE_TIMEOUT = "aod.power_save_timeout"
    const val AOD_FRAME_X = "aod.frame_x"
    const val AOD_FRAME_Y = "aod.frame_y"
    const val AOD_FRAME_SIZE = "aod.frame_size"
    const val AOD_FRAME_ANGLE = "aod.frame_angle"
    const val LOCK_WP_SPEED = "lock.wp.speed"
    const val LOCK_WP_INTENSITY = "lock.wp.intensity"
    const val LOCK_SP_SPEED = "lock.sp.speed"
    const val LOCK_SP_INTENSITY = "lock.sp.intensity"
    const val LOCK_SP_GYRO_GAIN = "lock.sp.gyro_gain"
    const val HOME_TRANSITION_DURATION = "home.transition.duration_ms"
    const val HOME_ICON_DURATION = "home.icon.duration_ms"
    const val HOME_ICON_STAGGER = "home.icon.stagger_ms"
    const val HOME_ICON_TRAVEL = "home.icon.travel"

    val all: List<EffectParam> = listOf(
        EffectParam(AOD_COLOR_MIX, EffectParamGroup.AOD_MOTION, "effect_param_color_mix", 0f, 100f, 60f, 1f),
        EffectParam(AOD_ROTATE_SPEED, EffectParamGroup.AOD_MOTION, "effect_param_rotate_speed", 0f, 60f, 12f, 1f),
        EffectParam(AOD_PHOTO_TILT, EffectParamGroup.AOD_MOTION, "effect_param_photo_tilt", 0f, 15f, 6f, 0.5f),
        EffectParam(AOD_POWER_SAVE_TIMEOUT, EffectParamGroup.AOD_MOTION, "effect_param_power_save", 5f, 120f, 30f, 1f),
        EffectParam(AOD_FRAME_X, EffectParamGroup.AOD_MOTION, "effect_param_frame_x", 0f, 100f, 50f, 1f),
        EffectParam(AOD_FRAME_Y, EffectParamGroup.AOD_MOTION, "effect_param_frame_y", 0f, 100f, 50f, 1f),
        EffectParam(AOD_FRAME_SIZE, EffectParamGroup.AOD_MOTION, "effect_param_frame_size", 5f, 100f, 100f, 1f),
        EffectParam(AOD_FRAME_ANGLE, EffectParamGroup.AOD_MOTION, "effect_param_frame_angle", -45f, 45f, 0f, 1f),
        EffectParam(LOCK_WP_SPEED, EffectParamGroup.LOCK_WALLPAPER, "effect_param_lock_wp_speed", 0.2f, 3f, 1f, 0.1f),
        EffectParam(LOCK_WP_INTENSITY, EffectParamGroup.LOCK_WALLPAPER, "effect_param_lock_wp_intensity", 0f, 100f, 60f, 1f),
        EffectParam(LOCK_SP_SPEED, EffectParamGroup.LOCK_SPECIAL, "effect_param_lock_sp_speed", 0.2f, 3f, 1f, 0.1f),
        EffectParam(LOCK_SP_INTENSITY, EffectParamGroup.LOCK_SPECIAL, "effect_param_lock_sp_intensity", 0f, 100f, 60f, 1f),
        EffectParam(LOCK_SP_GYRO_GAIN, EffectParamGroup.LOCK_SPECIAL, "effect_param_gyro_gain", 0f, 200f, 100f, 5f),
        EffectParam(
            HOME_TRANSITION_DURATION, EffectParamGroup.HOME_TRANSITION,
            "effect_param_transition_duration", 200f, 1200f, 500f, 20f,
        ),
        EffectParam(HOME_ICON_DURATION, EffectParamGroup.HOME_ICON, "effect_param_icon_duration", 200f, 1200f, 420f, 20f),
        EffectParam(HOME_ICON_STAGGER, EffectParamGroup.HOME_ICON, "effect_param_icon_stagger", 0f, 120f, 28f, 2f),
        EffectParam(HOME_ICON_TRAVEL, EffectParamGroup.HOME_ICON, "effect_param_icon_travel", 0f, 100f, 60f, 5f),
    )

    private val byId: Map<String, EffectParam> = all.associateBy { it.id }

    fun find(id: String?): EffectParam? = if (id == null) null else byId[id]

    fun clamp(id: String, value: Float): Float {
        val spec = byId[id] ?: return value
        if (value.isNaN()) return spec.default
        return value.coerceIn(spec.min, spec.max)
    }

    /** 按步进取整并夹紧；用于滑杆回写，避免浮点尾巴。 */
    fun quantize(id: String, value: Float): Float {
        val spec = byId[id] ?: return value
        if (spec.step <= 0f) return clamp(id, value)
        val steps = ((clamp(id, value) - spec.min) / spec.step).roundToInt()
        return clamp(id, spec.min + steps * spec.step)
    }

    fun defaults(): Map<String, Float> = all.associate { it.id to it.default }
}
