package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.EffectOverlayPolicy
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 壁纸效果预览接线契约：构建门与显示门分离、桌面也覆盖、选中即预热。 */
class WallpaperEffectPreviewGateContractTest {

    private fun preview(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private fun body(text: String, fn: String): String {
        val start = Regex("""^\s*(override |internal |private )*fun $fn\b""", RegexOption.MULTILINE)
            .find(text)?.range?.first ?: error("取不到 $fn")
        var depth = 0
        var i = text.indexOf('{', start)
        val from = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(from, i + 1)
                }
            }
            i++
        }
        error("$fn 括号不闭合")
    }

    @Test
    fun buildGateIgnoresScene() {
        assertTrue(EffectOverlayPolicy.needsBuild(EffectCatalog.LOCK_WP_BLUR, null))
        assertTrue(EffectOverlayPolicy.needsBuild(null, EffectCatalog.LOCK_SP_BOKEH))
        assertFalse(EffectOverlayPolicy.needsBuild(EffectCatalog.LOCK_WP_NONE, EffectCatalog.LOCK_SP_NONE))
        assertFalse(EffectOverlayPolicy.needsBuild(null, null))
    }

    @Test
    fun homeAndLockAreBothVisible() {
        assertEquals(1f, EffectOverlayPolicy.visibilityOf(SceneState.LOCK), 1e-6f)
        assertEquals(1f, EffectOverlayPolicy.visibilityOf(SceneState.HOME), 1e-6f)
        assertEquals(0f, EffectOverlayPolicy.visibilityOf(SceneState.AOD), 1e-6f)
    }

    @Test
    fun unlockEdgesStayFullyVisible() {
        for (edge in listOf(TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK)) {
            for (i in 0..10) {
                assertEquals(
                    1f,
                    EffectOverlayPolicy.visibilityAt(SceneState.LOCK, edge, i / 10f),
                    1e-6f,
                )
            }
        }
    }

    @Test
    fun aodEdgesRampMonotonically() {
        var prevIn = -1f
        var prevOut = 2f
        for (i in 0..10) {
            val p = i / 10f
            val into = EffectOverlayPolicy.visibilityAt(SceneState.AOD, TransitionEdge.AOD_TO_LOCK, p)
            val outOf = EffectOverlayPolicy.visibilityAt(SceneState.LOCK, TransitionEdge.LOCK_TO_AOD, p)
            assertTrue("淡入必须单调不减", into >= prevIn - 1e-6f)
            assertTrue("淡出必须单调不增", outOf <= prevOut + 1e-6f)
            prevIn = into
            prevOut = outOf
        }
        assertEquals(0f, EffectOverlayPolicy.visibilityAt(SceneState.AOD, TransitionEdge.AOD_TO_LOCK, 0f), 1e-6f)
        assertEquals(1f, EffectOverlayPolicy.visibilityAt(SceneState.AOD, TransitionEdge.AOD_TO_LOCK, 1f), 1e-6f)
    }

    @Test
    fun visibilityClampsOutOfRangeProgress() {
        assertEquals(0f, EffectOverlayPolicy.visibilityAt(SceneState.AOD, TransitionEdge.AOD_TO_LOCK, -3f), 1e-6f)
        assertEquals(1f, EffectOverlayPolicy.visibilityAt(SceneState.AOD, TransitionEdge.AOD_TO_LOCK, 9f), 1e-6f)
    }

    @Test
    fun previewNoLongerGatesEffectOnLockVisible() {
        val text = preview()
        assertFalse("旧的 lockVisible 门必须删净", text.contains("lockVisible"))
    }

    @Test
    fun allFourBackdropSitesGoThroughSharedHelper() {
        val text = preview()
        for (fn in listOf("drawSailScene", "drawPhotoScene", "captureRevealTarget")) {
            assertTrue("$fn 必须走 drawEffectBackdrop", body(text, fn).contains("drawEffectBackdrop("))
        }
        assertTrue(
            "drawWallpaperBackground 必须用跨三态可见度",
            body(text, "drawWallpaperBackground").contains("EffectOverlayPolicy.visibilityAt("),
        )
    }

    @Test
    fun sharedHelperBuildsUnconditionallyAndGatesOnlyDrawing() {
        val fn = body(preview(), "drawEffectBackdrop")
        val requestAt = fn.indexOf("requestEffectLayer(")
        val visibilityAt = fn.indexOf("EffectOverlayPolicy.visibilityAt(")
        assertTrue("必须先无条件请求构建", requestAt >= 0)
        assertTrue("可见度只求值一次", visibilityAt >= 0)
        assertTrue("构建必须发生在可见度判断之前", requestAt < visibilityAt)
        assertTrue("只有画的时候才判可见度", fn.contains("EffectOverlayPolicy.drawable(visibility)"))
    }

    @Test
    fun effectParamSettersWarmImmediately() {
        val text = preview()
        for (prop in listOf("lockWallpaperEffect", "lockSpecialEffect", "lockWallpaperIntensity")) {
            val block = Regex("""var $prop[\s\S]{0,400}?invalidate\(\)""").find(text)?.value
                ?: error("取不到 $prop setter")
            assertTrue("$prop 变更必须立刻预热", block.contains("warmEffectLayer()"))
        }
        assertTrue("换热壁纸必须预热", body(text, "setWallpaper").contains("warmEffectLayer()"))
    }

    @Test
    fun warmGuardUsesSharedBuildGate() {
        val fn = body(preview(), "warmEffectLayer")
        assertTrue(fn.contains("EffectOverlayPolicy.needsBuild("))
        assertTrue(fn.contains("requestEffectLayer("))
    }

    @Test
    fun revealCacheKeyIncludesEffectIdentity() {
        val fn = body(preview(), "captureRevealTarget")
        assertTrue("揭开目标层缓存键必须含效果 id", fn.contains("lockWallpaperEffect"))
        assertTrue(fn.contains("lockSpecialEffect"))
        assertTrue(fn.contains("effectLayerKey"))
    }

    /** 阳性对照：旧口径在 HOME 静止恒 0，与新口径必须不同。 */
    @Test
    fun positiveControlHomeWasInvisibleUnderOldLockAlphaRule() {
        val oldLockAlphaAtHome = 0f
        val newVisibility = EffectOverlayPolicy.visibilityOf(SceneState.HOME)
        assertTrue("HOME 静止新旧口径必须不同", kotlin.math.abs(oldLockAlphaAtHome - newVisibility) > 0.5f)
    }

    /** 阳性对照：旧的「只在 to==LOCK 时构建」会漏掉 AOD/HOME 选中。 */
    @Test
    fun positiveControlOldBuildGateMissedNonLockScenes() {
        val oldGate: (SceneState, TransitionEdge?) -> Boolean = { scene, edge ->
            scene == SceneState.LOCK || edge?.let { it.name.endsWith("_TO_LOCK") } == true
        }
        assertFalse("旧门在 AOD 静止不构建", oldGate(SceneState.AOD, null))
        assertFalse("旧门在 HOME 静止不构建", oldGate(SceneState.HOME, null))
        assertTrue("新门与态无关", EffectOverlayPolicy.needsBuild(EffectCatalog.LOCK_WP_BLUR, null))
    }
}