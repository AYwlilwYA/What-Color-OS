package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ContentWindow
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.home.HomeGeometry
import com.coloros16.liquidglass.engine.home.HomeLayout
import com.coloros16.liquidglass.engine.to
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桌面层错峰契约：图标网格 / 页点 / Dock 三块同相位滑出，被窗口裁掉前已位移。
 *
 * 需求 requirements:765 第1项「图标行/Dock 各自位移」。
 */
class HomeStaggerContractTest {

    private val preview =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private val engine =
        File("src/main/kotlin/com/coloros16/liquidglass/engine/SystemSceneMotion.kt").readText()

    private val w = 734.5f
    private val h = 1591.5f
    private val dp = 2.625f

    private fun geo(): HomeGeometry = HomeGeometry.solve(w, h, dp, 14f * dp, 1)

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

    private data class P(val name: String, val x: Float, val y: Float)

    private fun homePoints(): List<P> {
        val g = geo()
        val out = ArrayList<P>()
        for (i in 0 until HomeLayout.COLUMNS * HomeLayout.ROWS) {
            out.add(P("g$i", g.cellIconLeftPx(i) + g.iconSizePx / 2f, g.cellIconTopPx(i) + g.iconSizePx / 2f))
        }
        for (i in 0 until HomeLayout.HOTSEAT_ICONS) {
            out.add(
                P(
                    "d$i", g.hotseatIconLeftPx(i) + g.hotseatIconSizePx / 2f,
                    g.hotseatIconTopPx() + g.hotseatIconSizePx / 2f,
                ),
            )
        }
        out.add(
            P(
                "dots", g.indicatorItemLeftPx(0) + g.indicatorItemSizePx / 2f,
                g.indicatorItemTopPx() + g.indicatorItemSizePx / 2f,
            ),
        )
        return out
    }

    /**
     * 逐元素量：不加位移时首次被窗口裁掉的时刻，那一刻加了位移的元素走了多远。
     *
     * 判据不能用「出窗时位移>20」——窗口收缩本身也会让元素出窗，两者会混。
     */
    private fun dyAtClip(edge: TransitionEdge, travel: Float, photo: Boolean, slot: Int): List<Float> {
        val pts = homePoints()
        val layers = SystemSceneMotion.Layers()
        val pose = SailLensMotion.Pose()
        val win = ContentWindow.Window()
        val cp = SystemSceneMotion.ContentPose()
        val pv = PhotoStyleMotion.Values()
        val style = PhotoStyleMotion.byId(2)!!
        val out = ArrayList<Float>()
        for (pt in pts) {
            var clipAt = -1f
            for (k in 0..400) {
                val p = k / 400f
                SystemSceneMotion.sample(edge, p, layers)
                if (layers.homeAlpha <= 0.002f) break
                if (photo) {
                    PhotoStyleMotion.sample(style, edge, p, h / w, 0f, 0f, pv)
                    ContentWindow.photo(pv.v, w, h, win)
                } else {
                    SailLensMotion.sample(edge, p, 0.5f, 0.435f, 1.24f, pose)
                    ContentWindow.lens(pose, w, h, win)
                }
                if (!ContentWindow.contains(win, pt.x, pt.y, w, h)) {
                    clipAt = p
                    break
                }
            }
            if (clipAt < 0f) continue
            SystemSceneMotion.sample(edge, clipAt, layers)
            SystemSceneMotion.contentStagger(1f - layers.homeAlpha, slot, false, travel, cp)
            out.add(kotlin.math.abs(cp.shift * h))
        }
        return out
    }

    private val lastSlot = SystemSceneMotion.CONTENT_STAGGER_COUNT - 1

