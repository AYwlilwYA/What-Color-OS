package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.CloudSpec
import com.coloros16.liquidglass.engine.DotSpec
import com.coloros16.liquidglass.engine.RadialSpec
import com.coloros16.liquidglass.engine.SceneSkyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 星月场景求值：星点/月海坑同序列、视差与淡出、云带分层、山脊路径、月球几何。 */
class SceneSkyMathTest {

    private val fields = SceneSkyMath.fields()
    private val stars = fields.first
    private val marks = fields.second

    @Test
    fun fieldSizesMatchCounts() {
        assertEquals(SceneSkyMath.STAR_COUNT * 4, stars.size)
        assertEquals(SceneSkyMath.MOON_MARK_COUNT * 4, marks.size)
    }

    /** 同种子必须给同一幅画面：两侧各生成一次结果必须逐值相等。 */
    @Test
    fun fieldsAreDeterministic() {
        val again = SceneSkyMath.fields()
        assertTrue(stars.contentEquals(again.first))
        assertTrue(marks.contentEquals(again.second))
    }

    /**
     * 月海坑必须接着星点的 Random 序列抽。
     *
     * 阳性对照：若各起一个 Random(11)，月海坑首值会变成星点首值那条序列 ⇒ 与真值不同。
     */
    @Test
    fun moonMarksContinueStarSequenceNotFreshSeed() {
        val fresh = Random(SceneSkyMath.SEED)
        val angle = fresh.nextFloat() * 6.2831855f
        val dist = 0.10f + fresh.nextFloat() * 0.52f
        val wrong = (kotlin.math.cos(angle) * dist).coerceIn(-0.82f, 0.82f)
        assertTrue("同种子重启序列必须与真值不同，否则本测无鉴别力", kotlin.math.abs(wrong - marks[0]) > 1e-4f)
    }

    @Test
    fun starChannelsStayInRange() {
        var i = 0
        while (i < stars.size) {
            assertTrue(stars[i] in 0f..1f)
            assertTrue(stars[i + 1] in 0f..0.60f)
            assertTrue(stars[i + 2] in 0.6f..1.95f)
            assertTrue(stars[i + 3] in 0.18f..1.0f)
            i += 4
        }
    }

    @Test
    fun moonMarkChannelsStayInRange() {
        var i = 0
        while (i < marks.size) {
            assertTrue(marks[i] in -0.82f..0.82f)
            assertTrue(marks[i + 1] in -0.82f..0.82f)
            assertTrue(marks[i + 2] in 0.018f..0.08f)
            assertTrue(marks[i + 3] in 0.06f..0.16f)
            i += 4
        }
    }

    @Test
    fun starFadeOnlyBelowStart() {
        assertEquals(1f, SceneSkyMath.starFade(0f), 1e-6f)
        assertEquals(1f, SceneSkyMath.starFade(SceneSkyMath.STAR_FADE_START), 1e-6f)
        val end = SceneSkyMath.STAR_FADE_START + SceneSkyMath.STAR_FADE_SPAN
        assertEquals(1f - SceneSkyMath.STAR_FADE_DEPTH, SceneSkyMath.starFade(end), 1e-5f)
    }

    @Test
    fun lightFadesStarsAndMilkyWay() {
        assertEquals(1f, SceneSkyMath.starAlphaBase(0f), 1e-6f)
        assertEquals(1f - SceneSkyMath.STAR_LIGHT_K, SceneSkyMath.starAlphaBase(1f), 1e-6f)
        assertEquals(SceneSkyMath.STAR_LIGHT_FLOOR, SceneSkyMath.starAlphaBase(2f), 1e-6f)
        assertEquals(
            (1f - SceneSkyMath.MILKYWAY_LIGHT_K) * SceneSkyMath.MILKYWAY_ALPHA,
            SceneSkyMath.milkyWayAlpha(1f), 1e-6f,
        )
        assertEquals(
            SceneSkyMath.MILKYWAY_LIGHT_FLOOR * SceneSkyMath.MILKYWAY_ALPHA,
            SceneSkyMath.milkyWayAlpha(2f), 1e-6f,
        )
        assertTrue(SceneSkyMath.milkyWayAlpha(0f) > SceneSkyMath.milkyWayAlpha(1f))
    }

    /** 相机右移 ⇒ 星点左移（视差反向）。 */
    @Test
    fun starParallaxIsOppositeCamera() {
        val a = ArrayList<DotSpec>()
        val b = ArrayList<DotSpec>()
        SceneSkyMath.stars(stars, 1000f, 2000f, 0f, 0.5f, 0.5f, 1f, 3f, a)
        SceneSkyMath.stars(stars, 1000f, 2000f, 0f, 0.7f, 0.5f, 1f, 3f, b)
        assertEquals(a.size, b.size)
        assertTrue("相机右移星点必须左移", b[0].cx < a[0].cx)
        assertEquals(
            -(0.2f * 1000f * SceneSkyMath.STAR_PARALLAX_X),
            b[0].cx - a[0].cx, 1e-2f,
        )
    }

