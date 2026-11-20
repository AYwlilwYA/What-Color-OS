package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ContentWindow
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.to
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 退场滑出而非淡没契约：有窗口裁切的样式退场走大行程，内容在仍可见时就移出窗口。
 *
 * 需求 requirements:770 第2项「退场期内容先滑出、镜片再收拢」+ :765「不能用淡入淡出」。
 */
class ExitSlideNotFadeContractTest {

    private val preview =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private val w = 734.5f
    private val h = 1591.5f
    private val dp = 2.625f
    private val aspect = h / w

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

    private fun sailBranch(): String = preview.substring(
        preview.indexOf("private fun drawSailScene"),
        preview.indexOf("private fun drawLensCurtain"),
    )

    private fun photoBranch(): String = preview.substring(
        preview.indexOf("private fun drawPhotoScene"),
        preview.indexOf("private fun captureRevealTarget"),
    )

    /** ★退场行程必须与入场分离：退场要移出窗口，入场只需轻微暗示。 */
    @Test
    fun exitTravelIsSeparateAndLarger() {
        assertTrue("入场常量仍在", preview.contains("const val LOCK_STAGGER_TRAVEL = 0.06f"))
        assertTrue("圆窗退场常量", preview.contains("const val LOCK_STAGGER_EXIT_TRAVEL = 0.30f"))
        assertTrue("方窗退场常量", preview.contains("const val LOCK_STAGGER_EXIT_TRAVEL_SQUARE = 0.44f"))
        assertTrue("圆窗通知卡退场常量", preview.contains("const val LOCK_STAGGER_EXIT_TRAVEL_CARD = 0.49f"))
        assertTrue("方窗通知卡退场常量", preview.contains("const val LOCK_STAGGER_EXIT_TRAVEL_CARD_SQUARE = 0.58f"))
        assertTrue(
            "元素求值必须走分档入口并带元素序号",
            body("lockElementPose").contains("val travel = lockExitTravel(entering, index)"),
        )
        val b = body("lockExitTravel")
        assertTrue("入场与无窗口走小行程", b.contains("if (entering || !windowClipped) return LOCK_STAGGER_TRAVEL"))
        assertTrue("形状只判一次", b.contains("val square = contentWindow.shape == ContentWindow.Shape.SQUARE"))
        assertTrue("通知卡单独一档", b.contains("LOCK_CARD_INDEX ->"))
        assertTrue("方窗天气档", b.contains("if (square) LOCK_STAGGER_EXIT_TRAVEL_SQUARE else LOCK_STAGGER_EXIT_TRAVEL"))
        assertTrue(
            "方窗通知卡档",
            b.contains("if (square) LOCK_STAGGER_EXIT_TRAVEL_CARD_SQUARE else LOCK_STAGGER_EXIT_TRAVEL_CARD"),
        )
        assertTrue("退场行程必须显著大于入场", 0.30f > 0.06f * 4)
        assertTrue("方窗行程必须大于圆窗", 0.44f > 0.30f)
        assertTrue("同形状下通知卡行程必须大于天气行", 0.49f > 0.30f && 0.58f > 0.44f)
    }

    /** 阳性对照：共用一个常量会让入场位移过大（从下方 477px 冲上来）。 */
    @Test
    fun positiveControlSharedTravelMakesEntryTooLarge() {
        val entryPx = 0.06f * h
        val exitPx = 0.30f * h
        assertEquals("入场 95.5px", 95.49f, entryPx, 0.1f)
        assertEquals("退场 477.4px", 477.45f, exitPx, 0.1f)
        assertTrue("差 5 倍 ⇒ 共用会毁掉入场观感", exitPx / entryPx > 4.9f)
    }

    /** 逐点求值：返回 (首次全裁 p, 那时元素可见度, 那时层 alpha, 那时元素有效不透明度)。 */
    private class ExitSample {
        var firstClipAt = -1f
        var visibleAtClip = -1f
        var layerAtClip = -1f
        var maxShiftWhileVisible = 0f
    }

