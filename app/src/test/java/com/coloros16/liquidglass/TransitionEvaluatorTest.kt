package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ContentLayerConfig
import com.coloros16.liquidglass.engine.Easing
import com.coloros16.liquidglass.engine.ElementPose
import com.coloros16.liquidglass.engine.ElementTrack
import com.coloros16.liquidglass.engine.Keyframe
import com.coloros16.liquidglass.engine.KeyframeTrack
import com.coloros16.liquidglass.engine.MaskLayerConfig
import com.coloros16.liquidglass.engine.SceneContract
import com.coloros16.liquidglass.engine.SceneLayerConfig
import com.coloros16.liquidglass.engine.SceneSample
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.StyleCapabilities
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionEvaluator
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.engine.TransitionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionEvaluatorTest {

    private val screen = ScreenMetrics(widthPx = 1080f, heightPx = 2400f)
    private val fullCap = StyleCapabilities(sceneContinuous = true, mask = true, crossfade = true)

    // ---- Easing ----

    @Test
    fun easingHitsEndpointsAndStaysMonotonic() {
        val easing = Easing.STANDARD
        assertEquals(0f, easing.map(0f), 0.0001f)
        assertEquals(1f, easing.map(1f), 0.0001f)
        var previous = -1f
        for (i in 0..100) {
            val v = easing.map(i / 100f)
            assertTrue("monotonic at $i", v >= previous - 0.0001f)
            previous = v
        }
    }

    @Test
    fun linearEasingIsIdentity() {
        assertEquals(0.25f, Easing.LINEAR.map(0.25f), 0.0001f)
        assertEquals(1f, Easing.LINEAR.map(3f), 0.0001f)
    }

    // ---- KeyframeTrack / SceneContract ----

    @Test
    fun keyframeInterpolatesAndClamps() {
        val track = KeyframeTrack(listOf(Keyframe(0f, 0f), Keyframe(1f, 10f)))
        assertEquals(0f, track.sample(-1f), 0.001f)
        assertEquals(10f, track.sample(2f), 0.001f)
        assertEquals(5f, track.sample(0.5f), 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun keyframeRejectsDescendingTime() {
        KeyframeTrack(listOf(Keyframe(0.6f, 0f), Keyframe(0.2f, 1f)))
    }

    @Test
    fun sceneSampleFillsCameraLightAndElements() {
        val contract = SceneContract(
            id = "demo",
            cameraX = KeyframeTrack(listOf(Keyframe(0f, 0f), Keyframe(1f, 1f))),
            cameraScale = KeyframeTrack(listOf(Keyframe(0f, 1f), Keyframe(1f, 2f))),
            light = KeyframeTrack(listOf(Keyframe(0f, 0.2f), Keyframe(1f, 1f))),
            elements = listOf(
                ElementTrack("clock", alpha = KeyframeTrack(listOf(Keyframe(0f, 0f), Keyframe(1f, 1f)))),
            ),
        )
        val sample = SceneSample()
        sample.sample(contract, 0.5f)
        assertEquals(0.5f, sample.cameraX, 0.001f)
        assertEquals(1.5f, sample.cameraScale, 0.001f)
        assertEquals(0.6f, sample.light, 0.001f)
        assertEquals(1, sample.elements.size)
        assertEquals("clock", sample.elements[0].id)
        assertEquals(0.5f, sample.elements[0].alpha, 0.001f)

        sample.sample(contract.copyForTest(emptyList()), 0.5f)
        assertEquals(0, sample.elements.size)
    }

    private fun SceneContract.copyForTest(elements: List<ElementTrack>): SceneContract = SceneContract(
        id = id,
        cameraX = cameraX,
        cameraY = cameraY,
        cameraScale = cameraScale,
        cameraRotation = cameraRotation,
        light = light,
        elements = elements,
    )

    @Test
    fun screenCornerDistanceFromCenter() {
        val expected = kotlin.math.hypot(540f, 1200f)
        assertEquals(expected, screen.maxCornerDistance(540f, 1200f), 0.01f)
        assertEquals(2400f, screen.longSide, 0f)
        assertEquals(1080f, screen.shortSide, 0f)
    }

    // ---- TransitionEvaluator ----

    private fun evaluatorWith(profile: TransitionProfile): TransitionEvaluator =
        TransitionEvaluator(
            defaultProfile = TransitionProfile(
                scene = SceneLayerConfig(),
                mask = MaskLayerConfig(),
                content = ContentLayerConfig(easing = Easing.LINEAR),
            ),
        ).also { it.setProfile(TransitionEdge.AOD_TO_LOCK, profile) }

    @Test
    fun contentLayerCrossfadesEndToEnd() {
        val evaluator = evaluatorWith(
            TransitionProfile(
                mask = MaskLayerConfig(enabled = false),
                content = ContentLayerConfig(easing = Easing.LINEAR),
            ),
        )
        val out = TransitionParams()

        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 0f, screen, fullCap, fullCap, out)
        assertTrue(out.active)
        assertEquals(1f, out.srcAlpha, 0.001f)
        assertEquals(0f, out.dstAlpha, 0.001f)

        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, fullCap, fullCap, out)
        assertEquals(0.5f, out.srcAlpha, 0.001f)
        assertEquals(0.5f, out.dstAlpha, 0.001f)

        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 1f, screen, fullCap, fullCap, out)
        assertEquals(0f, out.srcAlpha, 0.001f)
        assertEquals(1f, out.dstAlpha, 0.001f)
    }

    @Test
    fun maskLayerGrowsRadiusAndFadesOuterContent() {
        val evaluator = evaluatorWith(
            TransitionProfile(
                mask = MaskLayerConfig(enabled = true, easing = Easing.LINEAR),
                content = ContentLayerConfig(enabled = false),
            ),
        )
        val out = TransitionParams()

        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 0f, screen, fullCap, fullCap, out)
        assertTrue(out.maskEnabled)
        assertEquals(0f, out.maskRadius, 0.001f)
        assertEquals(540f, out.maskCenterX, 0.001f)
        assertEquals(1200f, out.maskCenterY, 0.001f)
        assertEquals(1f, out.srcAlpha, 0.001f)

        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 1f, screen, fullCap, fullCap, out)
        val expected = screen.maxCornerDistance(540f, 1200f) * 1.05f
        assertEquals(expected, out.maskRadius, 0.5f)
        assertEquals(1.06f, out.srcScale, 0.001f)
        assertEquals(0f, out.srcAlpha, 0.001f)
        assertEquals(86.4f, out.maskFeather, 0.01f)
    }

    @Test
    fun maskLayerBlockedByCapabilitiesFallsBackToContent() {
        val evaluator = evaluatorWith(
            TransitionProfile(
                mask = MaskLayerConfig(enabled = true, easing = Easing.LINEAR),
                content = ContentLayerConfig(easing = Easing.LINEAR),
            ),
        )
        val out = TransitionParams()
        val noMask = StyleCapabilities(mask = false, crossfade = true)

        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, noMask, noMask, out)
        assertFalse(out.maskEnabled)
        assertEquals(0.5f, out.dstAlpha, 0.001f)
    }

    @Test
    fun allLayersBlockedUsesHardCut() {
        val evaluator = evaluatorWith(
            TransitionProfile(
                mask = MaskLayerConfig(enabled = true),
                content = ContentLayerConfig(enabled = true),
            ),
        )
        val out = TransitionParams()
        val none = StyleCapabilities(sceneContinuous = false, mask = false, crossfade = false)

        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 0.4f, screen, none, none, out)
        assertEquals(1f, out.srcAlpha, 0.001f)
        assertEquals(0f, out.dstAlpha, 0.001f)

        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 1f, screen, none, none, out)
        assertEquals(0f, out.srcAlpha, 0.001f)
        assertEquals(1f, out.dstAlpha, 0.001f)
    }

    @Test
    fun sceneLayerNeedsContractAndSampleBuffer() {
        val contract = SceneContract(
            id = "scene",
            cameraScale = KeyframeTrack(listOf(Keyframe(0f, 1f), Keyframe(1f, 1.8f))),
            light = KeyframeTrack(listOf(Keyframe(0f, 0.3f), Keyframe(1f, 0.9f))),
        )
        val evaluator = TransitionEvaluator(
            defaultProfile = TransitionProfile(
                scene = SceneLayerConfig(enabled = true, contract = contract, easing = Easing.LINEAR),
                mask = MaskLayerConfig(enabled = false),
                content = ContentLayerConfig(enabled = false),
            ),
        )
        val out = TransitionParams()
        val sample = SceneSample()

        evaluator.evaluate(TransitionEdge.LOCK_TO_HOME, 0.5f, screen, fullCap, fullCap, out, sample)
        assertEquals(1.4f, out.cameraScale, 0.001f)
        assertEquals(0.6f, out.light, 0.001f)
        assertEquals("scene", sample.contractId)

        evaluator.evaluate(TransitionEdge.LOCK_TO_HOME, 0.5f, screen, fullCap, fullCap, out, null)
        assertEquals(1f, out.cameraScale, 0.001f)
        assertEquals(1f, out.light, 0.001f)
    }

    @Test
    fun profileLookupFallsBackToDefault() {
        val evaluator = TransitionEvaluator()
        val a = evaluator.profileOf(TransitionEdge.AOD_TO_HOME)
        val b = evaluator.profileOf(TransitionEdge.HOME_TO_AOD)
        assertTrue(a === b)

        val custom = TransitionProfile(content = ContentLayerConfig(enabled = false))
        evaluator.setProfile(TransitionEdge.AOD_TO_HOME, custom)
        assertTrue(custom === evaluator.profileOf(TransitionEdge.AOD_TO_HOME))
        assertTrue(b === evaluator.profileOf(TransitionEdge.HOME_TO_AOD))
    }

    @Test
    fun allSixEdgesReachTargetState() {
        val evaluator = TransitionEvaluator()
        val out = TransitionParams()
        for (edge in TransitionEdge.values()) {
            evaluator.evaluate(edge, 1f, screen, fullCap, fullCap, out)
            assertEquals("edge $edge", 1f, out.dstAlpha, 0.001f)
            assertEquals("edge $edge", 0f, out.srcAlpha, 0.001f)
        }
    }

    @Test
    fun slideDirectionOffsetsSourceAndTarget() {
        val evaluator = TransitionEvaluator(
            defaultProfile = TransitionProfile(
                mask = MaskLayerConfig(enabled = false),
                content = ContentLayerConfig(
                    easing = Easing.LINEAR,
                    direction = com.coloros16.liquidglass.engine.SlideDirection.LEFT,
                    slideFraction = 0.25f,
                ),
            ),
        )
        val out = TransitionParams()
        evaluator.evaluate(TransitionEdge.HOME_TO_LOCK, 0f, screen, fullCap, fullCap, out)
        assertEquals(0f, out.srcDx, 0.001f)
        assertEquals(-270f, out.dstDx, 0.001f)

        evaluator.evaluate(TransitionEdge.HOME_TO_LOCK, 1f, screen, fullCap, fullCap, out)
        assertEquals(-270f, out.srcDx, 0.001f)
        assertEquals(0f, out.dstDx, 0.001f)
    }

    @Test
    fun elementPoseDefaultsWhenChannelsMissing() {
        val pose = ElementPose()
        ElementTrack("x").sample(0.5f, pose)
        assertEquals("x", pose.id)
        assertEquals(0f, pose.x, 0f)
        assertEquals(1f, pose.scale, 0f)
        assertEquals(1f, pose.alpha, 0f)
    }
}
