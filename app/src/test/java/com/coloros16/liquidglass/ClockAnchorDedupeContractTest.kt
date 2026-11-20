package com.coloros16.liquidglass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 时钟锚点去重必须连「视图当前实际属性」一起比。
 *
 * 反例（修复前）：只比上次写入的目标值 ⇒ 系统转场改写 translation/scale 后，
 * 我们的目标值没变，去重永久成立，锚点再也不重写 = 时钟停在系统给的位置。
 */
class ClockAnchorDedupeContractTest {

    private fun src(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/ClockAnchorApply.kt",
    ).readText()

    /** 去重块是否包含全部视图态比较。 */
    private fun stateAware(text: String): Boolean {
        val at = text.indexOf("if (prev != null &&")
        if (at < 0) return false
        val block = text.substring(at).substringBefore(") return")
        return listOf(
            "view.translationX", "view.translationY",
            "view.scaleX", "view.scaleY",
            "view.alpha", "view.rotation",
        ).all { block.contains(it) }
    }

    @Test
    fun dedupeComparesCurrentViewState() {
        assertTrue("去重必须连视图当前属性一起比", stateAware(src()))
    }

    @Test
    fun oldFormWouldFailThisContract() {
        val old = """
            if (prev != null &&
                kotlin.math.abs(prev[0] - dx) < EPS &&
                kotlin.math.abs(prev[1] - dy) < EPS
            ) return
        """.trimIndent()
        assertFalse("阳性对照：只比目标值的旧写法必须判不合格", stateAware(old))
    }

    @Test
    fun originLedgerKeepsFirstOrigin() {
        val ledger = File("src/main/kotlin/com/coloros16/liquidglass/engine/OriginLedger.kt").readText()
        assertTrue("记账必须是「首次写入前记、已记过不覆盖」", ledger.contains("if (origins.containsKey(key)) return false"))
    }
}