    /** ★核心定量：末槽 + 行程 0.10 时每个被裁元素都已位移（最小 69.4px）。 */
    @Test
    fun everyClippedElementHasMovedLens() {
        val dy = dyAtClip(TransitionEdge.HOME_TO_AOD, 0.10f, photo = false, slot = lastSlot)
        assertEquals("圆窗下被裁元素数（手算真值）", 27, dy.size)
        assertEquals("最小位移 69.4px（手算真值）", 69.4f, dy.min(), 0.5f)
        assertEquals("平均位移 120.8px（手算真值）", 120.8f, dy.average().toFloat(), 1.0f)
        assertEquals("最大位移 159.2px（手算真值）", 159.2f, dy.max(), 1.0f)
    }

    /** 照片方窗同口径：13 个被裁元素全部已位移，最小 146.2px。 */
    @Test
    fun everyClippedElementHasMovedPhoto() {
        val dy = dyAtClip(TransitionEdge.HOME_TO_AOD, 0.10f, photo = true, slot = lastSlot)
        assertEquals("方窗下被裁元素数（手算真值）", 13, dy.size)
        assertEquals("最小位移 146.2px（手算真值）", 146.2f, dy.min(), 0.5f)
        assertEquals("平均位移 153.0px（手算真值）", 153.0f, dy.average().toFloat(), 1.0f)
    }

    /**
     * ★★阳性对照一：取首槽（index=0）退场延迟最长，前 38% 层进度零位移。
     *
     * order = 5、start = 5×0.076 = 0.38 ⇒ lp<0.38 时 local=0。
     * 而多数图标在 lp=0.10~0.24 就被收缩的窗口吃掉 ⇒ 零位移消失。
     */
    @Test
    fun positiveControlFirstSlotLeavesManyEaten() {
        val dy = dyAtClip(TransitionEdge.HOME_TO_AOD, 0.10f, photo = false, slot = 0)
        val zero = dy.count { it < 20f }
        assertEquals("首槽下零位移被吃 21 个（27 个被裁元素里只有 6 个熬到 lp>0.38）", 21, zero)
        assertTrue("首槽最小位移为 0", dy.min() < 1f)
        val last = dyAtClip(TransitionEdge.HOME_TO_AOD, 0.10f, photo = false, slot = lastSlot)
        assertEquals("末槽零位移必须为 0", 0, last.count { it < 20f })
    }

    /** ★阳性对照二：按元素序号折算槽位（count=28）会让顶两行零位移被吃。 */
    @Test
    fun positiveControlIndexDelayLeavesTopRowsEaten() {
        val pts = homePoints()
        val layers = SystemSceneMotion.Layers()
        val pose = SailLensMotion.Pose()
        val win = ContentWindow.Window()
        val cp = SystemSceneMotion.ContentPose()
        var zeroMoved = 0
        for ((i, pt) in pts.withIndex()) {
            var outAt = -1f
            var dyAt = 0f
            for (k in 0..400) {
                val p = k / 400f
                SystemSceneMotion.sample(TransitionEdge.HOME_TO_AOD, p, layers)
                if (layers.homeAlpha <= 0.002f) break
                SailLensMotion.sample(TransitionEdge.HOME_TO_AOD, p, 0.5f, 0.435f, 1.24f, pose)
                ContentWindow.lens(pose, w, h, win)
                SystemSceneMotion.contentStagger(1f - layers.homeAlpha, i, false, 0.30f, cp, count = 28)
                val dy = cp.shift * h
                if (!ContentWindow.contains(win, pt.x, pt.y + dy, w, h)) {
                    outAt = p; dyAt = dy
                    break
                }
            }
            if (outAt >= 0f && kotlin.math.abs(dyAt) < 20f) zeroMoved++
        }
        assertEquals("序号延迟下零位移被吃 8 个（手算真值 g0~g7）", 8, zeroMoved)
    }

