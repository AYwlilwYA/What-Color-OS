package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ContentWindow
import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneTimeline
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 内容层裁进特效窗口契约：圆窗/方窗几何与两侧 shader 同口径，窗口外不出现内容。
 *
 * 需求 requirements:769 第2项（镜片圆窗）、:770 第3项（照片方窗）。
 */
class ContentWindowContractTest {

    private val preview =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()
    private val lensRenderer =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodLensRenderer.kt").readText()
    private val photoRenderer =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/PhotoSceneRenderer.kt").readText()

    private val w = 734.5f
    private val h = 1591.5f
    private val dp = 2.625f

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

    private fun lensWin(scale: Float, cx: Float, cy: Float): ContentWindow.Window {
        val pose = SailLensMotion.Pose()
        pose.scale = scale
        pose.centerX = cx
        pose.centerY = cy
        val win = ContentWindow.Window()
        ContentWindow.lens(pose, w, h, win)
        return win
    }

    private fun photoWin(scale: Float, x: Float, y: Float, angle: Float = 0f): ContentWindow.Window {
        val v = FloatArray(8)
        v[PhotoStyle.SLOT_SCALE] = scale
        v[PhotoStyle.SLOT_X] = x
        v[PhotoStyle.SLOT_Y] = y
        v[PhotoStyle.SLOT_ROTATION] = angle
        val win = ContentWindow.Window()
        ContentWindow.photo(v, w, h, win)
        return win
    }

    /** ★圆窗半径必须是 scale×屏宽：shader 在 y 乘 aspect 的空间比距离 ⇒ 屏幕空间正圆。 */
    @Test
    fun lensRadiusIsScaleTimesWidth() {
        val win = lensWin(SailLensMotion.RADIUS, 0.5f, 0.435f)
        assertEquals(ContentWindow.Shape.CIRCLE, win.shape)
        assertEquals(SailLensMotion.RADIUS * w, win.radius, 1e-3f)
        assertEquals(0.5f * w, win.centerX, 1e-3f)
        assertEquals(0.435f * h, win.centerY, 1e-3f)
        assertTrue("半径必须按宽不是按短边高", abs(win.radius - SailLensMotion.RADIUS * h) > 1f)
    }

    /** ★与 shader 判定逐点对账：dist>uLensScale 返回黑，contains 必须同结论。 */
    @Test
    fun circleContainsMatchesShaderDistance() {
        val scale = 0.73f
        val cx = 0.5f
        val cy = 0.4675f
        val win = lensWin(scale, cx, cy)
        val aspect = h / w
        var checked = 0
        for (ix in 0..10) {
            for (iy in 0..10) {
                val px = w * ix / 10f
                val py = h * iy / 10f
                val dx = px / w - cx
                val dy = (py / h - cy) * aspect
                val shaderInside = dx * dx + dy * dy <= scale * scale
                assertEquals(
                    "点($px,$py) 与 shader 判定不一致",
                    shaderInside, ContentWindow.contains(win, px, py, w, h),
                )
                checked++
            }
        }
        assertEquals(121, checked)
    }

    /** ★方窗几何必须与 PhotoSceneRenderer 的 uCenter/uSide 逐字同口径。 */
    @Test
    fun squareGeometryMatchesRendererUniforms() {
        val win = photoWin(0.986f, 0.5f, 0.435f)
        assertEquals(ContentWindow.Shape.SQUARE, win.shape)
        assertEquals("side=min(w,h)*SLOT_SCALE", minOf(w, h) * 0.986f, win.side, 1e-3f)
        assertEquals("uCenter.x=w*(SLOT_X+0.5)", w * (0.5f + 0.5f), win.centerX, 1e-3f)
        assertEquals("uCenter.y=h*(0.5-SLOT_Y)", h * (0.5f - 0.435f), win.centerY, 1e-3f)
        assertTrue("渲染器确实这么算 uCenter", photoRenderer.contains("width * (values[PhotoStyle.SLOT_X] + 0.5f)"))
        assertTrue("渲染器确实这么算 uCenter.y", photoRenderer.contains("height * (0.5f - values[PhotoStyle.SLOT_Y])"))
        assertTrue("渲染器确实这么算 side", photoRenderer.contains("min(width, height) * values[PhotoStyle.SLOT_SCALE]"))
    }

