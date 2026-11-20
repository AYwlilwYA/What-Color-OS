package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockSource
import com.coloros16.liquidglass.engine.PumpSourcePolicy
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.TransitionBridge
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpSourcePolicyContractTest {

    private fun bridge() = TransitionBridge(ScreenMetrics(1080f, 2400f, 3f))

    // ---------- 源归属 ----------

    @Test
    fun aodTouchingEdgesAreSystemDriven() {
        assertTrue(PumpSourcePolicy.systemDriven(TransitionEdge.AOD_TO_LOCK))
        assertTrue(PumpSourcePolicy.systemDriven(TransitionEdge.LOCK_TO_AOD))
        assertTrue(PumpSourcePolicy.systemDriven(TransitionEdge.AOD_TO_HOME))
        assertTrue(PumpSourcePolicy.systemDriven(TransitionEdge.HOME_TO_AOD))
    }

    @Test
    fun lockHomeEdgesAreNotSystemDriven() {
        assertFalse(PumpSourcePolicy.systemDriven(TransitionEdge.LOCK_TO_HOME))
        assertFalse(PumpSourcePolicy.systemDriven(TransitionEdge.HOME_TO_LOCK))
    }

    @Test
    fun terminalValueIsNotFeeding() {
        assertFalse(PumpSourcePolicy.systemFeeding(TransitionEdge.AOD_TO_HOME, 1f))
        assertTrue(PumpSourcePolicy.systemFeeding(TransitionEdge.AOD_TO_HOME, 0.999f))
        assertTrue(PumpSourcePolicy.systemFeeding(TransitionEdge.AOD_TO_HOME, 0f))
    }

    @Test
    fun nanValueIsNotFeeding() {
        assertFalse(PumpSourcePolicy.systemFeeding(TransitionEdge.AOD_TO_HOME, Float.NaN))
    }

    @Test
    fun nonSystemEdgeAlwaysSelfTimes() {
        assertTrue(PumpSourcePolicy.shouldSelfTime(TransitionEdge.LOCK_TO_HOME, true, 0.3f))
        assertTrue(PumpSourcePolicy.shouldSelfTime(TransitionEdge.HOME_TO_LOCK, true, 0f))
    }

    // ---------- 桥的接管判定 ----------

    @Test
    fun recordedLockToHomeSequenceKeepsSelfTimeline() {
        val b = bridge()
        // 真机原序列：系统泵只写 0.000，随后起播内部时间轴，再写一次端点 1.000
        b.onSystemProgress(TransitionEdge.LOCK_TO_HOME, 0f)
        assertTrue(b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 3840L))
        val start = b.nowMs()
        b.onSystemProgress(TransitionEdge.LOCK_TO_HOME, 1f)

        val st = b.clock.state()
        assertTrue("端点直跳不得终止内部时间轴", st.playing)
        assertTrue(st.source != ClockSource.SYSTEM_PUMP)

        b.advance(start + 1000L)
        val t = b.clock.state().t
        assertTrue("1s 后应落在约 0.26 而非 1.0：$t", t in 0.25f..0.27f)
    }

    @Test
    fun staleSystemTerminalDoesNotBlockSelfTimedLockHome() {
        val b = bridge()
        b.onSystemProgress(TransitionEdge.LOCK_TO_HOME, 1f)
        assertTrue(b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L))
        assertTrue(b.clock.state().playing)
        assertTrue(b.clock.state().source != ClockSource.SYSTEM_PUMP)
    }

    @Test
    fun staleSystemTerminalDoesNotBlockSelfTimedAodEdge() {
        val b = bridge()
        b.onSystemProgress(TransitionEdge.HOME_TO_AOD, 1f)
        assertTrue(b.ensurePlaying(TransitionEdge.HOME_TO_AOD, 900L))
        assertTrue(b.clock.state().playing)
    }

    @Test
    fun midFlightSystemProgressKeepsOwnership() {
        val b = bridge()
        b.onSystemProgress(TransitionEdge.AOD_TO_HOME, 0.2f)
        b.onSystemProgress(TransitionEdge.AOD_TO_HOME, 0.4f)
        assertFalse(b.ensurePlaying(TransitionEdge.AOD_TO_HOME, 900L))
        assertTrue(b.clock.state().source == ClockSource.SYSTEM_PUMP)
        assertFalse(b.clock.state().playing)
    }

    @Test
    fun singleWriteWithoutMovementYieldsToSelfTimed() {
        val b = bridge()
        b.onSystemProgress(TransitionEdge.AOD_TO_HOME, 0.4f)
        assertTrue(b.ensurePlaying(TransitionEdge.AOD_TO_HOME, 900L))
        assertTrue(b.clock.state().playing)
    }

    @Test
    fun movementWitnessResetsOnNewEdge() {
        val b = bridge()
        b.onSystemProgress(TransitionEdge.AOD_TO_HOME, 0.2f)
        b.onSystemProgress(TransitionEdge.AOD_TO_HOME, 0.6f)
        assertTrue(b.clock.pumpSawMovement())
        b.onSystemProgress(TransitionEdge.HOME_TO_AOD, 0.1f)
        assertFalse(b.clock.pumpSawMovement())
    }

    @Test
    fun repeatedSameValueIsNotMovement() {
        val b = bridge()
        b.onSystemProgress(TransitionEdge.HOME_TO_AOD, 0f)
        b.onSystemProgress(TransitionEdge.HOME_TO_AOD, 0f)
        assertFalse("同值重复不是推进", b.clock.pumpSawMovement())
        b.onSystemProgress(TransitionEdge.HOME_TO_AOD, 1f)
        assertFalse("端点直跳只算宣告完成，不算逐帧喂", b.clock.pumpSawMovement())
        b.onSystemProgress(TransitionEdge.HOME_TO_AOD, 0.5f)
        assertTrue("落到开区间内才证明有中间帧", b.clock.pumpSawMovement())
    }

    @Test
    fun selfTimedTerminalStillDoesNotReplay() {
        val b = bridge()
        assertTrue(b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 100L))
        val start = b.nowMs()
        b.advance(start + 500L)
        assertFalse(b.clock.state().playing)
        assertFalse(b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 100L))
    }

    @Test
    fun playingEdgeIsNotRestartedRegardlessOfSource() {
        val b = bridge()
        assertTrue(b.ensurePlaying(TransitionEdge.AOD_TO_LOCK, 800L))
        assertFalse(b.ensurePlaying(TransitionEdge.AOD_TO_LOCK, 800L))
    }
}