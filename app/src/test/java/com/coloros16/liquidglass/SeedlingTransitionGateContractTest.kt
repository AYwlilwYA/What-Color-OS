package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingTransitionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 过渡帧循环单飞登记契约：新取代旧 + 旧完成回调不误清新循环，含阳性对照。 */
class SeedlingTransitionGateContractTest {

    @Test
    fun initiallyInactive() {
        val g = SeedlingTransitionGate()
        assertFalse(g.isActive())
        assertEquals(0L, g.activeId())
    }

    @Test
    fun beginReturnsZeroWhenNoPrior() {
        val g = SeedlingTransitionGate()
        assertEquals("首个过渡无旧循环", 0L, g.begin())
        assertTrue(g.isActive())
    }

    @Test
    fun beginReturnsPriorToken() {
        val g = SeedlingTransitionGate()
        val prev1 = g.begin() // 首个：无旧循环返回 0，新 id=1
        val id1 = g.activeId()
        val prev2 = g.begin() // 第二个：返回旧 id1，新 id=2
        assertEquals("首个过渡无旧循环", 0L, prev1)
        assertEquals("新过渡返回被取代的旧 id", id1, prev2)
        assertTrue(g.isActive())
        assertTrue("id 单调递增", g.activeId() > id1)
    }

    @Test
    fun completeClearsActive() {
        val g = SeedlingTransitionGate()
        g.begin()
        val token = g.activeId() // 当前活跃 id（begin 返回的是旧 id，不是新 id）
        assertTrue(g.complete(token))
        assertFalse(g.isActive())
        assertEquals(0L, g.activeId())
    }

    @Test
    fun staleCompleteDoesNotClearNew_positiveControl() {
        val g = SeedlingTransitionGate()
        g.begin()
        val old = g.activeId()
        g.begin()
        val new = g.activeId()
        assertFalse("阳性对照：旧循环完成回调不清新循环", g.complete(old))
        assertTrue("新循环仍活跃", g.isActive())
        assertEquals(new, g.activeId())
        assertTrue(g.complete(new))
        assertFalse(g.isActive())
    }

    @Test
    fun resetClears() {
        val g = SeedlingTransitionGate()
        g.begin()
        val token = g.activeId()
        g.reset()
        assertFalse(g.isActive())
        assertFalse("reset 后旧 token 完成无效", g.complete(token))
    }

    @Test
    fun tripleTransitionChain() {
        val g = SeedlingTransitionGate()
        val prevA = g.begin(); val a = g.activeId()
        val prevB = g.begin(); val b = g.activeId()
        val prevC = g.begin(); val c = g.activeId()
        assertEquals("a 无旧循环", 0L, prevA)
        assertEquals("b 取代 a", a, prevB)
        assertEquals("c 取代 b", b, prevC)
        assertFalse(g.complete(a))
        assertFalse(g.complete(b))
        assertTrue(g.complete(c))
        assertFalse(g.isActive())
    }
}