    /** 相机缩放放大 ⇒ 星点离画面中心更远。 */
    @Test
    fun starZoomPushesAwayFromCenter() {
        val a = ArrayList<DotSpec>()
        val b = ArrayList<DotSpec>()
        SceneSkyMath.stars(stars, 1000f, 2000f, 0f, 0.5f, 0.5f, 1f, 3f, a)
        SceneSkyMath.stars(stars, 1000f, 2000f, 0f, 0.5f, 0.5f, 1.2f, 3f, b)
        val first = a[0]
        val zoomed = b[0]
        assertTrue(
            kotlin.math.abs(zoomed.cx - 500f) > kotlin.math.abs(first.cx - 500f) ||
                kotlin.math.abs(first.cx - 500f) < 1e-3f,
        )
    }

    /** 全亮场景下星点仍产出（有下限），且亮星带光晕。 */
    @Test
    fun brightStarsEmitHalo() {
        val out = ArrayList<DotSpec>()
        SceneSkyMath.stars(stars, 1000f, 2000f, 0f, 0.5f, 0.5f, 1f, 3f, out)
        var bright = 0
        var i = 0
        while (i < stars.size) {
            if (stars[i + 3] > SceneSkyMath.STAR_HALO_BRIGHTNESS &&
                (SceneSkyMath.starAlphaBase(0f) * stars[i + 3] * SceneSkyMath.starFade(stars[i + 1]) * 255f).toInt() >
                SceneSkyMath.STAR_MIN_ALPHA
            ) bright++
            i += 4
        }
        assertTrue("必须有亮星，否则本测无鉴别力", bright > 0)
        assertEquals("每颗亮星多一个光晕点", stars.size / 4 + bright, out.size)
    }

    @Test
    fun starRadiusScalesWithDensity() {
        val a = ArrayList<DotSpec>()
        val b = ArrayList<DotSpec>()
        SceneSkyMath.stars(stars, 1000f, 2000f, 0f, 0.5f, 0.5f, 1f, 2f, a)
        SceneSkyMath.stars(stars, 1000f, 2000f, 0f, 0.5f, 0.5f, 1f, 4f, b)
        assertEquals(a[0].radius * 2f, b[0].radius, 1e-4f)
    }

    /** 前层云比后层视差更大、强度更小。 */
    @Test
    fun cloudLayersDifferInParallaxAndStrength() {
        val back = ArrayList<CloudSpec>()
        val front = ArrayList<CloudSpec>()
        SceneSkyMath.clouds(SceneSkyMath.cloudBlobs(), 1000f, 2000f, 1f, 0, 0.7f, 0.5f, back)
        SceneSkyMath.clouds(SceneSkyMath.cloudBlobs(), 1000f, 2000f, 1f, 1, 0.7f, 0.5f, front)
        assertEquals(back.size, front.size)
        assertTrue("前层横向视差必须更大", kotlin.math.abs(front[0].cx - 60f) > kotlin.math.abs(back[0].cx - 60f))
        val backAlpha = (back[0].stops[0].argb ushr 24) and 0xFF
        val frontAlpha = (front[0].stops[0].argb ushr 24) and 0xFF
        assertTrue("前层必须更淡", frontAlpha < backAlpha)
    }

    @Test
    fun cloudAspectIsFixed() {
        val out = ArrayList<CloudSpec>()
        SceneSkyMath.clouds(SceneSkyMath.cloudBlobs(), 1000f, 2000f, 0.85f, 0, 0.5f, 0.5f, out)
        assertTrue(out.isNotEmpty())
        for (c in out) assertEquals(SceneSkyMath.CLOUD_ASPECT, c.yScale, 1e-6f)
    }

    @Test
    fun zeroStrengthCloudsProduceNothing() {
        val out = ArrayList<CloudSpec>()
        SceneSkyMath.clouds(SceneSkyMath.cloudBlobs(), 1000f, 2000f, 0f, 0, 0.5f, 0.5f, out)
        assertTrue(out.isEmpty())
    }

    /** 山脊段数 = 控制点数/2 − 1，且两控制点同 x（中点）。 */
    @Test
    fun ridgeCubicsUseMidpointHandles() {
        val pts = SceneSkyMath.backRidge()
        val p = SceneSkyMath.ridgePath(pts, 1000f, 2000f, 0f)
        assertEquals(pts.size / 2 - 1, p.cubics.size)
        for (i in p.cubics.indices) {
            assertEquals(p.cubics[i].c1x, p.cubics[i].c2x, 1e-4f)
            val prevX = 1000f * pts[i * 2]
            val nextX = 1000f * pts[i * 2 + 2]
            assertEquals((prevX + nextX) / 2f, p.cubics[i].c1x, 1e-3f)
        }
    }

