package com.coloros16.liquidglass.engine

import java.util.concurrent.CopyOnWriteArrayList

/** 进度监听：回调只带基础类型，逐帧通知不产生对象分配。 */
fun interface ClockListener {
    fun onClock(
        edge: TransitionEdge?,
        source: ClockSource,
        t: Float,
        playing: Boolean,
        revision: Long,
    )
}

/**
 * 可插拔进度源：系统进度泵、预览时间轴、手动滑杆写入同一个 t。
 * 播放由调用方驱动 [advance]（预览用帧回调，系统源由 hook 直接 [setT]），自身不起线程、不轮询。
 */
class TransitionClock(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    /** 时钟时间源：起播与推进必须用同一基准，混用会让进度恒为 0。 */
    fun now(): Long = nowMs()

    private val listeners = CopyOnWriteArrayList<ClockListener>()

    private var edge: TransitionEdge? = null
    private var source: ClockSource = ClockSource.MANUAL
    private var t: Float = 0f
    private var playing: Boolean = false
    private var revision: Long = 0L

    private var playStartMs: Long = 0L
    private var playFromT: Float = 0f
    private var playDurationMs: Long = 0L
    private var playDelayMs: Long = 0L

    private var pumpSawMovement: Boolean = false
    private var pumpEdge: TransitionEdge? = null
    private var pumpT: Float = Float.NaN

    /** 本轮系统泵宣告以来是否见过进度真的推进（无帧的边恒 false）。 */
    @Synchronized
    fun pumpSawMovement(): Boolean = pumpSawMovement

    @Synchronized
    fun state(): ClockState = ClockState(edge, source, t, playing, revision)

    /** 订阅进度变化；返回取消订阅。 */
    fun subscribe(listener: ClockListener): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** 写入进度（系统泵或手动）。 */
    @Synchronized
    fun setT(edge: TransitionEdge, value: Float, source: ClockSource) {
        val clamped = clamp01(value)
        if (source == ClockSource.SYSTEM_PUMP) {
            if (pumpEdge != edge) {
                pumpEdge = edge
                pumpSawMovement = false
                pumpT = Float.NaN
            } else if (PumpTakeoverPolicy.interior(pumpT, clamped)) {
                pumpSawMovement = true
            }
            pumpT = clamped
            if (playing && this.source == ClockSource.INTERNAL_TIMELINE &&
                this.edge == edge && !pumpSawMovement
            ) {
                return
            }
        }
        val changed = this.edge != edge || this.t != clamped || this.source != source || playing
        this.edge = edge
        this.t = clamped
        this.source = source
        this.playing = false
        if (changed) notifyChanged()
    }

    /** 手动拖拽定位。 */
    @Synchronized
    fun seek(edge: TransitionEdge, value: Float) {
        setT(edge, value, ClockSource.MANUAL)
    }

    /**
     * 起播：由调用方按帧调 [advance] 推进。
     *
     * [startDelayMs] 是起手停顿：这段时间内进度停在 [from] 不动，之后才按 [durationMs] 归一化。
     * 停顿不占时长，各通道 fraction 不受稀释。
     */
    @Synchronized
    fun play(
        edge: TransitionEdge,
        durationMs: Long,
        from: Float = 0f,
        source: ClockSource = ClockSource.PREVIEW,
        startDelayMs: Long = 0L,
    ) {
        this.edge = edge
        this.source = source
        if (source == ClockSource.INTERNAL_TIMELINE) pumpSawMovement = false
        this.playFromT = clamp01(from)
        this.t = this.playFromT
        this.playDurationMs = durationMs.coerceAtLeast(1L)
        this.playDelayMs = startDelayMs.coerceAtLeast(0L)
        this.playStartMs = nowMs()
        this.playing = true
        notifyChanged()
    }

    @Synchronized
    fun pause() {
        if (!playing) return
        playing = false
        notifyChanged()
    }

    /**
     * 推进播放进度；返回是否仍在播放。
     * 非播放态直接返回 false，不做任何计算。
     */
    @Synchronized
    fun advance(now: Long = nowMs()): Boolean {
        if (!playing) return false
        val elapsed = now - playStartMs - playDelayMs
        val next = if (elapsed <= 0L) playFromT else playFromT + elapsed.toFloat() / playDurationMs
        if (next >= 1f) {
            t = 1f
            playing = false
            notifyChanged()
            return false
        }
        if (next != t) {
            t = next
            notifyChanged()
        }
        return true
    }

    @Synchronized
    fun reset() {
        edge = null
        source = ClockSource.MANUAL
        t = 0f
        playing = false
        pumpEdge = null
        pumpT = Float.NaN
        pumpSawMovement = false
        notifyChanged()
    }

    private fun notifyChanged() {
        revision++
        val e = edge
        val s = source
        val v = t
        val p = playing
        val r = revision
        for (listener in listeners) {
            listener.onClock(e, s, v, p, r)
        }
    }

    private fun clamp01(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
}
