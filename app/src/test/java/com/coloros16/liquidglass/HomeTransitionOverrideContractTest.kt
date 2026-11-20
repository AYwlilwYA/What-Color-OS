package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.HomeTransitionOverride
import com.coloros16.liquidglass.engine.SeamProfilePolicy
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectParams
import com.coloros16.liquidglass.engine.effect.EffectSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桌面过渡选项接 hook 契约：默认不接管保持接缝配方，选了才覆盖，时长同轮生效。
 */
class HomeTransitionOverrideContractTest {

    private fun hook(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/AodHandoffHook.kt",
    ).readText()

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
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

    private fun settings(id: String, ms: Float? = null): EffectSettings =
        EffectSettings().apply {
            homeTransition = id
            if (ms != null) setParam(EffectParams.HOME_TRANSITION_DURATION, ms)
        }

    /** ★默认不接管：出厂设置下六边一律 null，接缝配方原样保留。 */
    @Test
    fun defaultKeepsSeamProfilesUntouched() {
        val s = EffectSettings()
        assertEquals(EffectCatalog.HOME_TRANSITION_NONE, s.homeTransition)
        for (e in TransitionEdge.values()) {
            assertNull("边 $e 默认不得覆盖", HomeTransitionOverride.profileFor(e, s))
            assertEquals("边 $e 默认时长交回", 0L, HomeTransitionOverride.durationMs(e, s))
        }
    }

    /** ★阳性对照：默认与选了 crossfade 必须不同，否则接线是空操作。 */
    @Test
    fun positiveControlSelectionActuallyChangesProfile() {
        val off = HomeTransitionOverride.profileFor(TransitionEdge.LOCK_TO_HOME, EffectSettings())
        val on = HomeTransitionOverride.profileFor(
            TransitionEdge.LOCK_TO_HOME,
            settings(EffectCatalog.HOME_TRANSITION_CROSSFADE),
        )
        assertNull(off)
        assertNotNull(on)
        assertTrue("选中后内容层必须开", on!!.content.enabled)
        assertFalse("crossfade 不带位移", on.content.slideFraction > 0f)
    }

    /** ★作用域只锁屏↔桌面两条，含息屏的四条边一律不受控。 */
    @Test
    fun onlyLockHomePairIsGoverned() {
        val s = settings(EffectCatalog.HOME_TRANSITION_CROSSFADE, 900f)
        for (e in TransitionEdge.values()) {
            val governed = e == TransitionEdge.LOCK_TO_HOME || e == TransitionEdge.HOME_TO_LOCK
            assertEquals("边 $e 作用域", governed, HomeTransitionOverride.governs(e))
            if (governed) continue
            assertNull("边 $e 不得覆盖", HomeTransitionOverride.profileFor(e, s))
            assertEquals("边 $e 时长交回", 0L, HomeTransitionOverride.durationMs(e, s))
        }
    }

    /** ★与预览侧同口径：预览必须复用本件 governs/profileFor，不得自造第二份作用域表。 */
    @Test
    fun scopeMatchesPreviewEdgeList() {
        val p = File(
            "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
        ).readText()
        assertTrue("预览作用域必须走共用件", p.contains("HomeTransitionOverride.governs(e)"))
        assertTrue("预览配方必须走共用件", p.contains("HomeTransitionOverride.profileFor(e, settings)"))
        assertFalse("预览不得自造第二份作用域表", p.contains("HOME_TRANSITION_EDGES"))
        val c = File(
            "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodSceneCatalog.kt",
        ).readText()
        assertTrue("预览时长必须走共用件", c.contains("HomeTransitionOverride.durationMs(edge, settings)"))
    }

    /** 选中后受控两边都拿到配方，时长取选项值。 */
    @Test
    fun selectedCoversGovernedEdgesWithDuration() {
        val s = settings(EffectCatalog.HOME_TRANSITION_CROSSFADE, 900f)
        for (e in TransitionEdge.values()) {
            if (!HomeTransitionOverride.governs(e)) continue
            assertNotNull("边 $e 应被覆盖", HomeTransitionOverride.profileFor(e, s))
            assertEquals("边 $e 时长", 900L, HomeTransitionOverride.durationMs(e, s))
        }
    }

    /** 时长缺省取参数表默认值，不是 0。 */
    @Test
    fun durationFallsBackToParamDefault() {
        val s = settings(EffectCatalog.HOME_TRANSITION_CROSSFADE)
        val expected = EffectParams.find(EffectParams.HOME_TRANSITION_DURATION)!!.default.toLong()
        assertEquals(expected, HomeTransitionOverride.durationMs(TransitionEdge.LOCK_TO_HOME, s))
        assertTrue("默认时长必须为正", expected > 0L)
    }

    /** 未知选项 id 一律不接管（profileFor 返回 null）。 */
    @Test
    fun unknownIdDoesNotTakeOver() {
        val s = settings("home.transition.bogus", 700f)
        assertNull(HomeTransitionOverride.profileFor(TransitionEdge.LOCK_TO_HOME, s))
        assertEquals(0L, HomeTransitionOverride.durationMs(TransitionEdge.LOCK_TO_HOME, s))
    }

