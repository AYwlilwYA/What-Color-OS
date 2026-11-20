package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.BackdropSourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底图四路两侧对账契约：枚举每一路都要在 hook 与预览两侧可达。
 *
 * 治的缺陷是第三路 SCENE_SKY 被一个**显式实参** `hasSceneSky = false` 关掉，
 * 且 hook 侧零处加载素材 —— 显式 false 长得像「有意为之」，比漏传默认参更隐蔽。
 */
class BackdropFourWayWiringContractTest {

    private fun world(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
    ).readText()

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
    ).readText()

    private fun policy(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/engine/BackdropSourcePolicy.kt",
    ).readText()

    private fun body(text: String, name: String): String {
        val at = text.indexOf("fun $name(")
        if (at < 0) return ""
        var depth = 0
        var started = false
        val sb = StringBuilder()
        for (i in at until text.length) {
            val c = text[i]
            sb.append(c)
            if (c == '{') { depth++; started = true }
            if (c == '}') { depth--; if (started && depth == 0) break }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 四路可达

    /** 枚举有几路，两侧就都要有几路分支；缺一路就是结构性缺失。 */
    @Test
    fun bothSidesHandleEveryKindExplicitly() {
        val hook = body(world(), "backdropOrNull")
        val pv = body(preview(), "backdropSource")
        for (kind in BackdropSourcePolicy.Kind.values()) {
            assertTrue("hook 缺分支 $kind", hook.contains("Kind.$kind ->"))
            assertTrue("预览缺分支 $kind", pv.contains("Kind.$kind ->"))
        }
        assertFalse("hook 不得用 else 吞掉未处理的路", hook.contains("else -> null"))
    }

    /** 第三路的能力位必须实算，不得写死常量。 */
    @Test
    fun sceneSkyCapabilityIsComputedNotHardcoded() {
        val hook = body(world(), "backdropOrNull")
        assertTrue("按已解码实算", hook.contains("sceneSkyOrNull() != null"))
        assertFalse("不得再硬编 false", hook.contains("hasSceneSky = false"))
        assertFalse("不得硬编 true", hook.contains("hasSceneSky = true"))
    }

    /** 阳性对照：硬编 false 时第三路永远落 NONE，与素材在不在包里无关。 */
    @Test
    fun positiveControlHardcodedFalseMakesThirdWayUnreachable() {
        val skyReady = true
        val hardcoded = BackdropSourcePolicy.backdropKind(
            hasStylePhoto = false, useSystemWallpaper = false,
            hasSystemWallpaper = false, hasSceneSky = false,
        )
        val computed = BackdropSourcePolicy.backdropKind(
            hasStylePhoto = false, useSystemWallpaper = false,
            hasSystemWallpaper = false, hasSceneSky = skyReady,
        )
        assertEquals("硬编 false ⇒ 无底", BackdropSourcePolicy.Kind.NONE, hardcoded)
        assertEquals("实算 ⇒ 第三路", BackdropSourcePolicy.Kind.SCENE_SKY, computed)
        assertNotEquals("两者必须不同，否则本修复无意义", hardcoded, computed)
    }

    // ---------------------------------------------------------------- 默认配置分歧

    /**
     * 默认配置（接管开关关 + 未选照片）就走第三路 —— 这不是边角情形。
     *
     * 修前 hook 恒 NONE、预览 SCENE_SKY ⇒ 出厂默认下两侧画面不同。
     */
    @Test
    fun defaultConfigLandsOnThirdWayNotNone() {
        val defaultUseSystemWallpaper = false
        val kind = BackdropSourcePolicy.backdropKind(
            hasStylePhoto = false, useSystemWallpaper = defaultUseSystemWallpaper,
            hasSystemWallpaper = true, hasSceneSky = true,
        )
        assertEquals(BackdropSourcePolicy.Kind.SCENE_SKY, kind)
    }

    /** 开关开但动态壁纸（静态源不可用）同样落第三路，是第二条分歧路径。 */
    @Test
    fun liveWallpaperAlsoFallsBackToThirdWay() {
        val kind = BackdropSourcePolicy.backdropKind(
            hasStylePhoto = false, useSystemWallpaper = true,
            hasSystemWallpaper = false, hasSceneSky = true,
        )
        assertEquals(BackdropSourcePolicy.Kind.SCENE_SKY, kind)
    }

    /** 五种配置组合穷举：两侧喂同一组能力位必须得同一个 Kind。 */
    @Test
    fun allConfigCombinationsAgreeAcrossSides() {
        val cases = listOf(
            Triple(false, false, true) to BackdropSourcePolicy.Kind.SCENE_SKY,
            Triple(true, false, true) to BackdropSourcePolicy.Kind.STYLE_PHOTO,
            Triple(false, true, true) to BackdropSourcePolicy.Kind.SYSTEM_WALLPAPER,
            Triple(false, true, false) to BackdropSourcePolicy.Kind.SCENE_SKY,
            Triple(true, true, false) to BackdropSourcePolicy.Kind.STYLE_PHOTO,
        )
        for ((inputs, expected) in cases) {
            val (photo, sys, wall) = inputs
            assertEquals(
                "photo=$photo sys=$sys wall=$wall",
                expected,
                BackdropSourcePolicy.backdropKind(photo, sys, wall, hasSceneSky = true),
            )
        }
    }

    /** 第三路也不可用时才真的无底（素材缺失/解码失败）。 */
    @Test
    fun noneOnlyWhenAllThreeUnavailable() {
        assertEquals(
            BackdropSourcePolicy.Kind.NONE,
            BackdropSourcePolicy.backdropKind(false, false, true, hasSceneSky = false),
        )
        assertEquals(
            BackdropSourcePolicy.Kind.NONE,
            BackdropSourcePolicy.backdropKind(false, true, false, hasSceneSky = false),
        )
    }

    // ---------------------------------------------------------------- 素材加载链

    @Test
    fun hookLoadsSceneSkyFromModuleAssets() {
        val src = body(world(), "ensureSceneSky")
        assertTrue("素材路径具名常量", world().contains("SCENE_SKY_ASSET = \"aod/scene_sky.webp\""))
        assertTrue("走模块 assets 通道", body(world(), "decodeSceneSky").contains("HookAssetSource.open(a)"))
        assertTrue("后台单飞", src.contains("sceneSkyLoading.compareAndSet(false, true)"))
        assertTrue("就绪回调", src.contains("onReady()"))
        assertFalse("禁定时器", src.contains("postDelayed"))
        assertFalse("禁睡眠轮询", src.contains("Thread.sleep"))
    }

    /** 素材缺失是结构性问题，重试无用 ⇒ 失败只记一次诊断，不排重试。 */
    @Test
    fun missingAssetLogsOnceAndDoesNotRetry() {
        val src = body(world(), "ensureSceneSky")
        assertTrue("只记一次", src.contains("if (!sceneSkyMissLogged)"))
        assertTrue("文案点明结构性缺失", src.contains("结构性缺失"))
        assertFalse("不排重试任务", src.contains("schedule("))
    }

    /** 采样与壁纸/照片同一上限，避免整张 4K 底图进系统进程。 */
    @Test
    fun sceneSkyDecodeUsesSharedSamplePolicy() {
        val src = body(world(), "decodeSceneSky")
        assertTrue("先探边界", src.contains("inJustDecodeBounds = true"))
        assertTrue("共用采样策略", src.contains("BackdropSourcePolicy.sampleSizeFor("))
        assertTrue("同一上限常量", src.contains("MAX_DECODE_DIM"))
    }

    /** 装载点：与壁纸/照片同一条事件链（换样式时按需），不新增轮询。 */
    @Test
    fun stageRequestsSceneSkyOnStyleEvent() {
        val src = body(stage(), "worldSpec")
        assertTrue("换样式事件里请求", src.contains("AodWorldLayer.ensureSceneSky {"))
        val at = src.indexOf("AodWorldLayer.ensureWallpaper")
        val sky = src.indexOf("AodWorldLayer.ensureSceneSky")
        assertTrue("与壁纸同链", at >= 0 && sky > at)
    }

    // ---------------------------------------------------------------- 释放链

    /** 三条释放链两两判身份：同一位图被多路持有时不得提前回收。 */
    @Test
    fun allReleasePathsGuardAgainstSharedBitmap() {
        val sky = body(world(), "releaseSceneSky")
        assertTrue("场景底图判壁纸", sky.contains("old !== wallpaper"))
        assertTrue("场景底图判用户照片", sky.contains("old !== stylePhoto"))
        assertTrue("场景底图判相框照片", sky.contains("old !== framePhotoBitmap"))
        assertTrue("用户照片判场景底图", body(world(), "releaseStylePhoto").contains("old !== sceneSky"))
        assertTrue("相框照片判场景底图", body(world(), "releaseFramePhoto").contains("old !== sceneSky"))
    }

    @Test
    fun hotReloadReleasesSceneSkyAndClearsLedger() {
        val src = body(world(), "release")
        assertTrue("释放位图", src.contains("releaseSceneSky()"))
        assertTrue("清单飞标记", src.contains("sceneSkyLoading.set(false)"))
        assertTrue("清失败账", src.contains("sceneSkyMissLogged = false"))
    }

    // ---------------------------------------------------------------- 诊断口径

    /** 守卫日志必须能区分「解码在途」与「压根没有可用源」。 */
    @Test
    fun missReasonDistinguishesLoadingFromStructural() {
        val src = body(world(), "backdropMissReason")
        assertTrue("在途判据含三条链", src.contains("decoding.get()"))
        assertTrue("含用户照片链", src.contains("stylePhotoLoading.get()"))
        assertTrue("含场景底图链", src.contains("sceneSkyLoading.get()"))
        assertTrue("两态文案", src.contains("\"decoding\"") && src.contains("\"no-source\""))
        assertTrue("回显第三路状态", src.contains("sky="))
    }

    /**
     * 三处底图缺失守卫都改用新口径。
     *
     * 只查这三条日志的消息体，不全文扫「未就绪」——
     * `world-blurclear-nosoft` 的「预模糊底图未就绪」是**真时序**
     * （预模糊图由 setSource 异步派生，等一下确实会有），措辞本就正确，不在治理范围。
     */
    @Test
    fun allGuardsUseNewWordingNotReadinessImplication() {
        val text = world()
        for (key in listOf("world-nosource", "world-base-nosource")) {
            val bodies = Regex("\"$key\",\\s*\\n\\s*([^\\n]*)").findAll(text).map { it.groupValues[1] }.toList()
            assertTrue("$key 至少一处", bodies.isNotEmpty())
            for (b in bodies) {
                assertTrue("$key 必须走统一原因函数", b.contains("backdropMissReason(useSystemWallpaper)"))
                assertFalse("$key 不得再暗示时序", b.contains("未就绪"))
            }
        }
        assertEquals(
            "三处守卫都走同一份原因",
            3, Regex("backdropMissReason\\(useSystemWallpaper\\)").findAll(text).count(),
        )
    }

    /** 阳性对照：预模糊那条是真时序，必须保留「未就绪」措辞，不能被一并改掉。 */
    @Test
    fun softBackdropGuardKeepsTransientWordingBecauseItIsTrulyTransient() {
        assertTrue(
            "预模糊图由 setSource 异步派生，等一下会有",
            world().contains("world-blurclear-nosoft\", \"预模糊底图未就绪"),
        )
    }

    /** 阳性对照：旧文案「未就绪」暗示等等就好，与实际永远不会有相反。 */
    @Test
    fun positiveControlOldWordingImpliedTransience() {
        val old = "底图源未就绪"
        assertTrue("旧文案含时序暗示词", old.contains("未就绪"))
        val structural = "no-source"
        assertFalse("新文案不含时序暗示", structural.contains("未就绪"))
    }

    // ---------------------------------------------------------------- 效果层输入口径

    /**
     * 效果层输入取同一张底图：纹理压在用户看到的那张图上。
     *
     * 定案依据是两侧世界层内部本来就同源（底图与镜片/照片内容源同一张），
     * 效果层跟壁纸走反而与自己的世界层脱节。
     */
    @Test
    fun effectLayerInputFollowsBackdrop() {
        assertTrue(
            "策略件注释必须写跟底图",
            policy().contains("效果层输入取同一张底图"),
        )
        assertFalse(
            "不得再写恒壁纸",
            policy().contains("效果层输入恒为系统壁纸"),
        )
        assertTrue(
            "预览效果层取当前底图",
            preview().contains("requestEffectLayer(backdrop"),
        )
    }

    /** 预览每一处底图取用都必须走策略件，不得有简化式跳过用户照片。 */
    @Test
    fun allSceneBranchesUseTheSameBackdropSource() {
        val src = preview()
        for (fn in listOf("drawSailScene", "drawPhotoScene", "drawHandPaintScene")) {
            assertTrue(
                "$fn 底图必须走策略件",
                body(src, fn).contains("val backdrop = backdropSource()"),
            )
        }
        assertTrue(
            "天空分支底图必须走策略件",
            body(src, "drawSceneWorld").contains("val sky = backdropSource()"),
        )
        assertTrue(
            "非场景款底图必须走策略件",
            body(src, "drawWallpaperBackground").contains("val bitmap = backdropSource()"),
        )
        assertTrue(
            "效果层预热必须走策略件",
            body(src, "warmEffectLayer").contains("val bitmap = backdropSource()"),
        )
    }

    /**
     * ★全文禁简化式：底图取用点会随功能新增，逐个函数点名必漏。
     *
     * 按写法全文扫，新增第五处也抓得到。
     */
    @Test
    fun noSimplifiedBackdropFormRemainsAnywhere() {
        val src = preview()
        assertFalse("不得残留 realWallpaper ?: sceneSky", src.contains("realWallpaper ?: sceneSky"))
        assertFalse(
            "不得残留只看壁纸开关的三选一",
            src.contains("(if (useSystemWallpaper) wallpaper else null) ?: sceneSky"),
        )
        assertFalse("不得残留 realWallpaper 中间量", src.contains("realWallpaper"))
    }

    /** 换底图的三个入口都必须清效果层键并预热，否则纹理压在旧图上。 */
    @Test
    fun everyBackdropChangeInvalidatesEffectLayer() {
        val src = preview()
        for (setter in listOf("useSystemWallpaper", "stylePhoto")) {
            val at = src.indexOf("var $setter")
            assertTrue("$setter 必须是可观察属性", at >= 0)
            val end = src.indexOf("\n    /**", at)
            val block = src.substring(at, if (end > at) end else at + 600)
            assertTrue("$setter 变更必须清效果层键", block.contains("effectLayerKey = \"\""))
            assertTrue("$setter 变更必须预热效果层", block.contains("warmEffectLayer()"))
        }
        assertTrue(
            "系统壁纸入口同样清键预热",
            body(src, "setWallpaper").contains("effectLayerKey = \"\"") &&
                body(src, "setWallpaper").contains("warmEffectLayer()"),
        )
    }

    /**
     * 天空分支的视差与自绘云山月只跟模块底图走。
     *
     * 用户照片/系统壁纸是实景，叠云山月会盖住用户的图。
     */
    @Test
    fun skyBranchParallaxAndOverlaysFollowModuleBackdropOnly() {
        val b = body(preview(), "drawSceneWorld")
        assertTrue("视差按底图身份判", b.contains("val parallax = if (sky === sceneSky) 1f else 0f"))
        assertTrue("自绘云山月按底图身份门控", b.contains("if (sky === sceneSky) {"))
        assertFalse("不得再按壁纸开关判", b.contains("realWallpaper == null"))
    }

    /**
     * 阳性对照：简化式在「选了照片」时与策略件取到不同的图。
     *
     * 策略件照片优先级最高，简化式只看壁纸开关 ⇒ 两者必然分叉。
     */
    @Test
    fun positiveControlSimplifiedFormSkipsStylePhoto() {
        val hasPhoto = true
        val useSys = true
        val hasWall = true
        val hasSky = true
        val byPolicy = BackdropSourcePolicy.backdropKind(hasPhoto, useSys, hasWall, hasSky)
        assertEquals("策略件照片优先", BackdropSourcePolicy.Kind.STYLE_PHOTO, byPolicy)
        // 简化式 realWallpaper ?: sceneSky 的等价判定：只看壁纸开关
        val simplified = if (useSys && hasWall) {
            BackdropSourcePolicy.Kind.SYSTEM_WALLPAPER
        } else if (hasSky) {
            BackdropSourcePolicy.Kind.SCENE_SKY
        } else {
            BackdropSourcePolicy.Kind.NONE
        }
        assertNotEquals("简化式取不到照片 ⇒ 两种写法必然分叉", byPolicy, simplified)
    }

    /** hook 世界层内部本来同源：底图与镜片/照片内容源是同一张。 */
    @Test
    fun hookWorldLayerIsInternallyConsistent() {
        val world = File(
            "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt",
        ).readText()
        for (fn in listOf("drawLens", "drawPhoto")) {
            val b = body(world, fn)
            assertTrue("$fn 底图走四路策略", b.contains("backdropOrNull(useSystemWallpaper)"))
            assertTrue("$fn 内容源用同一张 bmp", b.contains("setSource(bmp"))
        }
    }
}
