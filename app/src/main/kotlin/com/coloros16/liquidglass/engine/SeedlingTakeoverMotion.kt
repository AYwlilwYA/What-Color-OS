package com.coloros16.liquidglass.engine

import kotlin.math.max
import kotlin.math.min

/**
 * 流体云接管过渡动画求值（纯 JVM，零 android.*，App 预览与 hook 共用）。
 *
 * 动画真值来自系统 ChangeListener 事件 Bundle（animationType + animationDurations 逐段时长 +
 * placeholder_width 岛目标宽度），本求值器把事件喂进的时长/类型映射为胶囊↔大卡统一过渡进度，
 * 不自拟时长。弹簧参数参考参考实现 config/c.java:234-338（阻尼 0.62/回弹 6dp/展开 0.9/收起 1.0），
 * 但大位移禁 ζ<1（记忆 project_spring-overshoot-large-displacement）→ 位移通道用临界阻尼。
 */
enum class SeedlingAnimType {
    SHRINK_IN, EXPAND_OUT, SHRINK_IN_EXPAND_OUT, MOVE_LEFT, MOVE_RIGHT, UNKNOWN;

    companion object {
        /** 系统 StatusBarAnimationType.name() 字符串映射（StatusBarAnimationType.java:14-18）。 */
        fun ofName(name: String?): SeedlingAnimType = when (name) {
            "SHRINK_IN" -> SHRINK_IN
            "EXPAND_OUT" -> EXPAND_OUT
            "SHRINK_IN_EXPAND_OUT" -> SHRINK_IN_EXPAND_OUT
            "MOVE_LEFT" -> MOVE_LEFT
            "MOVE_RIGHT" -> MOVE_RIGHT
            else -> UNKNOWN
        }
    }
}

/** 参考实现动画/外观参数默认值（config/c.java:234-338），接管态可被 Prefs 覆盖。 */
data class SeedlingMotionParams(
    val damping: Float = 0.62f,
    val bounceDp: Float = 6f,
    val expandSpeed: Float = 0.9f,
    val collapseSpeed: Float = 1.0f,
    val capsuleWidthRatio: Float = 0.46f,
    val cardRadiusDp: Float = 24f,
    val speedScale: Float = 1f,
)

/** 一次过渡事件（由 hook 从 ChangeListener Bundle 解出喂入）。 */
data class SeedlingTransitionEvent(
    val type: SeedlingAnimType,
    val durationsMs: LongArray,
    val placeholderWidthPx: Int,
    val isLockscreen: Boolean = false,
) {
    /** 总时长 = 逐段时长之和；空数组回落 [fallbackMs]（≤0 再按类型回落到标准时长）。速度缩放只缩时长不改曲线形状。 */
    fun totalDurationMs(fallbackMs: Long, speedScale: Float): Long {
        val sum = durationsMs.sum()
        val typeFallback = when (type) {
            SeedlingAnimType.SHRINK_IN -> SeedlingTakeoverMotion.FALLBACK_COLLAPSE_MS
            else -> SeedlingTakeoverMotion.FALLBACK_EXPAND_MS
        }
        val base = if (sum > 0) sum else if (fallbackMs > 0) fallbackMs else typeFallback
        val scale = speedScale.coerceAtLeast(0.1f)
        return max(1L, (base / scale).toLong())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeedlingTransitionEvent) return false
        return type == other.type && durationsMs.contentEquals(other.durationsMs) &&
            placeholderWidthPx == other.placeholderWidthPx && isLockscreen == other.isLockscreen
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + durationsMs.contentHashCode()
        result = 31 * result + placeholderWidthPx
        result = 31 * result + isLockscreen.hashCode()
        return result
    }
}

/** 过渡采样结果：u=胶囊↔大卡 morph 进度（0=胶囊 1=大卡），widthPx=当前岛宽。 */
data class SeedlingTransitionSample(
    val u: Float,
    val widthPx: Float,
)

object SeedlingTakeoverMotion {

    /** 默认过渡时长兜底（系统 durations 空时用），展开/收起分别。 */
    const val FALLBACK_EXPAND_MS = 320L
    const val FALLBACK_COLLAPSE_MS = 280L

