package com.coloros16.liquidglass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Gl3DSceneCameraWiringContractTest {

    private fun source(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/wallpaper/Gl3DSceneRenderer.kt",
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
    fun sceneModeDefaultsOff() {
        assertTrue("场景模式必须默认关，不改现有曲线行为", source().contains("private var sceneMode = false"))
    }

    @Test
    fun setCameraTurnsSceneModeOff() {
        assertTrue("切回曲线相机必须退出场景模式", body(source(), "setCamera").contains("sceneMode = false"))
    }

    @Test
    fun setSceneCameraEnablesAndTakesFarPlane() {
        val b = body(source(), "setSceneCamera")
        assertTrue(b.contains("sceneMode = true"))
        assertTrue(b.contains("sceneFar = farPlane"))
    }

    @Test
    fun drawBranchesOnSceneMode() {
        val b = body(source(), "draw")
        assertTrue("绘制必须按模式分流", b.contains("if (sceneMode)"))
        assertTrue("场景模式不再采样曲线", b.contains("active = scenePose"))
        assertTrue("曲线模式仍采样", b.contains("camera.sample(cameraProgress, pose)"))
    }

    @Test
    fun sceneModeUsesExplicitFarPlane() {
        val b = body(source(), "draw")
        assertTrue(
            "场景模式必须用指定远面的重载",
            b.contains("CameraMatrices.buildProjection(active, aspect, sceneFar, projection)"),
        )
        assertTrue(
            "曲线模式仍用默认远面",
            b.contains("CameraMatrices.buildProjection(active, aspect, projection)"),
        )
    }

    @Test
    fun sceneParallaxNotAppliedToSceneCamera() {
        val b = body(source(), "draw")
        val curveBranch = b.substringAfter("} else {").substringBefore("active = pose")
        assertTrue("视差摇摆只作用于曲线模式", curveBranch.contains("pose.x += sway"))
        assertFalse("场景位姿不得被视差改写", b.contains("scenePose.x +="))
    }

    @Test
    fun setScenePoseCopiesAllSevenChannels() {
        val b = body(source(), "setScenePose")
        assertTrue(b.contains("source.x"))
        assertTrue(b.contains("source.y"))
        assertTrue(b.contains("source.z"))
        assertTrue(b.contains("source.yawDeg"))
        assertTrue(b.contains("source.pitchDeg"))
        assertTrue(b.contains("source.rollDeg"))
        assertTrue(b.contains("source.fovDeg"))
    }

    @Test
    fun engineProvidesFarPlaneAndAngleConversion() {
        val engine = File(
            "src/main/kotlin/com/coloros16/liquidglass/engine/SnowMountainCamera.kt",
        ).readText()
        assertTrue("引擎必须给远面需求", engine.contains("fun requiredFarPlane()"))
        assertTrue("引擎必须给前向反解角", engine.contains("fun anglesFromForward("))
        assertTrue("引擎必须给位姿折算", engine.contains("fun toCameraPose("))
    }

    @Test
    fun defaultFarPlaneIsTooSmallForSceneTruth() {
        val mat = File("src/main/kotlin/com/coloros16/liquidglass/engine/Mat4.kt").readText()
        assertTrue(mat.contains("const val FAR_PLANE = 120f"))
        val truth = com.coloros16.liquidglass.engine.SnowMountainCamera.requiredFarPlane()
        assertTrue("场景所需远面必须大于默认值", truth > 120f)
    }
}