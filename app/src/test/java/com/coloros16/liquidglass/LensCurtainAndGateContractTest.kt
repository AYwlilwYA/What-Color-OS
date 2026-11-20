package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ContentWindow
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 镜片黑幕只画圆外 + 照片类删内容闸契约。
 *
 * 需求 requirements:770 第2项「镜片黑幕只画在镜片圆外（圆内由镜片自身覆盖）」、
 * :771 第3项「窗口外不再出现内容；删 contentGate」。
 */
class LensCurtainAndGateContractTest {

    private val preview =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private val w = 734.5f
    private val h = 1591.5f
    private val dp = 2.625f

    private fun body(fn: String): String {
        val start = Regex("""^\s*(override |internal |private )*fun $fn\b""", RegexOption.MULTILINE)
            .find(preview)?.range?.first ?: error("取不到 $fn")
        var depth = 0
        var i = preview.indexOf('{', start)
        val from = i
        while (i < preview.length) {
            when (preview[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return preview.substring(from, i + 1)
                }
            }
            i++
        }
        error("$fn 括号不闭合")
    }

    private fun photoBranch(): String = preview.substring(
        preview.indexOf("private fun drawPhotoScene"),
        preview.indexOf("private fun captureRevealTarget"),
    )

    /** ★黑幕必须挖圆孔，不再全屏 fill。 */
    @Test
    fun curtainDrawsRingNotFullScreen() {
        val b = body("drawLensCurtain")
        assertTrue("必须用 EVEN_ODD 挖孔路径", b.contains("canvas.drawPath(curtainPath, fill)"))
        assertTrue("路径含全屏矩形", b.contains("curtainPath.addRect(0f, 0f, w, h"))
        assertTrue("路径含圆", b.contains("curtainPath.addCircle(cx, cy, r"))
        assertTrue("全屏 fill 只保留零半径兜底", b.indexOf("canvas.drawRect(0f, 0f, w, h, fill)") < b.indexOf("curtainPath.reset()"))
        assertTrue("镜片绘制必须调黑幕件", body("drawSailScene").contains("drawLensCurtain(canvas, w, h)"))
    }

    /** ★独立 Path 持有 EVEN_ODD，不得污染共享 circle 的 fillType。 */
    @Test
    fun curtainPathIsSeparateFromSharedCircle() {
        assertTrue(
            "必须独立持有并设 EVEN_ODD",
            preview.contains("private val curtainPath = Path().apply { fillType = Path.FillType.EVEN_ODD }"),
        )
        val b = body("drawLensCurtain")
        assertFalse("不得动共享 circle", b.contains("circle."))
        assertFalse("不得改共享 fillType", b.contains("circle.fillType"))
    }

    /** 阳性对照：若复用共享 circle 改 fillType，遮罩链的 clipPath 会被连带改成挖孔。 */
    @Test
    fun positiveControlSharedPathFillTypeLeaks() {
        val legacy = """
            circle.reset()
            circle.fillType = Path.FillType.EVEN_ODD
            circle.addRect(...); circle.addCircle(...)
            canvas.drawPath(circle, fill)
        """.trimIndent()
        assertTrue("旧写法改了共享 fillType", legacy.contains("circle.fillType"))
        assertFalse("旧写法没有还原", legacy.contains("Path.FillType.WINDING"))
        assertTrue("遮罩链确实复用同一个 circle", preview.contains("circle.addCircle("))
    }

    /** ★圆已盖住四角时整段跳过：圆外零像素，画了也是浪费。 */
    @Test
    fun curtainSkippedWhenCircleCoversScreen() {
        val b = body("drawLensCurtain")
        assertTrue("必须判覆盖", b.contains("ContentWindow.coversScreen(r, cx, cy, w, h)"))
        assertTrue("覆盖时早退", b.contains("logCurtain(\"skip-covered\", r)"))
        val coverAt = b.indexOf("coversScreen")
        val drawAt = b.indexOf("canvas.drawPath")
        assertTrue("覆盖判定必须在绘制之前", coverAt in 0 until drawAt)
    }

