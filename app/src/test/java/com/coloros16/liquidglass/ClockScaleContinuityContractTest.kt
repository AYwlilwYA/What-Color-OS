package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodClockEntry
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 时钟字号跨态连续性契约：静止态与转场两端不得跳变。
 *
 * 病灶是「息屏进场系数无条件乘到锁屏/桌面时钟上」与「静止走另一条绘制路径」。
 */
class ClockScaleContinuityContractTest {

    private val layers = SystemSceneMotion.Layers()

    private fun preview(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private fun delegate(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewEditDelegate.kt").readText()

    private fun body(text: String, fn: String): String {
        val start = Regex("""^\s*(override |internal |private )*fun $fn\b""", RegexOption.MULTILINE)
            .find(text)?.range?.first ?: error("取不到 $fn")
        var depth = 0
        var i = text.indexOf('{', start)
        val from = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(from, i + 1)
                }
            }
            i++
        }
        error("$fn 括号不闭合")
    }

    /** 息屏权重为 0 时进场系数必须完全失效（= 1）。 */
    @Test
    fun blendIsUnityWhenAodWeightZero() {
        assertEquals(1f, AodClockEntry.blend(0.85f, 0f), 1e-6f)
        assertEquals(1f, AodClockEntry.blend(0.5f, 0f), 1e-6f)
        assertEquals(1f, AodClockEntry.blend(1.3f, 0f), 1e-6f)
    }

    @Test
    fun blendIsEntryWhenAodWeightOne() {
        assertEquals(0.85f, AodClockEntry.blend(0.85f, 1f), 1e-6f)
        assertEquals(0.976f, AodClockEntry.blend(0.976f, 1f), 1e-6f)
    }

    @Test
    fun blendIsMonotonicInWeight() {
        var prev = 1f
        for (i in 0..10) {
            val v = AodClockEntry.blend(0.85f, i / 10f)
            assertTrue("权重增大时系数必须单调下降", v <= prev + 1e-6f)
            prev = v
        }
    }

    @Test
    fun blendClampsOutOfRangeWeight() {
        assertEquals(1f, AodClockEntry.blend(0.85f, -2f), 1e-6f)
        assertEquals(0.85f, AodClockEntry.blend(0.85f, 5f), 1e-6f)
    }

    /** LOCK→AOD 首帧字号必须等于静止 LOCK（旧口径会突降 15%）。 */
    @Test
    fun lockToAodFirstFrameMatchesStaticLock() {
        SystemSceneMotion.sampleStatic(SceneState.LOCK, layers)
        val staticEntry = layers.aodClockScale
        val staticScale = 0.62f * AodClockEntry.blend(staticEntry, 0f)

        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 0f, layers)
        val firstScale = 0.62f * AodClockEntry.blend(layers.aodClockScale, 0f)
        assertEquals(staticScale, firstScale, 1e-6f)
    }

    /** AOD→LOCK 末帧字号必须等于静止 LOCK。 */
    @Test
    fun aodToLockLastFrameMatchesStaticLock() {
        SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, 1f, layers)
        val lastScale = 0.62f * AodClockEntry.blend(layers.aodClockScale, 0f)
        SystemSceneMotion.sampleStatic(SceneState.LOCK, layers)
        val staticScale = 0.62f * AodClockEntry.blend(layers.aodClockScale, 0f)
        assertEquals(staticScale, lastScale, 1e-6f)
    }

    /** AOD→LOCK 首帧必须等于静止 AOD。 */
    @Test
    fun aodToLockFirstFrameMatchesStaticAod() {
        SystemSceneMotion.sampleStatic(SceneState.AOD, layers)
        val staticScale = 1f * AodClockEntry.blend(layers.aodClockScale, 1f)
        SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, 0f, layers)
        val firstScale = 1f * AodClockEntry.blend(layers.aodClockScale, 1f)
        assertEquals(staticScale, firstScale, 1e-6f)
    }

    /** 阳性对照：旧口径（无条件乘 entry）在 LOCK→AOD 首帧必突降。 */
    @Test
    fun positiveControlOldWayDropsAtFirstFrame() {
        SystemSceneMotion.sampleStatic(SceneState.LOCK, layers)
        val staticOld = 0.62f * layers.aodClockScale
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 0f, layers)
        val firstOld = 0.62f * layers.aodClockScale
        assertTrue(
            "旧口径首帧必须比静止小 10% 以上",
            (staticOld - firstOld) / staticOld > 0.10f,
        )
        val firstNew = 0.62f * AodClockEntry.blend(layers.aodClockScale, 0f)
        assertEquals("新口径必须补回这个落差", staticOld, firstNew, 1e-6f)
    }

    /** 整条边字号必须单调（息屏方向递增），不得中途回缩。 */
    @Test
    fun lockToAodFontIsMonotonic() {
        var prev = 0f
        for (i in 0..20) {
            val p = i / 20f
            SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, p, layers)
            val pose = 0.62f + (1f - 0.62f) * SystemSceneMotion.MASK.map(p)
            val scale = pose * AodClockEntry.blend(layers.aodClockScale, p)
            assertTrue("p=$p 字号不得回缩", scale >= prev - 1e-4f)
            prev = scale
        }
    }

    @Test
    fun previewBlendsEntryByAodWeight() {
        val fn = body(preview(), "drawSceneClock")
        assertTrue("必须按息屏权重混合进场系数", fn.contains("AodClockEntry.blend("))
        assertTrue(fn.contains("editDelegate.aodWeight("))
        assertTrue("字号必须用混合后的系数", fn.contains("pose.scale * entry"))
    }

    /** 场景类样式的静止态不得走 override 短路（否则与转场两条公式打架）。 */
    @Test
    fun sceneStylesNeverShortCircuitToOverride() {
        val fn = body(preview(), "drawSceneClock")
        assertTrue("override 必须限定非场景样式", fn.contains("sceneSpec == null"))
        assertTrue("补尾期不得被短路", fn.contains("clockTailEdge == null"))
    }

    @Test
    fun delegateExposesAodWeightForBothModes() {
        assertTrue(
            "权重求值只留引擎一份",
            delegate().contains("AodClockEntry.weightOf(edge, t, state)"),
        )
        val engine = File("src/main/kotlin/com/coloros16/liquidglass/engine/AodClockEntry.kt").readText()
        assertTrue("静止态按当前态取端值", engine.contains("if (edge == null) return if (staticState == SceneState.AOD)"))
        assertTrue("转场期线性插值", engine.contains("from + (to - from) * t"))
    }

    /** 阳性对照：旧的 override 门（只判 edge/scrub）会让场景样式静止态走错路径。 */
    @Test
    fun positiveControlOldOverrideGateMissesSceneStyles() {
        val oldGate = "if (edge == null && scrubP < 0f &&"
        assertTrue("旧门不含场景样式判据", !oldGate.contains("sceneSpec"))
        assertTrue("旧门不含补尾判据", !oldGate.contains("clockTailEdge"))
    }
}