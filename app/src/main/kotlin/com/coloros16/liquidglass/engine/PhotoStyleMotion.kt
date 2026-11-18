package com.coloros16.liquidglass.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/** 照片类息屏样式的一条通道补间：时长、起始延迟与曲线。 */
class PhotoTrack(val durationMs: Long, val delayMs: Long, val easing: Easing)

/**
 * 一套照片类息屏样式的静态参数与逐通道补间表。
 * 通道顺序：模糊、旋转、缩放、内容缩放、锚点 x、锚点 y、视频微秒、照片不透明度。
 */
class PhotoStyle internal constructor(
    val id: Int,
    val folder: String,
    val size: Int,
    val frames: Int,
    val fps: Int,
    val endMicros: Int,
    val rotation: Float,
    val blur: Float,
    val scale: Float,
    val content: Float,
    private val durations: LongArray,
    private val tracks: Array<Array<PhotoTrack?>>,
) {

    companion object {
        const val SLOT_BLUR = 0
        const val SLOT_ROTATION = 1
        const val SLOT_SCALE = 2
        const val SLOT_CONTENT = 3
        const val SLOT_X = 4
        const val SLOT_Y = 5
        const val SLOT_VIDEO = 6
        const val SLOT_PHOTO_ALPHA = 7

        /** 展开终态：方形网格边长按短边的该倍数放大。 */
        const val EXPANDED_SCALE = 2.35f

        /** 息屏态首次展开前的停顿。 */
        const val OPEN_DELAY_MS = 150L
    }

    internal fun track(transition: Int, slot: Int): PhotoTrack? =
        if (transition in 0..3) tracks[transition][slot] else null

    /** 交叠比率（息屏/锁屏/桌面）的补间时长。 */
    fun blendDuration(transition: Int): Long {
        val own = durations[transition.coerceIn(0, durations.lastIndex)]
        val common = if (transition < 4) 400L else 500L
        return if (transition < 4 || id != 1) maxOf(own, common) else common
    }

    /** 视频时间 → 帧序号；等于帧数为起始帧、帧数+1 为结束帧。 */
    fun frameIndex(micros: Float): Int = when {
        micros <= 0f -> frames
        micros >= endMicros -> frames + 1
        else -> (micros * fps / 1_000_000f).roundToInt().coerceIn(0, frames - 1)
    }

    /** 帧序号 → 素材文件名（不含扩展名）。 */
    fun frameName(index: Int): String = when {
        index <= 0 -> "000"
        index >= frames + 1 -> "end"
        index >= frames -> "start"
        else -> String.format(java.util.Locale.ROOT, "%03d", index)
    }
}

/** 五款照片类息屏样式的逐帧真值，预览与 hook 共用。 */
object PhotoStyleMotion {

    private val EASE = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
    private val LINEAR = Easing.LINEAR
    private val DEFAULT: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    private fun track(durationMs: Long, delayMs: Long, x1: Float, y1: Float, x2: Float, y2: Float) =
        PhotoTrack(durationMs, delayMs, CubicBezierEasing(x1, y1, x2, y2))

    /** 逐帧采样结果：八个通道 + 三态交叠比率。 */
    class Values {
        val v = FloatArray(8)
        val ratios = FloatArray(3)

        fun reset() {
            v.fill(0f)
            ratios.fill(0f)
        }
    }

