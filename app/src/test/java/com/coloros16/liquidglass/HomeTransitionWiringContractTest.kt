package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.HomeTransitionOverride
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SlideDirection
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectParams
import com.coloros16.liquidglass.engine.effect.EffectSettings
import com.coloros16.liquidglass.engine.effect.HomeMotion
import com.coloros16.liquidglass.engine.to
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桌面过渡选项接预览契约：默认不接管保持接缝口径，用户主动选才覆盖，时长同轮生效。
 *
 * 需求 requirements:724「样式：负责选择锁屏到桌面过渡！…变化效果：默认（不做接管），淡出淡入」。
 * 与 requirements:765「接缝处不能用淡入淡出」不冲突：后者管默认观感，本选项默认 NONE。
 */
class HomeTransitionWiringContractTest {

    private val preview =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()
    private val catalog =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodSceneCatalog.kt").readText()
    private val controller =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodDisplayController.kt").readText()

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

    /** ★默认必须是「不接管」，这是与用户禁淡入淡出令不冲突的前提。 */
    @Test
    fun defaultIsNoTakeover() {
        assertEquals(EffectCatalog.HOME_TRANSITION_NONE, EffectSettings().homeTransition)
        assertNull("默认必须无配方", HomeMotion.profileFor(EffectSettings().homeTransition))
        assertTrue(
            "预览字段默认必须 NONE",
            preview.contains("var homeTransition: String = EffectCatalog.HOME_TRANSITION_NONE"),
        )
    }

    /** 用户主动选淡出淡入必须能拿到配方（否则接了也白接）。 */
    @Test
    fun crossfadeProducesProfile() {
        val p = HomeMotion.profileFor(EffectCatalog.HOME_TRANSITION_CROSSFADE)
        assertNotNull("选了必须有配方", p)
        assertTrue("内容层开", p!!.content.enabled)
        assertFalse("遮罩关", p.mask.enabled)
        assertFalse("场景层关", p.scene.enabled)
        assertEquals("crossfade 无位移", SlideDirection.NONE, p.content.direction)
        assertEquals("crossfade 无行程", 0f, p.content.slideFraction, 1e-6f)
    }

    /** ★阳性对照：crossfade 与接缝推移必须是可分辨的两种配方。 */
    @Test
    fun positiveControlCrossfadeDiffersFromSeamSlide() {
        val cross = HomeMotion.profileFor(EffectCatalog.HOME_TRANSITION_CROSSFADE)!!
        val seam = com.coloros16.liquidglass.engine.TransitionProfile(
            scene = com.coloros16.liquidglass.engine.SceneLayerConfig(enabled = false),
            mask = com.coloros16.liquidglass.engine.MaskLayerConfig(enabled = false),
            content = com.coloros16.liquidglass.engine.ContentLayerConfig(enabled = false),
        )
        com.coloros16.liquidglass.engine.SeamProfilePolicy.apply(TransitionEdge.LOCK_TO_HOME, seam)
        assertTrue("接缝有位移", seam.content.direction != SlideDirection.NONE)
        assertEquals("接缝行程 0.22", 0.22f, seam.content.slideFraction, 1e-6f)
        assertTrue(
            "两者方向必须不同，否则检测器分辨不出覆盖是否生效",
            cross.content.direction != seam.content.direction,
        )
    }

    /** ★作用域只锁屏↔桌面两条边：AOD↔HOME 按用户令用息屏样式，不受此选项控制。 */
    @Test
    fun scopeIsOnlyLockHomeEdges() {
        assertTrue("作用域必须走共用件", preview.contains("HomeTransitionOverride.governs(e)"))
        assertTrue("共用件管 LOCK_TO_HOME", HomeTransitionOverride.governs(TransitionEdge.LOCK_TO_HOME))
        assertTrue("共用件管 HOME_TO_LOCK", HomeTransitionOverride.governs(TransitionEdge.HOME_TO_LOCK))
        assertFalse("不得管 AOD_TO_HOME", HomeTransitionOverride.governs(TransitionEdge.AOD_TO_HOME))
        assertFalse("不得管 HOME_TO_AOD", HomeTransitionOverride.governs(TransitionEdge.HOME_TO_AOD))
        assertFalse("不得管 AOD_TO_LOCK", HomeTransitionOverride.governs(TransitionEdge.AOD_TO_LOCK))
        assertFalse("不得管 LOCK_TO_AOD", HomeTransitionOverride.governs(TransitionEdge.LOCK_TO_AOD))
        assertEquals(SceneState.HOME, TransitionEdge.LOCK_TO_HOME.to)
        assertEquals(SceneState.LOCK, TransitionEdge.HOME_TO_LOCK.to)
    }

    /** ★4.11：不得在预览侧另写一套覆盖策略，必须复用 hook 侧同一份共用件。 */
    @Test
    fun mustReuseSharedOverrideNotReimplement() {
        val b = body(preview, "applyHomeTransition")
        assertTrue("配方走共用件", b.contains("HomeTransitionOverride.profileFor(e, settings)"))
        assertTrue("作用域走共用件", b.contains("HomeTransitionOverride.governs(e)"))
        assertFalse("不得自造作用域常量", preview.contains("HOME_TRANSITION_EDGES"))
        assertFalse("不得直接调 HomeMotion.profileFor", b.contains("HomeMotion.profileFor("))
        assertTrue("时长也走共用件", catalog.contains("HomeTransitionOverride.durationMs(edge, settings)"))
        assertFalse("目录不得自判作用域", catalog.contains("edge == TransitionEdge.LOCK_TO_HOME ||"))
    }

