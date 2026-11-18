package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.CameraKeyframe
import com.coloros16.liquidglass.engine.CameraMatrices
import com.coloros16.liquidglass.engine.CameraOrientation
import com.coloros16.liquidglass.engine.CameraPath
import com.coloros16.liquidglass.engine.CameraPathSpec
import com.coloros16.liquidglass.engine.CameraPaths
import com.coloros16.liquidglass.engine.CameraPose
import com.coloros16.liquidglass.engine.Mat4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPathTest {

    @Test
    fun presetPathsSampleAtEndpointsAndLoop() {
        CameraPaths.presetIds.forEach { id ->
            val path = CameraPaths.byId(id)
            val start = CameraPose()
            val end = CameraPose()
            path.sample(0f, start)
            path.sample(1f, end)
            assertEquals("$id x", start.x, end.x, 0.002f)
            assertEquals("$id y", start.y, end.y, 0.002f)
            assertEquals("$id z", start.z, end.z, 0.002f)
            assertEquals("$id yaw", start.yawDeg, end.yawDeg, 0.05f)
        }
    }

    @Test
    fun stillPathKeepsPoseConstant() {
        val path = CameraPaths.byId(CameraPaths.STILL)
        val a = CameraPose()
        val b = CameraPose()
        path.sample(0f, a)
        path.sample(0.73f, b)
        assertEquals(a.x, b.x, 1e-5f)
        assertEquals(a.y, b.y, 1e-5f)
        assertEquals(a.z, b.z, 1e-5f)
        assertEquals(a.fovDeg, b.fovDeg, 1e-5f)
    }

    @Test
    fun samplingIsContinuousAcrossKeyframes() {
        val path = CameraPath(
            listOf(
                CameraKeyframe(0f, 0f, 0f, 0f, 0f, 0f, 0f, 55f),
                CameraKeyframe(0.4f, 1.5f, 0.6f, 0.2f, 30f, 5f, -4f, 58f),
                CameraKeyframe(1f, 0f, 0f, 0f, 0f, 0f, 0f, 55f),
            ),
        )
        val left = CameraPose()
        val right = CameraPose()
        path.sample(0.4f - 1e-4f, left)
        path.sample(0.4f + 1e-4f, right)
        assertEquals(left.x, right.x, 0.01f)
        assertEquals(left.yawDeg, right.yawDeg, 0.05f)
    }

    @Test
    fun customPathParsesAndSorts() {
        val text = """
            # 自定义
            0.8, 0.5, 0.1, 0.2, 10, 2, 0, 60
            0.0, 0, 0, 0, 0, 0, 0, 55
            0.4, -0.5, 0.4, 0.5, -10, -2, 0, 58
        """.trimIndent()
        val path = CameraPathSpec.parse(text)
        assertNotNull(path)
        assertEquals(3, path!!.keyframes.size)
        assertEquals(0f, path.keyframes[0].t, 1e-5f)
        assertEquals(0.4f, path.keyframes[1].t, 1e-5f)
        assertEquals(0.8f, path.keyframes[2].t, 1e-5f)
        val pose = CameraPose()
        path.sample(0.8f, pose)
        assertEquals(0.5f, pose.x, 1e-4f)
        assertEquals(60f, pose.fovDeg, 1e-4f)
    }

    @Test
    fun customPathRejectsMalformedInput() {
        assertNull(CameraPathSpec.parse(null))
        assertNull(CameraPathSpec.parse(""))
        assertNull(CameraPathSpec.parse("0,0,0,0,0,0,0"))
        assertNull(CameraPathSpec.parse("0,0,0,0,0,0,0,55\n1,2,x,4,5,6,7,8"))
    }

    @Test
    fun customPathRoundTripsThroughFormat() {
        val formatted = CameraPathSpec.format(CameraPaths.byId(CameraPaths.ORBIT))
        val parsed = CameraPathSpec.parse(formatted)
        assertNotNull(parsed)
        assertEquals(5, parsed!!.keyframes.size)
    }

    @Test
    fun cycleProgressWrapsAndScales() {
        assertEquals(0f, CameraOrientation.cycleProgress(0, 10_000, 1f), 1e-5f)
        assertEquals(0.5f, CameraOrientation.cycleProgress(5_000, 10_000, 1f), 1e-5f)
        assertEquals(0f, CameraOrientation.cycleProgress(10_000, 10_000, 1f), 1e-5f)
        assertEquals(0.0f, CameraOrientation.cycleProgress(20_000, 10_000, 1f), 1e-5f)
        assertEquals(0.5f, CameraOrientation.cycleProgress(2_500, 10_000, 2f), 1e-5f)
    }

    @Test
    fun forwardVectorFollowsYawAndPitch() {
        val front = CameraOrientation.forward(0f, 0f)
        assertEquals(0f, front[0], 1e-5f)
        assertEquals(0f, front[1], 1e-5f)
        assertEquals(-1f, front[2], 1e-5f)

        val right = CameraOrientation.forward(90f, 0f)
        assertEquals(1f, right[0], 1e-5f)
        assertEquals(0f, right[2], 1e-5f)

        val up = CameraOrientation.forward(0f, 30f)
        assertEquals(0.5f, up[1], 1e-5f)
        assertTrue(up[2] < 0f)
    }

    @Test
    fun perspectiveMatrixUsesFovAndAspect() {
        val mat = Mat4()
        mat.perspective(90f, 2f, 0.1f, 100f)
        assertEquals(0.5f, mat.m[0], 1e-4f)
        assertEquals(1f, mat.m[5], 1e-4f)
        assertEquals(-1f, mat.m[11], 1e-4f)
    }

    @Test
    fun lookAtForwardProducesExpectedViewDepth() {
        val mat = Mat4()
        mat.lookAt(0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        assertEquals(1f, mat.m[0], 1e-4f)
        assertEquals(1f, mat.m[5], 1e-4f)
        assertEquals(1f, mat.m[10], 1e-4f)
        val z = mat.m[2] * 0f + mat.m[6] * 0f + mat.m[10] * -5f + mat.m[14]
        assertEquals(-5f, z, 1e-4f)
    }

    @Test
    fun viewProjectionMovesCameraForwardPointIntoClipSpace() {
        val pose = CameraPose()
        pose.set(0f, 0f, 0f, 0f, 0f, 0f, 90f)
        val vp = Mat4()
        CameraMatrices.buildViewProjection(pose, 1f, vp)
        val point = floatArrayOf(0f, 0f, -5f, 1f)
        val out = FloatArray(4)
        for (row in 0 until 4) {
            var sum = 0f
            for (col in 0 until 4) sum += vp.m[col * 4 + row] * point[col]
            out[row] = sum
        }
        assertTrue(out[3] > 1f)
        assertEquals(0f, out[0] / out[3], 1e-4f)
        assertEquals(0f, out[1] / out[3], 1e-4f)
    }
}
