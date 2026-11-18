package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodClockEntry
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.ui.aod.AodSceneCatalog
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起播时长必须按当前样式取真值：hook 与预览走同一个 durationOf。
 *
 * 纯 JVM 侧核对（AodSceneCatalog 只依赖引擎与常量，不触平台）。
 */
class StyleDurationContractTest {

    private val allEdges = TransitionEdge.values()

    @Test
    fun sailLockToHomeUsesLongestTrackNotSystemDuration() {
        val sail = AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, TransitionEdge.LOCK_TO_HOME)
        assertEquals(3840L, sail)
        assertEquals(800L, SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME))
        assertTrue("启航该边比系统内容层时长长 4.8 倍", sail >= 4L * SystemSceneMotion.LOCK_TO_HOME_MS)
    }

    @Test
    fun positiveControlSystemDurationWouldRushSailByNearlyFiveTimes() {
        val sys = SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME)
        val sail = AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, TransitionEdge.LOCK_TO_HOME)
        val ratio = sail.toFloat() / sys.toFloat()
        assertTrue("不认样式时真机比预览快 4 倍以上，比值=$ratio", ratio > 4f)
    }

    @Test
    fun sailDiffersFromSystemOnFiveOfSixEdges() {
        var differing = 0
        for (e in allEdges) {
            if (AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, e) != SystemSceneMotion.durationOf(e)) differing++
        }
        assertEquals("六条边里五条时长不同，只有 HOME_TO_LOCK 相同", 5, differing)
    }

    @Test
    fun sailHomeToLockMatchesSystem() {
        assertEquals(
            SystemSceneMotion.durationOf(TransitionEdge.HOME_TO_LOCK),
            AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, TransitionEdge.HOME_TO_LOCK),
        )
    }

    @Test
    fun everyStyleEveryEdgeHasPositiveDuration() {
        for (id in AodStyleCatalog.allIds()) {
            for (e in allEdges) {
                val ms = AodSceneCatalog.durationOf(id, e)
                assertTrue("style=$id edge=$e duration=$ms 必须为正", ms > 0L)
            }
        }
    }

    @Test
    fun nonSceneStylesFallBackToSystemDuration() {
        for (id in AodStyleCatalog.allIds()) {
            if (AodSceneCatalog.forStyle(id) != null) continue
            for (e in allEdges) {
                assertEquals(
                    "非场景样式 $id edge=$e 应回系统时长",
                    SystemSceneMotion.durationOf(e),
                    AodSceneCatalog.durationOf(id, e),
                )
            }
        }
    }

    @Test
    fun photoStylesHaveTheirOwnDurations() {
        val moon = AodStyleCatalog.ID_STARMOON
        val ms = AodSceneCatalog.durationOf(moon, TransitionEdge.AOD_TO_LOCK)
        assertNotEquals(SystemSceneMotion.durationOf(TransitionEdge.AOD_TO_LOCK), ms)
        assertTrue(ms > 0L)
    }

    @Test
    fun unknownStyleFallsBackToSystemNotZero() {
        for (e in allEdges) {
            assertEquals(SystemSceneMotion.durationOf(e), AodSceneCatalog.durationOf("no.such.style", e))
        }
    }

    @Test
    fun clockTailShrinksWhenStyleDurationIsLonger() {
        val e = TransitionEdge.LOCK_TO_AOD
        val sysTail = AodClockEntry.tailMs(SystemSceneMotion.durationOf(e))
        val sailTail = AodClockEntry.tailMs(AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, e))
        assertEquals(500L, sysTail)
        assertEquals(350L, sailTail)
        assertTrue("样式边更长 ⇒ 时钟补尾更短", sailTail < sysTail)
    }

    @Test
    fun clockTailVanishesWhenStyleDurationExceedsClockEntry() {
        val longEdgeMs = SystemSceneMotion.AOD_CLOCK_IN_MS.toLong() + 1L
        assertEquals(0L, AodClockEntry.tailMs(longEdgeMs))
    }

    @Test
    fun tailProgressStartsWhereEdgeEndedNoJump() {
        val e = TransitionEdge.LOCK_TO_AOD
        val sailMs = AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, e)
        val atEdgeEnd = AodClockEntry.progress(1f, sailMs)
        val atTailStart = AodClockEntry.tailProgress(0L, sailMs)
        assertEquals("补尾起点必须接边末进度", atEdgeEnd, atTailStart, 1e-6f)
    }

    @Test
    fun tailCompletesExactlyAtStyleTailLength() {
        val e = TransitionEdge.HOME_TO_AOD
        val sailMs = AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, e)
        val tail = AodClockEntry.tailMs(sailMs)
        assertTrue(tail > 0L)
        assertTrue(AodClockEntry.tailDone(tail, sailMs))
        assertTrue(!AodClockEntry.tailDone(tail - 1L, sailMs))
        assertEquals(1f, AodClockEntry.tailProgress(tail, sailMs), 1e-4f)
    }

    @Test
    fun usingSystemDurationForSailTailOverrunsByOneHundredFiftyMs() {
        val e = TransitionEdge.LOCK_TO_AOD
        val sysMs = SystemSceneMotion.durationOf(e)
        val sailMs = AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, e)
        val wrongTail = AodClockEntry.tailMs(sysMs)
        val rightTail = AodClockEntry.tailMs(sailMs)
        assertEquals("按系统时长算会让补尾多跑 150ms", 150L, wrongTail - rightTail)
    }

    @Test
    fun sailDurationMatchesEngineTruthDirectly() {
        for (e in allEdges) {
            assertEquals(
                "样式目录必须直接转发引擎真值，不得自算",
                SailLensMotion.durationMs(e),
                AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, e),
            )
        }
    }
}
