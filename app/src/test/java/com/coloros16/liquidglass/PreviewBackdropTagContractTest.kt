package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预览底图裁决诊断契约：报实际取到的那张，不按能力位猜。
 *
 * 能力位为真不等于这一路被选中（照片优先于壁纸），
 * 且第三路场景底图必须有对应文案，否则一律被报成壁纸。
 */
class PreviewBackdropTagContractTest {

    private fun preview(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private fun body(name: String): String {
        val text = preview()
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

    /** 四路都要有文案，缺一路就会被误报成另一路。 */
    @Test
    fun tagCoversEveryKindIncludingNone() {
        val b = body("backdropTag")
        for (tag in listOf("none", "photo", "sky", "wall", "other")) {
            assertTrue("缺文案 $tag", b.contains("\"$tag\""))
        }
    }

    /** ★按位图身份比，不按能力位判。 */
    @Test
    fun tagComparesBitmapIdentityNotCapability() {
        val b = body("backdropTag")
        assertTrue("照片按身份", b.contains("bmp === stylePhoto"))
        assertTrue("场景底图按身份", b.contains("bmp === sceneSky"))
        assertTrue("壁纸按身份", b.contains("bmp === wallpaper"))
        assertFalse("不得按开关猜", b.contains("if (useSystemWallpaper)"))
        assertFalse("不得按非空猜", b.contains("stylePhoto != null ->"))
    }

    /** 阳性对照：按能力位猜时，照片位已回收但 URI 还在 ⇒ 报 photo 实际画 wall。 */
    @Test
    fun positiveControlCapabilityGuessMisreports() {
        val photoUriPresent = true
        val photoBitmapRecycled = true
        val byCapability = if (photoUriPresent) "photo" else "wall"
        val actualIsWall = photoBitmapRecycled
        val byIdentity = if (actualIsWall) "wall" else "photo"
        assertEquals("能力位猜出 photo", "photo", byCapability)
        assertEquals("实际画的是 wall", "wall", byIdentity)
        assertTrue("两者必须不同，否则本诊断无意义", byCapability != byIdentity)
    }

    /** 阳性对照：只有 photo/wall 二选一时，第三路场景底图无文案可用。 */
    @Test
    fun positiveControlTwoWayTagCannotExpressSky() {
        val oldTags = setOf("photo", "wall")
        assertFalse("旧二选一表达不了 sky", oldTags.contains("sky"))
        assertTrue("新四路能表达", body("backdropTag").contains("\"sky\""))
    }

    /** 每一处底图取用点都要有诊断，漏一处就分不清那条路径画的是哪张。 */
    @Test
    fun everyBackdropConsumerLogsItsSource() {
        val src = preview()
        val sites = mapOf(
            "drawSceneWorld" to "scene-sky",
            "drawHandPaintScene" to "handpaint",
            "drawSailScene" to "lens",
            "drawPhotoScene" to "photo",
            "drawWallpaperBackground" to "plain",
            "warmEffectLayer" to "warm",
            "captureRevealTarget" to "reveal",
        )
        for ((fn, where) in sites) {
            val b = body(fn)
            assertTrue("$fn 缺底图诊断", b.contains("logBackdrop(\"$where\""))
        }
    }

    /** 诊断必须带款式/态/来源/尺寸/开关，否则定位不到是哪条路径。 */
    @Test
    fun diagnosticCarriesStyleStateSourceAndSize() {
        val b = body("logBackdrop")
        for (f in listOf("at=\$where", "style=\$styleId", "state=\$scene", "from=\$tag", "src=\$size", "sys=\$useSystemWallpaper")) {
            assertTrue("缺字段 $f", b.contains(f))
        }
    }

    /** 按结论判重 + 节流：静止期反复重绘不得刷屏。 */
    @Test
    fun diagnosticDedupesByConclusion() {
        val b = body("logBackdrop")
        assertTrue("键含来源与尺寸", b.contains("\$where|\$styleId|\$scene|\$tag|\$size|\$useSystemWallpaper"))
        assertTrue("同键节流", b.contains("if (key == backdropLogKey && now - backdropLogAt < 800L) return"))
    }

    /** 与 hook 侧同一口径：两侧都是先取实际那张再按身份比。 */
    @Test
    fun hookAndPreviewUseTheSameIdentityRule() {
        val hook = File(
            "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt",
        ).readText()
        val at = hook.indexOf("fun backdropSourceTag(")
        assertTrue("hook 侧必须有同名判据件", at >= 0)
        val seg = hook.substring(at, (at + 600).coerceAtMost(hook.length))
        assertTrue("hook 照片按身份", seg.contains("=== stylePhotoOrNull()"))
        assertTrue("hook 场景按身份", seg.contains("=== sceneSkyOrNull()"))
        assertTrue("hook 壁纸按身份", seg.contains("=== wallpaperOrNull()"))
        val pv = body("backdropTag")
        for (tag in listOf("photo", "sky", "wall", "none", "other")) {
            assertTrue("两侧文案必须一致：$tag", seg.contains("\"$tag\"") && pv.contains("\"$tag\""))
        }
    }
}