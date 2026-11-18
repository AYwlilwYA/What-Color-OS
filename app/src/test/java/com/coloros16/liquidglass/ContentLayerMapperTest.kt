package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ContentLayerMapper
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentLayerMapperTest {

    private val poses = ContentLayerMapper.Poses()

    private fun params(edge: TransitionEdge?): TransitionParams {
        val p = TransitionParams()
        p.active = edge != null
        p.edge = edge
        p.t = 0.4f
        p.srcAlpha = 0.6f
        p.dstAlpha = 0.4f
        p.srcScale = 1.08f
        p.dstScale = 0.94f
        p.srcDx = -30f
        p.srcDy = 12f
        p.dstDx = 20f
        p.dstDy = -8f
        return p
    }

    @Test
    fun nullEdgeLeavesAllIdentity() {
        ContentLayerMapper.map(params(null), poses)
        assertTrue(poses.aod.identity)
        assertTrue(poses.lock.identity)
        assertTrue(poses.home.identity)
    }

    @Test
    fun inactiveParamsLeaveAllIdentity() {
        val p = params(TransitionEdge.AOD_TO_LOCK)
        p.active = false
        ContentLayerMapper.map(p, poses)
        assertTrue(poses.aod.identity)
        assertTrue(poses.lock.identity)
    }

    @Test
    fun aodToLockMapsSrcToAodDstToLock() {
        ContentLayerMapper.map(params(TransitionEdge.AOD_TO_LOCK), poses)
        assertFalse(poses.aod.identity)
        assertFalse(poses.lock.identity)
        assertTrue(poses.home.identity)
        assertEquals(0.6f, poses.aod.alpha, 0.0001f)
        assertEquals(1.08f, poses.aod.scale, 0.0001f)
        assertEquals(-30f, poses.aod.dx, 0.0001f)
        assertEquals(12f, poses.aod.dy, 0.0001f)
        assertEquals(0.4f, poses.lock.alpha, 0.0001f)
        assertEquals(0.94f, poses.lock.scale, 0.0001f)
        assertEquals(20f, poses.lock.dx, 0.0001f)
        assertEquals(-8f, poses.lock.dy, 0.0001f)
    }

    @Test
    fun everyEdgeMapsExactlyTwoStates() {
        for (edge in TransitionEdge.values()) {
            ContentLayerMapper.map(params(edge), poses)
            val touched = listOf(poses.aod, poses.lock, poses.home).count { !it.identity }
            assertEquals(edge.name, 2, touched)
            assertFalse("$edge from", poses.of(edge.from).identity)
            assertFalse("$edge to", poses.of(edge.to).identity)
        }
    }

    @Test
    fun reverseEdgeSwapsRoles() {
        ContentLayerMapper.map(params(TransitionEdge.LOCK_TO_AOD), poses)
        assertEquals(0.6f, poses.lock.alpha, 0.0001f)
        assertEquals(0.4f, poses.aod.alpha, 0.0001f)
        assertTrue(poses.home.identity)
    }

    @Test
    fun homeEdgesTouchHomePose() {
        ContentLayerMapper.map(params(TransitionEdge.LOCK_TO_HOME), poses)
        assertFalse(poses.home.identity)
        assertEquals(0.4f, poses.home.alpha, 0.0001f)
        assertTrue(poses.aod.identity)
    }

    @Test
    fun remapResetsPreviousNonIdentity() {
        ContentLayerMapper.map(params(TransitionEdge.LOCK_TO_HOME), poses)
        assertFalse(poses.home.identity)
        ContentLayerMapper.map(params(TransitionEdge.AOD_TO_LOCK), poses)
        assertTrue(poses.home.identity)
        assertEquals(1f, poses.home.alpha, 0.0001f)
        assertEquals(0f, poses.home.dx, 0.0001f)
    }

    @Test
    fun neutralDetectsSystemDefault() {
        val pose = ContentLayerMapper.Pose()
        pose.set(1f, 1f, 0f, 0f)
        assertTrue(ContentLayerMapper.isNeutral(pose))
        pose.set(0.999f, 1.0001f, 0.2f, -0.2f)
        assertTrue(ContentLayerMapper.isNeutral(pose))
    }

    @Test
    fun neutralRejectsVisibleChange() {
        val pose = ContentLayerMapper.Pose()
        pose.set(0.9f, 1f, 0f, 0f)
        assertFalse(ContentLayerMapper.isNeutral(pose))
        pose.set(1f, 1.02f, 0f, 0f)
        assertFalse(ContentLayerMapper.isNeutral(pose))
        pose.set(1f, 1f, 3f, 0f)
        assertFalse(ContentLayerMapper.isNeutral(pose))
    }

    @Test
    fun alphaIsClampedIntoRange() {
        val pose = ContentLayerMapper.Pose()
        pose.set(1.6f, 1f, 0f, 0f)
        assertEquals(1f, pose.alpha, 0.0001f)
        pose.set(-0.4f, 1f, 0f, 0f)
        assertEquals(0f, pose.alpha, 0.0001f)
    }

    @Test
    fun ofRoutesEachState() {
        assertTrue(poses.of(SceneState.AOD) === poses.aod)
        assertTrue(poses.of(SceneState.LOCK) === poses.lock)
        assertTrue(poses.of(SceneState.HOME) === poses.home)
    }
}