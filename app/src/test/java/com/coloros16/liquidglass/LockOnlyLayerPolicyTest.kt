package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.LockOnlyLayerPolicy
import com.coloros16.liquidglass.engine.LockOnlyLayerPolicy.Kind
import com.coloros16.liquidglass.engine.edit.EditSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockOnlyLayerPolicyTest {

    @Test
    fun widgetsAndPillsAreLockOnly() {
        assertTrue(LockOnlyLayerPolicy.lockOnly(Kind.WIDGETS))
        assertTrue(LockOnlyLayerPolicy.lockOnly(Kind.PILLS))
    }

    @Test
    fun clockAndContentArePerSurface() {
        assertFalse(LockOnlyLayerPolicy.lockOnly(Kind.CLOCK))
        assertFalse(LockOnlyLayerPolicy.lockOnly(Kind.CONTENT))
    }

    @Test
    fun widgetsMountWhenLockGateOpenEvenFromAodPump() {
        assertTrue(
            "宿主是锁屏根，息屏面泵到它也该按锁屏闸判",
            LockOnlyLayerPolicy.shouldMount(Kind.WIDGETS, EditSurface.AOD, lockGate = true, aodGate = false),
        )
    }

    @Test
    fun widgetsNeverMountWhenLockGateClosedEvenIfAodOpen() {
        assertFalse(
            LockOnlyLayerPolicy.shouldMount(Kind.WIDGETS, EditSurface.AOD, lockGate = false, aodGate = true),
        )
        assertFalse(
            LockOnlyLayerPolicy.shouldMount(Kind.WIDGETS, EditSurface.LOCK, lockGate = false, aodGate = true),
        )
    }

    @Test
    fun pillsFollowSameRuleAsWidgets() {
        for (s in EditSurface.values()) {
            for (lg in booleanArrayOf(true, false)) {
                for (ag in booleanArrayOf(true, false)) {
                    assertEquals(
                        "surface=$s lock=$lg aod=$ag",
                        LockOnlyLayerPolicy.shouldMount(Kind.WIDGETS, s, lg, ag),
                        LockOnlyLayerPolicy.shouldMount(Kind.PILLS, s, lg, ag),
                    )
                }
            }
        }
    }

    @Test
    fun clockFollowsCurrentSurfaceGate() {
        assertTrue(LockOnlyLayerPolicy.shouldMount(Kind.CLOCK, EditSurface.AOD, lockGate = false, aodGate = true))
        assertFalse(LockOnlyLayerPolicy.shouldMount(Kind.CLOCK, EditSurface.AOD, lockGate = true, aodGate = false))
        assertTrue(LockOnlyLayerPolicy.shouldMount(Kind.CLOCK, EditSurface.LOCK, lockGate = true, aodGate = false))
        assertFalse(LockOnlyLayerPolicy.shouldMount(Kind.CLOCK, EditSurface.LOCK, lockGate = false, aodGate = true))
    }

    @Test
    fun lockOnlyLayersIgnoreAodGateEntirely() {
        for (s in EditSurface.values()) {
            for (lg in booleanArrayOf(true, false)) {
                assertEquals(
                    LockOnlyLayerPolicy.shouldMount(Kind.WIDGETS, s, lg, aodGate = false),
                    LockOnlyLayerPolicy.shouldMount(Kind.WIDGETS, s, lg, aodGate = true),
                )
            }
        }
    }

    @Test
    fun releaseOnlyWhenMountedAndGateClosed() {
        assertTrue(
            LockOnlyLayerPolicy.shouldRelease(Kind.WIDGETS, mounted = true, lockGate = false, aodGate = true, current = EditSurface.AOD),
        )
        assertFalse(
            "没挂过就不必撕",
            LockOnlyLayerPolicy.shouldRelease(Kind.WIDGETS, mounted = false, lockGate = false, aodGate = true, current = EditSurface.AOD),
        )
        assertFalse(
            "闸开着不撕",
            LockOnlyLayerPolicy.shouldRelease(Kind.WIDGETS, mounted = true, lockGate = true, aodGate = false, current = EditSurface.AOD),
        )
    }

    @Test
    fun mountAndReleaseAreComplementaryForMountedLayer() {
        for (k in Kind.values()) {
            for (s in EditSurface.values()) {
                for (lg in booleanArrayOf(true, false)) {
                    for (ag in booleanArrayOf(true, false)) {
                        val mount = LockOnlyLayerPolicy.shouldMount(k, s, lg, ag)
                        val release = LockOnlyLayerPolicy.shouldRelease(k, true, lg, ag, s)
                        assertTrue("$k $s lock=$lg aod=$ag", mount != release)
                    }
                }
            }
        }
    }

    @Test
    fun bothGatesClosedMeansNothingMounts() {
        for (k in Kind.values()) {
            for (s in EditSurface.values()) {
                assertFalse(LockOnlyLayerPolicy.shouldMount(k, s, lockGate = false, aodGate = false))
            }
        }
    }

    @Test
    fun kindTableCoversEveryKind() {
        assertEquals(4, Kind.values().size)
        assertEquals(2, Kind.values().count { LockOnlyLayerPolicy.lockOnly(it) })
    }
}