    /**
     * 事件类型 → morph 方向：EXPAND_OUT=胶囊→大卡（0→1），SHRINK_IN=大卡→胶囊（1→0），
     * SHRINK_IN_EXPAND_OUT=先收后展（0→1 经中间收态），MOVE_*=平移不 morph（u 保持）。
     */
    fun morphDirection(type: SeedlingAnimType): Int = when (type) {
        SeedlingAnimType.EXPAND_OUT -> 1
        SeedlingAnimType.SHRINK_IN -> -1
        SeedlingAnimType.SHRINK_IN_EXPAND_OUT -> 1
        else -> 0
    }

    /**
     * 采样过渡：tMs=事件以来经过毫秒，event=系统事件，fromU=起始 morph，
     * capsuleWidthPx=胶囊静止宽，cardWidthPx=大卡宽。
     *
     * 时长用系统真值（event.totalDurationMs）；曲线位移通道临界阻尼（ζ=1，大位移禁过冲），
     * 宽度在胶囊宽↔大卡宽间按 morph 插值。MOVE_* 只平移不 morph，u 恒 fromU。
     */
    fun sample(
        tMs: Long,
        event: SeedlingTransitionEvent,
        fromU: Float,
        capsuleWidthPx: Float,
        cardWidthPx: Float,
        params: SeedlingMotionParams = SeedlingMotionParams(),
    ): SeedlingTransitionSample {
        val dir = morphDirection(event.type)
        if (dir == 0) {
            return SeedlingTransitionSample(fromU.coerceIn(0f, 1f), lerp(capsuleWidthPx, cardWidthPx, fromU))
        }
        val fallback = if (dir > 0) FALLBACK_EXPAND_MS else FALLBACK_COLLAPSE_MS
        val duration = event.totalDurationMs(fallback, params.speedScale)
        val t = (tMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        // 位移通道临界阻尼弹簧（ζ=1）：大位移禁过冲；stiffness 由时长按 wn≈π/(duration/2) 估
        val eased = criticallyDampedEase(t)
        val targetU = if (dir > 0) 1f else 0f
        val u = (fromU + (targetU - fromU) * eased).coerceIn(0f, 1f)
        val width = lerp(capsuleWidthPx, cardWidthPx, u)
        return SeedlingTransitionSample(u, width)
    }

    /**
     * 胶囊弹性宽度动画（静止态插件 drawable bounds 通道之外的接管自绘弹性）：
     * 小位移用参考实现阻尼 0.62（允许过冲=弹动感），stiffness 由 response 估。
     * progress=0..1 归一化，返回 0..1+过冲 的宽度比例。
     */
    fun elasticWidthFraction(progress: Float, params: SeedlingMotionParams = SeedlingMotionParams()): Float {
        val spring = SpringEasing(
            stiffness = 130f,
            dampingRatio = params.damping.coerceIn(0.1f, 1f),
            durationMs = 420f,
        )
        return spring.map(progress)
    }

    /** 临界阻尼解析缓动（ζ=1）：1-(1+t)·e^-t 归一化到 0..1，无过冲。 */
    private fun criticallyDampedEase(t: Float): Float {
        val q = t.coerceIn(0f, 1f)
        if (q <= 0f) return 0f
        if (q >= 1f) return 1f
        // 把 0..1 映射到 0..6 个时间常数（临界阻尼 ~6τ 收敛到 99%）
        val x = q * 6f
        val v = 1.0 - (1.0 + x) * kotlin.math.exp(-x)
        return v.toFloat().coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    /** 岛目标宽：placeholder_width 事件真值优先（>0），否则按胶囊宽比例兜底。 */
    fun resolveTargetWidth(placeholderWidthPx: Int, capsuleWidthPx: Float, params: SeedlingMotionParams): Float {
        if (placeholderWidthPx > 0) return placeholderWidthPx.toFloat()
        return max(capsuleWidthPx, capsuleWidthPx / params.capsuleWidthRatio.coerceAtLeast(0.1f))
    }
}