package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ContentWindow
import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.home.HomeGeometry
import com.coloros16.liquidglass.engine.home.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退场行程按窗口形状分档的定量契约。
 *
 * 判据统一为「可见期结束时元素是否已出窗」：可见度落到 10% 那一刻，
 * 元素矩形必须已整体移出窗口（或移出屏幕），否则就是原地淡没。
 */
class ExitTravelTierContractTest {

    private val w = 734.5f
    private val h = 1591.5f
    private val dp = 2.625f

    private val glyphX = 24 * dp + 9 * dp
    private val weatherY = h * 0.375f
    private val glyphR = 9 * dp
    private val cardLeft = 16 * dp
    private val cardTop = h * 0.415f
    private val cardH = 64 * dp

    /** 元素矩形（左、上、右、下）与错峰序号。 */
    private class El(val name: String, val index: Int, val box: FloatArray)

    private val weather = El(
        "weather", 0,
        floatArrayOf(glyphX - glyphR, weatherY - dp - glyphR, w - 24 * dp, weatherY + 5 * dp),
    )
    private val card = El(
        "card", 1,
        floatArrayOf(cardLeft, cardTop, w - cardLeft, cardTop + cardH),
    )

    private val layers = SystemSceneMotion.Layers()
    private val lensPose = SailLensMotion.Pose()
    private val win = ContentWindow.Window()
    private val cp = SystemSceneMotion.ContentPose()
    private val pv = PhotoStyleMotion.Values()

    private fun windowAt(edge: TransitionEdge, style: PhotoStyle?, p: Float) {
        if (style != null) {
            PhotoStyleMotion.sample(style, edge, p, h / w, 0f, 0f, pv)
            ContentWindow.photo(pv.v, w, h, win)
        } else {
            SailLensMotion.sample(edge, p, 0.5f, 0.435f, 1.24f, lensPose)
            ContentWindow.lens(lensPose, w, h, win)
        }
    }

    /** 矩形与窗口是否还有交集：按形状解析求最近点，不采样免漏判。 */
    private fun overlaps(box: FloatArray, dy: Float): Boolean {
        val x0 = box[0]
        val x1 = box[2]
        val y0 = maxOf(box[1] + dy, 0f)
        val y1 = minOf(box[3] + dy, h)
        if (y1 < y0) return false
        when (win.shape) {
            ContentWindow.Shape.NONE -> return true
            ContentWindow.Shape.CIRCLE -> {
                val dx = win.centerX.coerceIn(x0, x1) - win.centerX
                val dyy = win.centerY.coerceIn(y0, y1) - win.centerY
                return dx * dx + dyy * dyy <= win.radius * win.radius
            }

            ContentWindow.Shape.SQUARE -> {
                val rad = Math.toRadians(win.angleDeg.toDouble())
                val c = kotlin.math.cos(rad).toFloat()
                val s = kotlin.math.sin(rad).toFloat()
                var u0 = Float.MAX_VALUE
                var u1 = -Float.MAX_VALUE
                var v0 = Float.MAX_VALUE
                var v1 = -Float.MAX_VALUE
                for (px in floatArrayOf(x0, x1)) {
                    for (py in floatArrayOf(y0, y1)) {
                        val dx = px - win.centerX
                        val dyy = py - win.centerY
                        val u = c * dx + s * dyy
                        val v = -s * dx + c * dyy
                        if (u < u0) u0 = u
                        if (u > u1) u1 = u
                        if (v < v0) v0 = v
                        if (v > v1) v1 = v
                    }
                }
                val half = win.side * 0.5f
                return u0 <= half && u1 >= -half && v0 <= half && v1 >= -half
            }
        }
    }

    private fun offScreen(box: FloatArray, dy: Float): Boolean = box[3] + dy < 0f || box[1] + dy > h

