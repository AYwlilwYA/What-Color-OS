package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingGesturePolicy
import com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture
import org.junit.Assert.assertEquals
import org.junit.Test

/** 胶囊手势判定契约（真值 86dp/s、2×slop、主轴），含阳性对照。 */
class SeedlingGestureContractTest {

    private val slop = 24
    private val density = 3f
    private val fast = 400f * density // 400dp/s > 86dp/s

    private fun classify(
        dx: Float, dy: Float, vx: Float, vy: Float,
        expanded: Boolean = false, hasSatellites: Boolean = false,
        cardSwipeFloating: Boolean = true, launchableSource: Boolean = true,
        appleCycle: Boolean = false,
    ) = SeedlingGesturePolicy.classify(dx, dy, vx, vy, slop, density, expanded, hasSatellites, cardSwipeFloating, launchableSource, appleCycle)

    @Test
    fun horizontalFlingDismisses() {
        assertEquals(Gesture.FLING_DISMISS, classify(dx = 100f, dy = 5f, vx = fast, vy = 10f))
        assertEquals(Gesture.FLING_DISMISS, classify(dx = -100f, dy = 5f, vx = -fast, vy = 10f))
    }

    @Test
    fun horizontalWithSatellitesRotates() {
        assertEquals("有副岛右滑=轮换（左副岛顶上）", Gesture.ROTATE_SATELLITE_RIGHT, classify(dx = 100f, dy = 5f, vx = fast, vy = 10f, hasSatellites = true))
        assertEquals("有副岛左滑=轮换（右副岛顶上）", Gesture.ROTATE_SATELLITE_LEFT, classify(dx = -100f, dy = 5f, vx = -fast, vy = 10f, hasSatellites = true))
    }

    @Test
    fun horizontalNoSatellitesStillDismisses_positiveControl() {
        assertEquals("阳性对照：无副岛横滑仍划掉", Gesture.FLING_DISMISS, classify(dx = 100f, dy = 5f, vx = fast, vy = 10f, hasSatellites = false))
    }

    @Test
    fun expandedAppleHorizontalCycles() {
        assertEquals("苹果式大卡右滑=换卡", Gesture.CARD_CYCLE_RIGHT, classify(dx = 100f, dy = 5f, vx = fast, vy = 10f, expanded = true, appleCycle = true))
        assertEquals("苹果式大卡左滑=换卡", Gesture.CARD_CYCLE_LEFT, classify(dx = -100f, dy = 5f, vx = -fast, vy = 10f, expanded = true, appleCycle = true))
    }

    @Test
    fun expandedNonAppleHorizontalStillDismisses_positiveControl() {
        assertEquals("阳性对照：非苹果式大卡横滑仍划掉", Gesture.FLING_DISMISS, classify(dx = 100f, dy = 5f, vx = fast, vy = 10f, expanded = true, appleCycle = false))
    }

    @Test
    fun capsuleAppleCycleIgnored_positiveControl() {
        assertEquals("阳性对照：胶囊态 appleCycle 不生效仍划掉", Gesture.FLING_DISMISS, classify(dx = 100f, dy = 5f, vx = fast, vy = 10f, expanded = false, appleCycle = true))
    }

    @Test
    fun verticalFlingPromotesSatellite() {
        assertEquals("上滑提升左副岛", Gesture.PROMOTE_LEFT, classify(dx = 5f, dy = -100f, vx = 10f, vy = -fast, hasSatellites = true))
        assertEquals("下滑提升右副岛", Gesture.PROMOTE_RIGHT, classify(dx = 5f, dy = 100f, vx = 10f, vy = fast, hasSatellites = true))
    }

    @Test
    fun verticalFlingNoSatelliteNone_positiveControl() {
        assertEquals("阳性对照：无副岛竖滑不触发", Gesture.NONE, classify(dx = 5f, dy = -100f, vx = 10f, vy = -fast, hasSatellites = false))
    }

    @Test
    fun expandedCardUpCollapses() {
        assertEquals(Gesture.CARD_COLLAPSE, classify(dx = 5f, dy = -100f, vx = 10f, vy = -fast, expanded = true))
    }

    @Test
    fun expandedCardDownFloatingOpen() {
        assertEquals(Gesture.CARD_FLOATING_OPEN, classify(dx = 5f, dy = 100f, vx = 10f, vy = fast, expanded = true))
    }

    @Test
    fun expandedCardDownBlocked_positiveControl() {
        assertEquals("阳性对照：规则关下滑不触发", Gesture.NONE, classify(dx = 5f, dy = 100f, vx = 10f, vy = fast, expanded = true, cardSwipeFloating = false))
        assertEquals("阳性对照：占位来源下滑不触发", Gesture.NONE, classify(dx = 5f, dy = 100f, vx = 10f, vy = fast, expanded = true, launchableSource = false))
    }

    @Test
    fun slowSwipeNone_positiveControl() {
        val slow = 40f * density // 40dp/s < 86dp/s
        assertEquals("阳性对照：速度不足不触发", Gesture.NONE, classify(dx = 100f, dy = 5f, vx = slow, vy = 10f))
        assertEquals(Gesture.NONE, classify(dx = 5f, dy = -100f, vx = 10f, vy = -slow, hasSatellites = true))
    }

    @Test
    fun smallDisplacementNone_positiveControl() {
        assertEquals("阳性对照：位移不足2slop不触发", Gesture.NONE, classify(dx = 30f, dy = 5f, vx = fast, vy = 10f))
    }

    @Test
    fun horizontalWinsOverVertical() {
        assertEquals("主轴横向优先（有副岛=轮换）", Gesture.ROTATE_SATELLITE_RIGHT, classify(dx = 100f, dy = 60f, vx = fast, vy = fast, hasSatellites = true))
        assertEquals("无副岛主轴横向=划掉", Gesture.FLING_DISMISS, classify(dx = 100f, dy = 60f, vx = fast, vy = fast, hasSatellites = false))
    }
}
