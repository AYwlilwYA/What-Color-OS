package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodClockEntry
import com.coloros16.liquidglass.engine.HandPaintGeometry
import com.coloros16.liquidglass.engine.HandPaintTimeline
import com.coloros16.liquidglass.engine.SystemSceneMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预览侧帧泵停帧判据：必须看「值是否贴合目标」，不能只看「已过渡时长」。
 *
 * 时长与值恰好等价时要证明等价，不能默认；不等价就是可见空档。
 */
class PreviewFramePumpStopContractTest {

    private fun src(rel: String): String =
        File("src/main/kotlin/com/coloros16/liquidglass/$rel").readText()

    /** 时钟补尾：停帧阈值与「值到 1 所需时长」必须恒等，六档边长逐一核。 */
    @Test
    fun clockTailStopThresholdEqualsValueCompletion() {
        for (edgeMs in listOf(300L, 500L, 600L, 800L, 950L, 1000L, 1200L)) {
            val tail = AodClockEntry.tailMs(edgeMs)
            val atEdgeEnd = AodClockEntry.progress(1f, edgeMs)
            val need = (1f - atEdgeEnd) * SystemSceneMotion.AOD_CLOCK_IN_MS
            assertEquals("edgeMs=$edgeMs 停帧阈值必须等于值到 1 所需时长", need, tail.toFloat(), 1f)
            assertEquals(
                "edgeMs=$edgeMs 停帧那一刻值必须恰好到 1",
                1f, AodClockEntry.scaleAt(AodClockEntry.tailProgress(tail, edgeMs)), 1e-4f,
            )
        }
    }

    /** 阳性对照：停帧前一帧值必须还没到 1，否则说明阈值取大了（会多跑帧）。 */
    @Test
    fun positiveControlOneFrameBeforeStopIsNotYetSettled() {
        val edgeMs = 500L
        val tail = AodClockEntry.tailMs(edgeMs)
        assertTrue("阈值必须为正", tail > 0L)
        val before = AodClockEntry.scaleAt(AodClockEntry.tailProgress(tail - 16L, edgeMs))
        assertTrue("停帧前一帧必须还没贴合", before < 1f)
    }

    /**
     * 阳性对照：补尾中途停帧会留空档。
     *
     * 判据取**进度**不取缩放值——缩放只在 0.85..1 的窄区间，
     * 曲线前段陡，progress 0.9 时 scale 已 ≈0.9997，用它判「贴合」不敏感。
     */
    @Test
    fun positiveControlStoppingMidTailLeavesGap() {
        val edgeMs = 800L
        val tail = AodClockEntry.tailMs(edgeMs)
        assertTrue("该边必须有补尾", tail > 0L)
        val midProgress = AodClockEntry.tailProgress(tail / 2, edgeMs)
        assertTrue("补尾中途进度必须还没到 1", midProgress < 0.999f)
        assertEquals("补尾走完进度才到 1", 1f, AodClockEntry.tailProgress(tail, edgeMs), 1e-6f)
        val midScale = AodClockEntry.scaleAt(midProgress)
        assertTrue("窄区间使缩放看着已贴合（所以不能用它当判据）", midScale > 0.99f)
    }

    /** 手绘帧泵：needsFrame 直接由进度判定，播完即停。 */
    @Test
    fun handPaintStopsByProgressNotElapsed() {
        val p = HandPaintTimeline.Play()
        val pts = 13001
        HandPaintTimeline.start(p, 1, 0L, pts)
        val dur = HandPaintGeometry.replayDurationMs(pts)
        assertTrue("播放中要帧", HandPaintTimeline.needsFrame(p, dur / 2))
        assertTrue("播完不要帧", !HandPaintTimeline.needsFrame(p, dur))
        assertTrue("超过时长也不要帧", !HandPaintTimeline.needsFrame(p, dur + 5000L))
        HandPaintTimeline.stop(p)
        assertTrue("停播后不要帧", !HandPaintTimeline.needsFrame(p, 0L))
    }

    /** 手绘停帧那一刻必须画满，不能停在 0.996 留一截没画。 */
    @Test
    fun handPaintLastFrameIsFullyDrawn() {
        val p = HandPaintTimeline.Play()
        val pts = 13001
        HandPaintTimeline.start(p, 1, 0L, pts)
        val dur = HandPaintGeometry.replayDurationMs(pts)
        assertEquals(1f, HandPaintTimeline.progressAt(p, dur), 1e-6f)
        val lastGrid = dur / 37L * 37L
        assertTrue("末格点确实还没到 1（所以不能按格点停）", HandPaintTimeline.progressAt(p, lastGrid) < 1f)
        assertTrue("末格点仍要帧", HandPaintTimeline.needsFrame(p, lastGrid))
    }

    /** 镜片帧泵：按「该不该转」判定，不按时长；静止零回调。 */
    @Test
    fun lensPumpArmedByStateNotDuration() {
        val text = src("ui/aod/AodPreviewView.kt")
        assertTrue("续帧判据必须是状态而非时长", text.contains("if (lensSpinning || motionAnimating() || lensSpinActive())"))
        assertTrue("不满足必须停泵", text.contains("else if (!lensSpinning) stopLensTick()"))
        assertTrue("离窗必须停", text.contains("if (!isAttachedToWindow)"))
        assertTrue("不许出现固定周期 sleep 轮询", !text.contains("Thread.sleep"))
    }

    /** 时钟补尾泵：离窗与无补尾都必须退出，不留孤儿回调。 */
    @Test
    fun clockTailPumpExitsOnDetachAndNoTail() {
        val text = src("ui/aod/AodPreviewView.kt")
        val i = text.indexOf("private val clockTailTick")
        val body = text.substring(i, text.indexOf("private fun scheduleClockTail", i))
        assertTrue("无补尾直接退出", body.contains("clockTailEdge ?: return"))
        assertTrue("离窗清补尾", body.contains("if (!isAttachedToWindow)"))
        assertTrue("停帧后不再自调度", body.contains("if (clockTailEdge != null) scheduleClockTail()"))
    }
}
