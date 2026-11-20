package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneTimeline
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.WallpaperZoomSettle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 壁纸落位补间契约：两个已定案口径都不改，只补交接那一帧的缝。
 *
 * 边内 LOCK↔HOME 恒 1.1（参考实现恒等），静止桌面 1.0（系统真值）。
 */
class WallpaperZoomSettleContractTest {

    private fun preview(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private fun body(name: String): String {
        val text = preview()
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

    private fun edgeEnd(edge: TransitionEdge): Float {
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sample(edge, 1f, l)
        return l.wallpaperScale
    }

    private fun staticZoom(state: SceneState): Float {
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(state, l)
        return l.wallpaperScale
    }

    /** ★两个已定案口径一个字都不许改。 */
    @Test
    fun bothSettledPoliciesStayUnchanged() {
        for (e in listOf(TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK)) {
            for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val l = SystemSceneMotion.Layers()
                SystemSceneMotion.sample(e, t, l)
                assertEquals(
                    "$e t=$t 边内恒锁屏缩放",
                    SystemSceneMotion.WALLPAPER_SCALE_LOCK, l.wallpaperScale, 1e-6f,
                )
            }
        }
        assertEquals(
            "静止桌面仍是系统真值",
            SystemSceneMotion.WALLPAPER_SCALE_HOME, staticZoom(SceneState.HOME), 1e-6f,
        )
        assertEquals(
            "静止锁屏仍是系统真值",
            SystemSceneMotion.WALLPAPER_SCALE_LOCK, staticZoom(SceneState.LOCK), 1e-6f,
        )
    }

    /** 只有桌面落位需要补间，另两个锚点两端同值。 */
    @Test
    fun onlyHomeLandingNeedsSettle() {
        assertTrue(
            "LOCK→HOME 落位有缝",
            WallpaperZoomSettle.needed(edgeEnd(TransitionEdge.LOCK_TO_HOME), staticZoom(SceneState.HOME)),
        )
        assertFalse(
            "HOME→LOCK 落位无缝",
            WallpaperZoomSettle.needed(edgeEnd(TransitionEdge.HOME_TO_LOCK), staticZoom(SceneState.LOCK)),
        )
        assertFalse(
            "LOCK→AOD 落位无缝",
            WallpaperZoomSettle.needed(edgeEnd(TransitionEdge.LOCK_TO_AOD), staticZoom(SceneState.AOD)),
        )
        assertFalse(
            "HOME→AOD 落位无缝",
            WallpaperZoomSettle.needed(edgeEnd(TransitionEdge.HOME_TO_AOD), staticZoom(SceneState.AOD)),
        )
    }

    /** ★起点必须接边末值：补间第一帧就等于边末，否则补间本身又造一次跳。 */
    @Test
    fun settleStartsExactlyAtEdgeEnd() {
        val from = edgeEnd(TransitionEdge.LOCK_TO_HOME)
        val to = staticZoom(SceneState.HOME)
        assertEquals("elapsed=0 等于边末", from, WallpaperZoomSettle.zoomAt(from, to, 0L), 1e-6f)
    }

    /** 终点必须精确落到静止值，不许残留。 */
    @Test
    fun settleEndsExactlyAtStaticValue() {
        val from = edgeEnd(TransitionEdge.LOCK_TO_HOME)
        val to = staticZoom(SceneState.HOME)
        assertEquals("走完等于静止值", to, WallpaperZoomSettle.zoomAt(from, to, WallpaperZoomSettle.SETTLE_MS), 1e-6f)
        assertEquals("超时也等于静止值", to, WallpaperZoomSettle.zoomAt(from, to, WallpaperZoomSettle.SETTLE_MS * 3), 1e-6f)
        assertTrue("走完判 done", WallpaperZoomSettle.done(WallpaperZoomSettle.SETTLE_MS))
        assertFalse("差 1ms 未完", WallpaperZoomSettle.done(WallpaperZoomSettle.SETTLE_MS - 1))
    }

    /** 单调收敛：不得先反向再回来（那就是用户报的小—大—小）。 */
    @Test
    fun settleIsMonotonicNoOvershoot() {
        val from = edgeEnd(TransitionEdge.LOCK_TO_HOME)
        val to = staticZoom(SceneState.HOME)
        var prev = from
        for (ms in 0..WallpaperZoomSettle.SETTLE_MS step 4) {
            val z = WallpaperZoomSettle.zoomAt(from, to, ms)
            assertTrue("ms=$ms 不得反弹（$z > $prev）", z <= prev + 1e-6f)
            assertTrue("ms=$ms 不得越过终点", z >= to - 1e-6f)
            prev = z
        }
    }

    /** 每帧位移必须小到看不出：按 60fps 折算上沿像素。 */
    @Test
    fun perFrameShiftIsBelowPerceptibleStep() {
        val w = 1080f
        val h = 2400f
        val from = edgeEnd(TransitionEdge.LOCK_TO_HOME)
        val to = staticZoom(SceneState.HOME)
        fun top(z: Float): Float {
            val s = maxOf(w / w, h / h) * z
            return (h - h * s) / 2f
        }
        val frameMs = 1000f / 60f
        var maxStep = 0f
        var prev = top(from)
        var ms = 0f
        while (ms <= WallpaperZoomSettle.SETTLE_MS) {
            val cur = top(WallpaperZoomSettle.zoomAt(from, to, ms.toLong()))
            maxStep = maxOf(maxStep, kotlin.math.abs(cur - prev))
            prev = cur
            ms += frameMs
        }
        // 不补间时一帧跳 120px；补间后最大单帧必须显著小于它
        assertTrue("单帧最大位移 $maxStep 必须远小于 120px", maxStep < 40f)
        assertEquals("不补间时整跳 120px", 120f, kotlin.math.abs(top(to) - top(from)), 0.5f)
    }

    /** 同值不起补间：浮点噪声不该触发无意义动画。 */
    @Test
    fun sameZoomDoesNotStartSettle() {
        assertFalse(WallpaperZoomSettle.needed(1.1f, 1.1f))
        assertFalse(WallpaperZoomSettle.needed(1.1f, 1.1f + WallpaperZoomSettle.EPS / 2f))
        assertTrue(WallpaperZoomSettle.needed(1.1f, 1.0f))
        assertEquals("同值直接返回目标", 1.1f, WallpaperZoomSettle.zoomAt(1.1f, 1.1f, 0L), 1e-6f)
    }

    /** 进度钳位：负值与超时都不越界。 */
    @Test
    fun progressIsClamped() {
        assertEquals(0f, WallpaperZoomSettle.progress(-100L), 1e-6f)
        assertEquals(1f, WallpaperZoomSettle.progress(WallpaperZoomSettle.SETTLE_MS * 5), 1e-6f)
        assertEquals(0.5f, WallpaperZoomSettle.progress(WallpaperZoomSettle.SETTLE_MS / 2), 1e-3f)
    }

    /** ★接线：落位与拖动收尾都走 render，补间必须在 render 里起。 */
    @Test
    fun previewStartsSettleOnLanding() {
        val b = body("render")
        assertTrue("边内清补间", b.contains("zoomSettleFrom = 0f"))
        assertTrue("落位起补间", b.contains("startZoomSettle(prevEdge, scene)"))
        assertTrue("拖动中清补间", body("renderScrub").contains("zoomSettleFrom = 0f"))
        assertTrue("换款清补间", body("applyStyle").contains("zoomSettleFrom = 0f"))
    }

    /** 起补间前必须比两端真值，不得硬编 1.1→1.0。 */
    @Test
    fun settleReadsBothEndsFromEngine() {
        val b = body("startZoomSettle")
        assertTrue("边末取引擎", b.contains("SystemSceneMotion.sample(prevEdge, 1f, settleFromLayers)"))
        assertTrue("静止取引擎", b.contains("SystemSceneMotion.sampleStatic(target, settleToLayers)"))
        assertTrue("按差值判是否补", b.contains("WallpaperZoomSettle.needed"))
        assertFalse("不得硬编 1.1", b.contains("1.1f"))
        assertFalse("不得硬编 1.0", b.contains("1.0f"))
    }

    /** ★所有壁纸缩放消费点必须走补间取值，漏一处就两层不同框。 */
    @Test
    fun everyZoomConsumerGoesThroughSettle() {
        val src = preview()
        val raw = src.lines().filter { it.contains("layers.wallpaperScale") }
        assertEquals("只允许 wallpaperZoom 自身读原始分层：$raw", 1, raw.size)
        assertTrue("那一处就是取值件", raw[0].contains("return layers.wallpaperScale"))
        assertTrue("底图与效果层同取值", body("drawEffectBackdrop").contains("wallpaperZoom()"))
        assertTrue("软底同取值", body("drawBlurClearLayer").contains("wallpaperZoom()"))
    }

    /** 静止态直取常量的那条捷径必须在补间期让路。 */
    @Test
    fun staticShortcutYieldsDuringSettle() {
        val b = body("drawWallpaperBackground")
        assertTrue(
            "补间期不走静止捷径",
            b.contains("edge == null && scrubP < 0f && zoomSettleFrom <= 0f"),
        )
    }

    /**
     * ★出边分支必须有「出边真在跑」的守卫。
     *
     * 缺守卫时入边期间每帧都拿旧的 zoomSettleStartMs 算 elapsed（恒 done），
     * 于是每帧刷一条 from=0.0 的假 settle 日志，还把节流窗一直踩住、
     * 把入边的中间帧全吃掉（真机实测：入边 12 帧只剩终点 1 条）。
     */
    @Test
    fun settleBranchIsGuardedSoItCannotFakeRunForEntry() {
        // tick 是 val 对象不是 fun，按声明头切到 scheduleZoomSettle 之前
        val tick = preview().substringAfter("val zoomSettleTick = object")
            .substringBefore("private fun scheduleZoomSettle")
        assertTrue("出边分支要先判在有跑", tick.contains("if (zoomSettleFrom > 0f) {"))
        // 阳性对照：没有守卫时同样的折叠会把入边中间帧吃光
        val gap = WallpaperZoomSettle.SETTLE_MS / 4L
        val frame = 17L
        assertTrue("帧间隔 $frame < 节流窗 $gap ⇒ 假日志能把窗口占满", frame < gap)
    }

    /**
     * ★hook 侧也要补入边起点：`HOME_TO_LOCK` 的 1.0→1.1 不是预览独有。
     *
     * 世界层底（`bindWorld`）与效果层取景（`applyEffect`）都吃 `sceneLayers.wallpaperScale`，
     * 只有系统壁纸那一层由 OS 缩放；不补就是真机按锁屏键第一帧整屏跳 10%。
     */
    @Test
    fun hookSideAlsoSettlesEntryZoom() {
        val hook = File("src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt").readText()
        assertTrue("要有入边补间求值", hook.contains("private fun enterZoomOf("))
        val body = hook.substringAfter("private fun enterZoomOf(").substringBefore("private val choreoCallbacks")
        // 起点必须是「该边的起点态」，不能是「上一次见过的静止值」——后者会在设备
        // 于 LOCK 停过后一直挂 1.1，把两端都是 1.0 的边也补成 1.1→1.0（真机实测 8.8%/10%）
        assertTrue("起点取边起点态", body.contains("SystemSceneMotion.sampleStatic(edge.from"))
        assertFalse("不得用上一次静止值当起点", body.contains("lastStaticZoom"))
        assertTrue("复用同一套补间曲线", body.contains("WallpaperZoomSettle.zoomAt("))
        assertTrue("只在刚进边那一帧起", body.contains("t < 0.02f"))
        // 边内采样之后必须覆盖，否则世界层/效果层仍拿未补间的值
        val play = hook.substringAfter("SystemSceneMotion.sample(edge, params.t, sceneLayers, edgeMs = stageEdgeMs(edge))")
            .substringBefore("layer.bind(params")
        assertTrue("边内要覆盖缩放", play.contains("enterZoomOf(edge, params.t"))
        // 静止态是下一次入边的起点，必须记下来并清掉边标记
        val still = hook.substringAfter("SystemSceneMotion.sampleStatic(state, sceneLayers)")
            .substringBefore("layer.bind(null, sceneLayers")
        assertTrue("静止态清边标记", still.contains("enterZoomEdge = null"))
        // 阳性对照：两端同值的边（AOD↔HOME 都是 1.0）起点=终点 ⇒ 判据必须判「不需要补间」
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(SceneState.AOD, l)
        val aodZoom = l.wallpaperScale
        SystemSceneMotion.sampleStatic(SceneState.HOME, l)
        val homeZoom = l.wallpaperScale
        assertEquals("AOD/HOME 静止同值", aodZoom, homeZoom, 1e-4f)
        assertFalse("两端同值不得起补间", WallpaperZoomSettle.needed(aodZoom, homeZoom))
        // 补间在跑时帧泵不能停，否则补间停在半路
        assertTrue("补间期续帧", hook.contains("enterZoomFrom > 0f) startChoreographer("))
    }

    /** 帧回调只在补间期武装，离屏必须撤（禁轮询）。 */
    @Test
    fun frameCallbackIsArmedOnlyDuringSettle() {
        val src = preview()
        assertTrue("单飞守卫", body("scheduleZoomSettle").contains("if (zoomSettleScheduled) return"))
        // 钉意图不钉字面量：补间结束就该停泵，条件里允许多个补间变量
        assertTrue(
            "走完停泵",
            Regex("if \\([^)\\n]*zoomSettleFrom > 0f[^)\\n]*\\) scheduleZoomSettle\\(\\)").containsMatchIn(src),
        )
        assertTrue("离屏撤回调", src.contains("removeFrameCallback(zoomSettleTick)"))
        assertTrue("离屏清状态", src.contains("zoomSettleFrom = 0f"))
        assertFalse("不得用定时器", src.contains("postDelayed(zoomSettle"))
    }

    /**
     * 诊断必须能分辨补间进行中与已结束。
     *
     * ★钉意图不钉字面量：两条补间共用这一个函数，起终点是按 enter 分支取的
     * （改写形态自由），要紧的是「日志里的 from/to 确实来自补间真值」。
     */
    @Test
    fun diagnosticCarriesFromToAndDone() {
        val b = body("logZoomSettle")
        assertTrue("起终点必须取自补间真值", Regex("val from = if \\(enter\\) enterZoomFrom else zoomSettleFrom").containsMatchIn(b))
        assertTrue("终点同理", Regex("val to = if \\(enter\\) enterZoomTo else zoomSettleTo").containsMatchIn(b))
        assertTrue("日志必须带起终点字段", b.contains("from=\$from") && b.contains("to=\$to"))
        assertTrue("带当前值", b.contains("zoom="))
        assertTrue("带进度", b.contains("p="))
        assertTrue("带是否走完", b.contains("done=\$done"))
        assertTrue("进行中节流", b.contains("if (!done && now - last < ZOOM_SETTLE_LOG_GAP_MS) return"))
        assertTrue("走完不受节流", b.contains("!done &&"))
    }

    /** 两条补间必须能在日志里分开认，否则「按锁屏键那一跳补没补」取证不到。 */
    @Test
    fun enterAndSettleAreDistinguishableInLog() {
        val b = body("logZoomSettle")
        assertTrue("入边要独立 tag", b.contains("\"zoom-enter\""))
        assertTrue("落位沿用原 tag", b.contains("\"zoom-settle\""))
        assertTrue("tag 由 enter 决定", Regex("val tag = if \\(enter\\)").containsMatchIn(b))
    }

    /**
     * ★节流窗必须短于补间时长，否则整段只采到一条中间帧。
     *
     * 逐帧验收要能从日志取数，不能只能靠手算曲线。
     */
    @Test
    fun logGapAllowsMultipleMidFrames() {
        val src = preview()
        assertTrue(
            "节流窗必须由补间时长派生，不得各写魔数",
            src.contains("ZOOM_SETTLE_LOG_GAP_MS = WallpaperZoomSettle.SETTLE_MS / 4L"),
        )
        val gap = WallpaperZoomSettle.SETTLE_MS / 4L
        assertTrue("节流窗必须为正", gap > 0L)
        assertTrue("节流窗必须短于补间时长（gap=$gap settle=${WallpaperZoomSettle.SETTLE_MS}）", gap < WallpaperZoomSettle.SETTLE_MS)
        // 按节流窗模拟一次完整补间，中间帧条数必须 >1
        var last = -1L
        var mid = 0
        var ms = 0L
        while (ms < WallpaperZoomSettle.SETTLE_MS) {
            if (last < 0L || ms - last >= gap) { mid++; last = ms }
            ms += 16L
        }
        assertTrue("整段中间帧必须多于一条，实际 $mid", mid > 1)
    }

    /** 阳性对照：旧节流窗 400ms 大于补间时长 ⇒ 整段只剩一条中间帧。 */
    @Test
    fun positiveControlOldGapYieldsSingleMidFrame() {
        val oldGap = 400L
        assertTrue("旧窗大于补间时长", oldGap > WallpaperZoomSettle.SETTLE_MS)
        var last = -1L
        var mid = 0
        var ms = 0L
        while (ms < WallpaperZoomSettle.SETTLE_MS) {
            if (last < 0L || ms - last >= oldGap) { mid++; last = ms }
            ms += 16L
        }
        assertEquals("旧窗只采到一条", 1, mid)
    }

    /**
     * ★拖动吸附落位也要补间：拖动期 this.edge 恒 null，取边必须认 scrubEdge。
     *
     * 播放路径 prevEdge 来自 this.edge，拖动路径来自 renderScrub 记下的那一段。
     */
    @Test
    fun scrubLandingAlsoStartsSettle() {
        val src = preview()
        assertTrue("拖动必须记下所在边", body("renderScrub").contains("scrubEdge = SceneTimeline.edgeAt(scrubP)"))
        assertTrue(
            "落位取边必须兜到拖动边",
            body("render").contains("val prevEdge = this.edge ?: scrubEdge"),
        )
        assertTrue("落位后清拖动边", body("render").contains("scrubEdge = null"))
        assertTrue("换款清拖动边", body("applyStyle").contains("scrubEdge = null"))
        assertTrue("离屏清拖动边", body("onDetachedFromWindow").contains("scrubEdge = null"))
    }

    /**
     * ★兜底 scrubEdge 不得顺带打开时钟补尾：拖动只有两条 forward 边，终点都不是息屏。
     *
     * `render` 里 prevEdge 同时供补间与时钟补尾两个分支用，扩了来源要核另一个分支。
     */
    @Test
    fun scrubEdgeNeverOpensClockTail() {
        for (p in listOf(0f, 0.25f, 0.49f, 0.5f, 0.75f, 1f)) {
            val e = SceneTimeline.edgeAt(p)
            assertFalse(
                "拖动边 $e（p=$p）终点不是息屏，不得触发时钟补尾",
                com.coloros16.liquidglass.engine.AodClockEntry.entering(e),
            )
        }
        assertTrue(
            "阳性对照：真正的息屏进场边必须判 true",
            com.coloros16.liquidglass.engine.AodClockEntry.entering(TransitionEdge.LOCK_TO_AOD),
        )
    }

    /** 阳性对照：只认 this.edge 时拖动落位取到 null，补间永不触发。 */
    @Test
    fun positiveControlEdgeOnlyMissesScrubLanding() {
        val edgeDuringScrub: TransitionEdge? = null
        val scrubEdge = SceneTimeline.edgeAt(1f)
        assertEquals("拖动到桌面端点所在边", TransitionEdge.LOCK_TO_HOME, scrubEdge)
        assertNull("只认 this.edge 取不到边", edgeDuringScrub)
        assertEquals("兜到 scrubEdge 才取得到", TransitionEdge.LOCK_TO_HOME, edgeDuringScrub ?: scrubEdge)
        assertTrue(
            "该边落位确实有缝",
            WallpaperZoomSettle.needed(edgeEnd(scrubEdge), staticZoom(SceneState.HOME)),
        )
    }

    /** ★阳性对照：不补间时落位那一帧整跳 9.1%。 */
    @Test
    fun positiveControlWithoutSettleJumpsWholeGap() {
        val from = edgeEnd(TransitionEdge.LOCK_TO_HOME)
        val to = staticZoom(SceneState.HOME)
        val jump = kotlin.math.abs(from - to) / from
        assertEquals("不补间整跳 9.1%", 0.0909f, jump, 1e-3f)
        val settled = WallpaperZoomSettle.zoomAt(from, to, 16L)
        val firstStep = kotlin.math.abs(from - settled) / from
        assertTrue("补间首帧只走一小步（$firstStep）", firstStep < jump / 3f)
    }

    /** 入边起点缩放。 */
    private fun edgeStart(edge: TransitionEdge): Float {
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sample(edge, 0f, l)
        return l.wallpaperScale
    }

    /**
     * ★前提：`HOME_TO_LOCK` 的**起始帧**就与 HOME 静止值不同（1.0 → 1.1）。
     *
     * 与出边那条（边末 1.1 → 桌面静止 1.0）是**同一道缝的两个方向**。
     * 只补出边 = 半修：按锁屏键进边的那一帧仍跳 10%。
     */
    @Test
    fun homeToLockEntryDiffersFromHomeStatic() {
        val homeZoom = staticZoom(SceneState.HOME)
        val start = edgeStart(TransitionEdge.HOME_TO_LOCK)
        assertEquals("HOME 静止应为 1.0", 1.0f, homeZoom, 1e-4f)
        assertEquals("HOME_TO_LOCK 起始应为 1.1", 1.1f, start, 1e-4f)
        assertTrue(
            "起始帧差 ${start - homeZoom} 必须触发补间，否则按锁屏键那一帧会跳",
            WallpaperZoomSettle.needed(homeZoom, start),
        )
    }

    /** 阳性对照：本来就是同值的边不得被判成需要补间（否则会多出无意义动画）。 */
    @Test
    fun positiveControlSameValueEdgesNeedNoEntrySettle() {
        val aodZoom = staticZoom(SceneState.AOD)
        assertEquals("AOD→LOCK 起始应与 AOD 静止同值", aodZoom, edgeStart(TransitionEdge.AOD_TO_LOCK), 1e-4f)
        assertFalse(WallpaperZoomSettle.needed(aodZoom, edgeStart(TransitionEdge.AOD_TO_LOCK)))
    }

    /** 预览页必须有入边补间：只在 prevEdge 为空（刚进边）时起一次。 */
    @Test
    fun previewStartsEntrySettleWhenEdgeBegins() {
        val src = preview()
        assertTrue("要有 startEnterZoom", src.contains("private fun startEnterZoom("))
        val r = body("render")
        assertTrue("刚进边时要起补间", r.contains("startEnterZoom("))
        assertTrue("补间值必须参与壁纸缩放取值", body("wallpaperZoom").contains("enterZoomFrom"))
        // 两侧同一取法：起点取该边起点态，不取「上一次见过的静止态」
        assertTrue("预览侧起点取边起点态", src.contains("SystemSceneMotion.sampleStatic(edge.from"))
        assertFalse("预览侧不得再用上次静止态", src.contains("lastStaticScene"))
    }
}
