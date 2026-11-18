package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.AodDisplayMode
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectParams
import com.coloros16.liquidglass.engine.effect.EffectSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectSettingsTest {

    @Test
    fun defaultsAreConservative() {
        val s = EffectSettings.defaults()
        assertFalse(s.allowAodDynamic)
        assertFalse(s.aodAlwaysPlay)
        assertEquals(EffectCatalog.LOCK_WP_NONE, s.lockWallpaperEffect)
        assertEquals(EffectCatalog.LOCK_SP_NONE, s.lockSpecialEffect)
        assertEquals(EffectCatalog.HOME_TRANSITION_NONE, s.homeTransition)
        assertEquals(EffectCatalog.HOME_ICON_FOREGROUND_FLY, s.homeIconEffect)
        assertEquals(EffectSettings.DEFAULT_STYLE_ID, s.aodStyleId)
        assertEquals(EffectSettings.DEFAULT_STYLE_ID, s.lockStyleId)
        assertTrue(s.homeFollowLock)
        assertFalse(s.homeAodIndependent)
        assertEquals(AodDisplayMode.ALWAYS, s.displayMode)
        assertEquals(22 * 60, s.scheduleStartMinute)
        assertEquals(7 * 60, s.scheduleEndMinute)
    }

    @Test
    fun paramsFallBackToTableDefaults() {
        val s = EffectSettings.defaults()
        assertEquals(60f, s.param(EffectParams.AOD_COLOR_MIX), 0f)
        assertEquals(1f, s.param(EffectParams.LOCK_WP_SPEED), 0f)
        assertEquals(0f, s.param("no.such.param"), 0f)
    }

    @Test
    fun setParamRejectsUnknownAndClamps() {
        val s = EffectSettings.defaults()
        assertFalse(s.setParam("no.such.param", 1f))
        assertTrue(s.setParam(EffectParams.AOD_COLOR_MIX, 999f))
        assertEquals(100f, s.param(EffectParams.AOD_COLOR_MIX), 0f)
        s.resetParam(EffectParams.AOD_COLOR_MIX)
        assertEquals(60f, s.param(EffectParams.AOD_COLOR_MIX), 0f)
    }

    @Test
    fun roundTripKeepsEveryField() {
        val s = EffectSettings.defaults()
        s.allowAodDynamic = true
        s.aodAlwaysPlay = true
        s.aodStyleId = "aod.galaxy"
        s.lockStyleId = "aod.starmoon"
        s.lockWallpaperEffect = EffectCatalog.LOCK_WP_RIPPLE
        s.lockSpecialEffect = EffectCatalog.LOCK_SP_LIQUID
        s.homeTransition = EffectCatalog.HOME_TRANSITION_CROSSFADE
        s.homeIconEffect = EffectCatalog.HOME_ICON_ROTATE_FLY
        s.homeFollowLock = false
        s.homeAodIndependent = true
        s.homeAodStyleId = "aod.door"
        s.displayMode = AodDisplayMode.SCHEDULED
        s.scheduleStartMinute = 23 * 60 + 30
        s.scheduleEndMinute = 6 * 60 + 15
        s.aodInfoBattery = false
        s.aodInfoNotificationIcon = false
        s.aodSceneInfo = true
        s.aodNewNotification = false
        s.setParam(EffectParams.AOD_PHOTO_TILT, 9.5f)
        s.setParam(EffectParams.HOME_ICON_STAGGER, 40f)
        s.aodFramePair = false
        s.aodFramePhoto = "content://media/external/images/1"
        s.aodPhoto = "sakura"
        s.setParam(EffectParams.AOD_FRAME_X, 30f)
        s.setParam(EffectParams.AOD_FRAME_Y, 70f)
        s.setParam(EffectParams.AOD_FRAME_SIZE, 80f)
        s.setParam(EffectParams.AOD_FRAME_ANGLE, -20f)

        val parsed = EffectSettings.parse(s.toText())
        assertEquals(s.allowAodDynamic, parsed.allowAodDynamic)
        assertEquals(s.aodAlwaysPlay, parsed.aodAlwaysPlay)
        assertEquals(s.aodFramePair, parsed.aodFramePair)
        assertEquals(s.aodFramePhoto, parsed.aodFramePhoto)
        assertEquals(s.aodPhoto, parsed.aodPhoto)
        assertEquals(s.aodStyleId, parsed.aodStyleId)
        assertEquals(s.lockStyleId, parsed.lockStyleId)
        assertEquals(s.lockWallpaperEffect, parsed.lockWallpaperEffect)
        assertEquals(s.lockSpecialEffect, parsed.lockSpecialEffect)
        assertEquals(s.homeTransition, parsed.homeTransition)
        assertEquals(s.homeIconEffect, parsed.homeIconEffect)
        assertEquals(s.homeFollowLock, parsed.homeFollowLock)
        assertEquals(s.homeAodIndependent, parsed.homeAodIndependent)
        assertEquals(s.homeAodStyleId, parsed.homeAodStyleId)
        assertEquals(s.displayMode, parsed.displayMode)
        assertEquals(s.scheduleStartMinute, parsed.scheduleStartMinute)
        assertEquals(s.scheduleEndMinute, parsed.scheduleEndMinute)
        assertEquals(s.aodInfoBattery, parsed.aodInfoBattery)
        assertEquals(s.aodInfoNotificationIcon, parsed.aodInfoNotificationIcon)
        assertEquals(s.aodSceneInfo, parsed.aodSceneInfo)
        assertEquals(s.aodNewNotification, parsed.aodNewNotification)
        assertEquals(s.paramOverrides(), parsed.paramOverrides())
    }

    @Test
    fun parseIsTolerant() {
        val text = """
            v1
            # comment line
            unknown_key=whatever
            allow_aod_dynamic=yes
            home_follow_lock=0
            aod_display_mode=POWER_SAVE
            aod_schedule=bad-value
            lock_wp=lock.wp.none
            p.aod.color_mix=not-a-number
            p.no.such.param=5
            p.aod.rotate_speed=-20
        """.trimIndent()
        val s = EffectSettings.parse(text)
        assertTrue(s.allowAodDynamic)
        assertFalse(s.homeFollowLock)
        assertEquals(AodDisplayMode.POWER_SAVE, s.displayMode)
        // 非法时间窗保持默认
        assertEquals(22 * 60, s.scheduleStartMinute)
        assertEquals(7 * 60, s.scheduleEndMinute)
        // 非法 / 未知参数不写入；越界参数夹紧
        assertEquals(60f, s.param(EffectParams.AOD_COLOR_MIX), 0f)
        assertFalse(s.paramOverrides().containsKey("no.such.param"))
        assertEquals(0f, s.param(EffectParams.AOD_ROTATE_SPEED), 0f)
    }

    @Test
    fun emptyAndNullUseDefaults() {
        assertEquals(EffectSettings.defaults().toText(), EffectSettings.parse(null).toText())
        assertEquals(EffectSettings.defaults().toText(), EffectSettings.parse("").toText())
        assertEquals(EffectSettings.defaults().toText(), EffectSettings.parse("   \n\n").toText())
    }

    @Test
    fun versionLineTolerated() {
        val s = EffectSettings.parse("v1\naod_style=aod.door\n")
        assertEquals("aod.door", s.aodStyleId)
        val v2 = EffectSettings.parse("v2\naod_style=aod.door\n")
        assertEquals("aod.door", v2.aodStyleId)
    }

    @Test
    fun legacySpecialIdsMigrate() {
        val s = EffectSettings.parse("lock_sp=lock.sp.vivo.liquid")
        assertEquals(EffectCatalog.LOCK_SP_LIQUID, s.lockSpecialEffect)
        val written = s.toText()
        assertFalse(written.contains("lock.sp.vivo"))
    }
}
