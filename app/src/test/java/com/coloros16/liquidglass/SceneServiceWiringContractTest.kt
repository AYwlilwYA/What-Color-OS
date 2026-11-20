package com.coloros16.liquidglass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 三态场景服务侧接线契约：走公开命令通道、事件驱动无轮询、收敛停帧、曲线模式零回归。
 */
class SceneServiceWiringContractTest {

    private fun service(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/wallpaper/Lg3DWallpaperService.kt",
    ).readText()

    private fun renderer(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/wallpaper/Gl3DSceneRenderer.kt",
    ).readText()

    private fun settings(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/wallpaper/WallpaperSettings.kt",
    ).readText()

    private fun manifestXml(): String = File("src/main/res/xml/wallpaper_lg3d.xml").readText()

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

    /**
     * 不声明 ambient 能力：该属性对应的权限是系统级，普通应用声明了也拿不到。
     *
     * 息屏事件走公开命令通道，与 ambient 声明无关，故不声明不影响三态。
     */
    @Test
    fun ambientModeNotDeclaredInWallpaperMeta() {
        assertFalse(
            "ambient 能力需系统级权限，普通应用不声明",
            manifestXml().contains("android:supportsAmbientMode"),
        )
    }

    /** 阳性对照：命令分发不经 ambient 门，故不声明也能收到三态命令。 */
    @Test
    fun commandChannelNeedsNoAmbientDeclaration() {
        val s = service()
        assertTrue("三态靠命令通道", s.contains("override fun onCommand("))
        assertFalse(
            "服务不得依赖 ambient 状态判定（该回调不可重写）",
            s.contains("onAmbientModeChanged"),
        )
        assertFalse("不得读 ambient 标志位", s.contains("isInAmbientMode"))
    }

    @Test
    fun serviceHandlesFrameworkSleepWakeCommands() {
        val s = service()
        assertTrue(
            "壁纸引擎收三态靠框架命令，不是 ambient 回调（后者非公开 API 不可重写）",
            s.contains("override fun onCommand("),
        )
        assertTrue(s.contains("\"android.wallpaper.goingtosleep\""))
        assertTrue(s.contains("\"android.wallpaper.wakingup\""))
        assertTrue(s.contains("\"android.wallpaper.keyguardgoingaway\""))
        val b = body(s, "onCommand")
        assertTrue(b.contains("COMMAND_GOING_TO_SLEEP ->"))
        assertTrue(b.contains("COMMAND_WAKING_UP ->"))
        assertTrue(b.contains("COMMAND_KEYGUARD_GOING_AWAY ->"))
        assertTrue("每条命令都要重判三态", b.contains("refreshSceneState()"))
        assertTrue("必须转交父类保持原行为", b.contains("super.onCommand("))
    }

    @Test
    fun keyguardAwayCancelsPendingLockSettle() {
        val b = body(service(), "onCommand")
        val branch = b.substringAfter("COMMAND_KEYGUARD_GOING_AWAY ->")
        assertTrue("解锁命令必须撤掉等锁屏的那一拍", branch.contains("removeCallbacks(lockSettle)"))
        assertTrue(branch.contains("applySceneState(SceneState.HOME"))
    }

    @Test
    fun screenEventsRegisteredAndUnregistered() {
        val reg = body(service(), "registerScreenState")
        assertTrue(reg.contains("Intent.ACTION_SCREEN_OFF"))
        assertTrue(reg.contains("Intent.ACTION_SCREEN_ON"))
        assertTrue(reg.contains("Intent.ACTION_USER_PRESENT"))
        assertTrue("注册前必须先摘上一次", reg.contains("unregisterScreenState()"))
        val unreg = body(service(), "unregisterScreenState")
        assertTrue(unreg.contains("unregisterReceiver(receiver)"))
        assertTrue("销毁时必须摘接收器", body(service(), "onDestroy").contains("unregisterScreenState()"))
    }

    @Test
    fun noPollingInService() {
        val s = service()
        assertFalse("禁 while(true) 轮询", s.contains("while (true)"))
        assertFalse("禁 Thread.sleep", s.contains("Thread.sleep"))
        assertFalse("禁 Timer", s.contains("Timer("))
        assertFalse("禁 ScheduledExecutor", s.contains("ScheduledExecutor"))
    }

    @Test
    fun lockSettleIsOneShotNotRepeating() {
        val s = service()
        val count = Regex("postDelayed\\(").findAll(s).count()
        assertTrue("只允许亮屏认锁屏这一处一次性延迟，实际 $count 处", count == 1)
        assertTrue(s.contains("SceneModeResolver.LOCK_SETTLE_MS"))
        assertTrue("等待必须可撤销", s.contains("removeCallbacks(lockSettle)"))
    }