    /** ★不得把 transitionForEdge 的返回直接喂 profileFor：AOD_TO_HOME 返回的是样式 id 不是过渡 id。 */
    @Test
    fun mustNotFeedStyleIdIntoProfileFor() {
        val s = EffectSettings()
        s.aodStyleId = "aod.sail"
        s.homeTransition = EffectCatalog.HOME_TRANSITION_CROSSFADE
        val viaEdge = HomeMotion.transitionForEdge(TransitionEdge.AOD_TO_HOME, s)
        assertEquals("该边返回样式 id", "aod.sail", viaEdge)
        assertNull("样式 id 喂进 profileFor 恒 null ⇒ 静默失效", HomeMotion.profileFor(viaEdge))
        assertFalse(
            "预览不得走 transitionForEdge（会踩这个坑）",
            body(preview, "applyHomeTransition").contains("transitionForEdge"),
        )
    }

    /** 覆盖逻辑：有配方用配方，无配方回落原分流（SCENE 交叠 / 非 SCENE 接缝）。 */
    @Test
    fun overrideFallsBackToSeamWhenNone() {
        val b = body(preview, "applyHomeTransition")
        assertTrue("必须调共用件", b.contains("HomeTransitionOverride.profileFor(e, settings)"))
        assertTrue("必须保留 SCENE 分流", b.contains("AodSceneCatalog.forStyle(styleId) != null"))
        assertTrue("无配方回落", b.contains("evaluator.setProfile(e, profile ?: base)"))
        assertTrue("非受控边跳过", b.contains("if (!HomeTransitionOverride.governs(e)) continue"))
        val s = EffectSettings()
        assertNull("默认六边全 null", HomeTransitionOverride.profileFor(TransitionEdge.LOCK_TO_HOME, s))
        s.homeTransition = EffectCatalog.HOME_TRANSITION_CROSSFADE
        assertNotNull("选后受控边有配方", HomeTransitionOverride.profileFor(TransitionEdge.LOCK_TO_HOME, s))
        assertNull("选后 AOD_TO_HOME 仍 null", HomeTransitionOverride.profileFor(TransitionEdge.AOD_TO_HOME, s))
    }

    /** ★顺序：覆盖必须在基础分流之后，否则被 applyProfiles 的循环盖回去。 */
    @Test
    fun overrideRunsAfterBaseProfiles() {
        val b = body(preview, "applyProfiles")
        val baseLoop = b.indexOf("for (e in SEAM_EDGES)")
        val override = b.indexOf("applyHomeTransition()")
        assertTrue("基础分流必须在", baseLoop >= 0)
        assertTrue("覆盖调用必须在", override >= 0)
        assertTrue("覆盖必须在基础分流之后（实测 base=$baseLoop override=$override）", override > baseLoop)
    }

    /** 阳性对照：若覆盖写在基础循环之前，六边循环会把两条边改回接缝配方。 */
    @Test
    fun positiveControlOverrideBeforeBaseIsSwallowed() {
        val legacy = """
            applyHomeTransition()
            for (e in SEAM_EDGES) { evaluator.setProfile(e, seamProfiles.getValue(e)) }
        """.trimIndent()
        assertTrue(
            "旧序里覆盖在前",
            legacy.indexOf("applyHomeTransition") < legacy.indexOf("for (e in SEAM_EDGES)"),
        )
        assertTrue("SEAM_EDGES 含这两条边 ⇒ 必被盖回", preview.contains("TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK"))
    }

    /** 换样式与改选项两条入口都必须重新覆盖（否则改选项不生效）。 */
    @Test
    fun bothEntryPointsReapply() {
        assertTrue("换样式链必须调", body(preview, "applyProfiles").contains("applyHomeTransition()"))
        val setter = preview.substring(
            preview.indexOf("var homeTransition: String"),
            preview.indexOf("var homeTransitionDurationMs"),
        )
        assertTrue("选项 setter 必须调", setter.contains("applyHomeTransition()"))
        assertTrue("选项 setter 必须重绘", setter.contains("invalidate()"))
    }

