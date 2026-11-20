package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingActivity
import com.coloros16.liquidglass.engine.SeedlingAlertPolicy
import com.coloros16.liquidglass.engine.SeedlingExpanded
import com.coloros16.liquidglass.engine.SeedlingIdleHide
import com.coloros16.liquidglass.engine.SeedlingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 弹卡判定 + 无操作隐藏契约，含阳性对照。 */
class SeedlingAlertIdleContractTest {

    private fun act(kind: SeedlingKind, priority: Int = kind.defaultPriority, milestone: Boolean = false, expandable: Boolean = true) =
        SeedlingActivity(
            id = "a", source = "t", kind = kind, priority = priority, expandable = expandable,
            expanded = SeedlingExpanded(milestone = milestone),
        )

    // ---------- 弹卡判定 ----------

    @Test
    fun exclusiveCallAutoExpands() {
        assertTrue(SeedlingAlertPolicy.shouldAutoExpand(act(SeedlingKind.CALL), isNewActivity = true, previousMainId = null))
    }

    @Test
    fun codeAutoExpands() {
        assertTrue(SeedlingAlertPolicy.shouldAutoExpand(act(SeedlingKind.CODE), isNewActivity = true, previousMainId = "media:x"))
    }

    @Test
    fun milestoneAutoExpandsOnUpdate() {
        assertTrue(SeedlingAlertPolicy.shouldAutoExpand(act(SeedlingKind.LIVE_UPDATE, milestone = true), isNewActivity = false, previousMainId = "a"))
    }

    @Test
    fun highPriorityAutoExpands() {
        assertTrue(SeedlingAlertPolicy.shouldAutoExpand(act(SeedlingKind.TIMER, priority = 85), isNewActivity = false, previousMainId = "a"))
    }

    @Test
    fun normalMediaNeverAutoExpands_positiveControl() {
        assertFalse("阳性对照：普通媒体只上胶囊不弹卡", SeedlingAlertPolicy.shouldAutoExpand(act(SeedlingKind.MEDIA), true, null))
        assertFalse(SeedlingAlertPolicy.shouldAutoExpand(act(SeedlingKind.MESSAGE), true, null))
    }

    @Test
    fun notExpandableBlocks() {
        assertFalse(SeedlingAlertPolicy.shouldAutoExpand(act(SeedlingKind.CALL, expandable = false), true, null))
    }

    @Test
    fun holdMsDefault5000() {
        assertEquals(5000L, SeedlingAlertPolicy.holdMs(null))
        assertEquals(8000L, SeedlingAlertPolicy.holdMs(8000L))
    }

    // ---------- 无操作隐藏 ----------

    @Test
    fun disabledNeverHides() {
        val h = SeedlingIdleHide(180_000L, enabled = false)
        h.touch(0L)
        assertNull(h.dueInMs(999_999L))
        assertFalse(h.shouldHide(999_999L))
    }

    @Test
    fun enabledCountsDown() {
        val h = SeedlingIdleHide(180_000L, enabled = true)
        h.touch(1000L)
        assertEquals(180_000L, h.dueInMs(1000L))
        assertEquals(60_000L, h.dueInMs(121_000L))
        assertEquals(0L, h.dueInMs(181_000L))
        assertTrue(h.shouldHide(181_000L))
    }

    @Test
    fun interactionResets_positiveControl() {
        val h = SeedlingIdleHide(180_000L, enabled = true)
        h.touch(0L)
        assertFalse(h.shouldHide(179_000L))
        h.touch(179_000L)
        assertFalse("阳性对照：交互重置后不隐藏", h.shouldHide(181_000L))
        assertTrue(h.shouldHide(359_000L))
    }

    @Test
    fun noTouchNoAlarm() {
        val h = SeedlingIdleHide(180_000L, enabled = true)
        assertNull("未交互过不排闹钟", h.dueInMs(999_999L))
    }

    @Test
    fun resetClears() {
        val h = SeedlingIdleHide(180_000L, enabled = true)
        h.touch(0L)
        h.reset()
        assertNull(h.dueInMs(100L))
    }

    @Test
    fun toggleEnabledAtRuntime() {
        val h = SeedlingIdleHide(180_000L, enabled = false)
        h.touch(0L)
        assertNull(h.dueInMs(100L))
        h.enabled = true
        assertEquals(179_900L, h.dueInMs(100L))
    }
}
