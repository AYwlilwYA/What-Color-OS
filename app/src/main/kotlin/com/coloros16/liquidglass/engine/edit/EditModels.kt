package com.coloros16.liquidglass.engine.edit

import kotlin.math.abs

/** 编辑轴约束：息屏只允许水平平移，纵向留给防烧屏。 */
enum class EditAxis { FREE, HORIZONTAL_ONLY }

/** 编辑面：锁屏自由摆放，息屏只水平。 */
enum class EditSurface(val axis: EditAxis) {
    LOCK(EditAxis.FREE),
    AOD(EditAxis.HORIZONTAL_ONLY),
}

/** 时钟附加信息。 */
enum class ClockInfo { NONE, LUNAR, WEATHER }

/** 时钟字体 token：画布与真机共用，未知回退 sans。 */
object ClockFonts {
    const val SANS = "sans"
    const val SERIF = "serif"
    const val MONO = "mono"
    const val CASUAL = "casual"

    val all: List<String> = listOf(SANS, SERIF, MONO, CASUAL)

    fun normalize(token: String?): String = when (token?.trim()?.lowercase()) {
        "serif" -> SERIF
        "mono", "monospace" -> MONO
        "casual" -> CASUAL
        "sans", "sans-serif", "sans_serif" -> SANS
        else -> SANS
    }
}

/** 时钟外观：样式、字体、笔画粗细、颜色、玻璃染色、附加信息。 */
data class ClockStyle(
    val styleId: String = DEFAULT_STYLE_ID,
    val fontFamily: String = DEFAULT_FONT,
    val weight: Int = DEFAULT_WEIGHT,
    val letterSpacing: Float = 0f,
    val colorArgb: Int = WHITE,
    val glassTint: Boolean = true,
    val info: ClockInfo = ClockInfo.NONE,
) {
    fun sanitized(): ClockStyle = copy(
        styleId = styleId.takeIf { validToken(it) } ?: DEFAULT_STYLE_ID,
        fontFamily = ClockFonts.normalize(fontFamily),
        weight = weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT),
        letterSpacing = letterSpacing.coerceIn(MIN_LETTER_SPACING, MAX_LETTER_SPACING),
    )

    companion object {
        const val DEFAULT_STYLE_ID = "clock.default"
        const val DEFAULT_FONT = ClockFonts.SANS
        const val DEFAULT_WEIGHT = 300
        const val WHITE = -1
        const val MIN_WEIGHT = 100
        const val MAX_WEIGHT = 900
        const val MIN_LETTER_SPACING = -0.05f
        const val MAX_LETTER_SPACING = 0.2f

        fun validToken(value: String): Boolean =
            value.isNotBlank() && value.length <= 64 && value.none { it == '\n' || it == '\r' || it == '=' }
    }
}

/** 小组件尺寸档：只有 1×1 / 2×2 / 1×4 三档。 */
enum class WidgetSize(val columns: Int, val rows: Int) {
    SPAN_1X1(1, 1),
    SPAN_2X2(2, 2),
    SPAN_1X4(1, 4);

    companion object {
        fun fromToken(token: String?): WidgetSize? = when (token?.trim()?.lowercase()) {
            "1x1" -> SPAN_1X1
            "2x2" -> SPAN_2X2
            "1x4" -> SPAN_1X4
            else -> null
        }

        fun token(size: WidgetSize): String = "${size.columns}x${size.rows}"
    }
}

/** 一个可摆放的小组件实例；位置为归一化比例。 */
data class LockWidget(
    val typeId: String,
    val enabled: Boolean = true,
    val x: Float = 0.75f,
    val y: Float = 0.35f,
    val size: WidgetSize = WidgetSize.SPAN_1X1,
) {
    fun sanitized(): LockWidget = copy(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
    )
}

/** 底部大丸内容：无 / 手电筒 / 相机 / 拨号 / 录音 / 计算器 / 扫一扫 / 收付款。 */
enum class PillType(val id: String) {
    NONE("pill.none"),
    FLASHLIGHT("pill.flashlight"),
    CAMERA("pill.camera"),
    DIAL("pill.dial"),
    RECORDER("pill.recorder"),
    CALCULATOR("pill.calculator"),
    SCAN("pill.scan"),
    PAY("pill.pay");

    companion object {
        fun from(id: String?): PillType? = values().firstOrNull { it.id == id }
    }
}

