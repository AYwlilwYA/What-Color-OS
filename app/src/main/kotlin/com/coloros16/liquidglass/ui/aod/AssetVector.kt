package com.coloros16.liquidglass.ui.aod

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.math.max

/**
 * 从 assets 里的矢量 XML 构造 Drawable：只支持 vector/path 的填充与描边。
 *
 * 系统导出的图标是二进制 XML，落盘前已解码成文本，这里按 viewport 缩放绘制。
 */
object AssetVector {

    private data class Segment(
        val path: Path,
        val fill: Int,
        val strokeColor: Int,
        val strokeWidth: Float,
    )

    private class Vector(val viewportW: Float, val viewportH: Float, val segments: List<Segment>) : Drawable() {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private var tint: Int? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val scale = minOf(b.width() / viewportW, b.height() / viewportH)
            val save = canvas.save()
            canvas.translate(
                b.left + (b.width() - viewportW * scale) / 2f,
                b.top + (b.height() - viewportH * scale) / 2f,
            )
            canvas.scale(scale, scale)
            for (seg in segments) {
                if (Color.alpha(seg.fill) > 0) {
                    fillPaint.color = tint ?: seg.fill
                    canvas.drawPath(seg.path, fillPaint)
                }
                if (seg.strokeWidth > 0f && Color.alpha(seg.strokeColor) > 0) {
                    strokePaint.color = seg.strokeColor
                    strokePaint.strokeWidth = seg.strokeWidth
                    canvas.drawPath(seg.path, strokePaint)
                }
            }
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Drawable.getOpacity 已废弃", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        override fun setTint(tintColor: Int) {
            tint = tintColor
            invalidateSelf()
        }
    }

    private val cache = HashMap<String, Drawable>()

    /** 解析并缓存；失败返回 null。 */
    fun load(assets: android.content.res.AssetManager, path: String): Drawable? {
        synchronized(cache) { cache[path]?.let { return it.constantState?.newDrawable()?.mutate() ?: it } }
        val parsed = runCatching { parse(assets, path) }.getOrNull() ?: return null
        synchronized(cache) { cache[path] = parsed }
        return parsed.constantState?.newDrawable()?.mutate() ?: parsed
    }

    private fun parse(assets: android.content.res.AssetManager, path: String): Drawable {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        assets.open(path).bufferedReader().use { parser.setInput(it) }
        var viewportW = 24f
        var viewportH = 24f
        val segments = ArrayList<Segment>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "vector" -> {
                        viewportW = attr(parser, "viewportWidth")?.toFloatOrNull() ?: viewportW
                        viewportH = attr(parser, "viewportHeight")?.toFloatOrNull() ?: viewportH
                    }
                    "path" -> {
                        val data = attr(parser, "pathData") ?: ""
                        if (data.isNotEmpty()) {
                            val p = runCatching {
                                androidx.core.graphics.PathParser.createPathFromPathData(data)
                            }.getOrNull()
                            if (p != null) {
                                segments.add(
                                    Segment(
                                        p,
                                        color(attr(parser, "fillColor"), Color.TRANSPARENT),
                                        color(attr(parser, "strokeColor"), Color.TRANSPARENT),
                                        attr(parser, "strokeWidth")?.toFloatOrNull() ?: 0f,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return Vector(max(1f, viewportW), max(1f, viewportH), segments)
    }

    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == name) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun color(text: String?, fallback: Int): Int {
        if (text.isNullOrBlank()) return fallback
        return runCatching { Color.parseColor(text) }.getOrDefault(fallback)
    }

    /** 效果 id → assets 里的原版图标路径；没有对应素材返回 null。 */
    fun iconPath(effectId: String?): String? = when (effectId) {
        EffectCatalog.LOCK_WP_NONE -> "effect/icon/none.webp"
        EffectCatalog.LOCK_WP_RAINBOW -> "effect/meizu/rainbow.png"
        EffectCatalog.LOCK_WP_WAVE -> "effect/meizu/wave.png"
        EffectCatalog.LOCK_WP_FOG -> "effect/meizu/fog.png"
        EffectCatalog.LOCK_WP_STRIPES_FINE -> "effect/icon/stripes_fine.webp"
        EffectCatalog.LOCK_WP_STRIPES_COARSE -> "effect/icon/stripes_coarse.webp"
        EffectCatalog.LOCK_WP_LIGHT_STRIPES -> "effect/icon/light_stripes.webp"
        EffectCatalog.LOCK_WP_GRID -> "effect/icon/grid.webp"
        EffectCatalog.LOCK_WP_WATER -> "effect/icon/water.webp"
        EffectCatalog.LOCK_WP_RIPPLE -> "effect/icon/ripple.webp"
        EffectCatalog.LOCK_WP_BLUR -> "effect/icon/blur_on.xml"
        EffectCatalog.LOCK_SP_BOKEH -> "effect/icon/depth.xml"
        else -> null
    }

}