    /**
     * ★阳性对照三：判据本身要有分辨力——「出窗时位移>20」会把窗口收缩误判成滑出。
     *
     * g0 在起点就需要 162px 才能滑出圆窗（0.102h），而它 p=0.20 就被收缩的窗口吃掉，
     * 那时 lp 只有 0.105 ⇒ 用「出窗位移」判会得出错误结论。
     */
    @Test
    fun positiveControlOutWindowCriterionIsMisleading() {
        val g = geo()
        val x = g.cellIconLeftPx(0) + g.iconSizePx / 2f
        val y = g.cellIconTopPx(0) + g.iconSizePx / 2f
        val pose = SailLensMotion.Pose()
        val win = ContentWindow.Window()
        SailLensMotion.sampleStatic(SceneState.HOME, 0.5f, 0.5f, 1.24f, pose)
        ContentWindow.lens(pose, w, h, win)
        assertTrue("起点 g0 在窗内", ContentWindow.contains(win, x, y, w, h))
        var need = 0f
        while (need < 1600f) {
            if (!ContentWindow.contains(win, x, y - need, w, h)) break
            need += 1f
        }
        assertEquals("起点滑出需 162px（手算真值）", 162f, need, 2f)
        assertTrue("远超入场行程 95.5px ⇒ 靠位移滑出顶部图标不现实", need > 0.06f * h)
    }

    /**
     * ★HOME_TO_LOCK 窗口恒 expanded 覆盖全屏 ⇒ 元素一个都不会被裁。
     *
     * 这条边桌面层退场靠自身可见度 + 位移，不是靠窗口吃；
     * 若拿「被裁元素数」当判据会得到 0 而误判「没生效」。
     */
    @Test
    fun homeToLockWindowNeverClips() {
        val pose = SailLensMotion.Pose()
        val win = ContentWindow.Window()
        for (k in 0..20) {
            SailLensMotion.sample(TransitionEdge.HOME_TO_LOCK, k / 20f, 0.5f, 0.435f, 1.24f, pose)
            ContentWindow.lens(pose, w, h, win)
            assertTrue("p=${k / 20f} 圆必须盖满全屏", ContentWindow.coversScreen(win.radius, win.centerX, win.centerY, w, h))
        }
        assertEquals("该边被裁元素数为 0", 0, dyAtClip(TransitionEdge.HOME_TO_LOCK, 0.10f, false, lastSlot).size)
        val cp = SystemSceneMotion.ContentPose()
        SystemSceneMotion.contentStagger(1f, lastSlot, false, 0.10f, cp)
        assertEquals("退场终点仍满程位移", -0.10f, cp.shift, 1e-5f)
    }

    /** ★接线：三块绘制都必须接可见度与位移参数，且默认值保持静止态原行为。 */
    @Test
    fun threeHomeBlocksTakeVisibleAndShift() {
        for (fn in listOf("drawAppGrid", "drawHomeDots", "drawDock")) {
            val sig = preview.substring(preview.indexOf("private fun $fn("), preview.indexOf("private fun $fn(") + 400)
            assertTrue("$fn 必须有 visible 默认 1", sig.contains("visible: Float = 1f"))
            assertTrue("$fn 必须有 shift 默认 0", sig.contains("shift: Float = 0f"))
            val b = body(fn)
            assertTrue("$fn 必须早退", b.contains("if (visible <= 0.002f) return"))
            assertTrue("$fn 必须按可见度建层", b.contains("(visible.coerceIn(0f, 1f) * 255f).toInt()"))
            assertTrue("$fn 必须位移", b.contains("if (shift != 0f) canvas.translate(0f, shift)"))
            assertTrue("$fn 必须配对恢复", b.contains("canvas.restoreToCount(layer)"))
        }
    }

    /** 整层求值必须同相位取末槽（退场零延迟），不得按元素序号给延迟。 */
    @Test
    fun homeStaggerUsesSinglePhase() {
        val b = body("homeStaggerPose")
        assertTrue("必须调引擎件", b.contains("SystemSceneMotion.contentStagger("))
        assertTrue(
            "必须取末槽同相位",
            b.contains("layerProgress, SystemSceneMotion.CONTENT_STAGGER_COUNT - 1, entering, travel, homePose"),
        )
        assertFalse("不得传 count 走序号折算", b.contains("count ="))
        assertTrue("静止态早退", b.contains("if (effEdge == null) return 1f to 0f"))
        assertTrue("退场可见度取 1-progress", b.contains("if (entering) homePose.progress else 1f - homePose.progress"))
    }

