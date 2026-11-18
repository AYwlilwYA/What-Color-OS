package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 后台构建线程调用的渲染器不得持有可变共享绘制器材。
 *
 * 直读源码做离线契约：这些渲染器由预览的后台构建线程调用，
 * 共享 Rect/Paint/RuntimeShader 会被并发调用互相覆盖。
 */
class RendererThreadSafetyContractTest {

    private fun root(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun source(name: String): String {
        val f = File(root(), "app/src/main/kotlin/com/coloros16/liquidglass/ui/aod/$name.kt")
        assertTrue("找不到渲染器源码 $name", f.isFile)
        return f.readText()
    }

    /** 取某函数体（大括号配平），找不到返回空串。 */
    private fun body(src: String, fn: String): String {
        val lines = src.split("\n")
        val start = lines.indexOfFirst { Regex("""^\s*(internal |private )?fun $fn\b""").containsMatchIn(it) }
        if (start < 0) return ""
        var depth = 0
        var opened = false
        val out = StringBuilder()
        for (i in start until lines.size) {
            val l = lines[i]
            out.append(l).append('\n')
            depth += l.count { it == '{' } - l.count { it == '}' }
            if (l.contains('{')) opened = true
            if (opened && depth <= 0) break
        }
        return out.toString()
    }

    private fun sharedMutableFields(src: String): List<String> =
        Regex("""^\s*private (?:val|var) (\w+)\s*=\s*(RectF|Rect|Paint|Matrix)\(""", RegexOption.MULTILINE)
            .findAll(src).map { it.groupValues[1] }.toList()

    @Test
    fun tiltShiftHasNoSharedDrawingGear() {
        assertEquals(
            "移轴渲染在后台构建线程跑，器材必须局部持有",
            emptyList<String>(),
            sharedMutableFields(source("TiltShiftRenderer")),
        )
    }

    @Test
    fun effectTextureHasNoSharedDrawingGear() {
        assertEquals(
            "贴图渲染在后台构建线程跑，器材必须局部持有",
            emptyList<String>(),
            sharedMutableFields(source("EffectTextureRenderer")),
        )
    }

    @Test
    fun meizuHasNoSharedPaintOrProgram() {
        val fields = sharedMutableFields(source("MeizuGlassRenderer"))
        assertEquals("魅族玻璃画笔必须局部持有", emptyList<String>(), fields)
        assertTrue(
            "RuntimeShader 的 uniform 是实例状态，共用一份会被并发渲染覆盖",
            !Regex("""private val program\s*:""").containsMatchIn(source("MeizuGlassRenderer")),
        )
    }

    @Test
    fun tiltShiftBlurredAllocatesGearLocally() {
        val b = body(source("TiltShiftRenderer"), "blurred")
        assertTrue("找不到 blurred", b.isNotEmpty())
        for (decl in listOf("val src = Rect()", "val dst = Rect()", "val paint = Paint(")) {
            assertTrue("blurred 缺局部 $decl", b.contains(decl))
        }
    }

    @Test
    fun tiltShiftDrawAllocatesGearLocally() {
        val b = body(source("TiltShiftRenderer"), "draw")
        assertTrue("找不到 draw", b.isNotEmpty())
        for (decl in listOf("val rect = RectF()", "val src = Rect()", "val paint = Paint(")) {
            assertTrue("draw 缺局部 $decl", b.contains(decl))
        }
    }

    @Test
    fun effectTextureDrawAllocatesGearLocally() {
        val b = body(source("EffectTextureRenderer"), "draw")
        assertTrue("找不到 draw", b.isNotEmpty())
        assertTrue("draw 缺局部 RectF", b.contains("val rect = RectF("))
        assertTrue("draw 缺局部 Paint", b.contains("Paint(Paint.FILTER_BITMAP_FLAG)"))
    }

    @Test
    fun meizuRenderAllocatesPaintAndProgramPerCall() {
        val b = body(source("MeizuGlassRenderer"), "render")
        assertTrue("找不到 render", b.isNotEmpty())
        assertTrue("render 缺每次新建 program", b.contains("newProgram()"))
        assertTrue("render 缺局部 Paint", b.contains("val paint = Paint("))
    }

    @Test
    fun meizuDrawHardwareTakesPaintAsParameter() {
        val b = body(source("MeizuGlassRenderer"), "drawHardware")
        assertTrue("找不到 drawHardware", b.isNotEmpty())
        assertTrue("drawHardware 必须接收调用方的 paint", b.contains("paint: Paint"))
    }

    @Test
    fun meizuStillSerializesHardwareSurface() {
        val b = body(source("MeizuGlassRenderer"), "drawHardware")
        assertTrue("硬件离屏 Surface 仍需串行化", b.contains("synchronized(renderLock)"))
    }

    /** 阳性对照：检测器本身能抓到共享器材，避免「全绿」是检测失效。 */
    @Test
    fun detectorCatchesSharedGearInSyntheticSource() {
        val bad = """
            object Bad {
                private val rect = RectF()
                private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                fun draw() { rect.set(0f, 0f, 1f, 1f) }
            }
        """.trimIndent()
        assertEquals(listOf("rect", "paint"), sharedMutableFields(bad))
    }

    /** 阳性对照：函数体提取真的取到了体，不是空串导致假绿。 */
    @Test
    fun bodyExtractorReturnsNonEmptyForKnownFunctions() {
        for (pair in listOf(
            "TiltShiftRenderer" to "blurred",
            "TiltShiftRenderer" to "draw",
            "EffectTextureRenderer" to "draw",
            "MeizuGlassRenderer" to "render",
        )) {
            assertTrue("${pair.first}.${pair.second} 函数体不应为空", body(source(pair.first), pair.second).isNotEmpty())
        }
    }
}
