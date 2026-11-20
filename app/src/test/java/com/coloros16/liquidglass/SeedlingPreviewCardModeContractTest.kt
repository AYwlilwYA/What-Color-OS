package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingActivity
import com.coloros16.liquidglass.engine.SeedlingCardModePolicy
import com.coloros16.liquidglass.engine.SeedlingCardModePolicy.Mode
import com.coloros16.liquidglass.engine.SeedlingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App 预览双大卡同步契约：预览与 hook 共用 SeedlingCardModePolicy 求值，
 * OP 竖排几何 stackTop 同源、APPLE 横滑 cycleExpanded 同源、设置页喂模式与候选。
 */
class SeedlingPreviewCardModeContractTest {

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/SeedlingIslandPreviewView.kt",
    ).readText()

    private fun settings(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/SettingsActivity.kt",
    ).readText()

    private fun act(id: String) = SeedlingActivity(id = id, source = "s", kind = SeedlingKind.MEDIA)
    private val abc = listOf(act("a"), act("b"), act("c"))

    // ---------- 求值同源（预览消费面语义，engine 直测） ----------

    @Test
    fun previewShownActivitySemantics_apple() {
        // 预览 shownActivity = cardStack(mode, pool, expandedId).firstOrNull()
        assertEquals("a", SeedlingCardModePolicy.cardStack(Mode.APPLE, abc, null).firstOrNull()?.id)
        assertEquals("b", SeedlingCardModePolicy.cardStack(Mode.APPLE, abc, "b").firstOrNull()?.id)
    }

    @Test
    fun previewOpExtrasSemantics_dropFirst() {
        // OP 竖排副卡 = pool.drop(1)，第 idx 张顶 = stackTop(t, idx+1, h, gap)
        val extras = SeedlingCardModePolicy.cardStack(Mode.OP, abc, null).drop(1)
        assertEquals(listOf("b", "c"), extras.map { it.id })
        // 手算：t=100 h=180 gap=12 → 第一副卡顶 292、第二副卡顶 484
        assertEquals(292f, SeedlingCardModePolicy.stackTop(100f, 1, 180f, 12f))
        assertEquals(484f, SeedlingCardModePolicy.stackTop(100f, 2, 180f, 12f))
    }

    @Test
    fun previewOpCardHeightSolve() {
        // 预览板 avail=188dp，n=3 gap=12 base=180 min=24：opCardHeight 解 54.667，总高恰=188（gap 不缩）
        val avail = 188f; val gap = 12f; val n = 3
        val h = SeedlingCardModePolicy.opCardHeight(n, 180f, gap, avail, 24f)
        assertEquals(54.667f, h, 1e-3f)
        assertEquals(avail, SeedlingCardModePolicy.stackHeight(n, h, gap), 1e-3f)
        // n=2: h=(188-12)/2=88
        val h2 = SeedlingCardModePolicy.opCardHeight(2, 180f, gap, avail, 24f)
        assertEquals(88f, h2, 1e-3f)
        assertEquals(avail, SeedlingCardModePolicy.stackHeight(2, h2, gap), 1e-3f)
        // 阳性对照：n=1 h=180 总高 180<188 不缩
        assertEquals(180f, SeedlingCardModePolicy.opCardHeight(1, 180f, gap, avail, 24f), 1e-3f)
        assertTrue(SeedlingCardModePolicy.stackHeight(1, 180f, gap) <= avail)
    }

    @Test
    fun previewCycleSemantics_leftSwipeNext() {
        // 预览左滑 dx<0 → dir=1 下一张（与 hook CARD_CYCLE_LEFT 同向）
        assertEquals("b", SeedlingCardModePolicy.cycleExpanded(Mode.APPLE, abc, "a", 1))
        assertEquals("右滑上一张", "c", SeedlingCardModePolicy.cycleExpanded(Mode.APPLE, abc, "a", -1))
    }

    // ---------- 接线存在（源码扫描） ----------

    @Test
    fun previewConsumesPolicy() {
        val src = preview()
        assertTrue("预览须用 cardStack 求当前卡", src.contains("SeedlingCardModePolicy.cardStack(cardMode"))
        assertTrue("预览 OP 竖排须用 stackTop 同源几何", src.contains("SeedlingCardModePolicy.stackTop(t, idx + 1"))
        assertTrue("预览横滑须用 cycleExpanded 环形轮换", src.contains("SeedlingCardModePolicy.cycleExpanded(cardMode"))
    }

    @Test
    fun previewHasModeAndCandidates() {
        val src = preview()
        assertTrue("预览须有 cardMode 属性", src.contains("var cardMode: SeedlingCardModePolicy.Mode"))
        assertTrue("预览须有候选池", src.contains("var candidates: List<SeedlingActivity>"))
        assertTrue("候选空回落单活动", src.contains("candidates.ifEmpty { listOfNotNull(activity) }"))
    }

    @Test
    fun settingsFeedsPreview() {
        val src = settings()
        assertTrue("设置页须喂模式", src.contains("preview.cardMode ="))
        assertTrue("设置页须喂候选", src.contains("preview.candidates = SeedlingIslandPreviewView.demoCandidates()"))
        assertTrue("模式读自全局键", src.contains("Prefs.KEY_SEEDLING_CARD_MODE"))
    }

    @Test
    fun previewOpTapPromotes_positiveControl() {
        val src = preview()
        assertTrue("OP 点副卡提升为主卡", src.contains("expandedId = hit.id"))
        // 阳性对照：APPLE 分支不写 activity（只换 expandedId），OP 分支才提升
        assertTrue(src.contains("expandedId = newId"))
    }

    @Test
    fun hookAndPreviewSamePolicy_positiveControl() {
        val hook = File("src/main/kotlin/com/coloros16/liquidglass/hook/SeedlingIslandWindow.kt").readText()
        assertTrue("hook 与预览同一 policy 类", hook.contains("SeedlingCardModePolicy.cycleExpanded") && preview().contains("SeedlingCardModePolicy.cycleExpanded"))
        assertTrue("hook 与预览同一 stackTop", hook.contains("SeedlingCardModePolicy.stackTop(top, idx + 1") && preview().contains("SeedlingCardModePolicy.stackTop(t, idx + 1"))
    }
}
