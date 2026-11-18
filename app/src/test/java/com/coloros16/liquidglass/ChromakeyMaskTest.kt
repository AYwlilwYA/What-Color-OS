package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ChromakeyMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromakeyMaskTest {

    private val accent = floatArrayOf(0.8f, 0.2f, 0.1f)
    private val content = floatArrayOf(0.3f, 0.6f, 0.9f)
    private val out = FloatArray(3)

    @Test
    fun revealWeightIsGreenChannelNormalized() {
        assertEquals(0f, ChromakeyMask.revealWeight(0), 1e-6f)
        assertEquals(1f, ChromakeyMask.revealWeight(255), 1e-6f)
        assertEquals(0.5f, ChromakeyMask.revealWeight(128), 0.004f)
    }

    @Test
    fun weightsClampOutOfRangeBytes() {
        assertEquals(0f, ChromakeyMask.revealWeight(-40), 1e-6f)
        assertEquals(1f, ChromakeyMask.revealWeight(900), 1e-6f)
        assertEquals(0f, ChromakeyMask.accentWeight(-1), 1e-6f)
        assertEquals(1f, ChromakeyMask.whiteWeight(300), 1e-6f)
    }

    @Test
    fun allZeroChannelsGiveBlack() {
        ChromakeyMask.blend(0, 0, 0, accent, content, out)
        for (i in 0..2) assertEquals(0f, out[i], 1e-6f)
    }

    @Test
    fun fullGreenGivesContentUnchanged() {
        ChromakeyMask.blend(255, 255, 255, accent, content, out)
        for (i in 0..2) assertEquals(content[i], out[i], 1e-6f)
    }

    @Test
    fun accentOnlyGivesAccent() {
        ChromakeyMask.blend(255, 0, 0, accent, content, out)
        for (i in 0..2) assertEquals(accent[i], out[i], 1e-6f)
    }

    @Test
    fun whiteOverridesAccentWhenGreenIsZero() {
        ChromakeyMask.blend(255, 0, 255, accent, content, out)
        for (i in 0..2) assertEquals(1f, out[i], 1e-6f)
    }

    @Test
    fun channelOrderIsAccentThenWhiteThenContent() {
        ChromakeyMask.blend(255, 128, 255, accent, content, out)
        val g = ChromakeyMask.revealWeight(128)
        for (i in 0..2) assertEquals(1f + (content[i] - 1f) * g, out[i], 1e-5f)
    }

    @Test
    fun blendIsMonotonicTowardsContentAsGreenGrows() {
        var previous = -1f
        for (g in 0..255 step 5) {
            ChromakeyMask.blend(255, g, 0, accent, content, out)
            assertTrue(out[2] >= previous - 1e-6f)
            previous = out[2]
        }
    }

    @Test
    fun outputStaysInUnitRange() {
        for (r in intArrayOf(0, 77, 255)) {
            for (g in intArrayOf(0, 128, 255)) {
                for (b in intArrayOf(0, 200, 255)) {
                    ChromakeyMask.blend(r, g, b, accent, content, out)
                    for (i in 0..2) assertTrue(out[i] in 0f..1f)
                }
            }
        }
    }

    @Test
    fun onlyPhotoStylesRevealByFrame() {
        for (id in 1..5) assertTrue(ChromakeyMask.revealsByFrame(id))
        for (id in intArrayOf(-3, 0, 6, 101)) assertFalse(ChromakeyMask.revealsByFrame(id))
    }

    @Test
    fun revealThresholdsAreExclusiveAtEnds() {
        assertTrue(ChromakeyMask.notRevealed(0f))
        assertFalse(ChromakeyMask.notRevealed(0.01f))
        assertTrue(ChromakeyMask.fullyRevealed(1f))
        assertFalse(ChromakeyMask.fullyRevealed(0.99f))
    }

    @Test
    fun channelIndicesMatchRgbOrder() {
        assertEquals(0, ChromakeyMask.CHANNEL_ACCENT)
        assertEquals(1, ChromakeyMask.CHANNEL_REVEAL)
        assertEquals(2, ChromakeyMask.CHANNEL_WHITE)
    }
}