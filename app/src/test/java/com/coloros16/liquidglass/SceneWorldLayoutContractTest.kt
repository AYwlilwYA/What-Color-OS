package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneWorldLayout
import com.coloros16.liquidglass.engine.SnowMountainCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景世界几何契约：顶点数自洽、环带首尾闭合、星壳与月球都在远面内。
 */
class SceneWorldLayoutContractTest {

    @Test
    fun ridgeRingFillsExactlyDeclaredFloats() {
        val seg = SceneWorldLayout.RIDGE_SEGMENTS
        val out = FloatArray(SceneWorldLayout.ridgeVertexFloats(seg))
        SceneWorldLayout.ridgeRing(96f, 26f, 1.7f, seg, out)
        assertTrue("环带必须写满每个分量", out.any { it != 0f })
        assertEquals(seg * 6, out.size / 3)
    }

    @Test
    fun ridgeRingSeamIsContinuous() {
        val seg = 64
        val out = FloatArray(SceneWorldLayout.ridgeVertexFloats(seg))
        SceneWorldLayout.ridgeRing(90f, 20f, 1.7f, seg, out)
        val firstX = out[0]
        val firstZ = out[2]
        val lastSegStart = (seg - 1) * 18
        val endX = out[lastSegStart + 3]
        val endZ = out[lastSegStart + 5]
        assertEquals("末段终点 x 必须接回首段起点", firstX, endX, 1e-2f)
        assertEquals("末段终点 z 必须接回首段起点", firstZ, endZ, 1e-2f)
        val firstH = out[6 + 1]
        val endH = out[lastSegStart + 12 + 1]
        assertEquals("接缝高度必须相等否则有裂口", firstH, endH, 1e-2f)
    }

    @Test
    fun ridgeHeightStaysAboveGroundAndBelowAmp() {
        val amp = SceneWorldLayout.RIDGE_FAR_AMP
        for (i in 0..200) {
            val h = SceneWorldLayout.ridgeHeight(i / 200f, 1.7f, amp)
            assertTrue("高度不得低于地面 h=$h", h >= SceneWorldLayout.GROUND_Y - 1e-3f)
            assertTrue("高度不得超出幅度 h=$h", h <= SceneWorldLayout.GROUND_Y + amp + 1e-3f)
        }
    }

    @Test
    fun undersizedOutputIsNotWritten() {
        val small = FloatArray(3)
        SceneWorldLayout.ridgeRing(96f, 26f, 1.7f, 10, small)
        assertTrue("容量不足必须整体不写", small.all { it == 0f })
    }

    @Test
    fun domeVertexAndIndexCountsMatch() {
        val lat = 8
        val lon = 16
        val v = FloatArray(SceneWorldLayout.domeVertexFloats(lat, lon))
        SceneWorldLayout.domeVertices(lat, lon, 10f, v)
        assertEquals((lat + 1) * (lon + 1), v.size / 3)
        val idx = ShortArray(SceneWorldLayout.domeIndexCount(lat, lon))
        SceneWorldLayout.domeIndices(lat, lon, idx)
        assertEquals(lat * lon * 6, idx.size)
        val maxIndex = idx.max().toInt()
        assertTrue("索引不得越界顶点表", maxIndex < v.size / 3)
    }

    @Test
    fun domeVerticesLieOnRequestedRadius() {
        val v = FloatArray(SceneWorldLayout.domeVertexFloats(6, 12))
        SceneWorldLayout.domeVertices(6, 12, 25f, v)
        var i = 0
        while (i < v.size) {
            val d = kotlin.math.sqrt(v[i] * v[i] + v[i + 1] * v[i + 1] + v[i + 2] * v[i + 2])
            assertEquals("球面顶点模长必须等于半径", 25f, d, 1e-2f)
            i += 3
        }
    }