    /** 方窗判定与 shader 的 local∈[0,1] 逐点对账（无旋转）。 */
    @Test
    fun squareContainsMatchesShaderLocal() {
        val scale = 1.6f
        val sx = 0.2f
        val sy = 0.18f
        val win = photoWin(scale, sx, sy)
        val side = minOf(w, h) * scale
        val ucx = w * (sx + 0.5f)
        val ucy = h * (0.5f - sy)
        for (ix in 0..12) {
            for (iy in 0..12) {
                val px = w * ix / 12f
                val py = h * iy / 12f
                val lx = (px - ucx) / side + 0.5f
                val ly = (py - ucy) / side + 0.5f
                val shaderInside = lx in 0f..1f && ly in 0f..1f
                assertEquals(
                    "点($px,$py) 与 shader 判定不一致",
                    shaderInside, ContentWindow.contains(win, px, py, w, h),
                )
            }
        }
    }

    /** 旋转方窗：反旋后判定，与 shader 的 spin(coord-center,-angle) 同向。 */
    @Test
    fun rotatedSquareUsesInverseRotation() {
        val angle = 30f
        val win = photoWin(1.2f, 0f, 0f, angle)
        assertEquals(angle, win.angleDeg, 1e-4f)
        val half = win.side * 0.5f
        val rad = Math.toRadians(angle.toDouble())
        val c = kotlin.math.cos(rad).toFloat()
        val s = kotlin.math.sin(rad).toFloat()
        // 窗口本地角点 (half,half) 正旋到屏幕，必须判为窗内
        val px = win.centerX + c * half - s * half
        val py = win.centerY + s * half + c * half
        assertTrue("正旋后的角点必须在窗内", ContentWindow.contains(win, px, py, w, h))
        // 同距离但不旋转的点应在窗外（证明旋转真参与判定）
        val nx = win.centerX + half
        val ny = win.centerY + half
        assertFalse("未旋转的对角点必须落在窗外", ContentWindow.contains(win, nx, ny, w, h))
    }

