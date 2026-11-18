package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SingleFlightPending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightPendingTest {

    private fun slot() = SingleFlightPending<String>()

    @Test
    fun emptySlotHasNothingToDrain() {
        val s = slot()
        assertFalse(s.pending())
        assertNull(s.take())
    }

    @Test
    fun offerThenTakeReturnsIt() {
        val s = slot()
        s.offer("a")
        assertTrue(s.pending())
        assertEquals("a", s.take())
    }

    @Test
    fun takeClearsSoSecondDrainIsNoop() {
        val s = slot()
        s.offer("a")
        s.take()
        assertFalse(s.pending())
        assertNull("同一请求不得补做两次", s.take())
    }

    @Test
    fun laterOfferOverwritesEarlier() {
        val s = slot()
        s.offer("a")
        s.offer("b")
        s.offer("c")
        assertEquals("只留最后一次", "c", s.take())
        assertNull(s.take())
    }

    @Test
    fun droppedMiddleRequestsAreNotReplayed() {
        val s = slot()
        val replayed = mutableListOf<String>()
        for (id in listOf("a", "b", "c", "d")) s.offer(id)
        s.take()?.let { replayed.add(it) }
        assertEquals(listOf("d"), replayed)
    }

    @Test
    fun clearDropsPending() {
        val s = slot()
        s.offer("a")
        s.clear()
        assertFalse(s.pending())
        assertNull(s.take())
    }

    @Test
    fun shouldDrainWhenKeyDiffersFromSlot() {
        assertTrue(SingleFlightPending.shouldDrain("new", "old", present = true, recycled = false))
    }

    @Test
    fun shouldNotDrainWhenSlotAlreadyMatches() {
        assertFalse(
            "补做同键会自我循环",
            SingleFlightPending.shouldDrain("k", "k", present = true, recycled = false),
        )
    }

    @Test
    fun shouldDrainWhenSlotMissingOrRecycled() {
        assertTrue(SingleFlightPending.shouldDrain("k", "k", present = false, recycled = false))
        assertTrue(SingleFlightPending.shouldDrain("k", "k", present = true, recycled = true))
    }

    @Test
    fun renderFailureRetriesOnceWhenNoNewRequestArrives() {
        val s = slot()
        val slotKey = "old"
        var attempts = 0
        s.offer("new")
        repeat(5) {
            val next = s.take() ?: return@repeat
            if (!SingleFlightPending.shouldDrain(next, slotKey, present = true, recycled = false)) return@repeat
            attempts++
        }
        assertEquals("失败后待办已清空，无新请求就不再重试", 1, attempts)
        assertFalse(s.pending())
    }

    @Test
    fun continuousRequestsKeepRetryingWhileFailing() {
        val s = slot()
        val slotKey = "old"
        var attempts = 0
        s.offer("new")
        repeat(5) {
            val next = s.take() ?: return@repeat
            if (!SingleFlightPending.shouldDrain(next, slotKey, present = true, recycled = false)) return@repeat
            attempts++
            s.offer(next)
        }
        assertEquals("每轮重新登记则每轮重试；真实请求由绘制帧驱动不是自喂", 5, attempts)
    }

    @Test
    fun successfulPublishStopsDrainOfSameKey() {
        val s = slot()
        var slotKey = "old"
        s.offer("new")
        val first = s.take()!!
        assertTrue(SingleFlightPending.shouldDrain(first, slotKey, true, false))
        slotKey = first
        s.offer(first)
        val second = s.take()!!
        assertFalse("发布成功后同键不再补做", SingleFlightPending.shouldDrain(second, slotKey, true, false))
    }

    @Test
    fun offerDuringDrainIsKeptForNextRound() {
        val s = slot()
        s.offer("a")
        val taken = s.take()
        s.offer("b")
        assertEquals("a", taken)
        assertTrue("补做期间来的新请求要留到下一轮", s.pending())
        assertEquals("b", s.take())
    }

    @Test
    fun repeatedOfferTakeCyclesAlwaysDeliverLatest() {
        val s = slot()
        for (i in 0 until 20) {
            s.offer("id$i")
            assertEquals("id$i", s.take())
            assertFalse(s.pending())
        }
    }
}