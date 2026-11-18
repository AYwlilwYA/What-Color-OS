package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.MaskLayerConfig
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

class FrameRevealCapabilityTest {

    private val screen = ScreenMetrics(1080f, 2400f, 3f)
    private val geometric = StyleCapabilities(mask = true, crossfade = true)
    private val byFrame = StyleCapabilities(mask = true, crossfade = true, frameReveal = true)

    private fun evaluator() = TransitionEvaluator(
        defaultProfile = TransitionProfile(mask = MaskLayerConfig(enabled = true)),
    )

    @Test
    fun geometricCapabilitiesKeepRadiusAndFeather() {
        val out = TransitionParams()
        evaluator().evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, geometric, geometric, out)
        assertTrue(out.maskEnabled)
        assertFalse(out.maskByFrame)
        assertTrue(out.maskRadius > 0f)
        assertTrue(out.maskFeather > 0f)
    }

    @Test
    fun frameCapabilityOnSourceSuppressesGeometry() {
        val out = TransitionParams()
        evaluator().evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, byFrame, geometric, out)
        assertTrue(out.maskByFrame)
        assertFalse(out.maskEnabled)
        assertEquals(0f, out.maskRadius, 1e-6f)
        assertEquals(0f, out.maskFeather, 1e-6f)
    }

    @Test
    fun frameCapabilityOnDestinationAlsoSuppressesGeometry() {
        val out = TransitionParams()
        evaluator().evaluate(TransitionEdge.LOCK_TO_AOD, 0.5f, screen, geometric, byFrame, out)
        assertTrue(out.maskByFrame)
        assertFalse(out.maskEnabled)
    }

    @Test
    fun frameRevealSkipsMaskOuterFadeAndScale() {
        val maskOnly = TransitionEvaluator(
            defaultProfile = TransitionProfile(
                mask = MaskLayerConfig(enabled = true),
                content = com.coloros16.liquidglass.engine.ContentLayerConfig(enabled = false),
            ),
        )
        val plain = TransitionParams()
        val framed = TransitionParams()
        maskOnly.evaluate(TransitionEdge.AOD_TO_LOCK, 0.8f, screen, geometric, geometric, plain)
        maskOnly.evaluate(TransitionEdge.AOD_TO_LOCK, 0.8f, screen, byFrame, byFrame, framed)
        assertEquals(1f, framed.srcAlpha, 1e-6f)
        assertEquals(1f, framed.srcScale, 1e-6f)
        assertTrue(plain.srcAlpha < 1f)
        assertTrue(plain.srcScale > 1f)
    }

    @Test
    fun contentLayerStillFadesWhenFrameReveals() {
        val both = TransitionEvaluator(
            defaultProfile = TransitionProfile(
                mask = MaskLayerConfig(enabled = true),
                content = com.coloros16.liquidglass.engine.ContentLayerConfig(enabled = true),
            ),
        )
        val out = TransitionParams()
        both.evaluate(TransitionEdge.AOD_TO_LOCK, 0.8f, screen, byFrame, byFrame, out)
        assertTrue(out.maskByFrame)
        assertTrue(out.srcAlpha < 1f)
        assertTrue(out.dstAlpha > 0f)
    }

    @Test
    fun frameRevealDoesNotBlockContentLayer() {
        val out = TransitionParams()
        val e = TransitionEvaluator(
            defaultProfile = TransitionProfile(
                mask = MaskLayerConfig(enabled = true),
                content = com.coloros16.liquidglass.engine.ContentLayerConfig(enabled = true),
            ),
        )
        e.evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, byFrame, byFrame, out)
        assertTrue(out.active)
        assertTrue(out.maskByFrame)
    }

    @Test
    fun maskDisabledConfigWinsOverFrameCapability() {
        val out = TransitionParams()
        val e = TransitionEvaluator(
            defaultProfile = TransitionProfile(mask = MaskLayerConfig(enabled = false)),
        )
        e.evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, byFrame, byFrame, out)
        assertFalse(out.maskByFrame)
        assertFalse(out.maskEnabled)
    }

    @Test
    fun resetClearsFrameFlag() {
        val out = TransitionParams()
        val e = evaluator()
        e.evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, byFrame, byFrame, out)
        assertTrue(out.maskByFrame)
        e.evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, geometric, geometric, out)
        assertFalse(out.maskByFrame)
    }

    @Test
    fun copyFromCarriesFrameFlag() {
        val src = TransitionParams()
        evaluator().evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, byFrame, byFrame, src)
        val dst = TransitionParams()
        dst.copyFrom(src)
        assertTrue(dst.maskByFrame)
    }

    @Test
    fun sameStateEdgeStaysInactiveEvenWithFrameCapability() {
        val out = TransitionParams()
        evaluator().evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, byFrame, byFrame, out)
        assertTrue(out.active)
        out.reset()
        assertFalse(out.active)
        assertFalse(out.maskByFrame)
    }

    @Test
    fun defaultCapabilityIsGeometricForBackwardCompatibility() {
        assertFalse(StyleCapabilities().frameReveal)
        val out = TransitionParams()
        val caps = StyleCapabilities(mask = true)
        evaluator().evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, caps, caps, out)
        assertFalse(out.maskByFrame)
        assertTrue(out.maskEnabled)
    }
}