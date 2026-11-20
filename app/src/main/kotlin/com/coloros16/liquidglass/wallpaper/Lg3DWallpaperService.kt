package com.coloros16.liquidglass.wallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.CameraOrientation
import com.coloros16.liquidglass.engine.SceneCameraDriver
import com.coloros16.liquidglass.engine.SceneModeResolver
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.log.Lg

/**
 * 3D 相机壁纸服务：EGL/GLES 真三维渲染，相机视角由可配置曲线驱动。
 */
class Lg3DWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SceneEngine()

    private companion object {
        /** 场景模式帧间隔（毫秒）：过渡期约 30fps，收敛后停帧。 */
        const val SCENE_FRAME_MS = 33L

        const val COMMAND_GOING_TO_SLEEP = "android.wallpaper.goingtosleep"
        const val COMMAND_WAKING_UP = "android.wallpaper.wakingup"
        const val COMMAND_KEYGUARD_GOING_AWAY = "android.wallpaper.keyguardgoingaway"
    }

    private inner class SceneEngine : Engine() {

        private val thread = HandlerThread("lg3d-wallpaper")
        private lateinit var handler: Handler
        private var choreographer: Choreographer? = null

        private var eglDisplay: EGLDisplay? = null
        private var eglContext: EGLContext? = null
        private var eglSurface: EGLSurface? = null
        private var renderer: Gl3DSceneRenderer? = null

        private var surfaceReady = false
        private var visible = false
        private var loopRunning = false

        private var accumulatedMs = 0L
        private var resumeAnchorMs = 0L
        private var lastRenderMs = 0L
        private var configAtMs = 0L
        private var offsetX = 0.5f
        private var config: WallpaperSettings.Scene3D = WallpaperSettings.defaults3D()
        private var lastCurveId: String? = null
        private var lastCustom: String? = null
        private var firstFrameLogged = false

        private val driver = SceneCameraDriver()
        private var sceneActive = false
        private var ambient = false
        private var lastSceneFrameMs = 0L
        private var lockSettlePending = false
        private var sceneStateLogged: SceneState? = null
        private var screenReceiver: BroadcastReceiver? = null

        private val lockSettle = Runnable {
            lockSettlePending = false
            if (!sceneActive) return@Runnable
            if (!SceneModeResolver.lockStillHolds(keyguardLocked())) return@Runnable
            applySceneState(SceneState.LOCK, snap = false)
        }

        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("wallpaper_")) {
                handler.post { configAtMs = 0L }
            }
        }

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!loopRunning) return
                val now = SystemClock.elapsedRealtime()
                val interval = if (sceneActive) SCENE_FRAME_MS else if (lastCurveId == "still") 90L else 33L
                if (now - lastRenderMs >= interval) {
                    lastRenderMs = now
                    renderFrame(now)
                }
                if (sceneActive && !driver.needsFrame() && !lockSettlePending) {
                    loopRunning = false
                    Lg.i(WallpaperSettings.TAG, "3d scene settled, frames parked state=${driver.state}")
                    return
                }
                choreographer?.postFrameCallback(this)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            thread.start()
            handler = Handler(thread.looper)
            Prefs.read(this@Lg3DWallpaperService).registerOnSharedPreferenceChangeListener(prefsListener)
            registerScreenState()
            Lg.i(WallpaperSettings.TAG, "3d wallpaper engine created")
        }

        override fun onDestroy() {
            unregisterScreenState()
            Prefs.read(this@Lg3DWallpaperService).unregisterOnSharedPreferenceChangeListener(prefsListener)
            handler.removeCallbacks(lockSettle)
            handler.post {
                stopLoopOnThread()
                destroyEgl()
                renderer?.release()
                renderer = null
                surfaceReady = false
                choreographer?.removeFrameCallback(frameCallback)
                choreographer = null
            }
            thread.quitSafely()
            super.onDestroy()
            Lg.i(WallpaperSettings.TAG, "3d wallpaper engine destroyed")
        }

        /**
         * 屏幕与解锁事件：只作为三态判定的输入，不排任何周期任务。
         *
         * 注册前先摘掉本引擎上一次的接收器，避免同一引擎重建时堆叠。
         */
        private fun registerScreenState() {
            unregisterScreenState()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    handler.post {
                        when (action) {
                            Intent.ACTION_SCREEN_OFF -> ambient = true
                            Intent.ACTION_SCREEN_ON -> ambient = false
                            Intent.ACTION_USER_PRESENT -> ambient = false
                        }
                        refreshSceneState()
                    }
                }
            }
            screenReceiver = receiver
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }
            } catch (t: Throwable) {
                screenReceiver = null
                Lg.w(WallpaperSettings.TAG, "3d screen receiver register failed", t)
            }
        }

        private fun unregisterScreenState() {
            val receiver = screenReceiver ?: return
            screenReceiver = null
            try {
                unregisterReceiver(receiver)
            } catch (t: Throwable) {
                Lg.w(WallpaperSettings.TAG, "3d screen receiver unregister failed", t)
            }
        }

        private fun keyguardLocked(): Boolean = try {
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        } catch (t: Throwable) {
            Lg.w(WallpaperSettings.TAG, "3d keyguard state read failed", t)
            false
        }

        /**
         * 框架壁纸命令：息屏与唤醒由此下发，是三态判定的事件源。
         *
         * 息屏标志优先于锁屏状态，因为息屏时锁屏仍报已锁。
         */
        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            when (action) {
                COMMAND_GOING_TO_SLEEP -> handler.post {
                    ambient = true
                    Lg.i(WallpaperSettings.TAG, "3d cmd=sleep")
                    refreshSceneState()
                }
                COMMAND_WAKING_UP -> handler.post {
                    ambient = false
                    Lg.i(WallpaperSettings.TAG, "3d cmd=wake")
                    refreshSceneState()
                }
                COMMAND_KEYGUARD_GOING_AWAY -> handler.post {
                    ambient = false
                    Lg.i(WallpaperSettings.TAG, "3d cmd=keyguard-away")
                    if (lockSettlePending) {
                        lockSettlePending = false
                        handler.removeCallbacks(lockSettle)
                    }
                    applySceneState(SceneState.HOME, snap = false)
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            handler.post {
                visible = isVisible
                if (isVisible) {
                    refreshSceneState()
                    if (surfaceReady) startLoopOnThread()
                } else {
                    stopLoopOnThread()
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            handler.post {
                surfaceReady = false
                destroyEgl()
                val frame = holder.surfaceFrame
                val width = frame.width().coerceAtLeast(1)
                val height = frame.height().coerceAtLeast(1)
                val ok = try {
                    createEgl(holder, width, height)
                } catch (t: Throwable) {
                    Lg.e(WallpaperSettings.TAG, "3d egl setup threw", t)
                    destroyEgl()
                    false
                }
                if (ok) {
                    val sceneRenderer = Gl3DSceneRenderer()
                    try {
                        sceneRenderer.onSurfaceChanged(width, height)
                        sceneRenderer.onSurfaceCreated()
                        config = WallpaperSettings.read3D(this@Lg3DWallpaperService)
                        renderer = sceneRenderer
                        sceneActive = false
                        applySceneConfig(sceneRenderer)
                    } catch (t: Throwable) {
                        Lg.e(WallpaperSettings.TAG, "3d renderer init failed", t)
                        sceneRenderer.release()
                        renderer = null
                    }
                    surfaceReady = true
                    firstFrameLogged = false
                    if (visible) startLoopOnThread()
                }
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            handler.post {
                renderer?.onSurfaceChanged(width.coerceAtLeast(1), height.coerceAtLeast(1))
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.post {
                stopLoopOnThread()
                surfaceReady = false
                renderer?.release()
                renderer = null
                destroyEgl()
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            offsetX = xOffset
        }

        /**
         * 按配置切场景模式或曲线模式：场景模式给远面并按当前三态就位。
         */
        private fun applySceneConfig(target: Gl3DSceneRenderer) {
            if (!WallpaperSettings.validScene(config.sceneId)) {
                sceneActive = false
                target.setCamera(config.camera)
                Lg.i(WallpaperSettings.TAG, "3d mode=curve id=${config.curveId}")
                return
            }
            sceneActive = true
            target.setSceneCamera(driver.farPlane())
            driver.reset(SceneModeResolver.resolve(ambient, keyguardLocked()))
            lastSceneFrameMs = SystemClock.elapsedRealtime()
            pushScenePose(target)
            sceneStateLogged = driver.state
            Lg.i(
                WallpaperSettings.TAG,
                "3d mode=scene id=${config.sceneId} state=${driver.state} far=${driver.farPlane()}",
            )
        }

        private fun pushScenePose(target: Gl3DSceneRenderer) {
            target.setScenePose(driver.pose())
            target.setSceneMaterial(driver.dark(), driver.rot())
        }

        /**
         * 三态变化：换目标并武装帧回调；亮屏直进锁屏时先等一拍再认。
         */
        private fun refreshSceneState() {
            if (!sceneActive) return
            val locked = keyguardLocked()
            val next = SceneModeResolver.resolve(ambient, locked)
            if (SceneModeResolver.needsLockSettle(driver.state, next, locked)) {
                if (!lockSettlePending) {
                    lockSettlePending = true
                    handler.postDelayed(lockSettle, SceneModeResolver.LOCK_SETTLE_MS)
                    Lg.i(WallpaperSettings.TAG, "3d scene lock-settle armed")
                    armSceneFrames()
                }
                return
            }
            if (lockSettlePending) {
                lockSettlePending = false
                handler.removeCallbacks(lockSettle)
            }
            applySceneState(next, snap = false)
        }

        private fun applySceneState(next: SceneState, snap: Boolean) {
            if (!sceneActive) return
            val changed = driver.retarget(next, snap)
            if (changed || sceneStateLogged != next) {
                sceneStateLogged = next
                Lg.i(WallpaperSettings.TAG, "3d scene state=$next snap=$snap")
            }
            if (changed || snap) armSceneFrames()
        }

        private fun armSceneFrames() {
            if (!surfaceReady || !visible) return
            lastSceneFrameMs = SystemClock.elapsedRealtime()
            startLoopOnThread()
        }

        private fun startLoopOnThread() {
            if (loopRunning) return
            ensureChoreographer()
            resumeAnchorMs = SystemClock.elapsedRealtime()
            loopRunning = true
            lastRenderMs = 0L
            configAtMs = 0L
            choreographer?.postFrameCallback(frameCallback)
            Lg.i(WallpaperSettings.TAG, "3d loop start offset=$offsetX scene=$sceneActive")
        }

        private fun stopLoopOnThread() {
            if (!loopRunning) return
            accumulatedMs = elapsedMs()
            loopRunning = false
            choreographer?.removeFrameCallback(frameCallback)
            Lg.i(WallpaperSettings.TAG, "3d loop stop elapsed=${accumulatedMs}ms")
        }

        private fun elapsedMs(): Long =
            if (loopRunning) accumulatedMs + (SystemClock.elapsedRealtime() - resumeAnchorMs) else accumulatedMs

        private fun ensureChoreographer() {
            if (choreographer == null) choreographer = Choreographer.getInstance()
        }

        private fun renderFrame(now: Long) {
            if (!surfaceReady) return
            if (now - configAtMs >= 1000L) {
                configAtMs = now
                val fresh = WallpaperSettings.read3D(this@Lg3DWallpaperService)
                val sceneChanged = fresh.sceneId != config.sceneId
                val curveChanged = fresh.curveId != lastCurveId || fresh.customText != lastCustom
                config = fresh
                val target = renderer
                if (target != null && sceneChanged) {
                    lastCurveId = fresh.curveId
                    lastCustom = fresh.customText
                    applySceneConfig(target)
                } else if (target != null && curveChanged && !sceneActive) {
                    lastCurveId = fresh.curveId
                    lastCustom = fresh.customText
                    target.setCamera(fresh.camera)
                    Lg.i(
                        WallpaperSettings.TAG,
                        "3d camera curve=${fresh.curveId} cycle=${fresh.cycleMs}ms speed=${fresh.speed} parallax=${fresh.parallax}",
                    )
                }
            }

            val elapsed = elapsedMs()
            val progress = CameraOrientation.cycleProgress(elapsed, config.cycleMs, config.speed)
            val scene = renderer
            if (scene != null) {
                try {
                    if (sceneActive) {
                        val delta = now - lastSceneFrameMs
                        lastSceneFrameMs = now
                        driver.advance(delta)
                        pushScenePose(scene)
                    }
                    scene.draw(progress, offsetX, config.parallax, elapsed / 1000f)
                } catch (t: Throwable) {
                    Lg.e(WallpaperSettings.TAG, "3d draw failed, fallback to solid clear", t)
                    scene.release()
                    renderer = null
                    GLES20.glClearColor(0.008f, 0.012f, 0.028f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                }
            } else {
                GLES20.glClearColor(0.008f, 0.012f, 0.028f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            }
            if (eglSwap()) {
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    Lg.i(WallpaperSettings.TAG, "3d first frame progress=$progress offset=$offsetX")
                }
            } else {
                Lg.e(WallpaperSettings.TAG, "3d eglSwapBuffers failed")
            }
        }

        private fun createEgl(holder: SurfaceHolder, width: Int, height: Int): Boolean {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                Lg.e(WallpaperSettings.TAG, "3d eglGetDisplay failed")
                return false
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                Lg.e(WallpaperSettings.TAG, "3d eglInitialize failed")
                return false
            }
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0) || count[0] <= 0) {
                Lg.e(WallpaperSettings.TAG, "3d eglChooseConfig failed")
                EGL14.eglTerminate(display)
                return false
            }
            val config = configs[0] ?: return false
            val context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) {
                Lg.e(WallpaperSettings.TAG, "3d eglCreateContext failed")
                EGL14.eglTerminate(display)
                return false
            }
            val surface = EGL14.eglCreateWindowSurface(display, config, holder.surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                Lg.e(WallpaperSettings.TAG, "3d eglCreateWindowSurface failed ${width}x$height")
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                return false
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                Lg.e(WallpaperSettings.TAG, "3d eglMakeCurrent failed err=${EGL14.eglGetError()}")
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                return false
            }
            eglDisplay = display
            eglContext = context
            eglSurface = surface
            GLES20.glViewport(0, 0, width, height)
            Lg.i(WallpaperSettings.TAG, "3d egl ready ${width}x$height")
            return true
        }

        private fun eglSwap(): Boolean {
            val display = eglDisplay ?: return false
            val surface = eglSurface ?: return false
            return EGL14.eglSwapBuffers(display, surface)
        }

        private fun destroyEgl() {
            val display = eglDisplay
            if (display != null) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                eglSurface?.let { EGL14.eglDestroySurface(display, it) }
                eglContext?.let { EGL14.eglDestroyContext(display, it) }
                EGL14.eglTerminate(display)
            }
            eglSurface = null
            eglContext = null
            eglDisplay = null
        }
    }
}