    private fun build(
        id: Int,
        folder: String,
        size: Int,
        frames: Int,
        fps: Int,
        end: Int,
        rotation: Float,
        blur: Float,
        scale: Float,
        content: Float,
        durations: LongArray,
    ): PhotoStyle {
        val tracks = Array(4) { arrayOfNulls<PhotoTrack>(8) }
        for (tr in 0 until 4) {
            val open = tr == 0 || tr == 2
            fun set(t: PhotoTrack, vararg slots: Int) {
                for (s in slots) tracks[tr][s] = t
            }
            when (id) {
                1 -> {
                    set(if (open) track(517, 50, 0.28f, 0f, 0.33f, 1f) else track(450, 0, 0.28f, 0f, 0.33f, 1f), 2, 4, 5)
                    set(if (open) track(167, 0, 0.33f, 0f, 0.67f, 1f) else track(450, 0, 0.28f, 0f, 0.33f, 1f), 1)
                }

                2 -> {
                    set(if (open) track(367, 0, 0.32f, 0.01f, 0.1f, 1f) else track(467, 176, 0.32f, 0.01f, 0.1f, 1f), 4, 5)
                    set(if (open) track(367, 0, 0.32f, 0.03f, 0.1f, 0.99f) else track(567, 176, 0.32f, -0.04f, 0.1f, 1.01f), 2)
                    set(
                        if (open) track(if (tr == 0) 533 else 553, 0, 0.37f, 0.01f, 0.34f, 1f)
                        else track(600, 0, 0.33f, 0f, 0.67f, 1f),
                        0, 3,
                    )
                }

                3 -> {
                    set(if (open) track(250, 133, 0.06f, 0.12f, 0.25f, 1f) else track(433, 200, 0.25f, 0f, 0.25f, 1f), 4, 5)
                    set(if (open) track(267, 133, 0.06f, 0.12f, 0.25f, 1f) else track(533, 200, 0.25f, 0f, 0.25f, 1f), 2)
                    set(if (open) track(717, 0, 0.17f, 0f, 0.25f, 1f) else track(667, 0, 0.33f, 0f, 0.67f, 1f), 3)
                    set(if (open) track(717, 0, 0.25f, 0f, 0.25f, 1f) else track(667, 0, 0.33f, 0f, 0.67f, 1f), 0)
                }

                4 -> {
                    set(if (open) track(483, 0, 0.25f, 0f, 0.25f, 1f) else track(433, 217, 0.14f, 0f, 0.33f, 0.95f), 4, 5)
                    set(if (open) track(483, 0, 0.25f, 0f, 0.25f, 1f) else track(467, 217, 0.14f, 0f, 0.67f, 1f), 2)
                    set(track(if (open) 750 else 667, 0, 0.25f, 0f, 0.25f, 1f), 0, 3)
                }

                5 -> {
                    set(if (open) track(450, 383, 0.25f, 0f, 0.25f, 1f) else track(433, 267, 0.14f, 0f, 0.1f, 1f), 4, 5)
                    set(if (open) track(517, 317, 0.25f, 0f, 0.25f, 1f) else track(533, 267, 0.14f, 0f, 0.1f, 1f), 2)
                    set(track(600, if (open) 350 else 0, 0.25f, 0f, 0.25f, 1f), 0, 3)
                }
            }
        }
        return PhotoStyle(id, folder, size, frames, fps, end, rotation, blur, scale, content, durations, tracks)
    }

    private val styles = listOf(
        build(1, "leave_light", 720, 61, 120, 483667, -5f, 0f, 0.70486f, 1f, longArrayOf(567, 530, 567, 530, 800, 400)),
        build(2, "moon", 720, 32, 60, 516667, 0f, 22f, 0.986f, 2.34f, longArrayOf(533, 743, 553, 743, 800, 400)),
        build(3, "door", 720, 41, 60, 616667, 0f, 100f, 0.917f, 2.34f, longArrayOf(717, 733, 717, 733, 800, 400)),
        build(4, "mountain", 1080, 43, 60, 716667, 0f, 100f, 0.833f, 2.34f, longArrayOf(750, 684, 750, 684, 800, 400)),
        build(5, "galaxy", 1080, 56, 60, 916667, 0f, 100f, 0.833f, 2.34f, longArrayOf(950, 800, 950, 800, 800, 400)),
    )

    private val byId: Map<Int, PhotoStyle> = styles.associateBy { it.id }

    fun byId(id: Int): PhotoStyle? = byId[id]

    fun transitionOf(edge: TransitionEdge): Int = when (edge) {
        TransitionEdge.AOD_TO_LOCK -> 0
        TransitionEdge.LOCK_TO_AOD -> 1
        TransitionEdge.AOD_TO_HOME -> 2
        TransitionEdge.HOME_TO_AOD -> 3
        TransitionEdge.LOCK_TO_HOME -> 4
        TransitionEdge.HOME_TO_LOCK -> 5
    }

    fun fromOf(edge: TransitionEdge): SceneState = when (edge) {
        TransitionEdge.AOD_TO_LOCK, TransitionEdge.AOD_TO_HOME -> SceneState.AOD
        TransitionEdge.LOCK_TO_AOD, TransitionEdge.LOCK_TO_HOME -> SceneState.LOCK
        TransitionEdge.HOME_TO_AOD, TransitionEdge.HOME_TO_LOCK -> SceneState.HOME
    }

    fun toOf(edge: TransitionEdge): SceneState = when (edge) {
        TransitionEdge.AOD_TO_LOCK, TransitionEdge.HOME_TO_LOCK -> SceneState.LOCK
        TransitionEdge.AOD_TO_HOME, TransitionEdge.LOCK_TO_HOME -> SceneState.HOME
        TransitionEdge.LOCK_TO_AOD -> SceneState.AOD
        TransitionEdge.HOME_TO_AOD -> SceneState.AOD
    }

    /** 该边的整段时长（含首次展开的停顿），最短通道与交叠一起收口。 */
    fun durationMs(style: PhotoStyle, edge: TransitionEdge): Long {
        val tr = transitionOf(edge)
        var end = style.blendDuration(tr)
        for (slot in 0 until 8) {
            val spec = style.track(tr, slot)
            var duration = spec?.durationMs ?: 400L
            val delay = spec?.delayMs ?: 0L
            if (slot == PhotoStyle.SLOT_VIDEO) duration = style.endMicros / 1000L
            if (slot == PhotoStyle.SLOT_PHOTO_ALPHA && tr == 4) duration = 800L
            end = maxOf(end, delay + duration)
        }
        return (if (fromOf(edge) == SceneState.AOD) PhotoStyle.OPEN_DELAY_MS else 0L) + end
    }

