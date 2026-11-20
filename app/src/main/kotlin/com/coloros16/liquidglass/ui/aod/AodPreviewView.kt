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
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.AodClockEntry
import com.coloros16.liquidglass.engine.AodDrift
import com.coloros16.liquidglass.engine.AodSpinGate
import com.coloros16.liquidglass.engine.BackdropFit
import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockAnchorMapper
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.ContentLayerConfig
import com.coloros16.liquidglass.engine.Easing
import com.coloros16.liquidglass.engine.EffectOverlayPolicy
import com.coloros16.liquidglass.engine.ElementPose
import com.coloros16.liquidglass.engine.FeatherPainter
import com.coloros16.liquidglass.engine.MaskGeometry
import com.coloros16.liquidglass.engine.MaskLayerConfig
import com.coloros16.liquidglass.engine.MaskShape
import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.CloudSpec
import com.coloros16.liquidglass.engine.DotSpec
import com.coloros16.liquidglass.engine.GradientStop
import com.coloros16.liquidglass.engine.LinearSpec
import com.coloros16.liquidglass.engine.RadialSpec
import com.coloros16.liquidglass.engine.RidgePath
import com.coloros16.liquidglass.engine.SceneLayerConfig
import com.coloros16.liquidglass.engine.SceneSkyMath
import com.coloros16.liquidglass.engine.ContentWindow
import com.coloros16.liquidglass.engine.SceneSample
import com.coloros16.liquidglass.engine.HandPaintGeometry
import com.coloros16.liquidglass.engine.HandPaintSource
import com.coloros16.liquidglass.engine.HandPaintTimeline
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneTimeline
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.SeamProfilePolicy
import com.coloros16.liquidglass.engine.StyleCapabilities
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.WallpaperZoomSettle
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.WorldCenterPolicy
import com.coloros16.liquidglass.engine.TransitionEvaluator
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.engine.TransitionProfile
import com.coloros16.liquidglass.engine.WorldMaskPolicy
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.HomeTransitionOverride
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectParams
import com.coloros16.liquidglass.engine.effect.EffectSettings
import com.coloros16.liquidglass.engine.effect.EffectTextureCatalog
import com.coloros16.liquidglass.engine.effect.HomeMotion
import com.coloros16.liquidglass.engine.effect.IconEntranceState
import com.coloros16.liquidglass.engine.effect.IconPhaseFit
import com.coloros16.liquidglass.engine.home.HomeGeometry
import com.coloros16.liquidglass.engine.home.HomeLayout
import com.coloros16.liquidglass.engine.to
import com.coloros16.liquidglass.log.Lg
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
            if (field == value) return
            field = value
            effectLayerKey = ""
            syncLensSource()
            warmEffectLayer()
            invalidate()
        }

    /** 壁纸效果 id：有原版贴图的走法线折射，模糊/景深走各自管线；选中即预处理。 */
    var lockWallpaperEffect: String = EffectCatalog.LOCK_WP_NONE
        set(value) {
            if (field == value) return
            field = value
            effectLayerKey = ""
            warmEffectLayer()
            invalidate()
        }

    /** 特殊壁纸效果 id：景深走移轴带模糊；选中即预处理。 */
    var lockSpecialEffect: String = EffectCatalog.LOCK_SP_NONE
        set(value) {
            if (field == value) return
            field = value
            effectLayerKey = ""
            warmEffectLayer()
            invalidate()
        }

    /** 桌面过渡 id：默认不接管走接缝配方，用户主动选淡出淡入才覆盖。 */
    var homeTransition: String = EffectCatalog.HOME_TRANSITION_NONE
        set(value) {
            if (field == value) return
            field = value
            applyHomeTransition()
            invalidate()
        }

    /** 桌面过渡时长：接管时替代样式真值，只作用锁屏↔桌面。 */
    var homeTransitionDurationMs: Float = 500f

    /** 壁纸效果强度 0–1：魅族玻璃折射幅度。 */
    var lockWallpaperIntensity: Float = 0.6f
        set(value) {
            if (field == value) return
            field = value
            effectLayerKey = ""
            warmEffectLayer()
            invalidate()
        }

    /** 相框模式：双照片相框（留光），相框照片叠在壁纸上。 */
    var framePair: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 相框前景照片；null 时相框模式回退单照片。 */
    var framePhoto: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    /** 照片类样式（星月/轻启/山脉/银河）的用户照片；null 时用默认壁纸/天空。 */
    var stylePhoto: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            effectLayerKey = ""
            syncLensSource()
            warmEffectLayer()
            invalidate()
        }

    /** 取景参数：中心 x/y（0–1）、大小（0.05–1）、角度（±45°）。 */
    var frameCropX: Float = 0.5f
    var frameCropY: Float = 0.5f
    var frameCropSize: Float = 1f
    var frameCropAngle: Float = 0f

    /** 息屏动态总闸：关=旋转/混色/倾斜全部冻结。 */
    var allowAodDynamic: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncMotionTick()
            invalidate()
        }

    /** 启航混色幅度 0–100。 */
    var aodColorMix: Float = 60f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 启航旋转速度（度/秒），0=停转。 */
    var aodRotateSpeed: Float = 12f

    /** 桌面图标入场效果 id 与三项参数；进桌面边按 index 错峰求值。 */
    var homeIconEffect: String = EffectCatalog.HOME_ICON_FOREGROUND_FLY
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var homeIconDurationMs: Float = 420f
    var homeIconStaggerMs: Float = 28f
    var homeIconTravelPercent: Float = 60f
        set(value) {
            if (field == value) return
            field = value
            syncMotionTick()
            invalidate()
        }

    /** 照片倾斜幅度（度），息屏期正弦摆动。 */
    var aodPhotoTilt: Float = 6f
        set(value) {
            if (field == value) return
            field = value
            syncMotionTick()
            invalidate()
        }

    private var effectLayer: Bitmap? = null
    private var effectLayerKey: String = ""
    private var effectLoading = false

    private var scene: SceneState = SceneState.AOD
    private var edge: TransitionEdge? = null
    private var t: Float = 0f
    private var sceneSpec: AodSceneSpec? = null
    private var styleId: String = Prefs.DEFAULT_ALIVE_AOD_STYLE_ID
    private var scrubP = -1f
    private var scrubEdge: TransitionEdge? = null
    private val clockMachine = ClockStateMachine(easing = Easing.STANDARD)
    private val sceneSample = SceneSample()
    private val layers = SystemSceneMotion.Layers()

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val circle = Path()
    private val maskFramePath = Path()
    private val maskVeilPath = Path()
    private val rect = RectF()
    private val dst = RectF()
    private val src = Rect()
    private var wallpaper: Bitmap? = null
    private var sceneSky: Bitmap? = null
    private var sceneSkyLoading = false
    private var uiScale = 1f
    private val path = Path()

    private val lensRenderer = AodLensRenderer(context)
    private val lensPose = SailLensMotion.Pose()
    private val sailFade = SailLensMotion.Fade()
    private var lensAngleDeg = 0f
    private var lensLastNanos = 0L
    private var lensScheduled = false
    private var lensSpinning = false
    private var aodLayerAlpha = 1f
    private var contentGate = 1f
    private val photoRenderer = PhotoSceneRenderer(context)
    private val photoMotion = PhotoStyleMotion.Values()
    private var photoStyle: PhotoStyle? = null
    private var photoSequence: PhotoFrameSequence? = null
    private var handPaintRenderer: HandPaintRenderer? = null
    private var handPaintSide = -1
    private var handPaintSilks: List<com.coloros16.liquidglass.engine.HandPaintSilk>? = null
    private var handPaintRequested = -1
    private var handPaintLoading = false
    private val handPaintPlay = com.coloros16.liquidglass.engine.HandPaintTimeline.Play()
    private var lastHandPaintLogAt = 0L
    private var handPaintLayerLogAt = 0L
    private var handPaintLayerLogKey = ""
    private var photoFrame: Bitmap? = null
    private val phoneRect = RectF()
    private val phonePath = Path()
    private val editDelegate = AodPreviewEditDelegate(this)

    init {
        isClickable = true
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val aodMotion = com.coloros16.liquidglass.engine.effect.AodMotionState()

    private val lensTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            lensScheduled = false
            if (!isAttachedToWindow) {
                lensSpinning = false
                return
            }
            val last = lensLastNanos
            lensLastNanos = frameTimeNanos
            if (last != 0L) {
                val speed = if (lensSpinActive()) {
                    (aodRotateSpeed / SailLensMotion.SPIN_DEG_PER_SEC).coerceIn(0f, 2f)
                } else {
                    0f
                }
                lensAngleDeg = SailLensMotion.spin(
                    lensAngleDeg,
                    (frameTimeNanos - last) / 1_000_000_000f,
                    speed,
                )
            }
            if (lensSpinning || motionAnimating() || lensSpinActive()) {
                invalidate()
                scheduleLensTick()
            }
        }
    }

    /**
     * 镜片壁纸是否该自转：息屏静止态，以及从息屏出发的展开边全程。
     *
     * 展开期继续自转才看得到「边转边放大归位」；进息屏的收缩边保持冻结。
     */
    private fun lensSpinActive(): Boolean {
        val activeEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        return AodSpinGate.active(
            allowAodDynamic, style.id == AodStyleCatalog.ID_SAIL, scene, activeEdge,
        )
    }

    /** 息屏动态帧泵按需武装：总闸开且（启航转/混色或照片摆）才逐帧，静止零回调。 */
    private fun syncMotionTick() {
        if (motionAnimating() || lensSpinActive()) scheduleLensTick() else if (!lensSpinning) stopLensTick()
    }

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
    /** 非 SCENE 样式的接缝配方：揭开两条边圆外不淡出，其余四条边整屏纵向推移。 */
    private val seamProfiles: Map<TransitionEdge, TransitionProfile> = SEAM_EDGES.associateWith { e ->
        TransitionProfile(
            scene = SceneLayerConfig(enabled = false),
            mask = MaskLayerConfig(enabled = false, easing = Easing.STANDARD),
            content = ContentLayerConfig(enabled = false, easing = Easing.STANDARD),
        ).also { SeamProfilePolicy.apply(e, it) }
    }

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

    private val previewApps = HomeIconSource.all
    private val homePages = 2

    private var homeIcons: List<HomeIcon> = emptyList()
    private var iconsRequested = false
    private var homeGeometry: HomeGeometry? = null
    private var homeGeometryKey: String = ""
    private val iconBitmapCache = HashMap<Int, Pair<Drawable, Bitmap>>()
    private val labelMetrics = Paint.FontMetrics()

    private val sceneFields = SceneSkyMath.fields()
    private val stars = sceneFields.first
    private val moonMarks = sceneFields.second
    private val cloudBlobs = SceneSkyMath.cloudBlobs()
    private val backRidge = SceneSkyMath.backRidge()
    private val frontRidge = SceneSkyMath.frontRidge()

    private val starDots = ArrayList<DotSpec>()
    private val cloudSpecs = ArrayList<CloudSpec>()
    private val moonCraterSpecs = ArrayList<RadialSpec>()

    init {
        applyStyle(style)
        refreshTime()
    }

    private fun applyStyle(value: AodStyle) {
        clockTailEdge = null
        zoomSettleFrom = 0f
        scrubEdge = null
        styleId = value.id
        sceneSpec = AodSceneCatalog.forStyle(value.id)
        applyProfiles(value)
        syncPhotoStyle(value.id)
        syncHandPaintStyle(value.id)
        syncLensSource()
        lensSpinning = false
        syncMotionTick()
        val spec = sceneSpec ?: return
        for ((state, anchor) in spec.clockAnchors) {
            clockMachine.setAnchor(state, anchor, 0L, follow = false)
        }
        clockMachine.setAnchor(SceneState.HOME, homeClockAnchor(), 0L, follow = false)
        clockMachine.snapTo(scene, 0L)
    }

    /** 桌面时钟终点：真机锚点（桌面小部件>状态栏）优先，皆无走预览演示位（真机那一路是淡出）。 */
    private fun homeClockAnchor(): ClockAnchor =
        com.coloros16.liquidglass.hook.DesktopClockAnchorBridge.anchorOrNull()
            ?: ClockAnchorMapper.HOME_PREVIEW_DEMO

    /** 手绘样式切换：换款清渲染器与轨迹，非手绘款释放离屏画布。 */
    private fun syncHandPaintStyle(styleId: String) {
        val style = AodSceneCatalog.handPaintOf(styleId)
        if (style <= 0) {
            handPaintRenderer?.close()
            handPaintRenderer = null
            handPaintSide = -1
            handPaintSilks = null
            handPaintRequested = -1
            handPaintPlay.reset()
            return
        }
        if (handPaintRequested == style) return
        handPaintRequested = style
        handPaintSilks = null
        handPaintPlay.reset()
        requestHandPaint(style)
    }

    /** 轨迹后台单飞解析：设备原件不随包分发，就绪后重绘。 */
    private fun requestHandPaint(style: Int) {
        if (handPaintLoading) return
        handPaintLoading = true
        Thread({
            val silks = runCatching {
                com.coloros16.liquidglass.hook.DeviceHandPaint.obtain(style, context.cacheDir)
            }.getOrElse {
                Lg.w(TAG, "handpaint load failed style=$style ${it.javaClass.simpleName}")
                null
            }
            post {
                handPaintLoading = false
                if (handPaintRequested != style) return@post
                handPaintSilks = silks
                if (silks == null) {
                    Lg.w(TAG, "handpaint unavailable style=$style 设备无轨迹")
                } else {
                    Lg.i(TAG, "handpaint ready style=$style silks=${silks.size} points=${silks.sumOf { it.points.size }}")
                }
                invalidate()
            }
        }, "aod-handpaint").apply { isDaemon = true }.start()
    }

    /** 照片类样式切换时重建帧序列与内容纹理；其它样式释放。 */
    private fun syncPhotoStyle(styleId: String) {
        val photo = AodSceneCatalog.photoOf(styleId)
        if (photo == null) {
            photoSequence?.close()
            photoSequence = null
            photoStyle = null
            photoFrame = null
            return
        }
        if (photoStyle?.id == photo.id && photoSequence != null) return
        photoSequence?.close()
        photoStyle = photo
        photoFrame = null
        val sequence = PhotoFrameSequence(context.assets, photo) {
            post { if (photoStyle?.id == photo.id) invalidate() }
        }
        sequence.prefetch(intArrayOf(photo.frames, 0, 1, 2, 3))
        photoSequence = sequence
    }

    private fun applyProfiles(value: AodStyle) {
        maskProfile.mask.centerXFraction = value.maskCenterX
        maskProfile.mask.centerYFraction = value.maskCenterY
        val scene = AodSceneCatalog.forStyle(value.id) != null
        val maskEdge = if (value.transition == AodTransition.MASK) maskProfile else contentProfile
        evaluator.setProfile(TransitionEdge.AOD_TO_LOCK, maskEdge)
        evaluator.setProfile(TransitionEdge.LOCK_TO_AOD, maskEdge)
        for (e in SEAM_EDGES) {
            val profile = if (scene) contentProfile else seamProfiles.getValue(e)
            evaluator.setProfile(e, profile)
        }
        applyHomeTransition()
        logSeam(value.id, scene)
    }

    /** 桌面过渡覆盖：走共用策略件，作用域与时长口径与 hook 侧同源。 */
    private fun applyHomeTransition() {
        val scene = AodSceneCatalog.forStyle(styleId) != null
        val settings = homeSettings()
        var overridden = false
        for (e in SEAM_EDGES) {
            if (!HomeTransitionOverride.governs(e)) continue
            val profile = HomeTransitionOverride.profileFor(e, settings)
            val base = if (scene) contentProfile else seamProfiles.getValue(e)
            evaluator.setProfile(e, profile ?: base)
            if (profile != null) overridden = true
        }
        logHomeTransition(scene, overridden, HomeTransitionOverride.durationMs(TransitionEdge.LOCK_TO_HOME, settings))
    }

    private fun homeSettings(): EffectSettings =
        EffectSettings().also {
            it.homeTransition = homeTransition
            it.setParam(EffectParams.HOME_TRANSITION_DURATION, homeTransitionDurationMs)
        }

    private var homeTransitionLogKey: String? = null

    private fun logHomeTransition(scene: Boolean, overridden: Boolean, durationMs: Long) {
        val key = "$styleId|$homeTransition|$scene|$homeTransitionDurationMs"
        if (key == homeTransitionLogKey) return
        homeTransitionLogKey = key
        Lg.i(
            TAG,
            "home-transition id=$homeTransition override=$overridden scene=$scene" +
                " durationMs=$durationMs edges=LOCK_TO_HOME,HOME_TO_LOCK",
        )
    }

    private var seamLogKey: String? = null

    private fun logSeam(styleId: String, scene: Boolean) {
        val key = "$styleId|$scene"
        if (key == seamLogKey) return
        seamLogKey = key
        Lg.i(TAG, "seam style=$styleId mode=${if (scene) "scene-crossfade" else "reveal(noOuterFade)+slide(0.22)"}")
    }

    /** 设置系统壁纸底图（接管壁纸时的背景）。 */
    fun setWallpaper(bitmap: Bitmap?) {
        wallpaper = bitmap
        effectLayerKey = ""
        syncLensSource()
        warmEffectLayer()
        invalidate()
    }

    /** 底图优先级走共用策略件，与 hook 侧同口径；三者皆无时兜底用原壁纸。 */
    private fun backdropSource(hasSceneSky: Boolean = true): Bitmap? {
        val real = if (useSystemWallpaper) wallpaper else null
        return when (
            com.coloros16.liquidglass.engine.BackdropSourcePolicy.backdropKind(
                stylePhoto != null, useSystemWallpaper, wallpaper != null, hasSceneSky && sceneSky != null,
            )
        ) {
            com.coloros16.liquidglass.engine.BackdropSourcePolicy.Kind.STYLE_PHOTO -> stylePhoto
            com.coloros16.liquidglass.engine.BackdropSourcePolicy.Kind.SYSTEM_WALLPAPER -> real
            com.coloros16.liquidglass.engine.BackdropSourcePolicy.Kind.SCENE_SKY -> sceneSky
            com.coloros16.liquidglass.engine.BackdropSourcePolicy.Kind.NONE -> null
        }
    }

    /** 底图来自哪一路：按位图身份判，不按能力位猜（能力位为真不等于这一路被选中）。 */
    private fun backdropTag(bmp: Bitmap?): String = when {
        bmp == null -> "none"
        bmp === stylePhoto -> "photo"
        bmp === sceneSky -> "sky"
        bmp === wallpaper -> "wall"
        else -> "other"
    }

    private var backdropLogKey: String? = null
    private var backdropLogAt = 0L

    /** 底图裁决诊断：按结论判重，换款/换态/换底图各打一条。 */
    private fun logBackdrop(where: String, bmp: Bitmap?) {
        val tag = backdropTag(bmp)
        val size = if (bmp == null) "-" else "${bmp.width}x${bmp.height}"
        val key = "$where|$styleId|$scene|$tag|$size|$useSystemWallpaper"
        val now = SystemClock.uptimeMillis()
        if (key == backdropLogKey && now - backdropLogAt < 800L) return
        backdropLogKey = key
        backdropLogAt = now
        Lg.i(
            TAG,
            "preview-backdrop at=$where style=$styleId state=$scene from=$tag " +
                "src=$size sys=$useSystemWallpaper",
        )
    }

    private fun lensSource(): Bitmap? = backdropSource() ?: wallpaper

    private fun syncLensSource() {
        lensRenderer.setSource(lensSource(), PREVIEW_ASPECT)
        val photo = photoStyle ?: return
        photoRenderer.setSource(lensSource(), PREVIEW_ASPECT, photo.blur)
    }

    /** 内联编辑委托：布局配置与回调由预览控制器接线。 */
    val edit: AodPreviewEditDelegate get() = editDelegate

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editDelegate.onTouchEvent(event)) return true
        return super.onTouchEvent(event)
    }

    /** 刷新当前态 / 边 + 进度。 */
    fun render(scene: SceneState, edge: TransitionEdge?, t: Float) {
        // 拖动期 this.edge 恒 null，落位取边要认拖动走过的那一段
        val prevEdge = this.edge ?: scrubEdge
        this.scene = scene
        this.edge = edge
        this.t = t.coerceIn(0f, 1f)
        this.scrubP = -1f
        scrubEdge = null
        editDelegate.state = scene
        if (edge != null && edge != prevEdge) {
            lensAngleDeg = when (edge) {
                TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK -> lensPose.angleDeg
                else -> lensAngleDeg
            }
        }
        if (edge != null) {
            editDelegate.cancelTouch()
            clockTailEdge = null
            zoomSettleFrom = 0f
            // 刚进边：入边起点与上一静止态不同时补一段（HOME_TO_LOCK 起始 1.0→1.1 同族）
            if (prevEdge == null) {
                startEnterZoom(edge)
            }
        } else if (prevEdge != null) {
            startZoomSettle(prevEdge, scene)
        }
        if (edge == null && prevEdge != null &&
            AodClockEntry.entering(prevEdge) && scene == SceneState.AOD
        ) {
            val edgeMs = edgeMsFor(prevEdge)
            if (AodClockEntry.tailMs(edgeMs) > 0L) {
                clockTailEdge = prevEdge
                clockTailEdgeMs = edgeMs
                clockTailStartMs = SystemClock.uptimeMillis()
                scheduleClockTail()
            }
        }
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
        scrubEdge = SceneTimeline.edgeAt(scrubP)
        clockTailEdge = null
        zoomSettleFrom = 0f
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

        val sideInset = 16f * density
        val topInset = 96f * density
        val bottomInset = 212f * density
        val availW = (w - sideInset * 2f).coerceAtLeast(1f)
        val availH = (h - topInset - bottomInset).coerceAtLeast(1f)
        var pw = availH * PREVIEW_ASPECT
        var ph = availH
        if (pw > availW) {
            pw = availW
            ph = pw / PREVIEW_ASPECT
        }
        if (pw < 2f || ph < 2f) return
        val px = (w - pw) / 2f
        val py = topInset + (availH - ph) / 2f
        phoneRect.set(px, py, px + pw, py + ph)
        val corner = 18f * density

        fill.shader = RadialGradient(
            w / 2f, py + ph * 0.5f, maxOf(pw, ph) * 0.68f,
            intArrayOf(0x1E2A3A4E, 0x00000000),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        phonePath.reset()
        phonePath.addRoundRect(phoneRect, corner, corner, Path.Direction.CW)
        val clip = canvas.save()
        canvas.clipPath(phonePath)
        canvas.translate(px, py)
        screen = ScreenMetrics(pw, ph, density)
        uiScale = min(pw / (360f * density), ph / (800f * density)).coerceAtLeast(0.1f)

        lensSpinning = false
        val spec = sceneSpec
        if (spec != null) {
            drawSceneWorld(canvas, pw, ph, spec)
        } else {
            val rawEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
            val rawT = if (scrubP >= 0f) SceneTimeline.localT(scrubP) else t
            val e = rawEdge
            if (e == null) {
                drawWallpaperOnce(canvas, pw, ph, sceneDim(scene))
                drawState(canvas, scene, pw, ph, 1f)
                if (sceneSpec != null) drawSceneClock(canvas, pw, ph)
            } else {
                evaluator.evaluate(e, rawT, screen, capabilities, capabilities, params, null, scrubP >= 0f)
                SeamProfilePolicy.opaqueSlide(evaluator.profileOf(e).content, params)
                SystemSceneMotion.sample(
                    e, rawT, layers, direct = scrubP >= 0f,
                    edgeMs = edgeMsFor(e),
                )
                val veilOwnsDim = WorldMaskPolicy.veilDrawable(
                    worldDrawn = false,
                    maskEnabled = params.maskEnabled,
                    radiusDrawable = MaskGeometry.drawable(params.maskRadius),
                    maskByFrame = params.maskByFrame,
                )
                drawWallpaperOnce(canvas, pw, ph, if (veilOwnsDim) 0f else layers.dim)

                if (params.srcAlpha > 0.002f) {
                    val layer = canvas.saveLayerAlpha(0f, 0f, pw, ph, (params.srcAlpha * 255f).toInt())
                    canvas.translate(params.srcDx, params.srcDy)
                    canvas.scale(params.srcScale, params.srcScale, pw / 2f, ph / 2f)
                    drawState(canvas, e.from, pw, ph, 1f)
                    canvas.restoreToCount(layer)
                }

                if (params.maskEnabled && MaskGeometry.drawable(params.maskRadius)) {
                    circle.reset()
                    when (params.maskShape) {
                        MaskShape.CIRCLE -> circle.addCircle(
                            params.maskCenterX, params.maskCenterY, params.maskRadius, Path.Direction.CW,
                        )
                        MaskShape.ROUNDED_RECT -> {
                            val half = MaskGeometry.roundedRectHalfExtents(params.maskRadius, pw, ph)
                            val corner = MaskGeometry.roundedRectCornerPx(params.maskRadius, min(pw, ph))
                            rect.set(
                                params.maskCenterX - half[0], params.maskCenterY - half[1],
                                params.maskCenterX + half[0], params.maskCenterY + half[1],
                            )
                            circle.addRoundRect(rect, corner, corner, Path.Direction.CW)
                        }
                    }
                    val target = canvas.save()
                    canvas.clipPath(circle)
                    canvas.translate(params.dstDx, params.dstDy)
                    canvas.scale(params.dstScale, params.dstScale, pw / 2f, ph / 2f)
                    drawState(canvas, e.to, pw, ph, 1f)
                    canvas.restoreToCount(target)
                    drawMaskFeather(canvas, pw, ph, params)
                } else if (rawT >= 0.9999f) {
                    drawState(canvas, e.to, pw, ph, 1f)
                } else if (params.dstAlpha > 0.002f) {
                    val layer = canvas.saveLayerAlpha(0f, 0f, pw, ph, (params.dstAlpha * 255f).toInt())
                    canvas.translate(params.dstDx, params.dstDy)
                    canvas.scale(params.dstScale, params.dstScale, pw / 2f, ph / 2f)
                    drawState(canvas, e.to, pw, ph, 1f)
                    canvas.restoreToCount(layer)
                }
                if (sceneSpec != null) drawSceneClock(canvas, pw, ph)
            }
        }
        editDelegate.phoneRect.set(phoneRect)
        editDelegate.draw(canvas, pw, ph)
        canvas.restoreToCount(clip)

        framePaint.strokeWidth = density
        framePaint.shader = null
        framePaint.color = 0x2EFFFFFF
        canvas.drawRoundRect(phoneRect, corner, corner, framePaint)

        if (lensSpinning || motionAnimating() || lensSpinActive()) scheduleLensTick() else stopLensTick()
    }

    /** 息屏动态是否需要在场逐帧：总闸开 + 息屏在场 + 该样式有动参数。 */
    private fun motionAnimating(): Boolean {
        if (!allowAodDynamic) return false
        val aodVisible = scene == SceneState.AOD ||
            (edge != null && (edge!!.from == SceneState.AOD || edge!!.to == SceneState.AOD))
        if (!aodVisible) return false
        return (style.id == AodStyleCatalog.ID_SAIL && (aodRotateSpeed > 0f || aodColorMix > 0f)) ||
            (sceneSpec?.photo != null && aodPhotoTilt > 0f)
    }

    private fun scheduleLensTick() {
        if (lensScheduled) return
        lensScheduled = true
        Choreographer.getInstance().postFrameCallback(lensTick)
    }

    private fun stopLensTick() {
        if (lensScheduled) {
            Choreographer.getInstance().removeFrameCallback(lensTick)
            lensScheduled = false
        }
        lensLastNanos = 0L
    }

    /** 壁纸与压暗整屏只画一次：各态只画自己的内容，避免同屏双画与压暗跳变。 */
    private fun drawWallpaperOnce(canvas: Canvas, w: Float, h: Float, dim: Float) {
        if (!drawWallpaperBackground(canvas, w, h, 0f)) {
            drawAmbientWallpaper(canvas, w, h)
        }
        val a = (dim.coerceIn(0f, 1f) * 255f).toInt()
        if (a <= 0) return
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = Color.argb(a, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, fill)
    }

    private fun sceneDim(state: SceneState): Float = when (state) {
        SceneState.AOD -> SystemSceneMotion.DIM_AOD
        SceneState.LOCK -> SystemSceneMotion.DIM_LOCK
        SceneState.HOME -> SystemSceneMotion.DIM_HOME
    }

    /** 遮罩边缘羽化：分派与 hook 同源，圆走径向渐变、圆角矩形走路径环带。 */
    private fun drawMaskFeather(canvas: Canvas, w: Float, h: Float, p: TransitionParams) {
        val outer = MaskGeometry.featherOuterPx(p.maskRadius, p.maskFeather)
        if (outer <= 0f) return
        val veil = Color.argb((layers.dim.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0)
        fill.style = Paint.Style.FILL
        when (FeatherPainter.mode(p.maskShape, p.maskRadius, p.maskFeather)) {
            FeatherPainter.Mode.RADIAL -> {
                val stop = FeatherPainter.radialStop(p.maskRadius, p.maskFeather)
                fill.shader = RadialGradient(
                    p.maskCenterX, p.maskCenterY, outer,
                    intArrayOf(0x00000000, 0x00000000, veil),
                    floatArrayOf(0f, stop, 1f), Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
                logFeather("radial", p, outer, veil)
                return
            }

            FeatherPainter.Mode.PATH_RING -> {
                drawFeatherRing(canvas, w, h, p, veil)
                logFeather("ring", p, outer, veil)
                return
            }

            FeatherPainter.Mode.NONE -> Unit
        }
        addMaskShape(maskVeilPath, p.maskShape, p.maskCenterX, p.maskCenterY, outer, w, h)
        fill.shader = null
        fill.color = veil
        maskVeilPath.op(fullRectPath(w, h), maskVeilPath, Path.Op.DIFFERENCE)
        canvas.drawPath(maskVeilPath, fill)
        logFeather("hard", p, outer, veil)
    }

    /** 非圆形羽化：由外向内逐层画轮廓差集，每层不透明度按 src-over 反解值。 */
    private fun drawFeatherRing(canvas: Canvas, w: Float, h: Float, p: TransitionParams, veil: Int) {
        val inner = MaskGeometry.featherInnerPx(p.maskRadius, p.maskFeather)
        val outer = MaskGeometry.featherOuterPx(p.maskRadius, p.maskFeather)
        val steps = FeatherPainter.RING_STEPS
        val baseAlpha = Color.alpha(veil)
        fill.shader = null
        for (i in steps - 1 downTo 0) {
            val step = FeatherPainter.ringStep(i, steps)
            val r = inner + (outer - inner) * step[0]
            val a = (baseAlpha * step[1]).toInt().coerceIn(0, 255)
            if (a <= 0) continue
            addMaskShape(maskVeilPath, p.maskShape, p.maskCenterX, p.maskCenterY, r, w, h)
            maskVeilPath.op(fullRectPath(w, h), maskVeilPath, Path.Op.DIFFERENCE)
            fill.color = Color.argb(a, 0, 0, 0)
            canvas.drawPath(maskVeilPath, fill)
        }
    }

    /** 整屏矩形路径，供差集用；与形状路径共用同一 Path 实例前先取差。 */
    private fun fullRectPath(w: Float, h: Float): Path {
        maskFramePath.reset()
        maskFramePath.addRect(0f, 0f, w, h, Path.Direction.CW)
        return maskFramePath
    }

    /** 按形状构造轮廓；半径语义与揭开裁切分支一致。 */
    private fun addMaskShape(
        target: Path,
        shape: MaskShape,
        cx: Float,
        cy: Float,
        r: Float,
        w: Float,
        h: Float,
    ) {
        target.reset()
        when (shape) {
            MaskShape.CIRCLE -> target.addCircle(cx, cy, r, Path.Direction.CW)
            MaskShape.ROUNDED_RECT -> {
                val half = MaskGeometry.roundedRectHalfExtents(r, w, h)
                val corner = MaskGeometry.roundedRectCornerPx(r, min(w, h))
                rect.set(cx - half[0], cy - half[1], cx + half[0], cy + half[1])
                target.addRoundRect(rect, corner, corner, Path.Direction.CW)
            }
        }
    }

    private var featherLogKey: String? = null
    private var featherLogAt = 0L

    /** 羽化诊断：画法/形状/外径/压暗值同条，能核对两侧是否同口径。 */
    private fun logFeather(mode: String, p: TransitionParams, outer: Float, veil: Int) {
        val now = SystemClock.uptimeMillis()
        val key = "$mode|${p.maskShape}|${(outer / 20f).toInt()}|${Color.alpha(veil)}"
        if (key == featherLogKey && now - featherLogAt < 800L) return
        featherLogKey = key
        featherLogAt = now
        Lg.i(
            TAG,
            "mask-feather mode=$mode shape=${p.maskShape} r=${"%.1f".format(p.maskRadius)} " +
                "f=${"%.1f".format(p.maskFeather)} outer=${"%.1f".format(outer)} " +
                "dim=${"%.3f".format(layers.dim)} a=${Color.alpha(veil)}",
        )
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
        if (!editDelegate.drawClockOverride(canvas, w, h, timeText, dateText)) {
            drawClockBlock(canvas, w / 2f, centerY, base, timeColor, 0.58f)
        }

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

        if (!editDelegate.drawClockOverride(canvas, w, h, timeText, dateText)) {
            drawClockBlock(canvas, w / 2f, h * 0.205f, h * 0.152f, 0xFFF3F6F9.toInt(), 0.66f)
        }
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

        drawHomeWeather(canvas, w, h)

        val geo = homeGeometry(w, h)
        drawAppGrid(canvas, geo, -1L)
        drawHomeDots(canvas, geo)
        drawDock(canvas, geo)

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    // ---------------------------------------------------------------- 连续场景

    private fun drawSceneWorld(canvas: Canvas, w: Float, h: Float, spec: AodSceneSpec) {
        val u = if (scrubP >= 0f) {
            scrubP
        } else {
            SceneTimeline.uOf(edge, t, scene, SCENE_PLAY_CURVE)
        }
        sampleLayers()
        logPreviewFrame()
        aodLayerAlpha = layers.aodAlpha
        contentGate = 1f
        windowClipped = false
        sailFade.reset()
        if (spec.lens) {
            drawSailScene(canvas, w, h, u, spec)
            return
        }
        if (spec.photo != null) {
            drawPhotoScene(canvas, w, h, u, spec)
            return
        }
        if (spec.handPaintStyle > 0) {
            drawHandPaintScene(canvas, w, h, u, spec)
            return
        }
        sceneSample.sample(spec.contract, u)

        val sky = backdropSource()
        logBackdrop("scene-sky", sky)
        if (sky != null) {
            // 只有模块自绘的场景底图跟相机走；用户照片与系统壁纸是实景，不做视差
            val parallax = if (sky === sceneSky) 1f else 0f
            drawBitmapCover(
                canvas, sky, w, h,
                wallpaperZoom(),
                (0.5f - sceneSample.cameraX) * w * 0.05f * parallax,
                (0.5f - sceneSample.cameraY) * h * 0.03f * parallax,
            )
        } else {
            drawSceneSky(canvas, w, h, u)
            drawMilkyWay(canvas, w, h)
            drawSceneStars(canvas, w, h)
        }

        // 云/山/月是场景底图的自绘部分，只有底图确实是它时才叠
        if (sky === sceneSky) {
            val world = canvas.save()
            canvas.scale(wallpaperZoom(), wallpaperZoom(), w / 2f, h / 2f)
            drawClouds(canvas, w, h, 0.85f, 0)
            drawMountains(canvas, w, h, u)
            if (layers.aodAlpha > 0.002f) {
                val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (layers.aodAlpha * 255f).toInt())
                sceneSample.elements.getOrNull(0)?.let { drawSceneMoon(canvas, w, h, it) }
                canvas.restoreToCount(layer)
            }
            drawClouds(canvas, w, h, 0.45f, 1)
            canvas.restoreToCount(world)
        }

        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = alphaColor(0xFF02040A.toInt(), layers.dim)
        canvas.drawRect(0f, 0f, w, h, fill)

        if (layers.lockAlpha > 0.002f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (layers.lockAlpha * 255f).toInt())
            canvas.scale(layers.lockScale, layers.lockScale, w / 2f, h * 0.40f)
            drawLockOverlay(canvas, w, h, 1f)
            canvas.restoreToCount(layer)
        }

        if (layers.homeAlpha > 0.002f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (layers.homeAlpha * 255f).toInt())
            canvas.scale(layers.homeScale, layers.homeScale, w / 2f, h * 0.52f)
            drawSceneHomeIcons(canvas, w, h, 1f)
            drawSceneHomeDock(canvas, w, h, 1f)
            canvas.restoreToCount(layer)
        }

        drawSceneClock(canvas, w, h)
        val chrome = maxOf(layers.lockAlpha, layers.homeAlpha)
        if (chrome > 0.002f) drawGestureBar(canvas, w, h, chrome)
        drawVignette(canvas, w, h)
    }

    /**
     * 手绘增强版：完整轨迹按进度累积重放，停笔已在解析期折叠故不含停顿。
     *
     * 息屏态自播一遍；转场/拖动期按整轨进度 u 直接求值，与 scrub 1:1。
     * 轨迹未就绪时画底不画花，就绪回调触发重绘。
     */
    private fun drawHandPaintScene(canvas: Canvas, w: Float, h: Float, u: Float, spec: AodSceneSpec) {
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        val backdrop = backdropSource()
        logBackdrop("handpaint", backdrop)
        drawEffectBackdrop(canvas, w, h, backdrop, effEdge, if (scrubP >= 0f) SceneTimeline.localT(scrubP) else t)
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = alphaColor(0xFF02040A.toInt(), layers.dim)
        canvas.drawRect(0f, 0f, w, h, fill)

        val now = SystemClock.uptimeMillis()
        val playing = effEdge == null && scrubP < 0f && scene == SceneState.AOD
        val style = spec.handPaintStyle
        val silks = handPaintSilks
        val silk = silks?.firstOrNull()
        if (silk == null) {
            logHandPaint("pending style=$style loading=$handPaintLoading")
        } else {
            val rect = handPaintRect(w, h)
            val r = ensureHandPaintRenderer(rect[2])
            if (r == null) {
                logHandPaint("renderer-null style=$style side=${rect[2]}")
            } else {
                val progress = if (playing) {
                    if (HandPaintTimeline.start(handPaintPlay, style, now, silk.points.size)) {
                        Lg.i(TAG, "handpaint start style=$style points=${silk.points.size} dur=${handPaintPlay.durationMs}ms")
                    }
                    HandPaintTimeline.progressAt(handPaintPlay, now)
                } else {
                    HandPaintTimeline.stop(handPaintPlay)
                    1f
                }
                val added = r.advance(style, silk, progress)
                if (added > 0) {
                    logHandPaint("style=$style added=$added drawn=${r.drawn()}/${silk.points.size} p=${"%.3f".format(progress)}")
                }
                r.present(canvas, rect[0], rect[1], rect[2], layers.aodAlpha.coerceIn(0f, 1f))
            }
        }

        val lockHome = effEdge == TransitionEdge.LOCK_TO_HOME || effEdge == TransitionEdge.HOME_TO_LOCK
        val lockAlpha = layers.lockAlpha * contentGate
        if (lockAlpha > 0.002f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (lockAlpha * 255f).toInt())
            val scale = if (lockHome) 1f else layers.lockScale
            canvas.scale(scale, scale, w / 2f, h * 0.40f)
            drawLockOverlay(canvas, w, h, 1f)
            canvas.restoreToCount(layer)
        }
        val homeAlpha = layers.homeAlpha * contentGate
        if (homeAlpha > 0.002f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (homeAlpha * 255f).toInt())
            val scale = if (lockHome) 1f else layers.homeScale
            canvas.scale(scale, scale, w / 2f, h * 0.52f)
            drawSceneHomeIcons(canvas, w, h, 1f)
            drawSceneHomeDock(canvas, w, h, 1f)
            canvas.restoreToCount(layer)
        }

        drawHandPaintClock(canvas, w, h, playing && silk != null, now)
        val chrome = maxOf(lockAlpha, homeAlpha)
        if (chrome > 0.002f) drawGestureBar(canvas, w, h, chrome)
        logHandPaintLayers(lockAlpha, homeAlpha, silk != null)
        if (playing && silk != null && HandPaintTimeline.needsFrame(handPaintPlay, now)) postInvalidateOnAnimation()
    }

    /** 手绘层可见度诊断：锁屏/桌面内容层是否真的画了，轨迹在不在。 */
    private fun logHandPaintLayers(lockAlpha: Float, homeAlpha: Float, hasSilk: Boolean) {
        val now = SystemClock.uptimeMillis()
        val key = "${(lockAlpha * 20f).toInt()}|${(homeAlpha * 20f).toInt()}|$hasSilk"
        if (key == handPaintLayerLogKey && now - handPaintLayerLogAt < 1500L) return
        handPaintLayerLogKey = key
        handPaintLayerLogAt = now
        Lg.i(
            TAG,
            "handpaint layers lock=${"%.3f".format(lockAlpha)} home=${"%.3f".format(homeAlpha)} " +
                "silk=$hasSilk gate=${"%.3f".format(contentGate)} scene=$scene",
        )
    }

    /** 手绘贴图矩形：官方 dp 见方小图，中心落在屏高定比例处，与 hook 同源换算。 */
    private fun handPaintRect(w: Float, h: Float): FloatArray = HandPaintGeometry.presentRect(
        HandPaintSource.canvasPx(density), HandPaintSource.CENTER_Y, w, h,
    )

    /** 手绘时钟：笔画重放到官方延迟才淡入；透明期仍求值以推进位姿，只是不画。 */
    private fun drawHandPaintClock(canvas: Canvas, w: Float, h: Float, playing: Boolean, now: Long) {
        val fade = if (playing && handPaintPlay.running) {
            HandPaintSource.clockFadeAt(now - handPaintPlay.originMs)
        } else {
            1f
        }
        if (fade > 0.002f) {
            val layer = if (fade < 0.999f) {
                canvas.saveLayerAlpha(0f, 0f, w, h, (fade * 255f).toInt())
            } else {
                -1
            }
            drawSceneClock(canvas, w, h)
            if (layer >= 0) canvas.restoreToCount(layer)
        } else {
            logHandPaint("clock-wait fade=0 elapsed=${now - handPaintPlay.originMs}ms")
        }
    }

    /** 累积渲染器按贴图边长建一次，边长变了才重建（不按整屏短边，避免小图放大发虚）。 */
    private fun ensureHandPaintRenderer(drawSide: Float): HandPaintRenderer? {
        val side = drawSide.toInt().coerceIn(1, HandPaintRenderer.MAX_SIDE)
        val existing = handPaintRenderer
        if (existing != null && handPaintSide == side) return existing
        runCatching { existing?.close() }
        val created = runCatching { HandPaintRenderer(side) }.getOrNull() ?: run {
            Lg.w(TAG, "handpaint renderer create failed side=$side")
            return null
        }
        handPaintRenderer = created
        handPaintSide = side
        return created
    }

    private fun logHandPaint(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastHandPaintLogAt < 500L) return
        lastHandPaintLogAt = now
        Lg.i(TAG, "handpaint $msg")
    }

    /**
     * 世界层圆心：样式锚点 + 息屏时钟编辑偏移。
     *
     * 预览的编辑偏移只在绘制时相加、不写回状态机锚点，故这里取偏移而非状态机锚点。
     */
    private fun worldCenter(follows: Boolean, styleX: Float, styleY: Float): Pair<Float, Float> {
        val off = editDelegate.clockOffset(null, 0f, direct = true)
        return WorldCenterPolicy.center(follows, styleX, styleY, styleX + off.first, styleY + off.second)
    }

    /** 启航：圆形镜片展示旋转壁纸，展开时铺满整屏并交接到锁屏内容。 */
    private fun drawSailScene(canvas: Canvas, w: Float, h: Float, u: Float, spec: AodSceneSpec) {
        val aodAnchor = spec.clockAnchors[SceneState.AOD]
        val lensCenter = worldCenter(true, aodAnchor?.centerX ?: 0.5f, aodAnchor?.centerY ?: 0.6f)
        val aodX = lensCenter.first
        val sailDrift = if (editDelegate.editing) 0f else AodDrift.offsetY(SystemClock.uptimeMillis())
        val aodY = lensCenter.second + sailDrift
        val expanded = SailLensMotion.expandedScale(h / w)
        when {
            scrubP >= 0f -> SailLensMotion.sampleTimeline(scrubP, aodX, aodY, expanded, lensPose, spinDeg = lensAngleDeg)
            edge != null -> SailLensMotion.sample(edge!!, t, aodX, aodY, expanded, lensPose, spinDeg = lensAngleDeg)
            else -> SailLensMotion.sampleStatic(scene, aodX, aodY, expanded, lensPose)
        }

        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        val effT = if (scrubP >= 0f) SceneTimeline.localT(scrubP) else t

        val backdrop = backdropSource()
        logBackdrop("lens", backdrop)
        drawEffectBackdrop(canvas, w, h, backdrop, effEdge, effT)
        drawBlurClearLayer(canvas, w, h, effEdge, effT)
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = alphaColor(0xFF02040A.toInt(), layers.dim)
        canvas.drawRect(0f, 0f, w, h, fill)

        SailLensMotion.fade(effEdge, effT, sailFade)
        aodLayerAlpha = if (effEdge == null) layers.aodAlpha else sailFade.aodAlpha
        contentGate = 1f
        windowClipped = true
        if (aodLayerAlpha > 0.002f) {
            lensSpinning = lensSpinActive()
            com.coloros16.liquidglass.engine.effect.AodMotion.evaluate(
                SystemClock.uptimeMillis(), allowAodDynamic, aodColorMix, aodRotateSpeed, aodPhotoTilt, aodMotion,
            )
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (aodLayerAlpha * 255f).toInt())
            drawLensCurtain(canvas, w, h)
            lensPose.angleDeg = lensAngleDeg
            lensRenderer.draw(
                canvas, w, h, lensPose, SailLensMotion.lensZoom(lensPose.scale),
                colorMix = if (scene == SceneState.AOD) aodMotion.colorMix else 0f,
            )
            canvas.restoreToCount(layer)
        }

        val lockHome = effEdge == TransitionEdge.LOCK_TO_HOME || effEdge == TransitionEdge.HOME_TO_LOCK
        ContentWindow.lens(lensPose, w, h, contentWindow)
        val lockAlpha = layers.lockAlpha * contentGate
        if (lockAlpha > 0.002f) {
            val layer = canvas.save()
            val clip = clipContentWindow(canvas, w, h, contentWindow)
            val scale = if (lockHome) 1f else layers.lockScale
            canvas.scale(scale, scale, w / 2f, h * 0.40f)
            drawLockOverlay(canvas, w, h, 1f)
            canvas.restoreToCount(clip)
            canvas.restoreToCount(layer)
        }

        val homeAlpha = layers.homeAlpha * contentGate
        if (homeAlpha > 0.002f) {
            val layer = canvas.save()
            val clip = clipContentWindow(canvas, w, h, contentWindow)
            val scale = if (lockHome) 1f else layers.homeScale
            canvas.scale(scale, scale, w / 2f, h * 0.52f)
            drawSceneHomeIcons(canvas, w, h, 1f)
            drawSceneHomeDock(canvas, w, h, 1f)
            canvas.restoreToCount(clip)
            canvas.restoreToCount(layer)
        }

        drawSceneClock(canvas, w, h)
        val chrome = maxOf(lockAlpha, homeAlpha)
        if (chrome > 0.002f) drawGestureBar(canvas, w, h, chrome)
        logContentWindow("lens", lockAlpha, homeAlpha)
    }

    /** 镜片黑幕只画圆外：圆内由镜片自身覆盖，全屏 fill 是每帧一次的无谓绘制。 */
    private fun drawLensCurtain(canvas: Canvas, w: Float, h: Float) {
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = Color.BLACK
        val r = lensPose.scale * w
        if (r <= 0f) {
            canvas.drawRect(0f, 0f, w, h, fill)
            return
        }
        val cx = lensPose.centerX * w
        val cy = lensPose.centerY * h
        if (ContentWindow.coversScreen(r, cx, cy, w, h)) {
            logCurtain("skip-covered", r)
            return
        }
        curtainPath.reset()
        curtainPath.addRect(0f, 0f, w, h, Path.Direction.CW)
        curtainPath.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.drawPath(curtainPath, fill)
        logCurtain("ring", r)
    }

    /** 黑幕专用路径：EVEN_ODD 挖圆孔，独立持有避免污染共享 circle 的 fillType。 */
    private val curtainPath = Path().apply { fillType = Path.FillType.EVEN_ODD }

    private var curtainLogKey: String? = null
    private var curtainLogAt = 0L

    private fun logCurtain(mode: String, radius: Float) {
        val now = SystemClock.uptimeMillis()
        val key = "$mode|${(radius / 40f).toInt()}"
        if (key == curtainLogKey && now - curtainLogAt < 800L) return
        curtainLogKey = key
        curtainLogAt = now
        Lg.i(TAG, "lens-curtain mode=$mode r=${"%.1f".format(radius)} a=${"%.3f".format(aodLayerAlpha)}")
    }

    private val contentWindow = ContentWindow.Window()
    private var contentWindowLogKey: String? = null
    private var contentWindowLogAt = 0L

    /** 把内容层裁进特效窗口；NONE 时只 save 不裁，保持不接管样式的原行为。 */
    private fun clipContentWindow(canvas: Canvas, w: Float, h: Float, win: ContentWindow.Window): Int {
        val save = canvas.save()
        when (win.shape) {
            ContentWindow.Shape.NONE -> Unit
            ContentWindow.Shape.CIRCLE -> {
                circle.reset()
                circle.addCircle(win.centerX, win.centerY, win.radius, Path.Direction.CW)
                canvas.clipPath(circle)
            }

            ContentWindow.Shape.SQUARE -> {
                val half = win.side * 0.5f
                rect.set(win.centerX - half, win.centerY - half, win.centerX + half, win.centerY + half)
                circle.reset()
                circle.addRect(rect, Path.Direction.CW)
                if (win.angleDeg != 0f) {
                    rotateMatrix.setRotate(win.angleDeg, win.centerX, win.centerY)
                    circle.transform(rotateMatrix)
                }
                canvas.clipPath(circle)
            }
        }
        return save
    }

    private val rotateMatrix = android.graphics.Matrix()

    /** 裁切诊断：窗口形状与尺寸、内容层可见度，节流按量化档位。 */
    private fun logContentWindow(kind: String, lockAlpha: Float, homeAlpha: Float) {
        if (lockAlpha <= 0.002f && homeAlpha <= 0.002f) return
        val now = SystemClock.uptimeMillis()
        val size = if (contentWindow.shape == ContentWindow.Shape.CIRCLE) contentWindow.radius else contentWindow.side
        val key = "$kind|${contentWindow.shape}|${(size / 20f).toInt()}|" +
            "${(lockAlpha * 10f).toInt()}|${(homeAlpha * 10f).toInt()}"
        if (key == contentWindowLogKey && now - contentWindowLogAt < 800L) return
        contentWindowLogKey = key
        contentWindowLogAt = now
        Lg.i(
            TAG,
            "content-window kind=$kind shape=${contentWindow.shape} size=${"%.1f".format(size)}" +
                " center=${"%.1f".format(contentWindow.centerX)},${"%.1f".format(contentWindow.centerY)}" +
                " lock=${"%.3f".format(lockAlpha)} home=${"%.3f".format(homeAlpha)}",
        )
    }

    /**
     * 照片类息屏：绿幕帧当内容窗口，方形网格从时钟位置展开铺满整屏。
     * 网格外用黑色收口，内容取同框壁纸（息屏期按样式模糊）。
     */
    private fun drawPhotoScene(canvas: Canvas, w: Float, h: Float, u: Float, spec: AodSceneSpec) {
        val photo = spec.photo ?: return
        val anchor = spec.clockAnchors[SceneState.AOD]
        val drift = if (edge == null && scrubP < 0f && scene == SceneState.AOD && !editDelegate.editing) {
            AodDrift.offsetY(SystemClock.uptimeMillis())
        } else {
            0f
        }
        val photoCenter = worldCenter(
            WorldCenterPolicy.photoFollowsClock(photo.id),
            anchor?.centerX ?: 0.5f, anchor?.centerY ?: 0.435f,
        )
        val anchorX = photoCenter.first - 0.5f
        val anchorY = 0.5f - (photoCenter.second + drift)

        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        val effT = if (scrubP >= 0f) SceneTimeline.localT(scrubP) else t
        val aspect = h / w
        val paired = PhotoStyleMotion.pairedActive(photo.id, framePair, framePhoto != null)
        if (effEdge == null) {
            PhotoStyleMotion.sampleStatic(photo, scene, aspect, anchorX, anchorY, photoMotion, paired)
        } else {
            PhotoStyleMotion.sample(photo, effEdge, effT, aspect, anchorX, anchorY, photoMotion, paired)
        }

        contentGate = 1f
        windowClipped = true
        aodLayerAlpha = 1f

        val backdrop = backdropSource()
        logBackdrop("photo", backdrop)
        drawEffectBackdrop(canvas, w, h, backdrop, effEdge, effT)
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = alphaColor(0xFF02040A.toInt(), layers.dim)
        canvas.drawRect(0f, 0f, w, h, fill)

        val frame = photoFrame
        if (frame != null) {
            val pair = if (paired) framePhoto else null
            com.coloros16.liquidglass.engine.effect.AodMotion.evaluate(
                SystemClock.uptimeMillis(), allowAodDynamic, aodColorMix, aodRotateSpeed, aodPhotoTilt, aodMotion,
            )
            val tilt = if (scene == SceneState.AOD && effEdge == null) aodMotion.photoTiltDeg else 0f
            val values = photoMotion.v
            val savedRotation = values[PhotoStyle.SLOT_ROTATION]
            values[PhotoStyle.SLOT_ROTATION] = savedRotation + tilt
            val photoAlpha = values[PhotoStyle.SLOT_PHOTO_ALPHA]
            val reveal = if (photoAlpha < 0.999f) {
                captureRevealTarget(canvas, w, h, effEdge, photoAlpha)
            } else {
                logReveal("off alpha=${"%.3f".format(photoAlpha)}", "off")
                null
            }
            photoRenderer.draw(
                canvas, w, h, values, frame, style.accent,
                layers.dim * (1f - photoMotion.ratios[SceneState.AOD.ordinal]),
                values[PhotoStyle.SLOT_BLUR],
                wallpaperZoom(),
                pair, frameCropX, frameCropY, frameCropSize, frameCropAngle,
                revealPhoto = reveal,
            )
            values[PhotoStyle.SLOT_ROTATION] = savedRotation
        }
        updatePhotoFrame(photo)

        val lockHome = effEdge == TransitionEdge.LOCK_TO_HOME || effEdge == TransitionEdge.HOME_TO_LOCK
        ContentWindow.photo(photoMotion.v, w, h, contentWindow)
        val lockAlpha = layers.lockAlpha * contentGate
        if (lockAlpha > 0.002f) {
            val layer = canvas.save()
            val clip = clipContentWindow(canvas, w, h, contentWindow)
            val scale = if (lockHome) 1f else layers.lockScale
            canvas.scale(scale, scale, w / 2f, h * 0.40f)
            drawLockOverlay(canvas, w, h, 1f)
            canvas.restoreToCount(clip)
            canvas.restoreToCount(layer)
        }

        val homeAlpha = layers.homeAlpha * contentGate
        if (homeAlpha > 0.002f) {
            val layer = canvas.save()
            val clip = clipContentWindow(canvas, w, h, contentWindow)
            val scale = if (lockHome) 1f else layers.homeScale
            canvas.scale(scale, scale, w / 2f, h * 0.52f)
            drawSceneHomeIcons(canvas, w, h, 1f)
            drawSceneHomeDock(canvas, w, h, 1f)
            canvas.restoreToCount(clip)
            canvas.restoreToCount(layer)
        }

        drawSceneClock(canvas, w, h)
        val chrome = maxOf(lockAlpha, homeAlpha)
        if (chrome > 0.002f) drawGestureBar(canvas, w, h, chrome)
        logContentWindow("photo", lockAlpha, homeAlpha)
    }

    /** 照片揭开目标层快照：photoAlpha<1 时色键内容混向的目标态整屏画面（对齐 u_launcher_effect）。 */
    private fun captureRevealTarget(
        canvas: Canvas,
        w: Float,
        h: Float,
        effEdge: TransitionEdge?,
        revealAlpha: Float,
    ): Bitmap? {
        val target = effEdge?.to ?: scene
        if (target == SceneState.AOD) {
            logReveal("skip target=AOD", "aod")
            return null
        }
        val backdropId = System.identityHashCode(backdropSource())
        val key = "${target.name}:${w.toInt()}x${h.toInt()}:$backdropId:$useSystemWallpaper:$uiScale" +
            ":$lockWallpaperEffect:$lockSpecialEffect:$lockWallpaperIntensity:$effectLayerKey" +
            ":${System.identityHashCode(homeIcons)}:$homePages"
        val cached = revealCache
        if (cached != null && revealCacheKey == key) {
            if (!cached.isRecycled) {
                logReveal("hit target=$target alpha=${"%.3f".format(revealAlpha)}", key)
                return cached
            }
            photoRenderer.forgetReveal()
            revealCache = null
            revealCacheKey = null
            logReveal("stale target=$target 快照已回收，重建", "stale:$key")
        }
        val rw = (w / 2f).toInt().coerceAtLeast(2)
        val rh = (h / 2f).toInt().coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        val off = Canvas(bitmap)
        off.scale(rw / w, rh / h)
        val backdrop = backdropSource()
        logBackdrop("reveal", backdrop)
        drawEffectBackdrop(off, w, h, backdrop, null, 0f, target)
        when (target) {
            SceneState.LOCK -> drawLockOverlay(off, w, h, 1f, 0f, frozen = true)
            SceneState.HOME -> {
                drawDock(off, homeGeometry(w, h))
                drawAppGrid(off, homeGeometry(w, h), -1L)
                drawHomeDots(off, homeGeometry(w, h))
            }
            SceneState.AOD -> Unit
        }
        photoRenderer.forgetReveal()
        revealCache?.recycle()
        revealCache = bitmap
        revealCacheKey = key
        logReveal(
            "build target=$target alpha=${"%.3f".format(revealAlpha)} size=${rw}x$rh " +
                "lockFrozen=${target == SceneState.LOCK} backdrop=${backdrop != null} icons=${homeIcons.size}",
            key,
        )
        return bitmap
    }

    private var revealCache: Bitmap? = null
    private var revealCacheKey: String? = null
    private var revealLogKey: String? = null
    private var revealLogAt = 0L

    /** 揭开层诊断：按目标态与快照身份节流，换底图/换目标必打一条。 */
    private fun logReveal(msg: String, key: String) {
        val now = SystemClock.uptimeMillis()
        if (key == revealLogKey && now - revealLogAt < 800L) return
        revealLogKey = key
        revealLogAt = now
        Lg.i(TAG, "photo-reveal $msg")
    }

    private var enterZoomFrom = 0f
    private var enterZoomTo = 0f
    private var enterZoomStartMs = 0L
    private var zoomSettleFrom = 0f
    private var zoomSettleTo = 0f
    private var zoomSettleStartMs = 0L
    private var zoomSettleScheduled = false
    private val zoomSettleTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            zoomSettleScheduled = false
            if (zoomSettleFrom <= 0f && enterZoomFrom <= 0f) return
            if (!isAttachedToWindow) {
                zoomSettleFrom = 0f
                return
            }
            // 出边没在跑时不得走这条：起点是旧的 zoomSettleStartMs ⇒ 恒 done，
            // 会每帧刷一条 from=0.0 的假 settle 日志，还踩住节流窗把入边中间帧全吃掉
            if (zoomSettleFrom > 0f) {
                val elapsed = SystemClock.uptimeMillis() - zoomSettleStartMs
                if (WallpaperZoomSettle.done(elapsed)) {
                    logZoomSettle(zoomSettleTo, 1f, true)
                    zoomSettleFrom = 0f
                } else {
                    logZoomSettle(wallpaperZoom(), WallpaperZoomSettle.progress(elapsed), false)
                }
            }
            if (enterZoomFrom > 0f) {
                val el = SystemClock.uptimeMillis() - enterZoomStartMs
                if (WallpaperZoomSettle.done(el)) {
                    logZoomSettle(enterZoomTo, 1f, true, enter = true)
                    enterZoomFrom = 0f
                } else {
                    logZoomSettle(wallpaperZoom(), WallpaperZoomSettle.progress(el), false, enter = true)
                }
            }
            invalidate()
            if (zoomSettleFrom > 0f || enterZoomFrom > 0f) scheduleZoomSettle()
        }
    }

    private fun scheduleZoomSettle() {
        if (zoomSettleScheduled) return
        zoomSettleScheduled = true
        Choreographer.getInstance().postFrameCallback(zoomSettleTick)
    }

    /**
     * 落位补间：边末缩放与目标静止态缩放不同时补一段，两个口径都不改。
     *
     * 边内 LOCK↔HOME 恒 1.1、静止桌面 1.0，直接交接会跳 9.1%（上沿 120px）。
     */
    private val settleFromLayers = SystemSceneMotion.Layers()
    private val settleToLayers = SystemSceneMotion.Layers()

    /**
     * 入边补间：进边那一帧的缩放与上一静止态不同时补一段。
     *
     * 与落位补间对称 —— `HOME_TO_LOCK` 边内恒 1.1、HOME 静止 1.0，不补就是起始帧跳 10%。
     */
    private fun startEnterZoom(edge: TransitionEdge) {
        // 起点取该边起点态的引擎真值，不取「上一次见过的静止态」：
        // 后者在页面停过别的态之后会一直挂着，把两端同值的边也补成跳变
        SystemSceneMotion.sampleStatic(edge.from, settleFromLayers)
        SystemSceneMotion.sample(edge, 0f, settleToLayers)
        val fromZoom = settleFromLayers.wallpaperScale
        val toZoom = settleToLayers.wallpaperScale
        if (!WallpaperZoomSettle.needed(fromZoom, toZoom)) {
            enterZoomFrom = 0f
            return
        }
        enterZoomFrom = fromZoom
        enterZoomTo = toZoom
        enterZoomStartMs = SystemClock.uptimeMillis()
        scheduleZoomSettle()
    }

    private fun startZoomSettle(prevEdge: TransitionEdge, target: SceneState) {
        SystemSceneMotion.sample(prevEdge, 1f, settleFromLayers)
        SystemSceneMotion.sampleStatic(target, settleToLayers)
        val from = settleFromLayers
        val to = settleToLayers
        if (!WallpaperZoomSettle.needed(from.wallpaperScale, to.wallpaperScale)) {
            zoomSettleFrom = 0f
            return
        }
        zoomSettleFrom = from.wallpaperScale
        zoomSettleTo = to.wallpaperScale
        zoomSettleStartMs = SystemClock.uptimeMillis()
        scheduleZoomSettle()
    }

    /** 当前壁纸缩放：补间期走补间值，其余取引擎分层真值。 */
    private fun wallpaperZoom(): Float {
        if (enterZoomFrom > 0f) {
            val elapsed = SystemClock.uptimeMillis() - enterZoomStartMs
            if (WallpaperZoomSettle.done(elapsed)) {
                enterZoomFrom = 0f
            } else {
                return WallpaperZoomSettle.zoomAt(enterZoomFrom, enterZoomTo, elapsed)
            }
        }
        if (zoomSettleFrom > 0f) {
            val elapsed = SystemClock.uptimeMillis() - zoomSettleStartMs
            return WallpaperZoomSettle.zoomAt(
                zoomSettleFrom, zoomSettleTo, elapsed,
            )
        }
        return layers.wallpaperScale
    }

    private var zoomSettleLogAt = 0L
    private var enterZoomLogAt = 0L

    private fun logZoomSettle(zoom: Float, progress: Float, done: Boolean, enter: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        // 节流窗必须短于补间时长，否则整段只剩一条中间帧、逐帧验收无从取数；
        // 两条补间各用各的窗口，共用会让先跑的那条把后跑那条的中间帧吃掉
        val last = if (enter) enterZoomLogAt else zoomSettleLogAt
        if (!done && now - last < ZOOM_SETTLE_LOG_GAP_MS) return
        if (enter) enterZoomLogAt = now else zoomSettleLogAt = now
        val from = if (enter) enterZoomFrom else zoomSettleFrom
        val to = if (enter) enterZoomTo else zoomSettleTo
        // 入边/落位两条补间必须能分开认，否则「按锁屏键那一跳补没补」取证不到
        val tag = if (enter) "zoom-enter" else "zoom-settle"
        Lg.i(
            TAG,
            "$tag from=$from to=$to " +
                "zoom=${"%.4f".format(zoom)} p=${"%.3f".format(progress)} done=$done",
        )
    }

    /** 本次播放的边长，由控制端在 `clock.play` 同处写入，保证求值分母与时钟一致。 */
    var playbackEdgeMs: Long = 0L

    private var clockTailEdge: TransitionEdge? = null
    private var clockTailEdgeMs = 0L
    private var clockTailStartMs = 0L
    private var clockTailScheduled = false
    private val clockTailTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            clockTailScheduled = false
            clockTailEdge ?: return
            if (!isAttachedToWindow) {
                clockTailEdge = null
                return
            }
            val elapsed = SystemClock.uptimeMillis() - clockTailStartMs
            if (AodClockEntry.tailDone(elapsed, clockTailEdgeMs)) {
                clockTailEdge = null
            }
            invalidate()
            if (clockTailEdge != null) scheduleClockTail()
        }
    }

    private fun scheduleClockTail() {
        if (clockTailScheduled) return
        clockTailScheduled = true
        Choreographer.getInstance().postFrameCallback(clockTailTick)
    }

    /** 当前时钟进场缩放：播放/拖动取引擎 layers.aodClockScale（单一真值），边末后按补尾进度，静止态=1。 */
    private fun clockEntryScale(): Float {
        if (clockTailEdge != null) {
            val elapsed = SystemClock.uptimeMillis() - clockTailStartMs
            return AodClockEntry.scaleAt(AodClockEntry.tailProgress(elapsed, clockTailEdgeMs))
        }
        return layers.aodClockScale
    }

    /** 取当前视频时间对应的帧；未解码时沿用上一帧，并按进度前后预取。 */
    private fun updatePhotoFrame(photo: PhotoStyle) {
        val sequence = photoSequence ?: return
        val index = photo.frameIndex(photoMotion.v[PhotoStyle.SLOT_VIDEO])
        sequence.frame(index)?.let { photoFrame = it }
        sequence.prefetch(
            intArrayOf(
                index + 1, index + 2, index + 3, index + 4,
                index - 1, index - 2, index - 3, index - 4,
            ),
        )
    }

    /**
     * 本帧求值用的边长。
     *
     * 全文件只允许这一处计算：转场层有三条入口（非 SCENE 的 `:752`、SCENE 的 `sampleLayers`、
     * 以及它们各自的调用点），任何一条自己另算一次就会出现「一处用 3840、一处用 800」的漂移。
     * 播放期用账本（与时钟同源），非播放期按当前样式+边实时算。
     */
    private fun edgeMsFor(e: TransitionEdge): Long =
        if (playbackEdgeMs > 0L) playbackEdgeMs
        else AodSceneCatalog.durationOf(styleId, e, homeSettings())

    private fun sampleLayers() {
        when {
            scrubP >= 0f -> SystemSceneMotion.sampleTimeline(scrubP, layers)
            edge != null -> SystemSceneMotion.sample(edge!!, t, layers, edgeMs = edgeMsFor(edge!!))
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
        alpha: Float = 1f,
    ) {
        val box = BackdropFit.coverRect(w, h, bitmap.width, bitmap.height, zoom) ?: return
        src.set(0, 0, bitmap.width, bitmap.height)
        dst.set(box[0] + dx, box[1] + dy, box[2] + dx, box[3] + dy)
        iconPaint.shader = null
        iconPaint.colorFilter = null
        iconPaint.alpha = EffectOverlayPolicy.alphaByte(alpha)
        canvas.drawBitmap(bitmap, src, dst, iconPaint)
        iconPaint.alpha = 255
    }

    private fun drawSceneSky(canvas: Canvas, w: Float, h: Float, u: Float) {
        fill.style = Paint.Style.FILL
        fill.shader = linearOf(SceneSkyMath.skyGradient(w, h, u))
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        for (g in SceneSkyMath.skyGlows(w, h)) radialOf(canvas, g)
    }

    private fun drawMilkyWay(canvas: Canvas, w: Float, h: Float) {
        val save = canvas.save()
        canvas.rotate(
            SceneSkyMath.MILKYWAY_ANGLE_DEG,
            w * SceneSkyMath.MILKYWAY_PIVOT_X,
            h * SceneSkyMath.MILKYWAY_PIVOT_Y,
        )
        fill.style = Paint.Style.FILL
        fill.shader = linearOf(SceneSkyMath.milkyWayGradient(w, h, sceneSample.light))
        canvas.drawRect(
            w * SceneSkyMath.MILKYWAY_LEFT, h * SceneSkyMath.MILKYWAY_TOP,
            w * SceneSkyMath.MILKYWAY_RIGHT, h * SceneSkyMath.MILKYWAY_BOTTOM, fill,
        )
        fill.shader = null
        canvas.restoreToCount(save)
    }

    private fun drawSceneStars(canvas: Canvas, w: Float, h: Float) {
        SceneSkyMath.stars(
            stars, w, h, sceneSample.light,
            sceneSample.cameraX, sceneSample.cameraY, sceneSample.cameraScale,
            density * uiScale, starDots,
        )
        fill.style = Paint.Style.FILL
        fill.shader = null
        for (d in starDots) {
            fill.color = d.argb
            canvas.drawCircle(d.cx, d.cy, d.radius, fill)
        }
    }

    private fun drawClouds(canvas: Canvas, w: Float, h: Float, strength: Float, layer: Int) {
        SceneSkyMath.clouds(
            cloudBlobs, w, h, strength, layer,
            sceneSample.cameraX, sceneSample.cameraY, cloudSpecs,
        )
        for (c in cloudSpecs) {
            val save = canvas.save()
            canvas.translate(c.cx, c.cy)
            canvas.scale(1f, c.yScale)
            fill.style = Paint.Style.FILL
            fill.shader = radialOf(c.rx, c.stops)
            canvas.drawCircle(0f, 0f, c.rx, fill)
            fill.shader = null
            canvas.restoreToCount(save)
        }
    }

    private fun drawMountains(canvas: Canvas, w: Float, h: Float, u: Float) {
        val drift = SceneSkyMath.ridgeDrift(h, sceneSample.cameraY)
        buildRidge(SceneSkyMath.ridgePath(backRidge, w, h, drift))
        fill.style = Paint.Style.FILL
        fill.shader = linearOf(SceneSkyMath.ridgeBackGradient(w, h, u, drift))
        canvas.drawPath(path, fill)
        fill.shader = null

        buildRidge(SceneSkyMath.ridgePath(frontRidge, w, h, drift))
        fill.style = Paint.Style.FILL
        fill.shader = linearOf(SceneSkyMath.ridgeFrontGradient(w, h, u, drift))
        canvas.drawPath(path, fill)
        fill.shader = null
    }

    private fun buildRidge(r: RidgePath) {
        path.reset()
        path.moveTo(r.startX, r.startY)
        path.lineTo(r.firstX, r.firstY)
        for (c in r.cubics) path.cubicTo(c.c1x, c.c1y, c.c2x, c.c2y, c.x, c.y)
        path.lineTo(r.endX, r.endY)
        path.close()
    }

    private fun linearOf(s: LinearSpec): LinearGradient = LinearGradient(
        s.x0, s.y0, s.x1, s.y1,
        IntArray(s.stops.size) { s.stops[it].argb },
        FloatArray(s.stops.size) { s.stops[it].position },
        Shader.TileMode.CLAMP,
    )

    private fun radialOf(radius: Float, stops: List<GradientStop>): RadialGradient = RadialGradient(
        0f, 0f, radius,
        IntArray(stops.size) { stops[it].argb },
        FloatArray(stops.size) { stops[it].position },
        Shader.TileMode.CLAMP,
    )

    private fun radialShaderOf(s: RadialSpec): RadialGradient = if (s.stops.size == 2) {
        RadialGradient(s.cx, s.cy, s.radius, s.stops[0].argb, s.stops[1].argb, Shader.TileMode.CLAMP)
    } else {
        RadialGradient(
            s.cx, s.cy, s.radius,
            IntArray(s.stops.size) { s.stops[it].argb },
            FloatArray(s.stops.size) { s.stops[it].position },
            Shader.TileMode.CLAMP,
        )
    }

    private fun radialOf(canvas: Canvas, s: RadialSpec) {
        fill.style = Paint.Style.FILL
        fill.shader = radialShaderOf(s)
        canvas.drawCircle(s.cx, s.cy, s.radius, fill)
        fill.shader = null
    }

    private fun drawSceneMoon(canvas: Canvas, w: Float, h: Float, pose: ElementPose) {
        val radius = SceneSkyMath.moonRadius(w, pose.scale)
        val cx = pose.x * w
        val cy = pose.y * h

        for (g in SceneSkyMath.moonGlows(cx, cy, radius)) radialOf(canvas, g)

        fill.style = Paint.Style.FILL
        fill.shader = radialShaderOf(SceneSkyMath.moonBody(cx, cy, radius))
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null

        path.reset()
        path.addCircle(cx, cy, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(path)

        for (g in SceneSkyMath.moonSeas(cx, cy, radius)) radialOf(canvas, g)

        SceneSkyMath.moonCraters(moonMarks, cx, cy, radius, moonCraterSpecs)
        for (g in moonCraterSpecs) radialOf(canvas, g)

        radialOf(canvas, SceneSkyMath.moonSpecular(cx, cy, radius))

        fill.shader = linearOf(SceneSkyMath.moonTerminator(cx, cy, radius))
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null

        fill.shader = radialShaderOf(SceneSkyMath.moonNightSide(cx, cy, radius))
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null
        canvas.restoreToCount(save)

        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = dp(SceneSkyMath.MOON_STROKE_DP)
        stroke.shader = linearOf(SceneSkyMath.moonRimGradient(cx, cy, radius))
        canvas.drawCircle(cx, cy, radius - dp(SceneSkyMath.MOON_STROKE_INSET_DP), stroke)
        stroke.shader = null

        path.reset()
        path.addOval(
            r(
                cx + radius * SceneSkyMath.MOON_VEIL_LEFT, cy + radius * SceneSkyMath.MOON_VEIL_TOP,
                cx + radius * SceneSkyMath.MOON_VEIL_RIGHT, cy + radius * SceneSkyMath.MOON_VEIL_BOTTOM,
            ),
            Path.Direction.CW,
        )
        val veil = canvas.save()
        canvas.clipPath(path)
        radialOf(canvas, SceneSkyMath.moonVeil(cx, cy, radius))
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
        val geo = homeGeometry(w, h)
        val pose = homeStaggerPose(h)
        logHomeStagger(pose)
        drawAppGrid(canvas, geo, iconEntranceElapsedMs(), pose.first, pose.second)
        drawHomeDots(canvas, geo, pose.first, pose.second)
        canvas.restoreToCount(layer)
    }

    private fun drawSceneHomeDock(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val layer = if (alpha < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (alpha * 255f).toInt())
        } else {
            canvas.save()
        }
        val pose = homeStaggerPose(h)
        drawDock(canvas, homeGeometry(w, h), pose.first, pose.second)
        canvas.restoreToCount(layer)
    }

    /**
     * 桌面层错峰可见度与位移：整层同相位，取末槽（退场零延迟、入场最后落位）。
     *
     * 窗口从中心收缩，外围元素本来就更晚被裁；按元素序号给延迟会让外围元素
     * 在轮到自己之前就被窗口吃掉（零位移消失）。取末槽保证退场一开始就位移。
     */
    private fun homeStaggerPose(h: Float): Pair<Float, Float> {
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        if (effEdge == null) return 1f to 0f
        val entering = effEdge.to == SceneState.HOME
        val layerProgress = if (entering) layers.homeAlpha else 1f - layers.homeAlpha
        val travel = if (entering || !windowClipped) HOME_STAGGER_TRAVEL else HOME_STAGGER_EXIT_TRAVEL
        SystemSceneMotion.contentStagger(
            layerProgress, SystemSceneMotion.CONTENT_STAGGER_COUNT - 1, entering, travel, homePose,
            direct = scrubP >= 0f,
        )
        val visible = if (entering) homePose.progress else 1f - homePose.progress
        return visible to homePose.shift * h
    }

    private val homePose = SystemSceneMotion.ContentPose()

    private var homeStaggerLogKey: String? = null
    private var homeStaggerLogAt = 0L

    /** 桌面层错峰诊断：可见度与像素位移，节流按量化档位。 */
    private fun logHomeStagger(pose: Pair<Float, Float>) {
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        if (effEdge == null) return
        val now = SystemClock.uptimeMillis()
        val key = "${effEdge.name}|${(pose.first * 20f).toInt()}"
        if (key == homeStaggerLogKey && now - homeStaggerLogAt < 800L) return
        homeStaggerLogKey = key
        homeStaggerLogAt = now
        Lg.i(
            TAG,
            "home-stagger edge=${effEdge.name} clipped=$windowClipped" +
                " v=${"%.3f".format(pose.first)} dy=${"%.1f".format(pose.second)}",
        )
    }

    /** 镜片款息屏时钟淡入淡出系数：与息屏层同窗同曲线，只作用息屏段，其它态恒 1。 */
    private fun lensClockFade(): Float {
        if (sceneSpec?.lens != true) return 1f
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        if (effEdge == null) return 1f
        val effT = if (scrubP >= 0f) SceneTimeline.localT(scrubP) else t
        SailLensMotion.fade(effEdge, effT, sailFade)
        val a = sailFade.aodClockAlpha
        logClockFade(effEdge, effT, a)
        return a
    }

    private var clockFadeLogKey: String? = null
    private var clockFadeLogAt = 0L

    private fun logClockFade(edge: TransitionEdge?, t: Float, alpha: Float) {
        val now = SystemClock.uptimeMillis()
        val key = "${edge?.name}|${(alpha * 20f).toInt()}"
        if (key == clockFadeLogKey && now - clockFadeLogAt < 800L) return
        clockFadeLogKey = key
        clockFadeLogAt = now
        Lg.i(TAG, "lens-clockfade edge=${edge?.name} t=${"%.3f".format(t)} a=${"%.3f".format(alpha)}")
    }

    /** 锁屏↔桌面整屏轻模糊转清晰：画在锐利底图之后、压暗层之前，两者被同等压暗。 */
    private fun drawBlurClearLayer(canvas: Canvas, w: Float, h: Float, effEdge: TransitionEdge?, effT: Float) {
        val a = SailLensMotion.blurClearAlpha(effEdge, effT)
        if (a <= 0.002f) return
        lensRenderer.drawSoftBackdrop(canvas, w, h, a, wallpaperZoom())
        logBlurClear(effEdge, effT, a)
    }

    private var blurClearLogKey: String? = null
    private var blurClearLogAt = 0L

    private fun logBlurClear(edge: TransitionEdge?, t: Float, alpha: Float) {
        val now = SystemClock.uptimeMillis()
        val key = "${edge?.name}|${(alpha * 20f).toInt()}"
        if (key == blurClearLogKey && now - blurClearLogAt < 800L) return
        blurClearLogKey = key
        blurClearLogAt = now
        Lg.i(
            TAG,
            "lens-blurclear edge=${edge?.name} t=${"%.3f".format(t)} a=${"%.3f".format(alpha)} " +
                "zoom=${"%.3f".format(wallpaperZoom())}",
        )
    }

    /** 全场景只画一颗时钟，位置/缩放/透明度/旋转由时钟状态机逐帧插值；布局配置的偏移随插值一起过渡。 */
    private fun drawSceneClock(canvas: Canvas, w: Float, h: Float) {
        if (sceneSpec == null && edge == null && scrubP < 0f && clockTailEdge == null &&
            editDelegate.drawClockOverride(canvas, w, h, timeText, dateText, sceneClockColor())
        ) return
        val pose = clockMachine.pose
        val a = (pose.alpha * lensClockFade()).coerceIn(0f, 1f)
        if (a <= 0.002f) return
        val drift = if (edge == null && scrubP < 0f && scene == SceneState.AOD && !editDelegate.editing) {
            AodDrift.offsetY(SystemClock.uptimeMillis())
        } else {
            0f
        }
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        val effT = if (scrubP >= 0f) SceneTimeline.localT(scrubP) else t
        val off = editDelegate.clockOffset(effEdge, effT, scrubP >= 0f)
        val cx = (pose.centerX + off.first) * w
        val cy = (pose.centerY + off.second + drift) * h
        val entry = AodClockEntry.blend(clockEntryScale(), editDelegate.aodWeight(effEdge, effT))
        val clockScale = pose.scale * entry
        val tookOver = editDelegate.drawSceneClockAt(canvas, w, h, cx, cy, a, pose.rotationDeg, effEdge, effT, timeText, dateText, clockScale, sceneClockColor(), scrubP >= 0f)
        logClockScale(tookOver, pose.scale, entry, clockScale, effEdge, effT)
        if (tookOver) return
        val layer = if (a < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (a * 255f).toInt())
        } else {
            -1
        }
        val rotated = if (pose.rotationDeg != 0f) {
            canvas.save().also { canvas.rotate(pose.rotationDeg, cx, cy) }
        } else {
            -1
        }
        drawClockBlock(canvas, cx, cy, h * 0.145f * clockScale, sceneClockColor(), 0.62f)
        if (rotated >= 0) canvas.restoreToCount(rotated)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /** 时钟字号链路诊断：节流打印接管方与三个乘子，定位跨态字号跳变。 */
    private fun logClockScale(tookOver: Boolean, poseScale: Float, entry: Float, final: Float, edge: TransitionEdge?, t: Float) {
        val now = SystemClock.uptimeMillis()
        val key = "$tookOver|${(final * 200f).toInt()}|${edge?.name}|${(t * 20f).toInt()}"
        if (key == clockScaleLogKey && now - clockScaleLogAt < 1500L) return
        clockScaleLogKey = key
        clockScaleLogAt = now
        Lg.i(TAG, "clock-scale delegate=$tookOver pose=$poseScale entry=$entry final=$final edge=$edge t=$t scene=$scene scrub=$scrubP")
    }

    private var clockScaleLogKey = ""
    private var clockScaleLogAt = 0L

    private fun sceneClockColor(): Int = when {
        scrubP >= 0f -> if (scrubP <= 0.5f) {
            lerpColor(AOD_CLOCK_COLOR, LOCK_CLOCK_COLOR, scrubP / 0.5f)
        } else {
            lerpColor(LOCK_CLOCK_COLOR, HOME_CLOCK_COLOR, (scrubP - 0.5f) / 0.5f)
        }

        edge != null -> lerpColor(
            clockColorOf(edge!!.from),
            clockColorOf(edge!!.to),
            SystemSceneMotion.clockCurve(edge!!).map(t),
        )

        else -> clockColorOf(scene)
    }

    private fun clockColorOf(state: SceneState): Int = when (state) {
        SceneState.AOD -> AOD_CLOCK_COLOR
        SceneState.LOCK -> LOCK_CLOCK_COLOR
        SceneState.HOME -> HOME_CLOCK_COLOR
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

    /** 锁屏浮层逐元素错峰：天气行 index0、通知卡 index1，位移与透明度各自独立。 */
    private val lockPose = SystemSceneMotion.ContentPose()

    /**
     * 元素可见度与位移：退场时 progress 是「已让位程度」，可见度取 1-progress。
     *
     * 有窗口裁切的样式退场走大行程，让内容在仍可见时就滑出窗口，而不是淡没后才被裁。
     */
    private fun lockElementPose(index: Int, h: Float): Pair<Float, Float> {
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        if (effEdge == null) return 1f to 0f
        val entering = effEdge.to == SceneState.LOCK
        val layerProgress = if (entering) layers.lockAlpha else 1f - layers.lockAlpha
        val travel = lockExitTravel(entering, index)
        SystemSceneMotion.contentStagger(
            layerProgress, index, entering, travel, lockPose, direct = scrubP >= 0f,
        )
        val visible = if (entering) lockPose.progress else 1f - lockPose.progress
        return visible to lockPose.shift * h
    }

    /** 当前样式是否靠窗口裁切承担进出场（镜片圆窗 / 照片方窗）。 */
    private var windowClipped = false

    /**
     * 按窗口形状与元素选退场行程：方窗收拢慢需更大让位，通知卡底沿更低需再大一档。
     *
     * 解锁边窗口恒覆盖全屏、不裁切内容，大行程只会把内容甩出屏顶，故走小行程。
     */
    private fun lockExitTravel(entering: Boolean, index: Int): Float {
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        if (entering || !windowClipped) return LOCK_STAGGER_TRAVEL
        if (effEdge == TransitionEdge.LOCK_TO_HOME) return LOCK_STAGGER_TRAVEL
        val square = contentWindow.shape == ContentWindow.Shape.SQUARE
        return when (index) {
            LOCK_CARD_INDEX -> if (square) LOCK_STAGGER_EXIT_TRAVEL_CARD_SQUARE else LOCK_STAGGER_EXIT_TRAVEL_CARD
            else -> if (square) LOCK_STAGGER_EXIT_TRAVEL_SQUARE else LOCK_STAGGER_EXIT_TRAVEL
        }
    }

    /**
     * 锁屏层（天气行 + 通知卡）。
     *
     * [frozen] = 画静态目标态：跳过逐元素错峰，按满可见度、零位移画。
     * 揭开快照必须用它 —— 快照是「目标态整屏画面」，带转场进度画会把当前姿态烤进位图，
     * 而位图按目标态缓存（键里没有 edge/t），整条边都会沿用那一帧的姿态。
     */
    private fun drawLockOverlay(
        canvas: Canvas,
        w: Float,
        h: Float,
        alpha: Float,
        shift: Float = 0f,
        frozen: Boolean = false,
    ) {
        val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (alpha.coerceIn(0f, 1f) * 255f).toInt())
        if (shift != 0f) canvas.translate(0f, shift)

        val weatherPose = if (frozen) 1f to 0f else lockElementPose(LOCK_WEATHER_INDEX, h)
        val cardPose = if (frozen) 1f to 0f else lockElementPose(LOCK_CARD_INDEX, h)
        if (!frozen) logLockStagger(weatherPose, cardPose)
        drawLockWeather(canvas, w, h, weatherPose.first, weatherPose.second)
        drawLockCard(canvas, w, h, cardPose.first, cardPose.second)

        canvas.restoreToCount(layer)
    }

    private var lockStaggerLogKey: String? = null
    private var lockStaggerLogAt = 0L
    private var previewFrameLogAt = 0L

    /**
     * 预览侧逐帧通道：与 hook 的 `stage-frame-fast` 对应，供不需要 SystemUI 的 E2 取证。
     *
     * 边内 120ms、静止 800ms；静止态不打（只在播放/拖动时输出）。
     * `p` 是当前边的局部进度（拖动时取 `localT`），层量全部取自本帧 `layers`。
     */
    private fun logPreviewFrame() {
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        if (effEdge == null) return
        val now = SystemClock.uptimeMillis()
        val inEdge = scrubP < 0f && t < 1f
        if (now - previewFrameLogAt < (if (inEdge) PREVIEW_FRAME_IN_EDGE_MS else PREVIEW_FRAME_IDLE_MS)) return
        previewFrameLogAt = now
        val p = if (scrubP >= 0f) SceneTimeline.localT(scrubP) else t
        Lg.i(
            TAG,
            "preview-frame: edge=${effEdge.name} mode=${if (scrubP >= 0f) "scrub" else "play"}" +
                " p=${"%.3f".format(p)} lk=${"%.3f".format(layers.lockAlpha)}" +
                " aod=${"%.3f".format(layers.aodAlpha)} home=${"%.3f".format(layers.homeAlpha)}" +
                " dim=${"%.3f".format(layers.dim)} wp=${"%.3f".format(wallpaperZoom())}",
        )
    }

    /** 逐元素错峰诊断：两元素可见度与像素位移，节流按量化档位。 */
    private fun logLockStagger(weather: Pair<Float, Float>, card: Pair<Float, Float>) {
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        if (effEdge == null) return
        val now = SystemClock.uptimeMillis()
        val key = "${effEdge.name}|${(weather.first * 20f).toInt()}|${(card.first * 20f).toInt()}"
        if (key == lockStaggerLogKey && now - lockStaggerLogAt < 800L) return
        lockStaggerLogKey = key
        lockStaggerLogAt = now
        Lg.i(
            TAG,
            "lock-stagger edge=${effEdge.name} clipped=$windowClipped" +
                " shape=${contentWindow.shape}" +
                " travel=${"%.2f".format(lockExitTravel(effEdge.to == SceneState.LOCK, LOCK_WEATHER_INDEX))}" +
                " cardTravel=${"%.2f".format(lockExitTravel(effEdge.to == SceneState.LOCK, LOCK_CARD_INDEX))}" +
                " weather=${"%.3f".format(weather.first)}/${"%.1f".format(weather.second)}" +
                " card=${"%.3f".format(card.first)}/${"%.1f".format(card.second)}",
        )
    }

    /** 天气行：太阳图标 + 温度 + 城市，按自身错峰可见度与位移绘制。 */
    private fun drawLockWeather(canvas: Canvas, w: Float, h: Float, visible: Float, shift: Float) {
        if (visible <= 0.002f) return
        val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (visible.coerceIn(0f, 1f) * 255f).toInt())
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
        canvas.restoreToCount(layer)
    }

    /** 通知卡：按自身错峰可见度与位移绘制，与天气行不同相。 */
    private fun drawLockCard(canvas: Canvas, w: Float, h: Float, visible: Float, shift: Float) {
        if (visible <= 0.002f) return
        val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (visible.coerceIn(0f, 1f) * 255f).toInt())
        if (shift != 0f) canvas.translate(0f, shift)
        drawNotificationCard(canvas, w, h * 0.415f, 0)
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
        canvas.drawText(homeIcons.getOrNull(index)?.label ?: previewApps[index].label, textX, titleBaseline, text)

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

    /** 按预览缩放折算的等效密度：dp 常量乘它即得预览像素。 */
    private fun previewDensity(): Float = density * uiScale

    /** 桌面几何按尺寸缓存，尺寸不变不重算。 */
    private fun homeGeometry(w: Float, h: Float): HomeGeometry {
        val key = "$w|$h|$uiScale|$homePages"
        homeGeometry?.let { if (key == homeGeometryKey) return it }
        text.typeface = sansMedium
        text.textSize = HomeLayout.ICON_TEXT_SP * scaled * uiScale
        text.getFontMetrics(labelMetrics)
        val labelH = labelMetrics.descent - labelMetrics.ascent
        val geo = HomeGeometry.solve(w, h, previewDensity(), labelH, homePages)
        homeGeometry = geo
        homeGeometryKey = key
        return geo
    }

    private fun drawAppGrid(
        canvas: Canvas,
        geo: HomeGeometry,
        entranceMs: Long,
        visible: Float = 1f,
        shift: Float = 0f,
    ) {
        if (visible <= 0.002f) return
        val count = min(previewApps.size, HomeLayout.COLUMNS * HomeLayout.ROWS)
        val layer = canvas.saveLayerAlpha(
            0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(),
            (visible.coerceIn(0f, 1f) * 255f).toInt(),
        )
        if (shift != 0f) canvas.translate(0f, shift)
        text.typeface = sansMedium
        text.textSize = HomeLayout.ICON_TEXT_SP * scaled * uiScale
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.CENTER
        text.color = HomeLayout.LABEL_COLOR_DARK_WP
        text.setShadowLayer(
            HomeLayout.LABEL_SHADOW_RADIUS_DP * geo.density, 0f, 0f, HomeLayout.LABEL_SHADOW_COLOR,
        )
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val phase = iconPhase(count)
        for (index in 0 until count) {
            val left = geo.cellIconLeftPx(index)
            val top = geo.cellIconTopPx(index)
            val label = homeIcons.getOrNull(index)?.label ?: previewApps[index].label
            text.getFontMetrics(labelMetrics)
            val labelX = left + geo.iconSizePx / 2f
            val labelY = geo.cellLabelTopPx(index) - labelMetrics.ascent
            if (entranceMs < 0L) {
                drawAppIcon(canvas, index, labelX, top + geo.iconSizePx / 2f, geo.iconSizePx)
                canvas.drawText(label, labelX, labelY, text)
                continue
            }
            HomeMotion.iconEntrance(
                homeIconEffect, index, count, entranceMs,
                phase.first, phase.second, homeIconTravelPercent, iconPose,
            )
            val save = canvas.save()
            canvas.translate(iconPose.x * w, iconPose.y * h)
            canvas.scale(iconPose.scale, iconPose.scale, labelX, top + geo.iconSizePx / 2f)
            if (iconPose.rotationDeg != 0f) {
                canvas.rotate(iconPose.rotationDeg, labelX, top + geo.iconSizePx / 2f)
            }
            val alphaLayer = canvas.saveLayerAlpha(
                0f, 0f, w, h, (iconPose.alpha.coerceIn(0f, 1f) * 255f).toInt(),
            )
            drawAppIcon(canvas, index, labelX, top + geo.iconSizePx / 2f, geo.iconSizePx)
            canvas.drawText(label, labelX, labelY, text)
            canvas.restoreToCount(alphaLayer)
            canvas.restoreToCount(save)
        }
        text.clearShadowLayer()
        canvas.restoreToCount(layer)
    }

    /**
     * 图标入场已播时长；不在进桌面边返回 -1（按静态画）。
     *
     * 拖动期把整轨进度折算成毫秒，与播放期同一条求值曲线。
     */
    private fun iconEntranceElapsedMs(): Long {
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        if (effEdge != TransitionEdge.LOCK_TO_HOME && effEdge != TransitionEdge.AOD_TO_HOME) return -1L
        val effT = if (scrubP >= 0f) SceneTimeline.localT(scrubP) else t
        val total = edgeMsFor(effEdge)
        return (effT.coerceIn(0f, 1f) * total).toLong()
    }

    private val iconPose = IconEntranceState()

    /**
     * 图标入场相位：期望节奏超出边长时等比压缩，保证末位在边结束前落位。
     *
     * 边长取与播放进度同一个来源，避免两处用不同分母。
     */
    private fun iconPhase(count: Int): Pair<Float, Float> {
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        val edgeMs = if (effEdge == null) -1L else edgeMsFor(effEdge)
        val fitted = IconPhaseFit.fit(edgeMs, count, homeIconDurationMs, homeIconStaggerMs)
        logIconPhase(effEdge, edgeMs, count, fitted)
        return fitted
    }

    private var iconPhaseLogKey: String? = null

    /** 相位压缩诊断：只在边长或配置变化时打一条，逐帧不刷屏。 */
    private fun logIconPhase(
        edge: TransitionEdge?,
        edgeMs: Long,
        count: Int,
        fitted: Pair<Float, Float>,
    ) {
        val key = "${edge?.name}|$edgeMs|$count|$homeIconDurationMs|$homeIconStaggerMs"
        if (key == iconPhaseLogKey) return
        iconPhaseLogKey = key
        Lg.i(
            TAG,
            "icon-phase edge=${edge?.name} edgeMs=$edgeMs count=$count" +
                " want=${"%.1f".format(homeIconDurationMs)}/${"%.1f".format(homeIconStaggerMs)}" +
                " fit=${"%.1f".format(fitted.first)}/${"%.2f".format(fitted.second)}" +
                " need=${"%.1f".format(IconPhaseFit.neededMs(count, homeIconDurationMs, homeIconStaggerMs))}" +
                " compressed=${IconPhaseFit.compressed(edgeMs, count, homeIconDurationMs, homeIconStaggerMs)}",
        )
    }

    private fun drawAppIcon(canvas: Canvas, index: Int, cx: Float, cy: Float, size: Float) {
        val icon = homeIcons.getOrNull(index)
        val bitmap = icon?.bitmap ?: icon?.drawable?.let { cachedIconBitmap(index, it, size) }
        if (bitmap != null && !bitmap.isRecycled) {
            dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
            src.set(0, 0, bitmap.width, bitmap.height)
            iconPaint.alpha = 255
            canvas.drawBitmap(bitmap, src, dst, iconPaint)
            return
        }
        val radius = HomeLayout.iconCornerRadiusPx(size)
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = 0x33FFFFFF
        canvas.drawRoundRect(r(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f), radius, radius, fill)
    }

    private fun cachedIconBitmap(index: Int, drawable: Drawable, size: Float): Bitmap {
        val px = size.toInt().coerceAtLeast(1)
        val slot = iconBitmapCache[index]
        if (slot != null && slot.first === drawable && slot.second.width == px && !slot.second.isRecycled) {
            return slot.second
        }
        val bitmap = HomeIconSource.toBitmap(drawable, px)
        iconBitmapCache[index] = drawable to bitmap
        return bitmap
    }

    private fun drawHomeWeather(canvas: Canvas, w: Float, h: Float) {
        text.typeface = sans
        text.textSize = sp(11f)
        text.letterSpacing = 0.03f
        text.textAlign = Paint.Align.CENTER
        text.color = 0x99FFFFFF.toInt()
        canvas.drawText("晴 13° · 北京市", w / 2f, h * 0.192f, text)
    }

    private fun drawHomeDots(canvas: Canvas, geo: HomeGeometry, visible: Float = 1f, shift: Float = 0f) {
        if (visible <= 0.002f) return
        val layer = canvas.saveLayerAlpha(
            0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(),
            (visible.coerceIn(0f, 1f) * 255f).toInt(),
        )
        if (shift != 0f) canvas.translate(0f, shift)
        fill.style = Paint.Style.FILL
        fill.shader = null
        val radius = geo.indicatorItemSizePx / 2f
        val cy = geo.indicatorItemTopPx() + radius
        for (i in 0 until homePages) {
            val alpha = if (i == 0) HomeLayout.INDICATOR_ACTIVE_ALPHA else HomeLayout.INDICATOR_NORMAL_ALPHA
            fill.color = Color.argb(alpha, 255, 255, 255)
            canvas.drawCircle(geo.indicatorItemLeftPx(i) + radius, cy, radius, fill)
        }
        canvas.restoreToCount(layer)
    }

    private fun drawDock(canvas: Canvas, geo: HomeGeometry, visible: Float = 1f, shift: Float = 0f) {
        if (visible <= 0.002f) return
        val layer = canvas.saveLayerAlpha(
            0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(),
            (visible.coerceIn(0f, 1f) * 255f).toInt(),
        )
        if (shift != 0f) canvas.translate(0f, shift)
        val left = geo.hotseatLeftPx
        val top = geo.hotseatTopPx
        val right = left + geo.hotseatWidthPx
        val bottom = top + geo.hotseatHeightPx
        val radius = geo.hotseatRadiusPx
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = HomeLayout.HOTSEAT_FILL_DARK_WP
        canvas.drawRoundRect(r(left, top, right, bottom), radius, radius, fill)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = HomeLayout.HOTSEAT_STROKE_PX * uiScale
        stroke.pathEffect = null
        stroke.shader = null
        stroke.color = HomeLayout.HOTSEAT_STROKE_COLOR
        canvas.drawRoundRect(r(left, top, right, bottom), radius, radius, stroke)
        val iconTop = geo.hotseatIconTopPx()
        for (i in 0 until HomeLayout.HOTSEAT_ICONS) {
            val index = HomeIconSource.grid.size + i
            val iconLeft = geo.hotseatIconLeftPx(i)
            drawAppIcon(
                canvas, index,
                iconLeft + geo.hotseatIconSizePx / 2f,
                iconTop + geo.hotseatIconSizePx / 2f,
                geo.hotseatIconSizePx,
            )
        }
        canvas.restoreToCount(layer)
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

    /** 暗角只压息屏世界：锁屏/桌面内容不该被压暗，故按息屏占比调强度。 */
    private fun drawVignette(canvas: Canvas, w: Float, h: Float) {
        val strength = layers.aodAlpha.coerceIn(0f, 1f)
        if (strength <= 0.002f) return
        val edge = (0x66 * strength).toInt().coerceIn(0, 255) shl 24
        fill.style = Paint.Style.FILL
        fill.shader = RadialGradient(
            w / 2f, h * 0.46f, maxOf(w, h) * 0.72f,
            intArrayOf(0x00000000, 0x00000000, edge),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int = SceneSkyMath.lerpColor(a, b, t)

    private fun drawWallpaperBackground(canvas: Canvas, w: Float, h: Float, scrim: Float): Boolean {
        val bitmap = backdropSource() ?: return false
        logBackdrop("plain", bitmap)
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = Color.WHITE
        fill.alpha = 255
        val zoom = if (edge == null && scrubP < 0f && zoomSettleFrom <= 0f) {
            when (scene) {
                SceneState.LOCK -> SystemSceneMotion.WALLPAPER_SCALE_LOCK
                else -> SystemSceneMotion.WALLPAPER_SCALE_HOME
            }
        } else {
            sampleLayers()
            wallpaperZoom()
        }
        val box = BackdropFit.coverRect(w, h, bitmap.width, bitmap.height, zoom) ?: return false
        dst.set(box[0], box[1], box[2], box[3])
        src.set(0, 0, bitmap.width, bitmap.height)
        val effEdge = if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge
        val effT = if (scrubP >= 0f) SceneTimeline.localT(scrubP) else t
        val layer = requestEffectLayer(bitmap, w, h)
        val visibility = EffectOverlayPolicy.visibilityAt(scene, effEdge, effT)
        canvas.drawBitmap(bitmap, src, dst, fill)
        val layerBox = layer?.let { BackdropFit.coverRect(w, h, it.width, it.height, zoom) }
        if (layer != null && layerBox != null && EffectOverlayPolicy.drawable(visibility)) {
            src.set(0, 0, layer.width, layer.height)
            dst.set(layerBox[0], layerBox[1], layerBox[2], layerBox[3])
            iconPaint.alpha = EffectOverlayPolicy.alphaByte(visibility)
            canvas.drawBitmap(layer, src, dst, iconPaint)
            iconPaint.alpha = 255
        }
        fill.color = Color.argb((scrim.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, fill)
        return true
    }

    /**
     * 壁纸底图 + 效果层：构建门只看效果是否选中（选中即预处理，与当前态无关），
     * 显示门按跨三态可见度决定叠多少（息屏 0、锁屏/桌面 1）。
     */
    private fun drawEffectBackdrop(
        canvas: Canvas,
        w: Float,
        h: Float,
        backdrop: Bitmap?,
        effEdge: TransitionEdge?,
        effT: Float,
        state: SceneState = scene,
    ) {
        if (backdrop == null) {
            drawAmbientWallpaper(canvas, w, h)
            return
        }
        val layer = requestEffectLayer(backdrop, w, h)
        drawBitmapCover(canvas, backdrop, w, h, wallpaperZoom(), 0f, 0f)
        val visibility = EffectOverlayPolicy.visibilityAt(state, effEdge, effT)
        if (layer != null && EffectOverlayPolicy.drawable(visibility)) {
            drawBitmapCover(canvas, layer, w, h, wallpaperZoom(), 0f, 0f, visibility)
        }
    }

    /** 效果参数一变就按当前预览尺寸预热，不等切到锁屏才构建。 */
    private fun warmEffectLayer() {
        if (!EffectOverlayPolicy.needsBuild(lockWallpaperEffect, lockSpecialEffect)) return
        val pw = phoneRect.width()
        val ph = phoneRect.height()
        if (pw < 2f || ph < 2f) return
        val bitmap = backdropSource() ?: return
        logBackdrop("warm", bitmap)
        requestEffectLayer(bitmap, pw, ph)
    }

    /**
     * 取当前效果处理后的整屏壁纸：贴图类做法线折射，模糊/景深走各自管线。
     *
     * 处理放后台线程，首帧先用原壁纸，算好再刷一次。
     */
    private fun requestEffectLayer(bitmap: Bitmap, w: Float, h: Float): Bitmap? {
        val wp = lockWallpaperEffect
        val sp = lockSpecialEffect
        val meizu = MeizuGlassRenderer.kindOf(wp)
        val textured = EffectTextureCatalog.hasTexture(wp)
        val blurred = wp == EffectCatalog.LOCK_WP_BLUR
        val depth = sp == EffectCatalog.LOCK_SP_BOKEH
        if (meizu == null && !textured && !blurred && !depth) return null
        val size = EffectOverlayPolicy.targetSize(w.toInt(), h.toInt())
        val tw = size[0].coerceAtLeast(1)
        val th = size[1].coerceAtLeast(1)
        val intensity = lockWallpaperIntensity
        val key = "$wp|$sp|$intensity|$tw|$th|${bitmap.width}x${bitmap.height}|${System.identityHashCode(bitmap)}"
        if (key == effectLayerKey) return effectLayer
        if (effectLoading) return effectLayer
        effectLoading = true
        Lg.i(TAG, "effect layer build wp=$wp sp=$sp size=${tw}x$th")
        val assets = context.assets
        Thread({
            var fail: Throwable? = null
            val out = runCatching {
                val base = Bitmap.createScaledBitmap(bitmap, tw, th, true)
                val result = when {
                    meizu != null -> {
                        val gray = if (meizu == MeizuGlassRenderer.FOG) {
                            EffectTextureRenderer.decode(assets, "effect/meizu/fog.png")
                        } else {
                            null
                        }
                        val rendered = MeizuGlassRenderer.render(base, meizu, tw, th, intensity, gray, gray)
                        gray?.recycle()
                        rendered ?: base
                    }
                    textured -> {
                        val spec = EffectTextureCatalog.find(wp)!!
                        val normal = EffectTextureRenderer.decode(assets, spec.texturePath)
                        if (normal == null) base else {
                            // 该效果普通档需背景模糊时先糊底图，再折射+叠玻璃（系统 step/radius 真值）
                            val backdrop = if (EffectTextureCatalog.needsBlur(spec, false)) {
                                AodBitmap.blur(base, spec.blurStep, spec.blurRadius)
                            } else {
                                base
                            }
                            val refracted = EffectTextureRenderer.refract(backdrop, normal, spec, tw, th, false)
                            val overlay = EffectTextureRenderer.render(normal, spec, tw, th, false)
                            val canvas = Canvas(refracted)
                            EffectTextureRenderer.draw(canvas, overlay, tw.toFloat(), th.toFloat())
                            overlay.recycle()
                            normal.recycle()
                            if (backdrop !== base) backdrop.recycle()
                            refracted
                        }
                    }
                    depth -> TiltShiftRenderer.render(base, tw, th)
                    else -> TiltShiftRenderer.blurred(base, 18f).let { full ->
                        val outB = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                        Canvas(outB).drawBitmap(full, null, android.graphics.RectF(0f, 0f, tw.toFloat(), th.toFloat()), null)
                        full.recycle()
                        outB
                    }
                }
                if (result !== base) base.recycle()
                result
            }.getOrElse {
                fail = it
                null
            }
            post {
                effectLoading = false
                if (out != null) {
                    effectLayer?.recycle()
                    effectLayer = out
                    effectLayerKey = key
                    Lg.i(TAG, "effect layer ready wp=$wp sp=$sp")
                } else {
                    Lg.w(TAG, "effect layer failed wp=$wp sp=$sp err=${fail?.javaClass?.simpleName}:${fail?.message}")
                }
                invalidate()
            }
        }, "aod-effect-layer").apply { isDaemon = true }.start()
        return effectLayer
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
        syncPhotoStyle(style.id)
        syncLensSource()
        refreshTime()
        scheduleMinuteTick()
        syncMotionTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(minuteTick)
        stopLensTick()
        clockTailEdge = null
        if (clockTailScheduled) {
            Choreographer.getInstance().removeFrameCallback(clockTailTick)
            clockTailScheduled = false
        }
        zoomSettleFrom = 0f
        scrubEdge = null
        if (zoomSettleScheduled) {
            Choreographer.getInstance().removeFrameCallback(zoomSettleTick)
            zoomSettleScheduled = false
        }
        photoSequence?.close()
        photoSequence = null
        photoFrame = null
        handPaintRenderer?.close()
        handPaintRenderer = null
        handPaintSide = -1
        photoRenderer.forgetReveal()
        revealCache?.recycle()
        revealCache = null
        revealCacheKey = null
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
                syncLensSource()
                warmEffectLayer()
                invalidate()
            }
        }, "aod-scene-sky").start()
    }

    /** 图标取系统原厂资源；一次性后台加载，不阻塞首帧。 */
    private fun requestAppIcons() {
        if (iconsRequested) return
        iconsRequested = true
        val appContext = context.applicationContext ?: context
        Thread({
            val loaded = HomeIconSource.resolve(appContext)
            post {
                homeIcons = loaded
                iconBitmapCache.clear()
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

    private companion object {
        const val TAG = "AodPreview"
        const val PREVIEW_ASPECT = com.coloros16.liquidglass.engine.SCREEN_ASPECT

        /** 预览逐帧通道的节流：边内要抓中间帧，静止只要端点。 */
        const val PREVIEW_FRAME_IN_EDGE_MS = 120L
        const val PREVIEW_FRAME_IDLE_MS = 800L

        /** 锁屏浮层错峰槽位：天气行 0、通知卡 1。 */
        const val LOCK_WEATHER_INDEX = 0
        const val LOCK_CARD_INDEX = 1

        /** 锁屏浮层逐元素错峰行程（屏高归一化，调用方乘屏高）。 */
        const val LOCK_STAGGER_TRAVEL = 0.06f

        /** 圆窗退场行程：窗口自身快速收拢，内容只需小幅让位即被送出窗。 */
        const val LOCK_STAGGER_EXIT_TRAVEL = 0.30f

        /** 方窗退场行程：方窗收拢慢，同行程下内容会原地淡没，需更大让位。 */
        const val LOCK_STAGGER_EXIT_TRAVEL_SQUARE = 0.44f

        /** 圆窗通知卡退场行程：卡片底沿比天气行低，同行程下淡没时仍在窗内。 */
        const val LOCK_STAGGER_EXIT_TRAVEL_CARD = 0.49f

        /** 方窗通知卡退场行程：方窗在卡片可见期内几乎不裁切，只能靠自身走出屏顶。 */
        const val LOCK_STAGGER_EXIT_TRAVEL_CARD_SQUARE = 0.58f

        /** 桌面层错峰行程：入场轻微暗示，与锁屏同量级。 */
        const val HOME_STAGGER_TRAVEL = 0.06f

        /** 桌面层退场行程：整层同相位，保证每个图标被窗口裁掉前都已位移。 */
        const val HOME_STAGGER_EXIT_TRAVEL = 0.10f

        /** 落位补间诊断节流窗：取补间时长的约 1/4，保证整段能采到多条中间帧。 */
        const val ZOOM_SETTLE_LOG_GAP_MS = WallpaperZoomSettle.SETTLE_MS / 4L

        /** 接缝配方覆盖的六条边（揭开两条 + 推移四条）。 */
        val SEAM_EDGES = listOf(
            TransitionEdge.AOD_TO_LOCK, TransitionEdge.LOCK_TO_AOD,
            TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK,
            TransitionEdge.AOD_TO_HOME, TransitionEdge.HOME_TO_AOD,
        )


        /** 播放期场景时间曲线：贝塞尔；拖动仍 1:1。 */
        private val SCENE_PLAY_CURVE = com.coloros16.liquidglass.engine.CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
        val AOD_CLOCK_COLOR = 0xFFC9D4E2.toInt()
        val LOCK_CLOCK_COLOR = 0xFFF0F4F8.toInt()
        val HOME_CLOCK_COLOR = 0xFFF5F8FB.toInt()
    }
}
