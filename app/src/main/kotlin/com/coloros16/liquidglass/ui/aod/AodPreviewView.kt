package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.ContentLayerConfig
import com.coloros16.liquidglass.engine.Easing
import com.coloros16.liquidglass.engine.ElementPose
import com.coloros16.liquidglass.engine.MaskLayerConfig
import com.coloros16.liquidglass.engine.SceneLayerConfig
import com.coloros16.liquidglass.engine.SceneSample
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneTimeline
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.StyleCapabilities
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionEvaluator
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.engine.TransitionProfile
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import java.time.LocalDateTime
import java.util.Random
import kotlin.math.min

/** 三态预览：息屏 / 锁屏 / 桌面，按引擎给的边与 t 求值，渲染同一份世界。 */
class AodPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var style: AodStyle = AodStyleCatalog.find(null)
        set(value) {
            field = value
            applyStyle(value)
            invalidate()
        }

    var useSystemWallpaper: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var scene: SceneState = SceneState.AOD
    private var edge: TransitionEdge? = null
    private var t: Float = 0f
    private var sceneSpec: AodSceneSpec? = null
    private var scrubP = -1f
    private val clockMachine = ClockStateMachine(easing = Easing.STANDARD)
    private val sceneSample = SceneSample()
    private val layers = SystemSceneMotion.Layers()

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val circle = Path()
    private val rect = RectF()
    private val dst = RectF()
    private val src = Rect()
    private var wallpaper: Bitmap? = null
    private var sceneSky: Bitmap? = null
    private var sceneSkyLoading = false
    private var uiScale = 1f
    private val path = Path()

    private val clockThin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val vendorClock: Typeface? by lazy {
        runCatching { Typeface.createFromAsset(context.assets, "aod/clock_thin.ttf") }.getOrNull()
    }
    private val clockLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private var timeText = "12:28"
    private var dateText = "11月16日 周日"

    private val minuteTick = object : Runnable {
        override fun run() {
            refreshTime()
            invalidate()
            scheduleMinuteTick()
        }
    }

    private val maskProfile = TransitionProfile(
        scene = SceneLayerConfig(enabled = false),
        mask = MaskLayerConfig(enabled = true, easing = Easing.STANDARD),
        content = ContentLayerConfig(enabled = false, easing = Easing.STANDARD),
    )
    private val contentProfile = TransitionProfile(
        scene = SceneLayerConfig(enabled = false),
        mask = MaskLayerConfig(enabled = false, easing = Easing.STANDARD),
        content = ContentLayerConfig(enabled = true, easing = Easing.STANDARD),
    )
    private val evaluator = TransitionEvaluator(
        profiles = hashMapOf(
            TransitionEdge.AOD_TO_LOCK to maskProfile,
            TransitionEdge.LOCK_TO_AOD to maskProfile,
            TransitionEdge.LOCK_TO_HOME to contentProfile,
            TransitionEdge.HOME_TO_LOCK to contentProfile,
            TransitionEdge.AOD_TO_HOME to contentProfile,
            TransitionEdge.HOME_TO_AOD to contentProfile,
        ),
        defaultProfile = contentProfile,
    )
    private val capabilities = StyleCapabilities(mask = true, crossfade = true)
    private val params = TransitionParams()
    private var screen = ScreenMetrics(1f, 1f, density)

    private data class PreviewApp(
        val label: String,
        val pkg: String,
        val top: Int,
        val bottom: Int,
    )

    private val previewApps = listOf(
        PreviewApp("消息", "com.tencent.mm", 0xFF43D06E.toInt(), 0xFF1E9E4A.toInt()),
        PreviewApp("视频", "tv.danmaku.bili", 0xFFF58FBF.toInt(), 0xFFDD5E8C.toInt()),
        PreviewApp("购物", "com.taobao.taobao", 0xFFFFA45C.toInt(), 0xFFEE641F.toInt()),
        PreviewApp("动态", "com.sina.weibo", 0xFFFFD15C.toInt(), 0xFFF0A61C.toInt()),
        PreviewApp("相机", "com.oplus.camera", 0xFF5A6472.toInt(), 0xFF262B33.toInt()),
        PreviewApp("天气", "com.coloros.weather2", 0xFF5FBCFF.toInt(), 0xFF2C7EE6.toInt()),
        PreviewApp("便签", "com.coloros.note", 0xFFFFD35C.toInt(), 0xFFEDAF1C.toInt()),
        PreviewApp("禅定", "com.coloros.relax", 0xFF5A9BFF.toInt(), 0xFF2C62D6.toInt()),
        PreviewApp("计算", "com.coloros.calculator", 0xFF5A6472.toInt(), 0xFF262B33.toInt()),
        PreviewApp("文件", "com.coloros.filemanager", 0xFF5FBCFF.toInt(), 0xFF2C7EE6.toInt()),
        PreviewApp("录音", "com.coloros.recorder", 0xFF4A5058.toInt(), 0xFFD8575F.toInt()),
        PreviewApp("设置", "com.coloros.settings", 0xFF6A7480.toInt(), 0xFF333A42.toInt()),
        PreviewApp("电话", "com.android.contacts", 0xFF4ADE80.toInt(), 0xFF22A34C.toInt()),
        PreviewApp("信息", "com.android.mms", 0xFF7ED0A8.toInt(), 0xFF2EA063.toInt()),
        PreviewApp("浏览器", "com.heytap.browser", 0xFF8CC2FF.toInt(), 0xFF3F86E8.toInt()),
        PreviewApp("相机", "com.oplus.camera", 0xFF5A6472.toInt(), 0xFF262B33.toInt()),
    )

    private val appIcons: Array<Drawable?> = arrayOfNulls(previewApps.size)
    private val appLabels: Array<String?> = arrayOfNulls(previewApps.size)
    private var iconsRequested = false

    private val stars = FloatArray(96 * 4)
    private val moonMarks = FloatArray(38 * 4)

    private val cloudBlobs = floatArrayOf(
        0.06f, 0.88f, 0.46f, 0.11f,
        0.28f, 0.92f, 0.52f, 0.09f,
        0.52f, 0.86f, 0.58f, 0.10f,
        0.78f, 0.91f, 0.50f, 0.08f,
        0.18f, 0.74f, 0.34f, 0.05f,
        0.64f, 0.76f, 0.42f, 0.05f,
    )

    private val backRidge = floatArrayOf(
        0.00f, 0.792f, 0.16f, 0.716f, 0.31f, 0.792f, 0.47f, 0.678f,
        0.62f, 0.772f, 0.79f, 0.702f, 1.00f, 0.786f,
    )

    private val frontRidge = floatArrayOf(
        0.00f, 0.900f, 0.19f, 0.842f, 0.38f, 0.918f, 0.55f, 0.826f,
        0.71f, 0.906f, 0.86f, 0.852f, 1.00f, 0.918f,
    )

    init {
        applyStyle(style)
        refreshTime()
        val rnd = Random(11L)
        var i = 0
        while (i < stars.size) {
            stars[i] = rnd.nextFloat()
            stars[i + 1] = rnd.nextFloat() * 0.60f
            stars[i + 2] = 0.6f + rnd.nextFloat() * 1.35f
            stars[i + 3] = 0.18f + rnd.nextFloat() * 0.82f
            i += 4
        }
        i = 0
        while (i < moonMarks.size) {
            val angle = rnd.nextFloat() * 6.2831855f
            val dist = (0.10f + rnd.nextFloat() * 0.52f)
            moonMarks[i] = (kotlin.math.cos(angle) * dist).coerceIn(-0.82f, 0.82f)
            moonMarks[i + 1] = (kotlin.math.sin(angle) * dist).coerceIn(-0.82f, 0.82f)
            moonMarks[i + 2] = 0.018f + rnd.nextFloat() * 0.062f
            moonMarks[i + 3] = 0.06f + rnd.nextFloat() * 0.10f
            i += 4
        }
    }

    private fun applyStyle(value: AodStyle) {
        sceneSpec = AodSceneCatalog.forStyle(value.id)
        applyProfiles(value)
        val spec = sceneSpec ?: return
        for ((state, anchor) in spec.clockAnchors) {
            clockMachine.setAnchor(state, anchor, 0L, follow = false)
        }
        clockMachine.snapTo(scene, 0L)
    }

    private fun applyProfiles(value: AodStyle) {
        maskProfile.mask.centerXFraction = value.maskCenterX
        maskProfile.mask.centerYFraction = value.maskCenterY
        val maskEdge = if (value.transition == AodTransition.MASK) maskProfile else contentProfile
        evaluator.setProfile(TransitionEdge.AOD_TO_LOCK, maskEdge)
        evaluator.setProfile(TransitionEdge.LOCK_TO_AOD, maskEdge)
        evaluator.setProfile(TransitionEdge.LOCK_TO_HOME, contentProfile)
        evaluator.setProfile(TransitionEdge.HOME_TO_LOCK, contentProfile)
        evaluator.setProfile(TransitionEdge.AOD_TO_HOME, contentProfile)
        evaluator.setProfile(TransitionEdge.HOME_TO_AOD, contentProfile)
    }

    /** 设置系统壁纸底图（接管壁纸时的背景）。 */
    fun setWallpaper(bitmap: Bitmap?) {
        wallpaper = bitmap
        invalidate()
    }

    /** 刷新当前态 / 边 / 进度。 */
    fun render(scene: SceneState, edge: TransitionEdge?, t: Float) {
        this.scene = scene
        this.edge = edge
        this.t = t.coerceIn(0f, 1f)
        this.scrubP = -1f
        if (sceneSpec != null) {
            if (edge == null) {
                clockMachine.snapTo(scene, 0L)
            } else {
                clockMachine.onTransition(edge, this.t, SystemSceneMotion.clockCurve(edge))
            }
        }
        invalidate()
    }

    /** 整轨拖动：p=0 息屏 / 0.5 锁屏 / 1 桌面，画面与手指 1:1，不走缓动。 */
    fun renderScrub(p: Float) {
        scrubP = p.coerceIn(0f, 1f)
        if (sceneSpec != null) clockMachine.onTimeline(scrubP)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = Color.BLACK
        canvas.drawRect(0f, 0f, w, h, fill)

        val spec = sceneSpec
        if (spec != null) {
            drawSceneWorld(canvas, w, h, spec)
            return
        }

        val rawEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        val rawT = if (scrubP >= 0f) SceneTimeline.localT(scrubP) else t
        val e = rawEdge
        if (e == null) {
            drawState(canvas, scene, w, h, 1f)
        } else {
            val evalEdge = if (e == TransitionEdge.LOCK_TO_AOD) TransitionEdge.AOD_TO_LOCK else e
            val evalT = if (e == TransitionEdge.LOCK_TO_AOD) 1f - rawT else rawT
            evaluator.evaluate(evalEdge, evalT, screen, capabilities, capabilities, params, null, scrubP >= 0f)

            if (params.srcAlpha > 0.002f) {
                val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (params.srcAlpha * 255f).toInt())
                canvas.translate(params.srcDx, params.srcDy)
                canvas.scale(params.srcScale, params.srcScale, w / 2f, h / 2f)
                drawState(canvas, evalEdge.from, w, h, 1f)
                canvas.restoreToCount(layer)
            }

            if (params.maskEnabled && params.maskRadius > 0.5f) {
                circle.reset()
                circle.addCircle(params.maskCenterX, params.maskCenterY, params.maskRadius, Path.Direction.CW)
                val target = canvas.save()
                canvas.clipPath(circle)
                canvas.translate(params.dstDx, params.dstDy)
                canvas.scale(params.dstScale, params.dstScale, w / 2f, h / 2f)
                drawState(canvas, evalEdge.to, w, h, 1f)
                canvas.restoreToCount(target)
            } else if (evalT >= 0.9999f) {
                drawState(canvas, evalEdge.to, w, h, 1f)
            } else if (params.dstAlpha > 0.002f) {
                val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (params.dstAlpha * 255f).toInt())
                canvas.translate(params.dstDx, params.dstDy)
                canvas.scale(params.dstScale, params.dstScale, w / 2f, h / 2f)
                drawState(canvas, evalEdge.to, w, h, 1f)
                canvas.restoreToCount(layer)
            }
        }
    }

    private fun drawState(canvas: Canvas, state: SceneState, w: Float, h: Float, alpha: Float) {
        when (state) {
            SceneState.AOD -> drawAod(canvas, w, h, alpha)
            SceneState.LOCK -> drawLock(canvas, w, h, alpha)
            SceneState.HOME -> drawHome(canvas, w, h, alpha)
        }
    }

    // ---------------------------------------------------------------- 息屏态

    private fun drawAod(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val layer = if (alpha < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (alpha * 255f).toInt())
        } else {
            -1
        }
        val full = style.form == AodForm.FULL
        fill.style = Paint.Style.FILL
        fill.shader = if (full) {
            LinearGradient(
                0f, 0f, w * 0.4f, h,
                intArrayOf(0xFF0C1018.toInt(), 0xFF050709.toInt(), 0xFF000000.toInt()),
                floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP,
            )
        } else {
            null
        }
        fill.color = Color.BLACK
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        if (full) {
            radial(canvas, w * 0.78f, h * 0.20f, w * 0.85f, alphaColor(style.accent, 0.10f), 0x00000000)
            radial(canvas, w * 0.20f, h * 0.88f, w * 0.95f, alphaColor(style.accent, 0.06f), 0x00000000)
            drawBokeh(canvas, w, h, 0.55f)
        }

        if (!full) {
            drawAodFrame(canvas, w, h)
        }

        if (style.id == AodStyleCatalog.ID_PORTRAIT) {
            radial(canvas, w / 2f, h * 0.96f, w * 0.30f, alphaColor(style.accent, 0.16f), 0x00000000)
        }

        if (style.id == AodStyleCatalog.ID_TEXT) {
            text.typeface = clockLight
            text.textSize = sp(17f)
            text.letterSpacing = 0.22f
            text.textAlign = Paint.Align.CENTER
            text.color = alphaColor(style.accent, 0.78f)
            canvas.drawText("晚安，好梦", w / 2f, h * 0.5f - (if (full) h * 0.20f else h * 0.16f), text)
        }

        val base = if (full) h * 0.198f else h * 0.142f
        val centerY = if (full) h * 0.50f else h * 0.52f
        val timeColor = when (style.id) {
            AodStyleCatalog.ID_STARMOON -> 0xFFC9D8EC.toInt()
            AodStyleCatalog.ID_TEXT -> 0xFFCBE3D7.toInt()
            AodStyleCatalog.ID_PORTRAIT -> 0xFFD8CCEA.toInt()
            else -> if (full) 0xFFD3DCE8.toInt() else 0xFFB7BDC4.toInt()
        }
        drawClockBlock(canvas, w / 2f, centerY, base, timeColor, 0.58f)

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun drawAodFrame(canvas: Canvas, w: Float, h: Float) {
        val frame = r(dp(22f), dp(26f), w - dp(22f), h - dp(26f))
        val radius = if (style.id == AodStyleCatalog.ID_HANDPAINT) dp(28f) else dp(24f)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = dp(1f)
        stroke.shader = null
        stroke.color = when (style.id) {
            AodStyleCatalog.ID_SAIL -> 0x2EC9A227
            AodStyleCatalog.ID_HANDPAINT -> 0x59E8D8B0
            else -> 0x1FFFFFFF
        }
        stroke.pathEffect = if (style.id == AodStyleCatalog.ID_HANDPAINT) {
            DashPathEffect(floatArrayOf(dp(7f), dp(6f)), 0f)
        } else {
            null
        }
        canvas.drawRoundRect(frame, radius, radius, stroke)
        stroke.pathEffect = null

        if (style.id == AodStyleCatalog.ID_SAIL) {
            radial(canvas, w / 2f, h - dp(30f), w * 0.50f, alphaColor(0xFFC9A227.toInt(), 0.16f), 0x00C9A227)
        }
    }

    // ---------------------------------------------------------------- 锁屏态

    private fun drawLock(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val layer = if (alpha < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (alpha * 255f).toInt())
        } else {
            -1
        }
        if (!drawWallpaperBackground(canvas, w, h, 0.30f)) {
            drawAmbientWallpaper(canvas, w, h)
            fill.style = Paint.Style.FILL
            fill.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0x59000000, 0x00000000, 0x52000000),
                floatArrayOf(0f, 0.34f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }

        drawClockBlock(canvas, w / 2f, h * 0.205f, h * 0.152f, 0xFFF3F6F9.toInt(), 0.66f)
        drawLockOverlay(canvas, w, h, 1f)

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    // ---------------------------------------------------------------- 桌面态

    private fun drawHome(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val layer = if (alpha < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (alpha * 255f).toInt())
        } else {
            -1
        }
        if (!drawWallpaperBackground(canvas, w, h, 0.16f)) {
            drawAmbientWallpaper(canvas, w, h)
            fill.style = Paint.Style.FILL
            fill.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0x33000000, 0x00000000, 0x2E000000),
                floatArrayOf(0f, 0.40f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }

        drawClockBlock(canvas, w / 2f, h * 0.098f, h * 0.104f, 0xFFF3F6F9.toInt(), 0.62f)
        drawHomeWeather(canvas, w, h)

        val dockH = h * 0.098f
        val dockTop = h * 0.686f
        drawAppGrid(canvas, w, h * 0.268f, dockTop - dp(30f))
        drawHomeDots(canvas, w, dockTop)
        drawDock(canvas, w, dockTop, dockH)

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    // ---------------------------------------------------------------- 连续场景

    private fun drawSceneWorld(canvas: Canvas, w: Float, h: Float, spec: AodSceneSpec) {
        val u = if (scrubP >= 0f) {
            scrubP
        } else {
            SceneTimeline.uOf(edge, t, scene, Easing.LINEAR)
        }
        sampleLayers()
        sceneSample.sample(spec.contract, u)

        val realWallpaper = if (useSystemWallpaper) wallpaper else null
        val sky = realWallpaper ?: sceneSky
        if (sky != null) {
            val parallax = if (realWallpaper != null) 0f else 1f
            drawBitmapCover(
                canvas, sky, w, h,
                layers.wallpaperScale,
                (0.5f - sceneSample.cameraX) * w * 0.05f * parallax,
                (0.5f - sceneSample.cameraY) * h * 0.03f * parallax,
            )
        } else {
            drawSceneSky(canvas, w, h, u)
            drawMilkyWay(canvas, w, h)
            drawSceneStars(canvas, w, h)
        }
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = alphaColor(0xFF02040A.toInt(), layers.dim)
        canvas.drawRect(0f, 0f, w, h, fill)

        if (realWallpaper == null) {
            drawClouds(canvas, w, h, 0.85f, 0)
            drawMountains(canvas, w, h, u)

            if (layers.aodAlpha > 0.002f) {
                val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (layers.aodAlpha * 255f).toInt())
                sceneSample.elements.getOrNull(0)?.let { drawSceneMoon(canvas, w, h, it) }
                canvas.restoreToCount(layer)
            }
            drawClouds(canvas, w, h, 0.45f, 1)
        }

        if (layers.lockAlpha > 0.002f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (layers.lockAlpha * 255f).toInt())
            canvas.scale(layers.lockScale, layers.lockScale, w / 2f, h * 0.40f)
            drawLockOverlay(canvas, w, h, 1f, lockShiftDp(u))
            canvas.restoreToCount(layer)
        }

        if (layers.homeAlpha > 0.002f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (layers.homeAlpha * 255f).toInt())
            canvas.scale(layers.homeScale, layers.homeScale, w / 2f, h * 0.52f)
            drawSceneHomeIcons(canvas, w, h, 1f)
            drawSceneHomeDock(canvas, w, h, 1f)
            canvas.restoreToCount(layer)
        }

        drawSceneClock(canvas, w, h, u)
        val chrome = maxOf(layers.lockAlpha, layers.homeAlpha)
        if (chrome > 0.002f) drawGestureBar(canvas, w, h, chrome * 0.7f)
        drawVignette(canvas, w, h)
    }

    private fun sampleLayers() {
        when {
            scrubP >= 0f -> SystemSceneMotion.sampleTimeline(scrubP, layers)
            edge != null -> SystemSceneMotion.sample(edge!!, t, layers)
            else -> SystemSceneMotion.sampleStatic(scene, layers)
        }
    }

    private fun drawBitmapCover(
        canvas: Canvas,
        bitmap: Bitmap,
        w: Float,
        h: Float,
        zoom: Float,
        dx: Float,
        dy: Float,
    ) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = maxOf(w / bw, h / bh) * zoom
        val dw = bw * scale
        val dh = bh * scale
        src.set(0, 0, bitmap.width, bitmap.height)
        dst.set((w - dw) / 2f + dx, (h - dh) / 2f + dy, (w + dw) / 2f + dx, (h + dh) / 2f + dy)
        canvas.drawBitmap(bitmap, src, dst, fill)
    }

    private fun drawSceneSky(canvas: Canvas, w: Float, h: Float, u: Float) {
        val top = mix3(0xFF03050A.toInt(), 0xFF060B16.toInt(), 0xFF0A1426.toInt(), u)
        val mid = mix3(0xFF070D18.toInt(), 0xFF0C1626.toInt(), 0xFF132340.toInt(), u)
        val low = mix3(0xFF0F1A2B.toInt(), 0xFF1B2C49.toInt(), 0xFF2B4268.toInt(), u)
        val glow = mix3(0xFF1B2739.toInt(), 0xFF2C4467.toInt(), 0xFF48678F.toInt(), u)
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(top, mid, low, glow),
            floatArrayOf(0f, 0.46f, 0.80f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        radial(canvas, w * 0.50f, h * 1.04f, w * 0.85f, alphaColor(0xFF9FB4D8.toInt(), 0.10f), 0x009FB4D8)
        radial(canvas, w * 0.76f, h * 0.30f, w * 0.70f, alphaColor(0xFF6E86B8.toInt(), 0.06f), 0x006E86B8)
    }

    private fun drawMilkyWay(canvas: Canvas, w: Float, h: Float) {
        val alpha = (1f - sceneSample.light * 0.85f).coerceIn(0.05f, 1f) * 0.55f
        val save = canvas.save()
        canvas.rotate(-24f, w * 0.52f, h * 0.34f)
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(
            0f, h * 0.16f, 0f, h * 0.52f,
            intArrayOf(
                0x00B9D0FF,
                Color.argb((alpha * 58f).toInt(), 205, 219, 245),
                Color.argb((alpha * 34f).toInt(), 205, 219, 245),
                0x00B9D0FF,
            ),
            floatArrayOf(0f, 0.32f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(-w * 0.3f, h * 0.16f, w * 1.3f, h * 0.52f, fill)
        fill.shader = null
        canvas.restoreToCount(save)
    }

    private fun drawSceneStars(canvas: Canvas, w: Float, h: Float) {
        val alphaBase = (1f - sceneSample.light * 0.86f).coerceIn(0.06f, 1f)
        val zoom = sceneSample.cameraScale
        val dx = (0.5f - sceneSample.cameraX) * w * 0.20f
        val dy = (0.5f - sceneSample.cameraY) * h * 0.12f
        fill.style = Paint.Style.FILL
        fill.shader = null
        var i = 0
        while (i < stars.size) {
            val nx = stars[i]
            val ny = stars[i + 1]
            val radius = stars[i + 2]
            val brightness = stars[i + 3]
            val fade = if (ny > 0.46f) 1f - (ny - 0.46f) / 0.14f * 0.82f else 1f
            val a = (alphaBase * brightness * fade * 255f).toInt().coerceIn(0, 255)
            if (a > 4) {
                val sx = ((nx - 0.5f) * zoom + 0.5f) * w + dx
                val sy = ((ny - 0.5f) * zoom + 0.5f) * h + dy
                val starR = radius * 0.55f * density * uiScale * 0.5f
                fill.color = Color.argb(a, 226, 236, 252)
                canvas.drawCircle(sx, sy, starR, fill)
                if (brightness > 0.90f) {
                    fill.color = Color.argb(a / 8, 210, 228, 255)
                    canvas.drawCircle(sx, sy, starR * 3.2f, fill)
                }
            }
            i += 4
        }
    }

    private fun drawClouds(canvas: Canvas, w: Float, h: Float, strength: Float, layer: Int) {
        val parallax = (0.5f - sceneSample.cameraX) * w * 0.34f
        val vertical = (0.5f - sceneSample.cameraY) * h * 0.16f
        var i = 0
        while (i < cloudBlobs.size) {
            val cx = cloudBlobs[i] * w + parallax * (if (layer == 0) 0.65f else 1f)
            val cy = cloudBlobs[i + 1] * h + vertical * (if (layer == 0) 0.5f else 0.8f)
            val rx = cloudBlobs[i + 2] * w
            val ry = rx * 0.17f
            val a = cloudBlobs[i + 3] * strength * (if (layer == 0) 1f else 0.8f)
            if (a > 0.002f) {
                val save = canvas.save()
                canvas.translate(cx, cy)
                canvas.scale(1f, ry / rx)
                fill.style = Paint.Style.FILL
                fill.shader = RadialGradient(
                    0f, 0f, rx,
                    intArrayOf(
                        Color.argb((a * 255f).toInt(), 158, 176, 208),
                        Color.argb((a * 120f).toInt(), 138, 156, 190),
                        0x0080919F,
                    ),
                    floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(0f, 0f, rx, fill)
                fill.shader = null
                canvas.restoreToCount(save)
            }
            i += 4
        }
    }

    private fun drawMountains(canvas: Canvas, w: Float, h: Float, u: Float) {
        val drift = (0.47f - sceneSample.cameraY) * h * 0.42f
        val backTop = mix3(0xFF0B1220.toInt(), 0xFF101A2D.toInt(), 0xFF16233C.toInt(), u)
        val backBottom = mix3(0xFF05080F.toInt(), 0xFF080C15.toInt(), 0xFF0A1020.toInt(), u)
        buildRidge(backRidge, w, h, drift)
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(
            0f, h * 0.66f + drift, 0f, h,
            intArrayOf(backTop, backBottom), null, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, fill)
        fill.shader = null

        val frontTop = mix3(0xFF060A10.toInt(), 0xFF080D16.toInt(), 0xFF0C1220.toInt(), u)
        buildRidge(frontRidge, w, h, drift)
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(
            0f, h * 0.78f + drift, 0f, h,
            intArrayOf(frontTop, 0xFF010204.toInt()), null, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, fill)
        fill.shader = null
    }

    private fun buildRidge(points: FloatArray, w: Float, h: Float, drift: Float) {
        path.reset()
        path.moveTo(-w * 0.06f, h * 1.06f)
        path.lineTo(-w * 0.06f, h * points[1] + drift)
        var i = 2
        while (i < points.size) {
            val prevX = w * points[i - 2]
            val prevY = h * points[i - 1] + drift
            val nextX = w * points[i]
            val nextY = h * points[i + 1] + drift
            val midX = (prevX + nextX) / 2f
            path.cubicTo(midX, prevY, midX, nextY, nextX, nextY)
            i += 2
        }
        path.lineTo(w * 1.06f, h * 1.06f)
        path.close()
    }

    private fun drawSceneMoon(canvas: Canvas, w: Float, h: Float, pose: ElementPose) {
        val radius = w * 0.355f * pose.scale
        val cx = pose.x * w
        val cy = pose.y * h

        radial(canvas, cx, cy, radius * 2.0f, alphaColor(0xFFA7C2EE.toInt(), 0.07f), 0x00A7C2EE)
        radial(canvas, cx, cy, radius * 1.15f, alphaColor(0xFFDCE7F7.toInt(), 0.11f), 0x00DCE7F7)

        fill.style = Paint.Style.FILL
        fill.shader = RadialGradient(
            cx - radius * 0.30f, cy - radius * 0.38f, radius * 1.30f,
            intArrayOf(
                0xFFFFFFFF.toInt(), 0xFFFEF9EF.toInt(), 0xFFF1E7D5.toInt(),
                0xFFD5C8B2.toInt(), 0xFFA79B87.toInt(), 0xFF6E665A.toInt(),
            ),
            floatArrayOf(0f, 0.22f, 0.46f, 0.66f, 0.85f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null

        path.reset()
        path.addCircle(cx, cy, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(path)

        radial(canvas, cx - radius * 0.28f, cy - radius * 0.06f, radius * 0.58f, 0x3D2A2519, 0x00272319)
        radial(canvas, cx + radius * 0.22f, cy + radius * 0.32f, radius * 0.50f, 0x382A2519, 0x00272319)
        radial(canvas, cx + radius * 0.32f, cy - radius * 0.24f, radius * 0.42f, 0x2B2A2519, 0x00272319)
        radial(canvas, cx - radius * 0.46f, cy + radius * 0.34f, radius * 0.46f, 0x33262118, 0x00262118)

        var i = 0
        while (i < moonMarks.size) {
            val mx = cx + moonMarks[i] * radius
            val my = cy + moonMarks[i + 1] * radius
            val mr = moonMarks[i + 2] * radius
            val ma = moonMarks[i + 3]
            fill.style = Paint.Style.FILL
            fill.shader = RadialGradient(
                mx, my, mr * 1.55f,
                intArrayOf(
                    Color.argb((ma * 255f).toInt(), 68, 60, 49),
                    Color.argb((ma * 70f).toInt(), 124, 113, 96),
                    0x00786E60,
                ),
                floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(mx, my, mr * 1.55f, fill)
            fill.shader = null
            i += 4
        }

        radial(canvas, cx - radius * 0.44f, cy - radius * 0.50f, radius * 0.86f, 0x24FFFFFF, 0x00FFFFFF)

        fill.shader = LinearGradient(
            cx + radius * 0.95f, cy - radius * 0.75f, cx - radius * 0.85f, cy + radius * 0.95f,
            intArrayOf(0x00000000, 0x12000000, 0x8F05070C.toInt()),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null

        fill.shader = RadialGradient(
            cx + radius * 0.52f, cy + radius * 0.42f, radius * 1.18f,
            intArrayOf(0xE60A0E16.toInt(), 0xC80A0E16.toInt(), 0x000A0E16),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null
        canvas.restoreToCount(save)

        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = dp(1.2f)
        stroke.shader = LinearGradient(
            cx - radius, cy - radius, cx + radius, cy + radius,
            intArrayOf(0x33FFFFFF, 0x0DFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius - dp(0.6f), stroke)
        stroke.shader = null

        path.reset()
        path.addOval(
            r(cx - radius * 1.05f, cy + radius * 0.42f, cx + radius * 1.05f, cy + radius * 0.92f),
            Path.Direction.CW,
        )
        val veil = canvas.save()
        canvas.clipPath(path)
        fill.style = Paint.Style.FILL
        fill.shader = RadialGradient(
            cx, cy + radius * 0.64f, radius * 0.90f,
            intArrayOf(Color.argb(46, 168, 186, 218), Color.argb(20, 150, 168, 200), 0x0096A8C8),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy + radius * 0.64f, radius * 0.90f, fill)
        fill.shader = null
        canvas.restoreToCount(veil)
    }

    private fun drawSceneHomeIcons(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val layer = if (alpha < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (alpha * 255f).toInt())
        } else {
            canvas.save()
        }
        val scale = 0.97f + 0.03f * alpha.coerceIn(0f, 1f)
        canvas.scale(scale, scale, w / 2f, h * 0.52f)
        val dockH = h * 0.098f
        val dockTop = h * 0.686f
        drawHomeWeather(canvas, w, h)
        drawAppGrid(canvas, w, h * 0.268f, dockTop - dp(30f))
        drawHomeDots(canvas, w, dockTop)
        canvas.restoreToCount(layer)
    }

    private fun drawSceneHomeDock(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val layer = if (alpha < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (alpha * 255f).toInt())
        } else {
            canvas.save()
        }
        val dockH = h * 0.098f
        drawDock(canvas, w, h * 0.686f, dockH)
        canvas.restoreToCount(layer)
    }

    /** 单一时钟层：三态共用一份文本，只按姿态缩放与换色，不被遮罩裁剪。 */
    private fun drawSceneClock(canvas: Canvas, w: Float, h: Float, u: Float) {
        val pose = clockMachine.pose
        if (!pose.visible) return
        val base = h * 0.145f * pose.scale * layers.aodClockScale
        val cx = pose.centerX * w
        val cy = pose.centerY * h
        val timeColor = mix3(0xFFC9D4E2.toInt(), 0xFFF0F4F8.toInt(), 0xFFF5F8FB.toInt(), u)
        val layer = if (pose.alpha < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (pose.alpha.coerceIn(0f, 1f) * 255f).toInt())
        } else {
            -1
        }
        val rotated = if (pose.rotationDeg != 0f) {
            canvas.save().also { canvas.rotate(pose.rotationDeg, cx, cy) }
        } else {
            -1
        }
        drawClockBlock(canvas, cx, cy, base, timeColor, 0.62f)
        if (rotated >= 0) canvas.restoreToCount(rotated)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    // ---------------------------------------------------------------- 通用部件

    private fun drawClockBlock(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        base: Float,
        timeColor: Int,
        dateAlpha: Float,
    ) {
        text.typeface = vendorClock ?: clockThin
        text.textSize = base
        text.letterSpacing = -0.018f
        text.textAlign = Paint.Align.CENTER
        text.color = timeColor
        text.alpha = 255
        val fm = text.fontMetrics
        canvas.drawText(timeText, cx, cy - (fm.ascent + fm.descent) / 2f, text)

        text.typeface = sansMedium
        text.textSize = base * 0.132f
        text.letterSpacing = 0.10f
        text.color = 0xFFE7EDF4.toInt()
        text.alpha = (dateAlpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawText(dateText, cx, cy + base * 0.64f, text)
        text.alpha = 255
    }

    /** 锁屏信息进出场位移：淡入时从下方 12dp 上来，淡出时向上 24dp 让位桌面。 */
    private fun lockShiftDp(u: Float): Float = when {
        u < 0.30f -> dp(12f)
        u < 0.42f -> dp(12f) * (1f - (u - 0.30f) / 0.12f)
        u > 0.62f -> -dp(24f) * ((u - 0.62f) / 0.06f).coerceIn(0f, 1f)
        else -> 0f
    }

    private fun drawLockOverlay(canvas: Canvas, w: Float, h: Float, alpha: Float, shift: Float = 0f) {
        val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (alpha.coerceIn(0f, 1f) * 255f).toInt())
        if (shift != 0f) canvas.translate(0f, shift)

        val weatherY = h * 0.375f
        val glyphR = dp(9f)
        val glyphX = dp(24f) + glyphR
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = 0xFFFFD479.toInt()
        canvas.drawCircle(glyphX, weatherY - dp(1f), glyphR, fill)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = dp(1.2f)
        stroke.shader = null
        stroke.pathEffect = null
        stroke.color = alphaColor(0xFFFFD479.toInt(), 0.55f)
        var ray = 0
        while (ray < 8) {
            val angle = Math.toRadians((ray * 45).toDouble())
            val ix = glyphX + (glyphR + dp(2.5f)) * Math.cos(angle).toFloat()
            val iy = weatherY - dp(1f) + (glyphR + dp(2.5f)) * Math.sin(angle).toFloat()
            val ox = glyphX + (glyphR + dp(5f)) * Math.cos(angle).toFloat()
            val oy = weatherY - dp(1f) + (glyphR + dp(5f)) * Math.sin(angle).toFloat()
            canvas.drawLine(ix, iy, ox, oy, stroke)
            ray++
        }

        text.typeface = sansMedium
        text.textSize = sp(14f)
        text.letterSpacing = 0.02f
        text.textAlign = Paint.Align.LEFT
        text.color = 0xFFF2F6FA.toInt()
        canvas.drawText("晴 13°", glyphX + glyphR + dp(28f), weatherY + dp(5f), text)

        text.typeface = sans
        text.textSize = sp(12f)
        text.letterSpacing = 0.04f
        text.textAlign = Paint.Align.RIGHT
        text.color = 0x9FE8EEF5.toInt()
        canvas.drawText("北京市", w - dp(24f), weatherY + dp(5f), text)
        text.textAlign = Paint.Align.LEFT

        drawNotificationCard(canvas, w, h * 0.415f, 0)
        drawNotificationCard(canvas, w, h * 0.523f, 1)
        drawMusicCard(canvas, w, h * 0.635f)

        canvas.restoreToCount(layer)
    }

    private fun drawNotificationCard(canvas: Canvas, w: Float, h: Float, index: Int) {
        val cardH = dp(64f)
        val left = dp(16f)
        val right = w - dp(16f)
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = if (index == 0) 0x26FFFFFF else 0x1CFFFFFF
        canvas.drawRoundRect(r(left, h, right, h + cardH), dp(22f), dp(22f), fill)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = dp(1f)
        stroke.pathEffect = null
        stroke.color = if (index == 0) 0x2BFFFFFF else 0x1FFFFFFF
        canvas.drawRoundRect(r(left, h, right, h + cardH), dp(22f), dp(22f), stroke)

        val iconSize = dp(34f)
        val iconCx = left + dp(20f) + iconSize / 2f
        val iconCy = h + cardH / 2f
        drawAppIcon(canvas, index, iconCx, iconCy, iconSize)

        text.typeface = sansMedium
        text.textSize = sp(13f)
        text.letterSpacing = 0.02f
        text.textAlign = Paint.Align.LEFT
        text.color = 0xFFF4F7FA.toInt()
        val textX = iconCx + iconSize / 2f + dp(12f)
        val titleBaseline = h + dp(25f)
        canvas.drawText(appLabels.getOrNull(index) ?: previewApps[index].label, textX, titleBaseline, text)

        text.typeface = sans
        text.textSize = sp(11.5f)
        text.letterSpacing = 0f
        text.color = 0xB3E8EEF5.toInt()
        canvas.drawText(
            if (index == 0) "妈：记得按时吃饭" else "有 2 条新内容待查看",
            textX, titleBaseline + dp(17f), text,
        )

        text.typeface = sans
        text.textSize = sp(10.5f)
        text.textAlign = Paint.Align.RIGHT
        text.color = 0x80FFFFFF.toInt()
        canvas.drawText(if (index == 0) "现在" else "8 分钟前", right - dp(16f), titleBaseline - dp(2f), text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawMusicCard(canvas: Canvas, w: Float, top: Float) {
        val cardH = dp(62f)
        val left = dp(16f)
        val right = w - dp(16f)
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = 0x1AFFFFFF
        canvas.drawRoundRect(r(left, top, right, top + cardH), dp(22f), dp(22f), fill)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = dp(1f)
        stroke.pathEffect = null
        stroke.color = 0x1FFFFFFF
        canvas.drawRoundRect(r(left, top, right, top + cardH), dp(22f), dp(22f), stroke)

        val art = dp(40f)
        val artCx = left + dp(16f) + art / 2f
        val artCy = top + cardH / 2f
        fill.shader = LinearGradient(
            artCx - art / 2f, artCy - art / 2f, artCx + art / 2f, artCy + art / 2f,
            intArrayOf(0xFF8FA3E0.toInt(), 0xFF44559A.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(
            r(artCx - art / 2f, artCy - art / 2f, artCx + art / 2f, artCy + art / 2f),
            dp(11f), dp(11f), fill,
        )
        fill.shader = null
        text.typeface = sansMedium
        text.textSize = sp(16f)
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.CENTER
        text.color = 0xE6FFFFFF.toInt()
        canvas.drawText("♪", artCx, artCy + sp(6f), text)
        text.textAlign = Paint.Align.LEFT
        text.typeface = sansMedium
        text.textSize = sp(13f)
        text.letterSpacing = 0.01f
        text.textAlign = Paint.Align.LEFT
        text.color = 0xFFF2F6FA.toInt()
        val textX = artCx + art / 2f + dp(12f)
        canvas.drawText("正在播放", textX, top + dp(26f), text)
        text.typeface = sans
        text.textSize = sp(11f)
        text.color = 0x99E8EEF5.toInt()
        canvas.drawText("轻音乐 · 夜航", textX, top + dp(43f), text)

        val iconCx = right - dp(30f)
        val iconCy = top + cardH / 2f
        fill.style = Paint.Style.FILL
        fill.color = 0xE6F2F6FA.toInt()
        path.reset()
        path.moveTo(iconCx - dp(4f), iconCy - dp(6f))
        path.lineTo(iconCx + dp(6f), iconCy)
        path.lineTo(iconCx - dp(4f), iconCy + dp(6f))
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun drawAppGrid(canvas: Canvas, w: Float, gridTop: Float, gridBottom: Float) {
        val columns = 4
        val rows = 3
        val cellW = (w - dp(24f)) / columns
        val cellH = (gridBottom - gridTop) / rows
        val iconSize = min(cellW * 0.62f, cellH * 0.62f)
        var index = 0
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val cx = dp(12f) + cellW * (col + 0.5f)
                val top = gridTop + cellH * row + (cellH - iconSize - dp(16f)) / 2f
                drawAppIcon(canvas, index, cx, top + iconSize / 2f, iconSize)
                text.typeface = sans
                text.textSize = sp(9.5f)
                text.letterSpacing = 0.01f
                text.textAlign = Paint.Align.CENTER
                text.color = 0xE6FFFFFF.toInt()
                canvas.drawText(
                    appLabels.getOrNull(index) ?: previewApps[index].label,
                    cx, top + iconSize + dp(13f), text,
                )
                index++
            }
        }
    }

    private fun drawAppIcon(canvas: Canvas, index: Int, cx: Float, cy: Float, size: Float) {
        val icon = appIcons.getOrNull(index)
        if (icon != null) {
            icon.setBounds(
                (cx - size / 2f).toInt(),
                (cy - size / 2f).toInt(),
                (cx + size / 2f).toInt(),
                (cy + size / 2f).toInt(),
            )
            icon.draw(canvas)
            return
        }
        val app = previewApps.getOrNull(index) ?: return
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(
            cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f,
            intArrayOf(app.top, app.bottom), null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(
            r(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f),
            dp(13f), dp(13f), fill,
        )
        fill.shader = null
        text.typeface = sansMedium
        text.textSize = size * 0.34f
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.CENTER
        text.color = 0xFFFFFFFF.toInt()
        canvas.drawText(app.label.take(1), cx, cy + size * 0.17f, text)
    }

    private fun drawHomeWeather(canvas: Canvas, w: Float, h: Float) {
        text.typeface = sans
        text.textSize = sp(11f)
        text.letterSpacing = 0.03f
        text.textAlign = Paint.Align.CENTER
        text.color = 0x99FFFFFF.toInt()
        canvas.drawText("晴 13° · 北京市", w / 2f, h * 0.192f, text)
    }

    private fun drawHomeDots(canvas: Canvas, w: Float, dockTop: Float) {
        val dotsTop = dockTop - dp(24f)
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = 0x14FFFFFF
        canvas.drawRoundRect(
            r(w / 2f - dp(28f), dotsTop, w / 2f + dp(28f), dotsTop + dp(18f)),
            dp(9f), dp(9f), fill,
        )
        for (i in 0 until 4) {
            fill.color = if (i == 0) 0xFFFFFFFF.toInt() else 0x59FFFFFF
            canvas.drawCircle(w / 2f - dp(11f) + dp(8f) * i, dotsTop + dp(9f), dp(1.8f), fill)
        }
    }

    private fun drawDock(canvas: Canvas, w: Float, dockTop: Float, dockH: Float) {
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = 0x1FFFFFFF
        canvas.drawRoundRect(
            r(dp(12f), dockTop, w - dp(12f), dockTop + dockH),
            dp(26f), dp(26f), fill,
        )
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = dp(1f)
        stroke.pathEffect = null
        stroke.color = 0x17FFFFFF
        canvas.drawRoundRect(
            r(dp(12f), dockTop, w - dp(12f), dockTop + dockH),
            dp(26f), dp(26f), stroke,
        )
        val dockIcon = min(dockH * 0.58f, w * 0.125f)
        for (i in 0 until 4) {
            val cx = dp(12f) + (w - dp(24f)) / 4f * (i + 0.5f)
            val cy = dockTop + dockH / 2f
            drawAppIcon(canvas, 12 + i, cx, cy, dockIcon)
        }
    }

    private fun drawBokeh(canvas: Canvas, w: Float, h: Float, strength: Float) {
        val spots = floatArrayOf(
            0.18f, 0.24f, 0.030f, 0.05f,
            0.32f, 0.16f, 0.018f, 0.04f,
            0.74f, 0.34f, 0.026f, 0.045f,
            0.86f, 0.62f, 0.020f, 0.035f,
            0.44f, 0.82f, 0.024f, 0.04f,
            0.10f, 0.66f, 0.016f, 0.03f,
        )
        var i = 0
        while (i < spots.size) {
            val cx = w * spots[i]
            val cy = h * spots[i + 1]
            val radius = w * spots[i + 2]
            val a = spots[i + 3] * strength
            radial(canvas, cx, cy, radius, alphaColor(0xFFFFFFFF.toInt(), a), 0x00FFFFFF)
            i += 4
        }
    }

    private fun drawAmbientWallpaper(canvas: Canvas, w: Float, h: Float) {
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(
            0f, 0f, w * 0.32f, h,
            intArrayOf(0xFF101828.toInt(), 0xFF080C16.toInt(), 0xFF02040A.toInt()),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        radial(canvas, w * 0.24f, h * 0.30f, w * 0.68f, alphaColor(0xFF6E7FC6.toInt(), 0.20f), 0x006E7FC6)
        radial(canvas, w * 0.82f, h * 0.20f, w * 0.62f, alphaColor(0xFF3E6FD1.toInt(), 0.18f), 0x003E6FD1)
        radial(canvas, w * 0.64f, h * 0.88f, w * 0.72f, alphaColor(0xFF2E7F88.toInt(), 0.13f), 0x002E7F88)
        radial(canvas, w * 0.12f, h * 0.94f, w * 0.46f, alphaColor(0xFF8A6A3E.toInt(), 0.10f), 0x008A6A3E)
        drawBokeh(canvas, w, h, 0.8f)
    }

    private fun drawGestureBar(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = Color.argb((alpha.coerceIn(0f, 1f) * 220f).toInt(), 255, 255, 255)
        val barW = dp(116f)
        val barH = dp(4f)
        val cy = h - dp(9f)
        canvas.drawRoundRect(
            r(w / 2f - barW / 2f, cy - barH / 2f, w / 2f + barW / 2f, cy + barH / 2f),
            barH / 2f, barH / 2f, fill,
        )
    }

    private fun drawVignette(canvas: Canvas, w: Float, h: Float) {
        fill.style = Paint.Style.FILL
        fill.shader = RadialGradient(
            w / 2f, h * 0.46f, maxOf(w, h) * 0.72f,
            intArrayOf(0x00000000, 0x00000000, 0x66000000),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
        )
    }

    private fun mix3(a: Int, b: Int, c: Int, u: Float): Int =
        if (u <= 0.5f) lerpColor(a, b, u * 2f) else lerpColor(b, c, (u - 0.5f) * 2f)

    private fun drawWallpaperBackground(canvas: Canvas, w: Float, h: Float, scrim: Float): Boolean {
        val bitmap = wallpaper ?: return false
        if (!useSystemWallpaper) return false
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = Color.WHITE
        fill.alpha = 255
        val scale = maxOf(w / bitmap.width, h / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (w - width) / 2f
        val top = (h - height) / 2f
        dst.set(left, top, left + width, top + height)
        src.set(0, 0, bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, src, dst, fill)
        fill.color = Color.argb((scrim.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, fill)
        return true
    }

    private fun radial(canvas: Canvas, cx: Float, cy: Float, radius: Float, center: Int, edge: Int) {
        fill.style = Paint.Style.FILL
        fill.shader = RadialGradient(cx, cy, radius, center, edge, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null
    }

    private fun alphaColor(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255f).toInt(),
        Color.red(color), Color.green(color), Color.blue(color),
    )

    private fun refreshTime() {
        val now = LocalDateTime.now()
        timeText = if (android.text.format.DateFormat.is24HourFormat(context)) {
            String.format("%02d:%02d", now.hour, now.minute)
        } else {
            val hour = now.hour % 12
            String.format("%d:%02d", if (hour == 0) 12 else hour, now.minute)
        }
        val weekday = when (now.dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }
        dateText = "${now.monthValue}月${now.dayOfMonth}日 $weekday"
    }

    private fun scheduleMinuteTick() {
        removeCallbacks(minuteTick)
        val delay = 60_000L - System.currentTimeMillis() % 60_000L
        postDelayed(minuteTick, delay)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestAppIcons()
        requestSceneSky()
        refreshTime()
        scheduleMinuteTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(minuteTick)
        super.onDetachedFromWindow()
    }

    /** 场景底图一次性后台解码，缺资源时回退程序化天空。 */
    private fun requestSceneSky() {
        if (sceneSky != null || sceneSkyLoading) return
        sceneSkyLoading = true
        Thread({
            val bitmap = runCatching {
                context.assets.open("aod/scene_sky.webp").use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
            post {
                sceneSky = bitmap
                sceneSkyLoading = false
                invalidate()
            }
        }, "aod-scene-sky").start()
    }

    /** 图标取系统原厂资源；一次性后台加载，不阻塞首帧。 */
    private fun requestAppIcons() {
        if (iconsRequested) return
        iconsRequested = true
        val pm = context.packageManager
        Thread({
            val loaded = arrayOfNulls<Drawable>(previewApps.size)
            val labels = arrayOfNulls<String>(previewApps.size)
            for (i in previewApps.indices) {
                try {
                    val info = pm.getApplicationInfo(previewApps[i].pkg, 0)
                    loaded[i] = pm.getApplicationIcon(info)
                    labels[i] = pm.getApplicationLabel(info).toString()
                } catch (t: Throwable) {
                    loaded[i] = null
                    labels[i] = null
                }
            }
            post {
                for (i in loaded.indices) {
                    appIcons[i] = loaded[i]
                    appLabels[i] = labels[i]
                }
                invalidate()
            }
        }, "aod-app-icons").apply { isDaemon = true }.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableW = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val availableH = MeasureSpec.getSize(heightMeasureSpec).toFloat()
        setMeasuredDimension(
            availableW.toInt().coerceAtLeast(1),
            availableH.toInt().coerceAtLeast(1),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        screen = ScreenMetrics(w.toFloat(), h.toFloat(), density)
        uiScale = min(w / (360f * density), h / (800f * density)).coerceAtLeast(0.1f)
    }

    private fun r(left: Float, top: Float, right: Float, bottom: Float): RectF =
        rect.apply { set(left, top, right, bottom) }

    private fun dp(value: Float): Float = value * density * uiScale

    private fun sp(value: Float): Float = value * scaled * uiScale
}
