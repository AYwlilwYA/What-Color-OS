package com.coloros16.liquidglass.wallpaper

import android.content.SharedPreferences
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.CameraOrientation
import com.coloros16.liquidglass.log.Lg

/**
 * 3D 相机壁纸服务：EGL/GLES 真三维渲染，相机视角由可配置曲线驱动。
 */
class Lg3DWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SceneEngine()

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

        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("wallpaper_")) {
                handler.post { configAtMs = 0L }
            }
        }

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!loopRunning) return
                val now = SystemClock.elapsedRealtime()
                val interval = if (lastCurveId == "still") 90L else 33L
                if (now - lastRenderMs >= interval) {
                    lastRenderMs = now
                    renderFrame(now)
                }
                choreographer?.postFrameCallback(this)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            thread.start()
            handler = Handler(thread.looper)
            Prefs.read(this@Lg3DWallpaperService).registerOnSharedPreferenceChangeListener(prefsListener)
            Lg.i(WallpaperSettings.TAG, "3d wallpaper engine created")
        }

        override fun onDestroy() {
            Prefs.read(this@Lg3DWallpaperService).unregisterOnSharedPreferenceChangeListener(prefsListener)
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
                        sceneRenderer.setCamera(config.camera)
                        renderer = sceneRenderer
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

        override fun onVisibilityChanged(isVisible: Boolean) {
            handler.post {
                visible = isVisible
                if (isVisible && surfaceReady) {
                    startLoopOnThread()
                } else if (!isVisible) {
                    stopLoopOnThread()
                }
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

        private fun startLoopOnThread() {
            if (loopRunning) return
            ensureChoreographer()
            resumeAnchorMs = SystemClock.elapsedRealtime()
            loopRunning = true
            lastRenderMs = 0L
            configAtMs = 0L
            choreographer?.postFrameCallback(frameCallback)
            Lg.i(WallpaperSettings.TAG, "3d loop start offset=$offsetX")
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
                if (fresh.curveId != lastCurveId || fresh.customText != lastCustom) {
                    lastCurveId = fresh.curveId
                    lastCustom = fresh.customText
                    renderer?.setCamera(fresh.camera)
                    Lg.i(
                        WallpaperSettings.TAG,
                        "3d camera curve=${fresh.curveId} cycle=${fresh.cycleMs}ms speed=${fresh.speed} parallax=${fresh.parallax}",
                    )
                }
                config = fresh
            }

            val elapsed = elapsedMs()
            val progress = CameraOrientation.cycleProgress(elapsed, config.cycleMs, config.speed)
            val scene = renderer
            if (scene != null) {
                try {
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
