package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.CapabilityProbe
import com.coloros16.liquidglass.engine.CapabilityResult
import com.coloros16.liquidglass.engine.CapabilityState
import com.coloros16.liquidglass.engine.ClockSource
import com.coloros16.liquidglass.engine.FuseState
import com.coloros16.liquidglass.engine.PlatformStateHub
import com.coloros16.liquidglass.engine.SafetyFuse
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionClock
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import com.coloros16.liquidglass.engine.transitionEdgeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformKitTest {

    // ---- TransitionEdge ----

    @Test
    fun allSixDirectedEdgesResolveForEveryPair() {
        val states = SceneState.values().toList()
        var pairs = 0
        for (from in states) {
            for (to in states) {
                if (from == to) {
                    assertNull(transitionEdgeOf(from, to))
                    continue
                }
                val edge = transitionEdgeOf(from, to)
                assertNotNull(edge)
                assertEquals(from, edge!!.from)
                assertEquals(to, edge.to)
                pairs++
            }
        }
        assertEquals(6, pairs)
    }

    @Test
    fun directAodHomeEdgesExist() {
        assertEquals(TransitionEdge.AOD_TO_HOME, transitionEdgeOf(SceneState.AOD, SceneState.HOME))
        assertEquals(TransitionEdge.HOME_TO_AOD, transitionEdgeOf(SceneState.HOME, SceneState.AOD))
    }

    // ---- TransitionClock ----

    @Test
    fun clockClampsAndReportsManualSource() {
        val clock = TransitionClock { 0L }
        clock.seek(TransitionEdge.AOD_TO_LOCK, 1.7f)
        val s = clock.state()
        assertEquals(1f, s.t, 0f)
        assertEquals(ClockSource.MANUAL, s.source)
        assertEquals(TransitionEdge.AOD_TO_LOCK, s.edge)
        assertFalse(s.playing)

        clock.seek(TransitionEdge.AOD_TO_LOCK, Float.NaN)
        assertEquals(0f, clock.state().t, 0f)
    }

    @Test
    fun clockSetTSameValueDoesNotBumpRevision() {
        val clock = TransitionClock { 0L }
        clock.setT(TransitionEdge.LOCK_TO_AOD, 0.5f, ClockSource.SYSTEM_PUMP)
        val r1 = clock.state().revision
        clock.setT(TransitionEdge.LOCK_TO_AOD, 0.5f, ClockSource.SYSTEM_PUMP)
        assertEquals(r1, clock.state().revision)
    }

    @Test
    fun clockPlayAdvancesAndStopsAtOne() {
        var now = 1_000L
        val clock = TransitionClock { now }
        var notifications = 0
        clock.subscribe { _, _, _, _, _ -> notifications++ }

        clock.play(TransitionEdge.LOCK_TO_HOME, durationMs = 200L)
        assertTrue(clock.state().playing)

        now = 1_100L
        assertTrue(clock.advance(now))
        assertEquals(0.5f, clock.state().t, 0.001f)

        now = 1_250L
        assertFalse(clock.advance(now))
        assertEquals(1f, clock.state().t, 0f)
        assertFalse(clock.state().playing)
        assertTrue(notifications >= 3)
    }

    @Test
    fun clockPauseStopsAdvance() {
        val clock = TransitionClock { 0L }
        clock.play(TransitionEdge.HOME_TO_LOCK, 500L)
        clock.pause()
        assertFalse(clock.advance(400L))
        assertEquals(0f, clock.state().t, 0f)
    }

    @Test
    fun clockResetClearsEdge() {
        val clock = TransitionClock { 0L }
        clock.seek(TransitionEdge.LOCK_TO_HOME, 0.3f)
        clock.reset()
        assertNull(clock.state().edge)
        assertEquals(0f, clock.state().t, 0f)
    }

    // ---- CapabilityProbe ----

    @Test
    fun probeCachesOkResult() {
        var calls = 0
        val probe = CapabilityProbe { 0L }
        probe.register("layer.l2", okTtlMs = 1_000L) {
            calls++
            CapabilityResult(CapabilityState.OK)
        }
        probe.check("layer.l2")
        probe.check("layer.l2")
        assertEquals(1, calls)
    }

    @Test
    fun probeReprobesAfterTtl() {
        var now = 0L
        var calls = 0
        val probe = CapabilityProbe { now }
        probe.register("layer.l2", okTtlMs = 100L) {
            calls++
            CapabilityResult(CapabilityState.OK)
        }
        probe.check("layer.l2")
        now = 150L
        probe.check("layer.l2")
        assertEquals(2, calls)
    }

    @Test
    fun probeUnregisteredIsUnsupported() {
        val probe = CapabilityProbe { 0L }
        val r = probe.check("missing")
        assertEquals(CapabilityState.UNSUPPORTED, r.state)
        assertEquals(CapabilityProbe.REASON_NOT_REGISTERED, r.reasonCode)
        assertFalse(probe.isUsable("missing"))
    }

    @Test
    fun probeThrowingIsDegradedAndRetriesSoon() {
        var now = 0L
        var calls = 0
        val probe = CapabilityProbe { now }
        probe.register("layer.l1", failTtlMs = 10L) {
            calls++
            throw IllegalStateException("boom")
        }
        val r = probe.check("layer.l1")
        assertEquals(CapabilityState.DEGRADED, r.state)
        assertEquals(CapabilityProbe.REASON_PROBE_ERROR, r.reasonCode)
        assertTrue(probe.isUsable("layer.l1"))

        now = 20L
        probe.check("layer.l1")
        assertEquals(2, calls)
    }

    @Test
    fun probeInvalidateForcesRecheck() {
        var calls = 0
        val probe = CapabilityProbe { 0L }
        probe.register("layer.l3") {
            calls++
            CapabilityResult(CapabilityState.OK)
        }
        probe.check("layer.l3")
        probe.invalidate("layer.l3")
        probe.check("layer.l3")
        assertEquals(2, calls)
    }

    @Test
    fun probeCachedReportsDoesNotProbe() {
        var calls = 0
        val probe = CapabilityProbe { 0L }
        probe.register("layer.l3") {
            calls++
            CapabilityResult(CapabilityState.OK)
        }
        assertTrue(probe.cachedReports().isEmpty())
        assertEquals(0, calls)
        probe.checkAll()
        assertEquals(1, probe.cachedReports().size)
    }

    // ---- SafetyFuse ----

    @Test
    fun fuseTripsOnceAtThreshold() {
        val fuse = SafetyFuse { 1_000L }
        var trips = 0
        fuse.register("aod", SafetyFuse.Policy(threshold = 2, windowMs = 10_000L)) { trips++ }

        assertFalse(fuse.recordFailure("aod", "hook_miss"))
        assertTrue(fuse.recordFailure("aod", "hook_miss"))
        assertFalse(fuse.recordFailure("aod", "hook_miss"))

        assertEquals(1, trips)
        assertTrue(fuse.isTripped("aod"))
        assertEquals(FuseState.TRIPPED, fuse.report("aod")?.state)
    }

    @Test
    fun fuseWindowPrunesOldFailures() {
        var now = 0L
        val fuse = SafetyFuse { now }
        var trips = 0
        fuse.register("aod", SafetyFuse.Policy(threshold = 2, windowMs = 100L)) { trips++ }

        fuse.recordFailure("aod")
        now = 500L
        fuse.recordFailure("aod")
        assertEquals(0, trips)
        assertEquals(1, fuse.report("aod")?.failures)
    }

    @Test
    fun fuseSuccessClearsWindowButKeepsTripped() {
        var now = 0L
        val fuse = SafetyFuse { now }
        fuse.register("aod", SafetyFuse.Policy(threshold = 2, windowMs = 100L)) {}
        fuse.recordFailure("aod")
        fuse.recordSuccess("aod")
        assertEquals(0, fuse.report("aod")?.failures)
        now = 10L
        assertFalse(fuse.recordFailure("aod"))

        fuse.register("aod", SafetyFuse.Policy(threshold = 1, windowMs = 100L)) {}
        fuse.recordFailure("aod")
        assertTrue(fuse.isTripped("aod"))
        fuse.recordSuccess("aod")
        assertTrue(fuse.isTripped("aod"))
        fuse.reset("aod")
        assertFalse(fuse.isTripped("aod"))
        assertEquals(FuseState.CLOSED, fuse.report("aod")?.state)
    }

    @Test
    fun fuseUnknownDomainIgnored() {
        val fuse = SafetyFuse { 0L }
        assertFalse(fuse.recordFailure("nope"))
        assertNull(fuse.report("nope"))
    }

    // ---- PlatformStateHub ----

    @Test
    fun hubPushesStructuralChangeOnly() {
        val clock = TransitionClock { 0L }
        val hub = PlatformStateHub(clock = clock)
        var pushes = 0
        hub.subscribe { pushes++ }

        clock.setT(TransitionEdge.AOD_TO_LOCK, 0.1f, ClockSource.SYSTEM_PUMP)
        assertEquals(1, pushes)

        clock.setT(TransitionEdge.AOD_TO_LOCK, 0.4f, ClockSource.SYSTEM_PUMP)
        assertEquals(1, pushes)

        clock.play(TransitionEdge.AOD_TO_LOCK, 100L)
        assertEquals(2, pushes)
    }

    @Test
    fun hubChangeSignalOnlyOnStructuralChange() {
        val clock = TransitionClock { 0L }
        val hub = PlatformStateHub(clock = clock)
        var signals = 0
        hub.changeSignal = { signals++ }

        clock.seek(TransitionEdge.LOCK_TO_HOME, 0.2f)
        clock.seek(TransitionEdge.LOCK_TO_HOME, 0.8f)
        assertEquals(1, signals)

        hub.notifyChanged()
        assertEquals(2, signals)
    }

    @Test
    fun hubSnapshotCarriesRevisionAndClock() {
        val clock = TransitionClock { 0L }
        val hub = PlatformStateHub(clock = clock)
        clock.seek(TransitionEdge.HOME_TO_LOCK, 0.6f)
        val snap = hub.snapshot()
        assertTrue(snap.revision >= 1L)
        assertEquals(0.6f, snap.clock.t, 0.001f)
        assertTrue(snap.capabilities.isEmpty())
        assertTrue(snap.fuses.isEmpty())
    }
}
