package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 脚本层守卫契约：凡自己驱动按住拖动（`aud.touch_down/touch_move`）的 E2 脚本，
 * 必须在 `main()` 前置 `touch_ok()` 守卫。
 *
 * 治过的问题（AOD-117③）：探测失败时静默回落旧注入方式 ⇒ 抓到的是落位静止帧，
 * 脚本照样打 PASS —— 「工具坏了当成测过了」，比报错危险。正解是判 INCONCLUSIVE 退出。
 */
class ScriptTouchGuardContractTest {

    private fun sources(): Map<String, String> =
        File("../scripts").listFiles { f -> f.name.endsWith(".py") }
            ?.associate { it.name to it.readText() }
            ?: emptyMap()

    /** 违反清单：用了原始触摸流但没守门。 */
    private fun violations(all: Map<String, String>): List<String> {
        val providers = setOf("aod_midframe_audit.py")
        return all.filterKeys { it !in providers }
            .filterValues { it.contains("touch_down(") || it.contains("touch_move(") }
            .filterValues { !it.contains("touch_ok()") }
            .keys.sorted()
    }

    @Test
    fun everyTouchDrivenScriptGuardsOnTouchOk() {
        val all = sources()
        assertTrue("scripts 目录读不到", all.isNotEmpty())
        val driven = all.filterKeys { it != "aod_midframe_audit.py" }
            .filterValues { it.contains("touch_down(") || it.contains("touch_move(") }
        assertTrue("一个触摸驱动脚本都没找到，契约失去覆盖面", driven.size >= 4)
        assertEquals("这些脚本用了原始触摸流却没有 touch_ok() 守卫", emptyList<String>(), violations(all))
    }

    /** 阳性对照：合成一份「用了触摸但没有守卫」的脚本，必须被点名。 */
    @Test
    fun syntheticUnguardedScriptIsFlagged() {
        val fake = mapOf("fake_e2.py" to "def main():\n    aud.touch_down(10, 10)\n    aud.touch_up()\n")
        assertEquals(listOf("fake_e2.py"), violations(fake))
    }

    /** 负向对照：加了守卫的同一份脚本不算违规。 */
    @Test
    fun guardedScriptPasses() {
        val fake = mapOf(
            "ok_e2.py" to "def main():\n    if not aud.touch_ok():\n        return 2\n    aud.touch_down(10, 10)\n",
        )
        assertEquals(emptyList<String>(), violations(fake))
    }
}
