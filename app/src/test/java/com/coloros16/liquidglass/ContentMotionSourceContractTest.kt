package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ContentLayerMapper
import com.coloros16.liquidglass.engine.ContentMotionSource
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionEvaluator
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.StyleCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内容层动效来源契约：hook 必须消费系统分层真值，锁屏↔桌面抑制缩放，镜片款走内容闸。
 */
class ContentMotionSourceContractTest {

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
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

    private fun layersAt(edge: TransitionEdge, t: Float): SystemSceneMotion.Layers =
        SystemSceneMotion.Layers().also { SystemSceneMotion.sample(edge, t, it) }

    private fun filled(edge: TransitionEdge, t: Float, gate: Float = 1f): ContentLayerMapper.Poses =
        ContentLayerMapper.Poses().also {
            ContentMotionSource.fill(layersAt(edge, t), edge, gate, it)
        }

    /** 旧 hook 行为：只走求值器 src/dst。 */
    private fun oldParams(edge: TransitionEdge, t: Float): ContentLayerMapper.Poses {
        val ev = TransitionEvaluator()
        val p = ev.profileOf(edge)
        p.mask.enabled = true
        p.content.enabled = true
        val params = TransitionParams()
        val caps = StyleCapabilities(mask = true, crossfade = true)
        ev.evaluate(edge, t, ScreenMetrics(1080f, 2400f, 3f), caps, caps, params)
        return ContentLayerMapper.Poses().also { ContentLayerMapper.map(params, it) }
    }

    @Test
    fun chooseLayersWhenPresent() {
        assertEquals(ContentMotionSource.Source.LAYERS, ContentMotionSource.choose(true))
        assertEquals(ContentMotionSource.Source.PARAMS, ContentMotionSource.choose(false))
    }

    @Test
    fun unlockEdgesSuppressScale() {
        assertTrue(ContentMotionSource.suppressScale(TransitionEdge.LOCK_TO_HOME))
        assertTrue(ContentMotionSource.suppressScale(TransitionEdge.HOME_TO_LOCK))
        assertFalse(ContentMotionSource.suppressScale(TransitionEdge.AOD_TO_LOCK))
        assertFalse(ContentMotionSource.suppressScale(null))
    }

    @Test
    fun scaleOfReturnsOneOnSuppressedEdges() {
        assertEquals(1f, ContentMotionSource.scaleOf(1.04f, TransitionEdge.LOCK_TO_HOME), 0f)
        assertEquals(1.04f, ContentMotionSource.scaleOf(1.04f, TransitionEdge.AOD_TO_LOCK), 1e-6f)
    }

    /** 息屏层只取透明度：缩放位移归时钟锚点链，避免乘两次。 */
    @Test
    fun aodLayerTakesAlphaOnly() {
        val out = filled(TransitionEdge.AOD_TO_LOCK, 0.4f)
        assertEquals(layersAt(TransitionEdge.AOD_TO_LOCK, 0.4f).aodAlpha, out.aod.alpha, 1e-6f)
        assertEquals(1f, out.aod.scale, 0f)
        assertEquals(0f, out.aod.dx, 0f)
        assertEquals(0f, out.aod.dy, 0f)
    }

    @Test
    fun lockLayerTakesLayersAlphaAndScale() {
        val edge = TransitionEdge.AOD_TO_LOCK
        val t = 0.4f
        val l = layersAt(edge, t)
        val out = filled(edge, t)
        assertEquals(l.lockAlpha, out.lock.alpha, 1e-6f)
        assertEquals(l.lockScale, out.lock.scale, 1e-6f)
    }

    @Test
    fun contentGateMultipliesLockAndHomeOnly() {
        val edge = TransitionEdge.AOD_TO_LOCK
        val t = 0.7f
        val l = layersAt(edge, t)
        val out = filled(edge, t, gate = 0.5f)
        assertEquals(l.lockAlpha * 0.5f, out.lock.alpha, 1e-6f)
        assertEquals(l.homeAlpha * 0.5f, out.home.alpha, 1e-6f)
        assertEquals("息屏层不受内容闸影响", l.aodAlpha, out.aod.alpha, 1e-6f)
    }

