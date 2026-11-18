package com.coloros16.liquidglass.wallpaper

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import java.io.File
import kotlin.math.max
import kotlin.math.sin

/**
 * 2D 壁纸服务：静态图片（带视差与缓慢推近）、循环视频、Canvas 程序化动态特效。
 */
class Lg2DWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = Scene2DEngine()

    private inner class Scene2DEngine : Engine() {

        private val thread = HandlerThread("lg2d-wallpaper")
        private lateinit var handler: Handler
        private var choreographer: Choreographer? = null

        private var surfaceReady = false
        private var visible = false
        private var canvasLoopRunning = false
        private var playbackMode = ""
        private var canvasUsed = false

        private var config: WallpaperSettings.Scene2D = WallpaperSettings.defaults2D()
        private var offsetX = 0.5f
        private var loopAnchorMs = 0L
        private var lastDrawMs = 0L
        private var firstFrameLogged = false

        private var bitmap: Bitmap? = null
        private var bitmapPath: String? = null
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        private var mediaPlayer: MediaPlayer? = null
        private var videoPrepared = false

        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("wallpaper_")) {
                handler.post {
                    val fresh = WallpaperSettings.read2D(this@Lg2DWallpaperService)
                    val structural = fresh.mode != config.mode ||
                        fresh.effect != config.effect ||
                        fresh.sourcePath != config.sourcePath
                    config = fresh
                    if (structural && surfaceReady) applyConfig()
                }
            }
        }

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!canvasLoopRunning) return
                val now = SystemClock.elapsedRealtime()
                val interval = if (playbackMode == WallpaperSettings.MODE_IMAGE) 50L else 33L
                if (now - lastDrawMs >= interval) {
                    lastDrawMs = now
                    drawCanvasFrame()
                }
                choreographer?.postFrameCallback(this)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            thread.start()
            handler = Handler(thread.looper)
            Prefs.read(this@Lg2DWallpaperService).registerOnSharedPreferenceChangeListener(prefsListener)
            Lg.i(WallpaperSettings.TAG, "2d wallpaper engine created")
        }

        override fun onDestroy() {
            Prefs.read(this@Lg2DWallpaperService).unregisterOnSharedPreferenceChangeListener(prefsListener)
            handler.post {
                stopAll()
                recycleBitmap()
                surfaceReady = false
                choreographer?.removeFrameCallback(frameCallback)
                choreographer = null
            }
            thread.quitSafely()
            super.onDestroy()
            Lg.i(WallpaperSettings.TAG, "2d wallpaper engine destroyed")
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            handler.post {
                surfaceReady = true
                firstFrameLogged = false
                canvasUsed = false
                config = WallpaperSettings.read2D(this@Lg2DWallpaperService)
                applyConfig()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // Canvas 每帧从 Canvas 取尺寸，无需缓存
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.post {
                stopAll()
                recycleBitmap()
                surfaceReady = false
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            handler.post {
                visible = isVisible
                if (!surfaceReady) return@post
                if (isVisible) {
                    resumePlayback()
                } else {
                    pausePlayback()
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

        private fun applyConfig() {
            if (!surfaceReady) return
            val target = effectiveMode()
            if (target == playbackMode) {
                if (target == WallpaperSettings.MODE_VIDEO && mediaPlayer != null) return
                if (target != WallpaperSettings.MODE_VIDEO && canvasLoopRunning) return
            }
            stopAll()
            if (!visible) return
            when (target) {
                WallpaperSettings.MODE_VIDEO -> startVideo()
                WallpaperSettings.MODE_IMAGE -> {
                    playbackMode = WallpaperSettings.MODE_IMAGE
                    startCanvasLoop()
                }
                else -> {
                    playbackMode = WallpaperSettings.MODE_EFFECT
                    startCanvasLoop()
                }
            }
            Lg.i(
                WallpaperSettings.TAG,
                "2d apply mode=${config.mode} effective=$playbackMode effect=${config.effect} source=${config.sourcePath}",
            )
        }

        private fun resumePlayback() {
            if (playbackMode == WallpaperSettings.MODE_VIDEO && mediaPlayer != null && videoPrepared) {
                try {
                    mediaPlayer?.start()
                } catch (t: Throwable) {
                    Lg.e(WallpaperSettings.TAG, "2d video resume failed", t)
                    applyConfig()
                }
            } else if (playbackMode == WallpaperSettings.MODE_EFFECT || playbackMode == WallpaperSettings.MODE_IMAGE) {
                startCanvasLoop()
            } else {
                applyConfig()
            }
        }

        private fun pausePlayback() {
            canvasLoopRunning = false
            choreographer?.removeFrameCallback(frameCallback)
            if (!videoPrepared) return
            try {
                mediaPlayer?.pause()
            } catch (t: Throwable) {
                Lg.w(WallpaperSettings.TAG, "2d video pause failed: ${t.message}")
            }
        }

        private fun stopAll() {
            canvasLoopRunning = false
            choreographer?.removeFrameCallback(frameCallback)
            releaseVideo()
            playbackMode = ""
        }

        private fun startCanvasLoop() {
            if (canvasLoopRunning) return
            if (choreographer == null) choreographer = Choreographer.getInstance()
            if (loopAnchorMs == 0L) loopAnchorMs = SystemClock.elapsedRealtime()
            canvasLoopRunning = true
            lastDrawMs = 0L
            choreographer?.postFrameCallback(frameCallback)
        }

        private fun elapsedSec(): Float {
            if (loopAnchorMs == 0L) return 0f
            return (SystemClock.elapsedRealtime() - loopAnchorMs) / 1000f
        }

        private fun drawCanvasFrame() {
            if (!surfaceReady || !visible) return
            val holder = surfaceHolder
            val canvas = try {
                holder.lockCanvas()
            } catch (t: Throwable) {
                Lg.e(WallpaperSettings.TAG, "2d lockCanvas failed", t)
                null
            } ?: return
            canvasUsed = true
            try {
                val width = canvas.width.toFloat()
                val height = canvas.height.toFloat()
                if (width <= 0f || height <= 0f) return
                val elapsed = elapsedSec()
                if (effectiveMode() == WallpaperSettings.MODE_IMAGE) {
                    drawImage(canvas, width, height, elapsed)
                } else {
                    CanvasEffects.draw(
                        canvas,
                        width,
                        height,
                        elapsed,
                        config.effect,
                        offsetX,
                        config.parallax,
                    )
                }
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    Lg.i(WallpaperSettings.TAG, "2d first frame mode=$playbackMode ${width.toInt()}x${height.toInt()}")
                }
            } catch (t: Throwable) {
                Lg.e(WallpaperSettings.TAG, "2d draw frame failed", t)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (t: Throwable) {
                    Lg.e(WallpaperSettings.TAG, "2d unlockCanvas failed", t)
                }
            }
        }

        private fun drawImage(canvas: Canvas, width: Float, height: Float, elapsedSec: Float) {
            val source = ensureBitmap()
            if (source == null) {
                CanvasEffects.draw(
                    canvas,
                    width,
                    height,
                    elapsedSec,
                    WallpaperSettings.EFFECT_AURORA,
                    offsetX,
                    config.parallax,
                )
                return
            }
            val overscan = 1.16f
            val baseScale = max(width / source.width, height / source.height) * overscan
            val kenBurns = 1f + 0.05f * (0.5f + 0.5f * sin(elapsedSec * 0.06f))
            val drawWidth = source.width * baseScale * kenBurns
            val drawHeight = source.height * baseScale * kenBurns
            val shiftX = (offsetX - 0.5f) * width * 0.12f * config.parallax
            val driftY = sin(elapsedSec * 0.045f) * height * 0.014f
            val left = (width - drawWidth) * 0.5f + shiftX
            val top = (height - drawHeight) * 0.5f + driftY
            canvas.drawBitmap(source, null, RectF(left, top, left + drawWidth, top + drawHeight), bitmapPaint)
        }

        private fun ensureBitmap(): Bitmap? {
            val path = config.sourcePath ?: return null
            if (path == bitmapPath) {
                val cached = bitmap
                if (cached != null && !cached.isRecycled) return cached
            }
            recycleBitmap()
            if (!File(path).isFile) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val target = 2048
            while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = try {
                BitmapFactory.decodeFile(path, options)
            } catch (t: Throwable) {
                Lg.e(WallpaperSettings.TAG, "2d decode image failed", t)
                null
            }
            bitmap = decoded
            bitmapPath = if (decoded != null) path else null
            if (decoded != null) {
                Lg.i(WallpaperSettings.TAG, "2d image loaded ${decoded.width}x${decoded.height} sample=$sample")
            }
            return decoded
        }

        private fun recycleBitmap() {
            try {
                bitmap?.recycle()
            } catch (t: Throwable) {
                Lg.w(WallpaperSettings.TAG, "2d recycle bitmap failed: ${t.message}")
            }
            bitmap = null
            bitmapPath = null
        }

        /**
         * Canvas 与 MediaPlayer 争用同一个 BufferQueue 生产者：本引擎一旦用 Canvas 画过，
         * MediaPlayer 就无法再接管该 Surface（already connected）。此时退回特效并提示重选壁纸。
         */
        private fun onVideoUnavailableOnUsedSurface() {
            Lg.w(
                WallpaperSettings.TAG,
                "2d video needs wallpaper re-apply: canvas already owns surface, fallback to effect",
            )
            playbackMode = WallpaperSettings.MODE_EFFECT
            startCanvasLoop()
        }

        private fun startVideo() {
            val path = config.sourcePath
            if (path == null) {
                playbackMode = WallpaperSettings.MODE_EFFECT
                startCanvasLoop()
                return
            }
            if (canvasUsed) {
                onVideoUnavailableOnUsedSurface()
                return
            }
            releaseVideo()
            val player = MediaPlayer()
            try {
                player.setSurface(surfaceHolder.surface)
                player.setDataSource(path)
                player.isLooping = true
                player.setVolume(0f, 0f)
                player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                player.setOnPreparedListener { mp ->
                    if (mp !== mediaPlayer) return@setOnPreparedListener
                    videoPrepared = true
                    Lg.i(WallpaperSettings.TAG, "2d video prepared ${mp.duration}ms")
                    if (visible && surfaceReady) {
                        try {
                            mp.start()
                        } catch (t: Throwable) {
                            Lg.e(WallpaperSettings.TAG, "2d video start failed", t)
                        }
                    }
                }
                player.setOnErrorListener { mp, what, extra ->
                    Lg.e(WallpaperSettings.TAG, "2d video error what=$what extra=$extra")
                    if (mp === mediaPlayer) {
                        releaseVideo()
                        playbackMode = WallpaperSettings.MODE_EFFECT
                        startCanvasLoop()
                    }
                    true
                }
                player.prepareAsync()
                mediaPlayer = player
                playbackMode = WallpaperSettings.MODE_VIDEO
                Lg.i(WallpaperSettings.TAG, "2d video preparing $path")
            } catch (t: Throwable) {
                Lg.e(WallpaperSettings.TAG, "2d video prepare failed", t)
                try {
                    player.release()
                } catch (ignored: Throwable) {
                }
                playbackMode = WallpaperSettings.MODE_EFFECT
                startCanvasLoop()
            }
        }

        private fun releaseVideo() {
            videoPrepared = false
            val player = mediaPlayer
            mediaPlayer = null
            if (player != null) {
                try {
                    player.setOnPreparedListener(null)
                    player.setOnErrorListener(null)
                    player.stop()
                } catch (ignored: Throwable) {
                }
                try {
                    player.release()
                } catch (ignored: Throwable) {
                }
            }
        }

        private fun effectiveMode(): String = when (config.mode) {
            WallpaperSettings.MODE_IMAGE -> if (config.sourcePath != null) WallpaperSettings.MODE_IMAGE else WallpaperSettings.MODE_EFFECT
            WallpaperSettings.MODE_VIDEO -> if (config.sourcePath != null) WallpaperSettings.MODE_VIDEO else WallpaperSettings.MODE_EFFECT
            else -> WallpaperSettings.MODE_EFFECT
        }
    }
}