    /** 可见期结束（可见度 < floor）那一刻：返回位移像素与是否已出窗。 */
    private fun atVisibleEnd(
        edge: TransitionEdge, style: PhotoStyle?, el: El, travel: Float, floor: Float,
    ): Pair<Float, Boolean> {
        for (k in 0..2000) {
            val p = k / 2000f
            SystemSceneMotion.sample(edge, p, layers)
            if (layers.lockAlpha <= 0.002f) break
            windowAt(edge, style, p)
            SystemSceneMotion.contentStagger(1f - layers.lockAlpha, el.index, false, travel, cp)
            if (1f - cp.progress >= floor) continue
            val dy = cp.shift * h
            return (-dy) to (offScreen(el.box, dy) || !overlaps(el.box, dy))
        }
        return 0f to false
    }

    /** 反解最小行程：可见期结束时元素已出窗的最小 travel（步长 0.005）。 */
    private fun neededTravel(edge: TransitionEdge, style: PhotoStyle?, el: El, floor: Float): Float {
        var tv = 0.05f
        while (tv <= 1.20f) {
            if (atVisibleEnd(edge, style, el, tv, floor).second) return tv
            tv += 0.005f
        }
        return -1f
    }

    private val circle = null as PhotoStyle?
    private fun square(id: Int): PhotoStyle = PhotoStyleMotion.byId(id)!!

    /** ★圆窗天气行最小行程 0.300：现行值恰好达标，不动。 */
    @Test
    fun circleWeatherMinimumIsQuantified() {
        val edge = TransitionEdge.LOCK_TO_AOD
        assertEquals("圆窗天气行最小行程", 0.300f, neededTravel(edge, circle, weather, 0.10f), 1e-3f)
        assertTrue("现行 0.30 在 10% 判据下已出窗", atVisibleEnd(edge, circle, weather, 0.30f, 0.10f).second)
        assertTrue("再小一档 0.295 就不够", !atVisibleEnd(edge, circle, weather, 0.295f, 0.10f).second)
    }

    /** ★方窗天气行最小行程 0.430：现行 0.30 完全不够，必须分档。 */
    @Test
    fun squareWeatherNeedsBiggerTravel() {
        val edge = TransitionEdge.LOCK_TO_AOD
        for (id in listOf(2, 3, 4, 5)) {
            assertEquals(
                "photo$id 方窗天气行最小行程",
                0.430f, neededTravel(edge, square(id), weather, 0.10f), 1e-3f,
            )
        }
        assertEquals("photo1 收拢快只需 0.310", 0.310f, neededTravel(edge, square(1), weather, 0.10f), 1e-3f)
    }

    /** ★阳性对照：方窗用圆窗行程 0.30 时，天气行可见期结束仍在窗内 = 原地淡没。 */
    @Test
    fun positiveControlCircleTravelFadesInPlaceOnSquare() {
        val edge = TransitionEdge.LOCK_TO_AOD
        val bad = atVisibleEnd(edge, square(2), weather, 0.30f, 0.10f)
        assertEquals("0.30 下已走 430px", 430f, bad.first, 1.0f)
        assertTrue("却仍未出窗 ⇒ 原地淡没", !bad.second)
        val good = atVisibleEnd(edge, square(2), weather, 0.44f, 0.10f)
        assertTrue("0.44 已出窗", good.second)
        assertTrue("位移比 0.30 明显更大", good.first > bad.first * 1.4f)
    }

    /** ★分档值必须覆盖五款方窗与圆窗的天气行。 */
    @Test
    fun squareTierCoversEveryStyle() {
        val edge = TransitionEdge.LOCK_TO_AOD
        for (id in listOf(1, 2, 3, 4, 5)) {
            assertTrue(
                "photo$id 用 0.44 必须已出窗",
                atVisibleEnd(edge, square(id), weather, 0.44f, 0.10f).second,
            )
        }
        assertTrue("圆窗用 0.30 已出窗", atVisibleEnd(edge, circle, weather, 0.30f, 0.10f).second)
    }

    /** 方窗行程不得随手放大：0.44 覆盖 0.430，余量仅一档。 */
    @Test
    fun squareTierIsTightNotOversized() {
        val need = neededTravel(TransitionEdge.LOCK_TO_AOD, square(2), weather, 0.10f)
        assertTrue("0.44 覆盖最小需求", 0.44f >= need)
        assertTrue("余量不足 0.02 ⇒ 不是随手放大的值", 0.44f - need < 0.02f)
    }

