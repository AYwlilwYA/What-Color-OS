package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 大丸新增入口契约。
 *
 * 反面实锤：`LockLayoutSettings` 默认只放两个槽，而全仓没有任何「新增槽」的入口 ——
 * `showPillPanel` 只改已有槽的内容（`setPillContent`），拖动只改坐标。
 * 用户能看见的只有两个图标，且不知道还能加。
 */
class AodPillSlotAddContractTest {

    private fun delegate(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewEditDelegate.kt").readText()

    private fun panel(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodInlineEditPanel.kt").readText()

    private fun controller(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodDisplayController.kt").readText()

    /** 回调必须存在，且带归一化坐标（否则调用方还得自己摸 phoneRect）。 */
    @Test
    fun delegateExposesSlotTapWithNormalizedCoords() {
        val src = delegate()
        assertTrue("接口要有回调", src.contains("fun onPillSlotTapped(xFrac: Float, yFrac: Float)"))
        val hit = src.substringAfter("tapped && target == null && inDockBand(")
            .substringBefore("target = null")
        assertTrue("要归一化 x：$hit", hit.contains("x / phoneRect.width()"))
        assertTrue("要归一化 y：$hit", hit.contains("y / phoneRect.height()"))
        assertTrue("要钳到 0..1", hit.contains("coerceIn(0f, 1f)"))
    }

    /** ★只在横带内响应：画布空白处点一下不该弹面板。 */
    @Test
    fun slotTapIsConfinedToDockBand() {
        val src = delegate()
        val band = src.substringAfter("private fun inDockBand(").substringBefore("private fun pillRect(")
        assertTrue("横带上下界由常量定：$band", band.contains("DOCK_BAND_TOP..DOCK_BAND_BOTTOM"))
        assertTrue("高度为 0 不得判真", band.contains("h > 0f"))
        val top = Regex("DOCK_BAND_TOP = ([\\d.]+)f").find(src)!!.groupValues[1].toFloat()
        val bottom = Regex("DOCK_BAND_BOTTOM = ([\\d.]+)f").find(src)!!.groupValues[1].toFloat()
        assertTrue("横带必须包住默认槽 y=0.88（$top..$bottom）", top <= 0.88f && bottom >= 0.88f)
        assertTrue("横带不得覆盖时钟区（顶部 40%）", top > 0.4f)
        assertTrue("横带不得越过画布底", bottom <= 1f)
        // 阳性对照：默认槽之外但仍在横带内的点应当命中，时钟中心不应当命中
        assertTrue("时钟中心不在横带", 0.435f !in top..bottom)
        assertTrue("默认槽在横带内", 0.88f in top..bottom)
    }

    /** 新增必须落到点击位置，不许套网格或塞默认位。 */
    @Test
    fun panelAddsPillAtClickedPosition() {
        val add = panel().substringAfter("fun showPillSlotPanel(")
            .substringBefore("fun showPillPanel(")
        assertTrue("要新建槽", add.contains("layout.setPill(PillPlacement("))
        assertTrue("x 取点击点", add.contains("x = xFrac"))
        assertTrue("y 取点击点", add.contains("y = yFrac"))
        assertTrue("改完要通知", add.contains("onChanged()"))
        assertFalse("不得套网格", add.contains("WIDGET_GRID_TOP"))
    }

    /** 只列还没放的类型：重复添加会把已有槽顶掉或生成两个同 typeId 的槽。 */
    @Test
    fun panelListsOnlyUnusedTypes() {
        val add = panel().substringAfter("fun showPillSlotPanel(")
            .substringBefore("fun showPillPanel(")
        assertTrue("要取已占用集合", add.contains("layout.pills().map { it.typeId }.toSet()"))
        assertTrue("要过滤掉已占用", add.contains("filter { it.id !in taken }"))
        assertTrue("放满要有提示", add.contains("settings_aod_edit_pill_add_empty"))
        // 阳性对照：默认两槽时可选正好剩 6 种
        val total = com.coloros16.liquidglass.engine.edit.PillType.choices().size
        val used = 2
        assertEquals("默认两槽时可选数", 6, total - used)
        assertTrue("可选数必须为正（否则面板永远空）", total - used > 0)
    }

    /** 预览侧必须真接上，否则面板永远弹不出来（回调空实现是最常见的假绿）。 */
    @Test
    fun previewSideIsWiredToThePanel() {
        val c = controller()
        assertTrue("控制器要实现回调", c.contains("override fun onPillSlotTapped("))
        assertTrue("回调要转给面板", c.contains("showPillSlotPanel("))
        assertFalse(
            "不得写成空实现",
            Regex("override fun onPillSlotTapped\\([^)]*\\)\\s*\\{\\s*}").containsMatchIn(c),
        )
        assertTrue("面板要有这个方法", panel().contains("fun showPillSlotPanel("))
    }

    /** 字符串资源必须在：缺资源会编译过但运行时显示键名。 */
    @Test
    fun stringsExist() {
        val res = File("src/main/res/values/strings.xml").readText()
        assertTrue(res.contains("name=\"settings_aod_edit_pill_add\""))
        assertTrue(res.contains("name=\"settings_aod_edit_pill_add_empty\""))
    }

    /**
     * ★新增槽必须能回读。
     *
     * 反面实锤（真机连试两次 `free` 里都还有 `pill.dial`）：写盘只遍历已有槽，
     * 回读却按「初始两槽」取（`pillMap[typeId] ?: return`）⇒ 新槽整条被丢，
     * 界面加完当场看着像成功，重进编辑页就没了。
     */
    @Test
    fun newPillSurvivesRoundTrip() {
        val s = com.coloros16.liquidglass.engine.edit.LockLayoutSettings()
        val before = s.pills().size
        assertTrue("默认槽", before > 0)
        val added = s.setPill(
            com.coloros16.liquidglass.engine.edit.PillPlacement("pill.dial", x = 0.5f, y = 0.88f),
        )
        assertTrue("新建要被接受", added)
        val text = s.toText()
        assertTrue("写盘必须带新槽：$text", text.contains("pill.dial.x="))
        val back = com.coloros16.liquidglass.engine.edit.LockLayoutSettings.parse(
            text, com.coloros16.liquidglass.engine.edit.EditSurface.LOCK,
        )
        val restored = back.pill("pill.dial")
        assertTrue("回读必须还在", restored != null)
        assertEquals("位置要保真", 0.5f, restored!!.x, 1e-4f)
        assertEquals("槽数要保真", before + 1, back.pills().size)
    }

    /** 阳性对照：旧写法（缺槽直接 return）确实会丢，且丢完不报错。 */
    @Test
    fun positiveControlOldReadPathSilentlyDropsNewSlot() {
        val map = HashMap<String, String>()
        map["pill.flashlight"] = "on=1"
        map["pill.dial"] = "on=1"
        val oldStyleKept = map.keys.filter { it in setOf("pill.flashlight", "pill.camera") }
        assertEquals("旧口径只留初始两槽，新槽被丢且无任何信号", 1, oldStyleKept.size)
        assertFalse("被丢的槽不会出现在结果里", "pill.dial" in oldStyleKept)
    }

    /**
     * ★系统只有两个槽：第 3 个起真机不显示，界面必须先说清楚。
     *
     * `PillPlacementApply` 只取 `x<0.5` 最左与 `x>=0.5` 最右各一个搬到系统
     * `start_button` / `end_button`，其余静默丢弃。预览侧却能画任意多个 ——
     * 不说清楚就是「预览加了、真机没有」的假象。
     */
    @Test
    fun slotPanelWarnsWhenMoreThanSystemSlots() {
        val add = panel().substringAfter("fun showPillSlotPanel(")
            .substringBefore("fun showPillPanel(")
        assertTrue("要有提示文案", add.contains("settings_aod_edit_pill_add_limit"))
        assertTrue("只在已满两个时提示", add.contains("if (taken.size >= 2)"))
        val res = File("src/main/res/values/strings.xml").readText()
        assertTrue("文案资源要在", res.contains("name=\"settings_aod_edit_pill_add_limit\""))
        // 阳性对照：系统侧确实只搬两个，配置里多出来的不会出现。
        // 这条钉的是「当前宿主只有两槽」的现状；AOD-135（自绘大丸支持 >2）落地时
        // 应随能力一起改成本仓新口径，不是回归。
        val hook = File("src/main/kotlin/com/coloros16/liquidglass/hook/aod/PillPlacementApply.kt").readText()
        assertTrue("hook 取最左", hook.contains("pills.filter { it.x < 0.5f }.minByOrNull"))
        assertTrue("hook 取最右", hook.contains("pills.filter { it.x >= 0.5f }.maxByOrNull"))
        assertEquals("hook 只搬两个槽", 2, Regex("place\\(start,|place\\(end,").findAll(hook).count())
    }
}
