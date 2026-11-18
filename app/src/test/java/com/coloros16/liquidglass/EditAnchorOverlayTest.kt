package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.edit.EditAnchorOverlay
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditAnchorOverlayTest {

    private val base = ClockAnchor(centerX = 0.5f, centerY = 0.3f, scale = 0.8f, alpha = 0.6f, rotationDeg = 12f)

    @Test
    fun defaultsLeaveAnchorUntouched() {
        val lock = LockLayoutSettings.defaults(EditSurface.LOCK)
        assertFalse(EditAnchorOverlay.active(lock))
        assertSame(base, EditAnchorOverlay.apply(base, lock))

        val aod = LockLayoutSettings.defaults(EditSurface.AOD)
        assertFalse(EditAnchorOverlay.active(aod))
        assertSame(base, EditAnchorOverlay.apply(base, aod))
    }

    @Test
    fun lockPositionReplacesAnchor() {
        val s = LockLayoutSettings.defaults(EditSurface.LOCK)
        s.clockX = 0.25f
        s.clockY = 0.7f
        assertTrue(EditAnchorOverlay.active(s))
        val out = EditAnchorOverlay.apply(base, s)
        assertEquals(0.25f, out.centerX, 0.0001f)
        assertEquals(0.7f, out.centerY, 0.0001f)
    }

    @Test
    fun scaleMultipliesTransitionScale() {
        val s = LockLayoutSettings.defaults(EditSurface.LOCK)
        s.clockScale = 1.5f
        val out = EditAnchorOverlay.apply(base, s)
        assertEquals(0.8f * 1.5f, out.scale, 0.0001f)
    }

    @Test
    fun alphaAndRotationComeFromTransitionOnly() {
        val s = LockLayoutSettings.defaults(EditSurface.LOCK)
        s.clockX = 0.1f
        s.clockScale = 1.2f
        val out = EditAnchorOverlay.apply(base, s)
        assertEquals(base.alpha, out.alpha, 0.0001f)
        assertEquals(base.rotationDeg, out.rotationDeg, 0.0001f)
    }

    @Test
    fun aodKeepsTransitionVertical() {
        val s = LockLayoutSettings.defaults(EditSurface.AOD)
        s.clockX = 0.8f
        s.clockY = 0.9f
        val out = EditAnchorOverlay.apply(base, s)
        assertEquals(0.8f, out.centerX, 0.0001f)
        assertEquals(base.centerY, out.centerY, 0.0001f)
    }

    @Test
    fun aodHorizontalOnlyStillActivates() {
        val s = LockLayoutSettings.defaults(EditSurface.AOD)
        s.clockX = 0.35f
        assertTrue(EditAnchorOverlay.active(s))
    }

    @Test
    fun scaleClampIsEnforcedBySettings() {
        val s = LockLayoutSettings.defaults(EditSurface.LOCK)
        s.clockScale = 9f
        assertEquals(LockLayoutSettings.MAX_CLOCK_SCALE, s.clockScale, 0.0001f)
        s.clockScale = 0.01f
        assertEquals(LockLayoutSettings.MIN_CLOCK_SCALE, s.clockScale, 0.0001f)
    }

    @Test
    fun outOfRangePositionIsClamped() {
        val s = LockLayoutSettings.defaults(EditSurface.LOCK)
        s.clockX = 2f
        s.clockY = -1f
        val out = EditAnchorOverlay.apply(base, s)
        assertEquals(1f, out.centerX, 0.0001f)
        assertEquals(0f, out.centerY, 0.0001f)
    }

    @Test
    fun tinyJitterIsTreatedAsDefault() {
        val s = LockLayoutSettings.defaults(EditSurface.LOCK)
        s.clockX = 0.5001f
        s.clockScale = 1.0001f
        assertFalse(EditAnchorOverlay.active(s))
    }
}