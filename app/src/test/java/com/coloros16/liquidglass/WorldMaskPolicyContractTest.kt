package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SkyBranchPolicy
import com.coloros16.liquidglass.engine.WorldMaskPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 世界层与几何遮罩分工契约：世界层画成即不叠遮罩，未画成必须回落遮罩。
 */
class WorldMaskPolicyContractTest {

    private fun layer(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodTransitionLayer.kt",
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
    fun everyWorldBranchOwnsDim() {
        for (b in SkyBranchPolicy.Branch.values()) {
            if (b == SkyBranchPolicy.Branch.NONE) continue
            assertTrue("分支 $b 自带整屏压暗", WorldMaskPolicy.ownsDim(b))
        }
    }

    @Test
    fun noneBranchDoesNotOwnDim() {
        assertFalse(
            "没有世界层时压暗仍归遮罩或整屏压暗",
            WorldMaskPolicy.ownsDim(SkyBranchPolicy.Branch.NONE),
        )
    }

    @Test
    fun veilSkippedWhenWorldDrawn() {
        assertFalse(
            "世界层画成后不得再叠几何遮罩",
            WorldMaskPolicy.veilDrawable(true, maskEnabled = true, radiusDrawable = true, maskByFrame = false),
        )
    }

    @Test
    fun veilFallsBackWhenWorldNotDrawn() {
        assertTrue(
            "素材未就绪世界层返 false 时必须回落遮罩，否则整屏无揭开",
            WorldMaskPolicy.veilDrawable(false, maskEnabled = true, radiusDrawable = true, maskByFrame = false),
        )
    }

    @Test
    fun veilNeedsBothEnabledAndDrawableRadius() {
        assertFalse(WorldMaskPolicy.veilDrawable(false, false, true, false))
        assertFalse(WorldMaskPolicy.veilDrawable(false, true, false, false))
    }

    @Test
    fun veilSkippedWhenFrameReveal() {
        assertFalse(
            "色键帧档形状来自素材，不画几何遮罩",
            WorldMaskPolicy.veilDrawable(false, true, true, maskByFrame = true),
        )
    }

    @Test
    fun positiveControlOldLogicWouldDoubleDimEveryWorldBranch() {
        for (b in SkyBranchPolicy.Branch.values()) {
            if (b == SkyBranchPolicy.Branch.NONE) continue
            val oldVeilWouldDraw = true
            assertTrue(
                "旧逻辑对分支 $b 会在世界层之上再画一层遮罩（阳性对照）",
                oldVeilWouldDraw && WorldMaskPolicy.ownsDim(b),
            )
            assertFalse(
                "新逻辑对分支 $b 不画遮罩",
                WorldMaskPolicy.veilDrawable(true, true, true, false),
            )
        }
    }

    @Test
    fun dimSkippedWhenWorldDrawn() {
        assertFalse(WorldMaskPolicy.dimDrawable(worldDrawn = true, maskByFrame = false, veilDrawn = false))
    }

    @Test
    fun dimSkippedWhenFrameReveal() {
        assertFalse(
            "色键帧档压暗由 shader 承担",
            WorldMaskPolicy.dimDrawable(worldDrawn = false, maskByFrame = true, veilDrawn = false),
        )
    }

    @Test
    fun dimSkippedWhenVeilDrawn() {
        assertFalse(
            "遮罩已按同一压暗量画过外侧，整屏不再叠",
            WorldMaskPolicy.dimDrawable(worldDrawn = false, maskByFrame = false, veilDrawn = true),
        )
    }

    @Test
    fun dimDrawnOnlyWhenNothingElsePaintedIt() {
        assertTrue(
            WorldMaskPolicy.dimDrawable(worldDrawn = false, maskByFrame = false, veilDrawn = false),
        )
    }

    @Test
    fun dimTruthTableExhaustive() {
        var drawCount = 0
        for (world in listOf(false, true)) {
            for (frame in listOf(false, true)) {
                for (veil in listOf(false, true)) {
                    val expected = !world && !frame && !veil
                    assertEquals(
                        "world=$world frame=$frame veil=$veil",
                        expected,
                        WorldMaskPolicy.dimDrawable(world, frame, veil),
                    )
                    if (expected) drawCount++
                }
            }
        }
        assertEquals("八种组合里只有一种该画整屏压暗", 1, drawCount)
    }

    @Test
    fun layerRoutesVeilThroughPolicy() {
        val b = body(layer(), "onDraw")
        assertTrue("遮罩判定必须走共用策略", b.contains("WorldMaskPolicy.veilDrawable("))
        assertTrue("整屏压暗判定必须走共用策略", b.contains("WorldMaskPolicy.dimDrawable("))
    }

    @Test
    fun layerPassesActualWorldDrawnNotSpecPresence() {
        val b = body(layer(), "onDraw")
        assertTrue(
            "必须传本帧世界层返回值，不是「该样式有世界层」",
            b.contains("val world =") && b.contains("drawWorld(canvas, w, h)"),
        )
        assertTrue(b.contains("world, p.maskEnabled, MaskGeometry.drawable(p.maskRadius), p.maskByFrame"))
    }

    @Test
    fun drawDimNoLongerRepeatsGateLogic() {
        val b = body(layer(), "drawDim")
        assertFalse(
            "门控已上移到 onDraw，drawDim 内不得再判一遍（两份判据会漂移）",
            b.contains("maskByFrame") || b.contains("maskEnabled"),
        )
    }

    @Test
    fun worldOwnsMaskHasThrottledDiagnostic() {
        val s = layer()
        assertTrue("让位遮罩必须配诊断日志", s.contains("logWorldOwnsMask"))
        val b = body(s, "logWorldOwnsMask")
        assertTrue(b.contains("worldMaskLoggedAt"))
        assertTrue(b.contains("FRAME_REVEAL_LOG_MS"))
        assertTrue(b.contains("mask by world"))
    }

    @Test
    fun veilStillDrawnForNonSceneStyles() {
        val b = body(layer(), "onDraw")
        assertTrue("非场景类仍走遮罩", b.contains("drawMaskVeil(canvas, w, h, p)"))
        assertTrue("色键帧档仍走既有诊断", b.contains("logFrameReveal()"))
    }

    /**
     * 新旧行为穷举对照：128 种组合里只允许「世界层画成 + 遮罩启用 + 非色键档」两处不同，
     * 其余 126 种必须逐一等价（证明这次只修双重压暗，没顺手改别的路径）。
     */
    @Test
    fun onlyWorldDrawnCasesDifferFromOldBehavior() {
        var same = 0
        val diffs = mutableListOf<String>()
        for (world in listOf(false, true)) {
            for (active in listOf(false, true)) {
                for (hasParams in listOf(false, true)) {
                    for (frame in listOf(false, true)) {
                        for (enabled in listOf(false, true)) {
                            for (r in listOf(0f, 0.4f, 1f, 500f)) {
                                val radiusOk = r > 0.5f
                                val oldVeil: Boolean
                                val oldDim: Boolean
                                if (!active || !hasParams) {
                                    oldVeil = false
                                    oldDim = !world
                                } else {
                                    oldVeil = !frame && enabled && radiusOk
                                    oldDim = !world && !frame && !(enabled && radiusOk)
                                }
                                val newVeil: Boolean
                                val newDim: Boolean
                                if (!active || !hasParams) {
                                    newVeil = false
                                    newDim = WorldMaskPolicy.dimDrawable(world, false, false)
                                } else {
                                    newVeil = WorldMaskPolicy.veilDrawable(world, enabled, radiusOk, frame)
                                    newDim = WorldMaskPolicy.dimDrawable(world, frame, newVeil)
                                }
                                if (oldVeil == newVeil && oldDim == newDim) {
                                    same++
                                } else {
                                    diffs.add("world=$world frame=$frame enabled=$enabled r=$r")
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals("只允许两处差异", 2, diffs.size)
        assertEquals("其余必须等价", 126, same)
        for (d in diffs) {
            assertTrue("差异必须都发生在世界层画成时：$d", d.startsWith("world=true"))
            assertTrue("差异必须都是非色键档：$d", d.contains("frame=false"))
            assertTrue("差异必须都是遮罩启用：$d", d.contains("enabled=true"))
        }
    }

    @Test
    fun doubleDimQuantifiedForHandPaintCorner() {
        val dim = 0.46f
        val base = 200f
        val afterWorld = base * (1f - dim)
        val afterVeil = afterWorld * (1f - dim)
        val lost = afterWorld - afterVeil
        assertTrue(
            "同量叠两层会再暗掉 ${lost}（占世界层输出的 ${lost / afterWorld * 100f}%）",
            lost > 40f,
        )
        assertEquals(afterWorld * dim, lost, 0.01f)
    }
}
