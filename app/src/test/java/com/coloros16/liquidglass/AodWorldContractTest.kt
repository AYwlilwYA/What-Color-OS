package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.AodMotion
import com.coloros16.liquidglass.engine.effect.AodMotionState
import com.coloros16.liquidglass.ui.aod.AodSceneCatalog
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界层接线契约：hook 侧取样式契约与镜片真值的口径必须与预览侧一致。
 *
 * 纯 JVM 核对（AodSceneCatalog / SailLensMotion / AodMotion 都不触平台）。
 */
class AodWorldContractTest {

    private val pose = SailLensMotion.Pose()
    private val fade = SailLensMotion.Fade()
    private val layers = SystemSceneMotion.Layers()
    private val motion = AodMotionState()

    @Test
    fun sailStyleHasLensSpec() {
        val spec = AodSceneCatalog.forStyle(AodStyleCatalog.ID_SAIL)
        assertNotNull("启航必须有世界契约", spec)
        assertTrue("启航是镜片类", spec!!.lens)
        assertNull("启航不是照片类", spec.photo)
    }

    @Test
    fun photoStylesHavePhotoSpecNotLens() {
        for (id in listOf(
            AodStyleCatalog.ID_LEAVE_LIGHT, AodStyleCatalog.ID_STARMOON,
            AodStyleCatalog.ID_DOOR, AodStyleCatalog.ID_MOUNTAIN, AodStyleCatalog.ID_GALAXY,
        )) {
            val spec = AodSceneCatalog.forStyle(id)
            assertNotNull("$id 应有世界契约", spec)
            assertFalse("$id 不是镜片类", spec!!.lens)
            assertNotNull("$id 是照片类", spec.photo)
        }
    }

    @Test
    fun nonSceneStylesHaveNoSpec() {
        var nonScene = 0
        for (id in AodStyleCatalog.allIds()) {
            if (AodSceneCatalog.forStyle(id) != null) continue
            nonScene++
            assertNull("非场景样式 $id 不该有世界契约", AodSceneCatalog.forStyle(id))
        }
        assertEquals("十款样式里六款有世界、四款无", 4, nonScene)
    }

    @Test
    fun unknownStyleHasNoSpec() {
        assertNull(AodSceneCatalog.forStyle("no.such.style"))
        assertNull(AodSceneCatalog.forStyle(""))
    }

    @Test
    fun lensAlphaUsesLensFadeNotSystemLayers() {
        var differs = 0
        for (edge in TransitionEdge.values()) {
            for (i in 1..9) {
                val t = i / 10f
                SystemSceneMotion.sample(edge, t, layers)
                SailLensMotion.fade(edge, t, fade)
                if (kotlin.math.abs(fade.aodAlpha - layers.aodAlpha) > 1e-3f) differs++
            }
        }
        assertTrue("★镜片透明度必须取镜片曲线，取系统分层会与预览错位 differs=$differs", differs > 0)
    }

    @Test
    fun lensAlphaFallsBackToSystemLayersWhenStill() {
        for (state in SceneState.values()) {
            SystemSceneMotion.sampleStatic(state, layers)
            val expected = if (state == SceneState.AOD) 1f else 0f
            assertEquals("$state 静止态息屏层透明度", expected, layers.aodAlpha, 1e-4f)
        }
    }

    @Test
    fun lensFadeEndpointsHoldForAllSixEdges() {
        for (edge in TransitionEdge.values()) {
            SailLensMotion.fade(edge, 0f, fade)
            val start = fade.aodAlpha
            SailLensMotion.fade(edge, 1f, fade)
            val end = fade.aodAlpha
            val entersAod = edge == TransitionEdge.LOCK_TO_AOD || edge == TransitionEdge.HOME_TO_AOD
            val leavesAod = edge == TransitionEdge.AOD_TO_LOCK || edge == TransitionEdge.AOD_TO_HOME
            if (entersAod) {
                assertEquals("$edge 起点息屏层不可见", 0f, start, 1e-4f)
                assertEquals("$edge 终点息屏层满显", 1f, end, 1e-4f)
            } else if (leavesAod) {
                assertEquals("$edge 起点息屏层满显", 1f, start, 1e-4f)
                assertEquals("$edge 终点息屏层不可见", 0f, end, 1e-4f)
            } else {
                assertEquals("$edge 不碰息屏层", 1f, start, 1e-4f)
                assertEquals("$edge 不碰息屏层", 1f, end, 1e-4f)
            }
        }
    }

