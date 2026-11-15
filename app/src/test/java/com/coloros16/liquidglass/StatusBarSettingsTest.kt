package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.statusbar.StatusBarConfig
import com.coloros16.liquidglass.hook.statusbar.StatusBarSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarSettingsTest {

    @Test
    fun blankOrderMeansSystemOrder() {
        assertEquals(emptyList<String>(), StatusBarSettings.parseOrder(null))
        assertEquals(emptyList<String>(), StatusBarSettings.parseOrder(""))
        assertEquals(emptyList<String>(), StatusBarSettings.parseOrder("  , ,  "))
    }

    @Test
    fun orderParsedLowercasedAndDeduplicated() {
        assertEquals(
            listOf("wifi", "mobile", "battery"),
            StatusBarSettings.parseOrder(" WiFi, mobile ,WIFI, battery "),
        )
    }

    @Test
    fun legacyAlarmAliasNormalizesToRealSlotName() {
        assertEquals("alarm_clock", StatusBarSettings.normalizeSlot("alarm"))
        assertEquals("alarm_clock", StatusBarSettings.parseOrder("wifi,alarm").last())
        assertEquals(listOf("alarm_clock"), StatusBarSettings.parseOrder("alarm,alarm_clock"))
    }

    @Test
    fun notificationKeyIsNotAnIconSlot() {
        assertTrue(StatusBarSettings.isIconSlot("mobile"))
        assertTrue(StatusBarSettings.isIconSlot("battery"))
        assertFalse(StatusBarSettings.isIconSlot("com.tencent.mm/0x628abfac"))
        assertFalse(StatusBarSettings.isIconSlot(""))
        assertEquals(listOf("mobile"), StatusBarSettings.parseOrder("com.tencent.mm/0x628abfac,mobile"))
    }

    @Test
    fun defaultConfigCoversNoFeature() {
        val cfg = StatusBarConfig()
        assertFalse(cfg.enabled)
        assertFalse(cfg.anyFeature)
        assertEquals(0.08f, cfg.dualRowGap, 0.0001f)
        assertEquals(0.25f, cfg.secondaryScale, 0.0001f)
        assertEquals(-1, cfg.marginLeftDp)
        assertEquals(-1, cfg.marginRightDp)
    }

    @Test
    fun masterOffDisablesFeatureDetection() {
        val cfg = StatusBarConfig(enabled = false, hideNetworkType = true)
        assertFalse(cfg.anyFeature)
        val on = cfg.copy(enabled = true)
        assertTrue(on.anyFeature)
    }

    @Test
    fun simplifyAloneCountsAsFeature() {
        assertTrue(StatusBarConfig(enabled = true, simplify = true).anyFeature)
    }
}
