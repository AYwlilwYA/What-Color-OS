package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingKind
import com.coloros16.liquidglass.engine.SeedlingStatusPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 常驻状态岛（充电/耳机）求值契约，含阳性对照。 */
class SeedlingStatusContractTest {

    // ---------- 电量钳位 ----------

    @Test
    fun clampLevelBounds() {
        assertEquals(0, SeedlingStatusPolicy.clampLevel(-5))
        assertEquals(100, SeedlingStatusPolicy.clampLevel(150))
        assertEquals(68, SeedlingStatusPolicy.clampLevel(68))
    }

    // ---------- 充电文案 ----------

    @Test
    fun chargingLabelNormal() {
        assertEquals("充电中 68%", SeedlingStatusPolicy.chargingLabel(68, full = false))
        assertEquals("充电中 0%", SeedlingStatusPolicy.chargingLabel(-3, full = false))
    }

    @Test
    fun chargingLabelFull() {
        assertEquals("已充满", SeedlingStatusPolicy.chargingLabel(100, full = true))
        assertEquals("已充满", SeedlingStatusPolicy.chargingLabel(80, full = true))
    }

    @Test
    fun isFullRequiresCharging_positiveControl() {
        assertTrue(SeedlingStatusPolicy.isFull(100, charging = true))
        assertFalse("阳性对照：满电但没插电不算充满", SeedlingStatusPolicy.isFull(100, charging = false))
        assertFalse(SeedlingStatusPolicy.isFull(99, charging = true))
    }

    @Test
    fun chargingSubtitlePlugType() {
        assertEquals("USB · 68%", SeedlingStatusPolicy.chargingSubtitle(68, 2))
        assertEquals("交流电源 · 50%", SeedlingStatusPolicy.chargingSubtitle(50, 1))
        assertEquals("无线充电 · 30%", SeedlingStatusPolicy.chargingSubtitle(30, 4))
        assertEquals("电源 · 10%", SeedlingStatusPolicy.chargingSubtitle(10, 0))
    }

    // ---------- 上岛判定 ----------

    @Test
    fun chargingActiveOnlyWhenPlugged() {
        assertTrue(SeedlingStatusPolicy.chargingActive(plugged = true))
        assertFalse("阳性对照：拔线即撤", SeedlingStatusPolicy.chargingActive(plugged = false))
    }

    @Test
    fun headsetActiveWiredOrBluetooth() {
        assertTrue(SeedlingStatusPolicy.headsetActive(wiredConnected = true, bluetoothAudioConnected = false))
        assertTrue(SeedlingStatusPolicy.headsetActive(wiredConnected = false, bluetoothAudioConnected = true))
        assertFalse("阳性对照：两者都无不上岛", SeedlingStatusPolicy.headsetActive(false, false))
    }

    @Test
    fun headsetLabelByType() {
        assertEquals("耳机已连接", SeedlingStatusPolicy.headsetLabel(wired = true))
        assertEquals("蓝牙耳机已连接", SeedlingStatusPolicy.headsetLabel(wired = false))
    }

    // ---------- 活动构造 ----------

    @Test
    fun chargingActivityIsLowPriorityStatus() {
        val a = SeedlingStatusPolicy.chargingActivity(68, 2, 1000L)
        assertEquals(SeedlingStatusPolicy.CHARGING_ID, a.id)
        assertEquals(SeedlingKind.STATUS, a.kind)
        assertEquals("STATUS 低优先 20", 20, a.priority)
        assertFalse("状态岛不可展开", a.expandable)
        assertFalse("状态岛不随来源前台隐藏", a.hideWhenSourceForeground)
        assertEquals("充电中 68%", a.compact.label)
        assertEquals(1000L, a.updatedAtElapsedMs)
    }

    @Test
    fun chargingActivityFullLabel() {
        val a = SeedlingStatusPolicy.chargingActivity(100, 2, 1000L)
        assertEquals("已充满", a.compact.label)
        assertEquals("已充满", a.expanded.title)
    }

    @Test
    fun headsetActivityIsLowPriorityStatus() {
        val a = SeedlingStatusPolicy.headsetActivity(wired = true, nowElapsedMs = 2000L)
        assertEquals(SeedlingStatusPolicy.HEADSET_ID, a.id)
        assertEquals(SeedlingKind.STATUS, a.kind)
        assertEquals(20, a.priority)
        assertFalse(a.expandable)
        assertEquals("耳机已连接", a.compact.label)
        assertEquals("有线耳机", a.expanded.subtitle)
    }

    @Test
    fun headsetActivityBluetoothSubtitle() {
        val a = SeedlingStatusPolicy.headsetActivity(wired = false, nowElapsedMs = 2000L)
        assertEquals("蓝牙耳机已连接", a.compact.label)
        assertEquals("蓝牙音频", a.expanded.subtitle)
    }

    @Test
    fun chargingAndHeadsetDistinctIds_positiveControl() {
        val c = SeedlingStatusPolicy.chargingActivity(50, 2, 0L)
        val h = SeedlingStatusPolicy.headsetActivity(true, 0L)
        assertTrue("阳性对照：充电与耳机是两条独立活动可并存", c.id != h.id)
        assertEquals(SeedlingStatusPolicy.SOURCE, c.source)
        assertEquals(SeedlingStatusPolicy.SOURCE, h.source)
    }
}
