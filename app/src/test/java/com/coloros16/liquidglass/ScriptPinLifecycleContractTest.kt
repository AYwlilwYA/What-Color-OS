package com.coloros16.liquidglass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 测试机 PIN 生命周期契约：临时设 PIN 的脚本必须保证收尾清除。
 *
 * 反例：中途 return / 异常时不清理 ⇒ 设备留着 PIN，之后所有依赖
 * 「无密码机直接解锁」的 E2 全部空跑，而且看起来像功能没生效。
 */
class ScriptPinLifecycleContractTest {

    private fun src(): String = File("../scripts/aod_142_verify.py").readText()

    private fun hasGuaranteedCleanup(text: String): Boolean {
        if (!text.contains("locksettings set-pin")) return false
        val at = text.indexOf("try:")
        if (at < 0) return false
        val fin = text.indexOf("finally:", at)
        if (fin < 0) return false
        val tail = text.substring(fin)
        return tail.contains("clear_pin()")
    }

    @Test
    fun pinSetIsAlwaysCleared() {
        assertTrue("设了 PIN 就必须在 finally 里清", hasGuaranteedCleanup(src()))
    }

    @Test
    fun oldFormWithoutFinallyFails() {
        val old = """
            locksettings set-pin 1234
            pads = bring_up_pin_pad()
            return drive()
        """.trimIndent()
        assertFalse("阳性对照：没有 finally 的写法必须判不合格", hasGuaranteedCleanup(old))
    }

    @Test
    fun existingPinIsNeverOverwritten() {
        val s = src()
        assertTrue("已有 PIN 时必须拒绝覆盖", s.contains("设备已有 PIN，不覆盖"))
        assertTrue("清 PIN 后必须回读确认", s.contains("locksettings get-disabled"))
    }
}