    /** ★核心事实：退场期内容确有大量落在窗外，裁切不是空操作。 */
    @Test
    fun exitPhaseReallyHasContentOutsideWindow() {
        val pts = listOf(
            Triple("天气太阳", 24 * dp + 9 * dp, h * 0.375f),
            Triple("天气城市", w - 24 * dp, h * 0.375f),
            Triple("通知卡左", 16 * dp, h * 0.415f),
            Triple("通知卡右", w - 16 * dp, h * 0.415f),
            Triple("手势条", w / 2f, h * 0.965f),
        )
        val pose = SailLensMotion.Pose()
        val win = ContentWindow.Window()
        var samplesWithOutside = 0
        var visibleSamples = 0
        val fade = SailLensMotion.Fade()
        for (k in 0..40) {
            val p = k / 40f
            SailLensMotion.fade(TransitionEdge.LOCK_TO_AOD, p, fade)
            if (fade.contentGate <= 0.01f) continue
            visibleSamples++
            SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, p, 0.5f, 0.435f, 1.24f, pose)
            ContentWindow.lens(pose, w, h, win)
            if (pts.any { !ContentWindow.contains(win, it.second, it.third, w, h) }) samplesWithOutside++
        }
        assertEquals("退场可见期采样点数", 18, visibleSamples)
        assertEquals(
            "播放态退场有窗外内容的采样点数（手算真值）",
            10, samplesWithOutside,
        )
    }

    /** ★拖动态（direct 线性）窗外点更少：曲线不同缺口分布就不同，判据必须分态。 */
    @Test
    fun scrubPhaseHasFewerOutsideSamplesThanPlay() {
        val pts = listOf(
            24 * dp + 9 * dp to h * 0.375f,
            w - 24 * dp to h * 0.375f,
            16 * dp to h * 0.415f,
            w - 16 * dp to h * 0.415f,
            w / 2f to h * 0.965f,
        )
        val pose = SailLensMotion.Pose()
        val win = ContentWindow.Window()
        val fade = SailLensMotion.Fade()
        var scrubOutside = 0
        var visible = 0
        for (k in 0..40) {
            val p = k / 40f
            SailLensMotion.fade(TransitionEdge.LOCK_TO_AOD, p, fade)
            if (fade.contentGate <= 0.01f) continue
            visible++
            SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, p, 0.5f, 0.435f, 1.24f, pose, direct = true)
            ContentWindow.lens(pose, w, h, win)
            if (pts.any { !ContentWindow.contains(win, it.first, it.second, w, h) }) scrubOutside++
        }
        assertEquals(18, visible)
        assertEquals("拖动态手算真值 9（比播放态 10 少一个）", 9, scrubOutside)
        assertTrue("拖动态缺口确实更小", scrubOutside < 10)
    }

    /** ★阳性对照：入场期几乎无窗外内容 ⇒ 单取入场中点会误判「裁切没必要」。 */
    @Test
    fun positiveControlEnterPhaseLooksCleanAtMidpoint() {
        val pose = SailLensMotion.Pose()
        SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, 0.5f, 0.5f, 0.435f, 1.24f, pose, direct = true)
        val win = ContentWindow.Window()
        ContentWindow.lens(pose, w, h, win)
        val pts = listOf(
            24 * dp + 9 * dp to h * 0.375f,
            w - 24 * dp to h * 0.375f,
            16 * dp to h * 0.415f,
            w - 16 * dp to h * 0.415f,
        )
        assertTrue(
            "入场 t=0.5 四点全在圆内 ⇒ 单点采样会漏判缺口",
            pts.all { ContentWindow.contains(win, it.first, it.second, w, h) },
        )
        assertTrue("此时圆半径已远大于息屏", pose.scale > SailLensMotion.RADIUS * 3)
    }

    /** 照片类退场同样有窗外内容（方窗）。 */
    @Test
    fun photoExitAlsoHasContentOutside() {
        val style = com.coloros16.liquidglass.engine.PhotoStyleMotion.byId(2)!!
        val values = com.coloros16.liquidglass.engine.PhotoStyleMotion.Values()
        val win = ContentWindow.Window()
        val fade = SailLensMotion.Fade()
        var outside = 0
        var visible = 0
        for (k in 0..40) {
            val p = k / 40f
            SailLensMotion.fade(TransitionEdge.LOCK_TO_AOD, p, fade)
            if (fade.contentGate <= 0.01f) continue
            visible++
            com.coloros16.liquidglass.engine.PhotoStyleMotion.sample(
                style, TransitionEdge.LOCK_TO_AOD, p, h / w, 0.5f, 0.435f, values,
            )
            ContentWindow.photo(values.v, w, h, win)
            val pts = listOf(
                24 * dp + 9 * dp to h * 0.375f,
                16 * dp to h * 0.415f,
                w / 2f to h * 0.965f,
            )
            if (pts.any { !ContentWindow.contains(win, it.first, it.second, w, h) }) outside++
        }
        assertEquals("照片退场可见期采样点数", 18, visible)
        assertTrue("照片退场必须有窗外内容（实测 $outside/18）", outside >= 4)
        assertTrue("手势条是最早越界的点，故窗外采样不少于四分之一", outside >= visible / 4)
    }

    /** NONE 不裁：非 SCENE 样式保持原行为（推移接缝不受影响）。 */
    @Test
    fun noneShapeDoesNotClip() {
        val win = ContentWindow.Window()
        assertEquals(ContentWindow.Shape.NONE, win.shape)
        for (px in listOf(0f, w / 2, w)) {
            for (py in listOf(0f, h / 2, h)) {
                assertTrue("NONE 必须一律不裁", ContentWindow.contains(win, px, py, w, h))
            }
        }
        assertTrue("NONE 与屏幕恒相交", ContentWindow.intersectsScreen(win, w, h))
        val b = body(preview, "clipContentWindow")
        assertTrue("NONE 分支只 save 不 clip", b.contains("ContentWindow.Shape.NONE -> Unit"))
    }

    /** 零尺寸/退化窗口一律不画，避免 clip 出空路径把整层吞掉又无日志。 */
    @Test
    fun degenerateWindowIsNotDrawable() {
        val zero = ContentWindow.Window()
        ContentWindow.lens(SailLensMotion.Pose().also { it.scale = 0f }, w, h, zero)
        assertFalse("零半径圆不与屏幕相交", ContentWindow.intersectsScreen(zero, w, h))
        assertFalse("零半径圆不含任何点", ContentWindow.contains(zero, w / 2, h / 2, w, h))
        val tiny = photoWin(0.0001f, 0f, 0f)
        assertEquals("side<=1 视为无窗口", ContentWindow.Shape.NONE, tiny.shape)
        val empty = ContentWindow.Window()
        ContentWindow.lens(SailLensMotion.Pose(), 0f, 0f, empty)
        assertEquals("零屏尺寸不建窗", ContentWindow.Shape.NONE, empty.shape)
    }

    /** 圆完全移出屏幕时判不相交（用于整层跳过）。 */
    @Test
    fun offscreenCircleDoesNotIntersect() {
        val far = lensWin(0.1f, 3f, 3f)
        assertFalse("远处小圆不与屏幕相交", ContentWindow.intersectsScreen(far, w, h))
        val near = lensWin(0.1f, 1.02f, 0.5f)
        assertTrue("贴近右缘的圆仍相交", ContentWindow.intersectsScreen(near, w, h))
    }

    /** ★接线：两个 SCENE 分支都必须建窗并裁切，且在 drawLockOverlay 之前。 */
    @Test
    fun bothSceneBranchesClipBeforeDrawing() {
        val sail = body(preview, "drawSailScene")
        assertTrue("镜片必须建圆窗", sail.contains("ContentWindow.lens(lensPose, w, h, contentWindow)"))
        val photo = body(preview, "drawPhotoScene")
        assertTrue("照片必须建方窗", photo.contains("ContentWindow.photo(photoMotion.v, w, h, contentWindow)"))
        for (b in listOf(sail to "drawSailScene", photo to "drawPhotoScene")) {
            val clipAt = b.first.indexOf("clipContentWindow(canvas, w, h, contentWindow)")
            val drawAt = b.first.indexOf("drawLockOverlay(canvas, w, h, 1f)")
            assertTrue("${b.second} 必须有裁切", clipAt >= 0)
            assertTrue("${b.second} 裁切必须在画内容之前（clip=$clipAt draw=$drawAt）", clipAt < drawAt)
            assertTrue("${b.second} 桌面层也要裁", b.first.indexOf("clipContentWindow", clipAt + 1) > clipAt)
        }
    }

    /**
     * ★有窗口的分支：整层不得再乘 alpha，否则逐元素错峰滑出被整层淡没盖掉。
     *
     * 层内元素各带自身可见度（`drawLockWeather/Card` 内部 saveLayerAlpha），
     * 外层再压一次 lockAlpha 就是两条淡出相乘 ⇒ 内容在滑出前已淡没。
     */
    @Test
    fun windowedBranchesDoNotFadeWholeLayer() {
        for (fn in listOf("drawSailScene", "drawPhotoScene")) {
            val b = body(preview, fn)
            val seg = b.substring(b.indexOf("val lockAlpha"), b.indexOf("drawSceneClock"))
            assertFalse("$fn 锁屏层不得再 saveLayerAlpha", seg.contains("canvas.saveLayerAlpha"))
            assertTrue("$fn 必须有两次纯 save", seg.split("canvas.save()").size - 1 >= 2)
            assertTrue("$fn 仍按 alpha 早退", seg.contains("if (lockAlpha > 0.002f)"))
            assertTrue("$fn 桌面层也早退", seg.contains("if (homeAlpha > 0.002f)"))
        }
    }

    /** ★顺序：clip 的 save 必须在层 save 之内、restore 必须配对反序。 */
    @Test
    fun clipRestoreIsPairedInsideLayer() {
        val b = body(preview, "drawSailScene")
        val seg = b.substring(b.indexOf("val lockAlpha"), b.indexOf("drawSceneClock"))
        val layerAt = seg.indexOf("canvas.save()")
        val clipAt = seg.indexOf("clipContentWindow")
        val restoreClip = seg.indexOf("canvas.restoreToCount(clip)")
        val restoreLayer = seg.indexOf("canvas.restoreToCount(layer)")
        assertTrue("layer 先于 clip", layerAt < clipAt)
        assertTrue("clip 先恢复", restoreClip < restoreLayer)
        assertTrue("clip 恢复在 layer 恢复之前", clipAt < restoreClip)
    }

    /** 阳性对照：只加 clip 不配对 restore 会污染后续时钟层。 */
    @Test
    fun positiveControlUnpairedRestoreLeaksIntoClock() {
        val legacy = """
            val layer = canvas.save()
            val clip = clipContentWindow(...)
            drawLockOverlay(...)
            canvas.restoreToCount(layer)
            drawSceneClock(...)
        """.trimIndent()
        assertFalse("旧写法没有 restore(clip)", legacy.contains("restoreToCount(clip)"))
        assertTrue("时钟在 clip 未恢复后绘制 ⇒ 会被裁", legacy.indexOf("drawSceneClock") > legacy.indexOf("restoreToCount(layer)"))
    }

    /** ★无窗口的手绘与天空分支仍要整层 alpha：没有裁切承担进出场。 */
    @Test
    fun unwindowedBranchesKeepLayerAlpha() {
        val hp = body(preview, "drawHandPaintScene")
        assertTrue("手绘仍整层压 alpha", hp.contains("canvas.saveLayerAlpha(0f, 0f, w, h, (lockAlpha * 255f).toInt())"))
        val world = body(preview, "drawSceneWorld")
        assertTrue("天空分支仍整层压 alpha", world.contains("canvas.saveLayerAlpha(0f, 0f, w, h, (layers.lockAlpha * 255f).toInt())"))
    }

    /** 裁切必须配诊断日志，否则「内容不见了」分不清是裁掉还是没画。 */
    @Test
    fun clipHasDiagnosticLog() {
        assertTrue("必须有日志函数", preview.contains("private fun logContentWindow("))
        assertTrue("镜片分支必须调", body(preview, "drawSailScene").contains("logContentWindow(\"lens\""))
        assertTrue("照片分支必须调", body(preview, "drawPhotoScene").contains("logContentWindow(\"photo\""))
        val b = body(preview, "logContentWindow")
        assertTrue("必须节流", b.contains("now - contentWindowLogAt < 800L"))
        assertTrue("全透明不打", b.contains("if (lockAlpha <= 0.002f && homeAlpha <= 0.002f) return"))
        assertTrue("必须打形状", b.contains("shape=\${contentWindow.shape}"))
        assertTrue("必须打尺寸", b.contains("size="))
    }

    /** ★不得动快照：快照是目标态整屏画面，裁了会让揭开混出黑边。 */
    @Test
    fun revealSnapshotMustNotBeClipped() {
        val b = body(preview, "captureRevealTarget")
        assertFalse("快照不得裁切", b.contains("clipContentWindow"))
        assertFalse("快照不得建窗", b.contains("ContentWindow.lens") || b.contains("ContentWindow.photo"))
        assertTrue("快照仍画整屏锁屏层", b.contains("drawLockOverlay(off, w, h, 1f, 0f, frozen = true)"))
        // 冻结是必须的：锁屏层逐元素姿态读的是当前转场进度（layers.lockAlpha/edge/t），
        // 而快照按目标态缓存（键里没有 edge/t）⇒ 不冻结就把捕获那一刻的姿态整条边沿用下去。
        assertFalse(
            "快照不得画带转场进度的锁屏层",
            b.contains("drawLockOverlay(off, w, h, 1f, 0f)\n") ||
                b.contains("drawLockOverlay(off, w, h, 1f, 0f) "),
        )
    }

    /** ★手绘与非 SCENE 不受本件影响（需求只点名镜片与照片两类）。 */
    @Test
    fun handPaintAndNonSceneUntouched() {
        val hp = body(preview, "drawHandPaintScene")
        assertFalse("手绘不建窗", hp.contains("ContentWindow.lens") || hp.contains("ContentWindow.photo"))
        assertFalse("手绘不裁切", hp.contains("clipContentWindow"))
        assertTrue("手绘仍按 contentGate 走", hp.contains("layers.lockAlpha * contentGate"))
    }

    /** 静止息屏态圆窗很小，但此时内容层 alpha=0 ⇒ 裁切不影响息屏观感。 */
    @Test
    fun staticAodHasNoContentToClip() {
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(SceneState.AOD, l)
        assertEquals("息屏态锁屏层恒 0", 0f, l.lockAlpha, 1e-6f)
        assertEquals("息屏态桌面层恒 0", 0f, l.homeAlpha, 1e-6f)
        val win = lensWin(SailLensMotion.RADIUS, 0.5f, 0.435f)
        assertTrue("息屏圆窗只占屏宽 44%", abs(2 * win.radius / w - 0.44f) < 0.01f)
    }

    /** 时间轴取样与逐边求值必须给出同一窗口（拖动与播放同源）。 */
    @Test
    fun scrubAndPlayGiveSameWindow() {
        val u = SceneTimeline.anchor(SceneState.AOD) + 0.3f
        val edge = SceneTimeline.edgeAt(u)
        val lt = SceneTimeline.localT(u)
        val a = SailLensMotion.Pose()
        val b = SailLensMotion.Pose()
        SailLensMotion.sampleTimeline(u, 0.5f, 0.435f, 1.24f, a)
        SailLensMotion.sample(edge, lt, 0.5f, 0.435f, 1.24f, b, direct = true)
        val wa = ContentWindow.Window()
        val wb = ContentWindow.Window()
        ContentWindow.lens(a, w, h, wa)
        ContentWindow.lens(b, w, h, wb)
        assertEquals(a.scale, b.scale, 1e-4f)
        assertEquals(wa.radius, wb.radius, 1e-3f)
        assertEquals(wa.centerX, wb.centerX, 1e-3f)
        assertEquals(wa.centerY, wb.centerY, 1e-3f)
    }
}