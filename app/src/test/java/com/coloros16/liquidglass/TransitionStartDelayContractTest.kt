package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockSource
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.TransitionBridge
import com.coloros16.liquidglass.engine.TransitionClock
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionStartDelay
import com.coloros16.liquidglass.engine.from
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 起手停顿契约：从息屏出发的边先停一拍，停顿不占时长，续播不停顿。
 */
class TransitionStartDelayContractTest {

    private class FakeTime(var nowMs: Long = 0L)

    private fun bridge(time: FakeTime): TransitionBridge =
        TransitionBridge(ScreenMetrics(1080f, 2376f, 3f), clock = TransitionClock { time.nowMs })

    @Test
    fun onlyEdgesLeavingAodGetDelay() {
        for (edge in TransitionEdge.values()) {
            val expected = if (edge.from == com.coloros16.liquidglass.engine.SceneState.AOD) 150L else 0L
            assertEquals("edge=$edge", expected, TransitionStartDelay.forEdge(edge))
        }
    }

    @Test
    fun delayCountIsExactlyTwoOfSixEdges() {
        val withDelay = TransitionEdge.values().count { TransitionStartDelay.forEdge(it) > 0L }
        assertEquals("六边里只有两条从息屏出发", 2, withDelay)
    }

    @Test
    fun enteringAodHasNoDelay() {
        assertEquals(0L, TransitionStartDelay.forEdge(TransitionEdge.LOCK_TO_AOD))
        assertEquals(0L, TransitionStartDelay.forEdge(TransitionEdge.HOME_TO_AOD))
    }

    @Test
    fun restartWhilePlayingSkipsDelay() {
        assertEquals(
            "打断上一条边换目标不停顿",
            0L,
            TransitionStartDelay.forRestart(TransitionEdge.AOD_TO_LOCK, interrupted = true),
        )
        assertEquals(
            150L,
            TransitionStartDelay.forRestart(TransitionEdge.AOD_TO_LOCK, interrupted = false),
        )
    }

    @Test
    fun progressStaysAtStartDuringDelay() {
        val clock = FakeTime(1000L)
        val b = bridge(clock)
        assertTrue(b.ensurePlaying(TransitionEdge.AOD_TO_LOCK, 950L))
        clock.nowMs = 1000L + 149L
        b.advance(clock.nowMs)
        assertEquals("停顿期进度必须停在起点", 0f, b.clock.state().t, 1e-6f)
        assertTrue("停顿期仍算在播放", b.clock.state().playing)
    }

    @Test
    fun progressMovesRightAfterDelay() {
        val clock = FakeTime(1000L)
        val b = bridge(clock)
        b.ensurePlaying(TransitionEdge.AOD_TO_LOCK, 950L)
        clock.nowMs = 1000L + 150L + 95L
        b.advance(clock.nowMs)
        assertEquals("停顿结束后按播放时长归一化", 0.1f, b.clock.state().t, 1e-4f)
    }

    @Test
    fun delayDoesNotDiluteDuration() {
        val clock = FakeTime(0L)
        val b = bridge(clock)
        b.ensurePlaying(TransitionEdge.AOD_TO_LOCK, 950L)
        clock.nowMs = 150L + 950L
        val stillPlaying = b.advance(clock.nowMs)
        assertFalse("停顿不占时长，150+950 就该播完", stillPlaying)
        assertEquals(1f, b.clock.state().t, 1e-6f)
    }

    @Test
    fun positiveControlWithoutDelayWouldFinishEarlier() {
        val clock = FakeTime(0L)
        val b = bridge(clock)
        b.startSelfTimed(TransitionEdge.AOD_TO_LOCK, 950L, clock.nowMs, startDelayMs = 0L)
        clock.nowMs = 950L
        b.advance(clock.nowMs)
        assertEquals("无停顿时 950ms 就播完（阳性对照）", 1f, b.clock.state().t, 1e-6f)

        val clock2 = FakeTime(0L)
        val b2 = bridge(clock2)
        b2.ensurePlaying(TransitionEdge.AOD_TO_LOCK, 950L)
        clock2.nowMs = 950L
        b2.advance(clock2.nowMs)
        assertTrue("有停顿时同一时刻还没播完", b2.clock.state().t < 1f)
        assertEquals((950f - 150f) / 950f, b2.clock.state().t, 1e-4f)
    }

