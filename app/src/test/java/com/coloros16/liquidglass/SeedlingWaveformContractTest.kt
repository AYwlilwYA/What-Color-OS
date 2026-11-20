package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingWaveform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 波形求值契约：幅值域/呼吸域/圆点分支/条间差异，含阳性对照。 */
class SeedlingWaveformContractTest {

    @Test
    fun amplitudeWithinBounds() {
        for (bar in 0..3) {
            for (t in 0..2000) {
                val a = SeedlingWaveform.amplitude(bar, t / 100.0)
                assertTrue("bar=$bar t=$t amp=$a 越下界", a >= 0.2f - 1e-4f)
                assertTrue("bar=$bar t=$t amp=$a 越上界", a <= 1.0f)
            }
        }
    }

    @Test
    fun breathWithin09And1() {
        for (t in 0..2000) {
            val b = SeedlingWaveform.breath(t / 100.0)
            assertTrue(b >= 0.9f - 1e-4f)
            assertTrue(b <= 1.0f + 1e-4f)
        }
    }

    @Test
    fun barsDifferAtSameTime_positiveControl() {
        var differ = false
        for (t in 0..100) {
            val a0 = SeedlingWaveform.amplitude(0, t / 10.0)
            val a1 = SeedlingWaveform.amplitude(1, t / 10.0)
            if (kotlin.math.abs(a0 - a1) > 0.01f) differ = true
        }
        assertTrue("阳性对照：不同条相位/增益必产生差异（非四根同摆）", differ)
    }

    @Test
    fun dotBranchReachable() {
        var sawDot = false
        var sawBar = false
        for (bar in 0..3) {
            for (t in 0..2000) {
                val a = SeedlingWaveform.amplitude(bar, t / 100.0)
                if (a < 0.5f) sawDot = true else sawBar = true
                assertEquals(a < 0.5f, SeedlingWaveform.isDot(bar, t / 100.0))
            }
        }
        assertTrue("低幅圆点分支可达", sawDot)
        assertTrue("高幅竖条分支可达", sawBar)
    }

    @Test
    fun animatesOverTime_positiveControl() {
        var changed = false
        val base = SeedlingWaveform.amplitude(2, 0.0)
        for (t in 1..100) {
            if (kotlin.math.abs(SeedlingWaveform.amplitude(2, t / 100.0) - base) > 0.01f) changed = true
        }
        assertTrue("阳性对照：幅值随时间变化（真动画非静帧）", changed)
    }

    @Test
    fun barIndexClamped() {
        assertEquals(SeedlingWaveform.amplitude(0, 1.0), SeedlingWaveform.amplitude(-3, 1.0), 1e-6f)
        assertEquals(SeedlingWaveform.amplitude(3, 1.0), SeedlingWaveform.amplitude(9, 1.0), 1e-6f)
    }
}