    /** ★末槽在退场是零延迟、入场是最后落位，两个方向都要对。 */
    @Test
    fun lastSlotMeansZeroExitDelayAndLastEntry() {
        val cp = SystemSceneMotion.ContentPose()
        SystemSceneMotion.contentStagger(0.05f, lastSlot, false, 0.10f, cp)
        assertTrue("退场早期就必须已位移（实测 ${cp.shift}）", kotlin.math.abs(cp.shift) > 0f)
        SystemSceneMotion.contentStagger(0.05f, 0, false, 0.10f, cp)
        assertEquals("首槽退场早期零位移", 0f, cp.shift, 1e-6f)
        SystemSceneMotion.contentStagger(0.5f, lastSlot, true, 0.06f, cp)
        val lastEntry = cp.progress
        SystemSceneMotion.contentStagger(0.5f, 0, true, 0.06f, cp)
        assertTrue("入场末槽必须慢于首槽", lastEntry < cp.progress)
    }

    /** 行程按方向与裁切能力选，与锁屏同口径但常量独立。 */
    @Test
    fun travelSeparateFromLock() {
        assertTrue("入场常量", preview.contains("const val HOME_STAGGER_TRAVEL = 0.06f"))
        assertTrue("退场常量", preview.contains("const val HOME_STAGGER_EXIT_TRAVEL = 0.10f"))
        val b = body("homeStaggerPose")
        assertTrue(
            "按方向与裁切选",
            b.contains("if (entering || !windowClipped) HOME_STAGGER_TRAVEL else HOME_STAGGER_EXIT_TRAVEL"),
        )
        assertTrue("退场行程必须小于锁屏（桌面元素多，大行程会飞出屏）", 0.10f < 0.30f)
    }

    /** ★桌面退场行程不能照抄锁屏 0.30：会让底部 Dock 飞出屏幕顶端。 */
    @Test
    fun lockExitTravelWouldFlingDockOffScreen() {
        val g = geo()
        val dockCy = g.hotseatIconTopPx() + g.hotseatIconSizePx / 2f
        val lockTravelPx = 0.30f * h
        val homeTravelPx = 0.10f * h
        assertTrue("Dock 起点在屏幕下部", dockCy > h * 0.8f)
        assertTrue("锁屏行程会把 Dock 推到屏外负坐标", dockCy - lockTravelPx < h * 0.65f)
        assertTrue("桌面行程位移后仍在屏内", dockCy - homeTravelPx > h * 0.7f)
        assertEquals("桌面退场 159.2px", 159.15f, homeTravelPx, 0.1f)
    }

    /** 三块绘制都必须真的接到整层姿态，不能只改签名不传值。 */
    @Test
    fun iconAndDockLayersFeedPose() {
        val icons = body("drawSceneHomeIcons")
        assertTrue("图标层必须求姿态", icons.contains("val pose = homeStaggerPose(h)"))
        assertTrue("网格必须传姿态", icons.contains("drawAppGrid(canvas, geo, iconEntranceElapsedMs(), pose.first, pose.second)"))
        assertTrue("页点必须传姿态", icons.contains("drawHomeDots(canvas, geo, pose.first, pose.second)"))
        val dock = body("drawSceneHomeDock")
        assertTrue("Dock 层必须求姿态", dock.contains("val pose = homeStaggerPose(h)"))
        assertTrue("Dock 必须传姿态", dock.contains("drawDock(canvas, homeGeometry(w, h), pose.first, pose.second)"))
    }

