package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingAutoCollapse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 大卡自动收起计时契约：排程/暂停续排/长按保持/边界，含阳性对照。 */
class SeedlingAutoCollapseContractTest {

    @Test
    fun armCountsDown() {
        val ac = SeedlingAutoCollapse(5000L)
        ac.arm(1000L)
        assertEquals(5000L, ac.dueInMs(1000L))
        assertEquals(2000L, ac.dueInMs(4000L))
        assertEquals(0L, ac.dueInMs(6000L))
        assertEquals("过期后钳 0 不出负数", 0L, ac.dueInMs(99999L))
    }

    @Test
    fun pauseFreezesRemaining() {
        val ac = SeedlingAutoCollapse(5000L)
        ac.arm(0L)
        ac.pause(2000L)
        assertNull("暂停中不倒计时", ac.dueInMs(50000L))
        ac.resume(50000L)
        assertEquals("按住 3s 不消耗额度，剩 3s", 3000L, ac.dueInMs(50000L))
        assertEquals(0L, ac.dueInMs(53000L))
    }

    @Test
    fun pauseWithoutArmNoOp() {
        val ac = SeedlingAutoCollapse(5000L)
        ac.pause(100L)
        ac.resume(200L)
        assertNull(ac.dueInMs(300L))
        assertFalse(ac.isArmed())
    }

    @Test
    fun nullHoldNeverCollapses() {
        val ac = SeedlingAutoCollapse(null)
        ac.arm(0L)
        assertNull("长按临时展开=永不自动收", ac.dueInMs(999999L))
        assertFalse(ac.isArmed())
    }

    @Test
    fun cancelDisarms() {
        val ac = SeedlingAutoCollapse(5000L)
        ac.arm(0L)
        assertTrue(ac.isArmed())
        ac.cancel()
        assertFalse(ac.isArmed())
        assertNull(ac.dueInMs(100L))
    }

    @Test
    fun rearmResetsQuota_positiveControl() {
        val ac = SeedlingAutoCollapse(5000L)
        ac.arm(0L)
        ac.pause(4000L)
        ac.resume(4000L)
        assertEquals(1000L, ac.dueInMs(4000L))
        ac.arm(4500L)
        assertEquals("阳性对照：重新展开重置满额度", 5000L, ac.dueInMs(4500L))
    }

    @Test
    fun doublePauseKeepsFirstSnapshot() {
        val ac = SeedlingAutoCollapse(5000L)
        ac.arm(0L)
        ac.pause(1000L)
        ac.pause(4000L)
        ac.resume(4000L)
        assertEquals("重复暂停不重复扣时", 4000L, ac.dueInMs(4000L))
    }
}
