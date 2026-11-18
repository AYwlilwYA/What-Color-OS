package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodSurfaceGate
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodSurfaceGateTest {

    @Test
    fun globalNeedsBothMasterAndHandoff() {
        assertTrue(AodSurfaceGate.global(true, true))
        assertFalse(AodSurfaceGate.global(true, false))
        assertFalse(AodSurfaceGate.global(false, true))
        assertFalse(AodSurfaceGate.global(false, false))
    }

    @Test
    fun aodSurfaceFollowsScreenKeyOnly() {
        assertTrue(AodSurfaceGate.surface(true, SceneState.AOD, screenOn = true, lockShowOn = false))
        assertFalse(AodSurfaceGate.surface(true, SceneState.AOD, screenOn = false, lockShowOn = true))
    }

    @Test
    fun lockSurfaceFollowsLockShowKeyOnly() {
        assertTrue(AodSurfaceGate.surface(true, SceneState.LOCK, screenOn = false, lockShowOn = true))
        assertFalse(AodSurfaceGate.surface(true, SceneState.LOCK, screenOn = true, lockShowOn = false))
    }

    @Test
    fun homeSurfaceNeverOwnedByThisProcess() {
        assertFalse(AodSurfaceGate.surface(true, SceneState.HOME, screenOn = true, lockShowOn = true))
    }

    @Test
    fun globalOffOverridesEverySurfaceKey() {
        for (s in SceneState.values()) {
            assertFalse(AodSurfaceGate.surface(false, s, screenOn = true, lockShowOn = true))
        }
    }

    @Test
    fun bothKeysOffMeansNoSurface() {
        assertFalse(AodSurfaceGate.surface(true, SceneState.AOD, false, false))
        assertFalse(AodSurfaceGate.surface(true, SceneState.LOCK, false, false))
    }

    @Test
    fun aodLockEdgePassesWhenEitherSideIsOpen() {
        val e = TransitionEdge.AOD_TO_LOCK
        assertTrue(AodSurfaceGate.edge(true, e, screenOn = true, lockShowOn = false))
        assertTrue(AodSurfaceGate.edge(true, e, screenOn = false, lockShowOn = true))
        assertTrue(AodSurfaceGate.edge(true, e, screenOn = true, lockShowOn = true))
        assertFalse(AodSurfaceGate.edge(true, e, screenOn = false, lockShowOn = false))
    }

    @Test
    fun reverseEdgeIsSymmetricToForward() {
        for (pair in listOf(
            TransitionEdge.AOD_TO_LOCK to TransitionEdge.LOCK_TO_AOD,
            TransitionEdge.LOCK_TO_HOME to TransitionEdge.HOME_TO_LOCK,
            TransitionEdge.AOD_TO_HOME to TransitionEdge.HOME_TO_AOD,
        )) {
            for (a in booleanArrayOf(true, false)) {
                for (b in booleanArrayOf(true, false)) {
                    assertEquals(
                        "$pair screen=$a lock=$b",
                        AodSurfaceGate.edge(true, pair.first, a, b),
                        AodSurfaceGate.edge(true, pair.second, a, b),
                    )
                }
            }
        }
    }

    @Test
    fun homeEdgeOnlyPassesViaItsNonHomeSide() {
        assertTrue(AodSurfaceGate.edge(true, TransitionEdge.LOCK_TO_HOME, false, true))
        assertFalse(AodSurfaceGate.edge(true, TransitionEdge.LOCK_TO_HOME, true, false))
        assertTrue(AodSurfaceGate.edge(true, TransitionEdge.AOD_TO_HOME, true, false))
        assertFalse(AodSurfaceGate.edge(true, TransitionEdge.AOD_TO_HOME, false, true))
    }

    @Test
    fun globalOffBlocksEveryEdge() {
        for (e in TransitionEdge.values()) {
            assertFalse(AodSurfaceGate.edge(false, e, screenOn = true, lockShowOn = true))
        }
    }

    @Test
    fun fingerprintIsUniquePerCombination() {
        val seen = HashSet<Int>()
        for (g in booleanArrayOf(true, false)) {
            for (s in booleanArrayOf(true, false)) {
                for (l in booleanArrayOf(true, false)) {
                    assertTrue(seen.add(AodSurfaceGate.fingerprint(g, s, l)))
                }
            }
        }
        assertEquals(8, seen.size)
    }

    @Test
    fun fingerprintStableForSameInput() {
        val a = AodSurfaceGate.fingerprint(true, false, true)
        val b = AodSurfaceGate.fingerprint(true, false, true)
        assertEquals(a, b)
    }

    @Test
    fun defaultOffKeysKeepEverythingShut() {
        val g = AodSurfaceGate.global(true, true)
        assertFalse(AodSurfaceGate.surface(g, SceneState.AOD, false, false))
        assertFalse(AodSurfaceGate.surface(g, SceneState.LOCK, false, false))
        for (e in TransitionEdge.values()) {
            assertFalse(AodSurfaceGate.edge(g, e, false, false))
        }
    }
}