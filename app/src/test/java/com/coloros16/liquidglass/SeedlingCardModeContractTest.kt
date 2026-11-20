package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingActivity
import com.coloros16.liquidglass.engine.SeedlingCardModePolicy
import com.coloros16.liquidglass.engine.SeedlingCardModePolicy.Mode
import com.coloros16.liquidglass.engine.SeedlingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 双大卡模式契约：OP 全卡竖排 / APPLE 单卡横滑环形轮换，含阳性对照。 */
class SeedlingCardModeContractTest {

    private fun act(id: String) = SeedlingActivity(id = id, source = "s", kind = SeedlingKind.MEDIA)
    private val abc = listOf(act("a"), act("b"), act("c"))

    // ---------- 卡序列 ----------

    @Test
    fun opShowsAllCards() {
        assertEquals(listOf("a", "b", "c"), SeedlingCardModePolicy.cardStack(Mode.OP, abc, null).map { it.id })
        assertEquals("OP 与展开 id 无关全显", listOf("a", "b", "c"), SeedlingCardModePolicy.cardStack(Mode.OP, abc, "b").map { it.id })
    }

    @Test
    fun appleShowsSingleCard() {
        assertEquals(listOf("b"), SeedlingCardModePolicy.cardStack(Mode.APPLE, abc, "b").map { it.id })
        assertEquals("未展开取首位", listOf("a"), SeedlingCardModePolicy.cardStack(Mode.APPLE, abc, null).map { it.id })
        assertEquals("未知 id 回落首位", listOf("a"), SeedlingCardModePolicy.cardStack(Mode.APPLE, abc, "zzz").map { it.id })
    }

    @Test
    fun appleStackSizeAlwaysOne_positiveControl() {
        assertTrue("阳性对照：APPLE 恒单卡", SeedlingCardModePolicy.cardStack(Mode.APPLE, abc, "c").size == 1)
        assertTrue(SeedlingCardModePolicy.cardStack(Mode.OP, abc, null).size == 3)
    }

    @Test
    fun emptyCandidates() {
        assertTrue(SeedlingCardModePolicy.cardStack(Mode.OP, emptyList(), null).isEmpty())
        assertTrue(SeedlingCardModePolicy.cardStack(Mode.APPLE, emptyList(), "a").isEmpty())
        assertNull(SeedlingCardModePolicy.cycleExpanded(Mode.APPLE, emptyList(), null, 1))
    }

    // ---------- 横滑轮换 ----------

    @Test
    fun appleCycleNextWraps() {
        assertEquals("b", SeedlingCardModePolicy.cycleExpanded(Mode.APPLE, abc, "a", 1))
        assertEquals("c", SeedlingCardModePolicy.cycleExpanded(Mode.APPLE, abc, "b", 1))
        assertEquals("末位下一张环回首位", "a", SeedlingCardModePolicy.cycleExpanded(Mode.APPLE, abc, "c", 1))
    }

    @Test
    fun appleCyclePrevWraps() {
        assertEquals("首位上一张环回末位", "c", SeedlingCardModePolicy.cycleExpanded(Mode.APPLE, abc, "a", -1))
        assertEquals("a", SeedlingCardModePolicy.cycleExpanded(Mode.APPLE, abc, "b", -1))
        assertEquals("b", SeedlingCardModePolicy.cycleExpanded(Mode.APPLE, abc, "c", -1))
    }

    @Test
    fun appleCycleUnknownStartsFirst() {
        assertEquals("未知 id 从首位起算下一张", "b", SeedlingCardModePolicy.cycleExpanded(Mode.APPLE, abc, "zzz", 1))
    }

    @Test
    fun opDoesNotCycle_positiveControl() {
        assertEquals("阳性对照：OP 全显不轮换", "a", SeedlingCardModePolicy.cycleExpanded(Mode.OP, abc, "a", 1))
        assertEquals("c", SeedlingCardModePolicy.cycleExpanded(Mode.OP, abc, "c", -1))
    }

    @Test
    fun zeroDirectionHolds() {
        assertEquals("b", SeedlingCardModePolicy.cycleExpanded(Mode.APPLE, abc, "b", 0))
    }

    // ---------- 竖排几何 ----------

    @Test
    fun stackTopArithmetic() {
        assertEquals(100f, SeedlingCardModePolicy.stackTop(100f, 0, 300f, 20f), 1e-3f)
        assertEquals(420f, SeedlingCardModePolicy.stackTop(100f, 1, 300f, 20f), 1e-3f)
        assertEquals(740f, SeedlingCardModePolicy.stackTop(100f, 2, 300f, 20f), 1e-3f)
    }

    @Test
    fun stackHeightArithmetic() {
        assertEquals("3×300+2×20=940", 940f, SeedlingCardModePolicy.stackHeight(3, 300f, 20f), 1e-3f)
        assertEquals("单卡无间距", 300f, SeedlingCardModePolicy.stackHeight(1, 300f, 20f), 1e-3f)
        assertEquals(0f, SeedlingCardModePolicy.stackHeight(0, 300f, 20f), 1e-3f)
    }

    // ---------- 卡高自适应（OP 竖排不越屏） ----------

    @Test
    fun opCardHeightFitsWhenOverflow() {
        // n=3 base=540 gap=36 avail=2187：总高1692≤2187 不缩，保持540
        assertEquals(540f, SeedlingCardModePolicy.opCardHeight(3, 540f, 36f, 2187f, 240f), 1e-3f)
        // avail=1200 装不下1692：h=(1200-72)/3=376，总高恰=1200
        val h = SeedlingCardModePolicy.opCardHeight(3, 540f, 36f, 1200f, 240f)
        assertEquals(376f, h, 1e-3f)
        assertEquals(1200f, SeedlingCardModePolicy.stackHeight(3, h, 36f), 1e-3f)
    }

    @Test
    fun opCardHeightSingleCardNeverShrinks_positiveControl() {
        assertEquals("阳性对照：单卡不缩", 540f, SeedlingCardModePolicy.opCardHeight(1, 540f, 36f, 100f, 240f), 1e-3f)
        assertEquals("n=2 装得下不缩", 540f, SeedlingCardModePolicy.opCardHeight(2, 540f, 36f, 2000f, 240f), 1e-3f)
    }

    @Test
    fun opCardHeightClampsToMin() {
        // avail 极小解出 h<min：钳到 min（防扁卡），不再保证总高=avail
        assertEquals(240f, SeedlingCardModePolicy.opCardHeight(3, 540f, 36f, 300f, 240f), 1e-3f)
    }

    @Test
    fun opCardHeightTwoCardsSolve() {
        // n=2 base=540 gap=36 avail=900：总高1116>900，h=(900-36)/2=432
        val h = SeedlingCardModePolicy.opCardHeight(2, 540f, 36f, 900f, 240f)
        assertEquals(432f, h, 1e-3f)
        assertEquals(900f, SeedlingCardModePolicy.stackHeight(2, h, 36f), 1e-3f)
    }
}