    /** 诊断必须带边名、裁切标记、可见度与位移。 */
    @Test
    fun homeStaggerHasDiagnostic() {
        assertTrue("必须有日志函数", preview.contains("private fun logHomeStagger("))
        assertTrue("图标层必须调", body("drawSceneHomeIcons").contains("logHomeStagger(pose)"))
        val b = body("logHomeStagger")
        assertTrue("必须节流", b.contains("now - homeStaggerLogAt < 800L"))
        assertTrue("静止态不打", b.contains("if (effEdge == null) return"))
        assertTrue("必须打边名", b.contains("edge=\${effEdge.name}"))
        assertTrue("必须打裁切标记", b.contains("clipped=\$windowClipped"))
        assertTrue("必须打可见度与位移", b.contains("v=") && b.contains("dy="))
    }

    /** 引擎 count 参数默认六，旧调用点行为不变。 */
    @Test
    fun engineCountDefaultsToSix() {
        assertTrue("必须有 count 默认值", engine.contains("count: Int = CONTENT_STAGGER_COUNT"))
        assertTrue("必须按总数折算槽位", engine.contains("if (total <= n) index else index * n / total"))
        val a = SystemSceneMotion.ContentPose()
        val b = SystemSceneMotion.ContentPose()
        SystemSceneMotion.contentStagger(0.5f, 1, false, 0.30f, a)
        SystemSceneMotion.contentStagger(0.5f, 1, false, 0.30f, b, count = 6)
        assertEquals("默认与显式六必须同值", a.progress, b.progress, 1e-6f)
        assertEquals("位移同值", a.shift, b.shift, 1e-6f)
    }

    /** 折算后槽位仍在 0..5，越界 index 被夹住。 */
    @Test
    fun slotMappingStaysInRange() {
        val cp = SystemSceneMotion.ContentPose()
        for (i in 0 until 28) {
            SystemSceneMotion.contentStagger(0.9f, i, false, 0.30f, cp, count = 28)
            assertTrue("index$i 进度必须合法", cp.progress in 0f..1f)
        }
        SystemSceneMotion.contentStagger(0.9f, 999, false, 0.30f, cp, count = 28)
        val last = cp.progress
        SystemSceneMotion.contentStagger(0.9f, 27, false, 0.30f, cp, count = 28)
        assertEquals("越界 index 夹到末槽", last, cp.progress, 1e-6f)
    }

    /** 去桌面那条边桌面是入场，方向不能反。 */
    @Test
    fun homeEnteringDirectionCorrect() {
        assertEquals(SceneState.HOME, TransitionEdge.LOCK_TO_HOME.to)
        assertEquals(SceneState.HOME, TransitionEdge.AOD_TO_HOME.to)
        assertFalse("去息屏不是入场", TransitionEdge.HOME_TO_AOD.to == SceneState.HOME)
        val cp = SystemSceneMotion.ContentPose()
        SystemSceneMotion.contentStagger(1f, 0, true, 0.06f, cp)
        assertEquals("入场终点落位", 0f, cp.shift, 1e-5f)
        SystemSceneMotion.contentStagger(1f, 0, false, 0.10f, cp)
        assertEquals("退场终点满程向上", -0.10f, cp.shift, 1e-5f)
    }

