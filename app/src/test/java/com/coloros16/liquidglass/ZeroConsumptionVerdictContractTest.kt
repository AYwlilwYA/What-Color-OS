package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 引擎零消费通道裁定契约：七项「引擎有、渲染两侧都不读」的字段，逐项钉住「为什么不接」。
 *
 * 防的是将来有人照着字段清单把效果接上——其中 ripple 官方自己在编译期关掉了，
 * 接上等于做出官方没有的效果。
 */
class ZeroConsumptionVerdictContractTest {

    private val lensRenderer =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodLensRenderer.kt").readText()
    private val preview =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()
    private val worldLayer =
        File("src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt").readText()
    private val catalog =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodSceneCatalog.kt").readText()
    private val motion =
        File("src/main/kotlin/com/coloros16/liquidglass/engine/SystemSceneMotion.kt").readText()

    private val edges = TransitionEdge.entries

    /** ★ripple：渲染器不得有该 uniform，官方 shader 用 ENABLE_RIPPLE=0 编译期剔除。 */
    @Test
    fun rippleMustNotReachShader() {
        assertFalse("渲染器不得出现 uRipple", lensRenderer.contains("uRipple"))
        assertFalse("渲染器不得读 pose.ripple", lensRenderer.contains("pose.ripple"))
        assertFalse("预览不得读 lensPose.ripple", preview.contains("lensPose.ripple"))
        assertFalse("hook 不得读 pose.ripple", worldLayer.contains("pose.ripple"))
    }

    /** ripple 引擎侧仍在算（忠实复刻官方动画层），只是不下发。 */
    @Test
    fun rippleStillEvaluatedInEngine() {
        val pose = SailLensMotion.Pose()
        SailLensMotion.sampleStatic(SceneState.AOD, 0.5f, 0.435f, 1.24f, pose)
        assertEquals("息屏 ripple=0", 0f, pose.ripple, 1e-4f)
        SailLensMotion.sampleStatic(SceneState.LOCK, 0.5f, 0.435f, 1.24f, pose)
        assertEquals("锁屏 ripple=1.24", 1.24f, pose.ripple, 1e-4f)
    }

    /** ★spinSpeed：语义由调用方的自转门实现，渲染器不读该字段。 */
    @Test
    fun spinSpeedHandledByCallerGateNotShader() {
        assertFalse("渲染器不得读 pose.spinSpeed", lensRenderer.contains("pose.spinSpeed"))
        assertTrue("预览必须有自转门", preview.contains("private fun lensSpinActive()"))
        assertTrue("门必须喂给 spin 求值", preview.contains("SailLensMotion.spin("))
    }

    /** spin 求值本身在 speed<=0 时冻结，与官方 advance 的 speed 门等价。 */
    @Test
    fun spinFreezesAtZeroSpeed() {
        val a = -123f
        assertEquals("speed=0 必须冻结", a, SailLensMotion.spin(a, 0.5f, 0f), 1e-6f)
        assertTrue("speed>0 必须继续转", SailLensMotion.spin(a, 0.5f, 1f) < a)
    }

    /** 展开边继续自转是与官方有意的差异（用户要求「转着转着放大归位」）。 */
    @Test
    fun expandEdgesKeepSpinningByDesign() {
        val gate = com.coloros16.liquidglass.engine.AodSpinGate
        assertTrue("展开边必须继续转", gate.active(true, true, SceneState.AOD, TransitionEdge.AOD_TO_LOCK))
        assertTrue("展开边必须继续转", gate.active(true, true, SceneState.AOD, TransitionEdge.AOD_TO_HOME))
        assertFalse("收缩边必冻结", gate.active(true, true, SceneState.LOCK, TransitionEdge.LOCK_TO_AOD))
        assertFalse("进息屏收缩边必冻结", gate.active(true, true, SceneState.HOME, TransitionEdge.HOME_TO_AOD))
    }

    /** expandedScale 是输入量：两侧都调函数，Pose 里的回写是冗余不是缺口。 */
    @Test
    fun expandedScaleIsInputNotOutput() {
        assertTrue("预览必须调函数", preview.contains("SailLensMotion.expandedScale("))
        assertTrue("hook 必须调函数", worldLayer.contains("SailLensMotion.expandedScale("))
        assertFalse("渲染器不读该回写", lensRenderer.contains("pose.expandedScale"))
        assertEquals(1.3f, SailLensMotion.expandedScale(2.33f), 1e-6f)
        assertEquals(1.24f, SailLensMotion.expandedScale(2.17f), 1e-6f)
    }

    /** ★wallpaperRotationDeg 是死字段：六边一处不赋值，恒 0。 */
    @Test
    fun wallpaperRotationIsDeadField() {
        val assignments = Regex("""out\.wallpaperRotationDeg\s*=""").findAll(motion).count()
        assertEquals("六边都不该赋值（壁纸层无旋转真值）", 0, assignments)
        val layers = SystemSceneMotion.Layers()
        for (e in edges) {
            SystemSceneMotion.sample(e, 0.5f, layers)
            assertEquals("边 $e 壁纸旋转恒 0", 0f, layers.wallpaperRotationDeg, 1e-6f)
        }
    }

