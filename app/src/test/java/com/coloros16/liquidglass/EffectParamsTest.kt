package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectParamsTest {

    @Test
    fun idsAreUnique() {
        val ids = EffectParams.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun defaultsInsideRange() {
        for (param in EffectParams.all) {
            assertTrue("${param.id} min>max", param.min <= param.max)
            assertTrue("${param.id} default below min", param.default >= param.min)
            assertTrue("${param.id} default above max", param.default <= param.max)
            assertTrue("${param.id} step must be positive", param.step > 0f)
        }
    }

    @Test
    fun clampBoundsAndNan() {
        val id = EffectParams.AOD_COLOR_MIX
        assertEquals(100f, EffectParams.clamp(id, 999f), 0f)
        assertEquals(0f, EffectParams.clamp(id, -5f), 0f)
        assertEquals(60f, EffectParams.clamp(id, Float.NaN), 0f)
        assertEquals(42f, EffectParams.clamp(id, 42f), 0f)
    }

    @Test
    fun quantizeSnapsToStep() {
        // step 0.5 -> 6.3 snaps to 6.5
        assertEquals(6.5f, EffectParams.quantize(EffectParams.AOD_PHOTO_TILT, 6.3f), 1e-4f)
        // step 20 -> 512 snaps to 520
        assertEquals(520f, EffectParams.quantize(EffectParams.HOME_TRANSITION_DURATION, 512f), 1e-4f)
    }

    @Test
    fun unknownParamUntouched() {
        assertNull(EffectParams.find("no.such.param"))
        assertEquals(7f, EffectParams.clamp("no.such.param", 7f), 0f)
        assertTrue(EffectParams.defaults().isNotEmpty())
    }

    @Test
    fun speedDefaultsAreInRange() {
        val speed = EffectParams.find(EffectParams.LOCK_WP_SPEED)!!
        assertFalse(speed.default < speed.min)
        assertTrue(EffectParams.clamp(speed.id, 0f) >= speed.min)
    }
}