    @Test
    fun starFieldFillsSevenFloatsPerStar() {
        val n = 200
        val out = FloatArray(SceneWorldLayout.starVertexFloats(n))
        SceneWorldLayout.starField(n, SceneWorldLayout.STAR_SEED, out)
        assertEquals(n, out.size / 7)
        var i = 0
        while (i < out.size) {
            assertTrue("星点必须在地平线以上 y=${out[i + 1]}", out[i + 1] >= 0f)
            assertTrue("星点尺寸必须为正", out[i + 3] > 0f)
            i += 7
        }
    }

    @Test
    fun starFieldIsDeterministicForSameSeed() {
        val a = FloatArray(SceneWorldLayout.starVertexFloats(64))
        val b = FloatArray(SceneWorldLayout.starVertexFloats(64))
        SceneWorldLayout.starField(64, SceneWorldLayout.STAR_SEED, a)
        SceneWorldLayout.starField(64, SceneWorldLayout.STAR_SEED, b)
        assertTrue("同种子必须同分布", a.contentEquals(b))
        val c = FloatArray(SceneWorldLayout.starVertexFloats(64))
        SceneWorldLayout.starField(64, SceneWorldLayout.STAR_SEED + 1L, c)
        assertTrue("换种子必须换分布（阳性对照）", !a.contentEquals(c))
    }

    @Test
    fun starsStayInsideFarPlaneFromEveryCameraState() {
        val far = SnowMountainCamera.requiredFarPlane()
        val maxDist = SceneWorldLayout.maxVisibleDistance()
        assertTrue("最远星点距离 $maxDist 必须小于远面 $far", maxDist < far)
    }

    @Test
    fun positiveControlOldStarRadiusWouldExceedFarPlane() {
        val far = SnowMountainCamera.requiredFarPlane()
        val maxCam = kotlin.math.sqrt(
            SnowMountainCamera.AOD_POS[0] * SnowMountainCamera.AOD_POS[0] +
                SnowMountainCamera.AOD_POS[1] * SnowMountainCamera.AOD_POS[1] +
                SnowMountainCamera.AOD_POS[2] * SnowMountainCamera.AOD_POS[2],
        )
        val tooBigShell = 280f
        assertTrue(
            "星壳取 280 会超出远面（阳性对照：说明壳半径不能随手放大）",
            tooBigShell + maxCam > far,
        )
    }

    @Test
    fun moonStaysInsideFarPlane() {
        val far = SnowMountainCamera.requiredFarPlane()
        val p = SceneWorldLayout.MOON_POS
        val cam = SnowMountainCamera.AOD_POS
        val dx = p[0] - cam[0]
        val dy = p[1] - cam[1]
        val dz = p[2] - cam[2]
        val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) + SceneWorldLayout.MOON_RADIUS
        assertTrue("最远相机到月球距离 $dist 必须小于远面 $far", dist < far)
    }

    @Test
    fun dimColorInterpolatesTowardsNight() {
        val out = FloatArray(3)
        SceneWorldLayout.dimColor(0.4f, 0.5f, 0.6f, 0f, out)
        assertEquals(0.4f, out[0], 1e-5f)
        assertEquals(0.6f, out[2], 1e-5f)
        SceneWorldLayout.dimColor(0.4f, 0.5f, 0.6f, 1f, out)
        assertEquals(SceneWorldLayout.NIGHT_R, out[0], 1e-5f)
        assertEquals(SceneWorldLayout.NIGHT_B, out[2], 1e-5f)
        SceneWorldLayout.dimColor(0.4f, 0.5f, 0.6f, 0.5f, out)
        assertEquals((0.4f + SceneWorldLayout.NIGHT_R) / 2f, out[0], 1e-5f)
    }

    @Test
    fun dimColorClampsOutOfRangeDark() {
        val out = FloatArray(3)
        SceneWorldLayout.dimColor(0.4f, 0.5f, 0.6f, -3f, out)
        assertEquals("负暗态按 0 处理", 0.4f, out[0], 1e-5f)
        SceneWorldLayout.dimColor(0.4f, 0.5f, 0.6f, 9f, out)
        assertEquals("超 1 暗态按 1 处理", SceneWorldLayout.NIGHT_R, out[0], 1e-5f)
    }
}
