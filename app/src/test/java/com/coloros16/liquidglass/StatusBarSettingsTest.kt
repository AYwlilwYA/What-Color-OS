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

    // ---------- 运营商文字 ----------

    @Test
    fun carrierReplacementEmptyByDefault() {
        assertEquals("", StatusBarConfig().carrierReplacement)
        assertFalse(StatusBarConfig(enabled = true).anyFeature)
    }

    @Test
    fun carrierTextAloneCountsAsFeature() {
        assertTrue(StatusBarConfig(enabled = true, carrierText = "Hello World").anyFeature)
    }

    @Test
    fun carrierTextIgnoredWhenMasterOff() {
        assertFalse(StatusBarConfig(enabled = false, carrierText = "Hello World").anyFeature)
    }

    // ---------- 通知中心时钟 ----------

    @Test
    fun notifClockDefaultsTouchNothing() {
        val cfg = StatusBarConfig(enabled = true)
        assertFalse(cfg.notifClockTouched)
        assertFalse(cfg.anyFeature)
        assertEquals(1f, cfg.notifClockScale, 1e-6f)
        assertEquals(0, cfg.notifDateSizeSp)
        assertEquals(10, cfg.notifClockDateGapDp)
    }

    /** 总开关关时，即便所有参数都改过也不得动手（阳性对照：开着就必须动）。 */
    @Test
    fun notifClockMasterSwitchGatesEverything() {
        val all = StatusBarConfig(
            enabled = true, notifClockEnable = false,
            notifClockGravity = 2, notifDatePosition = 1,
            notifClockScale = 2.4f, notifDateSizeSp = 16, notifClockDateGapDp = 24,
            notifHideSettingsBtn = true,
        )
        assertFalse(all.notifClockTouched)
        assertFalse(all.anyFeature)
        assertTrue(all.copy(notifClockEnable = true).notifClockTouched)
        assertTrue(all.copy(notifClockEnable = true).anyFeature)
    }

    /** 只开总开关但参数全默认 = 零写入；隐藏设置按钮是独立项，单独也算生效。 */
    @Test
    fun notifClockEnableAloneIsZeroWrite() {
        val on = StatusBarConfig(enabled = true, notifClockEnable = true)
        assertFalse(on.notifClockTouched)
        assertFalse(on.anyFeature)
        assertTrue(on.copy(notifHideSettingsBtn = true).anyFeature)
        assertFalse(on.copy(notifHideSettingsBtn = true).notifClockTouched)
    }

    /** 间距等于默认值不算改动（否则一开开关就写系统）。 */
    @Test
    fun notifGapAtDefaultIsNotTouched() {
        val base = StatusBarConfig(enabled = true, notifClockEnable = true)
        assertFalse(base.copy(notifClockDateGapDp = 10).notifClockTouched)
        assertTrue(base.copy(notifClockDateGapDp = 11).notifClockTouched)
        assertTrue(base.copy(notifClockDateGapDp = 0).notifClockTouched)
    }

    @Test
    fun notifEachParamIndependentlyTriggers() {
        val base = StatusBarConfig(enabled = true, notifClockEnable = true)
        assertTrue(base.copy(notifClockGravity = 1).notifClockTouched)
        assertTrue(base.copy(notifDatePosition = 3).notifClockTouched)
        assertTrue(base.copy(notifClockScale = 1.05f).notifClockTouched)
        assertTrue(base.copy(notifDateSizeSp = 16).notifClockTouched)
    }

    // ---------- 控制中心边距 ----------

    @Test
    fun ccMarginsDefaultToSystemSentinel() {
        val cfg = StatusBarConfig()
        assertEquals(-1, cfg.ccMarginLeftDp)
        assertEquals(-1, cfg.ccMarginRightDp)
        assertFalse(StatusBarConfig(enabled = true).anyFeature)
    }

    /** -1 是「用系统原值」哨兵，0 是真实的零边距，两者必须区分。 */
    @Test
    fun ccMarginZeroIsRealWriteButMinusOneIsNot() {
        assertFalse(StatusBarConfig(enabled = true, ccMarginLeftDp = -1).anyFeature)
        assertTrue(StatusBarConfig(enabled = true, ccMarginLeftDp = 0).anyFeature)
        assertTrue(StatusBarConfig(enabled = true, ccMarginRightDp = 0).anyFeature)
    }

    /** 控制中心边距与收起态状态栏边距是两套键，互不串味。 */
    @Test
    fun ccMarginsIndependentFromCollapsedMargins() {
        val ccOnly = StatusBarConfig(enabled = true, ccMarginLeftDp = 12)
        assertEquals(-1, ccOnly.marginLeftDp)
        assertTrue(ccOnly.anyFeature)
        val sbOnly = StatusBarConfig(enabled = true, marginLeftDp = 12)
        assertEquals(-1, sbOnly.ccMarginLeftDp)
        assertTrue(sbOnly.anyFeature)
    }
}