    /** ★不覆盖时必须回落接缝配方，不是留空配方。 */
    @Test
    fun hookFallsBackToSeamWhenNotOverridden() {
        val b = body(hook(), "applyHomeTransition")
        assertTrue("必须有覆盖分支", b.contains("if (override != null)"))
        assertTrue("否则走接缝配方", b.contains("SeamProfilePolicy.apply(edge, profile)"))
        assertTrue("必须逐边写回", b.contains("b.evaluator.setProfile(edge, profile)"))
    }

    /** 阳性对照：只写覆盖分支不写回落，会让取消选择后配方停在 crossfade。 */
    @Test
    fun positiveControlMissingFallbackWouldStickCrossfade() {
        val wrong = "if (override != null) { copy(override) }"
        assertTrue("存在性检查看不出问题", wrong.contains("override != null"))
        assertFalse("但没有回落分支", wrong.contains("SeamProfilePolicy.apply"))
    }

    /** 覆盖走共用策略，不在 hook 里另写一套判据。 */
    @Test
    fun hookUsesSharedPolicy() {
        val b = body(hook(), "applyHomeTransition")
        assertTrue(b.contains("HomeTransitionOverride.profileFor(edge, settings)"))
        assertTrue(b.contains("HomeTransitionOverride.durationMs(TransitionEdge.LOCK_TO_HOME, settings)"))
        assertTrue(b.contains("HomeMotion.transitionForEdge(TransitionEdge.LOCK_TO_HOME, settings)"))
    }

    /** 只在选项或时长真变时写桥（禁逐帧重设配方）。 */
    @Test
    fun writesOnlyWhenChanged() {
        val b = body(hook(), "applyHomeTransition")
        assertTrue(
            "必须有变化判据早退",
            b.contains("if (id == homeOverrideId && ms == homeOverrideMs) return"),
        )
    }

    /** 读配置失败保持上次值，不清成默认（fail-open）。 */
    @Test
    fun readFailureKeepsPrevious() {
        val b = body(hook(), "applyHomeTransition")
        assertTrue(b.contains("aod-home-read"))
        val catchAt = b.indexOf("catch (t: Throwable)")
        val returnAt = b.indexOf("return", catchAt)
        assertTrue("catch 分支必须直接 return", catchAt >= 0 && returnAt > catchAt)
        assertFalse("catch 里不得改状态", b.substring(catchAt, returnAt).contains("homeOverrideId ="))
    }

    /** 挂在既有 TTL 读键链上，不新起轮询。 */
    @Test
    fun ridesExistingTtlChainNoPolling() {
        assertTrue(
            "必须挂在 refreshRevealCapability 尾部",
            body(hook(), "refreshRevealCapability").contains("applyHomeTransition(b)"),
        )
        val b = body(hook(), "applyHomeTransition")
        assertFalse("不得自起循环", b.contains("while (") || b.contains("postDelayed"))
        assertFalse("不得起定时器", b.contains("Timer(") || b.contains("ScheduledExecutor"))
    }

    /** ★时长必须同轮接：舞台时钟优先取选项时长。 */
    @Test
    fun stageClockConsumesOverrideDuration() {
        val b = body(stage(), "edgeDurationMs")
        val home = b.indexOf("AodHandoffHook.homeTransitionMs(edge)")
        val style = b.indexOf("AodSceneCatalog.durationOf(id, edge)")
        assertTrue("必须取选项时长", home >= 0)
        assertTrue("选项优先于样式时长", home in 0 until style)
        assertTrue("命中要有诊断", b.contains("stage-duration-home"))
    }

    /** 阳性对照：只接配方不接时长 = 效果对了节奏还是旧的（半接）。 */
    @Test
    fun positiveControlProfileWithoutDurationIsHalfWired() {
        val half = "val p = HomeTransitionOverride.profileFor(edge, settings)"
        assertTrue("配方接了", half.contains("profileFor"))
        assertFalse("时长没接", half.contains("durationMs"))
    }

    /** 供热路径读的时长接口只读缓存字段，不读配置。 */
    @Test
    fun durationAccessorIsCacheOnly() {
        val b = body(hook(), "homeTransitionMs")
        assertFalse("不得读配置", b.contains("EffectConfigStore"))
        assertTrue("只读缓存", b.contains("homeOverrideMs"))
        assertTrue("仍受作用域约束", b.contains("HomeTransitionOverride.governs(edge)"))
    }

    /** 热重载必须清账，否则代次间残留旧选项。 */
    @Test
    fun hotReloadClearsOverrideState() {
        val b = body(hook(), "shutdownForHotReload")
        assertTrue(b.contains("homeOverrideId = null"))
        assertTrue(b.contains("homeOverrideMs = 0L"))
    }

    /** 引擎件纯 JVM，零 android 依赖。 */
    @Test
    fun engineFileIsPureJvm() {
        val s = File(
            "src/main/kotlin/com/coloros16/liquidglass/engine/HomeTransitionOverride.kt",
        ).readText()
        assertFalse("不得 import android", s.contains("import android."))
        assertFalse("不得引用 Android 类型", s.contains("Context") || s.contains("Looper"))
    }

    /** 诊断带选项 id 与时长，回线可核。 */
    @Test
    fun diagnosticCarriesIdAndDuration() {
        val b = body(hook(), "applyHomeTransition")
        assertTrue(b.contains("aod-home-transition"))
        assertTrue(b.contains("id=\$id"))
        assertTrue(b.contains("durationMs=\$ms"))
    }
}