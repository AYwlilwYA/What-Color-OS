package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodClockEntry
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景通道的绝对时长口径：`sample()` 必须按**调用方实际使用的边长**归一化。
 *
 * 边长来源按样式不同（启航款 1000/650/3840…、系统表 600/500/800…），
 * 若求值器固定用自己的常量，同一比例会落到不同绝对时间上，且与时钟补尾的账本对不上。
 */
class SystemSceneMotionEdgeMsTest {

    private val out = SystemSceneMotion.Layers()

    @Test
    fun clockTailStartsWhereEdgeEnds() {
        for (edge in listOf(TransitionEdge.LOCK_TO_AOD, TransitionEdge.HOME_TO_AOD)) {
            for (edgeMs in listOf(500L, 650L, 1000L)) {
                SystemSceneMotion.sample(edge, 1f, out, edgeMs = edgeMs)
                val atEdgeEnd = out.aodClockScale
                val tailStart = AodClockEntry.scaleAt(AodClockEntry.tailProgress(0L, edgeMs))
                assertEquals(
                    "edge=$edge edgeMs=$edgeMs 补尾起点必须接边末，不能跳变",
                    tailStart, atEdgeEnd, 1e-4f,
                )
            }
        }
    }

    @Test
    fun exitHonorsAbsoluteMilliseconds() {
        val edgeMs = 3840L
        val justBefore = 190f / edgeMs
        val justAfter = 210f / edgeMs
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_HOME, justBefore, out, edgeMs = edgeMs)
        assertTrue("190ms 时锁屏层还没退完，实际 ${out.lockAlpha}", out.lockAlpha > 0f)
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_HOME, justAfter, out, edgeMs = edgeMs)
        assertEquals("210ms 时锁屏层必须已退完", 0f, out.lockAlpha, 1e-5f)
    }

    @Test
    fun defaultEdgeMsKeepsLegacyShape() {
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_HOME, 0.2f, out)
        assertTrue("800ms 口径下 t=0.2 仍在退场中", out.lockAlpha > 0f)
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_HOME, 0.26f, out)
        assertEquals("800ms 口径下 t=0.25 后退完", 0f, out.lockAlpha, 1e-5f)
    }

    @Test
    fun homeToLockExitUsesSameEdgeMs() {
        SystemSceneMotion.sample(TransitionEdge.HOME_TO_LOCK, 0.3f, out)
        assertTrue("600ms 口径下 t=0.3 桌面还没退完", out.homeAlpha > 0f)
        SystemSceneMotion.sample(TransitionEdge.HOME_TO_LOCK, 0.34f, out)
        assertEquals("600ms 口径下 t=1/3 后退完", 0f, out.homeAlpha, 1e-5f)
    }

    @Test
    fun shorterEdgeFinishesClockSooner() {
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 1f, out, edgeMs = 1000L)
        assertEquals("边长 1000ms 时时钟进场恰好走完", 1f, out.aodClockScale, 1e-4f)
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 1f, out, edgeMs = 650L)
        assertTrue("边长 650ms 时时钟还差一截", out.aodClockScale < 1f)
    }

    @Test
    fun staticStatesUnaffected() {
        SystemSceneMotion.sampleStatic(SceneState.LOCK, out)
        assertEquals(1f, out.lockAlpha, 0f)
        assertEquals(0f, out.aodAlpha, 0f)
    }
}
