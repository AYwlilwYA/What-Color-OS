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
    fun fourStylesInDisplayOrder() {
        assertEquals(
            listOf("ios", "harmony_soft", "crystal", "classic_frost"),
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
            BuiltinGlassPresets.ID_PREFIX + "ios", "我的iOS", emptyList(), mapOf(Prefs.KEY_BLUR_RADIUS to 99f),
        )
        val seeded = BuiltinGlassPresets.seedInto(listOf(userEdited))
        assertEquals(all.size, seeded.size)
        assertEquals(99f, seeded.first { it.id == userEdited.id }.params[Prefs.KEY_BLUR_RADIUS]!!, 0f)
        val empty = BuiltinGlassPresets.seedInto(emptyList())
        assertEquals(all.size, empty.size)
        assertEquals(empty, BuiltinGlassPresets.seedInto(empty))
    }

    @Test
    fun retiredIosTiersAreDroppedFromExistingList() {
        val old = listOf(
            GlassRegistry.Preset(BuiltinGlassPresets.ID_PREFIX + "ios_clear", "iOS 通透", emptyList(), emptyMap()),
            GlassRegistry.Preset(BuiltinGlassPresets.ID_PREFIX + "ios_frost", "iOS 磨砂", emptyList(), emptyMap()),
            GlassRegistry.Preset("p1", "我的配置", listOf("com.x.Y"), mapOf(Prefs.KEY_BLUR_RADIUS to 7f)),
        )
        val out = BuiltinGlassPresets.seedInto(old)
        assertTrue("旧通透档必须清掉", out.none { it.id.endsWith("ios_clear") })
        assertTrue("旧磨砂档必须清掉", out.none { it.id.endsWith("ios_frost") })
        assertTrue("用户自建档必须保留", out.any { it.id == "p1" && it.params[Prefs.KEY_BLUR_RADIUS] == 7f })
        for (p in all) assertTrue("新档必须补齐: ${p.id}", out.any { it.id == p.id })
    }

    @Test
    fun positiveControlMigrationDetectedEvenWhenSizeUnchanged() {
        // 已有新 iOS 档时：删两条旧档、补零条 ⇒ 数量差 2；构造相等场景=旧列表只含一条待删档 + 缺一条新档
        val old = all.filterNot { it.id == BuiltinGlassPresets.ID_PREFIX + "crystal" } + listOf(
            GlassRegistry.Preset(BuiltinGlassPresets.ID_PREFIX + "ios_clear", "iOS 通透", emptyList(), emptyMap()),
        )
        val out = BuiltinGlassPresets.seedInto(old)
        assertEquals("删一条旧档补一条新档，数量恰好相等", old.size, out.size)
        assertTrue("内容必须已变（否则迁移会静默不落盘）", out != old)
        assertTrue(out.none { it.id.endsWith("ios_clear") })
        assertTrue(out.any { it.id == BuiltinGlassPresets.ID_PREFIX + "crystal" })
    }

    @Test
    fun noStyleTouchesPerformanceDefaults() {
        val perf = BuiltinGlassPresets.PERFORMANCE_KEYS
        assertTrue("性能键名单不能空", perf.isNotEmpty())
        for (p in all) {
            val c = BuiltinGlassPresets.companionOf(p.id)
            for (key in c.ints.keys + c.floats.keys + c.bools.keys) {
                assertFalse("风格不得改性能默认值: ${p.id} $key", key in perf)
            }
            for (key in p.params.keys) {
                assertFalse("配方也不得含性能键: ${p.id} $key", key in perf)
            }
        }
    }

    @Test
    fun performanceGuardListCoversCaptureAndTiltTuning() {
        val perf = BuiltinGlassPresets.PERFORMANCE_KEYS
        assertTrue(Prefs.KEY_PANEL_CAPTURE_HZ in perf)
        assertTrue(Prefs.KEY_BG_CAPTURE_SCALE in perf)
        assertTrue(Prefs.KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS in perf)
        assertTrue(Prefs.KEY_BG_PERIODIC_CAPTURE_ENABLE in perf)
        assertTrue(Prefs.KEY_ANIM_TILT_STRENGTH in perf)
        assertTrue(Prefs.KEY_ANIM_TILT_PERSPECTIVE in perf)
        assertFalse("倾斜总开关属观感项，不在性能名单", Prefs.KEY_ANIM_TILT_ENABLED in perf)
    }

    @Test
    fun positiveControlGuardWouldCatchInjectedPerfKey() {
        val injected = BuiltinGlassPresets.Companion(
            ints = mapOf(Prefs.KEY_PANEL_CAPTURE_HZ to 30),
        )
        assertTrue(
            "阳性对照：注入性能键必须被名单识别",
            injected.ints.keys.any { it in BuiltinGlassPresets.PERFORMANCE_KEYS },
        )
    }

    @Test
    fun companionTiltFollowsStyleFamily() {
        assertTrue(BuiltinGlassPresets.companionOf("builtin_ios").bools[Prefs.KEY_ANIM_TILT_ENABLED]!!)
        assertTrue("鸿蒙档不写倾斜开关，保持用户现值", BuiltinGlassPresets.companionOf("builtin_harmony_soft").bools[Prefs.KEY_ANIM_TILT_ENABLED] == null)
        assertTrue("经典档不写任何配套", BuiltinGlassPresets.companionOf("builtin_classic_frost").bools.isEmpty())
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
        for (id in listOf("builtin_ios", "builtin_crystal", "builtin_classic_frost")) {
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
