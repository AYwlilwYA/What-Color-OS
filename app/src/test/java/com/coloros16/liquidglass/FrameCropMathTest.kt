package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.FrameCropMath
import com.coloros16.liquidglass.engine.FrameRearPoses
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCropMathTest {

    private fun crop(x: Float = 0.5f, y: Float = 0.5f, s: Float = 1f, a: Float = 0f) =
        FrameCropMath.Crop().apply { set(x, y, s, a) }

    @Test
    fun identityMatrixMultipliesNeutral() {
        val m = floatArrayOf(2f, 3f, 0f, 4f, 5f, 0f, 6f, 7f, 1f)
        assertArrayEquals(m, FrameCropMath.mul3(m, FrameCropMath.IDENTITY), 0.0001f)
        assertArrayEquals(m, FrameCropMath.mul3(FrameCropMath.IDENTITY, m), 0.0001f)
    }

    @Test
    fun mul3MatchesHandComputed() {
        val a = floatArrayOf(1f, 2f, 0f, 3f, 4f, 0f, 5f, 6f, 1f)
        val b = floatArrayOf(7f, 8f, 0f, 9f, 10f, 0f, 11f, 12f, 1f)
        val out = FrameCropMath.mul3(a, b)
        assertArrayEquals(
            floatArrayOf(31f, 46f, 0f, 39f, 58f, 0f, 52f, 76f, 1f),
            out,
            0.0001f,
        )
    }

    @Test
    fun defaultCropOnSquareImageIsIdentityScaled() {
        val m = FrameCropMath.cropMatrix(crop(), 720, 720)
        val expected = 720f * 1f * 720f / 320f / 720f
        assertEquals(expected, m[0], 0.0001f)
        assertEquals(expected, m[4], 0.0001f)
        assertEquals(0f, m[1], 0.0001f)
        assertEquals(0f, m[3], 0.0001f)
        assertEquals(0.5f, m[6], 0.0001f)
        assertEquals(0.5f, m[7], 0.0001f)
        assertEquals(1f, m[8], 0.0001f)
    }

    @Test
    fun nonSquareImageKeepsAspect() {
        val m = FrameCropMath.cropMatrix(crop(), 1600, 900)
        val pixels = 900f * 720f / 320f
        assertEquals(pixels / 1600f, m[0], 0.0001f)
        assertEquals(pixels / 900f, m[4], 0.0001f)
        assertEquals(1600f / 900f, m[4] / m[0], 0.001f)
    }

    @Test
    fun sizeScalesSampleSpan() {
        val full = FrameCropMath.samplePixels(crop(s = 1f), 720, 720)
        val half = FrameCropMath.samplePixels(crop(s = 0.5f), 720, 720)
        assertEquals(full / 2f, half, 0.001f)
    }

    @Test
    fun cropClampsOutOfRange() {
        val c = crop(x = 2f, y = -1f, s = 9f, a = 90f)
        assertEquals(1f, c.centerX, 0.0001f)
        assertEquals(0f, c.centerY, 0.0001f)
        assertEquals(FrameCropMath.MAX_SIZE, c.size, 0.0001f)
        assertEquals(FrameCropMath.MAX_ANGLE_DEG, c.angleDeg, 0.0001f)
        c.set(0.5f, 0.5f, 0.001f, -90f)
        assertEquals(FrameCropMath.MIN_SIZE, c.size, 0.0001f)
        assertEquals(-FrameCropMath.MAX_ANGLE_DEG, c.angleDeg, 0.0001f)
    }

    @Test
    fun degenerateImageFallsBackToIdentity() {
        assertArrayEquals(FrameCropMath.IDENTITY, FrameCropMath.cropMatrix(crop(), 0, 100), 0.0001f)
        assertEquals(0f, FrameCropMath.samplePixels(crop(), 100, -5), 0.0001f)
    }

    @Test
    fun rotationKeepsDeterminant() {
        val m = FrameCropMath.cropMatrix(crop(a = 30f), 800, 600)
        val det = m[0] * m[4] - m[1] * m[3]
        val straight = FrameCropMath.cropMatrix(crop(a = 0f), 800, 600)
        val det0 = straight[0] * straight[4] - straight[1] * straight[3]
        assertEquals(det0, det, 0.0001f)
    }

    @Test
    fun frontOriginMatchesMeshOffset() {
        val o = FrameCropMath.frontOrigin()
        assertEquals(0.5f, o[0], 0.0001f)
        assertEquals(348.5f / 720f, o[1], 0.0001f)
    }

    @Test
    fun rearIndexBoundaries() {
        assertEquals(FrameRearPoses.START_INDEX, FrameRearPoses.indexOf(0f))
        assertEquals(FrameRearPoses.START_INDEX, FrameRearPoses.indexOf(-5f))
        assertEquals(FrameRearPoses.END_INDEX, FrameRearPoses.indexOf(FrameRearPoses.END_MICROS))
        assertEquals(FrameRearPoses.END_INDEX, FrameRearPoses.indexOf(999999f))
        assertEquals(0, FrameRearPoses.indexOf(1f))
        assertEquals(58, FrameRearPoses.indexOf(480000f))
        assertEquals(58, FrameRearPoses.indexOf(FrameRearPoses.END_MICROS - 1f))
        assertEquals(1, FrameRearPoses.indexOf(8334f))
    }

    @Test
    fun rearStaticPoseIsPureTranslation() {
        val m = FrameRearPoses.matrix(200000f)
        assertEquals(1f, m[0], 0.0001f)
        assertEquals(1f, m[4], 0.0001f)
        assertEquals(0f, m[1], 0.0001f)
        assertEquals(0f, m[3], 0.0001f)
        assertEquals(-0.5f, m[6], 0.0001f)
        assertEquals(-0.484027778f, m[7], 0.00001f)
    }

    @Test
    fun rearFirstPoseRotates() {
        val m = FrameRearPoses.matrix(1f)
        assertEquals(0.974224024f, m[0], 0.00001f)
        assertEquals(-0.120068625f, m[1], 0.00001f)
        assertEquals(0.120068625f, m[3], 0.00001f)
        val p = FrameRearPoses.poseAt(0)
        assertEquals(-p[2] * p[0] - p[3] * p[1], m[6], 0.00001f)
        assertEquals(p[3] * p[0] - p[2] * p[1], m[7], 0.00001f)
    }

    @Test
    fun rearMatrixComposesCropAfterRear() {
        val c = crop(x = 0.3f, y = 0.7f, s = 0.6f, a = 20f)
        val composed = FrameCropMath.rearMatrix(c, 1000, 800, 1f)
        val manual = FrameCropMath.mul3(
            FrameCropMath.cropMatrix(c, 1000, 800),
            FrameRearPoses.matrix(1f),
        )
        assertArrayEquals(manual, composed, 0.0001f)
    }

    @Test
    fun transformAppliesTranslationColumn() {
        val out = FrameCropMath.transform(FrameCropMath.IDENTITY, 0.25f, 0.75f)
        assertEquals(0.25f, out[0], 0.0001f)
        assertEquals(0.75f, out[1], 0.0001f)
        val shifted = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.1f, -0.2f, 1f)
        val o2 = FrameCropMath.transform(shifted, 0.25f, 0.75f)
        assertEquals(0.35f, o2[0], 0.0001f)
        assertEquals(0.55f, o2[1], 0.0001f)
    }

    @Test
    fun pairRequiresFrameKindAndPhoto() {
        assertTrue(FrameCropMath.pairActive(1, true, true))
        assertFalse(FrameCropMath.pairActive(1, true, false))
        assertFalse(FrameCropMath.pairActive(1, false, true))
        assertFalse(FrameCropMath.pairActive(2, true, true))
        assertFalse(FrameCropMath.pairActive(101, true, true))
    }

    @Test
    fun frontSourceFollowsLockRatio() {
        assertEquals(1, FrameCropMath.frontSourceIndex(true, 0.4f))
        assertEquals(3, FrameCropMath.frontSourceIndex(true, 0f))
        assertEquals(3, FrameCropMath.frontSourceIndex(false, 0.9f))
    }
}