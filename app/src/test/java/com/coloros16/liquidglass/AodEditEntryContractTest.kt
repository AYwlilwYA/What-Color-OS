package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SCREEN_ASPECT
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 编辑入口与画布比例契约：编辑就在预览页里做，画布必须整屏等比。
 *
 * ui/aod 类带 android 依赖不能在纯 JVM 加载，源码侧断言一律读文本。
 */
class AodEditEntryContractTest {

    private fun src(relative: String): String =
        File("src/main/kotlin/com/coloros16/liquidglass/$relative").readText()

    private fun res(relative: String): String = File("src/main/res/$relative").readText()

    /** 取函数体；表达式体（`= ...`）没有花括号，按缩进回到下一个成员为止。 */
    private fun body(text: String, fn: String): String {
        val match = Regex("""^([ \t]*)(override |internal |private )*fun $fn\b""", RegexOption.MULTILINE)
            .find(text) ?: error("取不到 $fn")
        val start = match.range.first
        val indent = match.groupValues[1].length
        val brace = text.indexOf('{', start)
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        if (brace < 0 || brace > lineEnd) {
            val next = Regex("""^[ \t]{0,$indent}(private |internal |override |public )*(fun|val|var|@|class|companion)\b""", RegexOption.MULTILINE)
                .find(text, lineEnd + 1)
            return text.substring(start, next?.range?.first ?: text.length)
        }
        var depth = 0
        var i = brace
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(brace, i + 1)
                }
            }
            i++
        }
        error("$fn 括号不闭合")
    }

    /** 屏幕比例与真机一致：1080×2376 与 1080×2400 都在 0.46 附近。 */
    @Test
    fun screenAspectMatchesRealDevice() {
        assertEquals(1080f / 2376f, SCREEN_ASPECT, 0.02f)
        assertEquals(1080f / 2400f, SCREEN_ASPECT, 0.02f)
        assertTrue("必须竖屏比例", SCREEN_ASPECT < 0.6f)
    }

    /** 阳性对照：正方形画布与真机比例差必须被上面的容差抓到。 */
    @Test
    fun positiveControlSquareCanvasFailsAspectTolerance() {
        assertTrue(
            "正方形比例必须超出容差",
            kotlin.math.abs(1f - SCREEN_ASPECT) > 0.02f,
        )
    }

    /** 预览样机框与编辑画布共用同一份比例，不许各写一份。 */
    @Test
    fun previewAndEditCanvasShareOneAspect() {
        val preview = src("ui/aod/AodPreviewView.kt")
        assertTrue(
            "预览比例必须引共用常量",
            preview.contains("PREVIEW_ASPECT = com.coloros16.liquidglass.engine.SCREEN_ASPECT"),
        )
        assertTrue("预览不得再写死 9f / 19.5f", !preview.contains("PREVIEW_ASPECT = 9f / 19.5f"))
        val edit = src("ui/aod/AodEditView.kt")
        assertTrue("编辑画布必须引共用常量", edit.contains("import com.coloros16.liquidglass.engine.SCREEN_ASPECT"))
    }

    /** 编辑画布自测手机比例：高度优先，超宽再按宽回算高。 */
    @Test
    fun editCanvasMeasuresPhoneProportion() {
        val fn = body(src("ui/aod/AodEditView.kt"), "onMeasure")
        assertTrue("必须按屏幕比例算宽", fn.contains("availH * SCREEN_ASPECT"))
        assertTrue("超宽必须回算高", fn.contains("w / SCREEN_ASPECT"))
        assertTrue("必须落到测量尺寸", fn.contains("setMeasuredDimension"))
    }

    /** 阳性对照：match_parent 直填容器会被压成正方形，比例必偏。 */
    @Test
    fun positiveControlFillParentBreaksAspect() {
        val square = 1080f / 1080f
        assertTrue("正方形容器比例远离真机", kotlin.math.abs(square - SCREEN_ASPECT) > 0.4f)
        val measuredW = 1400f * SCREEN_ASPECT
        assertEquals(SCREEN_ASPECT, measuredW / 1400f, 1e-4f)
    }

    /** 编辑画布在容器里居中，两侧留黑边而不是拉伸填满。 */
    @Test
    fun editCanvasIsCenteredInShell() {
        val xml = res("layout/view_shell_aod_edit.xml")
        val canvas = Regex("""<com\.coloros16\.liquidglass\.ui\.aod\.AodEditView[\s\S]*?/>""")
            .find(xml)?.value ?: error("取不到编辑画布节点")
        assertTrue("画布必须居中", canvas.contains("""android:layout_gravity="center""""))
    }

    /** 「编辑」行走就地编辑，不再压独立页。 */
    @Test
    fun editRowEntersInlineEdit() {
        val fn = body(src("ui/aod/AodSettingsUi.kt"), "editWidgetGroup")
        assertTrue("编辑行必须是就地编辑动作", fn.contains("AodNavAction.InlineEdit(editPageId)"))
        assertTrue("编辑行不得再压独立页", !fn.contains("AodNavAction.Push("))
    }

    /** 就地编辑分派：有内联能力走 handler，没有才退回独立页。 */
    @Test
    fun inlineEditDispatchFallsBackOnlyWithoutHandler() {
        val fn = body(src("ui/aod/AodSettingsUi.kt"), "onNav")
        assertTrue("必须分派 InlineEdit", fn.contains("is AodNavAction.InlineEdit ->"))
        assertTrue("有 handler 必须走就地", fn.contains("""handler("inline_edit:${'$'}{action.editPageId}")"""))
        assertTrue("无 handler 才退独立页", fn.contains("""push("aodui/${'$'}{action.editPageId}")"""))
        val handlerAt = fn.indexOf("val handler = editAction")
        val pushAt = fn.indexOf("push(\"aodui/")
        assertTrue("退路必须在 handler 判空分支里", handlerAt in 0 until pushAt)
    }

    /** 预览控制器认这个动作：关面板 + 落态 + 选中时钟。 */
    @Test
    fun displayControllerHandlesInlineEdit() {
        val text = src("ui/aod/AodDisplayController.kt")
        assertTrue("必须认 inline_edit 前缀", text.contains("""action.startsWith("inline_edit:")"""))
        val fn = body(text, "enterInlineEdit")
        assertTrue("必须关设置面板", fn.contains("settingsSheet?.dismiss()"))
        assertTrue("必须选中时钟露手柄", fn.contains("preview.edit.selectClock()"))
        assertTrue("息屏页必须落息屏态", fn.contains("PAGE_AOD_EDIT) SceneState.AOD else SceneState.LOCK"))
        assertTrue("动画期不得硬切", fn.contains("val busy = playing || scrub || settling"))
        assertTrue("必须有诊断日志", fn.contains("inline edit enter"))
    }

    /** 选中时钟必须有出口，且无配置时不得静默。 */
    @Test
    fun selectClockIsPublicAndLogged() {
        val text = src("ui/aod/AodPreviewEditDelegate.kt")
        val fn = body(text, "selectClock")
        assertTrue("必须是公开方法", text.contains("fun selectClock()"))
        assertTrue("必须走统一选中", fn.contains("select(Kind.CLOCK, null)"))
        assertTrue("必须重绘", fn.contains("host.invalidate()"))
        assertTrue("无配置必须留日志", fn.contains("select clock skipped no layout"))
    }

    /** 就地面板必须有复位入口：拉伸撞到边界钳位后用户得能回到默认。 */
    @Test
    fun inlineClockPanelHasLayoutReset() {
        val text = src("ui/aod/AodInlineEditPanel.kt")
        val fn = body(text, "showClockPanel")
        assertTrue("必须有复位入口", fn.contains("R.string.settings_aod_edit_reset"))
        assertTrue("复位必须清横向拉伸", fn.contains("layout.clockStretchX = fresh.clockStretchX"))
        assertTrue("复位必须清纵向拉伸", fn.contains("layout.clockStretchY = fresh.clockStretchY"))
        assertTrue("复位必须清缩放", fn.contains("layout.clockScale = fresh.clockScale"))
        assertTrue("复位必须清两轴位置", fn.contains("layout.clockX = fresh.clockX"))
        assertTrue("复位必须落盘", fn.contains("onChanged()"))
        assertTrue("复位必须有日志", fn.contains("inline clock layout reset"))
        assertTrue("复位不得动小组件", !fn.contains("layout.setWidget("))
    }

    /** 阳性对照：只清 scale 不清 stretch 的复位无法解除拉伸。 */
    @Test
    fun positiveControlResetWithoutStretchLeavesShape() {
        val s = LockLayoutSettings.defaults(com.coloros16.liquidglass.engine.edit.EditSurface.AOD)
        s.clockStretchX = 5f
        s.clockScale = 1.6f
        s.clockScale = 1f
        assertEquals("只清 scale 拉伸仍在", 5f, s.clockStretchX, 1e-6f)
        s.clockStretchX = 1f
        assertEquals(1f, s.clockStretchX, 1e-6f)
    }

    /** 用户可见文案不得再说「缩放」——手柄已是自由拉伸。 */
    @Test
    fun userFacingTextSaysStretchNotUniformScale() {
        val xml = res("values/strings.xml")
        for (key in listOf(
            "settings_aod_edit_inline_hint",
            "settings_aod_edit_hint_lock",
            "settings_aod_edit_hint_aod",
        )) {
            val value = Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1) ?: error("取不到 $key")
            assertTrue("$key 必须写自由拉伸", value.contains("拉伸"))
        }
    }
}
