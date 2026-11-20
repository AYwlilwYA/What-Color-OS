package com.coloros16.liquidglass.hook.aod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.coloros16.liquidglass.engine.BackdropFit
import com.coloros16.liquidglass.engine.EffectOverlayPolicy
import com.coloros16.liquidglass.engine.FeatherPainter
import com.coloros16.liquidglass.engine.MaskGeometry
import com.coloros16.liquidglass.engine.MaskShape
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SkyBranchPolicy
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.engine.WorldDrawPolicy
import com.coloros16.liquidglass.engine.WorldMaskPolicy
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.ui.aod.AodSceneSpec
import io.github.libxposed.api.XposedInterface

/**
 * 三态转场自绘层：只读引擎输出画遮罩与明暗，不重绘系统内容。
 *
 * 挂在 AOD 根布局内，不可点击、不拦触摸；无转场时整层透明。
 */
class AodTransitionLayer(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPath = Path()
    private val rect = RectF()

    private var params: TransitionParams? = null
    private var dim = 0f
    private var active = false
    private var effectId: String? = null
    private var effectSpecialId: String? = null
    private var effectIntensity = 1f
    private var effectWall: android.graphics.Bitmap? = null
    private var effectZoom = 1f
    private var effectAlpha = 0f
    private val effectPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var frameRevealLoggedAt = 0L
    private var worldMaskLoggedAt = 0L
    private var effectFitLoggedAt = 0L
    private var effectSkipLoggedAt = 0L
    private var effectSkipReason = ""
    private var maskVeilLoggedAt = 0L
    private var maskVeilKey = ""

    private var worldSpec: AodSceneSpec? = null
    private var worldState = SceneState.AOD
    private var worldEdge: TransitionEdge? = null
    private var worldT = 0f
    private var worldAodAlpha = 0f
    private var worldSpinDeg = 0f
    private var worldAllowDynamic = false
    private var worldColorMix = 0f
    private var worldDrawn = false
    private var effectDrawnInWorld = false
    private var worldTiltDeg = 0f
    private var worldWallpaperScale = 1f
    private var worldAccent = 0
    private var worldApi: XposedInterface? = null
    private var worldUseSystemWallpaper = true
    private var worldFramePair = false
    private var worldCropX = 0.5f
    private var worldCropY = 0.5f
    private var worldCropSize = 1f
    private var worldCropAngle = 0f
    private val skyCounts = AodSkyRenderer.Counts()
    private var skyLoggedAt = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    /** 由挂载方逐帧写入；数值全部来自引擎，本层不自算。 */
    /** 本层挂在哪一面（息屏根=AOD，窗口根=LOCK）；静止期是否画世界层由它决定。 */
    var surface: SceneState = SceneState.AOD

    fun bind(source: TransitionParams?, sceneLayers: SystemSceneMotion.Layers?, playing: Boolean) {
        params = source
        if (sceneLayers != null) {
            dim = sceneLayers.dim
        }
        active = playing
        invalidate()
    }

    /** 壁纸效果叠加：id 与不透明度由挂载方按当前态与转场进度写入，zoom 与壁纸底同框。 */
    fun bindEffect(
        id: String?,
        alpha: Float,
        specialId: String? = null,
        intensity: Float = 1f,
        wall: android.graphics.Bitmap? = null,
        zoom: Float = 1f,
    ) {
        val next = EffectOverlayPolicy.strengthFor(alpha)
        if (effectId == id && effectSpecialId == specialId &&
            effectIntensity == intensity && effectWall === wall &&
            effectZoom == zoom && EffectOverlayPolicy.sameAlpha(effectAlpha, next)
        ) return
        effectId = id
        effectSpecialId = specialId
        effectIntensity = intensity
        effectWall = wall
        effectZoom = zoom
        effectAlpha = next
        invalidate()
    }

    /**
     * 世界层输入：样式契约 + 当前态/边/进度 + 息屏层透明度与自转角。
     *
     * spec 为 null 表示该样式无世界层（非 SCENE 类），本层只画遮罩与压暗。
     */
    fun bindWorld(
        spec: AodSceneSpec?,
        state: SceneState,
        edge: TransitionEdge?,
        t: Float,
        aodAlpha: Float,
        spinDeg: Float,
        allowDynamic: Boolean,
        colorMix: Float,
        tiltDeg: Float = 0f,
        wallpaperScale: Float = 1f,
        accent: Int = 0,
        api: XposedInterface? = null,
        useSystemWallpaper: Boolean = true,
        framePair: Boolean = false,
        cropX: Float = 0.5f,
        cropY: Float = 0.5f,
        cropSize: Float = 1f,
        cropAngle: Float = 0f,
    ) {
        worldSpec = spec
        worldState = state
        worldEdge = edge
        worldT = t
        worldAodAlpha = aodAlpha
        worldSpinDeg = spinDeg
        worldAllowDynamic = allowDynamic
        worldColorMix = colorMix
        worldTiltDeg = tiltDeg
        worldWallpaperScale = wallpaperScale
        worldAccent = accent
        worldApi = api
        worldUseSystemWallpaper = useSystemWallpaper
        worldFramePair = framePair
        worldCropX = cropX
        worldCropY = cropY
        worldCropSize = cropSize
        worldCropAngle = cropAngle
        invalidate()
    }

    fun release() {
        params = null
        active = false
        dim = 0f
        effectId = null
        effectAlpha = 0f
        worldSpec = null
        worldEdge = null
        worldAodAlpha = 0f
        worldDrawn = false
        worldApi = null
        worldFramePair = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val allowWorld = WorldDrawPolicy.allowWorld(active, surface)
        val world = if (allowWorld) {
            drawWorld(canvas, w, h)
        } else {
            // 门关也要把状态写回去，否则 worldDrawn 停在 true，日志会说谎（关掉后零条、再开也不打）
            if (worldDrawn) {
                worldDrawn = false
                Lg.i(TAG, "world gated off active=$active surface=$surface")
            }
            false
        }
        if (!effectDrawnInWorld) drawEffect(canvas, w, h)
        effectDrawnInWorld = false
        val p = params
        if (!active || p == null) {
            if (WorldMaskPolicy.dimDrawable(world, maskByFrame = false, veilDrawn = false)) {
                drawDim(canvas, w, h)
            }
            return
        }

        val veil = WorldMaskPolicy.veilDrawable(
            world, p.maskEnabled, MaskGeometry.drawable(p.maskRadius), p.maskByFrame,
        )
        if (p.maskByFrame) {
            logFrameReveal()
        } else if (veil) {
            drawMaskVeil(canvas, w, h, p)
        } else if (world && p.maskEnabled) {
            logWorldOwnsMask()
        }
        if (WorldMaskPolicy.dimDrawable(world, p.maskByFrame, veil)) {
            drawDim(canvas, w, h)
        }
    }

    /**
     * 世界层优先：画成功时它自带压暗，本层不再叠一层（否则双倍压暗）。
     *
     * 分支优先级 镜片 → 照片类 → 程序化天空；三者都不适用则返回 false 走遮罩+压暗路径。
     */
    private fun drawWorld(canvas: Canvas, w: Float, h: Float): Boolean {
        val spec = worldSpec ?: return false
        val drawn = when (
            SkyBranchPolicy.branchOf(spec.lens, spec.photo != null, spec.sky, spec.handPaintStyle)
        ) {
            SkyBranchPolicy.Branch.LENS -> AodWorldLayer.drawLens(
                canvas, w, h, spec, worldState, worldEdge, worldT, dim,
                worldAodAlpha, worldSpinDeg, worldAllowDynamic, worldColorMix,
                wallpaperScale = worldWallpaperScale,
                useSystemWallpaper = worldUseSystemWallpaper,
                onBackdrop = { paintEffectInsideWorld(canvas, w, h) },
            )
            SkyBranchPolicy.Branch.PHOTO -> AodWorldLayer.drawPhoto(
                canvas, w, h, spec, worldState, worldEdge, worldT, dim,
                worldWallpaperScale, worldAccent, worldTiltDeg, worldApi,
                useSystemWallpaper = worldUseSystemWallpaper,
                framePair = worldFramePair,
                cropX = worldCropX,
                cropY = worldCropY,
                cropSize = worldCropSize,
                cropAngle = worldCropAngle,
                onBackdrop = { paintEffectInsideWorld(canvas, w, h) },
            ) { postInvalidate() }
            SkyBranchPolicy.Branch.HANDPAINT -> AodWorldLayer.drawHandPaint(
                canvas, w, h, spec, worldState, worldEdge, dim, worldAodAlpha,
                wallpaperScale = worldWallpaperScale,
                useSystemWallpaper = worldUseSystemWallpaper,
                onBackdrop = { paintEffectInsideWorld(canvas, w, h) },
            ) { postInvalidate() }
            SkyBranchPolicy.Branch.SKY -> drawSky(canvas, w, h, spec)
            SkyBranchPolicy.Branch.NONE -> false
        }
        if (drawn != worldDrawn) {
            worldDrawn = drawn
            Lg.i(
                TAG,
                "world branch drawn=$drawn lens=${spec.lens} photo=${spec.photo != null} " +
                    "sky=${spec.sky} handpaint=${spec.handPaintStyle} state=$worldState",
            )
        }
        return drawn
    }

    /** 世界层刚铺完壁纸底时插效果层：与预览同序（底 → 效果 → 模糊转清晰 → 压暗 → 内容）。 */
    private fun paintEffectInsideWorld(canvas: Canvas, w: Float, h: Float) {
        drawEffect(canvas, w, h, "world")
        effectDrawnInWorld = true
    }

    /** 程序化天空：求值走共用引擎，产出条数逐帧记诊断，条数为 0 也能分辨原因。 */
    private fun drawSky(canvas: Canvas, w: Float, h: Float, spec: AodSceneSpec): Boolean {
        val drawn = AodSkyRenderer.draw(
            canvas, w, h, spec.contract, worldState, worldEdge, worldT,
            worldAodAlpha, worldWallpaperScale, dim,
            resources.displayMetrics.density, skyCounts,
        )
        logSkyCounts()
        return drawn
    }

    private fun logSkyCounts() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - skyLoggedAt < FRAME_REVEAL_LOG_MS) return
        skyLoggedAt = now
        Lg.i(
            TAG,
            "world-sky stars=${skyCounts.stars} cloudBack=${skyCounts.cloudsBack} " +
                "cloudFront=${skyCounts.cloudsFront} moon=${skyCounts.moon} craters=${skyCounts.craters} " +
                "light=${"%.2f".format(skyCounts.light)} cam=${"%.3f".format(skyCounts.cameraX)},${"%.3f".format(skyCounts.cameraY)}",
        )
    }

    /** 世界层自带压暗：几何遮罩让位，节流一条诊断说明为何不画遮罩。 */
    private fun logWorldOwnsMask() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - worldMaskLoggedAt < FRAME_REVEAL_LOG_MS) return
        worldMaskLoggedAt = now
        Lg.i(TAG, "mask by world: geometry veil skipped, world layer already paints dim")
    }

    /** 色键帧承担揭开：本层不画几何遮罩，节流一条诊断说明形状来自素材。 */
    private fun logFrameReveal() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - frameRevealLoggedAt < FRAME_REVEAL_LOG_MS) return
        frameRevealLoggedAt = now
        Lg.i(TAG, "mask by frame: geometry veil skipped, reveal comes from chromakey G channel")
    }

    /** 效果叠加层压在遮罩与压暗之下，取景与壁纸底同框（同 zoom），可见度独立于锁屏层。 */
    private fun drawEffect(canvas: Canvas, w: Float, h: Float, where: String = "outer") {
        val id = effectId ?: return
        if (!EffectOverlayPolicy.drawable(effectAlpha)) {
            logEffectSkip(where, "alpha=${"%.3f".format(effectAlpha)}")
            return
        }
        val bitmap = AodEffectOverlay.peek(
            id, effectSpecialId, effectIntensity, effectWall, width, height,
        ) ?: run {
            logEffectSkip(where, "bitmap-not-ready id=$id")
            return
        }
        val box = BackdropFit.coverRect(w, h, bitmap.width, bitmap.height, effectZoom)
        if (box == null) {
            logEffectNoFit(where)
            return
        }
        effectPaint.alpha = EffectOverlayPolicy.alphaByte(effectAlpha)
        rect.set(box[0], box[1], box[2], box[3])
        AodEffectOverlay.draw(canvas, bitmap, rect, effectPaint)
        logEffectFit(w, h, box, where)
    }

    /** 取景与可见度诊断：能看出效果层与壁纸底是否同框、这一帧叠了多少、插在哪一序位。 */
    private fun logEffectFit(w: Float, h: Float, box: FloatArray, where: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - effectFitLoggedAt < FRAME_REVEAL_LOG_MS) return
        effectFitLoggedAt = now
        Lg.i(
            TAG,
            "effect-fit where=$where id=$effectId zoom=${"%.3f".format(effectZoom)} a=${"%.3f".format(effectAlpha)} " +
                "dst=${"%.1f".format(box[0])},${"%.1f".format(box[1])}," +
                "${"%.1f".format(box[2])},${"%.1f".format(box[3])} view=${"%.0f".format(w)}x${"%.0f".format(h)}",
        )
    }

    private fun logEffectNoFit(where: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - effectFitLoggedAt < FRAME_REVEAL_LOG_MS) return
        effectFitLoggedAt = now
        Lg.i(TAG, "effect-nofit where=$where id=$effectId zoom=$effectZoom 尺寸未就绪，本帧不画效果层")
    }

    /** 效果层没画的原因诊断：可见度为 0 与素材未就绪必须能分开。 */
    private fun logEffectSkip(where: String, reason: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (reason == effectSkipReason && now - effectSkipLoggedAt < FRAME_REVEAL_LOG_MS) return
        effectSkipReason = reason
        effectSkipLoggedAt = now
        Lg.i(TAG, "effect-skip where=$where reason=$reason")
    }

    /** 遮罩羽化诊断：画法、形状与内外径决定外侧压暗的实际观感，早退时也要能分辨原因。 */
    private fun logMaskVeil(
        mode: FeatherPainter.Mode,
        p: TransitionParams,
        inner: Float,
        outer: Float,
        reason: String,
    ) {
        val now = android.os.SystemClock.uptimeMillis()
        val key = "$mode|${p.maskShape}|$reason"
        if (key == maskVeilKey && now - maskVeilLoggedAt < MASK_VEIL_LOG_MS) return
        maskVeilKey = key
        maskVeilLoggedAt = now
        Lg.i(
            TAG,
            "mask-veil mode=$mode shape=${p.maskShape} reason=$reason " +
                "r=${"%.1f".format(p.maskRadius)} feather=${"%.1f".format(p.maskFeather)} " +
                "inner=${"%.1f".format(inner)} outer=${"%.1f".format(outer)} dim=${"%.2f".format(dim)}",
        )
    }

    /**
     * 遮罩外侧压暗：圆内/圆角矩形内透明，外侧按羽化过渡到不透明。
     *
     * 用径向渐变近似羽化，避免整屏 saveLayer 的开销。
     */
    private fun drawMaskVeil(canvas: Canvas, w: Float, h: Float, p: TransitionParams) {
        val cx = p.maskCenterX
        val cy = p.maskCenterY
        val outer = MaskGeometry.featherOuterPx(p.maskRadius, p.maskFeather)
        val inner = MaskGeometry.featherInnerPx(p.maskRadius, p.maskFeather)
        val mode = FeatherPainter.mode(p.maskShape, p.maskRadius, p.maskFeather)
        if (outer <= 0f) {
            logMaskVeil(mode, p, inner, outer, "outer<=0")
            return
        }

        val veil = Color.argb((dim.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0)
        fill.style = Paint.Style.FILL
        logMaskVeil(mode, p, inner, outer, "draw")
        when (mode) {
            FeatherPainter.Mode.RADIAL -> {
                val stop = FeatherPainter.radialStop(p.maskRadius, p.maskFeather)
                fill.shader = RadialGradient(
                    cx, cy, outer,
                    intArrayOf(0x00000000, 0x00000000, veil),
                    floatArrayOf(0f, stop, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
                return
            }

            FeatherPainter.Mode.PATH_RING -> {
                drawFeatherRing(canvas, w, h, p, veil)
                return
            }

            FeatherPainter.Mode.NONE -> Unit
        }

        maskPath.reset()
        maskPath.fillType = Path.FillType.EVEN_ODD
        maskPath.addRect(0f, 0f, w, h, Path.Direction.CW)
        addShape(maskPath, p.maskShape, cx, cy, outer, w, h)
        fill.shader = null
        fill.color = veil
        canvas.drawPath(maskPath, fill)
    }

    /**
     * 非圆形羽化：由内向外逐层画轮廓差集，每层不透明度按 smoothstep 递增。
     *
     * 径向渐变会把方角画成圆弧，故圆角矩形必须走路径。
     */
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
            maskPath.reset()
            maskPath.fillType = Path.FillType.EVEN_ODD
            maskPath.addRect(0f, 0f, w, h, Path.Direction.CW)
            addShape(maskPath, p.maskShape, p.maskCenterX, p.maskCenterY, r, w, h)
            fill.color = Color.argb(a, 0, 0, 0)
            canvas.drawPath(maskPath, fill)
        }
    }

    /** 按形状往路径里加轮廓；半径语义与硬边分支一致。 */
    private fun addShape(path: Path, shape: MaskShape, cx: Float, cy: Float, r: Float, w: Float, h: Float) {
        when (shape) {
            MaskShape.CIRCLE -> path.addCircle(cx, cy, r, Path.Direction.CW)
            MaskShape.ROUNDED_RECT -> {
                val e = MaskGeometry.roundedRectHalfExtents(r, w, h)
                val corner = MaskGeometry.roundedRectCornerPx(r, minOf(w, h))
                rect.set(cx - e[0], cy - e[1], cx + e[0], cy + e[1])
                path.addRoundRect(rect, corner, corner, Path.Direction.CW)
            }
        }
    }

    private fun drawDim(canvas: Canvas, w: Float, h: Float) {
        val a = (dim.coerceIn(0f, 1f) * 255f).toInt()
        if (a <= 0) return
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = Color.argb(a, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, fill)
    }

    private companion object {
        const val TAG = "LgAodLayer"
        const val FRAME_REVEAL_LOG_MS = 2000L
        const val MASK_VEIL_LOG_MS = 400L
    }
}
