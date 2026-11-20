package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingAction
import com.coloros16.liquidglass.engine.SeedlingNotifPolicy
import com.coloros16.liquidglass.engine.SeedlingSymbolCoverage
import org.junit.Assert.assertTrue
import org.junit.Test

/** 岛符号绘制覆盖契约：动作词表/kind 产出的符号都有专属绘制，不落圆点兜底。 */
class SeedlingSymbolCoverageContractTest {

    @Test
    fun allActionSymbolsDrawn() {
        val sems = SeedlingNotifPolicy.ActionSemantic.values()
        for (s in sems) {
            val sym = SeedlingNotifPolicy.symbolFor(s)
            assertTrue("动作语义 $s 的符号 $sym 必须有专属绘制", SeedlingSymbolCoverage.hasDistinctGlyph(sym))
        }
    }

    @Test
    fun allKindSymbolsDrawn() {
        for (sym in SeedlingSymbolCoverage.kindSymbols()) {
            assertTrue("kind 符号 $sym 必须有专属绘制", SeedlingSymbolCoverage.hasDistinctGlyph(sym))
        }
    }

    @Test
    fun callButtonsDistinct_positiveControl() {
        // 阳性对照：接听/挂断/免提三按钮符号互不相同且都有专属绘制
        val answer = SeedlingNotifPolicy.symbolFor(SeedlingNotifPolicy.ActionSemantic.ANSWER)
        val hangup = SeedlingNotifPolicy.symbolFor(SeedlingNotifPolicy.ActionSemantic.HANGUP)
        val speaker = SeedlingNotifPolicy.symbolFor(SeedlingNotifPolicy.ActionSemantic.SPEAKER)
        assertTrue(answer != hangup && hangup != speaker && answer != speaker)
        assertTrue(SeedlingSymbolCoverage.hasDistinctGlyph(answer))
        assertTrue(SeedlingSymbolCoverage.hasDistinctGlyph(hangup))
        assertTrue(SeedlingSymbolCoverage.hasDistinctGlyph(speaker))
    }

    @Test
    fun infoIsIntentionalFallback() {
        // INFO 是有意兜底圆点（未知动作），不算缺口
        assertTrue(SeedlingSymbolCoverage.hasDistinctGlyph("INFO"))
        assertTrue("INFO 不在专属绘制集（兜底）", "INFO" !in SeedlingSymbolCoverage.DRAWN_SYMBOLS)
    }

    @Test
    fun mediaControlSymbolsDrawn() {
        for (sym in listOf("PLAY", "PAUSE", "SKIP_NEXT", "SKIP_PREV", "HEART")) {
            assertTrue("媒体控制符号 $sym 有专属绘制", SeedlingSymbolCoverage.hasDistinctGlyph(sym))
        }
    }
}
