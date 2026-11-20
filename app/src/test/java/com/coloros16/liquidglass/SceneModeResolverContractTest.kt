package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneCameraLerp
import com.coloros16.liquidglass.engine.SceneModeResolver
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SnowMountainCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壁纸三态判定契约：息屏优先于锁屏、模式序号双向可逆、亮屏等一拍再认锁屏。
 */
class SceneModeResolverContractTest {

    @Test
    fun ambientWinsOverKeyguard() {
        assertEquals(
            "息屏时锁屏状态仍为已锁，必须先判息屏",
            SceneState.AOD, SceneModeResolver.resolve(ambient = true, keyguardLocked = true),
        )
        assertEquals(SceneState.AOD, SceneModeResolver.resolve(ambient = true, keyguardLocked = false))
    }

    @Test
    fun nonAmbientSplitsByLockState() {
        assertEquals(SceneState.LOCK, SceneModeResolver.resolve(ambient = false, keyguardLocked = true))
        assertEquals(SceneState.HOME, SceneModeResolver.resolve(ambient = false, keyguardLocked = false))
    }

    @Test
    fun positiveControlIgnoringAmbientWouldMisjudgeAodAsLock() {
        val locked = true
        val wrong = if (locked) SceneState.LOCK else SceneState.HOME
        assertEquals("只看锁屏状态会把息屏判成锁屏", SceneState.LOCK, wrong)
        assertEquals(SceneState.AOD, SceneModeResolver.resolve(true, locked))
    }

    @Test
    fun modeRoundTripsForEveryState() {
        for (state in SceneState.values()) {
            val mode = SceneModeResolver.modeOf(state)
            assertTrue(SceneModeResolver.validMode(mode))
            assertEquals(state, SceneModeResolver.stateOfMode(mode))
        }
    }

    @Test
    fun modeNumbersMatchVendorOrder() {
        assertEquals(0, SceneModeResolver.modeOf(SceneState.AOD))
        assertEquals(1, SceneModeResolver.modeOf(SceneState.LOCK))
        assertEquals(2, SceneModeResolver.modeOf(SceneState.HOME))
    }

    @Test
    fun invalidModeIsRejectedNotClamped() {
        assertFalse(SceneModeResolver.validMode(-1))
        assertFalse(SceneModeResolver.validMode(3))
        assertNull(SceneModeResolver.stateOfMode(-1))
        assertNull(SceneModeResolver.stateOfMode(3))
    }

    @Test
    fun lockSettleOnlyWhenLeavingAodIntoLock() {
        assertTrue(
            SceneModeResolver.needsLockSettle(SceneState.AOD, SceneState.LOCK, keyguardLocked = true),
        )
        assertFalse(
            "已在锁屏就不必再等",
            SceneModeResolver.needsLockSettle(SceneState.LOCK, SceneState.LOCK, keyguardLocked = true),
        )
        assertFalse(
            "息屏直接进桌面不该等",
            SceneModeResolver.needsLockSettle(SceneState.AOD, SceneState.HOME, keyguardLocked = false),
        )
        assertFalse(
            "回息屏不该等",
            SceneModeResolver.needsLockSettle(SceneState.HOME, SceneState.AOD, keyguardLocked = false),
        )
    }

    @Test
    fun lockSettleAbandonedWhenUnlockedDuringWait() {
        assertFalse("等待期间已解锁就放弃这次锁屏", SceneModeResolver.lockStillHolds(false))
        assertTrue(SceneModeResolver.lockStillHolds(true))
    }

    @Test
    fun deltaSecondsConvertsAndClampsNegative() {
        assertEquals(0.033f, SceneModeResolver.deltaSeconds(33L), 1e-6f)
        assertEquals(0f, SceneModeResolver.deltaSeconds(0L), 1e-6f)
        assertEquals(0f, SceneModeResolver.deltaSeconds(-5L), 1e-6f)
    }

    @Test
    fun needsFrameWhileApproaching() {
        val cur = SceneCameraLerp.Pose()
        val tgt = SceneCameraLerp.Pose()
        SnowMountainCamera.target(SceneState.AOD, cur)
        SnowMountainCamera.target(SceneState.LOCK, tgt)
        cur.elapsed = 0f
        assertTrue("刚开始过渡必须要帧", SceneModeResolver.needsFrame(cur, tgt))
    }

    @Test
    fun needsFrameStopsOnceSettledAndAtTarget() {
        val cur = SceneCameraLerp.Pose()
        val tgt = SceneCameraLerp.Pose()
        SnowMountainCamera.target(SceneState.HOME, tgt)
        cur.copyFrom(tgt)
        cur.elapsed = SceneCameraLerp.SETTLE_SECONDS + 0.1f
        assertFalse("收敛且贴合目标后必须停帧", SceneModeResolver.needsFrame(cur, tgt))
    }

    @Test
    fun positiveControlSettledButFarFromTargetStillNeedsFrame() {
        val cur = SceneCameraLerp.Pose()
        val tgt = SceneCameraLerp.Pose()
        SnowMountainCamera.target(SceneState.AOD, cur)
        SnowMountainCamera.target(SceneState.LOCK, tgt)
        cur.elapsed = SceneCameraLerp.SETTLE_SECONDS + 5f
        assertTrue(
            "过渡时长已到但位姿还差一截时必须继续要帧（时长只用于降帧率）",
            SceneModeResolver.needsFrame(cur, tgt),
        )
        assertTrue("此时确实已过收敛时长", SceneCameraLerp.settled(cur))
        assertFalse("但位姿并未贴合", SceneModeResolver.atTarget(cur, tgt))
    }

    @Test
    fun settledFlagAloneMustNotStopFrames() {
        val cur = SceneCameraLerp.Pose()
        val tgt = SceneCameraLerp.Pose()
        SnowMountainCamera.target(SceneState.HOME, tgt)
        cur.copyFrom(tgt)
        cur.pz = tgt.pz + 5f
        cur.elapsed = SceneCameraLerp.SETTLE_SECONDS * 3f
        assertTrue(
            "阳性对照：若用 settled 当停帧判据，这里会留下 5 单位空档",
            SceneModeResolver.needsFrame(cur, tgt),
        )
    }

    @Test
    fun atTargetChecksAllTenChannels() {
        val cur = SceneCameraLerp.Pose()
        val tgt = SceneCameraLerp.Pose()
        SnowMountainCamera.target(SceneState.LOCK, tgt)
        cur.copyFrom(tgt)
        assertTrue(SceneModeResolver.atTarget(cur, tgt))
        cur.dark = tgt.dark + 0.5f
        assertFalse("暗态没到位就不算贴合", SceneModeResolver.atTarget(cur, tgt))
        cur.copyFrom(tgt)
        cur.height = tgt.height + 0.5f
        assertFalse("高度没到位就不算贴合", SceneModeResolver.atTarget(cur, tgt))
        cur.copyFrom(tgt)
        cur.lz = tgt.lz + 0.5f
        assertFalse("注视点没到位就不算贴合", SceneModeResolver.atTarget(cur, tgt))
    }
}
