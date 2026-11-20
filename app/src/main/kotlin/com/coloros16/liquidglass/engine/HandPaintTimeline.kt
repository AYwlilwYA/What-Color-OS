package com.coloros16.liquidglass.engine

/**
 * 手绘播放时间轴：把单调时钟折算成完整重放进度，事件驱动无轮询。
 *
 * 停笔已在解析期折叠，故进度按有效点数线性推进；
 * 播放时长由 [HandPaintGeometry.replayDurationMs] 给（自拟基线，无系统真值）。
 */
object HandPaintTimeline {

    /** 一次播放的状态：起点时刻、总时长、是否已播完。 */
    class Play {
        var originMs = 0L
        var durationMs = 0L
        var style = -1
        var running = false

        fun reset() {
            originMs = 0L
            durationMs = 0L
            style = -1
            running = false
        }
    }

    /**
     * 起播：记下起点与时长；同款已在播则不打断（返回 false）。
     *
     * 换款或已播完则重新起播，避免换样式后残留上一款进度。
     */
    fun start(play: Play, style: Int, nowMs: Long, effectivePoints: Int): Boolean {
        if (play.running && play.style == style) return false
        play.style = style
        play.originMs = nowMs
        play.durationMs = HandPaintGeometry.replayDurationMs(effectivePoints)
        play.running = true
        return true
    }

    /** 当前进度 0..1；未起播返回 0，时长非正返回 1（视为已完成）。 */
    fun progressAt(play: Play, nowMs: Long): Float {
        if (!play.running) return 0f
        if (play.durationMs <= 0L) return 1f
        val elapsed = nowMs - play.originMs
        if (elapsed <= 0L) return 0f
        return (elapsed.toFloat() / play.durationMs).coerceIn(0f, 1f)
    }

    /** 是否已播完（播完后停在整幅，不再申请帧回调）。 */
    fun finishedAt(play: Play, nowMs: Long): Boolean =
        !play.running || progressAt(play, nowMs) >= 1f

    /**
     * 下一帧还要不要继续：只有未播完时才需要帧回调。
     *
     * 播完即停，避免静止态持续唤醒渲染线程。
     */
    fun needsFrame(play: Play, nowMs: Long): Boolean =
        play.running && progressAt(play, nowMs) < 1f

    /** 停播：清运行标志但保留 style，便于同款重播时识别。 */
    fun stop(play: Play) {
        play.running = false
    }
}