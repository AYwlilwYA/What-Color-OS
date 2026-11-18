package com.coloros16.liquidglass

import com.coloros16.liquidglass.sf.SfGlassChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SfChannelReadyTest {

    @Test
    fun moduleAbsentMeansUnavailable() {
        val (ok, reason) = SfGlassChannel.classifyProbe("NO_MODULE\n")
        assertFalse(ok)
        assertTrue(reason.contains("未安装"))
    }

    @Test
    fun disabledAndRemovedMeanUnavailable() {
        assertFalse(SfGlassChannel.classifyProbe("DISABLED").first)
        assertFalse(SfGlassChannel.classifyProbe("REMOVED").first)
    }

    @Test
    fun missingLoaderInTreeMeansUnavailable() {
        val (ok, reason) = SfGlassChannel.classifyProbe("NO_LOADER")
        assertFalse(ok)
        assertTrue(reason.contains("装载件"))
    }

    @Test
    fun fusedStateMeansUnavailable() {
        val (ok, reason) = SfGlassChannel.classifyProbe("STATE=disabled")
        assertFalse(ok)
        assertTrue(reason.contains("熔断"))
    }

    @Test
    fun healthyStateMeansAvailable() {
        val (ok, reason) = SfGlassChannel.classifyProbe("STATE=healthy")
        assertTrue(ok)
        assertEquals("healthy", reason)
    }

    @Test
    fun bootingSuspectAndAbsentStateStillAvailable() {
        assertTrue(SfGlassChannel.classifyProbe("STATE=booting").first)
        assertTrue(SfGlassChannel.classifyProbe("STATE=suspect").first)
        assertTrue(SfGlassChannel.classifyProbe("STATE=absent").first)
        assertTrue(SfGlassChannel.classifyProbe("STATE=empty").first)
    }

    @Test
    fun takesLastNonEmptyLineAndTrims() {
        assertTrue(SfGlassChannel.classifyProbe("\n  STATE=healthy  \n\n").first)
        assertFalse(SfGlassChannel.classifyProbe("warning: something\nNO_MODULE\n").first)
    }

    @Test
    fun garbageOutputMeansUnavailableNotEmptyGlass() {
        assertFalse(SfGlassChannel.classifyProbe("").first)
        assertFalse(SfGlassChannel.classifyProbe("   \n  ").first)
        assertFalse(SfGlassChannel.classifyProbe("su: permission denied").first)
    }
}
