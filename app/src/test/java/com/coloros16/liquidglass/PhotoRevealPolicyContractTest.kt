package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.PhotoRevealPolicy
import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 照片类揭开目标底图契约：photoAlpha<1 必须给 uReveal，否则 shader 混到黑。
 */
class PhotoRevealPolicyContractTest {

    private fun world(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt",
    ).readText()

    private fun renderer(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/PhotoSceneRenderer.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
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

    @Test
    fun fullAlphaNeedsNoReveal() {
        assertFalse(PhotoRevealPolicy.needed(1f, SceneState.HOME))
        assertFalse(PhotoRevealPolicy.needed(0.9995f, SceneState.HOME))
    }

    @Test
    fun partialAlphaNeedsReveal() {
        assertTrue(PhotoRevealPolicy.needed(0.5f, SceneState.HOME))
        assertTrue(PhotoRevealPolicy.needed(0f, SceneState.LOCK))
    }

    /** 目标是息屏时不参与：息屏是起点不是揭开目标。 */
    @Test
    fun aodTargetIsSkipped() {
        assertFalse(PhotoRevealPolicy.needed(0.3f, SceneState.AOD))
        assertFalse(PhotoRevealPolicy.needed(0.3f, null))
    }

    @Test
    fun targetFollowsEdgeEnd() {
        assertEquals(SceneState.HOME, PhotoRevealPolicy.targetOf(TransitionEdge.LOCK_TO_HOME, SceneState.LOCK))
        assertEquals(SceneState.LOCK, PhotoRevealPolicy.targetOf(TransitionEdge.HOME_TO_LOCK, SceneState.HOME))
        assertEquals(SceneState.AOD, PhotoRevealPolicy.targetOf(TransitionEdge.LOCK_TO_AOD, SceneState.LOCK))
    }

    @Test
    fun targetFallsBackToStateWithoutEdge() {
        assertEquals(SceneState.HOME, PhotoRevealPolicy.targetOf(null, SceneState.HOME))
    }

    /** 缓存键四项输入：任一变化都要失效。 */
    @Test
    fun keyCarriesAllInputs() {
        val base = PhotoRevealPolicy.keyOf(SceneState.HOME, 1080, 2400, 7)
        assertTrue(PhotoRevealPolicy.keyValid(base, SceneState.HOME, 1080, 2400, 7))
        assertFalse("换目标态", PhotoRevealPolicy.keyValid(base, SceneState.LOCK, 1080, 2400, 7))
        assertFalse("换宽", PhotoRevealPolicy.keyValid(base, SceneState.HOME, 1081, 2400, 7))
        assertFalse("换高", PhotoRevealPolicy.keyValid(base, SceneState.HOME, 1080, 2401, 7))
        assertFalse("换壁纸", PhotoRevealPolicy.keyValid(base, SceneState.HOME, 1080, 2400, 8))
        assertFalse("无缓存", PhotoRevealPolicy.keyValid(null, SceneState.HOME, 1080, 2400, 7))
    }

    @Test
    fun snapshotSideIsHalfAndAtLeastTwo() {
        assertEquals(540, PhotoRevealPolicy.snapshotSide(1080f))
        assertEquals(2, PhotoRevealPolicy.snapshotSide(1f))
        assertEquals(2, PhotoRevealPolicy.snapshotSide(0f))
    }

    /**
     * ★真值：photoAlpha 终点只由**目标态**决定 —— 目标是桌面则为 0，否则为 1。
     *
     * 两条入桌面的边（AOD→HOME / LOCK→HOME）都淡出，不是只有解锁那一条。
     */
    @Test
    fun photoAlphaEndpointFollowsTargetState() {
        val style = PhotoStyleMotion.byId(1)!!
        val v = PhotoStyleMotion.Values()
        for (edge in TransitionEdge.values()) {
            PhotoStyleMotion.sample(style, edge, 1f, 2400f / 1080f, 0f, 0f, v)
            val a = v.v[PhotoStyle.SLOT_PHOTO_ALPHA]
            val expected = if (edge.to == SceneState.HOME) 0f else 1f
            assertEquals("边 $edge 终点由目标态决定", expected, a, 1e-4f)
        }
    }

    /** 起点侧同理：从桌面出发的边起点为 0，其余为 1。 */
    @Test
    fun photoAlphaStartFollowsSourceState() {
        val style = PhotoStyleMotion.byId(1)!!
        val v = PhotoStyleMotion.Values()
        for (edge in TransitionEdge.values()) {
            PhotoStyleMotion.sample(style, edge, 0f, 2400f / 1080f, 0f, 0f, v)
            val a = v.v[PhotoStyle.SLOT_PHOTO_ALPHA]
            val expected = if (edge.from == SceneState.HOME) 0f else 1f
            assertEquals("边 $edge 起点由起点态决定", expected, a, 1e-4f)
        }
    }

    /** ★阳性对照：不给 uReveal 时 shader 拿 content 自身兜底 ⇒ 等于没混合。 */
    @Test
    fun positiveControlShaderFallsBackToContent() {
        val r = renderer()
        assertTrue("缺省兜底是 content 自己", r.contains("shader.setInputBuffer(\"uReveal\", revealSh ?: content)"))
        assertTrue("混合只在 alpha<1 时执行", r.contains("if (uPhotoAlpha < 1.0)"))
        assertTrue(r.contains("content = mix(revealColor, content, uPhotoAlpha);"))
    }

    /** 阳性对照：解锁中段 alpha 明显小于 1，不给底图就是可见缺陷。 */
    @Test
    fun positiveControlMidUnlockAlphaIsPartial() {
        val style = PhotoStyleMotion.byId(1)!!
        val v = PhotoStyleMotion.Values()
        PhotoStyleMotion.sample(style, TransitionEdge.LOCK_TO_HOME, 0.5f, 2400f / 1080f, 0f, 0f, v)
        val a = v.v[PhotoStyle.SLOT_PHOTO_ALPHA]
        assertTrue("中段必须是部分透明", a > 0.01f && a < 0.99f)
        assertTrue(PhotoRevealPolicy.needed(a, SceneState.HOME))
    }

    @Test
    fun hookPassesRevealPhoto() {
        val b = body(world(), "drawPhoto")
        assertTrue("必须走策略求值", b.contains("revealBackdrop("))
        assertTrue("必须传给渲染器", b.contains("revealPhoto = reveal,"))
        assertTrue("目标态按边取", b.contains("PhotoRevealPolicy.targetOf(edge, state)"))
    }

    @Test
    fun hookRevealIsCachedNotPerFrame() {
        val b = body(world(), "revealBackdrop")
        assertTrue("命中缓存直接返回", b.contains("revealKey == key && !cached.isRecycled"))
        assertTrue("旧图必须释放", b.contains("revealCache?.recycle()"))
        assertTrue("不需要时早退", b.contains("if (!PhotoRevealPolicy.needed(photoAlpha, target))"))
    }

    /** 键含缩放：壁纸缩放变了快照也要重建（否则底图与新缩放不同框）。 */
    @Test
    fun hookKeyIncludesZoom() {
        val b = body(world(), "revealBackdrop")
        assertTrue(b.contains("System.identityHashCode(bmp)"))
        assertTrue("缩放必须进键", b.contains("format(zoom)"))
    }

    @Test
    fun hookRevealHasDiagnostics() {
        val b = body(world(), "revealBackdrop")
        assertTrue(b.contains("world-reveal-off"))
        assertTrue(b.contains("world-reveal-hit"))
        assertTrue(b.contains("world-reveal-build"))
        assertTrue(b.contains("world-reveal-fail"))
    }

    @Test
    fun releaseRecyclesRevealCache() {
        val b = body(world(), "release")
        assertTrue(b.contains("revealCache?.recycle()"))
        assertTrue(b.contains("revealCache = null"))
        assertTrue(b.contains("revealKey = null"))
    }

    /** 预览侧早就在做这件事：本条是把 hook 补齐到同口径。 */
    @Test
    fun previewAlreadyCapturesReveal() {
        val p = preview()
        assertTrue(p.contains("captureRevealTarget(canvas, w, h, effEdge, photoAlpha)"))
        assertTrue(p.contains("revealPhoto = reveal,"))
    }

    /** ★回收前必须让渲染器丢掉 shader，否则按身份命中旧 shader 采样已释放内存。 */
    @Test
    fun recycleMustForgetRendererShaderFirst() {
        val w = world()
        val b = body(w, "revealBackdrop")
        val forgetAt = b.indexOf("forgetReveal()")
        val recycleAt = b.indexOf("revealCache?.recycle()")
        assertTrue("两者都要有", forgetAt >= 0 && recycleAt >= 0)
        assertTrue("必须先丢 shader 再回收", forgetAt < recycleAt)
        val rel = body(w, "release")
        assertTrue(
            "release 里也必须在置空前丢",
            rel.indexOf("photoRenderer?.forgetReveal()") < rel.indexOf("photoRenderer = null"),
        )
    }

    /** 渲染器侧：已回收位图不得建 shader；提供显式丢弃入口。 */
    @Test
    fun rendererGuardsRecycledBitmap() {
        val r = renderer()
        assertTrue(r.contains("fun forgetReveal()"))
        assertTrue(body(r, "revealShaderOf").contains("if (bitmap.isRecycled) return null"))
    }

    /** 缓存命中但位图已回收时要清键重建，不能一直返回死图。 */
    @Test
    fun staleCacheClearsKey() {
        val b = body(world(), "revealBackdrop")
        assertTrue(b.contains("cached.isRecycled"))
        assertTrue(b.contains("world-reveal-stale"))
    }

    /** 禁轮询：快照按需建，不得起计时器。 */
    @Test
    fun noPollingInRevealPath() {
        val b = body(world(), "revealBackdrop")
        assertFalse(b.contains("postDelayed"))
        assertFalse(b.contains("Timer("))
        assertFalse(b.contains("while (true)"))
    }

    /** 快照尺寸必须与传给 shader 的 uRevealSize 语义一致（按位图实际尺寸）。 */
    @Test
    fun revealSizeComesFromBitmap() {
        val r = renderer()
        assertTrue(
            r.contains("(revealPhoto?.width ?: contentBitmap.width).toFloat()"),
        )
        assertNotEquals(0, PhotoRevealPolicy.SNAPSHOT_DIVISOR)
    }
}