    /**
     * ★通知卡最小行程按形状分两档：方窗 0.580、圆窗 0.490。
     *
     * 卡片底沿比天气行低 0.137h，天气档不够；方窗在卡片可见期内几乎不裁切，只能靠自身出屏。
     */
    @Test
    fun cardMinimumIsQuantifiedPerShape() {
        val edge = TransitionEdge.LOCK_TO_AOD
        for (id in listOf(2, 3, 4, 5)) {
            assertEquals(
                "photo$id 方窗通知卡最小行程",
                0.580f, neededTravel(edge, square(id), card, 0.10f), 1e-3f,
            )
        }
        assertEquals("photo1 方窗收拢快只需 0.485", 0.485f, neededTravel(edge, square(1), card, 0.10f), 1e-3f)
        assertEquals("圆窗通知卡最小行程", 0.490f, neededTravel(edge, circle, card, 0.10f), 1e-3f)
        assertTrue(
            "卡片需求必须大于同形状天气行",
            neededTravel(edge, square(2), card, 0.10f) > neededTravel(edge, square(2), weather, 0.10f),
        )
    }

    /** ★两档必须覆盖全部款式：方窗五款用 0.58、圆窗用 0.49。 */
    @Test
    fun cardTierCoversEveryStyle() {
        val edge = TransitionEdge.LOCK_TO_AOD
        for (id in listOf(1, 2, 3, 4, 5)) {
            assertTrue(
                "photo$id 通知卡用 0.58 必须已出窗",
                atVisibleEnd(edge, square(id), card, 0.58f, 0.10f).second,
            )
        }
        assertTrue("圆窗通知卡用 0.49 已出窗", atVisibleEnd(edge, circle, card, 0.49f, 0.10f).second)
    }

    /** ★两档都不得随手放大：各自余量不足 0.005（步长即一档）。 */
    @Test
    fun cardTiersAreTightNotOversized() {
        val edge = TransitionEdge.LOCK_TO_AOD
        val sqNeed = neededTravel(edge, square(2), card, 0.10f)
        val ciNeed = neededTravel(edge, circle, card, 0.10f)
        assertTrue("方窗档覆盖需求", 0.58f >= sqNeed)
        assertTrue("方窗档余量不足一档（实测 ${0.58f - sqNeed}）", 0.58f - sqNeed < 0.005f)
        assertTrue("圆窗档覆盖需求", 0.49f >= ciNeed)
        assertTrue("圆窗档余量不足一档（实测 ${0.49f - ciNeed}）", 0.49f - ciNeed < 0.005f)
    }

    /** ★边界：再小一档就不够，证明两档都卡在临界不是宽松取值。 */
    @Test
    fun cardTierBoundariesAreExact() {
        val edge = TransitionEdge.LOCK_TO_AOD
        assertTrue("方窗 0.575 不够", !atVisibleEnd(edge, square(2), card, 0.575f, 0.10f).second)
        assertTrue("方窗 0.580 够", atVisibleEnd(edge, square(2), card, 0.580f, 0.10f).second)
        assertTrue("圆窗 0.485 不够", !atVisibleEnd(edge, circle, card, 0.485f, 0.10f).second)
        assertTrue("圆窗 0.490 够", atVisibleEnd(edge, circle, card, 0.490f, 0.10f).second)
    }

    /** ★阳性对照：通知卡沿用天气档必原地淡没（这正是修前的缺陷）。 */
    @Test
    fun positiveControlCardWithWeatherTierFadesInPlace() {
        val edge = TransitionEdge.LOCK_TO_AOD
        val sqBad = atVisibleEnd(edge, square(2), card, 0.44f, 0.10f)
        assertTrue("方窗卡片用 0.44 未出窗", !sqBad.second)
        assertEquals("那时已走 630.4px 却仍在窗内", 630.4f, sqBad.first, 1.0f)
        val ciBad = atVisibleEnd(edge, circle, card, 0.30f, 0.10f)
        assertTrue("圆窗卡片用 0.30 未出窗", !ciBad.second)
        assertEquals("那时已走 429.8px", 429.8f, ciBad.first, 1.0f)
        val sqGood = atVisibleEnd(edge, square(2), card, 0.58f, 0.10f)
        assertTrue("换成 0.58 已出窗", sqGood.second)
        assertTrue("位移显著更大", sqGood.first > sqBad.first * 1.3f)
    }

