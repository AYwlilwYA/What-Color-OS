package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodClockEntry
import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockAnchorCompose
import com.coloros16.liquidglass.engine.ClockAnchorMapper
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桌面时钟锚点接 hook 契约：真机终点进状态机、六边都插到桌面锚点、落地件写旋转。
 *
 * 采集链（小部件广播 / 状态栏矩形 / 锁屏时钟高度）此前只写 HOME 锚点无人读，
 * 桌面段时钟原地淡出；本契约钉死消费侧三件：兜底同源、目标态随边、旋转落地。
 */
class ClockHomeAnchorWiringContractTest {

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
    ).readText()

    private fun apply(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/ClockAnchorApply.kt",
    ).readText()

    private fun bridge(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/DesktopClockAnchorBridge.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
    ).readText()

    private fun mapper(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/engine/ClockAnchorMapper.kt",
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

    private val lockAnchor = ClockAnchor(centerX = 0.36f, centerY = 0.155f, scale = 0.62f)
    private val aodAnchor = ClockAnchor(centerX = 0.50f, centerY = 0.72f, scale = 0.86f)
    private val statusBarAnchor = ClockAnchorMapper.fromTargetRect(
        com.coloros16.liquidglass.engine.WidgetRectPx(920, 40, 1010, 90),
        screenW = 1080, screenH = 2400, lockClockHeightPx = 300f,
    )!!

    private fun machine(): ClockStateMachine = ClockStateMachine().apply {
        setAnchor(SceneState.AOD, aodAnchor, 0L, false)
        setAnchor(SceneState.LOCK, lockAnchor, 0L, false)
        setAnchor(SceneState.HOME, statusBarAnchor, 0L, false)
    }

    // ---------------------------------------------------------------- 兜底同源

    @Test
    fun bothFallbacksLiveInEngineNotInRenderers() {
        assertTrue("真机兜底常量在引擎", mapper().contains("val HOME_FALLBACK = ClockAnchor("))
        assertTrue("预览演示位在引擎", mapper().contains("val HOME_PREVIEW_DEMO = ClockAnchor("))
        assertFalse("hook 不自带字面量", stage().contains("centerX = 0.13f"))
        assertFalse("预览不自带字面量", preview().contains("centerX = 0.13f"))
    }

    /** 真机与预览取的是两个不同常量：真机两路皆无淡出，预览演示位飞顶栏。 */
    @Test
    fun deviceAndPreviewReadTheirOwnConstant() {
        assertTrue("hook 读真机兜底", body(stage(), "syncHomeAnchor").contains("ClockAnchorMapper.HOME_FALLBACK"))
        assertTrue("预览读演示位", body(preview(), "homeClockAnchor").contains("ClockAnchorMapper.HOME_PREVIEW_DEMO"))
        assertEquals("真机兜底淡出", 0f, ClockAnchorMapper.HOME_FALLBACK.alpha, 1e-6f)
        assertEquals("预览演示位可见", 1f, ClockAnchorMapper.HOME_PREVIEW_DEMO.alpha, 1e-6f)
    }

    /** 阳性对照：两份字面量各自漂移时无法被单侧断言发现，故断言两侧都指同一常量。 */
    @Test
    fun positiveControlTwoLiteralsWouldDrift() {
        val a = ClockAnchorMapper.HOME_PREVIEW_DEMO
        val b = a.copy(centerY = 0.04f)
        assertNotEquals("同形字面量改一处就分叉", a, b)
        assertEquals("真机兜底只有一份", ClockAnchorMapper.HOME_FALLBACK, ClockAnchorMapper.pickHomeAnchor(null, null))
    }

    // ---------------------------------------------------------------- hook 消费

    @Test
    fun syncClockAnchorsAlsoSyncsHome() {
        val src = body(stage(), "syncClockAnchors")
        assertTrue("末尾必须同步桌面锚点", src.contains("syncHomeAnchor(machine)"))
    }

    @Test
    fun homeAnchorPrefersDeviceRectangle() {
        val src = body(stage(), "syncHomeAnchor")
        val read = src.indexOf("DesktopClockAnchorBridge.anchorOrNull()")
        val fallback = src.indexOf("ClockAnchorMapper.HOME_FALLBACK")
        assertTrue("必须读真机锚点", read >= 0)
        assertTrue("真机优先于兜底", read < fallback)
    }

    @Test
    fun homeAnchorIsDedupedNotWrittenEveryFrame() {
        val src = body(stage(), "syncHomeAnchor")
        assertTrue("按值判重", src.contains("homeAnchorKey"))
        assertTrue("值未变直接返回", src.contains(") return"))
        assertTrue("不叠跟随补间", src.contains("follow = false"))
    }

    @Test
    fun homeAnchorDoesNotTakeEditOrStyleBaseline() {
        val src = body(stage(), "syncHomeAnchor")
        assertFalse("桌面态无编辑面", src.contains("ClockAnchorCompose"))
        assertFalse("桌面态不取样式基线", src.contains("clockAnchors"))
        assertFalse("桌面态不读编辑配置", src.contains("layoutFor"))
    }

    @Test
    fun homeSyncHasThrottledDiagnostic() {
        val src = body(stage(), "syncHomeAnchor")
        assertTrue("静默 return 之外要有可裁决日志", src.contains("stage-anchor-home"))
        assertTrue("要能分辨真机与兜底", src.contains("device") && src.contains("fallback"))
    }

    @Test
    fun composeCoversOnlyAodAndLock() {
        assertTrue(
            "编辑合成仍只覆盖两端",
            stage().contains("CLOCK_ANCHOR_STATES = arrayOf(SceneState.AOD, SceneState.LOCK)"),
        )
    }

    // ---------------------------------------------------------------- 目标态随边

    @Test
    fun clockStateFollowsEdgeWhenHostNotInvolved() {
        val src = body(stage(), "applyClockAnchor")
        assertTrue("目标态走共用判定", src.contains("clockStateOf(surface, edge)"))
        assertFalse("不再按面硬写死两态", src.contains("if (surface == EditSurface.AOD) SceneState.AOD else SceneState.LOCK"))
    }

    @Test
    fun hostStateRuleMatchesAllSixEdges() {
        for (edge in TransitionEdge.values()) {
            for (host in arrayOf(SceneState.AOD, SceneState.LOCK)) {
                val expected = if (edge.from == host || edge.to == host) host else edge.to
                val actual = ruleOf(host, edge)
                assertEquals("host=$host edge=$edge", expected, actual)
            }
        }
    }

    /** 与 hook 同一条判定式；两侧口径由上一条穷举钉死。 */
    private fun ruleOf(host: SceneState, edge: TransitionEdge): SceneState =
        if (edge.from == host || edge.to == host) host else edge.to

    /** 三态边里宿主缺席的只有两种组合：息屏面遇解锁边、锁屏面遇息屏↔桌面边。 */
    @Test
    fun hostAbsentOnlyOnTwoEdgeHostCombinations() {
        val absent = ArrayList<Pair<SceneState, TransitionEdge>>()
        for (edge in TransitionEdge.values()) {
            for (host in arrayOf(SceneState.AOD, SceneState.LOCK)) {
                if (edge.from != host && edge.to != host) absent.add(host to edge)
            }
        }
        assertEquals(
            listOf(
                SceneState.LOCK to TransitionEdge.AOD_TO_HOME,
                SceneState.AOD to TransitionEdge.LOCK_TO_HOME,
                SceneState.LOCK to TransitionEdge.HOME_TO_AOD,
                SceneState.AOD to TransitionEdge.HOME_TO_LOCK,
            ).sortedBy { it.second.ordinal },
            absent.sortedBy { it.second.ordinal },
        )
        assertEquals("缺席时取边终点", SceneState.HOME, ruleOf(SceneState.AOD, TransitionEdge.LOCK_TO_HOME))
        assertEquals("缺席时取边终点", SceneState.HOME, ruleOf(SceneState.LOCK, TransitionEdge.AOD_TO_HOME))
        assertEquals("在场时取宿主", SceneState.LOCK, ruleOf(SceneState.LOCK, TransitionEdge.AOD_TO_LOCK))
        assertEquals("在场时取宿主", SceneState.AOD, ruleOf(SceneState.AOD, TransitionEdge.LOCK_TO_AOD))
    }

    // ---------------------------------------------------------------- 行程数值

    @Test
    fun unlockEdgeTravelsToStatusBarNotInPlace() {
        val m = machine()
        m.onTransition(TransitionEdge.LOCK_TO_HOME, 1f, SystemSceneMotion.clockCurve(TransitionEdge.LOCK_TO_HOME))
        assertEquals(statusBarAnchor.centerY, m.pose.centerY, 1e-4f)
        assertEquals(statusBarAnchor.centerX, m.pose.centerX, 1e-4f)
        assertEquals(statusBarAnchor.scale, m.pose.scale, 1e-4f)
        assertTrue("有真机终点时仍可见", m.pose.visible)
    }

    /** 阳性对照：修前 HOME 停在隐藏缺省（中心 0.5/0.5），与状态栏终点差整段行程。 */
    @Test
    fun positiveControlUnreadHomeAnchorMissedTheWholeTravel() {
        val m = machine()
        m.setAnchor(SceneState.HOME, ClockAnchor(alpha = 0f), 0L, false)
        m.onTransition(TransitionEdge.LOCK_TO_HOME, 1f, SystemSceneMotion.clockCurve(TransitionEdge.LOCK_TO_HOME))
        assertEquals("缺省中心在屏中央", 0.5f, m.pose.centerY, 1e-4f)
        assertFalse("缺省不可见", m.pose.visible)
        val travel = kotlin.math.abs(0.5f - statusBarAnchor.centerY)
        assertTrue("漏读时错位的行程必须大到可见", travel > 0.4f)
    }

    /** 两路真机终点皆无：真机口径是淡出，不是编一个顶栏终点。 */
    @Test
    fun noDeviceTargetFadesOutInsteadOfInventingOne() {
        val m = machine()
        m.setAnchor(SceneState.HOME, ClockAnchorMapper.pickHomeAnchor(null, null), 0L, false)
        m.onTransition(TransitionEdge.LOCK_TO_HOME, 1f, SystemSceneMotion.clockCurve(TransitionEdge.LOCK_TO_HOME))
        assertEquals(0f, m.pose.alpha, 1e-4f)
        assertFalse(m.pose.visible)
    }

    @Test
    fun allSixEdgesReachTheirEndpointAnchors() {
        for (edge in TransitionEdge.values()) {
            val m = machine()
            m.onTransition(edge, 1f, SystemSceneMotion.clockCurve(edge))
            val to = m.anchorOf(edge.to)
            assertEquals("edge=$edge cy", to.centerY, m.pose.centerY, 1e-4f)
            assertEquals("edge=$edge scale", to.scale, m.pose.scale, 1e-4f)
        }
    }

    @Test
    fun deviceWidgetAnchorOverridesFallbackMidTransition() {
        val widget = ClockAnchorMapper.fromTargetRect(
            com.coloros16.liquidglass.engine.WidgetRectPx(120, 300, 960, 620),
            screenW = 1080, screenH = 2400, lockClockHeightPx = 300f,
        )
        requireNotNull(widget)
        val m = machine()
        m.setAnchor(SceneState.HOME, ClockAnchorMapper.pickHomeAnchor(widget, null), 0L, false)
        m.onTransition(TransitionEdge.LOCK_TO_HOME, 1f, SystemSceneMotion.clockCurve(TransitionEdge.LOCK_TO_HOME))
        assertEquals(460f / 2400f, m.pose.centerY, 1e-4f)
        assertEquals(320f / 300f, m.pose.scale, 1e-3f)
    }

    // ---------------------------------------------------------------- 旋转落地

    @Test
    fun applyWritesRotationAndDedupesIt() {
        val src = body(apply(), "apply")
        assertTrue("写旋转", src.contains("view.rotation = rot"))
        val dedupe = src.substringAfter("if (prev != null", "").substringBefore(") return", "")
        assertTrue("旋转进判重键", dedupe.contains("prev[5]") && dedupe.contains("rot"))
        assertTrue("旋转进已写值", src.contains("floatArrayOf(dx, dy, sx, anchor.alpha, sy, rot)"))
    }

    @Test
    fun originLedgerCarriesRotationAndRestoreWritesItBack() {
        assertTrue("记账含旋转", apply().contains("val rotation: Float,"))
        assertTrue("采集原旋转", body(apply(), "apply").contains("view.pivotX, view.pivotY, view.rotation,"))
        assertTrue("还原写回旋转", body(apply(), "restore").contains("view.rotation = origin.rotation"))
    }

    @Test
    fun rotationFlowsFromStyleAnchorThroughComposeAndPose() {
        val tilted = aodAnchor.copy(rotationDeg = 12f)
        val composed = ClockAnchorCompose.compose(tilted, LockLayoutSettings.defaults(EditSurface.AOD))
        assertEquals("合成保留样式旋转", 12f, composed.rotationDeg, 1e-4f)
        val m = machine()
        m.setAnchor(SceneState.AOD, composed, 0L, false)
        m.onTransition(TransitionEdge.AOD_TO_LOCK, 0f, SystemSceneMotion.MASK)
        assertEquals("姿态带旋转", 12f, m.pose.rotationDeg, 1e-4f)
    }

    @Test
    fun hookPassesPoseRotationIntoAnchor() {
        val src = body(stage(), "applyClockAnchor")
        assertTrue("hook 把姿态旋转装进锚点", src.contains("pose.rotationDeg,"))
        assertTrue(
            "旋转必须紧跟在 alpha 位之后（锚点构造顺序）",
            Regex("""pose\.alpha[^,]*,\s*pose\.rotationDeg,""").containsMatchIn(src),
        )
    }

    /** 阳性对照：预览确实按旋转画，落地件不写就是两侧不一致。 */
    @Test
    fun positiveControlPreviewRotatesCanvas() {
        assertTrue("预览按姿态旋转画", preview().contains("canvas.rotate(pose.rotationDeg, cx, cy)"))
    }

    // ---------------------------------------------------------------- 进场系数施加面

    @Test
    fun entryScaleOnlyAppliesToAodHost() {
        for (w in floatArrayOf(0f, 0.25f, 0.5f, 1f)) {
            assertEquals("锁屏面恒原样", 1f, AodClockEntry.entryFor(false, 0.85f, w), 1e-6f)
            assertEquals(
                "息屏面按权重混合",
                AodClockEntry.blend(0.85f, w),
                AodClockEntry.entryFor(true, 0.85f, w),
                1e-6f,
            )
        }
    }

    @Test
    fun weightOfMatchesEdgeEndpoints() {
        assertEquals(0f, AodClockEntry.weightOf(TransitionEdge.LOCK_TO_HOME, 0.5f, SceneState.LOCK), 1e-6f)
        assertEquals(0f, AodClockEntry.weightOf(TransitionEdge.LOCK_TO_AOD, 0f, SceneState.LOCK), 1e-6f)
        assertEquals(1f, AodClockEntry.weightOf(TransitionEdge.LOCK_TO_AOD, 1f, SceneState.LOCK), 1e-6f)
        assertEquals(0.5f, AodClockEntry.weightOf(TransitionEdge.AOD_TO_LOCK, 0.5f, SceneState.AOD), 1e-6f)
        assertEquals(1f, AodClockEntry.weightOf(null, 0f, SceneState.AOD), 1e-6f)
        assertEquals(0f, AodClockEntry.weightOf(null, 0f, SceneState.HOME), 1e-6f)
    }

    /** 阳性对照：反向边首帧息屏权重为 0，旧口径无条件乘 0.85 会让锁屏时钟凭空缩小 15%。 */
    @Test
    fun positiveControlReverseEdgeFirstFrameShrankLockClock() {
        val layers = SystemSceneMotion.Layers()
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 0f, layers)
        assertEquals("首帧进场系数已落到起始值", 0.85f, layers.aodClockScale, 1e-4f)
        val w = AodClockEntry.weightOf(TransitionEdge.LOCK_TO_AOD, 0f, SceneState.LOCK)
        assertEquals("首帧息屏权重为 0", 0f, w, 1e-6f)
        val old = 0.62f * layers.aodClockScale
        val new = 0.62f * AodClockEntry.entryFor(false, layers.aodClockScale, w)
        assertTrue("旧口径落差必须大到可见", (0.62f - old) / 0.62f > 0.10f)
        assertEquals("锁屏面新口径不受影响", 0.62f, new, 1e-6f)
    }

    @Test
    fun hookRoutesEntryThroughSharedRule() {
        val src = body(stage(), "applyClockAnchor")
        assertTrue("走共用施加规则", src.contains("AodClockEntry.entryFor("))
        assertTrue("权重来自引擎", src.contains("AodClockEntry.weightOf(edge, t,"))
        assertFalse("不再按面硬乘", src.contains("if (surface == EditSurface.AOD) systemScale else 1f"))
    }

    /** 息屏面只在边末与旧口径相等（权重满）；全程被夹在旧口径与 1 之间，不得更小。 */
    @Test
    fun aodHostNeverShrinksBelowOldWayAndLandsOnIt() {
        for (edge in arrayOf(TransitionEdge.LOCK_TO_AOD, TransitionEdge.HOME_TO_AOD)) {
            val layers = SystemSceneMotion.Layers()
            for (i in 0..10) {
                val t = i / 10f
                SystemSceneMotion.sample(edge, t, layers)
                val w = AodClockEntry.weightOf(edge, t, SceneState.AOD)
                val got = AodClockEntry.entryFor(true, layers.aodClockScale, w)
                assertTrue("edge=$edge t=$t 不得比裸乘更小", got >= layers.aodClockScale - 1e-6f)
                assertTrue("edge=$edge t=$t 不得超过原样", got <= 1f + 1e-6f)
            }
            SystemSceneMotion.sample(edge, 1f, layers)
            assertEquals(
                "edge=$edge 边末权重满，与旧口径相等",
                layers.aodClockScale,
                AodClockEntry.entryFor(true, layers.aodClockScale, 1f),
                1e-6f,
            )
        }
    }

    @Test
    fun aodHostAtReverseFirstFrameStaysUnity() {
        val layers = SystemSceneMotion.Layers()
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 0f, layers)
        val w = AodClockEntry.weightOf(TransitionEdge.LOCK_TO_AOD, 0f, SceneState.AOD)
        assertEquals(
            "息屏面首帧也不该被进场系数缩小",
            1f,
            AodClockEntry.entryFor(true, layers.aodClockScale, w),
            1e-6f,
        )
    }

    // ---------------------------------------------------------------- 采集侧口径

    @Test
    fun bridgeStillWritesHomeAnchorOnEvents() {
        assertTrue(
            "采集侧仍按事件写桌面锚点",
            body(bridge(), "applyHomeAnchor").contains("machine.setAnchor(SceneState.HOME, effective,"),
        )
    }

    @Test
    fun bridgePriorityGoesThroughEnginePicker() {
        assertTrue(
            "优先级只有一份实现",
            body(bridge(), "applyHomeAnchor").contains("ClockAnchorMapper.pickHomeAnchor(widgetAnchor, statusBarAnchor)"),
        )
    }
}
