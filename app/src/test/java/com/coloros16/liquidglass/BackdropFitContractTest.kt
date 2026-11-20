package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.BackdropFit
import com.coloros16.liquidglass.engine.SystemSceneMotion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * 壁纸底图与效果叠加层必须同框：同一 cover 折算 + 同一 zoom。
 *
 * 修前 hook 效果层平铺 (0,0,w,h)，壁纸底走 cover×zoom ⇒ 锁屏静止态纵向错位 120px、尺度差 10%。
 */
class BackdropFitContractTest {

    private fun layer(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodTransitionLayer.kt",
    ).readText()

    private fun overlay(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodEffectOverlay.kt",
    ).readText()

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
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

    /** 手算基准：与实现无关地重算一遍 cover 框，避免拿实现当期望。 */
    private fun expected(w: Float, h: Float, bw: Int, bh: Int, zoom: Float): FloatArray {
        val s = max(w / bw, h / bh) * zoom
        val dw = bw * s
        val dh = bh * s
        return floatArrayOf((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    }

    private fun assertBox(exp: FloatArray, got: FloatArray?) {
        assertNotNull(got)
        val g = got!!
        assertEquals(4, g.size)
        for (i in 0 until 4) assertEquals("第${i}项", exp[i], g[i], 0.01f)
    }

    // ---------------------------------------------------------------- 几何手算

    /** 同尺寸 zoom=1 恰好铺满视口。 */
    @Test
    fun sameSizeUnitZoomFillsViewport() {
        assertBox(
            floatArrayOf(0f, 0f, 1080f, 2400f),
            BackdropFit.coverRect(1080f, 2400f, 1080, 2400, 1f),
        )
    }

    /** 锁屏静止 zoom=1.1：1080×2400 视口应外扩到 (-54,-120,1134,2520)。 */
    @Test
    fun lockZoomOverflowsByHandComputedAmount() {
        assertBox(
            floatArrayOf(-54f, -120f, 1134f, 2520f),
            BackdropFit.coverRect(1080f, 2400f, 1080, 2400, SystemSceneMotion.WALLPAPER_SCALE_LOCK),
        )
    }

    /** 转场中点 zoom=1.05：外扩减半 (-27,-60,1107,2460)。 */
    @Test
    fun midTransitionZoomIsHalfOverflow() {
        assertBox(
            floatArrayOf(-27f, -60f, 1107f, 2460f),
            BackdropFit.coverRect(1080f, 2400f, 1080, 2400, 1.05f),
        )
    }

    /** zoom<1 时框内缩，两侧留白（1080×2400 zoom=0.9 → 54,120,1026,2280）。 */
    @Test
    fun zoomBelowOneShrinksInsideViewport() {
        assertBox(
            floatArrayOf(54f, 120f, 1026f, 2280f),
            BackdropFit.coverRect(1080f, 2400f, 1080, 2400, 0.9f),
        )
    }

    /** 横屏视口：长边换成宽，外扩方向随之互换 (-80,-36,1680,756)。 */
    @Test
    fun landscapeViewportSwapsOverflowAxis() {
        assertBox(
            floatArrayOf(-80f, -36f, 1680f, 756f),
            BackdropFit.coverRect(1600f, 720f, 1600, 720, 1.1f),
        )
    }

    /** 位图宽高比与视口不同：按长边覆盖，短边溢出（1080×1920 底进 1080×2400）。 */
    @Test
    fun mismatchedAspectCoversLongSide() {
        assertBox(
            floatArrayOf(-202.5f, -120f, 1282.5f, 2520f),
            BackdropFit.coverRect(1080f, 2400f, 1080, 1920, 1.1f),
        )
    }

    /** 降采样后的效果层与全尺寸壁纸底折算出同一个框（长边封顶不改宽高比时）。 */
    @Test
    fun downsampledOverlayLandsInSameBoxAsFullSizeBackdrop() {
        val full = BackdropFit.coverRect(1080f, 2400f, 1080, 2400, 1.1f)!!
        val small = BackdropFit.coverRect(1080f, 2400f, 324, 720, 1.1f)!!
        for (i in 0 until 4) assertEquals("降采样后第${i}项", full[i], small[i], 0.01f)
    }

    // ---------------------------------------------------------------- 守卫

    @Test
    fun nonPositiveViewportOrBitmapReturnsNull() {
        assertNull(BackdropFit.coverRect(0f, 2400f, 1080, 2400, 1.1f))
        assertNull(BackdropFit.coverRect(1080f, 0f, 1080, 2400, 1.1f))
        assertNull(BackdropFit.coverRect(-1f, 2400f, 1080, 2400, 1.1f))
        assertNull(BackdropFit.coverRect(1080f, 2400f, 0, 2400, 1.1f))
        assertNull(BackdropFit.coverRect(1080f, 2400f, 1080, -5, 1.1f))
    }

    /** zoom 非正夹到下限而不是收成一点：1080×2400 视口下框应为 1.08×2.4px。 */
    @Test
    fun nonPositiveZoomIsClampedNotCollapsed() {
        for (z in listOf(0f, -1f, -100f)) {
            val box = BackdropFit.coverRect(1080f, 2400f, 1080, 2400, z)!!
            assertEquals("zoom=$z 宽", 1080f * BackdropFit.MIN_ZOOM, box[2] - box[0], 0.001f)
            assertEquals("zoom=$z 高", 2400f * BackdropFit.MIN_ZOOM, box[3] - box[1], 0.001f)
        }
    }

    /** 中心对齐：任意 zoom 下框中心恒等于视口中心。 */
    @Test
    fun boxIsAlwaysCenteredOnViewport() {
        for (z in listOf(0.5f, 1f, 1.05f, 1.1f, 1.4f)) {
            val box = BackdropFit.coverRect(1080f, 2400f, 1080, 1920, z)!!
            assertEquals("zoom=$z 中心x", 540f, (box[0] + box[2]) / 2f, 0.01f)
            assertEquals("zoom=$z 中心y", 1200f, (box[1] + box[3]) / 2f, 0.01f)
        }
    }

    // ---------------------------------------------------------------- 阳性对照：平铺 vs 同框

    /** 修前平铺 (0,0,w,h)：锁屏态纵向差 120px、尺度差 10%；息屏/桌面态恰好等价。 */
    @Test
    fun positiveControlFlatRectDivergesOnlyWhenZoomNotOne() {
        val w = 1080f
        val h = 2400f
        val flat = floatArrayOf(0f, 0f, w, h)
        val cases = listOf(
            Triple("AOD静止", SystemSceneMotion.WALLPAPER_SCALE_AOD, 0f),
            Triple("LOCK静止", SystemSceneMotion.WALLPAPER_SCALE_LOCK, 120f),
            Triple("HOME静止", SystemSceneMotion.WALLPAPER_SCALE_HOME, 0f),
            Triple("转场中点", 1.05f, 60f),
        )
        for ((name, zoom, drift) in cases) {
            val box = BackdropFit.coverRect(w, h, 1080, 2400, zoom)!!
            assertEquals("$name 纵向错位", drift, abs(box[1] - flat[1]), 0.01f)
            val scaleDiff = abs((box[3] - box[1]) / h - 1f)
            assertEquals("$name 尺度差", abs(zoom - 1f), scaleDiff, 0.0001f)
        }
        assertEquals("四组", 4, cases.size)
    }

    /** 修前后只在 zoom≠1 时有差异：zoom=1 两者逐点相等（不能靠改公式蒙对）。 */
    @Test
    fun unitZoomKeepsOldFlatBehaviourBitForBit() {
        assertArrayEquals(
            floatArrayOf(0f, 0f, 1440f, 3200f),
            BackdropFit.coverRect(1440f, 3200f, 1440, 3200, 1f),
            0f,
        )
    }

    // ---------------------------------------------------------------- 接线：hook 侧

    @Test
    fun effectLayerUsesSharedFitNotFlatRect() {
        val src = body(layer(), "drawEffect")
        assertTrue("走共用折算", src.contains("BackdropFit.coverRect(w, h, bitmap.width, bitmap.height, effectZoom)"))
        assertTrue("按框写 rect", src.contains("rect.set(box[0], box[1], box[2], box[3])"))
        assertTrue("守卫不画", src.contains("if (box == null)"))
        assertTrue("守卫有诊断（带序位实参即可，不钉字面量）", src.contains("logEffectNoFit("))
    }

    /** 阳性对照：修前 draw 内部硬写 (0,0,w,h)，调用方给的框会被覆盖。 */
    @Test
    fun positiveControlOldDrawHardcodedFullViewport() {
        val src = body(overlay(), "draw")
        assertTrue("现在只按传入框画", src.contains("canvas.drawBitmap(bitmap, null, rect, paint)"))
        assertTrue("不得再自算平铺框", !src.contains("rect.set(0f, 0f,"))
    }

    @Test
    fun bindEffectCarriesZoomAndDedupesOnIt() {
        val src = body(layer(), "bindEffect")
        assertTrue("入参含 zoom", src.contains("zoom: Float = 1f"))
        assertTrue("判重比 zoom", src.contains("effectZoom == zoom"))
        assertTrue("写入 zoom", src.contains("effectZoom = zoom"))
    }

    /** 阳性对照：zoom 不进判重键时，锁屏缩放推进不会触发重绘。 */
    @Test
    fun positiveControlZoomChangeMustInvalidate() {
        val src = body(layer(), "bindEffect")
        val guardAt = src.indexOf("effectZoom == zoom")
        val invalidateAt = src.indexOf("invalidate()")
        assertTrue("zoom 必须在早退判据里", guardAt in 0 until invalidateAt)
    }

    @Test
    fun stageFeedsSystemWallpaperScaleOnBothPaths() {
        val src = stage()
        assertTrue(
            "播放期传分层 zoom",
            src.contains("EffectOverlayPolicy.visibilityAt(staticState(layer), edge, params.t),") &&
                src.contains("sceneLayers.wallpaperScale,"),
        )
        assertTrue(
            "静止态传分层 zoom",
            src.contains("applyEffect(layer, EffectOverlayPolicy.visibilityOf(state), sceneLayers.wallpaperScale)"),
        )
        assertTrue("applyEffect 签名收 zoom", body(src, "applyEffect").contains("zoom: Float"))
        assertTrue("传给 bindEffect", body(src, "applyEffect").contains("slot.intensity, wall, zoom"))
    }

    /** 世界层底图与效果层必须走同一个折算件，否则两侧公式会各自漂。 */
    @Test
    fun worldBackdropAndEffectShareTheSameFit() {
        val backdrop = body(world(), "drawBackdrop")
        assertTrue("底图走共用件", backdrop.contains("BackdropFit.coverRect(width, height, bmp.width, bmp.height, zoom)"))
        assertTrue("底图不再自算 cover", !backdrop.contains("maxOf(width / bw, height / bh)"))
        assertTrue("效果层走共用件", body(layer(), "drawEffect").contains("BackdropFit.coverRect("))
    }

    /** 同一 zoom 值喂两条链：世界层与效果层都取 sceneLayers.wallpaperScale。 */
    @Test
    fun bothChainsReadTheSameZoomSource() {
        val src = stage()
        val worldFeed = src.indexOf("wallpaperScale = sceneLayers.wallpaperScale")
        val effectFeed = src.indexOf("applyEffect(layer, EffectOverlayPolicy.visibilityOf(state), sceneLayers.wallpaperScale)")
        assertTrue("世界层喂分层 zoom", worldFeed >= 0)
        assertTrue("效果层喂分层 zoom", effectFeed >= 0)
    }

    // ---------------------------------------------------------------- 接线：预览侧同源

    @Test
    fun previewCoverAlsoGoesThroughSharedFit() {
        val cover = body(preview(), "drawBitmapCover")
        assertTrue("预览 cover 走共用件", cover.contains("BackdropFit.coverRect(w, h, bitmap.width, bitmap.height, zoom)"))
        assertTrue("偏移仍叠加", cover.contains("dst.set(box[0] + dx, box[1] + dy, box[2] + dx, box[3] + dy)"))
        assertTrue("不再自算", !cover.contains("maxOf(w / bw, h / bh)"))
    }

    @Test
    fun previewWallpaperAndEffectUseSameZoom() {
        val src = body(preview(), "drawWallpaperBackground")
        assertTrue("底图走共用件", src.contains("BackdropFit.coverRect(w, h, bitmap.width, bitmap.height, zoom)"))
        assertTrue("效果层走共用件同 zoom", src.contains("BackdropFit.coverRect(w, h, it.width, it.height, zoom)"))
    }

    /** 三处 cover 公式收敛成一处：全仓不得再有第二份 maxOf cover 写法。 */
    @Test
    fun noSecondCoverFormulaRemains() {
        for ((name, text) in listOf("preview" to preview(), "world" to world(), "layer" to layer())) {
            assertTrue("$name 不得残留自算 cover", !text.contains("maxOf(w / bw, h / bh)"))
            assertTrue("$name 不得残留自算 cover", !text.contains("maxOf(width / bw, height / bh)"))
        }
    }

    // ---------------------------------------------------------------- 诊断与纯 JVM

    @Test
    fun fitDiagnosticCarriesZoomAlphaAndRect() {
        val src = body(layer(), "logEffectFit")
        assertTrue("含 zoom", src.contains("zoom="))
        assertTrue("含可见度", src.contains("a="))
        assertTrue("含目标框", src.contains("dst="))
        assertTrue("含视口尺寸", src.contains("view="))
        assertTrue("节流不轮询", src.contains("now - effectFitLoggedAt < FRAME_REVEAL_LOG_MS"))
    }

    @Test
    fun fitEngineIsPureJvm() {
        val src = File("src/main/kotlin/com/coloros16/liquidglass/engine/BackdropFit.kt").readText()
        assertTrue("零 android 依赖", !src.contains("android."))
        assertTrue("只依赖 kotlin.math", src.contains("import kotlin.math.max"))
    }
}