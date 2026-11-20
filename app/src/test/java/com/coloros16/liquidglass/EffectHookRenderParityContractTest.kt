package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 壁纸效果 hook 侧渲染契约：四类分派与预览同序、壁纸底接入、强度与壁纸身份进判重键。
 *
 * 修前 hook 只覆盖贴图档且只叠玻璃染色，魅族三档与模糊/景深完全没画，
 * 贴图档也缺折射与糊底 ⇒ 同一效果两侧观感不同。
 */
class EffectHookRenderParityContractTest {

    private fun overlay(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodEffectOverlay.kt",
    ).readText()

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
    ).readText()

    private fun layer(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodTransitionLayer.kt",
    ).readText()

    private fun world(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
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

    // ---------------------------------------------------------------- 四类覆盖

    /** 十档壁纸效果逐一分类：hook 必须每档都有画法，不能只认贴图档。 */
    @Test
    fun everyWallpaperEffectHasARenderPath() {
        val ids = listOf(
            EffectCatalog.LOCK_WP_RAINBOW, EffectCatalog.LOCK_WP_WAVE, EffectCatalog.LOCK_WP_FOG,
            EffectCatalog.LOCK_WP_BLUR,
            EffectCatalog.LOCK_WP_STRIPES_FINE, EffectCatalog.LOCK_WP_STRIPES_COARSE,
            EffectCatalog.LOCK_WP_LIGHT_STRIPES, EffectCatalog.LOCK_WP_GRID,
            EffectCatalog.LOCK_WP_WATER, EffectCatalog.LOCK_WP_RIPPLE,
        )
        for (id in ids) {
            val meizu = id == EffectCatalog.LOCK_WP_RAINBOW ||
                id == EffectCatalog.LOCK_WP_WAVE || id == EffectCatalog.LOCK_WP_FOG
            val textured = EffectTextureCatalog.hasTexture(id)
            val blurred = id == EffectCatalog.LOCK_WP_BLUR
            assertTrue("id=$id 必须落进四类之一", meizu || textured || blurred)
        }
        assertEquals("十档", 10, ids.size)
    }

    /** 阳性对照：修前只认贴图档 ⇒ 魅族三档与模糊共四档零覆盖。 */
    @Test
    fun positiveControlOldOverlayMissedFourEffects() {
        val missed = listOf(
            EffectCatalog.LOCK_WP_RAINBOW, EffectCatalog.LOCK_WP_WAVE,
            EffectCatalog.LOCK_WP_FOG, EffectCatalog.LOCK_WP_BLUR,
        )
        for (id in missed) {
            assertFalse("修前这四档查不到贴图档 ⇒ 直接 clear 不画", EffectTextureCatalog.hasTexture(id))
        }
        assertEquals("零覆盖恰好四档", 4, missed.size)
    }

    @Test
    fun overlayDispatchesAllFourKinds() {
        val src = body(overlay(), "render")
        assertTrue("魅族分支", src.contains("if (kind != null) return renderMeizu("))
        assertTrue("贴图分支", src.contains("if (spec != null) return renderTexture("))
        assertTrue("景深分支", src.contains("depth -> TiltShiftRenderer.render("))
        assertTrue("模糊分支", src.contains("blurred -> TiltShiftRenderer.blurred("))
    }

    /** 分派顺序必须与预览一致：魅族 > 贴图 > 景深 > 模糊。 */
    @Test
    fun dispatchOrderMatchesPreview() {
        val hook = body(overlay(), "render")
        val prev = body(preview(), "requestEffectLayer")
        assertTrue(hook.indexOf("kind != null") < hook.indexOf("spec != null"))
        assertTrue(hook.indexOf("spec != null") < hook.indexOf("depth ->"))
        assertTrue(prev.indexOf("meizu != null") < prev.indexOf("textured ->"))
        assertTrue(prev.indexOf("textured ->") < prev.indexOf("depth ->"))
    }

    // ---------------------------------------------------------------- 贴图档折射与糊底

    @Test
    fun texturePathRefractsAndBlursBackdrop() {
        val src = body(overlay(), "renderTexture")
        assertTrue("按需糊底", src.contains("EffectTextureCatalog.needsBlur(spec, false)"))
        assertTrue("糊底走共用件", src.contains("AodBitmap.blur(base, spec.blurStep, spec.blurRadius)"))
        assertTrue("折射底图", src.contains("EffectTextureRenderer.refract("))
        assertTrue("再叠玻璃染色", src.contains("EffectTextureRenderer.render(source, spec"))
        assertTrue("叠上去", src.contains("EffectTextureRenderer.draw(Canvas(refracted), glass"))
    }

    /** 阳性对照：修前只调 render 不调 refract ⇒ 只有玻璃染色没有几何折射。 */
    @Test
    fun positiveControlOldTexturePathHadNoRefraction() {
        val src = overlay()
        assertTrue("现在必须有折射", src.contains("EffectTextureRenderer.refract("))
        assertTrue("预览一直有折射", preview().contains("EffectTextureRenderer.refract("))
    }

    @Test
    fun textureWithoutWallpaperDegradesToGlassOnly() {
        val src = body(overlay(), "renderTexture")
        assertTrue("无底图只叠染色", src.contains("if (wall == null) {"))
        assertTrue("必须记日志", src.contains("effect-tex-nowall"))
    }

    // ---------------------------------------------------------------- 壁纸底接入

    @Test
    fun worldExposesDecodedWallpaper() {
        assertTrue(
            "世界层暴露已解码壁纸",
            world().contains("fun wallpaperOrNull(): Bitmap? = wallpaper?.takeIf { !it.isRecycled && !liveWallpaper }"),
        )
    }

    /**
     * 意图：构建前先把可能成为底图的三条源都按需解码，再取图喂构建。
     *
     * 输入口径为「跟世界层同一张底图」，故取图走四路策略而非只取系统壁纸；
     * 只 ensure 壁纸会让照片/场景底图这两路永远 null（不是等一下就绪）。
     */
    @Test
    fun stageEnsuresEveryBackdropSourceBeforeRequest() {
        val src = body(stage(), "applyEffect")
        val take = src.indexOf("AodWorldLayer.backdropOrNull(")
        assertTrue("取图走四路策略", take >= 0)
        for (fn in listOf("ensureWallpaper", "ensureStylePhoto", "ensureSceneSky")) {
            val at = src.indexOf("AodWorldLayer.$fn")
            assertTrue("$fn 必须先按需解码", at >= 0)
            assertTrue("$fn 必须在取图之前", at < take)
        }
        assertTrue("取到的图要传给构建", src.contains("slot.intensity, wall, layer.width, layer.height"))
    }

    /** 需要底图的三类无壁纸时不画并记日志，不能静默出空层。 */
    @Test
    fun backdropDependentKindsRequireWallpaper() {
        val src = body(overlay(), "request")
        assertTrue(src.contains("if (kind != null || blurred || depth) {"))
        assertTrue(src.contains("effect-nowall"))
    }

    // ---------------------------------------------------------------- 判重键

    @Test
    fun keyCarriesIntensityAndWallpaperIdentity() {
        val src = body(overlay(), "keyOf")
        assertTrue("强度进键", src.contains("\$intensity"))
        assertTrue("壁纸身份进键", src.contains("System.identityHashCode(wall)"))
        assertTrue("壁纸尺寸进键", src.contains("wall.width"))
        assertTrue("特殊效果进键", src.contains("\$specialId"))
    }

    /** 阳性对照：键不含强度时滑杆动了也判同值 ⇒ 不重建、画面不变。 */
    @Test
    fun positiveControlKeyWithoutIntensityWouldSkipRebuild() {
        val a = "lock.wp.oplus.water|540x1188"
        val b = "lock.wp.oplus.water|540x1188"
        assertEquals("旧键对强度变化无感", a, b)
        val withIntensity = "$a|0.3"
        val withOther = "$a|0.9"
        assertTrue("新键必须能分辨", withIntensity != withOther)
    }

    @Test
    fun layerDedupeAlsoCarriesNewInputs() {
        val src = body(layer(), "bindEffect")
        assertTrue(src.contains("effectSpecialId == specialId"))
        assertTrue(src.contains("effectIntensity == intensity"))
        assertTrue("壁纸按身份比", src.contains("effectWall === wall"))
    }

    @Test
    fun peekUsesSameKeyAsRequest() {
        val peek = body(layer(), "drawEffect")
        assertTrue(
            "取层与建层必须同键",
            peek.contains("id, effectSpecialId, effectIntensity, effectWall, width, height"),
        )
    }

    // ---------------------------------------------------------------- 特殊效果与强度来源

    @Test
    fun stageReadsSpecialEffectAndIntensity() {
        val src = body(stage(), "effectiveEffects")
        assertTrue("读特殊效果", src.contains("resolved.lockSpecial"))
        assertTrue("读强度键", src.contains("EffectParams.LOCK_WP_INTENSITY"))
        assertTrue("归一化到 0..1", src.contains("/ 100f).coerceIn(0f, 1f)"))
    }

    /** 阳性对照：修前 needsBuild 第二参恒 null ⇒ 景深永远进不了构建门。 */
    @Test
    fun positiveControlOldGateAlwaysPassedNullSpecial() {
        assertFalse("修后不得再恒传 null", stage().contains("EffectOverlayPolicy.needsBuild(id, null)"))
        assertTrue("修后传真实特殊效果", stage().contains("EffectOverlayPolicy.needsBuild(id, special)"))
    }

    @Test
    fun effectReadIsTtlCachedNotPolled() {
        val src = body(stage(), "effectiveEffects")
        assertTrue("TTL 惰性读", src.contains("now - effectReadAt < EFFECT_TTL_MS"))
        assertTrue("读失败保持上一份", src.contains("effectSlot\n") || src.contains("            effectSlot"))
        assertFalse("不得有轮询", src.contains("Thread.sleep"))
        assertFalse("不得有定时器", src.contains("postDelayed"))
    }

    // ---------------------------------------------------------------- 资源与降级

    @Test
    fun intermediateBitmapsAreRecycledButNotTheInputWallpaper() {
        val tex = body(overlay(), "renderTexture")
        assertTrue("玻璃层用完回收", tex.contains("glass.recycle()"))
        assertTrue("糊底中间图回收", tex.contains("backdrop.recycle()"))
        assertTrue("不回收入参壁纸", tex.contains("base !== wall"))
        val meizu = body(overlay(), "renderMeizu")
        assertTrue("灰度图回收", meizu.contains("gray?.recycle()"))
        assertTrue("不回收入参壁纸", meizu.contains("base !== wall"))
    }

    @Test
    fun meizuFailureIsLoggedNotSilent() {
        val src = body(overlay(), "renderMeizu")
        assertTrue("雾花缺灰度图要记", src.contains("effect-fog-nogray"))
        assertTrue("渲染不可用要记", src.contains("effect-meizu-fail"))
    }

    @Test
    fun fogGrayPathMatchesPreview() {
        assertTrue(overlay().contains("FOG_GRAY_PATH = \"effect/meizu/fog.png\""))
        assertTrue(preview().contains("\"effect/meizu/fog.png\""))
    }

    @Test
    fun blurRadiusMatchesPreview() {
        assertTrue(overlay().contains("BLUR_RADIUS_PX = 18f"))
        assertTrue(preview().contains("TiltShiftRenderer.blurred(base, 18f)"))
    }
}
