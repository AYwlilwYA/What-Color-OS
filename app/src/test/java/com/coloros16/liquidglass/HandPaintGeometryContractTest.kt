package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.HandPaintGeometry
import com.coloros16.liquidglass.engine.HandPaintMotion
import com.coloros16.liquidglass.engine.HandPaintPoint
import com.coloros16.liquidglass.engine.HandPaintSilk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandPaintGeometryContractTest {

    @Test
    fun canvasSideIsDoubleCenter() {
        assertEquals(1020f, HandPaintGeometry.canvasSide(510f, 510f), 0.001f)
        assertEquals(1360f, HandPaintGeometry.canvasSide(680f, 680f), 0.001f)
    }

    @Test
    fun fitUsesShortSideAndCenters() {
        val f = HandPaintGeometry.fit(1020f, 1080f, 2376f)
        assertEquals(1080f / 1020f, f[0], 0.0001f)
        assertEquals(0f, f[1], 0.001f)
        assertEquals((2376f - 1080f) / 2f, f[2], 0.001f)
    }

    @Test
    fun fitMapsCenterToTargetCenter() {
        val f = HandPaintGeometry.fit(1020f, 1080f, 2376f)
        assertEquals(540f, HandPaintGeometry.mapX(510f, f), 0.01f)
        assertEquals(1188f, HandPaintGeometry.mapY(510f, f), 0.01f)
    }

    @Test
    fun fitGuardsNonPositiveInput() {
        val f = HandPaintGeometry.fit(0f, 1080f, 2376f)
        assertEquals(1f, f[0], 0f)
        assertEquals(0f, f[1], 0f)
        assertEquals(0f, f[2], 0f)
    }

    @Test
    fun fitSquareTargetHasNoOffset() {
        val f = HandPaintGeometry.fit(1020f, 512f, 512f)
        assertEquals(0f, f[1], 0.001f)
        assertEquals(0f, f[2], 0.001f)
    }

    @Test
    fun replayDurationGrowsWithPointCount() {
        val short = HandPaintGeometry.replayDurationMs(7200)
        val long = HandPaintGeometry.replayDurationMs(25920)
        assertTrue("short=$short long=$long", long > short)
    }

    @Test
    fun replayDurationClampedAtBothEnds() {
        assertEquals(HandPaintGeometry.REPLAY_MIN_MS, HandPaintGeometry.replayDurationMs(0))
        assertEquals(HandPaintGeometry.REPLAY_MIN_MS, HandPaintGeometry.replayDurationMs(10))
        assertEquals(HandPaintGeometry.REPLAY_MAX_MS, HandPaintGeometry.replayDurationMs(1_000_000))
    }

    @Test
    fun replayDurationMatchesFormulaInMidRange() {
        val pts = 13001
        val expected = pts.toLong() * HandPaintGeometry.REPLAY_MS_PER_KILO_POINT / 1000L
        assertEquals(expected, HandPaintGeometry.replayDurationMs(pts))
    }

    @Test
    fun realPresetDurationsLandInSecondScale() {
        val presets = intArrayOf(13001, 12815, 9936, 7199, 14400, 25918, 14398, 13029, 13932)
        for (p in presets) {
            val ms = HandPaintGeometry.replayDurationMs(p)
            assertTrue("pts=$p ms=$ms", ms in 3000L..15000L)
        }
    }

    @Test
    fun mirrorFlipsScaleOnlyWhenEnabled() {
        assertEquals(-1f, HandPaintGeometry.mirrorOf(true, 540f)[0], 0f)
        assertEquals(1f, HandPaintGeometry.mirrorOf(false, 540f)[0], 0f)
        assertEquals(540f, HandPaintGeometry.mirrorOf(true, 540f)[1], 0f)
    }

    @Test
    fun spiralScaleShrinksMonotonically() {
        assertEquals(1f, HandPaintGeometry.spiralScale(0), 0.0001f)
        val s1 = HandPaintGeometry.spiralScale(1)
        val s2 = HandPaintGeometry.spiralScale(2)
        assertTrue(s1 < 1f && s2 < s1)
    }

    @Test
    fun spiralAngleOffsetsByHalfRotationCell() {
        assertEquals(0f, HandPaintGeometry.spiralAngleDeg(0, 6), 0.0001f)
        assertEquals(30f, HandPaintGeometry.spiralAngleDeg(1, 6), 0.0001f)
        assertEquals(60f, HandPaintGeometry.spiralAngleDeg(2, 6), 0.0001f)
    }

    @Test
    fun strokeAlphaTruncatesLowValues() {
        assertEquals(25, HandPaintGeometry.strokeAlphaByte(0.0993f))
        assertEquals(0, HandPaintGeometry.strokeAlphaByte(0f))
        assertEquals(255, HandPaintGeometry.strokeAlphaByte(1f))
    }

    @Test
    fun incrementOnlyMovesForward() {
        val r = HandPaintGeometry.incrementRange(100, 0.5f, 1000)
        assertEquals(100, r[0])
        assertEquals(500, r[1])
    }

    @Test
    fun incrementEmptyWhenAlreadyPastTarget() {
        val r = HandPaintGeometry.incrementRange(600, 0.5f, 1000)
        assertEquals(r[0], r[1])
    }

    @Test
    fun reverseProgressNeedsReset() {
        assertTrue(HandPaintGeometry.needsReset(600, 0.5f, 1000))
        assertFalse(HandPaintGeometry.needsReset(100, 0.5f, 1000))
    }

    @Test
    fun fullProgressDrawsEverything() {
        val r = HandPaintGeometry.incrementRange(0, 1f, 1000)
        assertEquals(0, r[0])
        assertEquals(1000, r[1])
        assertTrue(HandPaintMotion.finished(1f, 1000))
    }

    @Test
    fun zeroTotalNeverDraws() {
        val r = HandPaintGeometry.incrementRange(0, 1f, 0)
        assertEquals(r[0], r[1])
    }

    @Test
    fun copiesMatchVendorPresetOne() {
        val silk = HandPaintSilk(
            id = 0, enabled = true, mirror = true, rotates = 6, spirals = 2,
            brushLineWidth = 2f, centerX = 510f, centerY = 510f,
            points = listOf(HandPaintPoint(0f, 0f, 1f, 1f, 1f, 0.05f)),
        )
        assertEquals(24, HandPaintMotion.copiesOf(silk))
    }

    @Test
    fun copyAnglesAreEvenlySpaced() {
        val silk = HandPaintSilk(0, true, true, 5, 1, 2f, 510f, 510f, emptyList())
        assertEquals(0f, HandPaintMotion.copyAngleDeg(silk, 0), 0.0001f)
        assertEquals(72f, HandPaintMotion.copyAngleDeg(silk, 1), 0.0001f)
        assertEquals(288f, HandPaintMotion.copyAngleDeg(silk, 4), 0.0001f)
    }
}