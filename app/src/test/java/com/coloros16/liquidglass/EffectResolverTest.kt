package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.EffectBlockReason
import com.coloros16.liquidglass.engine.effect.EffectCapabilities
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectResolver
import com.coloros16.liquidglass.engine.effect.EffectSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectResolverTest {

    private fun settings(): EffectSettings = EffectSettings.defaults()

    private val noCaps = EffectCapabilities.NONE
    private val panoramaOnly = EffectCapabilities.NONE.copy(panoramaAodActive = true)
    private val panoramaGyro = panoramaOnly.copy(hasGyro = true)

    @Test
    fun noneEffectsPassWithoutCapabilities() {
        val resolved = EffectResolver.resolve(settings(), noCaps)
        assertEquals(EffectBlockReason.NONE, resolved.lockWallpaper.reason)
        assertTrue(resolved.lockWallpaper.noop)
        assertTrue(resolved.lockSpecial.noop)
        assertTrue(resolved.homeTransition.noop)
    }

    @Test
    fun unknownIdReported() {
        val s = settings()
        s.lockWallpaperEffect = "lock.wp.unknown"
        val resolved = EffectResolver.resolve(s, noCaps)
        assertEquals(EffectBlockReason.UNKNOWN_ID, resolved.lockWallpaper.reason)
        assertEquals(EffectCatalog.LOCK_WP_NONE, resolved.lockWallpaper.effectiveId)
    }

    @Test
    fun unimplementedDepth3dIsBlockedFirst() {
        val s = settings()
        s.lockSpecialEffect = EffectCatalog.LOCK_SP_DEPTH_3D
        val resolved = EffectResolver.resolve(s, panoramaOnly.copy(photoSceneAvailable = true))
        assertEquals(EffectBlockReason.UNIMPLEMENTED, resolved.lockSpecial.reason)
    }

    @Test
    fun vivoLiquidNeedsDynamicThenPanorama() {
        val s = settings()
        s.lockSpecialEffect = EffectCatalog.LOCK_SP_LIQUID

        assertEquals(EffectBlockReason.NEEDS_AOD_DYNAMIC, EffectResolver.resolve(s, noCaps).lockSpecial.reason)

        s.allowAodDynamic = true
        assertEquals(EffectBlockReason.NEEDS_PANORAMA_AOD, EffectResolver.resolve(s, noCaps).lockSpecial.reason)

        val ok = EffectResolver.resolve(s, panoramaOnly)
        assertEquals(EffectBlockReason.NONE, ok.lockSpecial.reason)
        assertTrue(ok.lockSpecial.applied)
    }

    @Test
    fun lenticularAlsoNeedsGyro() {
        val s = settings()
        s.allowAodDynamic = true
        s.lockSpecialEffect = EffectCatalog.LOCK_SP_LENTICULAR
        assertEquals(
            EffectBlockReason.NEEDS_GYRO,
            EffectResolver.resolve(s, panoramaOnly).lockSpecial.reason,
        )
        assertEquals(
            EffectBlockReason.NONE,
            EffectResolver.resolve(s, panoramaGyro).lockSpecial.reason,
        )
    }

    @Test
    fun bokehNeedsDynamicOnly() {
        val s = settings()
        s.lockSpecialEffect = EffectCatalog.LOCK_SP_BOKEH
        assertEquals(EffectBlockReason.NEEDS_AOD_DYNAMIC, EffectResolver.resolve(s, noCaps).lockSpecial.reason)
        s.allowAodDynamic = true
        assertEquals(EffectBlockReason.NONE, EffectResolver.resolve(s, noCaps).lockSpecial.reason)
    }

    @Test
    fun wallpaperEffectsNeedNoCapabilities() {
        val s = settings()
        s.lockWallpaperEffect = EffectCatalog.LOCK_WP_RIPPLE
        val resolved = EffectResolver.resolve(s, noCaps)
        assertEquals(EffectBlockReason.NONE, resolved.lockWallpaper.reason)
        assertEquals(EffectCatalog.LOCK_WP_RIPPLE, resolved.lockWallpaper.effectiveId)
        assertFalse(resolved.lockWallpaper.noop)
    }

    @Test
    fun homeStyleFollowsAodUnlessIndependent() {
        val s = settings()
        s.aodStyleId = "aod.galaxy"
        s.homeAodStyleId = "aod.door"
        s.homeAodIndependent = false
        val follow = EffectResolver.resolve(s, noCaps)
        assertTrue(follow.homeUsesAodStyle)
        assertEquals("aod.galaxy", follow.homeAodStyleId)

        s.homeAodIndependent = true
        val independent = EffectResolver.resolve(s, noCaps)
        assertFalse(independent.homeUsesAodStyle)
        assertEquals("aod.door", independent.homeAodStyleId)
    }

    @Test
    fun crossfadeProfileOnlyForCrossfade() {
        assertNull(com.coloros16.liquidglass.engine.effect.HomeMotion.profileFor(EffectCatalog.HOME_TRANSITION_NONE))
        val profile = com.coloros16.liquidglass.engine.effect.HomeMotion.profileFor(EffectCatalog.HOME_TRANSITION_CROSSFADE)
        assertTrue(profile != null)
        assertTrue(profile!!.content.enabled)
        assertFalse(profile.scene.enabled)
    }

    @Test
    fun aodToHomeUsesAodStyle() {
        val s = settings()
        s.aodStyleId = "aod.sail"
        s.homeTransition = EffectCatalog.HOME_TRANSITION_CROSSFADE
        assertEquals(
            "aod.sail",
            com.coloros16.liquidglass.engine.effect.HomeMotion.transitionForEdge(TransitionEdge.AOD_TO_HOME, s),
        )
        assertEquals(
            EffectCatalog.HOME_TRANSITION_CROSSFADE,
            com.coloros16.liquidglass.engine.effect.HomeMotion.transitionForEdge(TransitionEdge.LOCK_TO_HOME, s),
        )
    }
}
