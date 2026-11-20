package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.EffectOverlayPolicy
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 效果层必须插在「壁纸底之后、压暗与内容之前」，两侧同序。
 *
 * 修前 hook 在整个世界层画完（含压暗与镜片）之后才叠效果层
 * ⇒ 锁屏静止态效果层把压暗完全盖掉（差 0.30），转场期镜片被效果层压掉。
 */
class EffectLayerOrderContractTest {

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

    // ---------------------------------------------------------------- 缺陷定量

    /** 锁屏静止态：效果层不透明会把压暗完全盖掉，差整个 dim 值。 */
    @Test
    fun lockStaticEffectWouldEraseAllDim() {
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(SceneState.LOCK, l)
        val dim = l.dim
        val vis = EffectOverlayPolicy.visibilityOf(SceneState.LOCK)
        assertEquals("锁屏效果层满强度", 1f, vis, 1e-6f)
        assertEquals("修前有效压暗归零", 0f, dim * (1f - vis), 1e-6f)
        assertEquals("差整个 dim", dim, dim - dim * (1f - vis), 1e-6f)
        assertEquals("锁屏 dim 真值 0.30", 0.30f, dim, 1e-6f)
    }

    /** 转场中点：效果层半透明仍吃掉一半压暗。 */
    @Test
    fun midTransitionEffectEatsHalfTheDim() {
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, 0.5f, l)
        val vis = EffectOverlayPolicy.visibilityAt(SceneState.LOCK, TransitionEdge.AOD_TO_LOCK, 0.5f)
        assertEquals("中点效果层半强度", 0.5f, vis, 1e-6f)
        val wrong = l.dim * (1f - vis)
        assertEquals("被吃掉一半", l.dim / 2f, wrong, 1e-5f)
        assertEquals("差值等于一半 dim", l.dim / 2f, l.dim - wrong, 1e-5f)
    }

    /** 息屏态效果层可见度 0 ⇒ 该态看不出缺陷，只有锁屏/桌面与含锁屏的边暴露。 */
    @Test
    fun aodStaticHidesTheDefect() {
        assertEquals(0f, EffectOverlayPolicy.visibilityOf(SceneState.AOD), 1e-6f)
        assertEquals(1f, EffectOverlayPolicy.visibilityOf(SceneState.LOCK), 1e-6f)
        assertEquals(1f, EffectOverlayPolicy.visibilityOf(SceneState.HOME), 1e-6f)
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(SceneState.AOD, l)
        val vis = EffectOverlayPolicy.visibilityOf(SceneState.AOD)
        assertEquals("息屏态两种层序等价", l.dim, l.dim * (1f - vis), 1e-6f)
    }

    /** 桌面态 dim 最小但效果层仍满强度 ⇒ 压暗同样被全吃。 */
    @Test
    fun homeStaticAlsoLosesAllDim() {
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(SceneState.HOME, l)
        assertEquals(0.10f, l.dim, 1e-6f)
        val vis = EffectOverlayPolicy.visibilityOf(SceneState.HOME)
        assertEquals(0f, l.dim * (1f - vis), 1e-6f)
    }

    // ---------------------------------------------------------------- 世界层回调接线

    /** 三个画底的世界层分支都要有 onBackdrop 回调口。 */
    @Test
    fun allThreeBackdropBranchesExposeCallback() {
        val w = world()
        for (fn in listOf("drawLens", "drawPhoto", "drawHandPaint", "drawWallpaperBase")) {
            assertTrue("$fn 缺 onBackdrop 参数", w.contains("fun $fn(") && body(w, fn).isNotEmpty())
        }
        assertTrue("drawLens 签名带回调", sig(w, "drawLens").contains("onBackdrop: () -> Unit = NOOP"))
        assertTrue("drawPhoto 签名带回调", sig(w, "drawPhoto").contains("onBackdrop: () -> Unit = NOOP"))
        assertTrue("drawHandPaint 签名带回调", sig(w, "drawHandPaint").contains("onBackdrop: () -> Unit = NOOP"))
        assertTrue("drawWallpaperBase 签名带回调", sig(w, "drawWallpaperBase").contains("onBackdrop: () -> Unit = NOOP"))
    }

    private fun sig(text: String, name: String): String {
        val at = text.indexOf("fun $name(")
        if (at < 0) return ""
        val end = text.indexOf("): Boolean", at)
        return if (end < 0) "" else text.substring(at, end)
    }

    /** 回调必须紧跟 drawBackdrop，在压暗与内容之前。 */
    @Test
    fun callbackFiresRightAfterBackdropBeforeDim() {
        val w = world()
        val lens = body(w, "drawLens")
        val backdropAt = lens.indexOf("drawBackdrop(")
        val cbAt = lens.indexOf("onBackdrop()")
        val dimAt = lens.indexOf("canvas.drawRect(0f, 0f, width, height, dimPaint)")
        assertTrue("镜片：底 → 回调 → 压暗", backdropAt in 0 until cbAt && cbAt < dimAt)

        val photo = body(w, "drawPhoto")
        val pb = photo.indexOf("drawBackdrop(")
        val pc = photo.indexOf("onBackdrop()")
        val pd = photo.indexOf("r.draw(")
        assertTrue("照片：底 → 回调 → 内容", pb in 0 until pc && pc < pd)

        val base = body(w, "drawWallpaperBase")
        val bb = base.indexOf("drawBackdrop(")
        val bc = base.indexOf("onBackdrop()")
        val bd = base.indexOf("canvas.drawRect(0f, 0f, width, height, dimPaint)")
        assertTrue("共用底：底 → 回调 → 压暗", bb in 0 until bc && bc < bd)
    }

    /** 手绘把回调透传给共用底件，不自建第二份。 */
    @Test
    fun handPaintForwardsCallbackToSharedBase() {
        val h = body(world(), "drawHandPaint")
        assertTrue(
            "透传给共用件（含底图源开关）",
            h.contains("drawWallpaperBase(\n            canvas, width, height, dim, wallpaperScale, useSystemWallpaper, onBackdrop,\n        )"),
        )
        assertFalse("手绘不得自己画底", h.contains("drawBackdrop("))
    }

    /** 底未就绪时不触发回调（没有底就不该叠效果）。 */
    @Test
    fun callbackSkippedWhenBackdropMissing() {
        val b = body(world(), "drawWallpaperBase")
        val ifAt = b.indexOf("if (bmp != null) {")
        val cbAt = b.indexOf("onBackdrop()")
        val elseAt = b.indexOf("} else logThrottled(")
        assertTrue("回调在 if 分支内", ifAt in 0 until cbAt && cbAt < elseAt)
    }

    // ---------------------------------------------------------------- 层调用点接线

    @Test
    fun allThreeBranchesPassTheSameCallback() {
        val d = body(layer(), "drawWorld")
        val count = Regex("onBackdrop = \\{ paintEffectInsideWorld\\(canvas, w, h\\) \\}").findAll(d).count()
        assertEquals("三个分支各一处", 3, count)
    }

    /** 世界层内画过就不再在外面补画，避免一帧画两次。 */
    @Test
    fun noDoubleDrawWhenWorldAlreadyPaintedIt() {
        val d = body(layer(), "onDraw")
        assertTrue("按标志跳过", d.contains("if (!effectDrawnInWorld) drawEffect(canvas, w, h)"))
        assertTrue("每帧复位", d.contains("effectDrawnInWorld = false"))
        val paint = body(layer(), "paintEffectInsideWorld")
        assertTrue("置位", paint.contains("effectDrawnInWorld = true"))
        assertTrue("实际绘制且标明序位是世界层内", paint.contains("drawEffect(canvas, w, h, \"world\")"))
    }

    /** 阳性对照：修前 onDraw 无条件在世界层之后叠效果。 */
    @Test
    fun positiveControlOldOrderPaintedEffectAfterWholeWorld() {
        val d = body(layer(), "onDraw")
        val worldAt = d.indexOf("val world = drawWorld(")
        val effectAt = d.indexOf("drawEffect(canvas, w, h)")
        assertTrue("世界层仍先调", worldAt in 0 until effectAt)
        assertFalse(
            "不得再无条件补画",
            d.contains("val world = drawWorld(canvas, w, h)\n        drawEffect(canvas, w, h)"),
        )
    }

    /** 无世界层（NONE 分支）仍走外层补画，不能因为改序把非 SCENE 样式的效果丢了。 */
    @Test
    fun nonSceneStylesStillGetEffectViaOuterPath() {
        val d = body(layer(), "drawWorld")
        assertTrue("无契约直接返回", d.contains("val spec = worldSpec ?: return false"))
        assertTrue("NONE 分支返 false", d.contains("SkyBranchPolicy.Branch.NONE -> false"))
        val od = body(layer(), "onDraw")
        assertTrue("标志初值 false 才会补画", layer().contains("private var effectDrawnInWorld = false"))
        assertTrue("补画路径仍在", od.contains("if (!effectDrawnInWorld) drawEffect("))
    }

    /** 天空分支不画壁纸底（程序化天空），故不接回调，效果仍走外层。 */
    @Test
    fun skyBranchKeepsOuterPathBecauseItHasNoWallpaperBackdrop() {
        val d = body(layer(), "drawWorld")
        assertTrue("天空走 drawSky", d.contains("SkyBranchPolicy.Branch.SKY -> drawSky(canvas, w, h, spec)"))
        val sky = body(layer(), "drawSky")
        assertFalse("天空不接回调", sky.contains("onBackdrop"))
    }

    // ---------------------------------------------------------------- 与预览同序

    /** 预览三个场景分支都是「底+效果 → 压暗 → 内容」，hook 修后同序。 */
    @Test
    fun orderMatchesPreviewInAllThreeSceneBranches() {
        val p = preview()
        for (fn in listOf("drawSailScene", "drawPhotoScene", "drawHandPaintScene")) {
            val b = body(p, fn)
            val eb = b.indexOf("drawEffectBackdrop(")
            val dim = b.indexOf("canvas.drawRect(0f, 0f, w, h, fill)")
            assertTrue("$fn 缺效果底", eb >= 0)
            assertTrue("$fn 效果必须在压暗之前", eb < dim)
        }
    }

    /** 预览的效果底件内部序：底图 → 效果层。 */
    @Test
    fun previewEffectBackdropDrawsBaseThenOverlay() {
        val b = body(preview(), "drawEffectBackdrop")
        val baseAt = b.indexOf("drawBitmapCover(canvas, backdrop")
        val overlayAt = b.indexOf("drawBitmapCover(canvas, layer")
        assertTrue("底在前", baseAt in 0 until overlayAt)
    }

    // ---------------------------------------------------------------- 禁轮询与诊断

    @Test
    fun noPollingIntroduced() {
        for (src in listOf(layer(), world())) {
            assertFalse("不得 sleep", src.contains("Thread.sleep"))
            assertFalse("不得定时器", src.contains("Timer("))
            assertFalse("不得固定间隔调度", src.contains("ScheduledExecutor"))
        }
        val paint = body(layer(), "paintEffectInsideWorld")
        assertFalse("回调内不得自调度", paint.contains("postDelayed"))
    }

    /** 回调是纯绘制，不新建线程也不申请构建（构建仍由 applyEffect 事件驱动）。 */
    @Test
    fun callbackOnlyPaintsNeverRequestsBuild() {
        val paint = body(layer(), "paintEffectInsideWorld")
        assertFalse("不得在绘制回调里请求构建", paint.contains("AodEffectOverlay.request"))
        assertFalse("不得解码壁纸", paint.contains("ensureWallpaper"))
        assertTrue("只调绘制", paint.contains("drawEffect("))
    }

    /** 既有取景诊断仍在（本轮只改插入点，不动取景与诊断）。 */
    @Test
    fun fitDiagnosticsUnchanged() {
        val d = body(layer(), "drawEffect")
        assertTrue("取景仍走共用件", d.contains("BackdropFit.coverRect("))
        assertTrue("诊断仍在", d.contains("logEffectFit("))
        assertTrue("守卫诊断仍在（带序位实参即可）", d.contains("logEffectNoFit("))
        assertTrue("诊断能分辨插入序位", d.contains("logEffectFit(w, h, box, where)"))
    }
}