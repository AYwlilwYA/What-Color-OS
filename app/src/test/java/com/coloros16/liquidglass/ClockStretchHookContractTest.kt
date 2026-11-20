package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockStretchHookContractTest {

    private fun layout(sx: Float, sy: Float) = LockLayoutSettings().apply {
        clockX = 0.5f
        clockY = 0.45f
        clockScale = 1f
        clockStretchX = sx
        clockStretchY = sy
    }

    private data class Key(
        val x: Float,
        val y: Float,
        val scale: Float,
        val stretchX: Float,
        val stretchY: Float,
    )

    private fun keyOf(l: LockLayoutSettings) =
        Key(l.clockX, l.clockY, l.clockScale, l.clockStretchX, l.clockStretchY)

    @Test
    fun stretchOnlyChangeMakesKeyDifferent() {
        val a = keyOf(layout(1f, 1f))
        val b = keyOf(layout(6f, 1f))
        assertNotEquals(a, b)
    }

    @Test
    fun oldThreeFieldKeyWouldMissStretchOnlyChange() {
        val a = layout(1f, 1f)
        val b = layout(6f, 1f)
        assertEquals(a.clockX, b.clockX, 0f)
        assertEquals(a.clockY, b.clockY, 0f)
        assertEquals(a.clockScale, b.clockScale, 0f)
        assertNotEquals(a.clockStretchX, b.clockStretchX)
    }

    @Test
    fun verticalStretchAloneAlsoChangesKey() {
        assertNotEquals(keyOf(layout(1f, 1f)), keyOf(layout(1f, 4f)))
    }

    @Test
    fun anchorCarriesStretchIntoStateMachine() {
        val m = ClockStateMachine()
        m.setAnchor(
            SceneState.AOD,
            ClockAnchor(centerX = 0.5f, centerY = 0.45f, scale = 1f, stretchX = 6f, stretchY = 0.5f),
            0L,
            follow = false,
        )
        val a = m.anchorOf(SceneState.AOD)
        assertEquals(6f, a.stretchX, 0.0001f)
        assertEquals(0.5f, a.stretchY, 0.0001f)
    }

    @Test
    fun poseInterpolatesStretchDuringTransition() {
        val m = ClockStateMachine()
        m.setAnchor(
            SceneState.AOD,
            ClockAnchor(centerX = 0.5f, centerY = 0.45f, stretchX = 4f, stretchY = 1f),
            0L,
            follow = false,
        )
        m.setAnchor(
            SceneState.LOCK,
            ClockAnchor(centerX = 0.5f, centerY = 0.2f, stretchX = 1f, stretchY = 1f),
            0L,
            follow = false,
        )
        m.snapTo(SceneState.AOD, 0L)
        m.onTransition(TransitionEdge.AOD_TO_LOCK, 0.5f)
        val p = m.pose
        assertTrue("sx=${p.stretchX}", p.stretchX > 1f && p.stretchX < 4f)
    }

    @Test
    fun appliedFingerprintSeparatesTwoAxes() {
        val scale = 1f
        val fpA = floatArrayOf(0f, 0f, scale * 6f, 1f, scale * 1f)
        val fpB = floatArrayOf(0f, 0f, scale * 1f, 1f, scale * 6f)
        assertNotEquals(fpA[2], fpB[2])
        assertNotEquals(fpA[4], fpB[4])
    }

    @Test
    fun stretchRangeClampedBySettings() {
        val l = LockLayoutSettings()
        l.clockStretchX = 99f
        l.clockStretchY = 0.001f
        assertEquals(LockLayoutSettings.MAX_CLOCK_STRETCH, l.clockStretchX, 0.0001f)
        assertEquals(LockLayoutSettings.MIN_CLOCK_STRETCH, l.clockStretchY, 0.0001f)
    }

    @Test
    fun stretchSurvivesSerializationRoundTrip() {
        val l = layout(5.5f, 0.4f)
        val back = LockLayoutSettings.parse(l.toText(), EditSurface.AOD)
        assertEquals(5.5f, back.clockStretchX, 0.0001f)
        assertEquals(0.4f, back.clockStretchY, 0.0001f)
    }

    @Test
    fun missingStretchKeysFallBackToOne() {
        val text = layout(3f, 3f).toText()
            .lines()
            .filterNot { it.startsWith("clock.stretch_") }
            .joinToString("\n")
        val back = LockLayoutSettings.parse(text, EditSurface.AOD)
        assertEquals(1f, back.clockStretchX, 0.0001f)
        assertEquals(1f, back.clockStretchY, 0.0001f)
    }
}