    private fun sampleExit(travel: Float): ExitSample {
        val gx = 24 * dp + 9 * dp
        val gy = h * 0.375f
        val cx = 16 * dp
        val cy = h * 0.415f
        val layers = SystemSceneMotion.Layers()
        val pose = SailLensMotion.Pose()
        val win = ContentWindow.Window()
        val weather = SystemSceneMotion.ContentPose()
        val card = SystemSceneMotion.ContentPose()
        val out = ExitSample()
        for (k in 0..100) {
            val p = k / 100f
            SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, p, layers)
            SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, p, 0.5f, 0.435f, 1.24f, pose)
            ContentWindow.lens(pose, w, h, win)
            val lp = 1f - layers.lockAlpha
            SystemSceneMotion.contentStagger(lp, 0, false, travel, weather)
            SystemSceneMotion.contentStagger(lp, 1, false, travel, card)
            val wv = 1f - weather.progress
            val cv = 1f - card.progress
            val a = ContentWindow.contains(win, gx, gy + weather.shift * h, w, h)
            val b = ContentWindow.contains(win, cx, cy + card.shift * h, w, h)
            if (a && wv > 0.1f) {
                out.maxShiftWhileVisible = maxOf(out.maxShiftWhileVisible, kotlin.math.abs(weather.shift * h))
            }
            if (!a && !b && out.firstClipAt < 0f) {
                out.firstClipAt = p
                out.visibleAtClip = wv
                out.layerAtClip = layers.lockAlpha
            }
        }
        return out
    }

    /** ★核心定量：travel=0.30 时首次全裁发生在元素仍可见时（不是淡没后才裁）。 */
    @Test
    fun exitClipsWhileContentStillVisible() {
        val s = sampleExit(0.30f)
        assertEquals("首次全裁于 p=0.25（手算真值）", 0.25f, s.firstClipAt, 0.011f)
        assertEquals("那时天气行可见度 0.1350（手算真值）", 0.1350f, s.visibleAtClip, 0.002f)
        assertTrue("那时元素仍可见", s.visibleAtClip > 0.1f)
        assertTrue("可见期已滑出 200px 以上（实测 ${s.maxShiftWhileVisible}）", s.maxShiftWhileVisible > 200f)
    }

    /** ★阳性对照：旧 travel=0.06 时首次全裁发生在元素已淡没之后。 */
    @Test
    fun positiveControlOldTravelClipsOnlyAfterFadeOut() {
        val s = sampleExit(0.06f)
        assertTrue("旧行程首次全裁晚于 p=0.35（实测 ${s.firstClipAt}）", s.firstClipAt > 0.35f)
        assertTrue("那时元素早已淡没（visible=${s.visibleAtClip}）", s.visibleAtClip < 0.002f)
        assertTrue("可见期几乎没位移（实测 ${s.maxShiftWhileVisible}）", s.maxShiftWhileVisible < 100f)
    }

    /**
     * ★★真缺陷定量：光加行程不够，整层再乘 lockAlpha 会把滑出盖成淡没。
     *
     * 实测 p=0.20：元素可见度 0.5119、位移 -233px，但层 alpha 0.5000
     * ⇒ 有效不透明度只剩 0.2560；p=0.25 首次全裁时只剩 0.0428（几乎全黑）。
     * 删掉整层 alpha 后同一时刻就是 0.5119 / 0.1350 —— 才叫「滑出」。
     */
    @Test
    fun layerAlphaWouldCancelTheSlide() {
        val gx = 24 * dp + 9 * dp
        val gy = h * 0.375f
        val layers = SystemSceneMotion.Layers()
        val pose = SailLensMotion.Pose()
        val win = ContentWindow.Window()
        val weather = SystemSceneMotion.ContentPose()
        var withLayerAtClip = -1f
        var withoutLayerAtClip = -1f
        var firstClipAt = -1f
        for (k in 0..100) {
            val p = k / 100f
            SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, p, layers)
            SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, p, 0.5f, 0.435f, 1.24f, pose)
            ContentWindow.lens(pose, w, h, win)
            SystemSceneMotion.contentStagger(1f - layers.lockAlpha, 0, false, 0.30f, weather)
            val vis = 1f - weather.progress
            val inside = ContentWindow.contains(win, gx, gy + weather.shift * h, w, h)
            if (!inside && firstClipAt < 0f) {
                firstClipAt = p
                withLayerAtClip = layers.lockAlpha * vis
                withoutLayerAtClip = vis
            }
        }
        assertEquals("首次全裁 p=0.25", 0.25f, firstClipAt, 0.011f)
        assertEquals("乘层 alpha 后只剩 0.0428", 0.0428f, withLayerAtClip, 0.002f)
        assertEquals("不乘层 alpha 是 0.1350", 0.1350f, withoutLayerAtClip, 0.002f)
        assertTrue("差 3 倍以上 ⇒ 整层淡出确实盖掉了滑出", withoutLayerAtClip / withLayerAtClip > 3f)
    }

    /** 阳性对照：入场期整层 alpha 是必要的（内容随镜片长大渐显），不能一律删。 */
    @Test
    fun entryStillNeedsLayerAlphaButSlideIsVisible() {
        val gx = 24 * dp + 9 * dp
        val gy = h * 0.375f
        val layers = SystemSceneMotion.Layers()
        val pose = SailLensMotion.Pose()
        val win = ContentWindow.Window()
        val weather = SystemSceneMotion.ContentPose()
        var firstInsideAt = -1f
        for (k in 0..20) {
            val p = k / 20f
            SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, p, layers)
            SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, p, 0.5f, 0.435f, 1.24f, pose)
            ContentWindow.lens(pose, w, h, win)
            SystemSceneMotion.contentStagger(layers.lockAlpha, 0, true, 0.06f, weather)
            val inside = ContentWindow.contains(win, gx, gy + weather.shift * h, w, h)
            if (inside && firstInsideAt < 0f) firstInsideAt = p
        }
        assertEquals("入场首次进窗于 p=0.10", 0.10f, firstInsideAt, 1e-3f)
        SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, 0.10f, layers)
        assertEquals("那时层 alpha 仅 0.0712 ⇒ 入场靠渐显不是靠淡没", 0.0712f, layers.lockAlpha, 0.002f)
    }

    /** ★时序方向本来就对：内容先消失、镜片后收完，不需要改时序。 */
    @Test
    fun timingOrderWasAlreadyCorrect() {
        val layers = SystemSceneMotion.Layers()
        val pose = SailLensMotion.Pose()
        var contentGoneAt = -1f
        var lensDoneAt = -1f
        for (k in 0..40) {
            val p = k / 40f
            SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, p, layers)
            SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, p, 0.5f, 0.435f, 1.24f, pose)
            if (contentGoneAt < 0f && layers.lockAlpha <= 0.002f) contentGoneAt = p
            if (lensDoneAt < 0f && kotlin.math.abs(pose.scale - SailLensMotion.RADIUS) < 1e-3f) lensDoneAt = p
        }
        assertEquals("内容归零于 p=0.400（200ms）", 0.400f, contentGoneAt, 1e-3f)
        assertEquals("镜片收完于 p=0.950（475ms）", 0.950f, lensDoneAt, 1e-3f)
        assertTrue("内容先于镜片 ⇒ 时序方向已符合需求", contentGoneAt < lensDoneAt)
        assertTrue("相差 275ms 以上", (lensDoneAt - contentGoneAt) * 500f > 270f)
    }

    /** 阳性对照：若时序反了（镜片先收完），内容会被圆吃掉而不是自己滑出。 */
    @Test
    fun positiveControlReversedOrderWouldEatContent() {
        val layers = SystemSceneMotion.Layers()
        val pose = SailLensMotion.Pose()
        var eatCount = 0
        for (k in 0..40) {
            val p = k / 40f
            SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, p, layers)
            SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, p, 0.5f, 0.435f, 1.24f, pose)
            // 假想镜片瞬间收完：内容还可见但圆已到最小
            if (layers.lockAlpha > 0.1f) {
                val win = ContentWindow.Window()
                val minPose = SailLensMotion.Pose()
                minPose.scale = SailLensMotion.RADIUS
                minPose.centerX = 0.5f
                minPose.centerY = 0.435f
                ContentWindow.lens(minPose, w, h, win)
                if (!ContentWindow.contains(win, 24 * dp + 9 * dp, h * 0.375f, w, h)) eatCount++
            }
        }
        assertTrue("镜片先收完会在 $eatCount 个采样点把可见内容吃掉", eatCount > 10)
    }

    /** ★镜片款也必须删 gate：它有圆窗裁切，与照片类同理。 */
    @Test
    fun lensBranchGateAlsoRemoved() {
        val b = sailBranch()
        assertTrue("镜片 gate 必须置 1", b.contains("contentGate = 1f"))
        assertFalse("镜片不得再赋交叠闸", b.contains("contentGate = sailFade.contentGate"))
        assertTrue("必须标记有窗口裁切", b.contains("windowClipped = true"))
        assertTrue("仍要有圆窗", b.contains("ContentWindow.lens(lensPose, w, h, contentWindow)"))
        assertTrue("sailFade 仍供息屏层与时钟用", b.contains("sailFade.aodAlpha"))
    }

    /** 两个有窗口的分支都要标记，手绘与天空不标。 */
    @Test
    fun onlyWindowedBranchesMarkClipped() {
        assertTrue("镜片标记", sailBranch().contains("windowClipped = true"))
        assertTrue("照片标记", photoBranch().contains("windowClipped = true"))
        val hp = body("drawHandPaintScene")
        assertFalse("手绘无窗口不标", hp.contains("windowClipped"))
        assertTrue("手绘仍按 gate 走", hp.contains("layers.lockAlpha * contentGate"))
        val world = body("drawSceneWorld")
        assertTrue("入口必须复位", world.contains("windowClipped = false"))
    }

    /** ★复位必须在分流之前，否则换样式后残留上一次的 true。 */
    @Test
    fun resetHappensBeforeDispatch() {
        val b = body("drawSceneWorld")
        val resetAt = b.indexOf("windowClipped = false")
        val lensAt = b.indexOf("drawSailScene(")
        val photoAt = b.indexOf("drawPhotoScene(")
        assertTrue("复位必须在", resetAt >= 0)
        assertTrue("复位在镜片分流之前", resetAt < lensAt)
        assertTrue("复位在照片分流之前", resetAt < photoAt)
    }

    /** 阳性对照：不复位则从照片款切到手绘款会误用大行程。 */
    @Test
    fun positiveControlMissingResetLeaksBigTravel() {
        val legacy = """
            if (spec.lens) { drawSailScene(...); return }
            if (spec.photo != null) { drawPhotoScene(...); return }
            if (spec.handPaintStyle > 0) { drawHandPaintScene(...); return }
        """.trimIndent()
        assertFalse("旧写法无复位", legacy.contains("windowClipped = false"))
        assertTrue("手绘分流在照片之后 ⇒ 会继承照片的 true", legacy.indexOf("drawHandPaintScene") > legacy.indexOf("drawPhotoScene"))
    }

    /** 入场行程不受影响：两个有窗口分支的入场仍走 0.06。 */
    @Test
    fun entryTravelUnchangedForWindowedStyles() {
        val pose = SystemSceneMotion.ContentPose()
        SystemSceneMotion.contentStagger(0.5f, 0, true, 0.06f, pose)
        val entryShift = pose.shift
        SystemSceneMotion.contentStagger(0.5f, 0, true, 0.30f, pose)
        assertTrue("入场用大行程会位移过大", pose.shift > entryShift * 4)
        val b = body("lockExitTravel")
        assertTrue("入场必须短路用小行程", b.contains("if (entering || !windowClipped) return LOCK_STAGGER_TRAVEL"))
    }

    /** 静止态仍不位移（防烧屏漂移旁的内容不能飘）。 */
    @Test
    fun staticStateStillSettled() {
        val b = body("lockElementPose")
        assertTrue("静止态早退", b.contains("if (effEdge == null) return 1f to 0f"))
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(SceneState.LOCK, l)
        assertEquals("静止锁屏层满", 1f, l.lockAlpha, 1e-6f)
    }

    /** 诊断必须带裁切标记，否则分不清大行程有没有生效。 */
    @Test
    fun logCarriesClippedFlag() {
        val b = body("logLockStagger")
        assertTrue("必须打 clipped", b.contains("clipped=\$windowClipped"))
        assertTrue("仍打两元素", b.contains("weather=") && b.contains("card="))
    }

    /** 退场方向判定不能被行程改动影响。 */
    @Test
    fun exitDirectionStillCorrect() {
        assertEquals(SceneState.AOD, TransitionEdge.LOCK_TO_AOD.to)
        assertFalse("去息屏不是入场", TransitionEdge.LOCK_TO_AOD.to == SceneState.LOCK)
        assertTrue("来锁屏才是入场", TransitionEdge.AOD_TO_LOCK.to == SceneState.LOCK)
        val pose = SystemSceneMotion.ContentPose()
        SystemSceneMotion.contentStagger(1f, 0, false, 0.30f, pose)
        assertEquals("退场终点位移满程向上", -0.30f, pose.shift, 1e-5f)
        assertEquals("退场终点 progress=1", 1f, pose.progress, 1e-5f)
    }

    /** ★hook 侧不跟改：无裁切能力，大行程会让内容移出屏幕却仍可见。 */
    @Test
    fun hookMustNotAdoptBigTravel() {
        val stage = File("src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt").readText()
        assertFalse("hook 不得出现预览的裁切件", stage.contains("ContentWindow"))
        assertFalse("hook 不得用大行程常量", stage.contains("LOCK_STAGGER_EXIT_TRAVEL"))
        assertTrue("hook 仍按镜片款压内容", stage.contains("if (!spec.lens) return 1f"))
    }
}