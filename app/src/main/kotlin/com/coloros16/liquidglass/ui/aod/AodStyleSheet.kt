package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.coloros16.liquidglass.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.min

/** 样式抽屉：最近使用（已保存配置）、自带样式、导入样式。 */
class AodStyleSheet(
    private val context: Context,
    private val recentConfigs: List<AodConfig>,
    private val selectedConfigId: String?,
    private val onPickConfig: (AodConfig) -> Unit,
    private val onPickTemplate: (AodStyle) -> Unit,
    private val onImport: () -> Unit,
) {

    private val density = context.resources.displayMetrics.density
    private val plate600 = context.getColor(R.color.lg_plate_600)
    private val seam = context.getColor(R.color.lg_seam)
    private val seamHi = context.getColor(R.color.lg_seam_hi)
    private val ink100 = context.getColor(R.color.lg_ink_100)
    private val ink300 = context.getColor(R.color.lg_ink_300)
    private val ink400 = context.getColor(R.color.lg_ink_400)
    private val brass = context.getColor(R.color.lg_brass)
    private val blued = context.getColor(R.color.lg_blued)

    private var dialog: BottomSheetDialog? = null

    fun show() {
        val sheet = BottomSheetDialog(context)
        dialog = sheet

        val scroll = NestedScrollView(context).apply {
            isFillViewport = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(22f), dp(22f), dp(22f), dp(22f), 0f, 0f, 0f, 0f)
                setColor(context.getColor(R.color.lg_plate_700))
            }
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_aod_sheet)
            setPadding(0, dpi(8f), 0, dpi(20f))
        }
        scroll.addView(root)

        root.addView(
            View(context).apply { setBackgroundResource(R.drawable.bg_aod_grip) },
            LinearLayout.LayoutParams(dpi(38f), dpi(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpi(2f)
                bottomMargin = dpi(10f)
            },
        )

        root.addView(
            buildHeader(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(groupTitle(context.getString(R.string.settings_aod_recent)))
        root.addView(buildRecent())
        root.addView(groupTitle(context.getString(R.string.settings_aod_builtin)))
        root.addView(buildBuiltin())

        val importRow = TextView(context).apply {
            text = context.getString(R.string.settings_aod_import)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ink300)
            setBackgroundResource(R.drawable.bg_aod_import)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onImport()
                sheet.dismiss()
            }
        }
        root.addView(
            importRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(46f)).apply {
                leftMargin = dpi(16f)
                rightMargin = dpi(16f)
                topMargin = dpi(8f)
            },
        )

        sheet.setContentView(scroll)
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.behavior.skipCollapsed = true
        }
        sheet.show()
    }

    private fun buildHeader(): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dpi(16f), 0, dpi(16f), dpi(10f))
        }
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(
            TextView(context).apply {
                text = context.getString(R.string.settings_aod_sheet_kicker)
                textSize = 11f
                letterSpacing = 0.12f
                setTextColor(ink400)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            },
        )
        titles.addView(
            TextView(context).apply {
                text = context.getString(R.string.settings_aod_sheet_title)
                textSize = 19f
                setTextColor(ink100)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dpi(4f), 0, 0)
            },
        )
        titles.addView(
            TextView(context).apply {
                text = context.getString(R.string.settings_aod_sheet_desc)
                textSize = 12f
                letterSpacing = 0.01f
                setTextColor(ink400)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setPadding(0, dpi(6f), 0, 0)
            },
        )
        header.addView(titles)

        val close = TextView(context).apply {
            text = "×"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(ink300)
            setBackgroundResource(R.drawable.bg_aod_close)
            contentDescription = context.getString(R.string.settings_aod_sheet_close)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog?.dismiss() }
        }
        header.addView(close, LinearLayout.LayoutParams(dpi(30f), dpi(30f)))
        return header
    }

    private fun buildRecent(): View {
        if (recentConfigs.isEmpty()) {
            return TextView(context).apply {
                text = context.getString(R.string.settings_aod_sheet_recent_empty)
                textSize = 12f
                setTextColor(ink400)
                setPadding(dpi(16f), dpi(2f), dpi(16f), dpi(8f))
            }
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(16f), 0, dpi(16f), dpi(4f))
        }
        recentConfigs.forEach { config ->
            val style = AodStyleCatalog.find(config.styleId)
            inner.addView(
                card(style, config.name, config.id == selectedConfigId) {
                    onPickConfig(config)
                    dialog?.dismiss()
                },
            )
        }
        return horizontal(inner)
    }

    private fun buildBuiltin(): View {
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(16f), 0, dpi(16f), dpi(4f))
        }
        AodStyleCatalog.styles.forEach { style ->
            inner.addView(
                card(style, context.getString(style.nameRes), selected = false) {
                    onPickTemplate(style)
                    dialog?.dismiss()
                },
            )
        }
        return horizontal(inner)
    }

    private fun horizontal(inner: LinearLayout): View = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(inner)
    }

    private fun card(style: AodStyle, label: String, selected: Boolean, onClick: () -> Unit): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f))
            background = cardBackground(selected)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dpi(88f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dpi(10f)
            }
            setOnClickListener { onClick() }
        }
        card.addView(
            AodStyleThumbView(context).apply { this.style = style },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(100f)),
        )
        card.addView(
            TextView(context).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ink100)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dpi(2f), dpi(8f), dpi(2f), dpi(2f))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return card
    }

    private fun cardBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(16f)
        setColor(if (selected) 0x263E6FD1 else 0x14FFFFFF)
        setStroke(dpi(if (selected) 1.5f else 1f), if (selected) blued else 0x14FFFFFF)
    }

    private fun groupTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        letterSpacing = 0.06f
        setTextColor(ink400)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dpi(16f), dpi(16f), dpi(16f), dpi(8f))
    }

    private fun dp(value: Float): Float = value * density

    private fun dpi(value: Float): Int = (value * density).toInt()
}