    @Test
    fun gateIsClamped() {
        val out = filled(TransitionEdge.AOD_TO_LOCK, 0.7f, gate = 3f)
        assertEquals(layersAt(TransitionEdge.AOD_TO_LOCK, 0.7f).lockAlpha, out.lock.alpha, 1e-6f)
    }

    /**
     * ★阳性对照：进场内容淡入旧行为走通用 STANDARD 曲线，真值走 CONTENT_IN，前段慢得多。
     *
     * 手算期望：t=0.2 时旧 0.5000、新 0.2282（差 0.27），不是「旧行为恒半亮」。
     */
    @Test
    fun positiveControlContentFadeInDiffersFromOldParams() {
        val edge = TransitionEdge.AOD_TO_LOCK
        val t = 0.2f
        val old = oldParams(edge, t)
        val new = filled(edge, t)
        assertEquals("旧行为按 STANDARD", 0.50f, old.lock.alpha, 0.01f)
        assertEquals("真值按 CONTENT_IN", 0.2282f, new.lock.alpha, 0.01f)
        assertTrue("偏差必须大到可见", old.lock.alpha - new.lock.alpha > 0.2f)
    }

    /** 锁屏退场：真值按 200ms 提前退完，旧行为拖到更晚（差异小但方向确定）。 */
    @Test
    fun lockExitFinishesEarlierUnderTruth() {
        val edge = TransitionEdge.LOCK_TO_AOD
        val new = filled(edge, 0.4f)
        val old = oldParams(edge, 0.4f)
        assertEquals("真值 200/500 已退完", 0f, new.lock.alpha, 1e-6f)
        assertTrue("旧行为此刻仍有残留", old.lock.alpha > 0.02f)
    }

    /** ★阳性对照：锁屏↔桌面旧行为会缩放内容，真值不缩放。 */
    @Test
    fun positiveControlUnlockScaleSuppressedVsOld() {
        val edge = TransitionEdge.LOCK_TO_HOME
        val t = 0.6f
        val l = layersAt(edge, t)
        assertTrue("分层里锁屏确有缩放", kotlin.math.abs(l.lockScale - 1f) > 0.005f)
        assertEquals("但这条边必须抑制", 1f, filled(edge, t).lock.scale, 0f)
    }

    /** ★阳性对照：桌面层旧行为完全不参与 AOD→LOCK，真值也不参与（一致，防治过头）。 */
    @Test
    fun thirdStateStaysIdentityOnBothPaths() {
        val edge = TransitionEdge.AOD_TO_LOCK
        val t = 0.5f
        val old = oldParams(edge, t)
        val new = filled(edge, t)
        assertTrue(old.home.identity)
        assertEquals(0f, new.home.alpha, 1e-6f)
        assertEquals(1f, new.home.scale, 1e-6f)
    }

    /** 息屏进场真值带时钟缩放通道，内容层不得跟着缩放。 */
    @Test
    fun aodEntryScaleNotAppliedToContent() {
        val edge = TransitionEdge.LOCK_TO_AOD
        val l = layersAt(edge, 0.8f)
        assertTrue("分层确有息屏时钟缩放", kotlin.math.abs(l.aodClockScale - 1f) > 0.001f)
        assertEquals("内容层缩放仍为原样", 1f, filled(edge, 0.8f).aod.scale, 0f)
    }

    @Test
    fun hookConsumesLayers() {
        val s = body(stage(), "applyContentLayers")
        assertTrue("必须走来源选择", s.contains("ContentMotionSource.choose(layers != null, hasWorld)"))
        assertTrue("真值分支必须填分层", s.contains("ContentMotionSource.fill(layers!!, edge"))
        assertTrue("兜底分支保留", s.contains("ContentLayerMapper.map(params, contentPoses)"))
    }

    @Test
    fun hookPassesLayersAtCallSite() {
        assertTrue(stage().contains("applyContentLayers(layer, params, sceneLayers, edge)"))
    }

