package com.coloros16.liquidglass

import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.glass.BuiltinGlassPresets
import com.coloros16.liquidglass.glass.GlassRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinStylePresetContractTest {

    private val all = BuiltinGlassPresets.all()

    @Test
    fun fiveStylesInDisplayOrder() {
        assertEquals(
            listOf("ios_clear", "ios_frost", "harmony_soft", "crystal", "classic_frost"),
            all.map { it.id.removePrefix(BuiltinGlassPresets.ID_PREFIX) },
        )
    }

    @Test
    fun everyParamKeyIsWhitelisted() {
        for (p in all) {
            for (key in p.params.keys) {
                assertTrue("key not in PARAM_KEYS: ${p.id} $key", key in GlassRegistry.PARAM_KEYS)
            }
        }
    }

    @Test
    fun booleanKeysAreSubsetOfParamsAndWhitelist() {
        assertTrue(BuiltinGlassPresets.BOOLEAN_PARAM_KEYS.isNotEmpty())
        for (key in BuiltinGlassPresets.BOOLEAN_PARAM_KEYS) {
            assertTrue("boolean key not whitelisted: $key", key in GlassRegistry.PARAM_KEYS)
        }
        for (p in all) {
            for (key in BuiltinGlassPresets.BOOLEAN_PARAM_KEYS) {
                val v = p.params[key]
                if (v != null) assertTrue("boolean encoding must be 0/1: ${p.id} $key=$v", v == 0f || v == 1f)
            }
        }
    }

    @Test
    fun positiveControlUnknownKeyIsNotWhitelisted() {
        assertFalse("no_such_param_key" in GlassRegistry.PARAM_KEYS)
    }

    @Test
    fun objectSwitchesCoverGlassTargetsNotMaster() {
        val keys = BuiltinGlassPresets.OBJECT_SWITCH_KEYS
        assertTrue(keys.size >= 20)
        assertFalse(Prefs.KEY_MASTER in keys)
        assertFalse(Prefs.KEY_GLASS_ENABLE in keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun seedIntoDoesNotDuplicateOrOverwrite() {
        val userEdited = GlassRegistry.Preset(
            BuiltinGlassPresets.ID_PREFIX + "ios_clear", "我的通透", emptyList(), mapOf(Prefs.KEY_BLUR_RADIUS to 99f),
        )
        val seeded = BuiltinGlassPresets.seedInto(listOf(userEdited))
        assertEquals(all.size, seeded.size)
        assertEquals(99f, seeded.first { it.id == userEdited.id }.params[Prefs.KEY_BLUR_RADIUS]!!, 0f)
        val empty = BuiltinGlassPresets.seedInto(emptyList())
        assertEquals(all.size, empty.size)
        assertEquals(empty, BuiltinGlassPresets.seedInto(empty))
    }

    @Test
    fun companionCoversCaptureAndTiltForEveryStyle() {
        for (p in all) {
            val c = BuiltinGlassPresets.companionOf(p.id)
            assertTrue("capture hz: ${p.id}", Prefs.KEY_PANEL_CAPTURE_HZ in c.ints)
            assertTrue("capture interval: ${p.id}", Prefs.KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS in c.ints)
            assertTrue("capture scale: ${p.id}", Prefs.KEY_BG_CAPTURE_SCALE in c.floats)
            assertTrue("tilt switch: ${p.id}", Prefs.KEY_ANIM_TILT_ENABLED in c.bools)
            val hz = c.ints[Prefs.KEY_PANEL_CAPTURE_HZ]!!
            assertTrue("hz range: ${p.id}", hz in Prefs.PANEL_CAPTURE_HZ_MIN..Prefs.PANEL_CAPTURE_HZ_MAX)
        }
    }

    @Test
    fun companionTiltFollowsStyleFamily() {
        assertTrue(BuiltinGlassPresets.companionOf("builtin_ios_clear").bools[Prefs.KEY_ANIM_TILT_ENABLED]!!)
        assertTrue(BuiltinGlassPresets.companionOf("builtin_ios_frost").bools[Prefs.KEY_ANIM_TILT_ENABLED]!!)
        assertFalse(BuiltinGlassPresets.companionOf("builtin_harmony_soft").bools[Prefs.KEY_ANIM_TILT_ENABLED]!!)
        assertFalse(BuiltinGlassPresets.companionOf("builtin_classic_frost").bools[Prefs.KEY_ANIM_TILT_ENABLED]!!)
    }

    @Test
    fun harmonySpringMatchesOfficialTruth() {
        val ints = BuiltinGlassPresets.harmonySpringInts()
        val openStiff = ints[Prefs.animKey(Prefs.ANIM_GROUP_OPEN, Prefs.ANIM_PARAM_X_STIFFNESS)]!!
        val closeStiff = ints[Prefs.animKey(Prefs.ANIM_GROUP_CLOSE, Prefs.ANIM_PARAM_Y_STIFFNESS)]!!
        assertEquals(109, openStiff)
        assertEquals(130, closeStiff)
        assertEquals(80, ints[Prefs.animKey(Prefs.ANIM_GROUP_OPEN, Prefs.ANIM_PARAM_X_DAMPING)])
        assertEquals(83, ints[Prefs.animKey(Prefs.ANIM_GROUP_CLOSE, Prefs.ANIM_PARAM_WIDTH_DAMPING)])
        for (group in Prefs.ANIM_GROUPS) {
            for (param in Prefs.ANIM_SPRING_PARAMS) {
                assertTrue("missing ${group}_$param", Prefs.animKey(group, param) in ints)
            }
            assertTrue(Prefs.animKey(group, Prefs.ANIM_PARAM_FADE_DURATION) in ints)
        }
        assertEquals(3 * (Prefs.ANIM_SPRING_PARAMS.size + 1), ints.size)
    }

    @Test
    fun harmonyCompanionEnablesAnimGroupsOthersDoNot() {
        val hm = BuiltinGlassPresets.companionOf("builtin_harmony_soft")
        for (group in Prefs.ANIM_GROUPS) {
            assertEquals(true, hm.bools[Prefs.animEnabledKey(group)])
            assertTrue(Prefs.animKey(group, Prefs.ANIM_PARAM_X_STIFFNESS) in hm.ints)
        }
        val springKeys = BuiltinGlassPresets.harmonySpringInts().keys
        for (id in listOf("builtin_ios_clear", "builtin_ios_frost", "builtin_crystal", "builtin_classic_frost")) {
            val c = BuiltinGlassPresets.companionOf(id)
            assertTrue("non-harmony must not touch spring: $id", c.ints.keys.none { it in springKeys })
            assertTrue(c.bools.keys.none { it in Prefs.ANIM_GROUPS.map { g -> Prefs.animEnabledKey(g) } })
        }
    }

    @Test
    fun companionKeysDoNotOverlapMaterialParams() {
        for (p in all) {
            val c = BuiltinGlassPresets.companionOf(p.id)
            for (key in c.ints.keys + c.floats.keys + c.bools.keys) {
                assertFalse("companion overlaps params: ${p.id} $key", key in p.params)
            }
        }
    }

    @Test
    fun matchCurrentHitsExactlyAppliedRecipe() {
        for (p in all) {
            val store = HashMap(p.params)
            assertEquals(p.id, BuiltinGlassPresets.matchCurrent { store[it] })
        }
    }

    @Test
    fun matchCurrentVanishesAfterAnySingleKeyEdit() {
        val p = all.first()
        for (key in p.params.keys) {
            val store = HashMap(p.params)
            store[key] = (store[key] ?: 0f) + 1f
            assertEquals("edit $key must clear marker", null, BuiltinGlassPresets.matchCurrent { store[it] })
        }
    }

    @Test
    fun matchCurrentNullOnEmptyOrDefaults() {
        assertEquals(null, BuiltinGlassPresets.matchCurrent { null })
        val defaults = mapOf(
            Prefs.KEY_BLUR_RADIUS to Prefs.DEFAULT_BLUR_RADIUS,
            Prefs.KEY_REFRACTION_AMOUNT to Prefs.DEFAULT_REFRACTION_AMOUNT,
        )
        assertEquals(null, BuiltinGlassPresets.matchCurrent { defaults[it] })
    }

    @Test
    fun matchCurrentPositiveControlPartialRecipeDoesNotHit() {
        val p = all.first()
        val partial = p.params.filterKeys { it != Prefs.KEY_BLUR_RADIUS }
        assertEquals(null, BuiltinGlassPresets.matchCurrent { partial[it] })
    }

    @Test
    fun styleWritePlanSplitsBooleanAndFloatKeys() {
        for (p in all) {
            val bools = p.params.filterKeys { it in BuiltinGlassPresets.BOOLEAN_PARAM_KEYS }
            val floats = p.params.filterKeys { it !in BuiltinGlassPresets.BOOLEAN_PARAM_KEYS }
            assertEquals(p.params.size, bools.size + floats.size)
            assertTrue("each style must set mask alpha: ${p.id}", Prefs.KEY_MASK_ALPHA in floats)
            assertTrue("each style must set blur: ${p.id}", Prefs.KEY_BLUR_RADIUS in floats)
        }
    }
}
