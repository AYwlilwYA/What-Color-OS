package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.CubicBezierEasing
import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.HomeMotion
import com.coloros16.liquidglass.engine.effect.IconEntranceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 揭开层里桌面图标的入场相位契约：揭开窗口与图标错峰窗口重叠，快照带相位会把图标二次淡入。
 *
 * 照片类 photoAlpha<1 的时段就是揭开期，桌面内容混向目标态整屏快照；
 * 若快照自己也按入场相位画图标，早段图标透明度被乘两次，图标要等更晚才出现。
 */
class RevealIconPhaseContractTest {

    private val edge = TransitionEdge.LOCK_TO_HOME
    private val aspect = 2400f / 1080f

    private fun photoAlpha(styleId: Int, localT: Float): Float {
        val style = PhotoStyleMotion.byId(styleId) ?: error("取不到样式 $styleId")
        val out = PhotoStyleMotion.Values()
        PhotoStyleMotion.sample(style, edge, localT, aspect, 0f, 0f, out)
        return out.v[PhotoStyle.SLOT_PHOTO_ALPHA]
    }

    /** 揭开期确实存在：早段 photoAlpha<1、末段 =0。 */
    @Test
    fun revealWindowExistsOnUnlockEdge() {
        for (id in 1..5) {
            assertTrue("样式 $id 早段必须在揭开", photoAlpha(id, 0.2f) < 0.999f)
            assertEquals("样式 $id 末段必须完全揭开", 0f, photoAlpha(id, 1f), 1e-4f)
        }
    }

    /** ★核心事实：揭开早段仍有图标处于「几乎不可见」的入场相位。 */
    @Test
    fun earlyRevealOverlapsInvisibleIconPhase() {
        val style = PhotoStyleMotion.byId(1) ?: error("取不到留光")
        val total = PhotoStyleMotion.durationMs(style, edge)
        val out = PhotoStyleMotion.Values()
        var overlapping = 0
        for (step in 1..40) {
            val lt = step / 40f
            PhotoStyleMotion.sample(style, edge, lt, aspect, 0f, 0f, out)
            val alpha = out.v[PhotoStyle.SLOT_PHOTO_ALPHA]
            if (alpha >= 0.999f || alpha <= 0.001f) continue
            val elapsed = (lt * total).toLong()
            val pose = IconEntranceState()
            HomeMotion.iconEntrance(
                EffectCatalog.HOME_ICON_FOREGROUND_FLY, 11, 12, elapsed, 420f, 28f, 60f, pose,
            )
            if (pose.alpha < 0.2f) overlapping++
        }
        assertTrue("揭开早段必须存在几乎不可见的末位图标（实测 $overlapping 个采样点）", overlapping > 0)
    }

    /** 阳性对照：同一时刻静态画法（相位 -1 ⇒ 落位）与带相位画法的末位图标差异必须显著。 */
    @Test
    fun positiveControlStaticVsPhasedDifferSharply() {
        val style = PhotoStyleMotion.byId(1) ?: error("取不到留光")
        val total = PhotoStyleMotion.durationMs(style, edge)
        val elapsed = (0.24f * total).toLong()
        val phased = IconEntranceState()
        HomeMotion.iconEntrance(
            EffectCatalog.HOME_ICON_ROTATE_FLY, 11, 12, elapsed, 420f, 28f, 60f, phased,
        )
        val static = IconEntranceState()
        assertTrue("揭开期早段末位图标带相位时几乎不可见", phased.alpha < 0.2f)
        assertEquals("静态画法必须全亮", 1f, static.alpha, 1e-6f)
        assertTrue("两种画法差异必须显著", static.alpha - phased.alpha > 0.8f)
    }

    /** 末位图标的启动时刻落在揭开期内：揭开还没完，它才刚要开始出现。 */
    @Test
    fun lastIconStartsInsideRevealWindow() {
        val style = PhotoStyleMotion.byId(1) ?: error("取不到留光")
        val total = PhotoStyleMotion.durationMs(style, edge)
        val staggerStart = 11 * 28f
        var revealEndMs = total.toFloat()
        for (step in 1..400) {
            val lt = step / 400f
            if (photoAlpha(1, lt) <= 0.001f) {
                revealEndMs = lt * total
                break
            }
        }
        assertTrue(
            "末位图标启动 ${staggerStart}ms 必须早于揭开结束 ${revealEndMs}ms",
            staggerStart < revealEndMs,
        )
        val pose = IconEntranceState()
        HomeMotion.iconEntrance(
            EffectCatalog.HOME_ICON_ROTATE_FLY, 11, 12, staggerStart.toLong(), 420f, 28f, 60f, pose,
        )
        assertEquals("刚启动那一刻透明度必须为 0", 0f, pose.alpha, 1e-6f)
    }

