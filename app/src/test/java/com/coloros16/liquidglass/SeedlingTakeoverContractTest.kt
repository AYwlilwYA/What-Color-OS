package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingAnimType
import com.coloros16.liquidglass.engine.SeedlingDisplayRules
import com.coloros16.liquidglass.engine.SeedlingKind
import com.coloros16.liquidglass.engine.SeedlingKindPolicy
import com.coloros16.liquidglass.engine.SeedlingKindSwitches
import com.coloros16.liquidglass.engine.SeedlingMotionParams
import com.coloros16.liquidglass.engine.SeedlingSceneState
import com.coloros16.liquidglass.engine.SeedlingTakeoverMotion
import com.coloros16.liquidglass.engine.SeedlingTransitionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedlingTakeoverContractTest {

    // ---------- kind 序号映射（星流 of_key:196-223） ----------

    @Test
    fun kindOrdinalMappingMatchesStarflow() {
        assertEquals(SeedlingKind.FACE_UNLOCK, SeedlingKind.ofOrdinal(0))
        assertEquals(SeedlingKind.CALL, SeedlingKind.ofOrdinal(1))
        assertEquals(SeedlingKind.RECORDING, SeedlingKind.ofOrdinal(2))
        assertEquals(SeedlingKind.STATUS, SeedlingKind.ofOrdinal(7))
        assertEquals(SeedlingKind.STATUS, SeedlingKind.ofOrdinal(8))
        assertEquals(SeedlingKind.STATUS, SeedlingKind.ofOrdinal(10))
        assertEquals(SeedlingKind.TIMER, SeedlingKind.ofOrdinal(3))
        assertEquals(SeedlingKind.LIVE_UPDATE, SeedlingKind.ofOrdinal(4))
        assertEquals(SeedlingKind.LIVE_UPDATE, SeedlingKind.ofOrdinal(9))
        assertEquals(SeedlingKind.MEDIA, SeedlingKind.ofOrdinal(5))
        assertEquals(SeedlingKind.MESSAGE, SeedlingKind.ofOrdinal(6))
        assertEquals(SeedlingKind.CODE, SeedlingKind.ofOrdinal(11))
    }

    @Test
    fun unknownOrdinalFallsBackNotCrash() {
        assertEquals(SeedlingKind.UNKNOWN, SeedlingKind.ofOrdinal(99))
        assertEquals(SeedlingKind.UNKNOWN, SeedlingKind.ofOrdinal(-1))
    }

    // ---------- kind 默认开关（星流 config/c.java:51） ----------

    @Test
    fun kindDefaultsMatchStarflow() {
        val s = SeedlingKindSwitches()
        assertTrue(s.media)
        assertFalse(s.message)
        assertTrue(s.call)
        assertTrue(s.timer)
        assertTrue(s.status)
        assertFalse(s.faceUnlock)
        assertTrue(s.liveUpdate)
        assertFalse(s.code)
    }

    @Test
    fun messageDefaultOffBlocksMessageKind() {
        val show = SeedlingKindPolicy.shouldShow(
            SeedlingKind.MESSAGE, SeedlingKindSwitches(), SeedlingDisplayRules(), SeedlingSceneState(),
        )
        assertFalse(show)
    }

    @Test
    fun mediaDefaultOnAllowsMediaKind() {
        val show = SeedlingKindPolicy.shouldShow(
            SeedlingKind.MEDIA, SeedlingKindSwitches(), SeedlingDisplayRules(), SeedlingSceneState(),
        )
        assertTrue(show)
    }

    // ---------- 显示规则 ----------

    @Test
    fun aodDefaultHides() {
        val show = SeedlingKindPolicy.shouldShow(
            SeedlingKind.MEDIA, SeedlingKindSwitches(), SeedlingDisplayRules(),
            SeedlingSceneState(onAod = true),
        )
        assertFalse(show)
    }

    @Test
    fun shadeExpandedHidesByDefault() {
        val show = SeedlingKindPolicy.shouldShow(
            SeedlingKind.MEDIA, SeedlingKindSwitches(), SeedlingDisplayRules(),
            SeedlingSceneState(shadeExpanded = true),
        )
        assertFalse(show)
    }

    @Test
    fun lockscreenShowsByDefault() {
        val show = SeedlingKindPolicy.shouldShow(
            SeedlingKind.MEDIA, SeedlingKindSwitches(), SeedlingDisplayRules(),
            SeedlingSceneState(onLockscreen = true),
        )
        assertTrue(show)
    }

    @Test
    fun masterOffBlocksEverything() {
        val show = SeedlingKindPolicy.shouldShow(
            SeedlingKind.MEDIA, SeedlingKindSwitches(), SeedlingDisplayRules(), SeedlingSceneState(),
            masterEnabled = false,
        )
        assertFalse(show)
    }

    @Test
    fun unknownKindStillSubjectToDisplayRules() {
        // UNKNOWN 跳过 kind 开关，但 AOD 规则仍隐藏（阳性对照：证明显示规则真生效非空转）
        val onAod = SeedlingKindPolicy.shouldShow(
            SeedlingKind.UNKNOWN, SeedlingKindSwitches(), SeedlingDisplayRules(),
            SeedlingSceneState(onAod = true),
        )
        assertFalse(onAod)
        val normal = SeedlingKindPolicy.shouldShow(
            SeedlingKind.UNKNOWN, SeedlingKindSwitches(), SeedlingDisplayRules(), SeedlingSceneState(),
        )
        assertTrue(normal)
    }

    @Test
    fun satellitesFollowRuleAndBody() {
        val rules = SeedlingDisplayRules(satellites = true)
        assertTrue(
            SeedlingKindPolicy.shouldShowSatellites(
                SeedlingKind.MEDIA, SeedlingKindSwitches(), rules, SeedlingSceneState(),
            ),
        )
        val noSat = SeedlingDisplayRules(satellites = false)
        assertFalse(
            SeedlingKindPolicy.shouldShowSatellites(
                SeedlingKind.MEDIA, SeedlingKindSwitches(), noSat, SeedlingSceneState(),
            ),
        )
    }

    // ---------- 动画类型映射 ----------

    @Test
    fun animTypeOfNameMatchesSystem() {
        assertEquals(SeedlingAnimType.SHRINK_IN, SeedlingAnimType.ofName("SHRINK_IN"))
        assertEquals(SeedlingAnimType.EXPAND_OUT, SeedlingAnimType.ofName("EXPAND_OUT"))
        assertEquals(SeedlingAnimType.SHRINK_IN_EXPAND_OUT, SeedlingAnimType.ofName("SHRINK_IN_EXPAND_OUT"))
        assertEquals(SeedlingAnimType.MOVE_LEFT, SeedlingAnimType.ofName("MOVE_LEFT"))
        assertEquals(SeedlingAnimType.MOVE_RIGHT, SeedlingAnimType.ofName("MOVE_RIGHT"))
        assertEquals(SeedlingAnimType.UNKNOWN, SeedlingAnimType.ofName("bogus"))
        assertEquals(SeedlingAnimType.UNKNOWN, SeedlingAnimType.ofName(null))
    }

    @Test
    fun morphDirectionByType() {
        assertEquals(1, SeedlingTakeoverMotion.morphDirection(SeedlingAnimType.EXPAND_OUT))
        assertEquals(-1, SeedlingTakeoverMotion.morphDirection(SeedlingAnimType.SHRINK_IN))
        assertEquals(0, SeedlingTakeoverMotion.morphDirection(SeedlingAnimType.MOVE_LEFT))
        assertEquals(0, SeedlingTakeoverMotion.morphDirection(SeedlingAnimType.MOVE_RIGHT))
    }

    // ---------- 时长真值 ----------

    @Test
    fun durationUsesSystemTruthSum() {
        val ev = SeedlingTransitionEvent(SeedlingAnimType.EXPAND_OUT, longArrayOf(100, 200, 150), 432)
        assertEquals(450L, ev.totalDurationMs(999L, 1f))
    }

    @Test
    fun emptyDurationsFallsBack() {
        val ev = SeedlingTransitionEvent(SeedlingAnimType.EXPAND_OUT, longArrayOf(), 432)
        assertEquals(SeedlingTakeoverMotion.FALLBACK_EXPAND_MS, ev.totalDurationMs(0L, 1f))
    }

    @Test
    fun speedScaleShortensDuration() {
        val ev = SeedlingTransitionEvent(SeedlingAnimType.EXPAND_OUT, longArrayOf(400), 432)
        // speedScale 2 → 时长减半
        assertEquals(200L, ev.totalDurationMs(0L, 2f))
    }

    // ---------- 过渡采样 ----------

    @Test
    fun expandStartsAtCapsuleEndsAtCard() {
        val ev = SeedlingTransitionEvent(SeedlingAnimType.EXPAND_OUT, longArrayOf(300), 1000)
        val start = SeedlingTakeoverMotion.sample(0, ev, 0f, 400f, 1000f)
        val end = SeedlingTakeoverMotion.sample(300, ev, 0f, 400f, 1000f)
        assertEquals(0f, start.u, 1e-3f)
        assertEquals(400f, start.widthPx, 1f)
        assertEquals(1f, end.u, 1e-3f)
        assertEquals(1000f, end.widthPx, 1f)
    }

    @Test
    fun shrinkReversesDirection() {
        val ev = SeedlingTransitionEvent(SeedlingAnimType.SHRINK_IN, longArrayOf(280), 400)
        val start = SeedlingTakeoverMotion.sample(0, ev, 1f, 400f, 1000f)
        val end = SeedlingTakeoverMotion.sample(280, ev, 1f, 400f, 1000f)
        assertEquals(1f, start.u, 1e-3f)
        assertEquals(0f, end.u, 1e-3f)
        assertEquals(400f, end.widthPx, 1f)
    }

    @Test
    fun moveDoesNotMorph() {
        val ev = SeedlingTransitionEvent(SeedlingAnimType.MOVE_LEFT, longArrayOf(200), 400)
        val mid = SeedlingTakeoverMotion.sample(100, ev, 0.3f, 400f, 1000f)
        assertEquals(0.3f, mid.u, 1e-3f)
    }

    @Test
    fun transitionMidpointIsBetweenEndpoints() {
        // 阳性对照：临界阻尼曲线中点必在两端之间（证明真插值非恒值）
        val ev = SeedlingTransitionEvent(SeedlingAnimType.EXPAND_OUT, longArrayOf(300), 1000)
        val mid = SeedlingTakeoverMotion.sample(150, ev, 0f, 400f, 1000f)
        assertTrue("mid u=${mid.u}", mid.u > 0f && mid.u < 1f)
        assertTrue(mid.widthPx > 400f && mid.widthPx < 1000f)
    }

    @Test
    fun largeDisplacementNoOvershoot() {
        // 大位移禁 ζ<1：展开全程 u 不得超 1（临界阻尼无过冲）
        val ev = SeedlingTransitionEvent(SeedlingAnimType.EXPAND_OUT, longArrayOf(300), 1000)
        for (t in 0..300 step 10) {
            val s = SeedlingTakeoverMotion.sample(t.toLong(), ev, 0f, 400f, 1000f)
            assertTrue("overshoot at t=$t u=${s.u}", s.u <= 1.0001f)
        }
    }

    @Test
    fun elasticWidthAllowsOvershootForSmallDisplacement() {
        // 小位移弹性（阻尼 0.62）允许过冲=弹动感（阳性对照：与临界阻尼位移通道真不同）
        var maxFraction = 0f
        for (p in 0..100) {
            val f = SeedlingTakeoverMotion.elasticWidthFraction(p / 100f)
            if (f > maxFraction) maxFraction = f
        }
        assertTrue("elastic should overshoot, max=$maxFraction", maxFraction > 1f)
    }

    @Test
    fun targetWidthPrefersPlaceholderTruth() {
        val w = SeedlingTakeoverMotion.resolveTargetWidth(432, 400f, SeedlingMotionParams())
        assertEquals(432f, w, 1e-3f)
    }

    @Test
    fun targetWidthFallsBackWhenPlaceholderZero() {
        val w = SeedlingTakeoverMotion.resolveTargetWidth(0, 400f, SeedlingMotionParams())
        assertTrue("fallback w=$w", w >= 400f)
    }

    @Test
    fun widthTracksMorphExactly_positiveControl() {
        // 阳性对照：widthPx 必随 u 线性插值（updateGeometry 消费此通道，非独立重算）
        val ev = SeedlingTransitionEvent(SeedlingAnimType.EXPAND_OUT, longArrayOf(300), 1000)
        for (t in 0..300 step 30) {
            val s = SeedlingTakeoverMotion.sample(t.toLong(), ev, 0f, 400f, 1000f)
            val expected = 400f + (1000f - 400f) * s.u
            assertEquals("widthPx 必等于 lerp(capsule,card,u) t=$t", expected, s.widthPx, 0.5f)
        }
    }

    @Test
    fun shrinkWidthTracksMorph() {
        val ev = SeedlingTransitionEvent(SeedlingAnimType.SHRINK_IN, longArrayOf(280), 400)
        for (t in 0..280 step 40) {
            val s = SeedlingTakeoverMotion.sample(t.toLong(), ev, 1f, 400f, 1000f)
            val expected = 400f + (1000f - 400f) * s.u
            assertEquals(expected, s.widthPx, 0.5f)
        }
    }
}