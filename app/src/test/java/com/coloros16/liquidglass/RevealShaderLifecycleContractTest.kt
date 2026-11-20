package com.coloros16.liquidglass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 揭开层位图与派生 shader 的生命周期契约：回收位图前必须先丢掉它的 shader 缓存。
 *
 * BitmapShader 持位图引用，位图 recycle 后 shader 仍在缓存里按身份命中 ⇒ 采样已释放内存。
 * 顺序型契约断言 indexOf 先后，只断言「两句都有」抓不到写反。
 */
class RevealShaderLifecycleContractTest {

    private val preview =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()
    private val renderer =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/PhotoSceneRenderer.kt").readText()

    private fun body(text: String, fn: String): String {
        val start = Regex("""^\s*(override |internal |private )*fun $fn\b""", RegexOption.MULTILINE)
            .find(text)?.range?.first ?: error("取不到 $fn")
        var depth = 0
        var i = text.indexOf('{', start)
        val from = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(from, i + 1)
                }
            }
            i++
        }
        error("$fn 括号不闭合")
    }

    /** 渲染器必须提供丢弃入口，且清 key 与 shader 两件。 */
    @Test
    fun rendererExposesForgetAndClearsBoth() {
        assertTrue("缺 forgetReveal", renderer.contains("fun forgetReveal()"))
        val b = body(renderer, "forgetReveal")
        assertTrue("必须清 key", b.contains("revealKey = null"))
        assertTrue("必须清 shader", b.contains("revealShader = null"))
    }

    /** 取 shader 前必须挡已回收位图，否则身份命中会返回悬垂 shader。 */
    @Test
    fun shaderLookupGuardsRecycled() {
        val b = body(renderer, "revealShaderOf")
        val guardAt = b.indexOf("isRecycled")
        val hitAt = b.indexOf("=== revealKey")
        assertTrue("缺 isRecycled 守卫", guardAt >= 0)
        assertTrue("守卫必须在身份判重之前", guardAt < hitAt)
    }

    /** ★重建路径：forget 必须在 recycle 之前。 */
    @Test
    fun forgetBeforeRecycleOnRebuild() {
        val b = body(preview, "captureRevealTarget")
        val forgetAt = b.indexOf("photoRenderer.forgetReveal()")
        val recycleAt = b.indexOf("revealCache?.recycle()")
        assertTrue("重建路径缺 forget", forgetAt >= 0)
        assertTrue("重建路径缺 recycle", recycleAt >= 0)
        assertTrue("forget 必须在 recycle 之前", forgetAt < recycleAt)
    }

    /** ★detach 路径同样要 forget 在前。 */
    @Test
    fun forgetBeforeRecycleOnDetach() {
        val b = body(preview, "onDetachedFromWindow")
        val forgetAt = b.indexOf("photoRenderer.forgetReveal()")
        val recycleAt = b.indexOf("revealCache?.recycle()")
        assertTrue("detach 缺 forget", forgetAt >= 0)
        assertTrue("detach 缺 recycle", recycleAt >= 0)
        assertTrue("detach 的 forget 必须在 recycle 之前", forgetAt < recycleAt)
    }

    /** 命中键但位图已回收 ⇒ 清账重建，不许把已回收的图交出去。 */
    @Test
    fun staleCacheIsRebuiltNotReturned() {
        val b = body(preview, "captureRevealTarget")
        assertTrue("命中分支必须判 isRecycled", b.contains("!cached.isRecycled"))
        assertTrue("陈旧分支必须清图", b.contains("revealCache = null"))
        assertTrue("陈旧分支必须清键", b.contains("revealCacheKey = null"))
        assertTrue("陈旧分支必须有诊断", b.contains("stale target="))
        val staleAt = b.indexOf("stale target=")
        val createAt = b.indexOf("Bitmap.createBitmap(")
        assertTrue("陈旧分支必须在建图之前", staleAt < createAt)
    }

    /** 陈旧分支也要 forget，否则渲染器仍缓存着那张已回收图的 shader。 */
    @Test
    fun staleBranchAlsoForgets() {
        val b = body(preview, "captureRevealTarget")
        val staleGuard = b.indexOf("!cached.isRecycled")
        val firstForget = b.indexOf("photoRenderer.forgetReveal()")
        assertTrue("陈旧分支必须 forget", firstForget in 0 until b.indexOf("Bitmap.createBitmap("))
        assertTrue("forget 必须在陈旧判定之后", firstForget > staleGuard)
    }

    /** 阳性对照：只断言「两句都有」抓不到写反，必须比索引。 */
    @Test
    fun positiveControlOrderMattersNotPresence() {
        val wrong = """
            revealCache?.recycle()
            photoRenderer.forgetReveal()
        """.trimIndent()
        assertTrue("写反样本两句都在", wrong.contains("recycle()") && wrong.contains("forgetReveal()"))
        assertTrue(
            "但顺序是反的",
            wrong.indexOf("recycle()") < wrong.indexOf("forgetReveal()"),
        )
        val right = body(preview, "captureRevealTarget")
        assertTrue(
            "现实现顺序必须正确",
            right.indexOf("photoRenderer.forgetReveal()") < right.indexOf("revealCache?.recycle()"),
        )
    }

    /** 阳性对照：无守卫的旧 lookup，检测器必须能抓到。 */
    @Test
    fun positiveControlUnguardedLookupIsDetectable() {
        val legacy = """
            private fun revealShaderOf(bitmap: Bitmap): BitmapShader? {
                if (bitmap === revealKey) return revealShader
                revealKey = bitmap
                revealShader = BitmapShader(bitmap, CLAMP, CLAMP)
                return revealShader
            }
        """.trimIndent()
        assertFalse("旧写法无守卫", legacy.contains("isRecycled"))
        assertTrue("现实现有守卫", body(renderer, "revealShaderOf").contains("isRecycled"))
    }

    /** 只加不删：渲染器改动不得动其它 shader 缓存的语义。 */
    @Test
    fun otherShaderCachesUntouched() {
        assertTrue("frame 缓存仍在", renderer.contains("private fun shaderOf("))
        assertTrue("pair 缓存仍在", renderer.contains("private fun pairShaderOf("))
        assertTrue("setSource 仍在", renderer.contains("fun setSource("))
    }
}
