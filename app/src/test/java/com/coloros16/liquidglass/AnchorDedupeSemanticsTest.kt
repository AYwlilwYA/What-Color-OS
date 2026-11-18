package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorDedupeSemanticsTest {

    private data class AnchorKey(val x: Float, val y: Float, val scale: Float)

    private class PlainKey(val x: Float, val y: Float, val scale: Float)

    @Test
    fun plainClassKeyNeverEqualsFreshInstance() {
        val a = PlainKey(0.5f, 0.3f, 1f)
        val b = PlainKey(0.5f, 0.3f, 1f)
        assertFalse("普通 class 按引用比，同值新建实例必不等", a == b)
    }

    @Test
    fun dataClassKeyEqualsFreshInstanceWithSameValues() {
        val a = AnchorKey(0.5f, 0.3f, 1f)
        val b = AnchorKey(0.5f, 0.3f, 1f)
        assertTrue(a == b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun dataClassKeyDiffersWhenAnyFieldDiffers() {
        val base = AnchorKey(0.5f, 0.3f, 1f)
        assertNotEquals(base, base.copy(x = 0.51f))
        assertNotEquals(base, base.copy(y = 0.31f))
        assertNotEquals(base, base.copy(scale = 1.01f))
    }

    @Test
    fun mapLookupDedupesWithDataClassButNotWithPlainClass() {
        val good = HashMap<SceneState, AnchorKey>()
        good[SceneState.AOD] = AnchorKey(0.5f, 0.3f, 1f)
        assertTrue(good[SceneState.AOD] == AnchorKey(0.5f, 0.3f, 1f))

        val bad = HashMap<SceneState, PlainKey>()
        bad[SceneState.AOD] = PlainKey(0.5f, 0.3f, 1f)
        assertFalse(bad[SceneState.AOD] == PlainKey(0.5f, 0.3f, 1f))
    }

    @Test
    fun repeatedSyncWritesOnceWhenValuesUnchanged() {
        val machine = ClockStateMachine()
        val seen = HashMap<SceneState, AnchorKey>()
        var writes = 0
        repeat(60) {
            val key = AnchorKey(0.4f, 0.25f, 0.9f)
            if (seen[SceneState.AOD] != key) {
                seen[SceneState.AOD] = key
                machine.setAnchor(SceneState.AOD, ClockAnchor(key.x, key.y, key.scale), 0L, follow = false)
                writes++
            }
        }
        assertEquals("值不变只该写一次", 1, writes)
    }

    @Test
    fun repeatedSyncWritesEveryFrameWithPlainClassKey() {
        val seen = HashMap<SceneState, PlainKey>()
        var writes = 0
        repeat(60) {
            val key = PlainKey(0.4f, 0.25f, 0.9f)
            if (seen[SceneState.AOD] != key) {
                seen[SceneState.AOD] = key
                writes++
            }
        }
        assertEquals("普通 class 去重失效 ⇒ 每帧都写", 60, writes)
    }

    @Test
    fun syncWritesAgainWhenUserEditChangesValue() {
        val seen = HashMap<SceneState, AnchorKey>()
        var writes = 0
        val values = listOf(0.4f, 0.4f, 0.45f, 0.45f, 0.45f, 0.5f)
        for (x in values) {
            val key = AnchorKey(x, 0.25f, 0.9f)
            if (seen[SceneState.AOD] != key) {
                seen[SceneState.AOD] = key
                writes++
            }
        }
        assertEquals("三次真变化写三次", 3, writes)
    }

    @Test
    fun anchorWriteDoesNotDisturbRunningTransitionPose() {
        val machine = ClockStateMachine()
        machine.setAnchor(SceneState.AOD, ClockAnchor(0.2f, 0.2f, 0.8f), 0L, follow = false)
        machine.setAnchor(SceneState.LOCK, ClockAnchor(0.8f, 0.6f, 1.2f), 0L, follow = false)
        machine.onTransition(TransitionEdge.AOD_TO_LOCK, 0.5f)
        val before = machine.pose.centerX
        machine.setAnchor(SceneState.AOD, ClockAnchor(0.2f, 0.2f, 0.8f), 100L, follow = false)
        assertEquals("重写同值锚点不该改动正在跑的 pose", before, machine.pose.centerX, 0.0001f)
    }

    @Test
    fun followFalseNeverStartsTweenEvenWhenIdleOnSameState() {
        val machine = ClockStateMachine()
        machine.snapTo(SceneState.AOD, 0L)
        machine.setAnchor(SceneState.AOD, ClockAnchor(0.9f, 0.9f, 2f), 10L, follow = false)
        assertEquals("follow=false 不该立刻跳到新锚点", 0.5f, machine.pose.centerX, 0.0001f)
        assertEquals(0.5f, machine.pose.centerY, 0.0001f)
    }

    @Test
    fun anchorIsReadBackAfterSync() {
        val machine = ClockStateMachine()
        machine.setAnchor(SceneState.LOCK, ClockAnchor(0.33f, 0.44f, 1.1f), 0L, follow = false)
        val a = machine.anchorOf(SceneState.LOCK)
        assertEquals(0.33f, a.centerX, 0.0001f)
        assertEquals(0.44f, a.centerY, 0.0001f)
        assertEquals(1.1f, a.scale, 0.0001f)
    }

    @Test
    fun nanFieldBreaksDedupeSoGuardIsNeededUpstream() {
        val a = AnchorKey(Float.NaN, 0.3f, 1f)
        val b = AnchorKey(Float.NaN, 0.3f, 1f)
        assertTrue("data class 用 Float.equals，NaN 自等 ⇒ 仍能去重", a == b)
    }

    @Test
    fun negativeZeroAndZeroAreDistinctKeys() {
        val a = AnchorKey(-0.0f, 0.3f, 1f)
        val b = AnchorKey(0.0f, 0.3f, 1f)
        assertNotEquals("data class 用装箱 equals，-0.0 与 0.0 不等 ⇒ 会多写一次", a, b)
    }
}