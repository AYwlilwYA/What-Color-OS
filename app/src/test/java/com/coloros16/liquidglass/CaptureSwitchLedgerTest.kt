package com.coloros16.liquidglass

import com.coloros16.liquidglass.diagnostics.CaptureSwitchLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSwitchLedgerTest {

    @Test
    fun offSwitchIsAutoEnabledAndOwned() {
        val l = CaptureSwitchLedger()
        assertTrue(l.ensureOn("logs", currentlyOn = false))
        assertEquals(listOf("logs"), l.owned())
    }

    @Test
    fun userOwnedSwitchIsNeverTouched() {
        val l = CaptureSwitchLedger()
        assertFalse("已开着不该代开", l.ensureOn("logs", currentlyOn = true))
        assertEquals(emptyList<String>(), l.owned())
        assertEquals("结束不该关用户自己开的", emptyList<String>(), l.releaseForRestore())
    }

    @Test
    fun restoreOnlyOwnsKeysAndClears() {
        val l = CaptureSwitchLedger()
        l.ensureOn("logs", currentlyOn = false)
        l.ensureOn("engine", currentlyOn = true)
        assertEquals(listOf("logs"), l.releaseForRestore())
        assertEquals("账本必须清空", emptyList<String>(), l.releaseForRestore())
    }

    @Test
    fun repeatedEnsureIsIdempotent() {
        val l = CaptureSwitchLedger()
        l.ensureOn("logs", currentlyOn = false)
        l.ensureOn("logs", currentlyOn = false)
        assertEquals(listOf("logs"), l.owned())
    }

    @Test
    fun failedStartCanRestoreWithoutStop() {
        val l = CaptureSwitchLedger()
        l.ensureOn("logs", currentlyOn = false)
        assertEquals("启动失败也要还原", listOf("logs"), l.releaseForRestore())
    }

    @Test
    fun positiveControlMixedOwnershipKeepsUserKey() {
        val l = CaptureSwitchLedger()
        l.ensureOn("a", currentlyOn = false)
        l.ensureOn("b", currentlyOn = true)
        l.ensureOn("c", currentlyOn = false)
        assertEquals(listOf("a", "c"), l.releaseForRestore())
    }
}