    /** ★时长必须同轮接：只接配方 = 效果对了节奏还是系统时长（半接状态）。 */
    @Test
    fun durationIsWiredNotOnlyProfile() {
        assertTrue("必须有 settings 重载", catalog.contains("edge: TransitionEdge, settings: EffectSettings"))
        assertTrue("时长走共用件", catalog.contains("HomeTransitionOverride.durationMs(edge, settings)"))
        assertTrue("覆盖有效才用", catalog.contains("if (override > 0L) return override"))
        assertTrue("不接管回落样式真值", catalog.contains("return durationOf(styleId, edge)"))
        assertTrue("控制器必须传 settings", controller.contains("AodSceneCatalog.durationOf(draftId, edge, effects)"))
        val s = EffectSettings()
        assertEquals("默认交回原时长", 0L, HomeTransitionOverride.durationMs(TransitionEdge.LOCK_TO_HOME, s))
        s.homeTransition = EffectCatalog.HOME_TRANSITION_CROSSFADE
        assertEquals(
            "选后用参数默认 500",
            500L, HomeTransitionOverride.durationMs(TransitionEdge.LOCK_TO_HOME, s),
        )
        assertEquals(
            "AOD_TO_HOME 恒交回",
            0L, HomeTransitionOverride.durationMs(TransitionEdge.AOD_TO_HOME, s),
        )
        s.setParam(EffectParams.HOME_TRANSITION_DURATION, 900f)
        assertEquals(
            "调参数必须跟着变",
            900L, HomeTransitionOverride.durationMs(TransitionEdge.LOCK_TO_HOME, s),
        )
    }

    /** 阳性对照：不接时长时同一选项下时长恒等于样式真值，检测器要能分辨。 */
    @Test
    fun positiveControlWithoutDurationWiringValueNeverChanges() {
        val base = SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME)
        assertEquals("系统真值 800ms", 800L, base)
        val param = EffectParams.all.first { it.id == EffectParams.HOME_TRANSITION_DURATION }
        assertEquals("参数默认 500ms", 500f, param.default, 1e-6f)
        assertTrue("默认参数与系统真值必须不同，否则接没接看不出来", param.default.toLong() != base)
        assertEquals("参数下限 200", 200f, param.min, 1e-6f)
        assertEquals("参数上限 1200", 1200f, param.max, 1e-6f)
    }

    /** 时长口径：共用件用 0 表示「交回原时长」，故负值钳到 0 而不是 1。 */
    @Test
    fun durationUsesZeroAsHandbackSentinel() {
        val s = EffectSettings()
        s.homeTransition = EffectCatalog.HOME_TRANSITION_CROSSFADE
        s.setParam(EffectParams.HOME_TRANSITION_DURATION, 200f)
        assertEquals("下限 200 生效", 200L, HomeTransitionOverride.durationMs(TransitionEdge.LOCK_TO_HOME, s))
        assertEquals("0 是交回哨兵不是合法时长", 0L, HomeTransitionOverride.durationMs(TransitionEdge.AOD_TO_HOME, s))
        assertTrue("目录按 >0 判覆盖", catalog.contains("if (override > 0L) return override"))
        assertTrue("参数表下限 200 ⇒ 不会出现 0 时长", EffectParams.find(EffectParams.HOME_TRANSITION_DURATION)!!.min >= 200f)
    }

    /** 控制器必须把选项与时长都喂给预览（漏一个就是半接）。 */
    @Test
    fun controllerFeedsBothFields() {
        assertTrue("喂选项", controller.contains("preview.homeTransition = effects.homeTransition"))
        assertTrue(
            "喂时长",
            controller.contains("preview.homeTransitionDurationMs ="),
        )
        val feed = controller.indexOf("preview.homeTransition =")
        val icon = controller.indexOf("preview.homeIconEffect =")
        assertTrue("两者都在 updateUi 同批", feed >= 0 && icon > feed)
    }

    /** 新链必须配诊断日志，否则「选了没反应」定位不了。 */
    @Test
    fun hasDiagnosticLog() {
        assertTrue("必须有日志函数", preview.contains("private fun logHomeTransition("))
        assertTrue("覆盖时必须调", body(preview, "applyHomeTransition").contains("logHomeTransition("))
        val b = body(preview, "logHomeTransition")
        assertTrue("必须节流去重", b.contains("if (key == homeTransitionLogKey) return"))
        assertTrue("必须打选项 id", b.contains("id=\$homeTransition"))
        assertTrue("必须打是否覆盖", b.contains("override=\$overridden"))
        assertTrue("必须打是否 SCENE", b.contains("scene=\$scene"))
        assertTrue("必须打时长", b.contains("durationMs=\$durationMs"))
    }

    /** 播放日志必须带实际时长，否则半接状态看不见。 */
    @Test
    fun playLogCarriesDuration() {
        assertTrue("play 日志必须带 dur", controller.contains("play edge=\$edge target=\$target delay=\$startDelay dur=\$edgeMs"))
    }

    /** ★不得删用户可见选项：需求 :724 是用户自己写的清单，下架比接上更违背令。 */
    @Test
    fun optionMustNotBeRemovedFromSettings() {
        val ui = File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodSettingsUi.kt").readText()
        assertTrue("设置页必须仍有桌面过渡选择", ui.contains("EffectKind.HOME_TRANSITION"))
        val defs = EffectCatalog.of(com.coloros16.liquidglass.engine.effect.EffectKind.HOME_TRANSITION)
        assertEquals("目录必须仍有两个选项", 2, defs.size)
        assertTrue("含不接管", defs.any { it.id == EffectCatalog.HOME_TRANSITION_NONE })
        assertTrue("含淡出淡入", defs.any { it.id == EffectCatalog.HOME_TRANSITION_CROSSFADE })
    }
}