    @Test
    fun framesParkOnceSceneSettled() {
        val s = service()
        assertTrue(
            "收敛后必须停帧，否则静止期空转",
            s.contains("!driver.needsFrame()"),
        )
        assertTrue("停帧时必须真的退出循环", s.contains("loopRunning = false"))
    }

    @Test
    fun sceneModePushesPoseAndMaterialEachFrame() {
        val b = body(service(), "renderFrame")
        assertTrue(b.contains("driver.advance(delta)"))
        assertTrue(b.contains("pushScenePose(scene)"))
        val push = body(service(), "pushScenePose")
        assertTrue(push.contains("setScenePose(driver.pose())"))
        assertTrue(push.contains("setSceneMaterial(driver.dark(), driver.rot())"))
    }

    @Test
    fun sceneModeSetsExplicitFarPlane() {
        val b = body(service(), "applySceneConfig")
        assertTrue(
            "场景模式必须给远面，否则真值相机被裁掉全黑",
            b.contains("target.setSceneCamera(driver.farPlane())"),
        )
        assertTrue("曲线模式仍走 setCamera", b.contains("target.setCamera(config.camera)"))
    }

    @Test
    fun curveModeUnchangedWhenSceneNotSelected() {
        val b = body(service(), "applySceneConfig")
        val curveBranch = b.substringBefore("sceneActive = true")
        assertTrue(curveBranch.contains("sceneActive = false"))
        assertTrue(curveBranch.contains("WallpaperSettings.validScene(config.sceneId)"))
        assertFalse("曲线分支不得设远面", curveBranch.contains("setSceneCamera"))
    }

    @Test
    fun sceneIdDefaultsToNoneSoExistingBehaviorUnchanged() {
        val s = settings()
        assertTrue(s.contains("const val SCENE_NONE = \"\""))
        assertTrue(s.contains("sceneId: String = SCENE_NONE"))
        assertTrue("默认配置必须仍是曲线", body(s, "defaults3D").contains("sceneId = SCENE_NONE"))
    }

    @Test
    fun invalidSceneIdFallsBackToNone() {
        val b = body(settings(), "read3D")
        assertTrue(b.contains("validScene(sceneRaw)"))
        assertTrue(b.contains("else SCENE_NONE"))
    }

    @Test
    fun rendererDrawsWorldGeometryOnlyInSceneMode() {
        val b = body(renderer(), "draw")
        assertTrue(b.contains("if (sceneMode)"))
        assertTrue(b.contains("drawWorldStars("))
        assertTrue(b.contains("drawWorldMoon("))
        assertTrue(b.contains("drawWorldRidges()"))
        val drawBlock = b.substringAfter("drawSky()")
        val curveBranch = drawBlock.substringAfter("} else {")
        assertTrue("曲线模式仍画原小世界", curveBranch.contains("drawStars(elapsedSec)"))
        assertTrue(curveBranch.contains("drawRidge(ridgeFarBuffer"))
        assertFalse("曲线分支不得画世界几何", curveBranch.contains("drawWorldRidges"))
    }

    @Test
    fun worldGeometryBuiltFromEngineNotHardcodedInRenderer() {
        val b = body(renderer(), "buildWorldGeometry")
        assertTrue(b.contains("SceneWorldLayout.ridgeRing("))
        assertTrue(b.contains("SceneWorldLayout.starField("))
        assertTrue(b.contains("SceneWorldLayout.domeVertices("))
        assertTrue(b.contains("SceneWorldLayout.domeIndices("))
    }

    @Test
    fun ridgeShaderTakesModelAndFogAsUniforms() {
        val r = renderer()
        assertTrue("山脊顶点着色器必须吃模型矩阵才能自转", r.contains("uniform mat4 uModel;"))
        assertTrue("雾必须可按世界尺度调，否则大世界全糊", r.contains("uniform float uFogScale;"))
        assertTrue(r.contains("uniform vec3 uFogColor;"))
        assertTrue(r.contains("glGetUniformLocation(ridgeProgram, \"uFogScale\")"))
    }

    @Test
    fun curveFogScaleKeepsOriginalValue() {
        val r = renderer()
        assertTrue(
            "曲线模式雾系数必须保持原值，避免观感回归",
            r.contains("private const val CURVE_FOG_SCALE = 0.075f"),
        )
        assertTrue(r.contains("fogScale: Float = CURVE_FOG_SCALE"))
    }

    @Test
    fun sceneMaterialClampsDarkAndRot() {
        val b = body(renderer(), "setSceneMaterial")
        assertTrue(b.contains("dark.coerceIn(0f, 1f)"))
        assertTrue(b.contains("rot.coerceIn(0f, 1f)"))
    }
}