    @Test
    fun lensAlphaStaysInUnitRangeAcrossAllEdges() {
        for (edge in TransitionEdge.values()) {
            for (i in 0..50) {
                SailLensMotion.fade(edge, i / 50f, fade)
                assertTrue("$edge aodAlpha 越界 ${fade.aodAlpha}", fade.aodAlpha in 0f..1f)
            }
        }
    }

    @Test
    fun motionGateOffFreezesSpinAndMix() {
        AodMotion.evaluate(123_456L, false, 60f, 12f, 6f, motion)
        assertFalse(motion.active)
        assertEquals(0f, motion.rotationDeg, 1e-6f)
        assertEquals(0f, motion.colorMix, 1e-6f)
        assertEquals(0f, motion.photoTiltDeg, 1e-6f)
    }

    @Test
    fun motionGateOnWithZeroParamsStillCountsAsActive() {
        AodMotion.evaluate(123_456L, true, 0f, 0f, 0f, motion)
        assertTrue("★引擎 active 只看总闸不看幅度，静止判据不能靠 active=false", motion.active)
        assertEquals(0f, motion.rotationDeg, 1e-6f)
        assertEquals(0f, motion.colorMix, 1e-6f)
    }

    @Test
    fun motionRotationIsSingleSourceForSpinAndMix() {
        for (ms in listOf(1_000L, 37_000L, 900_000L, 86_400_000L)) {
            AodMotion.evaluate(ms, true, 60f, 12f, 6f, motion)
            assertTrue("$ms 转角必须在 [0,360)", motion.rotationDeg >= 0f && motion.rotationDeg < 360f)
            assertTrue("$ms 混色必须在 [0,1]", motion.colorMix in 0f..1f)
            assertTrue("$ms 倾斜必须在 ±15", kotlin.math.abs(motion.photoTiltDeg) <= 15f)
        }
    }

    @Test
    fun motionResetClearsStaleValues() {
        AodMotion.evaluate(37_000L, true, 60f, 12f, 6f, motion)
        assertTrue(motion.rotationDeg != 0f)
        motion.reset()
        assertEquals(0f, motion.rotationDeg, 1e-6f)
        assertEquals(0f, motion.colorMix, 1e-6f)
        assertFalse(motion.active)
    }

    @Test
    fun motionAtZeroClockIsFrozen() {
        AodMotion.evaluate(0L, true, 60f, 12f, 6f, motion)
        assertFalse(motion.active)
        assertEquals(0f, motion.rotationDeg, 1e-6f)
    }

    @Test
    fun allSceneSpecsProvideThreeStateAnchors() {
        for (id in AodStyleCatalog.allIds()) {
            val spec = AodSceneCatalog.forStyle(id) ?: continue
            for (state in SceneState.values()) {
                assertNotNull("$id 缺 $state 锚点", spec.clockAnchors[state])
            }
        }
    }

    @Test
    fun worldStyleCountMatchesCatalog() {
        var lens = 0
        var photo = 0
        for (id in AodStyleCatalog.allIds()) {
            val s = AodSceneCatalog.forStyle(id) ?: continue
            if (s.lens) lens++
            if (s.photo != null) photo++
        }
        assertEquals("镜片类只有启航一款", 1, lens)
        assertEquals("照片类五款", 5, photo)
    }

    @Test
    fun lensPoseKeepsFiniteAtEveryEdgeSample() {
        val expanded = SailLensMotion.expandedScale(2.2f)
        for (edge in TransitionEdge.values()) {
            for (i in 0..40) {
                SailLensMotion.sample(edge, i / 40f, 0.5f, 0.435f, expanded, pose, spinDeg = 123f)
                assertTrue("$edge scale 非有限", pose.scale.isFinite())
                assertTrue("$edge blur 非有限", pose.blur.isFinite())
                assertTrue("$edge 圆心非有限", pose.centerX.isFinite() && pose.centerY.isFinite())
                val zoom = SailLensMotion.lensZoom(pose.scale)
                assertTrue("$edge zoom 非正或非有限 $zoom", zoom > 0f && zoom.isFinite())
            }
        }
    }
}
