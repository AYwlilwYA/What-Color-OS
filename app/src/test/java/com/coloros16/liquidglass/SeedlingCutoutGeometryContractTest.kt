package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingCutoutGeometry
import com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.CutoutKind
import com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.Hole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 摄像头包绕几何契约：分类/包孔宽度/内容分居两侧不压孔，含阳性对照。 */
class SeedlingCutoutGeometryContractTest {

    private val sw = 1080f
    private val gap = 24f   // 8dp×3
    private val pad = 42f   // 14dp×3

    private fun centerHole() = Hole(500f, 20f, 580f, 100f, sw)
    private fun leftHole() = Hole(60f, 20f, 140f, 100f, sw)
    private fun rightHole() = Hole(940f, 20f, 1020f, 100f, sw)
    private fun waterdrop() = Hole(490f, 0f, 590f, 60f, sw)

    // ---------- 分类 ----------

    @Test
    fun classifyPositions() {
        assertEquals(CutoutKind.CENTER, SeedlingCutoutGeometry.classify(centerHole()))
        assertEquals(CutoutKind.LEFT, SeedlingCutoutGeometry.classify(leftHole()))
        assertEquals(CutoutKind.RIGHT, SeedlingCutoutGeometry.classify(rightHole()))
        assertEquals(CutoutKind.WATERDROP, SeedlingCutoutGeometry.classify(waterdrop()))
    }

    // ---------- 水滴灵动额头凹口 ----------

    @Test
    fun notchGeometryWaterdrop() {
        // 水滴孔(490,0,590,60)：半宽=50+24=74，中心540，槽底=60-0+24=84
        val n = SeedlingCutoutGeometry.notchGeometry(200f, 0f, 880f, 24f, waterdrop(), gap)!!
        assertEquals(466f, n[0], 1e-3f)
        assertEquals(614f, n[1], 1e-3f)
        assertEquals(84f, n[2], 1e-3f)
    }

    @Test
    fun notchGeometryNonWaterdrop_positiveControl() {
        // 阳性对照：居中/左/右/无孔都不凹（返回 null）
        assertEquals(null, SeedlingCutoutGeometry.notchGeometry(200f, 0f, 880f, 24f, centerHole(), gap))
        assertEquals(null, SeedlingCutoutGeometry.notchGeometry(200f, 0f, 880f, 24f, leftHole(), gap))
        assertEquals(null, SeedlingCutoutGeometry.notchGeometry(200f, 0f, 880f, 24f, null, gap))
    }

    @Test
    fun notchClampedIntoCard() {
        // 孔偏心时凹口中心钳进卡矩形：卡(700,0,1000)，孔中心540 → 钳到 700+74+24=798
        val n = SeedlingCutoutGeometry.notchGeometry(700f, 0f, 1000f, 24f, waterdrop(), gap)!!
        assertEquals(798f - 74f, n[0], 1e-3f)
        assertEquals(798f + 74f, n[1], 1e-3f)
    }

    @Test
    fun notchDepthAtLeastCorner() {
        // 浅孔（bottom=10）槽底=10+24=34 < cornerR=60 → 钳到 60
        val shallow = Hole(490f, 0f, 590f, 10f, sw)
        val n = SeedlingCutoutGeometry.notchGeometry(200f, 0f, 880f, 60f, shallow, gap)!!
        assertEquals(60f, n[2], 1e-3f)
    }

    @Test
    fun classifyNearCenterTolerance_positiveControl() {
        // 阳性对照：中心偏 5% 屏宽仍算居中（挖孔不必严格在正中）
        val off = Hole(540f, 20f, 620f, 100f, sw)
        assertEquals(CutoutKind.CENTER, SeedlingCutoutGeometry.classify(off))
        val farOff = Hole(700f, 20f, 780f, 100f, sw)
        assertEquals(CutoutKind.RIGHT, SeedlingCutoutGeometry.classify(farOff))
    }


    // ---------- 包孔宽度 ----------

    @Test
    fun capsuleWidthWrapsHole() {
        // 分居两侧：half=42+max(48,60)+24+24+24=174；around=80+48+348=476；内容300<476→取包孔宽
        assertEquals(476f, SeedlingCutoutGeometry.capsuleWidth(300f, centerHole(), gap, pad, 24f, 60f), 1e-3f)
        // 内容宽 500 > 476 → 取内容宽
        assertEquals(500f, SeedlingCutoutGeometry.capsuleWidth(500f, centerHole(), gap, pad, 24f, 60f), 1e-3f)
        // 单侧（左挖孔）：80+48+42+48+24+24+24+60+42+24=416
        assertEquals(416f, SeedlingCutoutGeometry.capsuleWidth(300f, leftHole(), gap, pad, 24f, 60f), 1e-3f)
    }

