package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.Easing
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 时钟编辑配置插值必须与状态机 pose 同曲线：播放走该边系统曲线，拖动走线性。
 *
 * 修前 delegate 的 clockOffset / drawSceneClockAt 都用裸 t，而 pose 走 clockCurve
 * ⇒ 位置与拉伸/字号/颜色两条链不同步，中途最大偏 0.095（MASK）/0.121（WALLPAPER）。
 */
class ClockConfigCurveParityContractTest {

    private fun delegate(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewEditDelegate.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
    ).readText()

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
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

    private fun lerp(a: Float, b: Float, e: Float): Float = a + (b - a) * e

    // ---------------------------------------------------------------- 曲线真值手算

    /** MASK 曲线关键点手算值（cubic-bezier(0.33,0,0.67,1)）。 */
    @Test
    fun maskCurveMatchesHandComputedValues() {
        val m = SystemSceneMotion.MASK
        assertEquals(0.104924f, m.map(0.20f), 0.00002f)
        assertEquals(0.157305f, m.map(0.25f), 0.00002f)
        assertEquals(0.500000f, m.map(0.50f), 0.00002f)
        assertEquals(0.842695f, m.map(0.75f), 0.00002f)
    }

    /** WALLPAPER 曲线关键点手算值（cubic-bezier(0.42,0,0.58,1)）。 */
    @Test
    fun wallpaperCurveMatchesHandComputedValues() {
        val w = SystemSceneMotion.WALLPAPER
        assertEquals(0.129162f, w.map(0.25f), 0.00002f)
        assertEquals(0.870838f, w.map(0.75f), 0.00002f)
    }

    /** 两条曲线端点必过 0/1，中点必过 0.5（对称 S 形）。 */
    @Test
    fun bothCurvesPassEndpointsAndMidpoint() {
        for (c in listOf(SystemSceneMotion.MASK, SystemSceneMotion.WALLPAPER)) {
            assertEquals(0f, c.map(0f), 1e-6f)
            assertEquals(1f, c.map(1f), 1e-6f)
            assertEquals(0.5f, c.map(0.5f), 1e-5f)
        }
    }

    /** 六边曲线归属：解锁两条走 WALLPAPER，其余四条走 MASK。 */
    @Test
    fun edgeCurveAssignmentIsTwoAndFour() {
        val wall = listOf(TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK)
        val mask = TransitionEdge.values().filter { it !in wall }
        for (e in wall) assertTrue("$e 应走 WALLPAPER", SystemSceneMotion.clockCurve(e) === SystemSceneMotion.WALLPAPER)
        for (e in mask) assertTrue("$e 应走 MASK", SystemSceneMotion.clockCurve(e) === SystemSceneMotion.MASK)
        assertEquals("WALLPAPER 两条", 2, wall.size)
        assertEquals("MASK 四条", 4, mask.size)
        assertEquals("共六边", 6, TransitionEdge.values().size)
    }

    // ---------------------------------------------------------------- 阳性对照：裸 t 偏差定量

    /** 裸 t 与 MASK 的最大偏差 0.0953，与 WALLPAPER 的最大偏差 0.1212。 */
    @Test
    fun positiveControlRawProgressDeviatesByUpToTwelvePoints() {
        var maxMask = 0f
        var maxWall = 0f
        for (i in 0..100) {
            val t = i / 100f
            maxMask = maxOf(maxMask, abs(SystemSceneMotion.MASK.map(t) - t))
            maxWall = maxOf(maxWall, abs(SystemSceneMotion.WALLPAPER.map(t) - t))
        }
        assertEquals("MASK 最大偏差", 0.0953f, maxMask, 0.0002f)
        assertEquals("WALLPAPER 最大偏差", 0.1212f, maxWall, 0.0002f)
        assertTrue("WALLPAPER 偏差更大", maxWall > maxMask)
    }

    /** 拉伸通道 AOD 1.0→LOCK 3.0：t=0.25 裸 t 得 1.5，真值 1.3146（相对偏 14.1%）。 */
    @Test
    fun stretchChannelQuantifiedAtQuarterProgress() {
        val t = 0.25f
        val raw = lerp(1f, 3f, t)
        val real = lerp(1f, 3f, SystemSceneMotion.MASK.map(t))
        assertEquals(1.5f, raw, 1e-6f)
        assertEquals(1.314609f, real, 0.00002f)
        assertEquals("相对偏 14.1%", 0.141f, (raw - real) / real, 0.002f)
    }

    /** 位置通道 cy 0.435→0.155、屏高 2400：t=0.25 裸 t 与真值差 62.29px。 */
    @Test
    fun positionChannelQuantifiedInPixels() {
        val t = 0.25f
        val raw = lerp(0.435f, 0.155f, t)
        val real = lerp(0.435f, 0.155f, SystemSceneMotion.MASK.map(t))
        assertEquals(0.365f, raw, 1e-6f)
        assertEquals(0.390955f, real, 0.00002f)
        assertEquals("像素差 62.29", -62.29f, (raw - real) * 2400f, 0.05f)
    }

    /** 字号通道 1.0→0.62：t=0.25 裸 t 0.905 vs 真值 0.9402。 */
    @Test
    fun fontScaleChannelQuantified() {
        val t = 0.25f
        val raw = lerp(1f, 0.62f, t)
        val real = lerp(1f, 0.62f, SystemSceneMotion.MASK.map(t))
        assertEquals(0.905f, raw, 1e-6f)
        assertEquals(0.940224f, real, 0.00002f)
        assertTrue("裸 t 字号偏小", raw < real)
    }

