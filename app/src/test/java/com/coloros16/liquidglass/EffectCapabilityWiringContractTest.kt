package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectBlockReason
import com.coloros16.liquidglass.engine.effect.EffectCapabilities
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectFamily
import com.coloros16.liquidglass.engine.effect.EffectKind
import com.coloros16.liquidglass.engine.effect.EffectResolver
import com.coloros16.liquidglass.engine.effect.EffectSettings
import com.coloros16.liquidglass.engine.effect.StyleFamilyMap
import com.coloros16.liquidglass.engine.effect.panoramaAodActiveOf
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 效果能力门槛接线契约：能力参必传、三侧同源、门槛回退有日志。
 *
 * 治的缺陷是「带默认值的能力参让调用方漏传也能编译过」——
 * hook 侧曾只传 settings，等于静默宣称本机没陀螺仪、不是全景息屏。
 */
class EffectCapabilityWiringContractTest {

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
    ).readText()

    private fun settingsUi(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodSettingsUi.kt",
    ).readText()

    private fun resolver(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/engine/effect/EffectResolver.kt",
    ).readText()

    private fun body(text: String, name: String): String {
        val at = text.indexOf("fun $name(")
        if (at < 0) return ""
        var depth = 0
        var started = false
        val sb = StringBuilder()
        for (i in at until text.length) {
            val c = text[i]
            sb.append(c)
            if (c == '{') { depth++; started = true }
            if (c == '}') { depth--; if (started && depth == 0) break }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 漏传陷阱

    /** 能力参不许有默认值：有默认值 = 漏传能编译过 = 静默宣称设备没这能力。 */
    @Test
    fun resolveTakesCapabilitiesWithoutDefaults() {
        val sig = resolver().substringAfter("fun resolve(").substringBefore("): ResolvedEffects")
        assertTrue("能力参必须显式声明", sig.contains("capabilities: EffectCapabilities"))
        assertFalse("能力参不得有默认值", sig.contains("EffectCapabilities ="))
        assertFalse("三个能力布尔不得再作独立形参", sig.contains("hasGyro: Boolean"))
        assertFalse("全景不得再作独立形参", sig.contains("panoramaAodActive: Boolean"))
        assertFalse("图片场景不得再作独立形参", sig.contains("photoSceneAvailable: Boolean"))
    }

    /** 阳性对照：旧签名（三参各带 = false）只传 settings 也能编译过，正是缺陷成因。 */
    @Test
    fun positiveControlOldSignatureAllowedSilentOmission() {
        val oldSig = "hasGyro: Boolean = false, panoramaAodActive: Boolean = false"
        assertTrue("旧签名确实带默认值", oldSig.contains("= false"))
        val newSig = resolver().substringAfter("fun resolve(").substringBefore("): ResolvedEffects")
        assertFalse("新签名不含任何默认值", newSig.contains("= false"))
    }

    /** 显式降级值必须存在且三项全 false，供调用方主动表达「能力都不可用」。 */
    @Test
    fun explicitNoneIsAvailableButNotImplicit() {
        assertFalse(EffectCapabilities.NONE.hasGyro)
        assertFalse(EffectCapabilities.NONE.panoramaAodActive)
        assertFalse(EffectCapabilities.NONE.photoSceneAvailable)
    }

    // ---------------------------------------------------------------- hook 接线

    @Test
    fun hookPassesAllThreeCapabilities() {
        val src = body(stage(), "effectiveEffects")
        assertTrue("hook 必须传能力快照", src.contains("EffectResolver.resolve(settings, caps)"))
        assertTrue("陀螺仪走探针", src.contains("hasGyro = gyroAvailable()"))
        assertTrue("全景走共用判定", src.contains("panoramaAodActive = panoramaAodActiveOf(styleId())"))
        assertTrue("图片场景显式给 false", src.contains("photoSceneAvailable = false"))
        assertFalse("不得再只传 settings", src.contains("EffectResolver.resolve(settings)\n"))
    }

    /** 陀螺仪一次探测即缓存，取不到 Context 不写缓存（下次再试），无定时器无轮询。 */
    @Test
    fun gyroProbeIsCachedOnceAndNotPolled() {
        val src = body(stage(), "gyroAvailable")
        assertTrue("先读缓存", src.contains("gyroCached?.let { return it }"))
        assertTrue("探到才写缓存", src.contains("gyroCached = has"))
        assertTrue("无 Context 不写缓存", src.indexOf("return false") < src.indexOf("gyroCached = has"))
        assertFalse("禁定时器", src.contains("postDelayed"))
        assertFalse("禁睡眠轮询", src.contains("Thread.sleep"))
        assertFalse("禁固定间隔执行器", src.contains("ScheduledExecutor"))
    }

    @Test
    fun gyroProbeUsesPublicSensorApiOnly() {
        val src = body(stage(), "gyroAvailable")
        assertTrue("走 SensorManager", src.contains("android.hardware.SensorManager::class.java"))
        assertTrue("查陀螺仪", src.contains("android.hardware.Sensor.TYPE_GYROSCOPE"))
        assertTrue("Context 走进程取法", src.contains("Lg.processContext()"))
        assertFalse("不反射隐藏符号", src.contains("getDeclaredMethod"))
        assertFalse("不反射隐藏字段", src.contains("getDeclaredField"))
    }

    /** 热重载必须清能力缓存与门槛结论键，否则新一代带着旧判定跑。 */
    @Test
    fun hotReloadClearsCapabilityCache() {
        val src = body(stage(), "shutdownForHotReload")
        assertTrue("清陀螺仪缓存", src.contains("gyroCached = null"))
        assertTrue("清门槛结论键", src.contains("lastEffectGateKey = \"\""))
    }

    // ---------------------------------------------------------------- 门槛可裁决

    /** 门槛回退是静默的（回 null 后渲染侧根本不请求），必须有结论日志否则定不了位。 */
    @Test
    fun gateVerdictHasDiagnosticWithCapabilityTruth() {
        val src = body(stage(), "effectiveEffects")
        assertTrue("解析后打门槛结论", src.contains("logEffectGate(caps, wp.reason, sp.reason, settings)"))
        val log = body(stage(), "logEffectGate")
        assertTrue("按结论判重不逐帧打", log.contains("if (key == lastEffectGateKey) return"))
        assertTrue("打陀螺仪真值", log.contains("gyro=\${caps.hasGyro}"))
        assertTrue("打全景真值", log.contains("panorama=\${caps.panoramaAodActive}"))
        assertTrue("打当前样式", log.contains("style=\${styleId()}"))
        assertTrue("两槽原因都打", log.contains("wp=") && log.contains("sp="))
    }

    // ---------------------------------------------------------------- 三侧同源

    @Test
    fun panoramaJudgementLivesInEngineOnly() {
        assertTrue(
            "设置页走共用判定",
            settingsUi().contains("panoramaAodActiveOf(effectiveStyleId())"),
        )
        assertTrue("hook 走共用判定", stage().contains("panoramaAodActiveOf(styleId())"))
        assertFalse(
            "设置页不得再自带字面量比较",
            settingsUi().contains("settings.aodStyleId == AodStyleCatalog.ID_FULLSCREEN"),
        )
    }

    /** 共用判定与样式目录常量必须一致，否则改了目录 id 门槛会静默失效。 */
    @Test
    fun panoramaJudgementAgreesWithStyleCatalog() {
        assertTrue(
            "全景样式 id 判为真",
            panoramaAodActiveOf(AodStyleCatalog.ID_FULLSCREEN),
        )
        assertEquals(
            "全景家族与判定同源",
            EffectFamily.PANORAMA, StyleFamilyMap.familyOf(AodStyleCatalog.ID_FULLSCREEN),
        )
        for (style in AodStyleCatalog.styles) {
            assertEquals(
                "style=${style.id} 判定必须等于家族归属",
                StyleFamilyMap.familyOf(style.id) == EffectFamily.PANORAMA,
                panoramaAodActiveOf(style.id),
            )
        }
    }

    @Test
    fun panoramaJudgementRejectsNullAndUnknown() {
        assertFalse(panoramaAodActiveOf(null))
        assertFalse(panoramaAodActiveOf(""))
        assertFalse(panoramaAodActiveOf("aod.unknown"))
        assertFalse(panoramaAodActiveOf(AodStyleCatalog.ID_SAIL))
    }

    /** 设置页门槛用草稿样式（预览面板），不能拿已存配置判 —— 否则用户刚切全景仍被拦。 */
    @Test
    fun settingsGateUsesDraftStyleWhenInPreviewPanel() {
        val src = body(settingsUi(), "effectiveStyleId")
        assertTrue("草稿优先", src.contains("styleOverride ?: settings.aodStyleId"))
        assertTrue("门槛读它", body(settingsUi(), "gateBlockReason").contains("effectiveStyleId()"))
    }

    // ---------------------------------------------------------------- 影响面

    /** 受影响面穷举：只有 requiresPanoramaAod 的三款在 hook 旧口径下被拦。 */
    @Test
    fun onlyPanoramaRequiringEffectsWereAffected() {
        val affected = EffectCatalog.all.filter { it.implemented && it.requiresPanoramaAod }.map { it.id }
        assertEquals(
            listOf(
                EffectCatalog.LOCK_SP_LIQUID,
                EffectCatalog.LOCK_SP_LENTICULAR,
                EffectCatalog.LOCK_SP_FLASH_CARD,
            ).sorted(),
            affected.sorted(),
        )
    }

    /** 阳性对照：旧口径（全 false）与健康口径（全景+陀螺仪）对这三款结论必须不同。 */
    @Test
    fun positiveControlOldAllFalseBlockedThoseThree() {
        val healthy = EffectCapabilities(hasGyro = true, panoramaAodActive = true, photoSceneAvailable = false)
        for (id in listOf(
            EffectCatalog.LOCK_SP_LIQUID,
            EffectCatalog.LOCK_SP_LENTICULAR,
            EffectCatalog.LOCK_SP_FLASH_CARD,
        )) {
            val s = EffectSettings.defaults()
            s.allowAodDynamic = true
            s.lockSpecialEffect = id
            val old = EffectResolver.resolve(s, EffectCapabilities.NONE).lockSpecial
            val new = EffectResolver.resolve(s, healthy).lockSpecial
            assertEquals("id=$id 旧口径被全景门槛拦", EffectBlockReason.NEEDS_PANORAMA_AOD, old.reason)
            assertEquals("id=$id 新口径放行", EffectBlockReason.NONE, new.reason)
            assertEquals("id=$id 旧口径回退无效果", EffectCatalog.LOCK_SP_NONE, old.effectiveId)
            assertEquals("id=$id 新口径保留原 id", id, new.effectiveId)
        }
    }

    /** 不受影响面：BOKEH 只要 dynamic、纹理五款无任何 requires ⇒ 新旧口径逐位相同。 */
    @Test
    fun bokehAndTexturesUnchangedByCapabilityFix() {
        val healthy = EffectCapabilities(hasGyro = true, panoramaAodActive = true, photoSceneAvailable = false)
        val s = EffectSettings.defaults()
        s.allowAodDynamic = true
        s.lockSpecialEffect = EffectCatalog.LOCK_SP_BOKEH
        assertEquals(
            EffectResolver.resolve(s, EffectCapabilities.NONE).lockSpecial,
            EffectResolver.resolve(s, healthy).lockSpecial,
        )
        for (def in EffectCatalog.of(EffectKind.LOCK_WALLPAPER)) {
            val w = EffectSettings.defaults()
            w.lockWallpaperEffect = def.id
            assertEquals(
                "wp=${def.id} 不受能力影响",
                EffectResolver.resolve(w, EffectCapabilities.NONE).lockWallpaper,
                EffectResolver.resolve(w, healthy).lockWallpaper,
            )
        }
    }

    /** DEPTH_3D 本就未实现 ⇒ 两侧都拦，能力补齐不改它的结论。 */
    @Test
    fun depth3dStillBlockedByUnimplementedNotCapability() {
        val s = EffectSettings.defaults()
        s.lockSpecialEffect = EffectCatalog.LOCK_SP_DEPTH_3D
        val all = EffectCapabilities(hasGyro = true, panoramaAodActive = true, photoSceneAvailable = true)
        assertEquals(
            EffectBlockReason.UNIMPLEMENTED,
            EffectResolver.resolve(s, all).lockSpecial.reason,
        )
    }
}
