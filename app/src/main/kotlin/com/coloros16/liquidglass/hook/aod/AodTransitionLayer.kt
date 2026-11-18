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
import com.coloros16.liquidglass.engine.EffectOverlayPolicy
import com.coloros16.liquidglass.engine.FeatherPainter
import com.coloros16.liquidglass.engine.MaskGeometry
import com.coloros16.liquidglass.engine.MaskShape
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.log.Lg

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
    private var effectAlpha = 0f
    private val effectPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var frameRevealLoggedAt = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    /** 由挂载方逐帧写入；数值全部来自引擎，本层不自算。 */
    fun bind(source: TransitionParams?, sceneLayers: SystemSceneMotion.Layers?, playing: Boolean) {
        params = source
        if (sceneLayers != null) {
            dim = sceneLayers.dim
        }
        active = playing
        invalidate()
    }

    /** 壁纸效果叠加：id 与不透明度由挂载方按当前态与转场进度写入。 */
    fun bindEffect(id: String?, alpha: Float) {
        val next = EffectOverlayPolicy.strengthFor(alpha)
        if (effectId == id && EffectOverlayPolicy.sameAlpha(effectAlpha, next)) return
        effectId = id
        effectAlpha = next
        invalidate()
    }

    fun release() {
        params = null
        active = false
        dim = 0f
        effectId = null
        effectAlpha = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        drawEffect(canvas, w, h)
        val p = params
        if (!active || p == null) {
            drawDim(canvas, w, h)
            return
        }

        if (p.maskByFrame) {
            logFrameReveal()
        } else if (p.maskEnabled && MaskGeometry.drawable(p.maskRadius)) {
            drawMaskVeil(canvas, w, h, p)
        }
        drawDim(canvas, w, h)
    }

    /** 色键帧承担揭开：本层不画几何遮罩，节流一条诊断说明形状来自素材。 */
    private fun logFrameReveal() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - frameRevealLoggedAt < FRAME_REVEAL_LOG_MS) return
        frameRevealLoggedAt = now
        Lg.i(TAG, "mask by frame: geometry veil skipped, reveal comes from chromakey G channel")
    }

    /** 效果叠加层压在遮罩与压暗之下，随锁屏层透明度淡入淡出。 */
    private fun drawEffect(canvas: Canvas, w: Float, h: Float) {
        val id = effectId ?: return
        if (!EffectOverlayPolicy.drawable(effectAlpha)) return
        val bitmap = AodEffectOverlay.peek(id, width, height) ?: return
        effectPaint.alpha = EffectOverlayPolicy.alphaByte(effectAlpha)
        AodEffectOverlay.draw(canvas, bitmap, w, h, effectPaint, rect)
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
        if (outer <= 0f) return

        val veil = Color.argb((dim.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0)
        fill.style = Paint.Style.FILL
        when (FeatherPainter.mode(p.maskShape, p.maskRadius, p.maskFeather)) {
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
        val p = params
        if (p != null && p.maskByFrame) return
        if (p != null && p.maskEnabled && MaskGeometry.drawable(p.maskRadius)) return
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = Color.argb(a, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, fill)
    }

    private companion object {
        const val TAG = "LgAodLayer"
        const val FRAME_REVEAL_LOG_MS = 2000L
    }
}
