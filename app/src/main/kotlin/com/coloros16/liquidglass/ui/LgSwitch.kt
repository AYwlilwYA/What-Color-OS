package com.coloros16.liquidglass.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.Switch
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.coloros16.liquidglass.R

/**
 * 自绘开关：轨道与拇指几何按设计稿固定（轨道 52×32dp 贴右、
 * ON 拇指滑到右缘并 16→24dp 变宽），左侧文字沿用 TextView 的 textSize/textColor 属性。
 * 取代框架 Switch 的 thumb bounds 计算（真机上拇指贴边压环、ON 被视图右缘切平）。
 */
class LgSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    // 必须带主题 switchStyle（对齐框架 Switch 两参构造）：置 0 会丢默认样式，super.onMeasure 里 makeLayout(mTextOn=null) 直接 NPE
    defStyleAttr: Int = android.R.attr.switchStyle,
) : Switch(context, attrs, defStyleAttr) {

    private val d = resources.displayMetrics.density
    private val cTrackOff = context.getColor(R.color.lg_track_off)
    private val cStroke = context.getColor(R.color.lg_sw_stroke)
    private val cThumbOff = context.getColor(R.color.lg_thumb_off)
    private val cBlued = context.getColor(R.color.lg_blued)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()

    private var animT = 0f
    private var animator: ValueAnimator? = null

    override fun setChecked(checked: Boolean) {
        val old = isChecked
        super.setChecked(checked)
        if (old == checked) return
        val target = if (checked) 1f else 0f
        if (!isLaidOut) {
            animT = target
            return
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animT, target).apply {
            duration = 180
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                animT = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val trackH = (32f * d).toInt()
        val minH = trackH + paddingTop + paddingBottom
        if (measuredHeight < minH) {
            setMeasuredDimension(measuredWidth, minH)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val trackW = 52f * d
        val trackH = 32f * d
        val rad = trackH / 2f
        val right = (width - paddingRight).toFloat()
        val left = right - trackW
        val top = (height - trackH) / 2f
        val bottom = top + trackH

        val label = text
        if (!label.isNullOrEmpty()) {
            textPaint.textSize = textSize
            textPaint.color = currentTextColor
            val save = canvas.save()
            canvas.clipRect(0f, 0f, left - 14f * d, height.toFloat())
            val fm = textPaint.fontMetrics
            val baseline = (height - (fm.ascent + fm.descent)) / 2f
            canvas.drawText(label, 0, label.length, paddingLeft.toFloat(), baseline, textPaint)
            canvas.restoreToCount(save)
        }

        trackPaint.style = Paint.Style.FILL
        trackPaint.color = if (isChecked) cBlued else cTrackOff
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, rad, rad, trackPaint)

        strokePaint.strokeWidth = 2f * d
        strokePaint.color = if (isChecked) cBlued else cStroke
        val inset = d
        rect.set(left + inset, top + inset, right - inset, bottom - inset)
        canvas.drawRoundRect(rect, rad - inset, rad - inset, strokePaint)

        val thumbW = (16f + 8f * animT) * d
        val thumbH = 16f * d
        // OFF 拇指贴左缘，ON 滑到右缘（右缘留 6dp）
        val thumbLeft = left + 6f * d + animT * (trackW - 12f * d - thumbW)
        val cy = (top + bottom) / 2f
        thumbPaint.color = blend(cThumbOff, Color.WHITE, animT)
        rect.set(thumbLeft, cy - thumbH / 2f, thumbLeft + thumbW, cy + thumbH / 2f)
        canvas.drawRoundRect(rect, thumbH / 2f, thumbH / 2f, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> invalidate()
        }
        return handled
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val a = Color.alpha(from) + ((Color.alpha(to) - Color.alpha(from)) * u).toInt()
        val r = Color.red(from) + ((Color.red(to) - Color.red(from)) * u).toInt()
        val g = Color.green(from) + ((Color.green(to) - Color.green(from)) * u).toInt()
        val b = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * u).toInt()
        return Color.argb(a, r, g, b)
    }
}