/** 底部大丸的摆放与缩放。 */
data class PillPlacement(
    val typeId: String,
    val enabled: Boolean = true,
    val x: Float = 0.2f,
    val y: Float = 0.88f,
    val scale: Float = 1f,
) {
    fun sanitized(): PillPlacement = copy(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
    )

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 1.6f
    }
}

/** 吸附轴。 */
enum class SnapAxis { X, Y }

/** 吸附参考线类型。 */
enum class GuideKind {
    CENTER_X,
    CENTER_Y,
    THIRD_LEFT,
    THIRD_RIGHT,
    THIRD_TOP,
    THIRD_BOTTOM,
    SAFE_LEFT,
    SAFE_RIGHT,
}

/** 一条吸附参考线。 */
data class SnapGuide(val kind: GuideKind, val axis: SnapAxis, val position: Float)

/** 吸附结果：最终位置 + 命中参考线（未命中为 null，位置 -1）。 */
data class SnapResult(
    val x: Float,
    val y: Float,
    val guideX: GuideKind? = null,
    val guideY: GuideKind? = null,
    val guideXPosition: Float = -1f,
    val guideYPosition: Float = -1f,
)

/** 拖动吸附求值：纯几何，无时间与平台依赖。 */
object SnapEngine {

    const val DEFAULT_THRESHOLD = 0.02f
    const val DEFAULT_SAFE_INSET = 0.06f

    fun guides(surface: EditSurface, safeInset: Float = DEFAULT_SAFE_INSET): List<SnapGuide> {
        val list = ArrayList<SnapGuide>(8)
        list.add(SnapGuide(GuideKind.CENTER_X, SnapAxis.X, 0.5f))
        list.add(SnapGuide(GuideKind.THIRD_LEFT, SnapAxis.X, 1f / 3f))
        list.add(SnapGuide(GuideKind.THIRD_RIGHT, SnapAxis.X, 2f / 3f))
        list.add(SnapGuide(GuideKind.SAFE_LEFT, SnapAxis.X, safeInset))
        list.add(SnapGuide(GuideKind.SAFE_RIGHT, SnapAxis.X, 1f - safeInset))
        if (surface.axis == EditAxis.FREE) {
            list.add(SnapGuide(GuideKind.CENTER_Y, SnapAxis.Y, 0.5f))
            list.add(SnapGuide(GuideKind.THIRD_TOP, SnapAxis.Y, 1f / 3f))
            list.add(SnapGuide(GuideKind.THIRD_BOTTOM, SnapAxis.Y, 2f / 3f))
        }
        return list
    }

    fun snap(
        x: Float,
        y: Float,
        guides: List<SnapGuide>,
        axis: EditAxis = EditAxis.FREE,
        threshold: Float = DEFAULT_THRESHOLD,
    ): SnapResult {
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        var hitX: SnapGuide? = null
        var hitY: SnapGuide? = null
        var bestX = Float.MAX_VALUE
        var bestY = Float.MAX_VALUE
        for (guide in guides) {
            if (guide.axis == SnapAxis.X) {
                val distance = abs(cx - guide.position)
                if (distance <= threshold && distance < bestX) {
                    bestX = distance
                    hitX = guide
                }
            } else if (axis == EditAxis.FREE) {
                val distance = abs(cy - guide.position)
                if (distance <= threshold && distance < bestY) {
                    bestY = distance
                    hitY = guide
                }
            }
        }
        return SnapResult(
            x = hitX?.position ?: cx,
            y = if (axis == EditAxis.FREE) hitY?.position ?: cy else cy,
            guideX = hitX?.kind,
            guideY = if (axis == EditAxis.FREE) hitY?.kind else null,
            guideXPosition = hitX?.position ?: -1f,
            guideYPosition = if (axis == EditAxis.FREE) hitY?.position ?: -1f else -1f,
        )
    }
}

/** 全屏预览底部的三态切换段。 */
enum class PreviewSegment { AOD, LOCK, HOME }

/** 预览分段按钮的固定顺序与透明度。 */
object PreviewShelf {

    val segments: List<PreviewSegment> = listOf(PreviewSegment.AOD, PreviewSegment.LOCK, PreviewSegment.HOME)

    /** 半透明白底按钮的不透明度。 */
    const val BUTTON_ALPHA = 0.55f

    fun next(current: PreviewSegment, step: Int): PreviewSegment {
        if (segments.isEmpty()) return current
        val index = segments.indexOf(current).let { if (it < 0) 0 else it }
        val size = segments.size
        val shifted = ((index + step) % size + size) % size
        return segments[shifted]
    }
}