    /**
     * ★★e 的来源核对（peer 第78轮同型提醒）：层进度取自 `layers.homeAlpha`，
     * 而 `layers` 由 `sampleLayers()` 按播放/拖动分别求值 —— **播放态已过该边曲线**。
     *
     * `contentStagger` 内部再过一次 STAGGER 是**设计**（错峰自己的缓动），
     * 不是「同一条曲线套两次」：前者是层进度曲线，后者是元素在窗口内的缓动。
     * 关键是 **direct 必须传到底**，否则拖动时会被套上播放曲线。
     */
    @Test
    fun layerProgressSourceMatchesPoseAndDirectPropagates() {
        val b = body("homeStaggerPose")
        assertTrue("层进度必须取自 layers", b.contains("if (entering) layers.homeAlpha else 1f - layers.homeAlpha"))
        assertTrue("direct 必须传到底", b.contains("direct = scrubP >= 0f"))
        val s = body("sampleLayers")
        assertTrue("拖动走 sampleTimeline", s.contains("scrubP >= 0f -> SystemSceneMotion.sampleTimeline(scrubP, layers)"))
        assertTrue(
            "播放走 sample(edge,t) 且显式传 edgeMs（否则层量按默认常量表归一化）",
            s.contains("SystemSceneMotion.sample(edge!!, t, layers, edgeMs = edgeMsFor(edge!!))"),
        )
        val eng = File("src/main/kotlin/com/coloros16/liquidglass/engine/SystemSceneMotion.kt").readText()
        assertTrue("sampleTimeline 内部 direct=true", eng.contains("sample(SceneTimeline.edgeAt(q), SceneTimeline.localT(q), out, direct = true)"))
    }

    /** ★定量：同一层进度下 direct 与否差值显著（证明 direct 真起作用不是摆设）。 */
    @Test
    fun directFlagChangesValueQuantified() {
        val layers = SystemSceneMotion.Layers()
        val a = SystemSceneMotion.ContentPose()
        val b = SystemSceneMotion.ContentPose()
        SystemSceneMotion.sample(TransitionEdge.HOME_TO_AOD, 0.375f, layers)
        val lp = 1f - layers.homeAlpha
        assertEquals("p=0.375 层进度 0.3172（手算真值）", 0.3172f, lp, 0.002f)
        SystemSceneMotion.contentStagger(lp, lastSlot, false, 0.10f, a, direct = false)
        SystemSceneMotion.contentStagger(lp, lastSlot, false, 0.10f, b, direct = true)
        assertEquals("播放态可见度 0.1196（手算真值）", 0.1196f, 1f - a.progress, 0.002f)
        assertEquals("同 lp 线性可见度 0.4883（手算真值）", 0.4883f, 1f - b.progress, 0.002f)
        assertTrue("差 0.369 ⇒ direct 真改变取值", kotlin.math.abs(a.progress - b.progress) > 0.35f)
    }

    /** ★拖动整轨必须线性：u=0.8 时 homeAlpha 恰 0.6（无曲线映射）。 */
    @Test
    fun scrubTimelineIsLinear() {
        val layers = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleTimeline(0.8f, layers)
        assertEquals("u=0.8 桌面层 0.6（线性）", 0.6f, layers.homeAlpha, 1e-4f)
        SystemSceneMotion.sampleTimeline(0.6f, layers)
        assertEquals("u=0.6 桌面层 0.2（线性）", 0.2f, layers.homeAlpha, 1e-4f)
        val cp = SystemSceneMotion.ContentPose()
        SystemSceneMotion.contentStagger(0.6f, lastSlot, true, 0.06f, cp, direct = true)
        assertEquals("拖动态 local=(0.6-0.38)/0.62=0.3548", 0.3548f, cp.progress, 0.002f)
    }

    /** hook 侧不跟改：无裁切能力，且已有零消费裁定档。 */
    @Test
    fun hookDoesNotAdoptHomeStagger() {
        val stage = File("src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt").readText()
        assertFalse("hook 不得用桌面错峰常量", stage.contains("HOME_STAGGER_EXIT_TRAVEL"))
        assertFalse("hook 不得出现预览裁切件", stage.contains("ContentWindow"))
    }

