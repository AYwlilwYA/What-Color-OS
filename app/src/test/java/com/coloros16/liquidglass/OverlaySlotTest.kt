package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.OverlaySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySlotTest {

    @Test
    fun matchingKeyWithLiveLayerIsUsable() {
        assertTrue(OverlaySlot.usable("a|10x20", "a|10x20", present = true, recycled = false))
    }

    @Test
    fun missingLayerIsNeverUsable() {
        assertFalse(OverlaySlot.usable("k", "k", present = false, recycled = false))
    }

    @Test
    fun recycledLayerIsNeverUsable() {
        assertFalse(OverlaySlot.usable("k", "k", present = true, recycled = true))
    }

    @Test
    fun mismatchedKeyIsNotUsable() {
        assertFalse(OverlaySlot.usable("a|10x20", "b|10x20", present = true, recycled = false))
        assertFalse(OverlaySlot.usable("a|10x20", "a|30x40", present = true, recycled = false))
    }

    @Test
    fun emptySlotKeyNeverMatchesRealRequest() {
        assertFalse(OverlaySlot.usable("", "a|10x20", present = true, recycled = false))
    }

    @Test
    fun sameKeyLiveLayerSkipsRebuild() {
        assertTrue(OverlaySlot.skipRebuild("k", "k", present = true, recycled = false))
    }

    @Test
    fun missingOrRecycledLayerForcesRebuild() {
        assertFalse(OverlaySlot.skipRebuild("k", "k", present = false, recycled = false))
        assertFalse(OverlaySlot.skipRebuild("k", "k", present = true, recycled = true))
    }

    @Test
    fun differentKeyForcesRebuild() {
        assertFalse(OverlaySlot.skipRebuild("old", "new", present = true, recycled = false))
    }

    @Test
    fun oldLayerIsNeverRecycledByPublisher() {
        assertFalse("绘制线程可能正持有旧层，主动回收会让它画到已回收位图", OverlaySlot.mayRecycleOld())
    }

    @Test
    fun writeKeyThenSlotReadSlotThenKeyNeverYieldsNewSlotWithOldKey() {
        var slotKey = "old"
        var slot: String? = "oldBitmap"
        fun publish(key: String, bmp: String) {
            slotKey = key
            slot = bmp
        }
        fun peek(want: String): String? {
            val s = slot
            val k = slotKey
            return if (OverlaySlot.usable(k, want, s != null, false)) s else null
        }
        assertEquals("oldBitmap", peek("old"))
        publish("new", "newBitmap")
        assertEquals("newBitmap", peek("new"))
        assertEquals(null, peek("old"))
    }

    @Test
    fun writeSlotThenKeyCanExposeNewSlotUnderOldKey() {
        var slotKey = "old"
        var slot: String? = "oldBitmap"
        fun publishWrongOrder(key: String, bmp: String) {
            slot = bmp
            slotKey = key
        }
        publishWrongOrder("new", "newBitmap")
        val s = slot
        val k = "old"
        assertTrue(
            "反序写 + 读方读到中间态时，新图会配旧键通过校验",
            OverlaySlot.usable(k, "old", s != null, false),
        )
        assertEquals("newBitmap", s)
    }

    @Test
    fun clearMakesSlotUnusableRegardlessOfKey() {
        var slotKey = "k"
        var slot: String? = "bmp"
        slotKey = ""
        slot = null
        assertFalse(OverlaySlot.usable(slotKey, "k", slot != null, false))
        assertFalse(OverlaySlot.usable(slotKey, "", slot != null, false))
    }

    @Test
    fun usableAndSkipRebuildAgreeOnSameState() {
        for (present in booleanArrayOf(true, false)) {
            for (recycled in booleanArrayOf(true, false)) {
                assertEquals(
                    "present=$present recycled=$recycled",
                    OverlaySlot.usable("k", "k", present, recycled),
                    OverlaySlot.skipRebuild("k", "k", present, recycled),
                )
            }
        }
    }
}