    /** ★天气行档位不受通知卡分档影响（两元素各自独立）。 */
    @Test
    fun weatherTierUnchangedByCardTier() {
        val edge = TransitionEdge.LOCK_TO_AOD
        assertTrue("圆窗天气仍 0.30 达标", atVisibleEnd(edge, circle, weather, 0.30f, 0.10f).second)
        assertTrue("方窗天气仍 0.44 达标", atVisibleEnd(edge, square(2), weather, 0.44f, 0.10f).second)
        assertTrue("圆窗天气用卡片档也达标但更费", atVisibleEnd(edge, circle, weather, 0.49f, 0.10f).second)
        assertTrue(
            "天气行不该被迫用卡片档",
            neededTravel(edge, circle, weather, 0.10f) < neededTravel(edge, circle, card, 0.10f),
        )
    }

    /**
     * ★方窗在通知卡可见期内几乎不裁切 ⇒ 卡片只能靠自身走出屏顶。
     *
     * 这解释了为什么卡片档（0.58）比天气档（0.44）大得多，也解释了它为何与款式无关。
     */
    @Test
    fun squareWindowBarelyClipsCardDuringItsVisibleSpan() {
        val edge = TransitionEdge.LOCK_TO_AOD
        var clippedFrames = 0
        var offScreenFrames = 0
        for (k in 0..2000) {
            val p = k / 2000f
            SystemSceneMotion.sample(edge, p, layers)
            if (layers.lockAlpha <= 0.002f) break
            windowAt(edge, square(2), p)
            SystemSceneMotion.contentStagger(1f - layers.lockAlpha, 1, false, 0.58f, cp)
            if (1f - cp.progress <= 0.002f) continue
            val dy = cp.shift * h
            if (offScreen(card.box, dy)) offScreenFrames++ else if (!overlaps(card.box, dy)) clippedFrames++
        }
        assertEquals("方窗对卡片零裁切帧", 0, clippedFrames)
        assertTrue("全靠出屏（实测 $offScreenFrames 帧）", offScreenFrames > 100)
    }

    /** ★卡片出屏下界由几何决定：底沿/h ÷ 可见期结束的 e，与窗口形状无关。 */
    @Test
    fun cardOffScreenBoundIsGeometric() {
        val edge = TransitionEdge.LOCK_TO_AOD
        var eAtEnd = 1f
        for (k in 0..2000) {
            val p = k / 2000f
            SystemSceneMotion.sample(edge, p, layers)
            if (layers.lockAlpha <= 0.002f) break
            SystemSceneMotion.contentStagger(1f - layers.lockAlpha, 1, false, 1f, cp)
            if (1f - cp.progress >= 0.10f) continue
            eAtEnd = cp.progress
            break
        }
        val bound = card.box[3] / h / eAtEnd
        assertEquals("解析下界 0.5783", 0.5783f, bound, 1e-3f)
        assertTrue("0.58 刚好越过下界", 0.58f > bound)
        assertTrue("0.575 在下界之下", 0.575f < bound)
        assertTrue(
            "天气行下界更小（底沿更高）",
            weather.box[3] / h / eAtEnd < bound,
        )
    }

    /** ★桌面层不得照抄通知卡档：0.58 会把 Dock 甩出屏顶。 */
    @Test
    fun homeLayerMustNotAdoptCardTravel() {
        val g = HomeGeometry.solve(w, h, dp, 14f * dp, 1)
        val dockBottom = g.hotseatIconTopPx() + g.hotseatIconSizePx
        assertTrue("Dock 底沿在屏内", dockBottom < h)
        assertTrue("0.58 位移 923px 是桌面现行 159px 的 5.8 倍", 0.58f * h > 5f * (0.10f * h))
        assertTrue("照抄会把 Dock 推到屏外", dockBottom - 0.58f * h < h * 0.4f)
    }

