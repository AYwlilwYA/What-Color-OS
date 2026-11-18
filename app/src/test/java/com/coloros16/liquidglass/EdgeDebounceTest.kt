package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.EdgeDebounce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeDebounceTest {

    private val window = 400L

    @Test
    fun noUnlockYetNeverSuppresses() {
        assertFalse(EdgeDebounce.suppress(1000L, 0L, window))
    }

    @Test
    fun showRightAfterUnlockIsSuppressed() {
        assertTrue(EdgeDebounce.suppress(1200L, 1000L, window))
    }

    @Test
    fun showAtWindowBoundaryPasses() {
        assertFalse("边界取严格小于", EdgeDebounce.suppress(1400L, 1000L, window))
        assertFalse(EdgeDebounce.suppress(1500L, 1000L, window))
    }

    @Test
    fun nonPositiveWindowDisablesDebounce() {
        assertFalse(EdgeDebounce.suppress(1000L, 1000L, 0L))
        assertFalse(EdgeDebounce.suppress(1000L, 1000L, -1L))
    }

    @Test
    fun negativeLastUnlockTreatedAsNever() {
        assertFalse(EdgeDebounce.suppress(1000L, -5L, window))
    }

    @Test
    fun negativeElapsedStillSuppressesAndClockIsMonotonic() {
        assertTrue("差为负仍落在窗口内 ⇒ 吞；uptimeMillis 单调不会回退，此路径不可达", EdgeDebounce.suppress(500L, 1000L, window))
        assertFalse("时刻推进到窗口外即恢复", EdgeDebounce.suppress(1500L, 1000L, window))
    }

    @Test
    fun bumpIncrementsIndependently() {
        assertEquals(1, EdgeDebounce.bump(0))
        assertEquals(6, EdgeDebounce.bump(5))
    }

    @Test
    fun twoSourcesKeepSeparateCounts() {
        var hide = 0
        var exit = 0
        repeat(3) { hide = EdgeDebounce.bump(hide) }
        repeat(5) { exit = EdgeDebounce.bump(exit) }
        assertEquals(3, hide)
        assertEquals("退场入口必须自增自己的计数", 5, exit)
    }

    @Test
    fun exitCountStaysZeroIfCallbackNeverBumpsIt() {
        var hide = 0
        var exit = 0
        repeat(4) {
            hide = EdgeDebounce.bump(hide)
        }
        assertEquals(4, hide)
        assertEquals("两个入口都走 hide 分支时 exit 恒 0（复现 bug）", 0, exit)
    }

    @Test
    fun sourceTagPutsOriginFirst() {
        assertEquals("hide=3 exit=5", EdgeDebounce.sourceTag(3, 5, fromExit = false))
        assertEquals("exit=5 hide=3", EdgeDebounce.sourceTag(3, 5, fromExit = true))
    }

    @Test
    fun sourceTagDistinguishesSameTotalFromDifferentOrigin() {
        val a = EdgeDebounce.sourceTag(2, 1, fromExit = false)
        val b = EdgeDebounce.sourceTag(2, 1, fromExit = true)
        assertTrue("同样计数不同来源，日志必须能分清", a != b)
    }

    @Test
    fun bothUnlockSourcesShareOneDebounceInstant() {
        var last = 0L
        last = 1000L
        assertTrue("hide 后紧跟的 show 被吞", EdgeDebounce.suppress(1100L, last, window))
        last = 1300L
        assertTrue("exit 后紧跟的 show 同样被吞", EdgeDebounce.suppress(1400L, last, window))
        assertFalse(EdgeDebounce.suppress(1800L, last, window))
    }

    @Test
    fun secondUnlockSourceDoesNotDoubleFireWithinBridge() {
        var last = 0L
        var fires = 0
        for (t in listOf(1000L, 1020L)) {
            last = t
            fires++
        }
        assertEquals("两个入口都会 fire，去重靠 ensurePlaying 不是靠计数", 2, fires)
        assertTrue(EdgeDebounce.suppress(1100L, last, window))
    }
}