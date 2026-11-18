package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.KeyedTtlCache
import com.coloros16.liquidglass.engine.edit.EditSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedTtlCacheTest {

    private val ttl = 500L

    @Test
    fun missingEntryIsNeverFresh() {
        assertFalse(KeyedTtlCache.fresh(null, 1000L, ttl))
    }

    @Test
    fun justReadIsFresh() {
        assertTrue(KeyedTtlCache.fresh(1000L, 1000L, ttl))
        assertTrue(KeyedTtlCache.fresh(1000L, 1499L, ttl))
    }

    @Test
    fun exactlyAtTtlIsExpired() {
        assertFalse("边界取严格小于", KeyedTtlCache.fresh(1000L, 1500L, ttl))
        assertFalse(KeyedTtlCache.fresh(1000L, 1600L, ttl))
    }

    @Test
    fun nonPositiveTtlAlwaysExpired() {
        assertFalse(KeyedTtlCache.fresh(1000L, 1000L, 0L))
        assertFalse(KeyedTtlCache.fresh(1000L, 1000L, -5L))
    }

    @Test
    fun readingOneKeyDoesNotRefreshTheOther() {
        val own = HashMap<EditSurface, Long>()
        own[EditSurface.LOCK] = 1000L
        own[EditSurface.AOD] = 1400L
        assertTrue("AOD 刚读过，新鲜", KeyedTtlCache.fresh(own[EditSurface.AOD], 1600L, ttl))
        assertFalse("LOCK 已过 600ms，该重读", KeyedTtlCache.fresh(own[EditSurface.LOCK], 1600L, ttl))
    }

    @Test
    fun sharedStampMakesStaleKeyLookFresh() {
        val lockReadAt = 1000L
        val aodReadAt = 1400L
        val now = 1600L
        assertFalse("LOCK 自己算：过期", KeyedTtlCache.fresh(lockReadAt, now, ttl))
        assertTrue("共享戳用 AOD 的时刻：误判新鲜", KeyedTtlCache.fresh(aodReadAt, now, ttl))
        assertTrue(
            KeyedTtlCache.sharedStampMisjudges(lockReadAt, aodReadAt, now, ttl),
        )
    }

    @Test
    fun sharedStampStarvesOneKeyWhenVisitIntervalIsBetweenHalfTtlAndTtl() {
        val step = 300L
        var shared = 0L
        val sharedReads = HashMap<EditSurface, Int>()
        repeat(60) { i ->
            val t = i * step
            val surface = if (i % 2 == 0) EditSurface.LOCK else EditSurface.AOD
            if (!KeyedTtlCache.fresh(shared, t, ttl)) {
                sharedReads[surface] = (sharedReads[surface] ?: 0) + 1
                shared = t
            }
        }
        assertEquals("AOD 一次都读不到，永远吃 LOCK 刷新后的缓存", 0, sharedReads[EditSurface.AOD] ?: 0)
        assertTrue((sharedReads[EditSurface.LOCK] ?: 0) > 0)
    }

    @Test
    fun perKeyStampReadsBothKeysEvenAtStarvingInterval() {
        val step = 300L
        val own = HashMap<EditSurface, Long>()
        val reads = HashMap<EditSurface, Int>()
        repeat(60) { i ->
            val t = i * step
            val surface = if (i % 2 == 0) EditSurface.LOCK else EditSurface.AOD
            if (!KeyedTtlCache.fresh(own[surface], t, ttl)) {
                own[surface] = t
                reads[surface] = (reads[surface] ?: 0) + 1
            }
        }
        assertEquals("每面每 600ms 访问、TTL 500 ⇒ 每次都该重读", 30, reads[EditSurface.LOCK])
        assertEquals(30, reads[EditSurface.AOD])
    }

    @Test
    fun fastAlternationDoesNotStarveButStillUnderReads() {
        val step = 100L
        var shared = 0L
        val sharedReads = HashMap<EditSurface, Int>()
        val own = HashMap<EditSurface, Long>()
        val ownReads = HashMap<EditSurface, Int>()
        repeat(60) { i ->
            val t = i * step
            val surface = if (i % 2 == 0) EditSurface.LOCK else EditSurface.AOD
            if (!KeyedTtlCache.fresh(shared, t, ttl)) {
                sharedReads[surface] = (sharedReads[surface] ?: 0) + 1
                shared = t
            }
            if (!KeyedTtlCache.fresh(own[surface], t, ttl)) {
                ownReads[surface] = (ownReads[surface] ?: 0) + 1
                own[surface] = t
            }
        }
        assertTrue(
            "访问远快于 TTL 时不饿死，但共享戳仍少读一半",
            (sharedReads[EditSurface.LOCK] ?: 0) < (ownReads[EditSurface.LOCK] ?: 0),
        )
        assertEquals(10, ownReads[EditSurface.LOCK])
        assertEquals(5, sharedReads[EditSurface.LOCK])
    }

    @Test
    fun noMisjudgeWhenBothStampsAgree() {
        assertFalse(KeyedTtlCache.sharedStampMisjudges(1000L, 1000L, 1200L, ttl))
        assertFalse(KeyedTtlCache.sharedStampMisjudges(1000L, 1000L, 1900L, ttl))
        assertFalse(KeyedTtlCache.sharedStampMisjudges(null, null, 1200L, ttl))
    }

    @Test
    fun firstReadOfOtherKeyIsAlsoMisjudgedBySharedStamp() {
        assertTrue(
            "别的键还没读过，共享戳却让它以为新鲜",
            KeyedTtlCache.sharedStampMisjudges(null, 1400L, 1600L, ttl),
        )
    }

    @Test
    fun clockRollbackTreatedAsFreshNotCrash() {
        assertTrue("时间回退时差为负，仍算新鲜（不抛）", KeyedTtlCache.fresh(2000L, 1000L, ttl))
    }
}