    /**
     * ★解锁边（LOCK_TO_HOME）窗口恒覆盖全屏 ⇒ 两元素都靠自身淡出。
     *
     * 这条边本轮不改：窗口不裁，加大行程只是把内容甩出屏幕顶端，观感是「飞走」不是「让位」。
     */
    @Test
    fun unlockEdgeWindowNeverClipsSoTravelIsCosmetic() {
        val edge = TransitionEdge.LOCK_TO_HOME
        for (k in 0..20) {
            windowAt(edge, circle, k / 20f)
            assertTrue(
                "p=${k / 20f} 圆窗必须盖满全屏",
                ContentWindow.coversScreen(win.radius, win.centerX, win.centerY, w, h),
            )
        }
        assertEquals("天气行最小行程", 0.425f, neededTravel(edge, circle, weather, 0.10f), 1e-3f)
        assertTrue("通知卡需要更大行程", neededTravel(edge, circle, card, 0.10f) > 0.55f)
        assertTrue("0.30 下天气行未出屏", !atVisibleEnd(edge, circle, weather, 0.30f, 0.10f).second)
    }

    /** ★圆窗在息屏边 p=0.096 起就不再覆盖全屏；方窗上沿越过天气行要晚得多。 */
    @Test
    fun windowCoverageDiffersByShape() {
        var firstUncovered = -1f
        for (k in 0..1000) {
            val p = k / 1000f
            windowAt(TransitionEdge.LOCK_TO_AOD, circle, p)
            if (!ContentWindow.coversScreen(win.radius, win.centerX, win.centerY, w, h)) {
                firstUncovered = p
                break
            }
        }
        assertEquals("圆窗 p=0.096 起露边", 0.096f, firstUncovered, 1e-3f)
        val weatherTop = weather.box[1]
        val leftX = weather.box[0]
        val cross = HashMap<Int, Float>()
        for (id in listOf(1, 2, 5)) {
            var crossAt = 2f
            for (k in 0..1000) {
                val p = k / 1000f
                windowAt(TransitionEdge.LOCK_TO_AOD, square(id), p)
                if (!ContentWindow.contains(win, leftX, weatherTop, w, h)) { crossAt = p; break }
            }
            cross[id] = crossAt
        }
        assertEquals("photo1 收拢最快，p=0.661 才吃到左上角", 0.661f, cross[1]!!, 1e-3f)
        assertTrue("photo2 收到息屏末仍没吃到左上角", cross[2]!! > 1f)
        assertTrue("photo5 同样没吃到", cross[5]!! > 1f)
    }

    /** ★桌面层不能照抄锁屏大行程：Dock 行程占比是锁屏的四分之一量级。 */
    @Test
    fun homeLayerMustNotAdoptSquareTravel() {
        val g = HomeGeometry.solve(w, h, dp, 14f * dp, 1)
        val dockBottom = g.hotseatIconTopPx() + g.hotseatIconSizePx
        assertTrue("Dock 底沿在屏内", dockBottom < h)
        assertTrue("0.44 行程位移 700px 远超桌面现行 159px", 0.44f * h > 4f * (0.10f * h))
        assertEquals("桌面现行行程 159px", 159.15f, 0.10f * h, 0.1f)
        assertEquals("桌面元素总数 29", 29, HomeLayout.COLUMNS * HomeLayout.ROWS + HomeLayout.HOTSEAT_ICONS + 1)
    }

    /** ★分档必须按窗口形状取，不得按样式 id 或硬编码分支。 */
    @Test
    fun tierIsKeyedByWindowShapeNotStyleId() {
        val preview =
            java.io.File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()
        val start = preview.indexOf("private fun lockExitTravel")
        assertTrue("必须有分档入口", start >= 0)
        val body = preview.substring(start, preview.indexOf("\n    }", start))
        assertTrue("按形状判", body.contains("ContentWindow.Shape.SQUARE"))
        assertTrue("不得按样式 id 判", !body.contains("style.id"))
        assertTrue("不得按照片款判", !body.contains("photo"))
        assertTrue("元素维度只认槽位常量不认绘制细节", body.contains("LOCK_CARD_INDEX"))
        assertTrue("不得按元素像素几何硬判", !body.contains("cardTop") && !body.contains("0.415"))
    }

