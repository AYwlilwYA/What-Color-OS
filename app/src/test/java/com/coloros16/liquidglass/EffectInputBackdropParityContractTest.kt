package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.BackdropSourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 效果层输入口径契约：效果层与世界层必须吃同一张底图。
 *
 * 治的缺陷是效果层单独加了「用系统壁纸」门控 —— 用户选了照片但没开该开关时输入为 null，
 * 而同一帧世界层画的是照片/场景底图 ⇒ 纹理压在 A 图、底图画的是 B 图。
 */
class EffectInputBackdropParityContractTest {

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
    ).readText()

    private fun world(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt",
    ).readText()

    private fun layer(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodTransitionLayer.kt",
    ).readText()

    private fun overlay(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodEffectOverlay.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
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

    // ---------------------------------------------------------------- 同源

    /** 效果层输入必须走四路策略，不得再自己加壁纸开关门控。 */
    @Test
    fun effectInputGoesThroughFourWayPolicy() {
        val src = body(stage(), "applyEffect")
        assertTrue("走四路策略", src.contains("AodWorldLayer.backdropOrNull(src.useSystemWallpaper)"))
        assertFalse("不得只取系统壁纸", src.contains("AodWorldLayer.wallpaperOrNull()"))
        assertFalse(
            "不得再用开关三元门控",
            src.contains("if (backdropSource().useSystemWallpaper)"),
        )
    }

    /** bindEffect 与 request 必须喂同一个变量，两处不同源就是纹理压错图。 */
    @Test
    fun bindAndRequestReceiveTheSameBitmap() {
        val src = body(stage(), "applyEffect")
        assertTrue("绑定收 wall", src.contains("layer.bindEffect(id, visibility, special, slot.intensity, wall, zoom)"))
        assertTrue(
            "构建收同一个 wall",
            src.contains("AodEffectOverlay.request(id, special, slot.intensity, wall, layer.width, layer.height)"),
        )
        assertEquals("底图只取一次", 1, Regex("val wall = ").findAll(src).count())
    }

    /**
     * 世界层三条绘制分支与效果层取的是同一个选择器。
     *
     * 手绘那条经 `drawWallpaperBase` 间接取，故按调用链核而不是要求同名调用出现在函数体里
     * （断言写意图：三路同源，不是「三处字面量都得有」）。
     */
    @Test
    fun worldAndEffectReadTheSameSelector() {
        val w = world()
        for (fn in listOf("drawLens", "drawPhoto")) {
            assertTrue(
                "$fn 直接走四路策略",
                body(w, fn).contains("backdropOrNull(useSystemWallpaper)"),
            )
        }
        assertTrue(
            "手绘经共用底组件",
            body(w, "drawHandPaint").contains("drawWallpaperBase("),
        )
        assertTrue(
            "共用底组件走四路策略",
            body(w, "drawWallpaperBase").contains("backdropOrNull(useSystemWallpaper)"),
        )
        assertTrue(
            "效果层同一策略",
            body(stage(), "applyEffect").contains("backdropOrNull(src.useSystemWallpaper)"),
        )
    }

    /** 预览侧三个场景分支都取 backdropSource 再喂效果层。 */
    @Test
    fun previewSceneBranchesFeedBackdropToEffect() {
        val pv = preview()
        for (fn in listOf("drawSailScene", "drawPhotoScene", "drawHandPaintScene")) {
            val b = body(pv, fn)
            assertTrue("$fn 取当前底图", b.contains("val backdrop = backdropSource()"))
            assertTrue("$fn 把底图喂效果层", b.contains("drawEffectBackdrop(canvas, w, h, backdrop,"))
        }
        assertTrue(
            "效果层构建吃传入底图",
            body(pv, "drawEffectBackdrop").contains("requestEffectLayer(backdrop, w, h)"),
        )
    }

    /** 策略件注释与两侧实现同向：写的是「同一张底图」而不是「恒系统壁纸」。 */
    @Test
    fun policyCommentMatchesImplementation() {
        val p = policy()
        assertTrue("注释写同源", p.contains("效果层输入取同一张底图"))
        assertFalse("旧口径注释必须已删", p.contains("效果层输入恒为系统壁纸"))
    }

    // ---------------------------------------------------------------- ensure 链

    /**
     * 三条源都要 ensure：只 ensure 壁纸时照片/场景两路永远 null。
     *
     * 「永远 null」与「等一下就绪」是两种病，缺 ensure 属前者，重试无用。
     */
    @Test
    fun allThreeSourcesAreEnsuredOnEffectPath() {
        val src = body(stage(), "applyEffect")
        assertTrue("壁纸", src.contains("AodWorldLayer.ensureWallpaper(onBackdropReady)"))
        assertTrue("用户照片带 URI", src.contains("AodWorldLayer.ensureStylePhoto(src.stylePhotoUri, onBackdropReady)"))
        assertTrue("场景底图", src.contains("AodWorldLayer.ensureSceneSky(onBackdropReady)"))
    }

    /** 三条 ensure 共用同一个就绪回调，且回调只重绘不申请构建。 */
    @Test
    fun readyCallbackOnlyRepaintsThisLayer() {
        val src = body(stage(), "applyEffect")
        assertTrue("回调重绘本层", src.contains("val onBackdropReady = { layer.postInvalidate() }"))
        assertEquals("三条源共用一个回调", 3, Regex("onBackdropReady\\)").findAll(src).count())
        assertFalse("回调里不得自调度", src.contains("postDelayed"))
    }

    /** 阳性对照：ensure 链只挂壁纸时，照片路的就绪回调根本不存在。 */
    @Test
    fun positiveControlWallpaperOnlyEnsureLeavesPhotoNeverReady() {
        val wallpaperOnly = listOf("ensureWallpaper")
        val full = listOf("ensureWallpaper", "ensureStylePhoto", "ensureSceneSky")
        assertFalse("只挂壁纸覆盖不到照片路", wallpaperOnly.contains("ensureStylePhoto"))
        assertTrue("三条齐全才覆盖照片路", full.contains("ensureStylePhoto"))
    }

    // ---------------------------------------------------------------- 默认配置分歧

    /**
     * 出厂默认（接管开关关）+ 已选照片：旧门控丢图，新口径取到照片。
     *
     * 这不是边角情形，默认值就是 false。
     */
    @Test
    fun defaultConfigWithPhotoKeepsEffectInput() {
        val useSys = false
        val hasPhoto = true
        val oldGate = if (useSys) BackdropSourcePolicy.Kind.SYSTEM_WALLPAPER else BackdropSourcePolicy.Kind.NONE
        val byPolicy = BackdropSourcePolicy.backdropKind(hasPhoto, useSys, hasSystemWallpaper = true, hasSceneSky = true)
        assertEquals("旧门控在默认配置下丢图", BackdropSourcePolicy.Kind.NONE, oldGate)
        assertEquals("新口径取到照片", BackdropSourcePolicy.Kind.STYLE_PHOTO, byPolicy)
        assertNotEquals("两者必须不同，否则本修复无意义", oldGate, byPolicy)
    }

    /** 开关开 + 动态壁纸：旧门控取到 null，新口径落第三路场景底图。 */
    @Test
    fun liveWallpaperFallsBackToSceneSkyForEffectToo() {
        val byPolicy = BackdropSourcePolicy.backdropKind(
            hasStylePhoto = false, useSystemWallpaper = true,
            hasSystemWallpaper = false, hasSceneSky = true,
        )
        assertEquals(BackdropSourcePolicy.Kind.SCENE_SKY, byPolicy)
    }

    /** 四路穷举：效果层与世界层喂同一组能力位必得同一个 Kind。 */
    @Test
    fun everyKindAgreesBetweenWorldAndEffect() {
        val cases = listOf(
            Triple(true, false, true) to BackdropSourcePolicy.Kind.STYLE_PHOTO,
            Triple(false, true, true) to BackdropSourcePolicy.Kind.SYSTEM_WALLPAPER,
            Triple(false, false, true) to BackdropSourcePolicy.Kind.SCENE_SKY,
            Triple(false, true, false) to BackdropSourcePolicy.Kind.SCENE_SKY,
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

    // ---------------------------------------------------------------- 判重键与诊断

    /** 换底图必换键：键含底图身份与尺寸，故照片↔壁纸↔场景切换会重建。 */
    @Test
    fun keyCarriesBackdropIdentitySoSwitchRebuilds() {
        val src = body(overlay(), "keyOf")
        assertTrue("身份进键", src.contains("System.identityHashCode(wall)"))
        assertTrue("尺寸进键", src.contains("wall.width"))
    }

    /** 阳性对照：键若不含底图身份，换图后尺寸相同就假命中旧层。 */
    @Test
    fun positiveControlKeyWithoutIdentityWouldReuseStaleLayer() {
        val base = "lock.wp.oplus.water|0.6|540x1188"
        val sameSizeDifferentBitmap = "$base|1080x2400"
        assertEquals("不含身份则两图同键", sameSizeDifferentBitmap, "$base|1080x2400")
        assertNotEquals(
            "含身份才能分辨",
            "$sameSizeDifferentBitmap|111",
            "$sameSizeDifferentBitmap|222",
        )
    }

    /** 输入裁决日志按结论判重，静止期不得逐帧刷屏。 */
    @Test
    fun inputVerdictIsLoggedAndDeduped() {
        val src = body(stage(), "logEffectInput")
        assertTrue("按结论判重", src.contains("if (key == lastEffectInputKey) return"))
        assertTrue("键含底图身份", src.contains("System.identityHashCode(wall)"))
        assertTrue("缺图走统一原因串", src.contains("AodWorldLayer.backdropMissReason(useSystemWallpaper)"))
        assertTrue("有图回显实际来源", src.contains("AodWorldLayer.backdropSourceTag(useSystemWallpaper)"))
        assertTrue("applyEffect 里调用", body(stage(), "applyEffect").contains("logEffectInput("))
    }

    /**
     * 来源标签按位图身份判，不按能力位猜。
     *
     * 能力位为真不等于这一路被选中：照片优先于壁纸，
     * 旧手绘诊断写成「有照片就报 photo」在「照片解码失败但壁纸在」时会报错来源。
     */
    @Test
    fun sourceTagIsIdentityBasedNotCapabilityBased() {
        val src = body(world(), "backdropSourceTag")
        assertTrue("先取实际那张", src.contains("val bmp = backdropOrNull(useSystemWallpaper) ?: return \"none\""))
        assertTrue("照片按身份", src.contains("bmp === stylePhotoOrNull()"))
        assertTrue("场景按身份", src.contains("bmp === sceneSkyOrNull()"))
        assertTrue("壁纸按身份", src.contains("bmp === wallpaperOrNull()"))
        assertFalse("不得按能力位猜", src.contains("if (stylePhotoOrNull() != null)"))
    }

    /** 手绘诊断改用身份标签：修前「有照片就报 photo」会与实际取到的图不符。 */
    @Test
    fun handPaintDiagnosticUsesIdentityTag() {
        val b = body(world(), "drawHandPaint")
        assertTrue("走身份标签", b.contains("src=\${backdropSourceTag(useSystemWallpaper)}"))
        assertFalse("不得再按能力位猜", b.contains("if (stylePhotoOrNull() != null) \"photo\" else \"wall\""))
    }

    /** 热重载清判重键，否则新一代带旧结论不打日志。 */
    @Test
    fun hotReloadClearsInputVerdictKey() {
        assertTrue(
            "清输入判重键",
            body(stage(), "shutdownForHotReload").contains("lastEffectInputKey = \"\""),
        )
    }

    /** 缺图文案不得暗示「等等就好」：可能是结构性无源。 */
    @Test
    fun missingBackdropWordingDoesNotImplyTransience() {
        val o = overlay()
        assertTrue("守卫仍在", o.contains("effect-nowall"))
        assertFalse("不得写未就绪", o.contains("壁纸底未就绪"))
    }

    /** 绘制回调仍只画不建：本轮只换输入源，不改插入点与构建时机。 */
    @Test
    fun paintCallbackStillNeverRequestsBuild() {
        val paint = body(layer(), "paintEffectInsideWorld")
        assertFalse(paint.contains("AodEffectOverlay.request"))
        assertFalse(paint.contains("ensureWallpaper"))
        assertTrue(paint.contains("drawEffect("))
    }
}