    /** 静态三态：息屏跟随时钟锚点，锁屏/桌面铺满整屏。 */
    fun sampleStatic(
        style: PhotoStyle,
        state: SceneState,
        aspect: Float,
        anchorX: Float,
        anchorY: Float,
        out: Values,
    ) {
        fillStatic(style, state, aspect, anchorX, anchorY, out.v)
        out.ratios.fill(0f)
        out.ratios[state.ordinal.coerceIn(0, 2)] = 1f
    }

    fun sample(
        style: PhotoStyle,
        edge: TransitionEdge,
        t: Float,
        aspect: Float,
        anchorX: Float,
        anchorY: Float,
        out: Values,
    ) {
        val tr = transitionOf(edge)
        val fromState = fromOf(edge)
        val toState = toOf(edge)
        val from = FloatArray(8)
        val to = FloatArray(8)
        fillStatic(style, fromState, aspect, anchorX, anchorY, from)
        fillStatic(style, toState, aspect, anchorX, anchorY, to)

        val elapsed = (
            t.coerceIn(0f, 1f) * durationMs(style, edge).toFloat() -
                (if (fromState == SceneState.AOD) PhotoStyle.OPEN_DELAY_MS.toFloat() else 0f)
            ).coerceAtLeast(0f)
        val videoSpan = abs(from[PhotoStyle.SLOT_VIDEO] - to[PhotoStyle.SLOT_VIDEO]) / 1000f

        for (slot in 0 until 8) {
            val spec = style.track(tr, slot)
            var duration = spec?.durationMs?.toFloat() ?: 400f
            val delay = spec?.delayMs?.toFloat() ?: 0f
            var curve = spec?.easing ?: DEFAULT
            if (slot == PhotoStyle.SLOT_VIDEO) {
                duration = videoSpan
                curve = LINEAR
            }
            if (slot == PhotoStyle.SLOT_PHOTO_ALPHA && tr == 4) {
                duration = 800f
                curve = EASE
            }
            val p = if (duration <= 0.01f) 1f else ((elapsed - delay) / duration).coerceIn(0f, 1f)
            out.v[slot] = from[slot] + (to[slot] - from[slot]) * curve.map(p)
        }

        val blend = style.blendDuration(tr).toFloat()
        val q = if (blend <= 0f) 1f else (elapsed / blend).coerceIn(0f, 1f)
        val curve = if (tr == 2 || tr == 3) EASE else LINEAR
        val eased = curve.map(q)
        val fromRatio = ratioOf(fromState)
        val toRatio = ratioOf(toState)
        for (i in 0..2) out.ratios[i] = fromRatio[i] + (toRatio[i] - fromRatio[i]) * eased
    }

    private fun fillStatic(
        style: PhotoStyle,
        state: SceneState,
        aspect: Float,
        anchorX: Float,
        anchorY: Float,
        out: FloatArray,
    ) {
        when (state) {
            SceneState.AOD -> {
                out[PhotoStyle.SLOT_BLUR] = style.blur
                out[PhotoStyle.SLOT_ROTATION] = style.rotation
                out[PhotoStyle.SLOT_SCALE] = style.scale
                out[PhotoStyle.SLOT_CONTENT] = style.content
                out[PhotoStyle.SLOT_X] = anchorX
                out[PhotoStyle.SLOT_Y] = anchorY
                out[PhotoStyle.SLOT_VIDEO] = 0f
                out[PhotoStyle.SLOT_PHOTO_ALPHA] = 1f
            }

            SceneState.LOCK, SceneState.HOME -> {
                out[PhotoStyle.SLOT_BLUR] = 0f
                out[PhotoStyle.SLOT_ROTATION] = 0f
                out[PhotoStyle.SLOT_SCALE] = PhotoStyle.EXPANDED_SCALE
                out[PhotoStyle.SLOT_CONTENT] = aspect / PhotoStyle.EXPANDED_SCALE
                out[PhotoStyle.SLOT_X] = 0f
                out[PhotoStyle.SLOT_Y] = 0f
                out[PhotoStyle.SLOT_VIDEO] = style.endMicros.toFloat()
                out[PhotoStyle.SLOT_PHOTO_ALPHA] = if (state == SceneState.LOCK) 1f else 0f
            }
        }
    }

    private fun ratioOf(state: SceneState): FloatArray = when (state) {
        SceneState.AOD -> floatArrayOf(1f, 0f, 0f)
        SceneState.LOCK -> floatArrayOf(0f, 1f, 0f)
        SceneState.HOME -> floatArrayOf(0f, 0f, 1f)
    }
}
