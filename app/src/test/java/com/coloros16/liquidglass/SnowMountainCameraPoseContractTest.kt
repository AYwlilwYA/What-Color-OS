package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.CameraMatrices
import com.coloros16.liquidglass.engine.CameraPose
import com.coloros16.liquidglass.engine.Mat4
import com.coloros16.liquidglass.engine.SceneCameraLerp
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SnowMountainCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SnowMountainCameraPoseContractTest {

    private fun poseOf(state: SceneState): SceneCameraLerp.Pose {
        val p = SceneCameraLerp.Pose()
        SnowMountainCamera.target(state, p)
        return p
    }

    @Test
    fun anglesRoundTripForwardVector() {
        for (state in listOf(SceneState.AOD, SceneState.LOCK, SceneState.HOME)) {
            val p = poseOf(state)
            val f = FloatArray(3)
            SnowMountainCamera.forward(p, f)
            val out = CameraPose()
            SnowMountainCamera.anglesFromForward(f[0], f[1], f[2], out)
            val yaw = Math.toRadians(out.yawDeg.toDouble())
            val pitch = Math.toRadians(out.pitchDeg.toDouble())
            val cp = cos(pitch)
            val len = kotlin.math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
            assertEquals(f[0] / len, (sin(yaw) * cp).toFloat(), 1e-4f)
            assertEquals(f[1] / len, sin(pitch).toFloat(), 1e-4f)
            assertEquals(f[2] / len, (-cos(yaw) * cp).toFloat(), 1e-4f)
        }
    }

    @Test
    fun toCameraPoseCopiesPositionAndDerivesAngles() {
        val p = poseOf(SceneState.AOD)
        val out = CameraPose()
        SnowMountainCamera.toCameraPose(p, out)
        assertEquals(79.3f, out.x, 1e-4f)
        assertEquals(-36.95f, out.y, 1e-4f)
        assertEquals(180f, out.z, 1e-4f)
        assertEquals(0f, out.rollDeg, 0f)
        val f = FloatArray(3)
        SnowMountainCamera.forward(p, f)
        val direct = CameraPose()
        SnowMountainCamera.anglesFromForward(f[0], f[1], f[2], direct)
        assertEquals(direct.yawDeg, out.yawDeg, 1e-4f)
        assertEquals(direct.pitchDeg, out.pitchDeg, 1e-4f)
    }

    @Test
    fun derivedAnglesAreNotVendorEulerFields() {
        val out = CameraPose()
        SnowMountainCamera.toCameraPose(poseOf(SceneState.AOD), out)
        assertTrue("yaw=${out.yawDeg} 不该等于厂商 Rot.x", kotlin.math.abs(out.yawDeg - (-7.9f)) > 1f)
        assertTrue("pitch=${out.pitchDeg} 不该等于厂商 Rot.y", kotlin.math.abs(out.pitchDeg - 23.115f) > 1f)
    }

    @Test
    fun zeroForwardGuardsNaN() {
        val out = CameraPose()
        SnowMountainCamera.anglesFromForward(0f, 0f, 0f, out)
        assertEquals(0f, out.yawDeg, 0f)
        assertEquals(0f, out.pitchDeg, 0f)
    }

    @Test
    fun straightUpClampsPitch() {
        val out = CameraPose()
        SnowMountainCamera.anglesFromForward(0f, 1f, 0f, out)
        assertEquals(89f, out.pitchDeg, 0.001f)
    }

    @Test
    fun defaultFarPlaneWouldClipAodCamera() {
        val p = poseOf(SceneState.AOD)
        val dist = kotlin.math.sqrt(p.px * p.px + p.py * p.py + p.pz * p.pz)
        assertTrue("AOD 相机距离 $dist 超过默认远面", dist > CameraMatrices.FAR_PLANE)
    }

    @Test
    fun requiredFarPlaneCoversAllStates() {
        val far = SnowMountainCamera.requiredFarPlane()
        for (state in listOf(SceneState.AOD, SceneState.LOCK, SceneState.HOME)) {
            val p = poseOf(state)
            val dist = kotlin.math.sqrt(p.px * p.px + p.py * p.py + p.pz * p.pz)
            assertTrue("state=$state dist=$dist far=$far", dist < far)
        }
    }

    @Test
    fun requiredFarPlaneIsDoubleMaxDistance() {
        val p = poseOf(SceneState.AOD)
        val dist = kotlin.math.sqrt(p.px * p.px + p.py * p.py + p.pz * p.pz)
        assertEquals(dist * 2f, SnowMountainCamera.requiredFarPlane(), 0.01f)
    }

    @Test
    fun explicitFarPlaneProjectionDiffersFromDefault() {
        val pose = CameraPose().apply { fovDeg = 55f }
        val a = Mat4()
        val b = Mat4()
        CameraMatrices.buildProjection(pose, 0.45f, a)
        CameraMatrices.buildProjection(pose, 0.45f, SnowMountainCamera.requiredFarPlane(), b)
        var diff = false
        for (i in a.m.indices) if (kotlin.math.abs(a.m[i] - b.m[i]) > 1e-6f) diff = true
        assertTrue("指定远面必须真的改变投影矩阵", diff)
    }

    @Test
    fun invalidFarPlaneFallsBackToDefault() {
        val pose = CameraPose().apply { fovDeg = 55f }
        val a = Mat4()
        val b = Mat4()
        CameraMatrices.buildProjection(pose, 0.45f, a)
        CameraMatrices.buildProjection(pose, 0.45f, 0f, b)
        for (i in a.m.indices) assertEquals(a.m[i], b.m[i], 1e-6f)
    }

    @Test
    fun viewMatrixFromDerivedAnglesLooksAtTarget() {
        val p = poseOf(SceneState.LOCK)
        val pose = CameraPose()
        SnowMountainCamera.toCameraPose(p, pose)
        val view = Mat4()
        CameraMatrices.buildView(pose, view)
        val cx = p.lx - p.px
        val cy = p.ly - p.py
        val cz = p.lz - p.pz
        val vx = view.m[0] * cx + view.m[4] * cy + view.m[8] * cz
        val vy = view.m[1] * cx + view.m[5] * cy + view.m[9] * cz
        assertTrue("注视点在视空间应几乎落在光轴上 vx=$vx vy=$vy", kotlin.math.abs(vx) < 0.01f && kotlin.math.abs(vy) < 0.01f)
    }
}