package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.WorldCenterPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 世界层圆心跟随时钟有效位的契约：圆心取「样式锚点 + 编辑偏移」，不是原始样式锚点。
 *
 * 留光款照片有自己的构图不跟随；镜片无款式门槛一律跟随。
 * 时钟锚点未就绪时必须回落样式锚点，不能落到状态机缺省位。
 */
class WorldCenterFollowContractTest {

    private fun read(vararg parts: String): String =
        File(listOf("src", "main", "kotlin", "com", "coloros16", "liquidglass", *parts).joinToString(File.separator))
            .readText()

    /** 只有留光款照片不跟时钟，其余四款都跟。 */
    @Test
    fun onlyLeaveLightPhotoKeepsItsOwnCenter() {
        assertFalse("留光照片不跟时钟", WorldCenterPolicy.photoFollowsClock(1))
        for (id in 2..5) assertTrue("样式 $id 照片应跟时钟", WorldCenterPolicy.photoFollowsClock(id))
    }

    /** 跟随时取时钟有效位，不跟随时留样式锚点。 */
    @Test
    fun centerTakesClockWhenFollowing() {
        val follow = WorldCenterPolicy.center(true, 0.5f, 0.435f, 0.3f, 0.6f)
        assertEquals(0.3f, follow.first, 1e-6f)
        assertEquals(0.6f, follow.second, 1e-6f)
        val keep = WorldCenterPolicy.center(false, 0.5f, 0.435f, 0.3f, 0.6f)
        assertEquals(0.5f, keep.first, 1e-6f)
        assertEquals(0.435f, keep.second, 1e-6f)
    }

    /** 有效位任一轴 NaN 视为未就绪，整体回落样式锚点（不能只回落一轴）。 */
    @Test
    fun nanFallsBackToStyleAnchorOnBothAxes() {
        val cases = listOf(
            Float.NaN to 0.6f,
            0.3f to Float.NaN,
            Float.NaN to Float.NaN,
        )
        for ((x, y) in cases) {
            val c = WorldCenterPolicy.center(true, 0.5f, 0.435f, x, y)
            assertEquals("x=$x y=$y 应回落样式 x", 0.5f, c.first, 1e-6f)
            assertEquals("x=$x y=$y 应回落样式 y", 0.435f, c.second, 1e-6f)
        }
    }

    /** 未编辑时钟（有效位=样式锚点）时圆心不变，缺省档零影响。 */
    @Test
    fun uneditedClockLeavesCenterUnchanged() {
        val c = WorldCenterPolicy.center(true, 0.5f, 0.435f, 0.5f, 0.435f)
        assertEquals(0.5f, c.first, 1e-6f)
        assertEquals(0.435f, c.second, 1e-6f)
    }

    /** 阳性对照：旧口径直读样式锚点，编辑后偏差按屏幅折算必须显著。 */
    @Test
    fun positiveControlOldReadDeviatesBy448Px() {
        val styleX = 0.5f
        val styleY = 0.435f
        val clockX = 0.3f
        val clockY = 0.6f
        val w = 1080f
        val h = 2376f
        val dx = (clockX - styleX) * w
        val dy = (clockY - styleY) * h
        assertEquals("横向偏差 216px", 216f, kotlin.math.abs(dx), 0.5f)
        assertEquals("纵向偏差 392px", 392.04f, kotlin.math.abs(dy), 0.5f)
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        assertTrue("旧口径圆心与时钟相距应约 448px（实测 $dist）", dist > 440f && dist < 456f)
        val fixed = WorldCenterPolicy.center(true, styleX, styleY, clockX, clockY)
        val fixedDist = kotlin.math.sqrt(
            ((fixed.first - clockX) * w) * ((fixed.first - clockX) * w) +
                ((fixed.second - clockY) * h) * ((fixed.second - clockY) * h),
        )
        assertEquals("修后圆心必须与时钟重合", 0f, fixedDist, 1e-3f)
    }

    /** 状态机未写入锚点时 hasAnchor=false，缺省值不得被当成真值。 */
    @Test
    fun machineReportsUnsetAnchor() {
        val m = ClockStateMachine()
        assertFalse("未写入应为 false", m.hasAnchor(SceneState.AOD))
        val def = m.anchorOf(SceneState.AOD)
        assertEquals("缺省位是 0.5/0.5", 0.5f, def.centerX, 1e-6f)
        assertEquals(0.5f, def.centerY, 1e-6f)
        assertTrue("缺省位与样式锚点 0.435 不同 ⇒ 不能当真值", kotlin.math.abs(def.centerY - 0.435f) > 0.06f)
        m.setAnchor(SceneState.AOD, ClockAnchor(centerX = 0.3f, centerY = 0.6f), 0L, follow = false)
        assertTrue("写入后应为 true", m.hasAnchor(SceneState.AOD))
        assertEquals(0.3f, m.anchorOf(SceneState.AOD).centerX, 1e-6f)
    }

    /** hook 两处都必须走策略 + 就绪判定，不得再直读 spec 锚点当圆心。 */
    @Test
    fun hookWorldLayerRoutesBothBranchesThroughPolicy() {
        val src = read("hook", "aod", "AodWorldLayer.kt")
        assertEquals("镜片与照片两处都要调策略", 2, Regex("WorldCenterPolicy\\.center\\(").findAll(src).count())
        assertEquals("两处都要判锚点就绪", 2, Regex("hasAnchor\\(SceneState\\.AOD\\)").findAll(src).count())
        assertTrue("照片支必须问款式门槛", src.contains("WorldCenterPolicy.photoFollowsClock(photo.id)"))
        assertTrue("未就绪必须传 NaN 而不是缺省位", src.contains("live?.centerX ?: Float.NaN"))
        assertFalse(
            "不得再有直读 spec 锚点当圆心的旧写法",
            src.contains("val anchorX = (anchor?.centerX ?: 0.5f) - 0.5f"),
        )
        assertFalse(
            "镜片也不得直读",
            src.contains("val aodX = anchor?.centerX ?: 0.5f"),
        )
    }

    /** 预览两处走同一私有件，且取编辑偏移而非状态机锚点。 */
    @Test
    fun previewRoutesBothBranchesThroughOffset() {
        val src = read("ui", "aod", "AodPreviewView.kt")
        assertEquals("镜片与照片两处都要调私有件", 2, Regex("worldCenter\\(").findAll(src).count() - 1)
        assertTrue("私有件必须取编辑偏移", src.contains("editDelegate.clockOffset(null, 0f, direct = true)"))
        assertTrue("照片支必须问款式门槛", src.contains("WorldCenterPolicy.photoFollowsClock(photo.id)"))
        assertFalse(
            "预览不得改读状态机锚点（那里存的是原始值）",
            src.contains("clockMachine.anchorOf(SceneState.AOD)"),
        )
        assertFalse(
            "不得再有直读 spec 锚点当圆心的旧写法",
            src.contains("val anchorX = (anchor?.centerX ?: 0.5f) - 0.5f"),
        )
    }

    /** 策略是纯 JVM：不引 android.*，预览与 hook 才能共用。 */
    @Test
    fun policyIsPureJvm() {
        val src = read("engine", "WorldCenterPolicy.kt")
        assertFalse("引擎件禁 android 依赖", src.contains("import android."))
    }
}