    /** 中点与两端裸 t 与曲线一致 ⇒ 只截两端或中点的验收看不出这个缺陷。 */
    @Test
    fun defectIsInvisibleAtEndpointsAndMidpoint() {
        for (t in listOf(0f, 0.5f, 1f)) {
            assertEquals(
                "t=$t 处两条链必须一致（故静止态与中点截屏看不出）",
                lerp(1f, 3f, t),
                lerp(1f, 3f, SystemSceneMotion.MASK.map(t)),
                1e-5f,
            )
        }
    }

    // ---------------------------------------------------------------- 接线：delegate

    @Test
    fun delegateExposesSharedClockProgress() {
        val src = body(delegate(), "clockProgress")
        assertTrue("钳位", src.contains("t.coerceIn(0f, 1f)"))
        assertTrue("拖动或无边走线性", src.contains("if (direct || edge == null) return q"))
        assertTrue(
            "播放走该边系统曲线",
            src.contains("SystemSceneMotion.clockCurve(edge).map(q)"),
        )
    }

    @Test
    fun bothConfigChainsUseSharedProgress() {
        val off = body(delegate(), "clockOffset")
        val at = body(delegate(), "drawSceneClockAt")
        assertTrue("位置链走共用进度", off.contains("val e = clockProgress(edge, t, direct)"))
        assertTrue("配置链走共用进度", at.contains("val e = clockProgress(edge, t, direct)"))
        assertFalse("位置链不得再用裸 t", off.contains("val e = t.coerceIn(0f, 1f)"))
        assertFalse("配置链不得再用裸 t", at.contains("val e = t.coerceIn(0f, 1f)"))
    }

    @Test
    fun bothEntryPointsCarryDirectFlag() {
        val d = delegate()
        assertTrue("clockOffset 收 direct", body(d, "clockOffset").contains("direct: Boolean = false"))
        assertTrue("drawSceneClockAt 收 direct", body(d, "drawSceneClockAt").contains("direct: Boolean = false"))
    }

    /** 四条配置通道必须共用同一个 e，不能各算一套。 */
    @Test
    fun allFourConfigChannelsShareOneProgress() {
        val src = body(delegate(), "drawSceneClockAt")
        for (frag in listOf(
            "lerp(fromSettings.clockScale, toSettings.clockScale, e)",
            "lerp(fromSettings.clockStretchX, toSettings.clockStretchX, e)",
            "lerp(fromSettings.clockStretchY, toSettings.clockStretchY, e)",
            "lerpColor(colorOf(fromSettings, sceneColor), colorOf(toSettings, sceneColor), e)",
            "if (e < 0.5f) fromSettings else toSettings",
        )) {
            assertTrue("缺通道 $frag", src.contains(frag))
        }
    }

    // ---------------------------------------------------------------- 接线：调用点

    @Test
    fun previewPassesScrubAsDirectOnBothCalls() {
        val src = preview()
        assertTrue(
            "位置调用传 scrub",
            src.contains("editDelegate.clockOffset(effEdge, effT, scrubP >= 0f)"),
        )
        assertTrue(
            "绘制调用传 scrub",
            src.contains("clockScale, sceneClockColor(), scrubP >= 0f)"),
        )
    }

    /** 拖动时 pose 走 onTimeline 线性，配置也必须线性，否则手指与画面不同步。 */
    @Test
    fun scrubKeepsBothChainsLinear() {
        val src = preview()
        assertTrue("pose 拖动走整轨直控", src.contains("clockMachine.onTimeline(scrubP)"))
        val d = body(delegate(), "clockProgress")
        assertTrue("配置拖动也线性", d.contains("if (direct || edge == null) return q"))
    }

    /** 播放时 pose 与配置必须取同一条边曲线（同源函数，不是各写一份）。 */
    @Test
    fun playUsesSameCurveSourceAsStateMachine() {
        val hk = stage()
        assertTrue(
            "hook pose 走 clockCurve",
            hk.contains("machine.onTransition(edge, t, SystemSceneMotion.clockCurve(edge))"),
        )
        assertTrue(
            "预览配置走同一函数",
            body(delegate(), "clockProgress").contains("SystemSceneMotion.clockCurve(edge)"),
        )
    }

    /** 静止态不受影响：edge 为 null 时两条链都取端值。 */
    @Test
    fun staticStateUnaffected() {
        val off = body(delegate(), "clockOffset")
        assertTrue("无边直接取当前态偏移", off.contains("if (edge == null) return offsetOf(state)"))
        val p = body(delegate(), "clockProgress")
        assertTrue("无边进度取钳位原值", p.contains("edge == null) return q"))
    }

    // ---------------------------------------------------------------- 与既有契约不冲突

    /** 既有契约要求「配置与 pose 同一个 e」，本轮把 e 的来源从裸 t 换成曲线，仍满足。 */
    @Test
    fun existingSameProgressRequirementStillHolds() {
        val src = body(delegate(), "drawSceneClockAt")
        assertFalse("不得回退到 wLock/wAod 权重", src.contains("wLock") || src.contains("wAod"))
        assertFalse("不得有 total 归一", src.contains("/ total"))
        assertTrue("仍是单一 e", src.contains("val e = clockProgress("))
    }

    /** 曲线映射单调递增，保证配置插值不会中途回退。 */
    @Test
    fun curveIsMonotonicSoConfigNeverGoesBackward() {
        for (c in listOf(SystemSceneMotion.MASK, SystemSceneMotion.WALLPAPER)) {
            var prev = -1f
            for (i in 0..100) {
                val v = c.map(i / 100f)
                assertTrue("必须单调不减", v >= prev - 1e-6f)
                prev = v
            }
        }
    }

    /** 线性缓动件存在且为恒等，供拖动语义参照。 */
    @Test
    fun linearEasingIsIdentity() {
        for (i in 0..10) {
            val t = i / 10f
            assertEquals(t, Easing.LINEAR.map(t), 1e-6f)
        }
    }
}