    @Test
    fun ridgeOverscansBothSides() {
        val p = SceneSkyMath.ridgePath(SceneSkyMath.frontRidge(), 1000f, 2000f, 0f)
        assertTrue(p.startX < 0f)
        assertTrue(p.endX > 1000f)
        assertEquals(p.startY, p.endY, 1e-4f)
        assertTrue(p.startY > 2000f)
    }

    @Test
    fun ridgeDriftFollowsCamera() {
        assertEquals(0f, SceneSkyMath.ridgeDrift(2000f, SceneSkyMath.RIDGE_DRIFT_BASE), 1e-4f)
        assertTrue("相机上移山脊下移", SceneSkyMath.ridgeDrift(2000f, 0.3f) > 0f)
    }

    @Test
    fun moonRadiusScalesWithElement() {
        assertEquals(1000f * SceneSkyMath.MOON_RADIUS, SceneSkyMath.moonRadius(1000f, 1f), 1e-3f)
        assertEquals(
            SceneSkyMath.moonRadius(1000f, 1f) * 0.5f,
            SceneSkyMath.moonRadius(1000f, 0.5f), 1e-3f,
        )
    }

    @Test
    fun moonCraterCountMatchesMarks() {
        val out = ArrayList<RadialSpec>()
        SceneSkyMath.moonCraters(marks, 500f, 800f, 200f, out)
        assertEquals(SceneSkyMath.MOON_MARK_COUNT, out.size)
        assertEquals(marks[2] * 200f * SceneSkyMath.MOON_MARK_RADIUS, out[0].radius, 1e-3f)
        assertEquals(500f + marks[0] * 200f, out[0].cx, 1e-3f)
    }

    @Test
    fun moonBodyHighlightIsUpperLeft() {
        val b = SceneSkyMath.moonBody(500f, 800f, 200f)
        assertTrue(b.cx < 500f)
        assertTrue(b.cy < 800f)
        assertEquals(6, b.stops.size)
        assertEquals(0xFFFFFFFF.toInt(), b.stops[0].argb)
    }

    @Test
    fun moonNightSideIsLowerRight() {
        val n = SceneSkyMath.moonNightSide(500f, 800f, 200f)
        assertTrue(n.cx > 500f)
        assertTrue(n.cy > 800f)
    }

    @Test
    fun skyGradientHasFourStopsInOrder() {
        val g = SceneSkyMath.skyGradient(1000f, 2000f, 0.5f)
        assertEquals(4, g.stops.size)
        assertEquals(0f, g.stops[0].position, 1e-6f)
        assertEquals(1f, g.stops[3].position, 1e-6f)
        for (i in 1 until g.stops.size) {
            assertTrue(g.stops[i].position > g.stops[i - 1].position)
        }
        assertEquals(2000f, g.y1, 1e-4f)
    }

    /** u=0 / 0.5 / 1 三端必须精确落在三个基色上。 */
    @Test
    fun mix3HitsAllThreeAnchors() {
        val a = 0xFF102030.toInt()
        val b = 0xFF405060.toInt()
        val c = 0xFF708090.toInt()
        assertEquals(a, SceneSkyMath.mix3(a, b, c, 0f))
        assertEquals(b, SceneSkyMath.mix3(a, b, c, 0.5f))
        assertEquals(c, SceneSkyMath.mix3(a, b, c, 1f))
    }

    @Test
    fun lerpColorClampsOutOfRange() {
        val a = 0xFF000000.toInt()
        val b = 0xFFFFFFFF.toInt()
        assertEquals(a, SceneSkyMath.lerpColor(a, b, -3f))
        assertEquals(b, SceneSkyMath.lerpColor(a, b, 5f))
        assertEquals(0xFF7F7F7F.toInt(), SceneSkyMath.lerpColor(a, b, 0.5f))
    }

    @Test
    fun withAlphaKeepsRgb() {
        assertEquals(0x7FA7C2EE, SceneSkyMath.withAlpha(0xFFA7C2EE.toInt(), 0.5f))
        assertEquals(0x00A7C2EE, SceneSkyMath.withAlpha(0xFFA7C2EE.toInt(), 0f))
    }

    /** 输出列表必须复用前先清空，否则逐帧累积。 */
    @Test
    fun outputListsAreClearedEachCall() {
        val out = ArrayList<DotSpec>()
        SceneSkyMath.stars(stars, 1000f, 2000f, 0f, 0.5f, 0.5f, 1f, 3f, out)
        val n = out.size
        SceneSkyMath.stars(stars, 1000f, 2000f, 0f, 0.5f, 0.5f, 1f, 3f, out)
        assertEquals(n, out.size)
        val craters = ArrayList<RadialSpec>()
        SceneSkyMath.moonCraters(marks, 500f, 800f, 200f, craters)
        SceneSkyMath.moonCraters(marks, 500f, 800f, 200f, craters)
        assertEquals(SceneSkyMath.MOON_MARK_COUNT, craters.size)
    }
}