    /** 覆盖判据取四角全在圆内，不是「半径大于半屏」这种近似。 */
    @Test
    fun coversScreenChecksAllFourCorners() {
        val cx = w / 2f
        val cy = h / 2f
        val need = kotlin.math.sqrt(cx * cx + cy * cy)
        assertFalse("半径刚到半屏宽不算覆盖", ContentWindow.coversScreen(w / 2f, cx, cy, w, h))
        assertFalse("半径到半屏高也不算", ContentWindow.coversScreen(h / 2f, cx, cy, w, h))
        assertFalse("略小于对角半长不算", ContentWindow.coversScreen(need - 1f, cx, cy, w, h))
        assertTrue("达到对角半长才算", ContentWindow.coversScreen(need + 1f, cx, cy, w, h))
        assertFalse("零半径不算", ContentWindow.coversScreen(0f, cx, cy, w, h))
        assertFalse("零屏尺寸不算", ContentWindow.coversScreen(1000f, cx, cy, 0f, 0f))
    }

    /** ★偏心圆：判据是四角距离不是圆心偏移；半径够大时偏心也能覆盖。 */
    @Test
    fun offCenterCoverageDependsOnFarthestCorner() {
        val cx = w * 0.1f
        val cy = h * 0.1f
        // 最远角是右下：sqrt((0.9w)^2+(0.9h)^2) ≈ 1577.5，r=h=1591.5 刚好盖住
        val far = kotlin.math.sqrt((w - cx) * (w - cx) + (h - cy) * (h - cy))
        assertTrue("r=h 略大于最远角距 ⇒ 偏心也覆盖", ContentWindow.coversScreen(h, cx, cy, w, h))
        assertTrue("h 确实大于最远角距", h > far)
        assertFalse("略小于最远角距就不覆盖", ContentWindow.coversScreen(far - 1f, cx, cy, w, h))
        assertTrue("刚过最远角距即覆盖", ContentWindow.coversScreen(far + 1f, cx, cy, w, h))
        assertFalse("小半径偏心必不覆盖", ContentWindow.coversScreen(w * 0.5f, cx, cy, w, h))
    }

