package com.coloros16.liquidglass.engine

/** 单通道关键帧：t 归一化到 0..1。 */
data class Keyframe(val t: Float, val value: Float)

/** 单通道关键帧轨道：t 必须递增；超出首尾取端点值。 */
class KeyframeTrack(
    private val frames: List<Keyframe>,
    private val easing: Easing = Easing.LINEAR,
) {

    init {
        require(frames.isNotEmpty()) { "keyframe track must not be empty" }
        for (i in 1 until frames.size) {
            require(frames[i].t >= frames[i - 1].t) { "keyframe t must be non-decreasing" }
        }
    }

    fun sample(t: Float): Float {
        val clamped = if (t.isNaN()) 0f else t.coerceIn(0f, 1f)
        val first = frames.first()
        val last = frames.last()
        if (clamped <= first.t) return first.value
        if (clamped >= last.t) return last.value
        var i = 1
        while (i < frames.size && frames[i].t <= clamped) i++
        val a = frames[i - 1]
        val b = frames[i]
        val span = b.t - a.t
        if (span <= 0f) return b.value
        val local = easing.map((clamped - a.t) / span)
        return a.value + (b.value - a.value) * local
    }
}