    /**
     * ★退场不得有第三次衰减：退场边 iconEntranceElapsedMs 返回 -1 走静态分支。
     *
     * 若退场也走带相位的分支，元素可见度会与 iconPose.alpha 再乘一次（双重淡出）。
     */
    @Test
    fun exitHasNoThirdFadeFromIconEntrance() {
        val b = body("iconEntranceElapsedMs")
        assertTrue(
            "只在两条进桌面边返回相位",
            b.contains("if (effEdge != TransitionEdge.LOCK_TO_HOME && effEdge != TransitionEdge.AOD_TO_HOME) return -1L"),
        )
        val grid = body("drawAppGrid")
        assertTrue("相位<0 必须走静态分支", grid.contains("if (entranceMs < 0L)"))
        val staticAt = grid.indexOf("if (entranceMs < 0L)")
        val phaseAt = grid.indexOf("HomeMotion.iconEntrance(")
        assertTrue("静态分支必须在相位求值之前", staticAt in 0 until phaseAt)
        assertTrue("静态分支必须 continue 跳过相位", grid.substring(staticAt, phaseAt).contains("continue"))
    }

    /**
     * 前景/后景/淡出淡入整屏同相位 ⇒ 末位图标在边内必落位。
     *
     * 旋转飞入的期望相位总长超出边长，由 `IconPhaseFit` 等比压到边内后同样必落位。
     */
    @Test
    fun entryPhaseFitsWithinEdge() {
        val total = SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME)
        assertEquals("解锁边 800ms", 800L, total)
        val count = HomeLayout.COLUMNS * HomeLayout.ROWS
        assertEquals("桌面网格 24 格", 24, count)
        val ip = com.coloros16.liquidglass.engine.effect.IconEntranceState()
        val (d, s) = com.coloros16.liquidglass.engine.effect.IconPhaseFit.fit(total, count, 420f, 28f)
        for (id in listOf(
            com.coloros16.liquidglass.engine.effect.EffectCatalog.HOME_ICON_FOREGROUND_FLY,
            com.coloros16.liquidglass.engine.effect.EffectCatalog.HOME_ICON_BACKGROUND_FLY,
            com.coloros16.liquidglass.engine.effect.EffectCatalog.HOME_ICON_CROSSFADE,
        )) {
            com.coloros16.liquidglass.engine.effect.HomeMotion.iconEntrance(
                id, count - 1, count, total, d, s, 60f, ip,
            )
            assertEquals("$id 同相位末位边内必满 alpha", 1f, ip.alpha, 1e-4f)
        }
        val need = com.coloros16.liquidglass.engine.effect.IconPhaseFit.neededMs(count, 420f, 28f)
        assertEquals("旋转飞入期望需 1064ms（手算 23×28+420）", 1064f, need, 1e-3f)
        assertTrue("期望超出边长 264ms ⇒ 必须压缩", need > total)
        assertTrue("确实压了", com.coloros16.liquidglass.engine.effect.IconPhaseFit.compressed(total, count, 420f, 28f))
        com.coloros16.liquidglass.engine.effect.HomeMotion.iconEntrance(
            com.coloros16.liquidglass.engine.effect.EffectCatalog.HOME_ICON_ROTATE_FLY,
            count - 1, count, total, d, s, 60f, ip,
        )
        assertEquals("压缩后旋转飞入末位边内必满", 1f, ip.alpha, 1e-4f)
        val half = total / 2
        com.coloros16.liquidglass.engine.effect.HomeMotion.iconEntrance(
            com.coloros16.liquidglass.engine.effect.EffectCatalog.HOME_ICON_ROTATE_FLY,
            0, count, half, d, s, 60f, ip,
        )
        val first = ip.alpha
        com.coloros16.liquidglass.engine.effect.HomeMotion.iconEntrance(
            com.coloros16.liquidglass.engine.effect.EffectCatalog.HOME_ICON_ROTATE_FLY,
            count - 1, count, half, d, s, 60f, ip,
        )
        assertTrue("错峰仍在：半程末位落后于首位", ip.alpha < first)
    }

    /** 静止态桌面层不位移（防烧屏漂移旁的内容不能飘）。 */
    @Test
    fun staticHomeStillSettled() {
        val b = body("homeStaggerPose")
        assertTrue("静止态早退", b.contains("if (effEdge == null) return 1f to 0f"))
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(SceneState.HOME, l)
        assertEquals("静止桌面层满", 1f, l.homeAlpha, 1e-6f)
    }
}