    /**
     * ★定量：黑幕参与的 36 帧里只有末 10 帧圆才真正盖住四角。
     *
     * 「圆面积占屏 100%」≠「四角全在圆内」——面积比早早就到 1，
     * 但圆是圆的、屏幕是矩形，四角要更晚才进圆。这两个判据不能混。
     * 位移轨 950ms 短于整组 1000ms，故同一 p 下位移比整组归一化时走得更远。
     */
    @Test
    fun curtainCoversOnlyTheLastStretch() {
        val pose = SailLensMotion.Pose()
        val fade = SailLensMotion.Fade()
        var covered = 0
        var total = 0
        var firstCoveredAt = -1f
        for (k in 0..40) {
            val p = k / 40f
            SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, p, 0.5f, 0.435f, 1.24f, pose)
            SailLensMotion.fade(TransitionEdge.AOD_TO_LOCK, p, fade)
            if (fade.aodAlpha <= 0.002f) continue
            total++
            val r = pose.scale * w
            if (ContentWindow.coversScreen(r, pose.centerX * w, pose.centerY * h, w, h)) {
                covered++
                if (firstCoveredAt < 0f) firstCoveredAt = p
            }
        }
        assertEquals("黑幕参与帧数（手算真值）", 36, total)
        assertEquals("其中圆盖住四角可跳过（手算真值）", 10, covered)
        assertEquals("首次覆盖发生在 p=0.650", 0.650f, firstCoveredAt, 1e-3f)
        assertTrue("覆盖只占末段不是多数", covered < total / 2)
    }

    /** ★阳性对照：面积比判据在 p=0.225 就说「满了」，比四角判据早 0.425。 */
    @Test
    fun positiveControlAreaRatioOverstatesCoverage() {
        val pose = SailLensMotion.Pose()
        var areaFullAt = -1f
        var cornerFullAt = -1f
        for (k in 0..40) {
            val p = k / 40f
            SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, p, 0.5f, 0.435f, 1.24f, pose)
            val r = pose.scale * w
            val areaRatio = Math.PI.toFloat() * r * r / (w * h)
            if (areaFullAt < 0f && areaRatio >= 1f) areaFullAt = p
            if (cornerFullAt < 0f && ContentWindow.coversScreen(r, pose.centerX * w, pose.centerY * h, w, h)) {
                cornerFullAt = p
            }
        }
        assertEquals("面积比在 p=0.225 就到 1（手算 r=622.2 面积比 1.0404）", 0.225f, areaFullAt, 1e-3f)
        assertEquals("四角判据要到 p=0.65", 0.650f, cornerFullAt, 1e-3f)
        assertTrue("面积比早了 0.425 ⇒ 拿它当跳过判据会漏画黑幕", cornerFullAt - areaFullAt > 0.40f)
    }

    /** 阳性对照：息屏静止圆只占屏宽 44%，那时黑幕必须真画。 */
    @Test
    fun positiveControlStaticAodNeedsCurtain() {
        val pose = SailLensMotion.Pose()
        SailLensMotion.sampleStatic(com.coloros16.liquidglass.engine.SceneState.AOD, 0.5f, 0.435f, 1.24f, pose)
        val r = pose.scale * w
        assertFalse("息屏圆远未覆盖全屏", ContentWindow.coversScreen(r, pose.centerX * w, pose.centerY * h, w, h))
        assertTrue("息屏圆只占屏宽 44%", kotlin.math.abs(2 * r / w - 0.44f) < 0.01f)
    }

    /** ★照片类必须已删 contentGate（需求 §771 明写）。 */
    @Test
    fun photoBranchGateRemoved() {
        val b = photoBranch()
        assertTrue("照片类 gate 必须置 1", b.contains("contentGate = 1f"))
        assertFalse("照片类不得再读交叠真值", b.contains("sailFade"))
        assertFalse("照片类不得再调 fade", b.contains("SailLensMotion.fade("))
        assertTrue("必须有裁切承担进出场", b.contains("ContentWindow.photo(photoMotion.v, w, h, contentWindow)"))
    }

    /** ★删 gate 的前置条件是裁切：单独删会有大量采样点把内容露在窗外。 */
    @Test
    fun removingGateWithoutClipWouldLeakContent() {
        val pts = listOf(
            24 * dp + 9 * dp to h * 0.375f,
            w - 24 * dp to h * 0.375f,
            16 * dp to h * 0.415f,
            w - 16 * dp to h * 0.415f,
            w / 2f to h * 0.965f,
        )
        val layers = SystemSceneMotion.Layers()
        val pose = SailLensMotion.Pose()
        val win = ContentWindow.Window()
        val fade = SailLensMotion.Fade()
        var wouldLeak = 0
        for (k in 0..40) {
            val p = k / 40f
            SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, p, layers)
            if (layers.lockAlpha <= 0.01f) continue
            SailLensMotion.fade(TransitionEdge.AOD_TO_LOCK, p, fade)
            SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, p, 0.5f, 0.435f, 1.24f, pose)
            ContentWindow.lens(pose, w, h, win)
            // 无裁切时：gate 之外的点只要 lockAlpha>0 就会被画出来
            if (fade.contentGate <= 0.01f && pts.any { !ContentWindow.contains(win, it.first, it.second, w, h) }) {
                wouldLeak++
            }
        }
        assertTrue("无裁切时会露内容的采样点 $wouldLeak（必须 >0，证明裁切是前置条件）", wouldLeak > 5)
    }

    /** 阳性对照：有裁切时那些点根本不会被画，故删 gate 安全。 */
    @Test
    fun positiveControlClipMakesGateRemovalSafe() {
        val layers = SystemSceneMotion.Layers()
        val pose = SailLensMotion.Pose()
        val win = ContentWindow.Window()
        var clippedAway = 0
        val px = w / 2f
        val py = h * 0.965f
        for (k in 0..40) {
            val p = k / 40f
            SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, p, layers)
            if (layers.lockAlpha <= 0.01f) continue
            SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, p, 0.5f, 0.435f, 1.24f, pose)
            ContentWindow.lens(pose, w, h, win)
            if (!ContentWindow.contains(win, px, py, w, h)) clippedAway++
        }
        assertTrue("手势条在 $clippedAway 个采样点被裁掉 ⇒ 删 gate 不会让它露出", clippedAway > 5)
    }

    /** 手绘无窗口仍保留 gate；镜片有圆窗裁切故不压 gate。 */
    @Test
    fun lensAndHandPaintKeepGate() {
        val sail = body("drawSailScene")
        assertTrue("镜片有圆窗故已删 gate", sail.contains("contentGate = 1f"))
        assertTrue("镜片必须标记有裁切", sail.contains("windowClipped = true"))
        val hp = body("drawHandPaintScene")
        assertTrue("手绘仍按 gate 走", hp.contains("layers.lockAlpha * contentGate"))
        assertFalse("手绘不得自置 1", hp.contains("contentGate = 1f"))
        assertFalse("手绘无窗口不标", hp.contains("windowClipped"))
    }

    /** ★两侧不是同口径而是各自正确：hook 无裁切能力故仍用 gate。 */
    @Test
    fun hookKeepsGateBecauseNoClipCapability() {
        val stage = File("src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt").readText()
        assertTrue("hook 仍按镜片款压内容", stage.contains("if (!spec.lens) return 1f"))
        assertTrue("hook 仍读交叠真值", stage.contains("worldFade.contentGate"))
        assertFalse("hook 侧不得出现预览的裁切件", stage.contains("ContentWindow"))
    }

    /** 黑幕必须配诊断，否则「圆外没黑」分不清是跳过还是没画。 */
    @Test
    fun curtainHasDiagnosticLog() {
        assertTrue("必须有日志函数", preview.contains("private fun logCurtain("))
        val b = body("logCurtain")
        assertTrue("必须节流", b.contains("now - curtainLogAt < 800L"))
        assertTrue("必须打模式", b.contains("mode=\$mode"))
        assertTrue("必须打半径", b.contains("r="))
        assertTrue("必须打透明度", b.contains("a=\${\"%.3f\".format(aodLayerAlpha)}"))
        val c = body("drawLensCurtain")
        assertTrue("跳过要记", c.contains("logCurtain(\"skip-covered\""))
        assertTrue("真画要记", c.contains("logCurtain(\"ring\""))
    }

    /** 零半径兜底必须全屏黑（息屏起始帧不能透出底图）。 */
    @Test
    fun zeroRadiusFallsBackToFullBlack() {
        val b = body("drawLensCurtain")
        val zeroAt = b.indexOf("if (r <= 0f)")
        val fullAt = b.indexOf("canvas.drawRect(0f, 0f, w, h, fill)")
        assertTrue("必须有零半径分支", zeroAt >= 0)
        assertTrue("零半径走全屏黑", fullAt > zeroAt)
        assertTrue("零半径分支必须 return", b.substring(zeroAt, b.indexOf("val cx")).contains("return"))
    }

    /** 黑幕半径口径必须与裁切圆窗一致，都是 scale×屏宽。 */
    @Test
    fun curtainRadiusMatchesContentWindow() {
        val b = body("drawLensCurtain")
        assertTrue("黑幕半径 scale×w", b.contains("val r = lensPose.scale * w"))
        val pose = SailLensMotion.Pose()
        pose.scale = 0.5f
        val win = ContentWindow.Window()
        ContentWindow.lens(pose, w, h, win)
        assertEquals("与裁切圆窗同半径", pose.scale * w, win.radius, 1e-3f)
    }
}