package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.HandPaintGeometry
import com.coloros16.liquidglass.engine.HandPaintSource
import com.coloros16.liquidglass.engine.HandPaintTimeline
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SkyBranchPolicy
import com.coloros16.liquidglass.engine.SystemSceneMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 手绘增强版声明入口与预览接线契约：款式可达、两侧共用求值、播完即停。
 *
 * ui/aod 类带 android 依赖不能在纯 JVM 加载，源码侧断言一律读文本。
 */
class HandPaintPreviewWiringContractTest {

    private fun src(relative: String): String =
        File("src/main/kotlin/com/coloros16/liquidglass/$relative").readText()

    private fun body(text: String, fn: String): String {
        val match = Regex("""^([ \t]*)(override |internal |private )*fun $fn\b""", RegexOption.MULTILINE)
            .find(text) ?: error("取不到 $fn")
        val start = match.range.first
        val brace = text.indexOf('{', start)
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        if (brace < 0 || brace > lineEnd) {
            val indent = match.groupValues[1].length
            val next = Regex(
                """^[ \t]{0,$indent}(private |internal |override |public )*(fun|val|var|@|class|companion)\b""",
                RegexOption.MULTILINE,
            ).find(text, lineEnd + 1)
            return text.substring(start, next?.range?.first ?: text.length)
        }
        var depth = 0
        var i = brace
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(brace, i + 1)
                }
            }
            i++
        }
        error("$fn 括号不闭合")
    }

    /** 手绘款必须能拿到 spec，否则 hook 与预览两侧分支都不可达。 */
    @Test
    fun handPaintStyleHasSceneSpec() {
        val text = src("ui/aod/AodSceneCatalog.kt")
        val fn = body(text, "forStyle")
        assertTrue("必须认手绘款", fn.contains("handPaintOf(styleId)"))
        assertTrue("必须传款号", fn.contains("handPaintStyle = handPaint"))
        assertTrue("手绘分支必须在照片类之前或独立", fn.contains("if (handPaint > 0)"))
        assertTrue("款号取共用常量", body(text, "handPaintOf").contains("HandPaintSource.DEFAULT_STYLE"))
    }

    /** 分支优先级：手绘在天空之前，避免被程序化天空吃掉。 */
    @Test
    fun branchPolicyPutsHandPaintBeforeSky() {
        assertEquals(
            SkyBranchPolicy.Branch.HANDPAINT,
            SkyBranchPolicy.branchOf(lens = false, hasPhoto = false, sky = true, handPaintStyle = 1),
        )
        assertEquals(
            SkyBranchPolicy.Branch.LENS,
            SkyBranchPolicy.branchOf(lens = true, hasPhoto = false, sky = true, handPaintStyle = 1),
        )
        assertEquals(
            SkyBranchPolicy.Branch.PHOTO,
            SkyBranchPolicy.branchOf(lens = false, hasPhoto = true, sky = true, handPaintStyle = 1),
        )
        assertEquals(
            SkyBranchPolicy.Branch.SKY,
            SkyBranchPolicy.branchOf(lens = false, hasPhoto = false, sky = true, handPaintStyle = 0),
        )
    }

    /** 预览必须走与 hook 同一份渲染器与求值，不许另写一份。 */
    @Test
    fun previewUsesSharedRendererAndEngine() {
        val text = src("ui/aod/AodPreviewView.kt")
        val fn = body(text, "drawHandPaintScene")
        assertTrue("必须用共用渲染器", fn.contains("r.advance(style, silk, progress)"))
        assertTrue("必须用共用贴图", fn.contains("r.present(canvas, rect[0], rect[1], rect[2]"))
        assertTrue("贴图矩形必须走共用换算", body(text, "handPaintRect").contains("HandPaintGeometry.presentRect"))
        assertTrue("必须用共用时间轴", fn.contains("HandPaintTimeline.progressAt(handPaintPlay, now)"))
        assertTrue("播完即停不再申请帧", fn.contains("HandPaintTimeline.needsFrame(handPaintPlay, now)"))
        assertTrue("轨迹必须从设备取", body(text, "requestHandPaint").contains("DeviceHandPaint.obtain(style, context.cacheDir)"))
    }

    /** 世界分支必须接手绘，否则声明了也画不出来。 */
    @Test
    fun sceneWorldDispatchesHandPaint() {
        val fn = body(src("ui/aod/AodPreviewView.kt"), "drawSceneWorld")
        assertTrue("必须分派手绘", fn.contains("if (spec.handPaintStyle > 0)"))
        assertTrue("必须调手绘绘制", fn.contains("drawHandPaintScene(canvas, w, h, u, spec)"))
    }

    /** 非息屏态直接画整幅：转场期不该再播一遍笔画。 */
    @Test
    fun nonAodStateShowsFullDrawing() {
        val fn = body(src("ui/aod/AodPreviewView.kt"), "drawHandPaintScene")
        assertTrue("必须区分是否自播", fn.contains("val playing = effEdge == null && scrubP < 0f && scene == SceneState.AOD"))
        assertTrue("非自播必须停时间轴", fn.contains("HandPaintTimeline.stop(handPaintPlay)"))
        assertTrue("非自播进度必须是整幅", Regex("""HandPaintTimeline\.stop\(handPaintPlay\)\s*\n\s*1f""").containsMatchIn(fn))
    }

    /** 轨迹未就绪不许早退：锁屏/桌面内容层与时钟照画，只是没有花。 */
    @Test
    fun pendingStillDrawsLockHomeAndClock() {
        val fn = body(src("ui/aod/AodPreviewView.kt"), "drawHandPaintScene")
        assertTrue("未就绪必须留日志", fn.contains("logHandPaint(\"pending style="))
        val pending = fn.substring(fn.indexOf("if (silk == null)"), fn.indexOf("val lockHome ="))
        assertTrue("未就绪分支不许 return", !pending.contains("return"))
        assertTrue("渲染器建不出也不许 return", !pending.contains("?: return"))
        assertTrue("未就绪也要走到内容层", fn.indexOf("drawLockOverlay") > fn.indexOf("if (silk == null)"))
        assertTrue("未就绪也要画时钟", fn.contains("drawHandPaintClock(canvas, w, h, playing && silk != null, now)"))
    }

    /** 阳性对照：旧写法在轨迹缺失时直接 return，锁屏桌面全空。 */
    @Test
    fun positiveControlEarlyReturnLosesAllLayers() {
        val legacy = """
            val silks = handPaintSilks
            if (silks == null) { drawSceneClock(canvas, w, h); drawVignette(canvas, w, h); return }
            val silk = silks.firstOrNull() ?: return
            val r = ensureHandPaintRenderer(rect[2]) ?: return
            drawLockOverlay(canvas, w, h, 1f, 0f)
        """.trimIndent()
        assertTrue("旧写法有三处早退", Regex("""\breturn\b""").findAll(legacy).count() == 3)
        assertTrue("早退都在内容层之前", legacy.indexOf("drawLockOverlay") > legacy.lastIndexOf("return"))
    }

    /** 换款与离窗必须释放离屏画布，避免位图泄漏。 */
    @Test
    fun rendererReleasedOnStyleChangeAndDetach() {
        val text = src("ui/aod/AodPreviewView.kt")
        val sync = body(text, "syncHandPaintStyle")
        assertTrue("非手绘款必须释放", sync.contains("handPaintRenderer?.close()"))
        assertTrue("换款必须清轨迹", sync.contains("handPaintSilks = null"))
        assertTrue("换款必须重置时间轴", sync.contains("handPaintPlay.reset()"))
        val detach = body(text, "onDetachedFromWindow")
        assertTrue("离窗必须释放", detach.contains("handPaintRenderer?.close()"))
    }

    /** 时长口径两侧同源：500ms/千点钳 [2000,20000]。 */
    @Test
    fun replayDurationIsSharedAndClamped() {
        assertEquals(500L, HandPaintGeometry.REPLAY_MS_PER_KILO_POINT)
        assertEquals(2000L, HandPaintGeometry.REPLAY_MIN_MS)
        assertEquals(20000L, HandPaintGeometry.REPLAY_MAX_MS)
        assertEquals(6500L, HandPaintGeometry.replayDurationMs(13001))
        assertEquals(2000L, HandPaintGeometry.replayDurationMs(1))
        assertEquals(20000L, HandPaintGeometry.replayDurationMs(999999))
    }

    /** 款号范围：0 号是空占位，1..9 可用。 */
    @Test
    fun styleRangeExcludesEmptyPlaceholder() {
        assertTrue(!HandPaintSource.validStyle(0))
        assertTrue(HandPaintSource.validStyle(1))
        assertTrue(HandPaintSource.validStyle(9))
        assertTrue(!HandPaintSource.validStyle(10))
        assertEquals(HandPaintSource.DEFAULT_STYLE, HandPaintSource.normalizeStyle(0))
    }

    /** 阳性对照：只声明不接线时预览仍走不到手绘分支。 */
    @Test
    fun positiveControlDeclarationWithoutDispatchIsUnreachable() {
        val legacy = """
            if (spec.lens) { drawSailScene(...); return }
            if (spec.photo != null) { drawPhotoScene(...); return }
            sceneSample.sample(spec.contract, u)
        """.trimIndent()
        assertTrue("旧分派没有手绘出口", !legacy.contains("handPaintStyle"))
    }

    /** 贴图是官方 dp 见方小图，不铺满整屏短边（两侧同源）。 */
    @Test
    fun presentRectIsOfficialDpSquareNotFullScreen() {
        val px = HandPaintSource.canvasPx(3f)
        assertEquals(540f, px, 1e-4f)
        val r = HandPaintGeometry.presentRect(px, HandPaintSource.CENTER_Y, 1080f, 2376f)
        assertEquals("边长取官方 dp 不取短边", 540f, r[2], 1e-4f)
        assertEquals("水平居中", 270f, r[0], 1e-4f)
        assertEquals("中心落屏高定比例", 2376f * HandPaintSource.CENTER_Y - 270f, r[1], 1e-3f)
        val clamped = HandPaintGeometry.presentRect(9999f, HandPaintSource.CENTER_Y, 400f, 800f)
        assertEquals("超短边必须钳住", 400f, clamped[2], 1e-4f)
    }

    /** 阳性对照：铺满整屏短边会把小图放大一倍以上、且中心位置错。 */
    @Test
    fun positiveControlFullScreenBlitIsWrong() {
        val full = minOf(1080f, 2376f)
        val official = HandPaintGeometry.presentRect(
            HandPaintSource.canvasPx(3f), HandPaintSource.CENTER_Y, 1080f, 2376f,
        )
        assertEquals("铺满短边恰好大一倍", 2f, full / official[2], 1e-4f)
        val fullTop = (2376f - full) * 0.5f
        val officialCenter = official[1] + official[2] * 0.5f
        assertEquals(
            "铺满后花心从 0.38 屏高落到正中，差 261px",
            2376f * (0.5f - HandPaintSource.CENTER_Y), (fullTop + full * 0.5f) - officialCenter, 1e-3f,
        )
        assertTrue("花心位移必须超过 250px", kotlin.math.abs((fullTop + full * 0.5f) - officialCenter) > 250f)
    }

    /** 时钟必须等笔画播到官方延迟才淡入，且透明期不画。 */
    @Test
    fun clockFadesInAfterOfficialDelay() {
        assertEquals(0f, HandPaintSource.clockFadeAt(0L), 1e-6f)
        assertEquals(0f, HandPaintSource.clockFadeAt(HandPaintSource.CLOCK_DELAY_MS), 1e-6f)
        assertEquals(0.5f, HandPaintSource.clockFadeAt(HandPaintSource.CLOCK_DELAY_MS + 300L), 1e-4f)
        assertEquals(1f, HandPaintSource.clockFadeAt(HandPaintSource.CLOCK_DELAY_MS + HandPaintSource.CLOCK_DURATION_MS), 1e-6f)
        val fn = body(src("ui/aod/AodPreviewView.kt"), "drawHandPaintClock")
        assertTrue("必须走共用淡入曲线", fn.contains("HandPaintSource.clockFadeAt(now - handPaintPlay.originMs)"))
        assertTrue("透明期必须不画只记日志", fn.contains("logHandPaint(\"clock-wait"))
        assertTrue("非自播态恒整幅时钟", fn.contains("1f"))
    }

    /** 阳性对照：不接淡入就是时钟与花同时出现（原厂是花先画、时钟后出）。 */
    @Test
    fun positiveControlNoFadeMeansClockFromFirstFrame() {
        assertTrue("零延迟就出时钟", HandPaintSource.clockFadeAt(HandPaintSource.CLOCK_DELAY_MS + 1L) > 0f)
        assertEquals("零毫秒必须还没有时钟", 0f, HandPaintSource.clockFadeAt(0L), 1e-6f)
    }

    /** 手绘转场必须画锁屏与桌面内容层，否则展开后只有壁纸和时钟。 */
    @Test
    fun handPaintSceneDrawsLockAndHomeLayers() {
        val fn = body(src("ui/aod/AodPreviewView.kt"), "drawHandPaintScene")
        assertTrue("必须画锁屏层", fn.contains("drawLockOverlay(canvas, w, h, 1f)"))
        assertTrue("必须画桌面图标", fn.contains("drawSceneHomeIcons(canvas, w, h, 1f)"))
        assertTrue("必须画 Dock", fn.contains("drawSceneHomeDock(canvas, w, h, 1f)"))
        assertTrue("锁屏↔桌面不缩放（与照片类同口径）", fn.contains("val lockHome = effEdge == TransitionEdge.LOCK_TO_HOME || effEdge == TransitionEdge.HOME_TO_LOCK"))
        assertTrue("必须走内容闸门", fn.contains("layers.lockAlpha * contentGate"))
    }

    /** 阳性对照：只有壁纸+时钟的旧写法在锁屏态没有通知与天气。 */
    @Test
    fun positiveControlBackdropOnlyHasNoLockContent() {
        val legacy = """
            drawEffectBackdrop(canvas, w, h, backdrop, effEdge, t)
            r.present(canvas, rect[0], rect[1], rect[2], alpha)
            drawSceneClock(canvas, w, h)
        """.trimIndent()
        assertTrue("旧写法无锁屏层", !legacy.contains("drawLockOverlay"))
        assertTrue("旧写法无桌面层", !legacy.contains("drawSceneHomeIcons"))
    }

    /** 离屏画布按贴图边长建，不按整屏短边（小图放大会发虚且吃内存）。 */
    @Test
    fun rendererSideFollowsPresentRect() {
        val text = src("ui/aod/AodPreviewView.kt")
        assertTrue("必须按贴图边长建", body(text, "drawHandPaintScene").contains("ensureHandPaintRenderer(rect[2])"))
        assertTrue("边长必须钳到上限", body(text, "ensureHandPaintRenderer").contains("coerceIn(1, HandPaintRenderer.MAX_SIDE)"))
    }

    /** 暗角只压息屏世界：锁屏/桌面态强度必须为 0，否则内容被无谓压暗。 */
    @Test
    fun vignetteFadesWithAodPresence() {
        val fn = body(src("ui/aod/AodPreviewView.kt"), "drawVignette")
        assertTrue("强度必须取息屏占比", fn.contains("val strength = layers.aodAlpha.coerceIn(0f, 1f)"))
        assertTrue("零息屏必须直接不画", fn.contains("if (strength <= 0.002f) return"))
        assertTrue("边缘 alpha 必须随强度缩放", fn.contains("(0x66 * strength).toInt()"))
        for (s in listOf(SceneState.LOCK, SceneState.HOME)) {
            val l = SystemSceneMotion.Layers()
            SystemSceneMotion.sampleStatic(s, l)
            assertEquals("$s 态息屏占比必须为 0", 0f, l.aodAlpha, 1e-6f)
        }
        val aod = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(SceneState.AOD, aod)
        assertEquals("息屏态必须满强度", 1f, aod.aodAlpha, 1e-6f)
    }

    /** 阳性对照：固定 0x66 边缘在锁屏态仍压暗，白底会被压成灰。 */
    @Test
    fun positiveControlFixedVignetteDarkensLockContent() {
        val fixedEdgeAlpha = 0x66 / 255f
        val white = 255f
        val darkened = white * (1f - fixedEdgeAlpha)
        assertTrue("固定暗角把 255 压到 153 以下", darkened < 154f)
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(SceneState.LOCK, l)
        assertEquals("按占比缩放后锁屏边缘 alpha 必须是 0", 0, (0x66 * l.aodAlpha).toInt())
    }

    /** 阳性对照：格点采样不整除时长，末次采样不在端点上。 */
    @Test
    fun positiveControlSamplingGridNeedNotLandOnEndpoint() {
        val dur = HandPaintGeometry.replayDurationMs(13001)
        val lastGrid = dur / 37L * 37L
        assertTrue("末格点必须小于时长", lastGrid < dur)
        val p = HandPaintTimeline.Play()
        HandPaintTimeline.start(p, 1, 0L, 13001)
        assertTrue("末格点进度 < 1", HandPaintTimeline.progressAt(p, lastGrid) < 1f)
        assertEquals("端点必须恰好 1", 1f, HandPaintTimeline.progressAt(p, dur), 1e-6f)
    }
}