    /**
     * ★解锁边必须走小行程：该边窗口恒覆盖不裁切，大行程只把内容甩出屏顶。
     *
     * E2 实测暴露过 `LOCK_TO_HOME/SQUARE/0.44`，按形状分派时漏了「该边是否真会裁」这一维。
     */
    @Test
    fun unlockEdgeMustUseSmallTravel() {
        val preview =
            java.io.File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()
        val start = preview.indexOf("private fun lockExitTravel")
        val body = preview.substring(start, preview.indexOf("\n    }", start))
        assertTrue(
            "解锁边必须短路到小行程",
            body.contains("if (effEdge == TransitionEdge.LOCK_TO_HOME) return LOCK_STAGGER_TRAVEL"),
        )
        val unlockAt = body.indexOf("TransitionEdge.LOCK_TO_HOME")
        val squareAt = body.indexOf("ContentWindow.Shape.SQUARE")
        assertTrue("解锁边短路必须在形状分派之前", unlockAt in 0 until squareAt)
        val cardAt = body.indexOf("LOCK_CARD_INDEX")
        assertTrue("解锁边短路也必须在元素分档之前", unlockAt in 0 until cardAt)
        assertTrue("边取自 scrub 或播放同一口径", body.contains("if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge"))
    }

    /** 阳性对照：解锁边用 0.44 会把天气行甩出屏顶，用 0.06 只走 95px。 */
    @Test
    fun positiveControlBigTravelFlingsWeatherOffScreenOnUnlock() {
        val bottom = weather.box[3]
        val big = bottom - 0.44f * h
        val small = bottom - 0.06f * h
        assertTrue("0.44 把天气行底沿甩到屏外（实测 $big）", big < 0f)
        assertTrue("0.06 仍在屏内（实测 $small）", small > 0f)
        assertEquals("0.44 位移 700px", 700.26f, 0.44f * h, 0.1f)
        assertEquals("0.06 位移 95px", 95.49f, 0.06f * h, 0.1f)
    }

    /**
     * ★解锁边方窗也恒覆盖全屏：半边 863.04 > 屏半高 795.75 且 > 屏半宽 367.25。
     *
     * 与圆窗同理 ⇒ 该边两种形状都不裁切，都该走小行程。
     */
    @Test
    fun unlockEdgeSquareAlsoCoversScreen() {
        val half = minOf(w, h) * PhotoStyle.EXPANDED_SCALE * 0.5f
        assertEquals("方窗半边 863.04", 863.04f, half, 0.05f)
        assertTrue("半边大于屏半高", half > h / 2f)
        assertTrue("半边大于屏半宽", half > w / 2f)
        for (id in listOf(1, 2, 3, 4, 5)) {
            for (k in 0..20) {
                windowAt(TransitionEdge.LOCK_TO_HOME, square(id), k / 20f)
                assertEquals("photo$id 该边方窗边长恒 EXPANDED", 2f * half, win.side, 0.5f)
                assertEquals("中心恒屏心", w / 2f, win.centerX, 0.5f)
                assertEquals("中心恒屏心", h / 2f, win.centerY, 0.5f)
            }
        }
    }

    /** 诊断必须带形状与行程，否则线上分不清走的哪一档。 */
    @Test
    fun diagnosticCarriesShapeAndTravel() {
        val preview =
            java.io.File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()
        assertTrue("必须打形状", preview.contains("shape=\${contentWindow.shape}"))
        assertTrue("必须打天气行行程", preview.contains("travel=\${\"%.2f\".format(lockExitTravel("))
        assertTrue(
            "必须打通知卡行程（两档不同，只打一个分不出）",
            preview.contains("cardTravel=\${\"%.2f\".format(lockExitTravel("),
        )
        assertTrue("两个行程分别取两个槽位", preview.contains("LOCK_WEATHER_INDEX))") && preview.contains("LOCK_CARD_INDEX))"))
    }
}
