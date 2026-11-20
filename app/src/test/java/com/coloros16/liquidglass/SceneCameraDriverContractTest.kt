package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneCameraDriver
import com.coloros16.liquidglass.engine.SceneCameraLerp
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SnowMountainCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景相机驱动契约：换态只换目标、当前值连续演化、收敛后停帧、位姿由注视点反解。
 */
class SceneCameraDriverContractTest {

    @Test
    fun resetPutsCurrentAtTargetImmediately() {
        val d = SceneCameraDriver()
        d.reset(SceneState.LOCK)
        assertEquals(SceneState.LOCK, d.state)
        assertEquals(SnowMountainCamera.LOCK_POS[2], d.current.pz, 1e-4f)
        assertFalse("就位后不需要过渡帧", d.needsFrame())
    }

    @Test
    fun retargetReportsChangeOnlyOnStateChange() {
        val d = SceneCameraDriver()
        d.reset(SceneState.AOD)
        assertTrue(d.retarget(SceneState.LOCK, snap = false))
        assertFalse("同态再喂不算变化", d.retarget(SceneState.LOCK, snap = false))
    }

    @Test
    fun retargetDoesNotJumpCurrentValue() {
        val d = SceneCameraDriver()
        d.reset(SceneState.AOD)
        val before = d.current.pz
        d.retarget(SceneState.LOCK, snap = false)
        assertEquals("换目标当帧位置不得跳变", before, d.current.pz, 1e-4f)
        assertEquals(SnowMountainCamera.LOCK_POS[2], d.target.pz, 1e-4f)
    }

    @Test
    fun snapRetargetJumpsAndClearsElapsed() {
        val d = SceneCameraDriver()
        d.reset(SceneState.AOD)
        d.advance(100L)
        d.retarget(SceneState.HOME, snap = true)
        assertEquals(SnowMountainCamera.HOME_POS[2], d.current.pz, 1e-4f)
        assertEquals(0f, d.current.elapsed, 1e-6f)
    }

    @Test
    fun anyStatePairConvergesWithoutJump() {
        for (from in SceneState.values()) {
            for (to in SceneState.values()) {
                val d = SceneCameraDriver()
                d.reset(from)
                d.retarget(to, snap = false)
                var last = d.current.pz
                for (i in 0 until 200) {
                    d.advance(16L)
                    val now = d.current.pz
                    assertTrue(
                        "from=$from to=$to step=$i 位置不得跳变 last=$last now=$now",
                        kotlin.math.abs(now - last) < 8f,
                    )
                    last = now
                }
                assertEquals(
                    "from=$from to=$to 应收敛到目标",
                    d.target.pz, d.current.pz, 0.5f,
                )
            }
        }
    }

    @Test
    fun needsFrameGoesFalseAfterConvergence() {
        val d = SceneCameraDriver()
        d.reset(SceneState.AOD)
        d.retarget(SceneState.LOCK, snap = false)
        assertTrue(d.needsFrame())
        for (i in 0 until 400) d.advance(16L)
        assertFalse("逼近到位后必须停帧", d.needsFrame())
    }

    @Test
    fun poseAnglesComeFromLookAtNotVendorEuler() {
        val d = SceneCameraDriver()
        d.reset(SceneState.AOD)
        val p = d.pose()
        assertEquals(SnowMountainCamera.AOD_POS[0], p.x, 1e-3f)
        assertEquals(SnowMountainCamera.AOD_POS[2], p.z, 1e-3f)
        val vendorYaw = -7.9f
        assertTrue(
            "反解偏航必须与厂商欧拉角不同（厂商 AOD Rot.x=$vendorYaw，反解得 ${p.yawDeg}）",
            kotlin.math.abs(p.yawDeg - vendorYaw) > 1f,
        )
        assertEquals(0f, p.rollDeg, 1e-6f)
    }

    @Test
    fun poseLooksAtTargetInViewSpace() {
        val d = SceneCameraDriver()
        d.reset(SceneState.LOCK)
        val p = d.pose()
        val view = com.coloros16.liquidglass.engine.Mat4()
        com.coloros16.liquidglass.engine.CameraMatrices.buildView(p, view)
        val lx = SnowMountainCamera.LOCK_LOOK[0]
        val ly = SnowMountainCamera.LOCK_LOOK[1]
        val lz = SnowMountainCamera.LOCK_LOOK[2]
        val vx = view.m[0] * lx + view.m[4] * ly + view.m[8] * lz + view.m[12]
        val vy = view.m[1] * lx + view.m[5] * ly + view.m[9] * lz + view.m[13]
        assertEquals("注视点视空间 x 必须近零", 0f, vx, 0.05f)
        assertEquals("注视点视空间 y 必须近零", 0f, vy, 0.05f)
    }

    @Test
    fun farPlaneExceedsEveryCameraDistance() {
        val d = SceneCameraDriver()
        val far = d.farPlane()
        for (p in listOf(
            SnowMountainCamera.AOD_POS,
            SnowMountainCamera.LOCK_POS,
            SnowMountainCamera.HOME_POS,
        )) {
            val dist = kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            assertTrue("远面 $far 必须大于相机距离 $dist", far > dist)
        }
    }

    @Test
    fun darkAndRotFollowStateTruth() {
        val d = SceneCameraDriver()
        d.reset(SceneState.AOD)
        assertEquals(SnowMountainCamera.AOD_DARK, d.dark(), 1e-4f)
        assertEquals(SnowMountainCamera.AOD_ROT, d.rot(), 1e-4f)
        d.reset(SceneState.HOME)
        assertEquals(SnowMountainCamera.HOME_DARK, d.dark(), 1e-4f)
        assertEquals(SnowMountainCamera.HOME_ROT, d.rot(), 1e-4f)
    }

    @Test
    fun abnormalDeltaFallsBackNotExplodes() {
        val d = SceneCameraDriver()
        d.reset(SceneState.AOD)
        d.retarget(SceneState.LOCK, snap = false)
        val before = d.current.pz
        d.advance(5000L)
        val step = kotlin.math.abs(d.current.pz - before)
        val maxStep = kotlin.math.abs(SnowMountainCamera.LOCK_POS[2] - before) *
            SceneCameraLerp.DT_FALLBACK * SceneCameraLerp.SPEED
        assertTrue("超长帧间隔必须回落基准步长", step <= maxStep + 1e-3f)
    }
}
