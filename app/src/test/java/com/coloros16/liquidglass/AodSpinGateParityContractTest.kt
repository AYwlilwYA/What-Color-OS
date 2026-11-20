package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodSpinGate
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.AodMotion
import com.coloros16.liquidglass.engine.effect.AodMotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 镜片自转门契约：门控只有一份，预览与 hook 都走它。
 *
 * 治的缺陷是 hook 侧只看动态总闸不看款式与边 —— 照片类会吃到镜片自转角、
 * 锁屏/桌面静止态与收缩边仍在转，而预览侧这三处都不转。
 */
class AodSpinGateParityContractTest {

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
    ).readText()

    private fun gate(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/engine/AodSpinGate.kt",
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

    // ------------------------------------------------------------ 门控真值

    /** 总闸关时六边三态全不转。 */
    @Test
    fun masterOffFreezesEveryCombination() {
        for (lens in listOf(true, false)) {
            for (state in SceneState.entries) {
                assertFalse(AodSpinGate.active(false, lens, state, null))
                for (e in TransitionEdge.entries) {
                    assertFalse("$lens $state $e", AodSpinGate.active(false, lens, state, e))
                }
            }
        }
    }

    /** 非镜片款一律不转：照片类没有自转通道。 */
    @Test
    fun nonLensStyleNeverSpins() {
        for (state in SceneState.entries) {
            assertFalse(AodSpinGate.active(true, false, state, null))
            for (e in TransitionEdge.entries) {
                assertFalse("$state $e", AodSpinGate.active(true, false, state, e))
            }
        }
    }

    /** 镜片款静止态只有息屏转。 */
    @Test
    fun stillOnlySpinsOnAod() {
        assertTrue(AodSpinGate.active(true, true, SceneState.AOD, null))
        assertFalse(AodSpinGate.active(true, true, SceneState.LOCK, null))
        assertFalse(AodSpinGate.active(true, true, SceneState.HOME, null))
    }

    /** 只有从息屏出发的两条展开边转，其余四边冻结。 */
    @Test
    fun onlyExpandEdgesSpin() {
        val spinning = TransitionEdge.entries.filter {
            AodSpinGate.active(true, true, SceneState.AOD, it)
        }
        assertEquals(
            listOf(TransitionEdge.AOD_TO_LOCK, TransitionEdge.AOD_TO_HOME),
            spinning,
        )
    }

    /** 展开边判定不看当前静止态：播放期 state 是起点面，两边都必须放行。 */
    @Test
    fun expandEdgesIndependentOfStateArgument() {
        for (state in SceneState.entries) {
            assertTrue(AodSpinGate.active(true, true, state, TransitionEdge.AOD_TO_LOCK))
            assertTrue(AodSpinGate.active(true, true, state, TransitionEdge.AOD_TO_HOME))
            assertFalse(AodSpinGate.active(true, true, state, TransitionEdge.LOCK_TO_AOD))
        }
    }

    // ------------------------------------------------------------ 静止帧链

    /** 三参数全零时不武装帧回调：总闸开也不代表画面在动。 */
    @Test
    fun allZeroParamsDoNotArmFrames() {
        assertFalse(AodSpinGate.stillAnimating(true, true, 0f, 0f, 0f))
        assertFalse(AodSpinGate.stillAnimating(true, false, 0f, 0f, 0f))
    }

    /** 任一相关参数非零就武装；混色只对镜片款有效。 */
    @Test
    fun anyRelevantParamArmsFrames() {
        assertTrue(AodSpinGate.stillAnimating(true, true, 12f, 0f, 0f))
        assertTrue(AodSpinGate.stillAnimating(true, true, 0f, 60f, 0f))
        assertTrue(AodSpinGate.stillAnimating(true, false, 0f, 0f, 6f))
        assertFalse("非镜片款没有混色通道", AodSpinGate.stillAnimating(true, false, 0f, 60f, 0f))
        assertFalse("非镜片款没有自转通道", AodSpinGate.stillAnimating(true, false, 12f, 0f, 0f))
    }

    /** 总闸关时任何参数都不武装。 */
    @Test
    fun masterOffNeverArmsFrames() {
        assertFalse(AodSpinGate.stillAnimating(false, true, 60f, 100f, 15f))
    }

    // ------------------------------------------------------------ 两侧接线

    /** 两侧都必须调共用门，不得各留一份 if。 */
    @Test
    fun bothSidesCallSharedGate() {
        assertTrue("预览必须调共用门", preview().contains("AodSpinGate.active("))
        assertTrue("hook 必须调共用门", stage().contains("AodSpinGate.active("))
        assertTrue("hook 帧链必须调共用门", stage().contains("AodSpinGate.stillAnimating("))
    }

    /** 预览门体不得再自己判款式与边。 */
    @Test
    fun previewGateHasNoOwnStyleOrEdgeBranch() {
        val b = body(preview(), "lensSpinActive")
        assertTrue("取到门体", b.isNotEmpty())
        assertFalse("不得再自判款式", b.contains("style.id != AodStyleCatalog.ID_SAIL"))
        assertFalse("不得再自判边", b.contains("activeEdge == TransitionEdge.AOD_TO_LOCK"))
        assertTrue("必须转发共用门", b.contains("AodSpinGate.active("))
    }

    /** hook 自转求值必须先过门再算角，且门关时返回上一角而不是清零。 */
    @Test
    fun hookSpinEvaluatesGateBeforeAngle() {
        val b = body(stage(), "lensSpin")
        assertTrue("取到 lensSpin", b.isNotEmpty())
        val gateAt = b.indexOf("AodSpinGate.active(")
        val angleAt = b.indexOf("SailLensMotion.spin(")
        assertTrue("必须有门", gateAt >= 0)
        assertTrue("必须有求值", angleAt >= 0)
        assertTrue("门必须在求值之前", gateAt < angleAt)
        assertFalse("门关不得清零（落位会跳角）", b.contains("return 0f"))
        assertTrue("门关必须返回上一角", Regex("""return lensSpinDeg""").findAll(b).count() >= 2)
    }

    /** 累计角必须多圈归一：大间隔补帧时不能停在 -360 以外。 */
    @Test
    fun hookSpinNormalizesPastFullTurn() {
        val b = body(stage(), "lensSpin")
        assertTrue("必须多圈归一", b.contains("% 360f"))
    }

    /** 增量按真实间隔算，与调用节奏无关（同一总时长不同分步结果相同）。 */
    @Test
    fun integrationIsStepCountIndependent() {
        var a = 0f
        repeat(100) { a = SailLensMotion.spin(a, 0.01f, 0.3f) }
        var b = 0f
        repeat(10) { b = SailLensMotion.spin(b, 0.1f, 0.3f) }
        assertEquals(a, b, 1e-3f)
    }

    /** hook 帧链不得再用 AodMotion.active 当静止判据（它只看总闸）。 */
    @Test
    fun hookStillJudgeDoesNotUseMotionActive() {
        val b = body(stage(), "stillWorldAnimating")
        assertTrue("取到帧链判据", b.isNotEmpty())
        assertFalse("active 只看总闸，不能当静止判据", b.contains("motionNow(true).active"))
        assertTrue("必须走共用门", b.contains("AodSpinGate.stillAnimating("))
    }

    /** 门控必须有诊断，关时也能分辨。 */
    @Test
    fun gateHasDiagnosticOnBothPhases() {
        val s = stage()
        assertTrue("必须有诊断函数", s.contains("private fun logSpinGate("))
        assertTrue("静止态要记", s.contains("logSpinGate(\"still\""))
        assertTrue("播放期要记", s.contains("logSpinGate(\"play\""))
        assertTrue("热重载要清判重键", s.contains("lastSpinGateKeys.clear()"))
    }

    /** 门控件必须纯 JVM。 */
    @Test
    fun gateIsPureJvm() {
        assertFalse("不得依赖 android", gate().contains("android."))
    }

    // ------------------------------------------------------------ 逐帧读配置

    /** 三个逐帧路径必须共用 TTL 缓存，不得各读各的。 */
    @Test
    fun perFramePathsShareSettingsCache() {
        val s = stage()
        for (name in listOf("stillWorldAnimating", "aodSpinSpeed", "motionNow")) {
            val b = body(s, name)
            assertTrue("$name 必须取到", b.isNotEmpty())
            assertTrue("$name 必须走共用缓存", b.contains("settingsCached()"))
            assertFalse("$name 不得直接解析配置", b.contains("EffectConfigStore.read("))
        }
    }

    /** 缓存必须真带 TTL，不是每次都重读。 */
    @Test
    fun settingsCacheHasTtl() {
        val b = body(stage(), "settingsCached")
        assertTrue("取到 settingsCached", b.isNotEmpty())
        assertTrue("必须走 TTL 判定", b.contains("KeyedTtlCache.fresh("))
        assertTrue("读失败必须保持上一份", b.contains("?: return cached"))
    }

    /** 热重载必须清缓存，否则新一代吃上一代的配置。 */
    @Test
    fun settingsCacheClearedOnHotReload() {
        val s = stage()
        val at = s.indexOf("fun shutdownForHotReload(")
        assertTrue("必须有热重载收尾", at >= 0)
        val b = body(s, "shutdownForHotReload")
        assertTrue("必须清 settingsCache", b.contains("settingsCache = null"))
        assertTrue("必须清 settingsReadAt", b.contains("settingsReadAt = 0L"))
    }

    // ------------------------------------------------------------ 阳性对照

    /** 旧 hook 口径：只看总闸 ⇒ 非镜片款、锁屏静止态、收缩边都会误判为要转。 */
    @Test
    fun positiveControlOldHookGateLetsWrongCasesThrough() {
        val motion = AodMotionState()
        AodMotion.evaluate(37_000L, true, 60f, 12f, 6f, motion)
        assertTrue("旧口径的前提：总闸开时 active 恒真", motion.active)
        val wrong = listOf(
            Triple(false, SceneState.AOD, null),
            Triple(true, SceneState.LOCK, null),
            Triple(true, SceneState.AOD, TransitionEdge.LOCK_TO_AOD),
            Triple(true, SceneState.AOD, TransitionEdge.LOCK_TO_HOME),
        )
        for ((lens, state, edge) in wrong) {
            assertTrue("旧口径会放行 $lens $state $edge", motion.active)
            assertFalse("新门必须挡住 $lens $state $edge", AodSpinGate.active(true, lens, state, edge))
        }
    }

    /** 旧帧链口径：总闸开 + 三参数全零仍逐帧，属无谓唤醒。 */
    @Test
    fun positiveControlOldStillJudgeArmsWithZeroParams() {
        val motion = AodMotionState()
        AodMotion.evaluate(123_456L, true, 0f, 0f, 0f, motion)
        assertTrue("旧口径照样判在动", motion.active)
        assertFalse("新门判不动", AodSpinGate.stillAnimating(true, true, 0f, 0f, 0f))
    }

    /** 门关时角必须真的是 0，不是「算了但乘 0」。 */
    @Test
    fun gateOffYieldsZeroAngleNotScaledAngle() {
        val seconds = 12.5f
        assertTrue("门开时角非零", SailLensMotion.spinAt(seconds, 0.3f) != 0f)
        val angle = if (AodSpinGate.active(true, true, SceneState.LOCK, null)) {
            SailLensMotion.spinAt(seconds, 0.3f)
        } else {
            0f
        }
        assertEquals(0f, angle, 0f)
    }
}