    /** cameraRotation：三个契约构造点都不声明该轨道 ⇒ 恒 0，不是渲染漏读。 */
    @Test
    fun cameraRotationNeverDeclared() {
        assertFalse("目录不得声明 cameraRotation", catalog.contains("cameraRotation"))
        for (id in listOf("aod.sail", "aod.leave_light", "aod.handpaint")) {
            val spec = com.coloros16.liquidglass.ui.aod.AodSceneCatalog.forStyle(id) ?: continue
            assertTrue("契约 $id 不该有旋转轨", spec.contract.cameraRotation == null)
        }
    }

    /** ★applyScene 整条路径不可达：SceneLayerConfig.enabled 默认 false 且无处置 true。 */
    @Test
    fun sceneLayerPathUnreachable() {
        val config = com.coloros16.liquidglass.engine.SceneLayerConfig()
        assertFalse("默认必须关", config.enabled)
        assertTrue("目录不得声明 scene 层启用", !catalog.contains("SceneLayerConfig(enabled = true)"))
        assertTrue(
            "预览 evaluate 的 sceneOut 必须传 null",
            preview.contains("evaluator.evaluate(e, rawT, screen, capabilities, capabilities, params, null,"),
        )
    }

    /** 预览场景相机走的是另一条路（直接 sample），不经 TransitionParams。 */
    @Test
    fun previewSceneCameraUsesDirectSample() {
        assertTrue("预览必须直接求值", preview.contains("sceneSample.sample(spec.contract, u)"))
        assertFalse("预览不读 params.cameraScale", preview.contains("params.cameraScale"))
        assertFalse("预览不读 params.light", preview.contains("params.light"))
    }

    /**
     * ★天空默认分支没有款能进入（AOD-90 已裁「不可达，不硬改」）。
     *
     * 该分支在预览里没有锁屏↔桌面缩放旁路（`:1157-1170` 直接用 `layers.lockScale`），
     * 与三个已实现族的口径不一致，且**从未在真机验过**。所以闸不该钉「sky=true 计数为 0」
     * （那是现状，启用时合法地要变），而该钉「任何新场景必须属于已实现的渲染族」——
     * 真要接天空/3D，先在这里被迫回答「那条分支验过没有」。
     */
    @Test
    fun everySceneSpecFallsIntoImplementedFamily() {
        val body = funBody(catalog, "forStyle")
        assertTrue("forStyle 抽取为空，判据失效", body.isNotEmpty())
        val calls = Regex("AodSceneSpec\\(").findAll(body).map { callText(body, it.range.first) }.toList()
        assertTrue("forStyle 至少要构造三种场景，实际 ${calls.size}", calls.size >= 3)
        for (call in calls) {
            assertTrue(
                "每个场景必须属于已实现族（lens / handPaintStyle / photo 三者之一）；" +
                    "落到天空默认分支前必须先在真机验过并对齐 lock↔home 缩放口径。实际：$call",
                call.contains("lens = true") || call.contains("handPaintStyle =") || call.contains("photo ="),
            )
        }
        // 阳性对照：裸 SceneSpec（天空）必须被判红
        val sky = funBody(
            "fun forStyle(id: String): AodSceneSpec? {\n" +
                "    return AodSceneSpec(contract = SceneContract(\"aod.sky\"))\n" +
                "}\n",
            "forStyle",
        )
        val skyCall = Regex("AodSceneSpec\\(").findAll(sky).map { callText(sky, it.range.first) }.first()
        assertFalse(
            "阳性对照：裸场景必须被判定为不在已实现族里",
            skyCall.contains("lens = true") || skyCall.contains("handPaintStyle =") || skyCall.contains("photo ="),
        )
    }

    /** 取函数体（按花括号配平）。 */
    private fun funBody(text: String, name: String): String {
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

    /** 取从 `(` 开始的整段调用（配平圆括号）。 */
    private fun callText(text: String, from: Int): String {
        var depth = 0
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return text.substring(from, i + 1) }
            }
            i++
        }
        return text.substring(from)
    }

    /** 阳性对照：若有人把 ripple 接进 shader，检测器必须判红。 */
    @Test
    fun positiveControlWiringRippleIsDetectable() {
        val bad = """shader.setFloatUniform("uRippleRadius", pose.ripple)"""
        assertTrue("阳性样本含 uniform", bad.contains("uRipple"))
        assertTrue("阳性样本读字段", bad.contains("pose.ripple"))
        assertFalse("现渲染器不含", lensRenderer.contains("uRipple"))
    }

    /** 阳性对照：若有人给壁纸旋转赋值，死字段断言必须判红。 */
    @Test
    fun positiveControlAssigningWallpaperRotationIsDetectable() {
        val bad = "out.wallpaperRotationDeg = lerp(0f, 5f, p)"
        assertTrue("阳性样本是赋值", Regex("""out\.wallpaperRotationDeg\s*=""").containsMatchIn(bad))
        assertEquals("现引擎零赋值", 0, Regex("""out\.wallpaperRotationDeg\s*=""").findAll(motion).count())
    }
}
