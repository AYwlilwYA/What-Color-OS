package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.EffectOverlayPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectOverlayPolicyTest {

    @Test
    fun smallSizeIsKeptAsIs() {
        assertArrayEquals(intArrayOf(320, 640), EffectOverlayPolicy.targetSize(320, 640))
        assertArrayEquals(intArrayOf(720, 720), EffectOverlayPolicy.targetSize(720, 720))
    }

    @Test
    fun longSideIsCappedKeepingAspect() {
        val size = EffectOverlayPolicy.targetSize(1080, 2376)
        assertEquals(327, size[0])
        assertEquals(720, size[1])
        val ratioIn = 1080f / 2376f
        val ratioOut = size[0].toFloat() / size[1].toFloat()
        assertEquals(ratioIn, ratioOut, 0.01f)
    }

    @Test
    fun landscapeIsCappedOnWidth() {
        val size = EffectOverlayPolicy.targetSize(2376, 1080)
        assertEquals(720, size[0])
        assertEquals(327, size[1])
    }

    @Test
    fun degenerateSizeNeverBecomesZero() {
        assertArrayEquals(intArrayOf(0, 100), EffectOverlayPolicy.targetSize(0, 100))
        assertArrayEquals(intArrayOf(100, -5), EffectOverlayPolicy.targetSize(100, -5))
        val thin = EffectOverlayPolicy.targetSize(1, 100000)
        assertTrue(thin[0] >= 1)
        assertTrue(thin[1] >= 1)
    }

    @Test
    fun keyIgnoresRawSizeWhenDownsampleMatches() {
        val a = EffectOverlayPolicy.keyOf("lock.wp.oplus.water", 1080, 2376)
        val b = EffectOverlayPolicy.keyOf("lock.wp.oplus.water", 1082, 2380)
        assertEquals(a, b)
    }

    @Test
    fun keySeparatesEffectIds() {
        val a = EffectOverlayPolicy.keyOf("lock.wp.oplus.water", 1080, 2376)
        val b = EffectOverlayPolicy.keyOf("lock.wp.oplus.grid", 1080, 2376)
        assertFalse(a == b)
    }

    @Test
    fun keySeparatesNullEffect() {
        val a = EffectOverlayPolicy.keyOf(null, 1080, 2376)
        val b = EffectOverlayPolicy.keyOf("lock.wp.oplus.water", 1080, 2376)
        assertFalse(a == b)
    }

    @Test
    fun alphaThresholdSkipsNearZero() {
        assertFalse(EffectOverlayPolicy.drawable(0f))
        assertFalse(EffectOverlayPolicy.drawable(0.001f))
        assertTrue(EffectOverlayPolicy.drawable(0.01f))
        assertTrue(EffectOverlayPolicy.drawable(1f))
    }

    @Test
    fun alphaByteClampsAndScales() {
        assertEquals(0, EffectOverlayPolicy.alphaByte(-1f))
        assertEquals(0, EffectOverlayPolicy.alphaByte(0f))
        assertEquals(127, EffectOverlayPolicy.alphaByte(0.5f))
        assertEquals(255, EffectOverlayPolicy.alphaByte(1f))
        assertEquals(255, EffectOverlayPolicy.alphaByte(3f))
    }

    @Test
    fun sameAlphaDedupesPerFrameJitter() {
        assertTrue(EffectOverlayPolicy.sameAlpha(0.5f, 0.5005f))
        assertFalse(EffectOverlayPolicy.sameAlpha(0.5f, 0.52f))
    }

    @Test
    fun strengthFollowsLockLayerOnly() {
        assertEquals(0f, EffectOverlayPolicy.strengthFor(0f), 0.0001f)
        assertEquals(0.4f, EffectOverlayPolicy.strengthFor(0.4f), 0.0001f)
        assertEquals(1f, EffectOverlayPolicy.strengthFor(1f), 0.0001f)
        assertEquals(0f, EffectOverlayPolicy.strengthFor(-0.3f), 0.0001f)
        assertEquals(1f, EffectOverlayPolicy.strengthFor(1.7f), 0.0001f)
    }
}