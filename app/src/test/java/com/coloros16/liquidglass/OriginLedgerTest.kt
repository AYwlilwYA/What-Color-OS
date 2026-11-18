package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.OriginLedger
import com.coloros16.liquidglass.engine.SceneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginLedgerTest {

    private class Props(val tx: Float, val scale: Float, val alpha: Float)

    private fun ledger() = OriginLedger<Props>()

    @Test
    fun firstRecordStoresAndReturnsTrue() {
        val l = ledger()
        assertTrue(l.record("k", Props(10f, 1.2f, 0.8f)))
        assertTrue(l.recorded("k"))
        assertEquals(1, l.size())
    }

    @Test
    fun secondRecordDoesNotOverwriteOrigin() {
        val l = ledger()
        l.record("k", Props(10f, 1.2f, 0.8f))
        assertFalse("已记过不得覆盖", l.record("k", Props(999f, 9f, 0f)))
        val back = l.take("k")!!
        assertEquals(10f, back.tx, 0.0001f)
        assertEquals(1.2f, back.scale, 0.0001f)
        assertEquals(0.8f, back.alpha, 0.0001f)
    }

    @Test
    fun overwriteWouldRestoreOurOwnOutputNotSystemValue() {
        val l = OriginLedger<Float>()
        l.record("k", 0.7f)
        l.record("k", 0.3f)
        assertEquals("覆盖后会把我们的输出当原值 ⇒ 回不去", 0.7f, l.take("k")!!, 0.0001f)
    }

    @Test
    fun takeRemovesSoSecondRestoreIsNoop() {
        val l = ledger()
        l.record("k", Props(1f, 1f, 1f))
        assertTrue(l.take("k") != null)
        assertNull("第二次撤必须无操作", l.take("k"))
        assertEquals(0, l.size())
    }

    @Test
    fun unrecordedKeyReturnsNullMeaningDoNotWrite() {
        val l = ledger()
        assertNull("没记过说明本件没写过，不得乱撤", l.take("never"))
        assertFalse(l.recorded("never"))
    }

    @Test
    fun hardcodedRestoreWouldClobberNonDefaultSystemValue() {
        val l = OriginLedger<Float>()
        l.record("alpha", 0.6f)
        val restored = l.take("alpha")!!
        assertEquals("系统原值 0.6，硬编码回 1f 就错了", 0.6f, restored, 0.0001f)
        assertTrue(restored != 1f)
    }

    @Test
    fun forgetClearsWithoutReturningValue() {
        val l = ledger()
        l.record("k", Props(1f, 1f, 1f))
        l.forget("k")
        assertFalse(l.recorded("k"))
        assertNull(l.take("k"))
    }

    @Test
    fun keysAreScopedPerViewState() {
        val a = OriginLedger.keyOf(42, SceneState.AOD)
        val b = OriginLedger.keyOf(42, SceneState.LOCK)
        assertTrue(a != b)
        val l = ledger()
        l.record(a, Props(1f, 1f, 1f))
        l.record(b, Props(2f, 2f, 2f))
        l.take(a)
        assertTrue("撤一面不影响另一面", l.recorded(b))
    }

    @Test
    fun keysOfCoversEveryStateForThatView() {
        val keys = OriginLedger.keysOf(42)
        assertEquals(SceneState.values().size, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        for (s in SceneState.values()) {
            assertTrue(keys.contains(OriginLedger.keyOf(42, s)))
        }
    }

    @Test
    fun keysOfDifferentViewsDoNotCollide() {
        val a = OriginLedger.keysOf(1).toSet()
        val b = OriginLedger.keysOf(2).toSet()
        assertTrue(a.intersect(b).isEmpty())
    }

    @Test
    fun forgetAllKeysOfViewLeavesOtherViewIntact() {
        val l = ledger()
        for (k in OriginLedger.keysOf(1)) l.record(k, Props(1f, 1f, 1f))
        for (k in OriginLedger.keysOf(2)) l.record(k, Props(2f, 2f, 2f))
        for (k in OriginLedger.keysOf(1)) l.forget(k)
        assertEquals(SceneState.values().size, l.size())
        for (k in OriginLedger.keysOf(2)) assertTrue(l.recorded(k))
    }

    @Test
    fun repeatedWriteRestoreCyclesAlwaysReturnFirstOrigin() {
        val l = OriginLedger<Float>()
        repeat(5) {
            assertTrue(l.record("k", 0.42f))
            assertEquals(0.42f, l.take("k")!!, 0.0001f)
            assertEquals(0, l.size())
        }
    }

    @Test
    fun clearDropsEverything() {
        val l = ledger()
        for (k in OriginLedger.keysOf(7)) l.record(k, Props(1f, 1f, 1f))
        l.clear()
        assertEquals(0, l.size())
    }
}