    @Test
    fun hookGateOnlyForLens() {
        val s = body(stage(), "contentGateNow")
        assertTrue("只有镜片款压内容", s.contains("if (!spec.lens) return 1f"))
        assertTrue("走同一份交叠真值", s.contains("SailLensMotion.fade(edge, t, worldFade)"))
        assertTrue(s.contains("worldFade.contentGate"))
    }

    /**
     * 预览侧有窗口裁切的两个分支都删 gate，改由几何承担进出场；无窗口的手绘仍用 gate。
     *
     * 两侧不是同口径而是**各自正确**：hook 内容层由宿主容器驱动无裁切能力，
     * 预览有 clipPath 故可用几何替代透明度。
     */
    @Test
    fun previewUsesSameGateAndSuppression() {
        val p = preview()
        assertTrue("有窗口的分支必须删 gate", p.contains("contentGate = 1f"))
        assertFalse("预览不得再赋交叠闸", p.contains("contentGate = sailFade.contentGate"))
        assertTrue(p.contains("val scale = if (lockHome) 1f else layers.lockScale"))
        assertTrue(p.contains("val scale = if (lockHome) 1f else layers.homeScale"))
        val photo = p.substring(p.indexOf("private fun drawPhotoScene"), p.indexOf("private fun captureRevealTarget"))
        assertTrue("照片分支不得再读交叠真值", !photo.contains("sailFade"))
        assertTrue("照片分支必须有裁切承担进出场", photo.contains("ContentWindow.photo("))
        val sail = p.substring(p.indexOf("private fun drawSailScene"), p.indexOf("private fun drawLensCurtain"))
        assertTrue("镜片分支必须有圆窗", sail.contains("ContentWindow.lens("))
        assertTrue("镜片分支仍用 sailFade 供息屏层", sail.contains("sailFade.aodAlpha"))
        assertTrue("手绘无窗口仍按 gate 走", p.contains("layers.lockAlpha * contentGate"))
    }

    @Test
    fun sourceIsLogged() {
        val s = stage()
        assertTrue("来源必须可分辨", s.contains("stage-content-src"))
        assertTrue(s.contains("\"layers\""))
        assertTrue(s.contains("\"params\""))
    }

    /** 静止态仍交回系统：不得因为接了真值就在静止态写内容层。 */
    @Test
    fun staticStillReleasesContent() {
        val s = body(stage(), "applyStatic")
        assertTrue(s.contains("releaseContentLayers(layer, keepClocks = true)"))
        assertFalse("静止态不走内容写入", s.contains("applyContentLayers"))
    }

    /** 镜片交叠真值本身：入场内容闸从 0 升起，出场降到 0。 */
    @Test
    fun lensGateTruthEndpoints() {
        val f = SailLensMotion.Fade()
        SailLensMotion.fade(TransitionEdge.AOD_TO_LOCK, 0f, f)
        assertEquals(0f, f.contentGate, 1e-6f)
        SailLensMotion.fade(TransitionEdge.AOD_TO_LOCK, 1f, f)
        assertEquals(1f, f.contentGate, 1e-6f)
        SailLensMotion.fade(TransitionEdge.LOCK_TO_AOD, 1f, f)
        assertEquals(0f, f.contentGate, 1e-6f)
    }

    /** 非镜片款 contentGate 恒 1：分层值不被无谓压暗。 */
    @Test
    fun nonLensStylesKeepFullAlpha() {
        val edge = TransitionEdge.AOD_TO_LOCK
        val t = 0.3f
        assertEquals(
            layersAt(edge, t).lockAlpha,
            filled(edge, t, gate = 1f).lock.alpha,
            1e-6f,
        )
    }

    /** 分层中性也是有效真值，不能因为「看着没动」就退回 params。 */
    @Test
    fun neutralLayersStillCountAsTruth() {
        val out = ContentLayerMapper.Poses()
        val l = SystemSceneMotion.Layers().also { SystemSceneMotion.sampleStatic(SceneState.HOME, it) }
        ContentMotionSource.fill(l, null, 1f, out)
        assertEquals(0f, out.aod.alpha, 1e-6f)
        assertEquals(1f, out.home.alpha, 1e-6f)
        assertEquals(ContentMotionSource.Source.LAYERS, ContentMotionSource.choose(true))
    }
}
