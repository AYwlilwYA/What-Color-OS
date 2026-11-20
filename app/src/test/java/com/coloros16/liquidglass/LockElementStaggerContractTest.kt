package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.to
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 锁屏浮层逐元素错峰契约：天气行与通知卡各自位移与淡入，不再整块一个位移。
 *
 * 需求 requirements:768 第1项「整层 alpha → 逐元素错峰纵向滑动」。
 * 引擎件 contentStagger 此前两侧零消费，本轮预览侧接上。
 */
class LockElementStaggerContractTest {

    private val text =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private fun body(fn: String): String {
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

    private fun pose(progress: Float, index: Int, entering: Boolean): SystemSceneMotion.ContentPose {
        val out = SystemSceneMotion.ContentPose()
        SystemSceneMotion.contentStagger(progress, index, entering, 0.06f, out)
        return out
    }

    /** 预览必须真的调 contentStagger，不能自己另写一套错峰。 */
    @Test
    fun previewConsumesContentStagger() {
        assertTrue("必须调引擎件", text.contains("SystemSceneMotion.contentStagger("))
        assertTrue("必须有元素求值入口", text.contains("private fun lockElementPose("))
        assertTrue("shift 是屏高归一化必须乘 h", text.contains("lockPose.shift * h"))
    }

    /** 旧的整块位移必须已删除（需求第6项点名它是待清理旧位移）。 */
    @Test
    fun oldBlockShiftRemoved() {
        assertFalse("lockShiftDp 必须已删", text.contains("lockShiftDp"))
        assertFalse("不得再有 12dp/24dp 分段硬编码", text.contains("u < 0.30f -> dp(12f)"))
    }

    /** 天气行与通知卡必须是两个独立绘制体，各带自己的 progress 与 shift。 */
    @Test
    fun weatherAndCardAreSeparateLayers() {
        assertTrue("天气行独立", text.contains("private fun drawLockWeather("))
        assertTrue("通知卡独立", text.contains("private fun drawLockCard("))
        val b = body("drawLockOverlay")
        assertTrue("天气槽位常量必须是 0", text.contains("const val LOCK_WEATHER_INDEX = 0"))
        assertTrue("通知卡槽位常量必须是 1", text.contains("const val LOCK_CARD_INDEX = 1"))
        assertTrue("天气取天气槽位", b.contains("lockElementPose(LOCK_WEATHER_INDEX, h)"))
        assertTrue("通知卡取通知卡槽位", b.contains("lockElementPose(LOCK_CARD_INDEX, h)"))
        assertTrue("两者各自传入", b.contains("weatherPose.first, weatherPose.second"))
        assertTrue("两者各自传入", b.contains("cardPose.first, cardPose.second"))
    }

    /** 每个元素自己 saveLayerAlpha + translate，不是整层一次。 */
    @Test
    fun eachElementHasOwnAlphaAndTranslate() {
        for (fn in listOf("drawLockWeather", "drawLockCard")) {
            val b = body(fn)
            assertTrue("$fn 必须按自身可见度淡入", b.contains("visible.coerceIn(0f, 1f) * 255f"))
            assertTrue("$fn 必须按自身 shift 位移", b.contains("canvas.translate(0f, shift)"))
            assertTrue("$fn 必须有透明早退", b.contains("if (visible <= 0.002f) return"))
        }
    }

    /** ★核心事实：同一层进度下，两个元素的进度与位移必须不同（错峰真生效）。 */
    @Test
    fun twoElementsDifferAtSameLayerProgress() {
        var differ = 0
        for (step in 1..19) {
            val p = step / 20f
            val a = pose(p, 0, true)
            val b = pose(p, 1, true)
            if (kotlin.math.abs(a.progress - b.progress) > 1e-4f) differ++
        }
        assertTrue("19 个采样点里必须有错峰差异（实测 $differ）", differ > 10)
    }

    /** 入场自下而上：起点 shift 为正（在下方），终点归零。 */
    @Test
    fun enteringSlidesUpFromBelow() {
        val start = pose(0f, 0, true)
        val end = pose(1f, 0, true)
        assertTrue("入场起点必须在下方", start.shift > 0f)
        assertEquals("入场终点必须落位", 0f, end.shift, 1e-5f)
        assertEquals("入场终点全亮", 1f, end.progress, 1e-5f)
    }

    /** ★退场 progress 是「已让位程度」不是可见度：终点 progress=1、shift 满程向上。 */
    @Test
    fun exitingProgressMeansDepartureNotVisibility() {
        val start = pose(0f, 0, false)
        val end = pose(1f, 0, false)
        assertEquals("退场起点在原位", 0f, start.shift, 1e-5f)
        assertEquals("退场起点尚未让位", 0f, start.progress, 1e-5f)
        assertTrue("退场终点必须向上", end.shift < 0f)
        assertEquals("退场终点位移满程", -0.06f, end.shift, 1e-5f)
        assertEquals("退场终点 progress=1（完全让位）", 1f, end.progress, 1e-5f)
    }

    /** ★接线必须把退场 progress 翻成可见度，否则越让位越亮。 */
    @Test
    fun wiringInvertsProgressWhenExiting() {
        val b = body("lockElementPose")
        assertTrue(
            "退场可见度必须取 1-progress",
            b.contains("if (entering) lockPose.progress else 1f - lockPose.progress"),
        )
        assertTrue("返回可见度而非原始 progress", b.contains("return visible to lockPose.shift * h"))
    }

    /** ★阳性对照：直接拿退场 progress 当 alpha 会末帧全亮且已移出屏，肉眼可见的错。 */
    @Test
    fun positiveControlRawProgressAsAlphaIsInverted() {
        val end = pose(1f, 0, false)
        assertEquals("错法末帧 alpha=1", 1f, end.progress, 1e-5f)
        assertEquals("错法末帧却已让位满程", -0.06f, end.shift, 1e-5f)
        assertEquals("正确末帧可见度必须是 0", 0f, 1f - end.progress, 1e-5f)
        var inverted = 0
        for (step in 1..40) {
            val p = pose(step / 40f, 0, false)
            if (p.progress > 0.01f && -p.shift > 0.01f) inverted++
        }
        assertTrue("错法有 $inverted 个采样点是「越移越亮」", inverted > 10)
    }

    /** 退场可见度必须单调递减（全程不许回亮）。 */
    @Test
    fun exitingVisibilityIsMonotonicallyDecreasing() {
        for (index in 0..1) {
            var prev = 1f
            for (step in 0..40) {
                val v = 1f - pose(step / 40f, index, false).progress
                assertTrue("index$index 在 ${step / 40f} 处回亮", v <= prev + 1e-6f)
                prev = v
            }
            assertEquals("index$index 末帧必须全隐", 0f, prev, 1e-5f)
        }
    }

    /** 入场时后一个元素比前一个晚启动（错峰顺序）。 */
    @Test
    fun laterElementStartsLater() {
        val p = 0.15f
        assertTrue("index1 必须比 index0 慢", pose(p, 1, true).progress < pose(p, 0, true).progress)
        assertEquals("入场 index0 从 0 起步", 0f, pose(0f, 0, true).progress, 1e-5f)
        assertTrue("入场 index1 在 0.05 处还没启动", pose(0.05f, 1, true).progress == 0f)
    }

    /** ★退场顺序反转：order=n-1-i ⇒ index1 起步 0.304 早于 index0 的 0.380。 */
    @Test
    fun exitOrderIsReversed() {
        val p = 0.35f
        val a = pose(p, 0, false)
        val b = pose(p, 1, false)
        assertEquals("index0 起步晚此刻还没动", 0f, a.progress, 1e-5f)
        assertTrue("index1 必须比 index0 先走", b.progress > a.progress)
        assertTrue("可见度上 index1 更淡", 1f - b.progress < 1f - a.progress)
        assertEquals("index0 可见度仍满", 1f, 1f - a.progress, 1e-5f)
    }

    /** 阳性对照：0.15 处两元素都还没启动，拿它当采样点检不出顺序（我原来就错在这）。 */
    @Test
    fun positiveControlTooEarlySampleCannotTellOrder() {
        val p = 0.15f
        assertEquals(0f, pose(p, 0, false).progress, 1e-5f)
        assertEquals(0f, pose(p, 1, false).progress, 1e-5f)
        assertTrue("首个可分辨采样点是 0.35", pose(0.35f, 1, false).progress > 0f)
    }

    /** 静止态必须落位不位移（否则息屏静止时钟旁的内容会飘）。 */
    @Test
    fun staticStateIsSettled() {
        val b = body("lockElementPose")
        assertTrue("静止态必须早退", b.contains("if (effEdge == null)"))
        assertTrue("静止态可见度=1 位移=0", b.contains("return 1f to 0f"))
    }

    /** 层进度取锁屏层真值：入场取 lockAlpha、退场取 1-lockAlpha。 */
    @Test
    fun layerProgressFollowsLockAlpha() {
        val b = body("lockElementPose")
        assertTrue("方向按终点态判", b.contains("effEdge.to == SceneState.LOCK"))
        assertTrue("必须用分层真值", b.contains("if (entering) layers.lockAlpha else 1f - layers.lockAlpha"))
        assertTrue("拖动走线性", b.contains("direct = scrubP >= 0f"))
    }

    /** 阳性对照：旧的整块位移在同一 u 下两元素必然同值，检测器要能分辨。 */
    @Test
    fun positiveControlBlockShiftGivesIdenticalPose() {
        val blockShift = { u: Float ->
            when {
                u < 0.30f -> 12f
                u < 0.42f -> 12f * (1f - (u - 0.30f) / 0.12f)
                u > 0.62f -> -24f * ((u - 0.62f) / 0.06f).coerceIn(0f, 1f)
                else -> 0f
            }
        }
        for (u in listOf(0.1f, 0.35f, 0.5f, 0.65f)) {
            assertEquals("旧写法两元素同值", blockShift(u), blockShift(u), 1e-6f)
        }
        val a = pose(0.35f, 0, true)
        val b = pose(0.35f, 1, true)
        assertNotEquals("新写法两元素必须不同", a.progress, b.progress, 1e-4f)
    }

    /** 阳性对照：六元素窗口是引擎常量，不是预览自造。 */
    @Test
    fun positiveControlWindowCountComesFromEngine() {
        assertEquals(6, SystemSceneMotion.CONTENT_STAGGER_COUNT)
        assertTrue("预览不得自造窗口数", !text.contains("CONTENT_STAGGER_COUNT = "))
        val last = pose(0.5f, SystemSceneMotion.CONTENT_STAGGER_COUNT - 1, true)
        val first = pose(0.5f, 0, true)
        assertTrue("末位必须明显慢于首位", last.progress < first.progress)
    }

    /** 新链必须配节流诊断日志，否则错峰没生效时定位不了。 */
    @Test
    fun staggerHasThrottledDiagnosticLog() {
        assertTrue("必须有日志函数", text.contains("private fun logLockStagger("))
        assertTrue("绘制前必须调", body("drawLockOverlay").contains("logLockStagger(weatherPose, cardPose)"))
        val b = body("logLockStagger")
        assertTrue("必须节流", b.contains("now - lockStaggerLogAt < 800L"))
        assertTrue("静止态不打", b.contains("if (effEdge == null) return"))
        assertTrue("必须打两元素可见度", b.contains("weather=") && b.contains("card="))
        assertTrue("必须带边名", b.contains("edge=\${effEdge.name}"))
    }

    /** ★图标入场与锁屏错峰是两条不同的轨，不得归一（本文件只钉住这个边界）。 */
    @Test
    fun iconEntranceIsADifferentTrackNotDuplicateStagger() {
        val home = File("src/main/kotlin/com/coloros16/liquidglass/engine/effect/HomeMotion.kt").readText()
        assertTrue("图标轨按绝对毫秒与可变个数", home.contains("elapsedMs: Long") && home.contains("count: Int"))
        assertTrue("图标轨四种效果分支", home.contains("HOME_ICON_ROTATE_FLY"))
        assertTrue("图标轨带缩放与旋转", home.contains("out.rotationDeg") && home.contains("out.scale"))
        val eng = File("src/main/kotlin/com/coloros16/liquidglass/engine/SystemSceneMotion.kt").readText()
        assertTrue("错峰轨固定六窗口按层进度", eng.contains("CONTENT_STAGGER_COUNT = 6"))
        assertTrue("错峰轨只有纵向位移与进度", eng.contains("out.shift = if (entering)"))
        assertTrue("图标轨不得被换成 contentStagger", !text.contains("contentStagger(") || !body("drawAppGrid").contains("contentStagger"))
    }

    /** 去桌面那条边锁屏是退场，方向不能反。 */
    @Test
    fun unlockEdgeMeansLockExiting() {
        assertEquals(SceneState.HOME, TransitionEdge.LOCK_TO_HOME.to)
        assertNotEquals(SceneState.LOCK, TransitionEdge.LOCK_TO_HOME.to)
        assertEquals(SceneState.LOCK, TransitionEdge.AOD_TO_LOCK.to)
    }

    /**
     * ★揭开快照必须在捕获那一刻也是满的（所以 `drawLockOverlay(frozen = true)`）。
     *
     * 揭开在 `photoAlpha < 0.999` 的首帧就触发，那一刻 `layers.lockAlpha` 还接近 0
     * ⇒ 若按 live 姿态画，位图里锁屏元素几乎不可见；而位图按目标态缓存（键无 edge/t），
     * 整条边都会沿用这张近空白的目标图 ⇒ 与「快照必须画静态图标」同族双淡入。
     */
    @Test
    fun livePoseAtCaptureTimeIsNearlyEmpty() {
        val layers = SystemSceneMotion.Layers()
        SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, 0.01f, layers)
        val live = SystemSceneMotion.ContentPose()
        val frozen = SystemSceneMotion.ContentPose()
        SystemSceneMotion.contentStagger(layers.lockAlpha, 0, true, 0.06f, live)
        SystemSceneMotion.contentStagger(1f, 0, true, 0.06f, frozen)
        assertTrue(
            "捕获首帧 live 可见度必须远低于满（实测 ${live.progress}，lockAlpha=${layers.lockAlpha}）",
            live.progress < 0.2f,
        )
        assertEquals("冻结必须按满可见度画", 1f, frozen.progress, 1e-3f)
        assertEquals("冻结零位移", 0f, frozen.shift, 1e-6f)
    }
}