/** 样式卡缩略图：按样式画迷你息屏画面。 */
class AodStyleThumbView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    var style: AodStyle = AodStyleCatalog.find(null)
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
    private val rect = RectF()
    private val path = Path()
    private val clip = Path()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val thumbCache = HashMap<String, android.graphics.Bitmap?>()
    private val srcRect = android.graphics.Rect()
    private val dstRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        clip.reset()
        clip.addRoundRect(r(0f, 0f, w, h), dp(8f), dp(8f), Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        fill.style = Paint.Style.FILL
        fill.shader = null

        if (drawOfficialThumb(canvas, w, h)) {
            canvas.restoreToCount(save)
            stroke.color = 0x1FFFFFFF
            stroke.strokeWidth = dp(1f)
            canvas.drawRoundRect(r(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f)), dp(8f), dp(8f), stroke)
            return
        }

        when (style.id) {
            AodStyleCatalog.ID_FULLSCREEN -> {
                fill.shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(0xFF141A26.toInt(), 0xFF05070A.toInt()),
                    null, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
                radial(canvas, w * 0.74f, h * 0.16f, w * 0.60f, 0x597896D2, 0x007896D2)
            }
            AodStyleCatalog.ID_SAIL -> {
                fill.shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(0xFF2A2314.toInt(), 0xFF0B0F14.toInt()),
                    null, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
                radial(canvas, w * 0.5f, h * 1.04f, w * 0.62f, 0x8CC9A227.toInt(), 0x00C9A227)
            }
            AodStyleCatalog.ID_STARMOON -> {
                fill.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(0xFF0A1224.toInt(), 0xFF060A12.toInt(), 0xFF14243C.toInt()),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
                fill.color = 0xB3FFFFFF.toInt()
                canvas.drawCircle(w * 0.18f, h * 0.18f, dp(0.7f), fill)
                canvas.drawCircle(w * 0.34f, h * 0.10f, dp(0.6f), fill)
                canvas.drawCircle(w * 0.80f, h * 0.14f, dp(0.7f), fill)
                canvas.drawCircle(w * 0.90f, h * 0.34f, dp(0.6f), fill)
                fill.shader = RadialGradient(
                    w * 0.60f, h * 0.42f, w * 0.24f,
                    intArrayOf(0xFFFFFFFF.toInt(), 0xFFE7DECD.toInt(), 0xFF9E9484.toInt()),
                    floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(w * 0.62f, h * 0.42f, w * 0.20f, fill)
                fill.shader = null
                fill.color = 0xFF0A1220.toInt()
                path.reset()
                path.moveTo(0f, h)
                path.lineTo(0f, h * 0.86f)
                path.quadTo(w * 0.26f, h * 0.72f, w * 0.52f, h * 0.86f)
                path.quadTo(w * 0.76f, h * 0.96f, w, h * 0.80f)
                path.lineTo(w, h)
                path.close()
                canvas.drawPath(path, fill)
            }
            else -> {
                fill.shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(style.accent, 0xFF0B0F14.toInt()),
                    null, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
            }
        }

        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = dp(1f)
        stroke.pathEffect = null
        when (style.id) {
            AodStyleCatalog.ID_FULLSCREEN -> {
                fill.color = 0xD1FFFFFF.toInt()
                canvas.drawRoundRect(
                    r(w / 2f - dp(17f), h * 0.46f, w / 2f + dp(17f), h * 0.46f + dp(4f)),
                    dp(2f), dp(2f), fill,
                )
            }
            AodStyleCatalog.ID_TEXT -> {
                fill.color = 0xE0FFFFFF.toInt()
                canvas.drawRoundRect(
                    r(w / 2f - dp(20f), h * 0.40f, w / 2f + dp(20f), h * 0.40f + dp(2f)),
                    dp(1f), dp(1f), fill,
                )
                fill.color = 0x4DFFFFFF
                canvas.drawRoundRect(
                    r(w / 2f - dp(20f), h * 0.48f, w / 2f + dp(20f), h * 0.48f + dp(2f)),
                    dp(1f), dp(1f), fill,
                )
                fill.color = 0x2EFFFFFF
                canvas.drawRoundRect(
                    r(w / 2f - dp(14f), h * 0.56f, w / 2f + dp(14f), h * 0.56f + dp(2f)),
                    dp(1f), dp(1f), fill,
                )
            }
            AodStyleCatalog.ID_PORTRAIT -> {
                radial(canvas, w / 2f, h * 0.58f, w * 0.32f, 0x8CC4B4E0.toInt(), 0x00C4B4E0)
            }
            AodStyleCatalog.ID_HANDPAINT -> {
                stroke.color = 0x73E8D8B0
                stroke.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
                canvas.drawRoundRect(
                    r(w / 2f - dp(27f), h / 2f - dp(30f), w / 2f + dp(27f), h / 2f + dp(30f)),
                    dp(14f), dp(14f), stroke,
                )
                stroke.pathEffect = null
                fill.color = 0xD1FFFFFF.toInt()
                canvas.drawRoundRect(
                    r(w / 2f - dp(13f), h * 0.46f, w / 2f + dp(13f), h * 0.46f + dp(3f)),
                    dp(2f), dp(2f), fill,
                )
            }
            AodStyleCatalog.ID_STARMOON -> {
                fill.color = 0xD9FFFFFF.toInt()
                canvas.drawRoundRect(
                    r(w * 0.20f, h * 0.54f, w * 0.54f, h * 0.54f + dp(3f)),
                    dp(2f), dp(2f), fill,
                )
                fill.color = 0x66FFFFFF.toInt()
                canvas.drawRoundRect(
                    r(w * 0.20f, h * 0.62f, w * 0.40f, h * 0.62f + dp(2f)),
                    dp(1f), dp(1f), fill,
                )
            }
            else -> {
                stroke.color = 0x29FFFFFF
                canvas.drawRoundRect(
                    r(w / 2f - dp(27f), h / 2f - dp(30f), w / 2f + dp(27f), h / 2f + dp(30f)),
                    dp(12f), dp(12f), stroke,
                )
                fill.color = 0xD1FFFFFF.toInt()
                canvas.drawRoundRect(
                    r(w / 2f - dp(13f), h * 0.46f, w / 2f + dp(13f), h * 0.46f + dp(3f)),
                    dp(2f), dp(2f), fill,
                )
            }
        }

        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = dp(1f)
        stroke.pathEffect = null
        stroke.color = 0x1AFFFFFF
        canvas.drawRoundRect(r(0.5f, 0.5f, w - 0.5f, h - 0.5f), dp(8f), dp(8f), stroke)
        canvas.restoreToCount(save)
    }

    /** 官方样式底图优先（alive 编辑器缩略图），缺素材回退程序化示意。 */
    private fun drawOfficialThumb(canvas: Canvas, w: Float, h: Float): Boolean {
        val name = when (style.id) {
            AodStyleCatalog.ID_FULLSCREEN -> "fullscreen"
            AodStyleCatalog.ID_SAIL -> "set_sail"
            AodStyleCatalog.ID_LEAVE_LIGHT -> "leave_light"
            AodStyleCatalog.ID_STARMOON -> "moon_and_star"
            AodStyleCatalog.ID_DOOR -> "light_start"
            AodStyleCatalog.ID_MOUNTAIN -> "mountain"
            AodStyleCatalog.ID_GALAXY -> "galaxy"
            else -> return false
        }
        val bitmap = thumbCache.getOrPut(name) {
            runCatching {
                context.assets.open("aod/style_thumbs/$name.png").use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
        } ?: return false
        if (bitmap.isRecycled) return false
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        val scale = maxOf(w / bitmap.width, h / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        dstRect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
        return true
    }

    private fun radial(canvas: Canvas, cx: Float, cy: Float, radius: Float, center: Int, edge: Int) {
        fill.style = Paint.Style.FILL
        fill.shader = RadialGradient(cx, cy, radius, center, edge, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null
    }

    private fun r(left: Float, top: Float, right: Float, bottom: Float): RectF =
        rect.apply { set(left, top, right, bottom) }

    private fun dp(value: Float): Float = value * density
}
