package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.coloros16.liquidglass.R
import kotlin.math.abs

/** 三态条：点击直连播边；长按变进度条，标签保持不动。 */
class AodStateBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onTap(index: Int)
        fun onScrubStart()
        fun onScrubMove(t: Float)
        fun onScrubEnd(t: Float)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val rect = RectF()
    private val labels = arrayOf(
        context.getString(R.string.settings_aod_state_aod),
        context.getString(R.string.settings_aod_state_lock),
        context.getString(R.string.settings_aod_state_home),
    )

    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var activeIndex = 0
    private var scrub = false
    private var scrubT = 0f
    private var interactive = true

    private var pressed = false
    private var moved = false
    private var downX = 0f

    private val longPress = Runnable {
        if (!pressed || moved || scrub || !interactive) return@Runnable
        scrub = true
        invalidate()
        listener?.onScrubStart()
    }

    init {
        isClickable = true
        isFocusable = true
    }

    fun setActive(index: Int) {
        activeIndex = index.coerceIn(0, 2)
        invalidate()
    }

    fun setInteractive(value: Boolean) {
        if (interactive == value) return
        interactive = value
        if (!value) {
            removeCallbacks(longPress)
            pressed = false
            scrub = false
        }
        invalidate()
    }

    fun setScrub(active: Boolean, t: Float) {
        scrub = active
        scrubT = t.coerceIn(0f, 1f)
        invalidate()
    }

    fun setScrubT(t: Float) {
        scrubT = t.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = dp(3f)
        val radius = h / 2f
        val innerRadius = (h - pad * 2f) / 2f
        val segW = (w - pad * 2f) / 3f

        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = 0x33B2B2B2
        canvas.drawRoundRect(r(0f, 0f, w, h), radius, radius, fill)

        if (scrub) {
            val right = pad + (w - pad * 2f) * scrubT
            fill.color = 0x2EFFFFFF
            canvas.drawRoundRect(r(pad, pad, right, h - pad), innerRadius, innerRadius, fill)
        }

        for (i in 0 until 3) {
            val selected = i == activeIndex
            text.typeface = if (selected) sansMedium else sans
            text.textSize = sp(if (selected) 18f else 14f)
            text.letterSpacing = 0.02f
            text.color = if (selected) 0xFFFFFFFF.toInt() else 0x66FFFFFF
            val fm = text.fontMetrics
            val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(labels[i], pad + segW * (i + 0.5f), baseline, text)
        }

        if (scrub) {
            val cx = pad + (w - pad * 2f) * scrubT
            fill.color = 0xF2FFFFFF.toInt()
            canvas.drawRoundRect(
                r(cx - dp(1.2f), h / 2f - dp(10f), cx + dp(1.2f), h / 2f + dp(10f)),
                dp(1.2f), dp(1.2f), fill,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        val x = event.x
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                moved = false
                downX = x
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(longPress, 320L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressed) return false
                if (!scrub && abs(x - downX) > touchSlop) {
                    removeCallbacks(longPress)
                    moved = true
                }
                if (scrub) {
                    val pad = dp(3f)
                    val t = ((x - pad) / (width - pad * 2f)).coerceIn(0f, 1f)
                    scrubT = t
                    listener?.onScrubMove(t)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!pressed) return false
                pressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (scrub) {
                    val t = scrubT
                    scrub = false
                    listener?.onScrubEnd(t)
                    invalidate()
                } else if (!moved) {
                    val pad = dp(3f)
                    val segW = (width - pad * 2f) / 3f
                    val index = ((x - pad) / segW).toInt().coerceIn(0, 2)
                    listener?.onTap(index)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                pressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (scrub) {
                    val t = scrubT
                    scrub = false
                    listener?.onScrubEnd(t)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    private fun r(left: Float, top: Float, right: Float, bottom: Float): RectF =
        rect.apply { set(left, top, right, bottom) }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = value * scaled
}
