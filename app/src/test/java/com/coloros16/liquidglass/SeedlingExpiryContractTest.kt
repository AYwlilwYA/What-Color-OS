package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingActivity
import com.coloros16.liquidglass.engine.SeedlingExpiry
import com.coloros16.liquidglass.engine.SeedlingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 过期引擎契约：最近到期/降级点/已过不收/余量 20ms，含阳性对照。 */
class SeedlingExpiryContractTest {

    private fun act(id: String, stale: Long = 0L, updated: Long = 0L, demote: Long? = null) =
        SeedlingActivity(id = id, source = "t", kind = SeedlingKind.MEDIA, staleAtElapsedMs = stale, updatedAtElapsedMs = updated, demoteAfterMs = demote)

    @Test
    fun emptyNoDeadline() {
        assertNull(SeedlingExpiry.nextDeadlineMs(emptyList(), 1000L))
        assertNull(SeedlingExpiry.scheduleDelayMs(emptyList(), 1000L))
    }

    @Test
    fun nearestStaleWins() {
        val list = listOf(act("a", stale = 5000L), act("b", stale = 3000L), act("c", stale = 9000L))
        assertEquals(3000L, SeedlingExpiry.nextDeadlineMs(list, 1000L))
        assertEquals(2020L, SeedlingExpiry.scheduleDelayMs(list, 1000L))
    }

    @Test
    fun demotePointIncluded() {
        val list = listOf(act("msg", updated = 1000L, demote = 6000L))
        assertEquals(7000L, SeedlingExpiry.nextDeadlineMs(list, 2000L))
        assertEquals(5020L, SeedlingExpiry.scheduleDelayMs(list, 2000L))
    }

    @Test
    fun staleAndDemoteMin() {
        val list = listOf(
            act("a", stale = 8000L),
            act("msg", updated = 1000L, demote = 6000L),
        )
        assertEquals("降级点 7000 早于 stale 8000", 7000L, SeedlingExpiry.nextDeadlineMs(list, 2000L))
    }

    @Test
    fun pastDeadlinesIgnored_positiveControl() {
        val list = listOf(act("a", stale = 500L), act("msg", updated = 100L, demote = 100L))
        assertNull("阳性对照：已过期时刻不排闹钟（layout 即时降级）", SeedlingExpiry.nextDeadlineMs(list, 1000L))
    }

    @Test
    fun delayClampedPlus20() {
        val list = listOf(act("a", stale = 1001L))
        assertEquals("差 1ms 钳后 +20 余量", 21L, SeedlingExpiry.scheduleDelayMs(list, 1000L))
        assertNull("恰好到期时刻不算未来（>now 守卫），不排闹钟", SeedlingExpiry.scheduleDelayMs(listOf(act("a", stale = 1000L)), 1000L))
        assertTrue(SeedlingExpiry.scheduleDelayMs(list, 999L)!! >= 20L)
    }

    @Test
    fun noStaleNoDemoteNoAlarm() {
        val list = listOf(act("a"), act("b", updated = 500L))
        assertNull(SeedlingExpiry.nextDeadlineMs(list, 1000L))
    }
}
