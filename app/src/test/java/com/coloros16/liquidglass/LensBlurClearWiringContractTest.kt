package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 锁屏↔桌面轻模糊转清晰接 hook 契约：两侧同曲线同顺序，跳过时可分辨。
 */
class LensBlurClearWiringContractTest {

    private fun world(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
    ).readText()

    private fun renderer(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodLensRenderer.kt",
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

    /** 只有锁屏↔桌面两条边参与，其余四边恒 0。 */
    @Test
    fun onlyLockHomeEdgesParticipate() {
        for (e in TransitionEdge.values()) {
            val involved = e == TransitionEdge.LOCK_TO_HOME || e == TransitionEdge.HOME_TO_LOCK
            var any = false
            for (i in 0..10) {
                if (SailLensMotion.blurClearAlpha(e, i / 10f) > 0.002f) any = true
            }
            assertEquals("边 $e 参与性", involved, any)
        }
    }

    /** 两端真值：解锁前段满模糊收到清晰，反向后段从清晰升回模糊。 */
    @Test
    fun endpointsMatchTruth() {
        assertEquals(1f, SailLensMotion.blurClearAlpha(TransitionEdge.LOCK_TO_HOME, 0f), 1e-4f)
        assertEquals(0f, SailLensMotion.blurClearAlpha(TransitionEdge.LOCK_TO_HOME, 0.6f), 1e-4f)
        assertEquals(0f, SailLensMotion.blurClearAlpha(TransitionEdge.HOME_TO_LOCK, 0.4f), 1e-4f)
        assertEquals(1f, SailLensMotion.blurClearAlpha(TransitionEdge.HOME_TO_LOCK, 1f), 1e-4f)
    }

    /** 单调性：解锁只降、反向只升，中途不得回弹。 */
    @Test
    fun monotonicBothDirections() {
        var last = 2f
        for (i in 0..40) {
            val a = SailLensMotion.blurClearAlpha(TransitionEdge.LOCK_TO_HOME, i / 40f)
            assertTrue("解锁段不得回弹 i=$i", a <= last + 1e-4f)
            last = a
        }
        last = -1f
        for (i in 0..40) {
            val a = SailLensMotion.blurClearAlpha(TransitionEdge.HOME_TO_LOCK, i / 40f)
            assertTrue("反向段不得回落 i=$i", a >= last - 1e-4f)
            last = a
        }
    }

    /** 静止态不参与（edge 为空一律 0）。 */
    @Test
    fun staticStateHasNoBlurClear() {
        assertEquals(0f, SailLensMotion.blurClearAlpha(null, 0f), 1e-6f)
        assertEquals(0f, SailLensMotion.blurClearAlpha(null, 0.5f), 1e-6f)
        assertEquals(0f, SailLensMotion.blurClearAlpha(null, 1f), 1e-6f)
    }

    /** ★hook 必须调这条曲线（修前全仓只有引擎定义与预览调用）。 */
    @Test
    fun hookConsumesCurve() {
        val b = body(world(), "drawBlurClear")
        assertTrue("必须走同一份曲线", b.contains("SailLensMotion.blurClearAlpha(edge, t)"))
        assertTrue("必须调渲染器件", b.contains("r.drawSoftBackdrop(canvas, width, height, a, wallpaperScale)"))
    }

    /** ★顺序：底图之后、压暗之前，两者被同等压暗。 */
    @Test
    fun drawnAfterBackdropBeforeDim() {
        val b = body(world(), "drawLens")
        val backdrop = b.indexOf("drawBackdrop(canvas, width, height, bmp, wallpaperScale)")
        val blur = b.indexOf("drawBlurClear(canvas, width, height, r, edge, t, wallpaperScale)")
        val dim = b.indexOf("dimPaint.color = Color.argb(")
        assertTrue("底图调用必须在", backdrop >= 0)
        assertTrue("轻模糊调用必须在", blur >= 0)
        assertTrue("压暗必须在", dim >= 0)
        assertTrue("轻模糊必须在底图之后", backdrop < blur)
        assertTrue("轻模糊必须在压暗之前", blur < dim)
    }

    /** 阳性对照：写反顺序（压暗后才画模糊）能骗过存在性检查、骗不过索引检查。 */
    @Test
    fun positiveControlWrongOrderPassesExistenceNotIndex() {
        val wrong = """
            drawBackdrop(canvas, width, height, bmp, wallpaperScale)
            dimPaint.color = Color.argb(0, 2, 4, 10)
            drawBlurClear(canvas, width, height, r, edge, t, wallpaperScale)
        """.trimIndent()
        assertTrue("存在性检查会被骗过", wrong.contains("drawBlurClear(") && wrong.contains("drawBackdrop("))
        assertFalse(
            "索引检查必须抓到",
            wrong.indexOf("drawBlurClear(") < wrong.indexOf("dimPaint.color = Color.argb("),
        )
    }

    /** 预览侧同一顺序（两侧口径一致，不制造反向不对称）。 */
    @Test
    fun previewUsesSameOrder() {
        val b = body(preview(), "drawSailScene")
        val backdrop = b.indexOf("drawEffectBackdrop(")
        val blur = b.indexOf("drawBlurClearLayer(")
        val dim = b.indexOf("fill.color = alphaColor(")
        assertTrue(backdrop in 0 until blur)
        assertTrue(blur in 0 until dim)
        assertTrue(body(preview(), "drawBlurClearLayer").contains("SailLensMotion.blurClearAlpha(effEdge, effT)"))
    }

    /** 缩放取壁纸真值，两侧同源（不得各自硬编）。 */
    @Test
    fun zoomComesFromWallpaperScale() {
        assertTrue(
            body(world(), "drawBlurClear")
                .contains("r.drawSoftBackdrop(canvas, width, height, a, wallpaperScale)"),
        )
        // 预览侧统一走 wallpaperZoom()：静止/边内仍取引擎分层，落位补间期取补间值
        assertTrue(
            body(preview(), "drawBlurClearLayer")
                .contains("lensRenderer.drawSoftBackdrop(canvas, w, h, a, wallpaperZoom())"),
        )
    }

    /** 三条诊断：不参与 / 底图未就绪 / 正常画，三者必须可分辨。 */
    @Test
    fun threeDiagnosticsAreDistinguishable() {
        val b = body(world(), "drawBlurClear")
        assertTrue("不参与要有日志", b.contains("world-blurclear-off"))
        assertTrue("底图未就绪要有日志", b.contains("world-blurclear-nosoft"))
        assertTrue("正常画要有日志", b.contains("\"world-blurclear\""))
        assertTrue("正常日志带进度与强度", b.contains("t=") && b.contains("a=") && b.contains("zoom="))
    }

    /** 未就绪判据走渲染器公开查询，不靠 drawSoftBackdrop 静默 return。 */
    @Test
    fun readinessIsQueriedNotSilentlySkipped() {
        assertTrue(renderer().contains("fun hasSoftBackdrop(): Boolean = lightSoft != null"))
        assertTrue(body(world(), "drawBlurClear").contains("if (!r.hasSoftBackdrop())"))
    }

    /** 阳性对照：静默 return 无法区分「不参与」与「底图缺失」。 */
    @Test
    fun positiveControlSilentReturnCannotTellReason() {
        val silent = "val bitmap = lightSoft ?: return"
        assertTrue("渲染器内部确实是静默 return", renderer().contains(silent))
        assertFalse("它不带任何原因字样", silent.contains("off") || silent.contains("nosoft"))
    }

    /** 预模糊图确实在 setSource 里产出（不是永远 null 的死器件）。 */
    @Test
    fun lightSoftIsActuallyBuilt() {
        val b = body(renderer(), "setSource")
        assertTrue("必须产出预模糊图", b.contains("lightSoft = runCatching { AodBitmap.blur(built, 4, 1) }"))
        assertTrue("换源必须清旧图", b.contains("lightSoft = null"))
    }

    /** 共享渲染器只新增查询函数，不改既有绘制行为（零回归）。 */
    @Test
    fun rendererChangeIsAdditiveOnly() {
        val b = body(renderer(), "drawSoftBackdrop")
        assertTrue("原静默守卫保留", b.contains("val bitmap = lightSoft ?: return"))
        assertTrue("原阈值保留", b.contains("if (a <= 0.002f || width <= 0f || height <= 0f) return"))
        assertTrue("原折算保留", b.contains("val scale = max(width / bw, height / bh) * zoom.coerceAtLeast(1e-3f)"))
    }
}