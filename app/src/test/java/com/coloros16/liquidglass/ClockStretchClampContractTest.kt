package com.coloros16.liquidglass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 时钟自由拉伸的钳制契约：框不得超出手机框。
 *
 * 钩心 = clockBounds.right/bottom + 3.2dp，手柄是唯一能收窄时钟的入口。
 * 框一旦宽过手机框，钩心就跟着移到屏幕外 —— 拉过头再也拉不回来（可达性死锁）。
 * 需求要的是「能拉成非常长的时钟」，框内拉满就是最长；超出部分本来就被裁掉看不见。
 */
class ClockStretchClampContractTest {

    private val NL = "\n"  // 源文件是 LF，不能用 System.lineSeparator()


    private fun delegate(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewEditDelegate.kt").readText()

    private fun editView(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodEditView.kt").readText()

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

    /** ★判定收在共用件：两页都调它，不许各写一份。 */
    @Test
    fun clampLivesInSharedEngine() {
        val clockLayout = File("src/main/kotlin/com/coloros16/liquidglass/engine/edit/ClockLayout.kt").readText()
        assertTrue("共用件提供 fitStretch", clockLayout.contains("fun fitStretch("))
        assertTrue("按去留白后的比例收", clockLayout.contains("(boundW - padW) / (measuredW - padW)"))
        for ((name, src) in listOf("预览" to delegate(), "独立页" to editView())) {
            val b = body(src, "resizeClock")
            assertTrue("$name resizeClock 必须调共用件", b.contains("ClockLayout.fitStretch("))
            assertTrue("$name 钳后必须重量", b.contains("measureClockBounds(s, w, h)") || b.contains("drawClockOffscreen(s, w, h)"))
        }
    }

    /** ★存量超框配置：进编辑时先收一次，否则手柄早就不可点。 */
    @Test
    fun staleOversizeConfigIsFittedOnTouch() {
        for ((name, src) in listOf("预览" to delegate(), "独立页" to editView())) {
            assertTrue("$name 要有 ensureStretchFits", src.contains("private fun ensureStretchFits("))
            val down = src.substringAfter("ACTION_DOWN ->").substringBefore("ACTION_MOVE")
            assertTrue("$name DOWN 里要先收再判命中", down.contains("ensureStretchFits(s)"))
        }
        val settings = File("src/main/kotlin/com/coloros16/liquidglass/engine/edit/LockLayoutSettings.kt").readText()
        assertTrue("载入侧直读任意 stretch（故必须靠进编辑时收）", settings.contains("\"clock.stretch_x\""))
    }

    /**
     * ★上限必须是「钩心可达宽」而不是手机框宽：手机框只是画布，屏幕比它宽。
     *
     * 用手机框宽做上限时，用户把缩放拉到 1.6 框自然超框（实测 515→781），
     * 随便点一下画布就被压窄 22%，而钩心其实还在屏内、完全可操作。
     */
    @Test
    fun previewBoundUsesHookReachNotPhoneWidth() {
        val d = delegate()
        assertTrue("要有可达宽计算", d.contains("private fun hookReachW(phoneW: Float)"))
        val b = body(d, "resizeClock")
        assertTrue("resize 用可达宽", b.contains("hookReachW(w)"))
        assertFalse("不得直接用手机框宽当上限", b.contains("density, w, h,"))
        val e = body(d, "ensureStretchFits")
        assertTrue("载入路径也用可达宽", e.contains("hookReachW(w)"))
        // ★钉恒等式不钉数值：换机型 / 横屏 / 分屏时 phone.left 与屏宽都会变
        val reachBody = body(d, "hookReachW")
        assertTrue("上限必须由屏幕宽与画布左沿算出", reachBody.contains("screenW - phoneRect.left"))
        assertTrue("扣的是钩心偏移常量", reachBody.contains("HOOK_CENTER_PAD_DP"))
        assertFalse("不得写死某机型的可达宽", Regex("return [0-9]{3,}f").containsMatchIn(reachBody))
        // 阳性对照（C15 实测数，只验证方向：可达宽 > 画布宽，故放宽有效）
        val reach = 1080f - 226.4f - 10f
        assertTrue("C15 上可达 $reach 必须大于画布宽 627.2", reach > 627.2f)
    }

    /** 钳制只该在超框时动手：框内拉伸不得被压回，否则用户拉不到满宽。 */
    @Test
    fun clampIsConditionalNotUnconditional() {
        val b = body(File("src/main/kotlin/com/coloros16/liquidglass/engine/edit/ClockLayout.kt").readText(), "fitStretch")
        assertTrue("宽只在超框时收", b.contains("measuredW > boundW"))
        assertTrue("高只在超框时收", b.contains("measuredH > boundH"))
        assertFalse("不得写死最大 stretch", b.contains("coerceAtMost(MAX_STRETCH"))
        assertTrue("未超框走原值", b.contains("} else {") && b.contains("stretchX"+NL+"        }"))
    }

    /** 测量必须真的随 stretch 变，否则钳制是空转。 */
    @Test
    fun measureAppliesStretch() {
        val b = body(delegate(), "measureClockBounds")
        assertTrue("横向走 textScaleX", b.contains("textScaleX = s.clockStretchX"))
        assertTrue("纵向进半高", b.contains("s.clockStretchY"))
    }

    /**
     * 阳性对照：不钳时按实测数据算，钩心会跑到屏幕外。
     *
     * 数据来自真机 `clock-hit`：phone.left=226.4、phone 宽=627.2、屏幕宽 1080。
     */
    @Test
    fun positiveControlUnclampedHookLeavesScreen() {
        val phoneLeft = 226.4f
        val phoneW = 627.2f
        val screenW = 1080f
        val boxRight = 996.2f
        val pad = 10f
        val hookScreenX = phoneLeft + boxRight + pad
        assertTrue("不钳时钩心 $hookScreenX 已出屏（屏宽 $screenW）", hookScreenX > screenW)
        // 钳到框宽 = 手机框宽、且位置钳到居中 ⇒ 右边 = 手机框右边
        val clampedRight = phoneW
        val clampedHookX = phoneLeft + clampedRight + pad
        assertTrue("钳后钩心 $clampedHookX 必须回到屏内", clampedHookX < screenW)
    }
}