    /**
     * 双淡入的定量后果：揭开权重与入场透明度相乘，图标比单条曲线暗得多。
     *
     * 快照带相位时可见度 = photoAlpha 的补 × 入场 alpha；静态快照只受前者控。
     */
    @Test
    fun doubleFadeIsQuantitativelyDarker() {
        val style = PhotoStyleMotion.byId(1) ?: error("取不到留光")
        val total = PhotoStyleMotion.durationMs(style, edge)
        val out = PhotoStyleMotion.Values()
        var worst = 1f
        var worstT = 0f
        var worstRotate = 1f
        for (step in 1..80) {
            val lt = step / 80f
            PhotoStyleMotion.sample(style, edge, lt, aspect, 0f, 0f, out)
            val pa = out.v[PhotoStyle.SLOT_PHOTO_ALPHA]
            if (pa >= 0.999f || pa <= 0.001f) continue
            val pose = IconEntranceState()
            HomeMotion.iconEntrance(
                EffectCatalog.HOME_ICON_FOREGROUND_FLY, 11, 12, (lt * total).toLong(), 420f, 28f, 60f, pose,
            )
            val staticVisible = 1f - pa
            val phasedVisible = (1f - pa) * pose.alpha
            assertEquals("双淡入恒等：比值必须恰等于入场 alpha", pose.alpha, phasedVisible / staticVisible, 1e-5f)
            if (staticVisible <= 0.05f) continue
            val ratio = phasedVisible / staticVisible
            if (ratio < worst) {
                worst = ratio
                worstT = lt
            }
            val rot = IconEntranceState()
            HomeMotion.iconEntrance(
                EffectCatalog.HOME_ICON_ROTATE_FLY, 11, 12, (lt * total).toLong(), 420f, 28f, 60f, rot,
            )
            if (rot.alpha < worstRotate) worstRotate = rot.alpha
        }
        assertTrue("同相位双淡入仍显著变暗 t=$worstT ratio=$worst", worst < 1f)
        assertTrue("错峰的双淡入必须比同相位更暗（rotate=$worstRotate synced=$worst）", worstRotate < worst)
    }

    /** 三种飞入效果在揭开早段都受影响，不是只有一种。 */
    @Test
    fun allFlyEffectsAffectedInEarlyReveal() {
        val style = PhotoStyleMotion.byId(1) ?: error("取不到留光")
        val elapsed = (0.24f * PhotoStyleMotion.durationMs(style, edge)).toLong()
        val alphas = HashMap<String, Float>()
        for (id in listOf(
            EffectCatalog.HOME_ICON_FOREGROUND_FLY,
            EffectCatalog.HOME_ICON_BACKGROUND_FLY,
            EffectCatalog.HOME_ICON_ROTATE_FLY,
            EffectCatalog.HOME_ICON_CROSSFADE,
        )) {
            val pose = IconEntranceState()
            HomeMotion.iconEntrance(id, 11, 12, elapsed, 420f, 28f, 60f, pose)
            assertTrue("效果 $id 在揭开早段必须仍未落位（alpha=${pose.alpha}）", pose.alpha < 1f)
            alphas[id] = pose.alpha
        }
        val rotate = alphas.getValue(EffectCatalog.HOME_ICON_ROTATE_FLY)
        for ((id, a) in alphas) {
            if (id == EffectCatalog.HOME_ICON_ROTATE_FLY) continue
            assertTrue("只有旋转飞入错峰，它必须最淡（$id=$a rotate=$rotate）", rotate < a)
        }
    }

    /** 息屏直接进桌面那条边也有揭开期，同样重叠。 */
    @Test
    fun aodToHomeEdgeAlsoOverlaps() {
        val style = PhotoStyleMotion.byId(1) ?: error("取不到留光")
        val e = TransitionEdge.AOD_TO_HOME
        val out = PhotoStyleMotion.Values()
        var hit = false
        for (step in 1..40) {
            val lt = step / 40f
            PhotoStyleMotion.sample(style, e, lt, aspect, 0f, 0f, out)
            val alpha = out.v[PhotoStyle.SLOT_PHOTO_ALPHA]
            if (alpha >= 0.999f || alpha <= 0.001f) continue
            val elapsed = (lt * PhotoStyleMotion.durationMs(style, e) - PhotoStyle.OPEN_DELAY_MS)
                .coerceAtLeast(0f).toLong()
            val pose = IconEntranceState()
            HomeMotion.iconEntrance(
                EffectCatalog.HOME_ICON_FOREGROUND_FLY, 11, 12, elapsed, 420f, 28f, 60f, pose,
            )
            if (pose.alpha < 0.2f) hit = true
        }
        assertTrue("息屏→桌面揭开早段也必须存在几乎不可见的末位图标", hit)
    }

    /** 缓动本身不是元凶：DECELERATE 前段陡，末位图标的延迟才是。 */
    @Test
    fun decelerateIsFrontLoadedSoDelayDominates() {
        val curve = CubicBezierEasing(0f, 0f, 0.2f, 1f)
        assertTrue("DECELERATE 在 30% 处已过半", curve.map(0.3f) > 0.5f)
        val pose = IconEntranceState()
        HomeMotion.iconEntrance(
            EffectCatalog.HOME_ICON_ROTATE_FLY, 11, 12, 192L, 420f, 28f, 60f, pose,
        )
        assertEquals("旋转飞入末位 192ms 时还没启动（延迟 308ms）", 0f, pose.alpha, 1e-6f)
        val synced = IconEntranceState()
        HomeMotion.iconEntrance(
            EffectCatalog.HOME_ICON_FOREGROUND_FLY, 11, 12, 192L, 420f, 28f, 60f, synced,
        )
        assertEquals("同相位效果无延迟，与首位逐位相同", curve.map(192f / 420f), synced.alpha, 1e-4f)
    }

    /** 目标态判定：揭开只发生在去桌面的两条边，去锁屏/息屏不该有桌面快照。 */
    @Test
    fun revealOnlyTargetsHomeOnTheseEdges() {
        assertEquals(SceneState.HOME, PhotoStyleMotion.toOf(TransitionEdge.LOCK_TO_HOME))
        assertEquals(SceneState.HOME, PhotoStyleMotion.toOf(TransitionEdge.AOD_TO_HOME))
        assertEquals(SceneState.LOCK, PhotoStyleMotion.toOf(TransitionEdge.AOD_TO_LOCK))
        assertEquals(SceneState.AOD, PhotoStyleMotion.toOf(TransitionEdge.LOCK_TO_AOD))
    }
}