    @Test
    fun capsuleWidthNoHole_positiveControl() {
        assertEquals("阳性对照：无挖孔不放大", 150f, SeedlingCutoutGeometry.capsuleWidth(150f, null, gap, pad), 1e-3f)
    }

    // ---------- 左缘方位 ----------

    @Test
    fun capsuleLeftByPosition() {
        val cw = 400f
        assertEquals("居中挖孔屏幕居中", sw / 2f - cw / 2f, SeedlingCutoutGeometry.capsuleLeft(centerHole(), sw, cw, gap, pad), 1e-3f)
        assertEquals("左挖孔贴左", 60f - gap - pad, SeedlingCutoutGeometry.capsuleLeft(leftHole(), sw, cw, gap, pad), 1e-3f)
        assertEquals("右挖孔贴右", 1020f + gap + pad - cw, SeedlingCutoutGeometry.capsuleLeft(rightHole(), sw, cw, gap, pad), 1e-3f)
        assertEquals("无挖孔居中", sw / 2f - cw / 2f, SeedlingCutoutGeometry.capsuleLeft(null, sw, cw, gap, pad), 1e-3f)
    }

    // ---------- 顶缘 ----------

    @Test
    fun capsuleTopAlignsHoleCenter() {
        val ch = 120f
        assertEquals("胶囊垂直中心对齐孔中心", 60f - ch / 2f, SeedlingCutoutGeometry.capsuleTop(centerHole(), ch), 1e-3f)
        assertEquals("水滴贴顶", 0f, SeedlingCutoutGeometry.capsuleTop(waterdrop(), ch), 1e-3f)
    }

    @Test
    fun cardTopBelowCamera() {
        assertEquals("大卡在挖孔下方", 100f + gap, SeedlingCutoutGeometry.cardTop(centerHole(), gap), 1e-3f)
        assertEquals("水滴大卡贴顶", 0f, SeedlingCutoutGeometry.cardTop(waterdrop(), gap), 1e-3f)
        assertEquals(0f, SeedlingCutoutGeometry.cardTop(null, gap), 1e-3f)
    }

    // ---------- 内容分居两侧 ----------

    @Test
    fun centerHoleContentSplitsAroundHole() {
        val hole = centerHole()
        val cw = SeedlingCutoutGeometry.capsuleWidth(300f, hole, gap, pad, 24f, 60f)
        val cl = SeedlingCutoutGeometry.capsuleLeft(hole, sw, cw, gap, pad)
        val a = SeedlingCutoutGeometry.capsuleContent(cl, cw, hole, gap, pad, 24f, 60f)
        // 符号在孔左侧、文字区在孔右侧，均不压孔
        assertTrue("符号中心在孔左", a[0] < hole.left)
        assertTrue("文字区左缘在孔右+间隙外", a[1] >= hole.right + gap)
        assertFalse("文字区不压孔", SeedlingCutoutGeometry.overlapsHole(a[1], a[2], hole))
        assertTrue("文字区有效宽", a[2] > a[1])
    }

    @Test
    fun leftHoleContentAllRight() {
        val hole = leftHole()
        val cw = SeedlingCutoutGeometry.capsuleWidth(500f, hole, gap, pad)
        val cl = SeedlingCutoutGeometry.capsuleLeft(hole, sw, cw, gap, pad)
        val a = SeedlingCutoutGeometry.capsuleContent(cl, cw, hole, gap, pad, 24f, 60f)
        assertTrue("左挖孔：符号也在孔右侧", a[0] > hole.right)
        assertFalse(SeedlingCutoutGeometry.overlapsHole(a[1], a[2], hole))
    }

    @Test
    fun rightHoleContentAllLeft() {
        val hole = rightHole()
        val cw = SeedlingCutoutGeometry.capsuleWidth(500f, hole, gap, pad)
        val cl = SeedlingCutoutGeometry.capsuleLeft(hole, sw, cw, gap, pad)
        val a = SeedlingCutoutGeometry.capsuleContent(cl, cw, hole, gap, pad, 24f, 60f)
        assertTrue("右挖孔：波形在孔左侧", a[3] < hole.left)
        assertFalse(SeedlingCutoutGeometry.overlapsHole(a[1], a[2], hole))
    }

    @Test
    fun noHoleOriginalLayout_positiveControl() {
        val a = SeedlingCutoutGeometry.capsuleContent(340f, 400f, null, gap, pad, 24f, 60f)
        assertEquals("阳性对照：无挖孔符号在左端", 340f + pad + 24f, a[0], 1e-3f)
        assertTrue(a[1] < a[2])
    }

    @Test
    fun waterdropKeepsOriginalLayout() {
        val a = SeedlingCutoutGeometry.capsuleContent(340f, 400f, waterdrop(), gap, pad, 24f, 60f)
        assertEquals("水滴不改变水平布局（凹口在顶缘）", 340f + pad + 24f, a[0], 1e-3f)
    }
}
