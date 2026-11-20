package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SkyBranchPolicy
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 手绘世界层壁纸底契约：手绘分支必须画壁纸底并消费系统缩放，与预览侧同口径。
 */
class HandPaintBackdropContractTest {

    private fun world(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt",
    ).readText()

    private fun layer(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodTransitionLayer.kt",
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

    /** 只有手绘分支自身不画底，其余三支都画（天空画程序化底）。 */
    @Test
    fun onlyHandPaintLackedBackdrop() {
        val w = world()
        assertTrue("镜片画底", body(w, "drawLens").contains("drawBackdrop("))
        assertTrue("照片类画底", body(w, "drawPhoto").contains("drawBackdrop("))
        assertTrue("手绘现在也画底", body(w, "drawHandPaint").contains("drawWallpaperBase("))
    }

    @Test
    fun handPaintConsumesWallpaperScale() {
        val w = world()
        assertTrue(
            "手绘签名必须有缩放参数与底图源开关",
            w.contains("wallpaperScale: Float = 1f,\n        useSystemWallpaper: Boolean = true,\n        onBackdrop"),
        )
        assertTrue(
            "必须传给底并透传底图源开关与底后回调",
            body(w, "drawHandPaint")
                .contains("drawWallpaperBase(\n            canvas, width, height, dim, wallpaperScale, useSystemWallpaper, onBackdrop,\n        )"),
        )
    }

    @Test
    fun callerPassesSceneLayersScale() {
        assertTrue(
            "调用点必须传世界缩放与底图源开关",
            layer().contains(
                "wallpaperScale = worldWallpaperScale,\n" +
                    "                useSystemWallpaper = worldUseSystemWallpaper,\n" +
                    "                onBackdrop",
            ),
        )
    }

    @Test
    fun baseDrawsBackdropThenDim() {
        val b = body(world(), "drawWallpaperBase")
        val backdropAt = b.indexOf("drawBackdrop(")
        val dimAt = b.indexOf("drawRect(")
        assertTrue("两者都要有", backdropAt >= 0 && dimAt >= 0)
        assertTrue("底必须在压暗之前", backdropAt < dimAt)
    }

    /** 底未就绪也要画压暗，并返回 false 让调用方知道。 */
    @Test
    fun baseReportsMissingSource() {
        val b = body(world(), "drawWallpaperBase")
        assertTrue("有底才画", b.contains("if (bmp != null) {"))
        assertTrue("画底走共用折算", b.contains("drawBackdrop(canvas, width, height, bmp, wallpaperScale)"))
        assertTrue(b.contains("return bmp != null"))
        assertTrue("缺源要有诊断", b.contains("world-base-nosource"))
        assertTrue("缺源不触发底后回调", b.indexOf("onBackdrop()") < b.indexOf("} else logThrottled("))
    }

    /** 阳性对照：修前手绘分支只有压暗矩形，没有任何壁纸绘制。 */
    @Test
    fun positiveControlOldHandPaintHadNoBackdrop() {
        val b = body(world(), "drawHandPaint")
        assertFalse("手绘不得自己直接画底（走共用件避免两份）", b.contains("drawBackdrop("))
        assertTrue("必须经共用件", b.contains("drawWallpaperBase("))
    }

    /** 缩放真值：锁屏 1.1，息屏/桌面 1.0，转场期在两者之间。 */
    @Test
    fun wallpaperScaleTruthValues() {
        assertEquals(1.0f, SystemSceneMotion.WALLPAPER_SCALE_AOD, 1e-6f)
        assertEquals(1.1f, SystemSceneMotion.WALLPAPER_SCALE_LOCK, 1e-6f)
        assertEquals(1.0f, SystemSceneMotion.WALLPAPER_SCALE_HOME, 1e-6f)
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, 0.5f, l)
        assertTrue("中间帧必须在两端之间", l.wallpaperScale > 1.0f && l.wallpaperScale < 1.1f)
    }

    /** 阳性对照：不带缩放（旧行为恒 1）与真值在锁屏态差 10%。 */
    @Test
    fun positiveControlMissingScaleIsTenPercentOff() {
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(com.coloros16.liquidglass.engine.SceneState.LOCK, l)
        assertEquals(1.1f, l.wallpaperScale, 1e-6f)
        assertTrue("旧行为恒 1 与真值差 0.1", kotlin.math.abs(l.wallpaperScale - 1f) > 0.09f)
    }

    @Test
    fun previewHandPaintDrawsBackdropWithScale() {
        val p = preview()
        val b = body(p, "drawHandPaintScene")
        assertTrue("预览手绘先画底", b.contains("drawEffectBackdrop("))
        val eb = body(p, "drawEffectBackdrop")
        // 缩放统一走 wallpaperZoom()：静止/边内取引擎分层，落位补间期取补间值
        assertTrue("底消费壁纸缩放取值件", eb.contains("drawBitmapCover(canvas, backdrop, w, h, wallpaperZoom()"))
    }

    /** 底与压暗的顺序必须与预览一致：底 → 压暗 → 花。 */
    @Test
    fun orderMatchesPreview() {
        val p = body(preview(), "drawHandPaintScene")
        val backdropAt = p.indexOf("drawEffectBackdrop(")
        val dimAt = p.indexOf("canvas.drawRect(0f, 0f, w, h, fill)")
        val flowerAt = p.indexOf("r.present(")
        assertTrue(backdropAt in 0 until dimAt)
        assertTrue(dimAt < flowerAt)
        val h = body(world(), "drawHandPaint")
        assertTrue(h.indexOf("drawWallpaperBase(") < h.indexOf("presentHandPaint("))
    }

    @Test
    fun baseIsLogged() {
        assertTrue(
            "底状态必须可诊断",
            body(world(), "drawHandPaint").contains("world-handpaint-base"),
        )
    }

    /** 手绘分支判定不变：本轮只补底，不动分支优先级。 */
    @Test
    fun branchPriorityUnchanged() {
        assertEquals(
            SkyBranchPolicy.Branch.HANDPAINT,
            SkyBranchPolicy.branchOf(false, false, false, 6),
        )
        assertEquals(
            SkyBranchPolicy.Branch.PHOTO,
            SkyBranchPolicy.branchOf(false, true, false, 6),
        )
        assertEquals(SkyBranchPolicy.Branch.LENS, SkyBranchPolicy.branchOf(true, false, false, 6))
    }

    /** 壁纸源刷新仍是事件驱动，不得因为补底新增轮询。 */
    @Test
    fun wallpaperRefreshStaysEventDriven() {
        val b = body(world(), "ensureWallpaper")
        assertFalse(b.contains("postDelayed"))
        assertFalse(b.contains("Timer("))
        assertFalse(b.contains("while (true)"))
        assertTrue("按 id 判重", b.contains("if (id == wallpaperId && wallpaper != null) return"))
    }
}
