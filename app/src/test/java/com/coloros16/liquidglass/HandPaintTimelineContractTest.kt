package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.HandPaintGeometry
import com.coloros16.liquidglass.engine.HandPaintSource
import com.coloros16.liquidglass.engine.HandPaintTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandPaintTimelineContractTest {

    private fun play(style: Int = 1, points: Int = 13001, now: Long = 1000L): HandPaintTimeline.Play {
        val p = HandPaintTimeline.Play()
        HandPaintTimeline.start(p, style, now, points)
        return p
    }

    @Test
    fun startSetsDurationFromPointCount() {
        val p = play(points = 13001)
        assertEquals(HandPaintGeometry.replayDurationMs(13001), p.durationMs)
        assertTrue(p.running)
    }

    @Test
    fun sameStyleWhileRunningDoesNotRestart() {
        val p = play(now = 1000L)
        val origin = p.originMs
        assertFalse(HandPaintTimeline.start(p, 1, 5000L, 13001))
        assertEquals(origin, p.originMs)
    }

    @Test
    fun differentStyleRestarts() {
        val p = play(style = 1, now = 1000L)
        assertTrue(HandPaintTimeline.start(p, 2, 5000L, 12815))
        assertEquals(5000L, p.originMs)
        assertEquals(2, p.style)
    }

    @Test
    fun restartAfterStop() {
        val p = play()
        HandPaintTimeline.stop(p)
        assertTrue(HandPaintTimeline.start(p, 1, 9000L, 13001))
        assertEquals(9000L, p.originMs)
    }

    @Test
    fun progressIsZeroBeforeStart() {
        val p = HandPaintTimeline.Play()
        assertEquals(0f, HandPaintTimeline.progressAt(p, 5000L), 0f)
    }

    @Test
    fun progressAtOriginIsZero() {
        val p = play(now = 1000L)
        assertEquals(0f, HandPaintTimeline.progressAt(p, 1000L), 0.0001f)
    }

    @Test
    fun progressAtHalfDuration() {
        val p = play(now = 1000L)
        val mid = 1000L + p.durationMs / 2
        assertEquals(0.5f, HandPaintTimeline.progressAt(p, mid), 0.001f)
    }

    @Test
    fun progressClampedAtOne() {
        val p = play(now = 1000L)
        assertEquals(1f, HandPaintTimeline.progressAt(p, 1000L + p.durationMs * 3), 0f)
    }

    @Test
    fun progressNeverNegativeWhenClockBehindOrigin() {
        val p = play(now = 5000L)
        assertEquals(0f, HandPaintTimeline.progressAt(p, 1000L), 0f)
    }

    @Test
    fun needsFrameOnlyWhilePlaying() {
        val p = play(now = 1000L)
        assertTrue(HandPaintTimeline.needsFrame(p, 1000L + 100L))
        assertFalse(HandPaintTimeline.needsFrame(p, 1000L + p.durationMs + 1L))
    }

    @Test
    fun stoppedPlayNeedsNoFrame() {
        val p = play()
        HandPaintTimeline.stop(p)
        assertFalse(HandPaintTimeline.needsFrame(p, 1000L))
        assertTrue(HandPaintTimeline.finishedAt(p, 1000L))
    }

    @Test
    fun zeroPointsGivesMinDurationAndCompletes() {
        val p = play(points = 0)
        assertEquals(HandPaintGeometry.REPLAY_MIN_MS, p.durationMs)
        assertTrue(HandPaintTimeline.finishedAt(p, p.originMs + p.durationMs))
    }

    @Test
    fun resetClearsEverything() {
        val p = play()
        p.reset()
        assertFalse(p.running)
        assertEquals(-1, p.style)
        assertEquals(0L, p.durationMs)
    }

    @Test
    fun progressMonotonicAcrossWholePlay() {
        val p = play(now = 0L)
        var last = -1f
        var t = 0L
        while (t <= p.durationMs) {
            val cur = HandPaintTimeline.progressAt(p, t)
            assertTrue("t=$t cur=$cur last=$last", cur >= last)
            last = cur
            t += 37L
        }
        assertTrue("last=$last", last > 0.99f)
        assertEquals(1f, HandPaintTimeline.progressAt(p, p.durationMs), 0.0001f)
    }

    @Test
    fun samplingGridNeedNotLandOnEndpoint() {
        val p = play(now = 0L)
        val lastGrid = p.durationMs / 37L * 37L
        assertTrue("duration=${p.durationMs} lastGrid=$lastGrid", lastGrid < p.durationMs)
        assertTrue(HandPaintTimeline.progressAt(p, lastGrid) < 1f)
        assertFalse(HandPaintTimeline.finishedAt(p, lastGrid))
        assertTrue(HandPaintTimeline.finishedAt(p, p.durationMs))
    }

    @Test
    fun styleZeroIsNotUsable() {
        assertFalse(HandPaintSource.validStyle(0))
        assertEquals(HandPaintSource.DEFAULT_STYLE, HandPaintSource.normalizeStyle(0))
    }

    @Test
    fun allNinePresetsUsable() {
        for (i in 1..9) assertTrue(HandPaintSource.validStyle(i))
        assertFalse(HandPaintSource.validStyle(10))
    }

    @Test
    fun entryNamesMatchVendorLayout() {
        assertEquals("handpaint/6/json.txt", HandPaintSource.jsonEntry(6))
        assertEquals("handpaint/6/startBitmap.jpg", HandPaintSource.startEntry(6))
        assertEquals("handpaint/6/finishBitmap.jpg", HandPaintSource.finishEntry(6))
        assertEquals("assets/handpaint.zip", HandPaintSource.OUTER_ENTRY)
    }
}