    @Test
    fun nonAodEdgeStartsImmediately() {
        val clock = FakeTime(0L)
        val b = bridge(clock)
        b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L)
        clock.nowMs = 80L
        b.advance(clock.nowMs)
        assertEquals("非息屏起点的边不停顿", 0.1f, b.clock.state().t, 1e-4f)
    }

    @Test
    fun switchingEdgeMidPlaySkipsDelay() {
        val clock = FakeTime(0L)
        val b = bridge(clock)
        b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L)
        clock.nowMs = 100L
        b.advance(clock.nowMs)
        assertTrue(b.clock.state().playing)
        assertTrue("换目标应起播", b.ensurePlaying(TransitionEdge.AOD_TO_LOCK, 950L))
        clock.nowMs = 100L + 95L
        b.advance(clock.nowMs)
        assertEquals("打断续播不停顿，立刻按新时长走", 0.1f, b.clock.state().t, 1e-4f)
    }

    @Test
    fun systemPumpProgressIsNotDelayed() {
        val clock = FakeTime(0L)
        val b = bridge(clock)
        b.onSystemProgress(TransitionEdge.AOD_TO_LOCK, 0.4f)
        assertEquals("系统泵直接写进度，不受起手停顿影响", 0.4f, b.clock.state().t, 1e-6f)
        assertEquals(ClockSource.SYSTEM_PUMP, b.clock.state().source)
    }

    @Test
    fun explicitStartDelayOverridesDefault() {
        val clock = FakeTime(0L)
        val b = bridge(clock)
        b.startSelfTimed(TransitionEdge.AOD_TO_LOCK, 950L, clock.nowMs, startDelayMs = 300L)
        clock.nowMs = 299L
        b.advance(clock.nowMs)
        assertEquals(0f, b.clock.state().t, 1e-6f)
        clock.nowMs = 300L + 95L
        b.advance(clock.nowMs)
        assertEquals(0.1f, b.clock.state().t, 1e-4f)
    }

    @Test
    fun previewUsesSameConstantAsEngine() {
        val controller = File(
            "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodDisplayController.kt",
        ).readText()
        assertTrue(
            "预览侧起手停顿必须与引擎同值",
            controller.contains("AOD_OPEN_DELAY_MS = ${TransitionStartDelay.FROM_AOD_MS}L"),
        )
    }

    @Test
    fun hookPlayLogCarriesDelayForVerification() {
        val hook = File(
            "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
        ).readText()
        assertTrue(
            "起播日志必须带 delay，否则真机看不出停顿有没有生效",
            hook.contains("delay=\${TransitionStartDelay.forEdge(edge)}"),
        )
    }

    @Test
    fun hookCallSiteUnchangedBecauseBridgeAppliesDelay() {
        val hook = File(
            "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
        ).readText()
        assertTrue(
            "停顿由桥按边自动给，调用点不各写一遍",
            hook.contains("bridge.ensurePlaying(edge, duration)"),
        )
    }

    @Test
    fun referenceImplementationUsesSameValue() {
        val ref = File("../reference/OpenAlive/app/java/org/aliveclean/SceneMotion.java")
        if (!ref.isFile) return
        val text = ref.readText()
        assertTrue(
            "参考实现从息屏出发时 setStartDelay(150)",
            text.contains("animation.setStartDelay(150)"),
        )
        assertTrue("且只在未被打断时加", text.contains("previous==0&&!interrupted"))
    }
}
