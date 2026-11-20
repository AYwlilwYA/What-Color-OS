package com.coloros16.liquidglass.engine

/**
 * 手绘预设的设备候选位置：厂商原件不随包分发，运行时从设备息屏应用取。
 *
 * 结构是 zip 套 zip：息屏应用 APK 内的 `assets/handpaint.zip`，
 * 内层再按款号分目录存 `json.txt` / `startBitmap.jpg` / `finishBitmap.jpg`。
 */
object HandPaintSource {

    /** 息屏应用安装目录候选（与时钟字型同一批位置）。 */
    val APK_PATHS: List<String> = listOf(
        "/product/app/Aod/Aod.apk",
        "/system/app/Aod/Aod.apk",
        "/product/priv-app/Aod/Aod.apk",
        "/system/priv-app/Aod/Aod.apk",
    )

    /** 外层 APK 内的手绘包 asset 名。 */
    const val OUTER_ENTRY = "assets/handpaint.zip"

    /** 可用款号：0 号目录是空占位，实测 1..9 各有 json。 */
    const val FIRST_STYLE = 1
    const val LAST_STYLE = 9

    /** 缺省款号（最近使用列表首位同款）。 */
    const val DEFAULT_STYLE = 1

    /** 官方布局的样式序号范围：0..9，比素材目录多一个 0 档。 */
    const val LAYOUT_INDEX_MIN = 0
    const val LAYOUT_INDEX_MAX = 9

    /** 官方手绘画布边长（dp）。 */
    const val CANVAS_DP = 180f

    /** 官方指针时钟淡入延迟与时长（毫秒）：笔画播到此时才出时钟。 */
    const val CLOCK_DELAY_MS = 1500L
    const val CLOCK_DURATION_MS = 600L

    /** 画布中心纵向位置（屏高归一化）：花在时钟上方，预览与 hook 同值。 */
    const val CENTER_Y = 0.38f

    /**
     * 布局序号 → 素材款号：0 档无对应素材（自定义档），其余同号。
     *
     * 返回 0 表示无预置轨迹，调用方据此走自定义或回退缺省。
     */
    fun styleOfLayoutIndex(index: Int): Int =
        if (index in FIRST_STYLE..LAST_STYLE) index else 0

    /** 素材款号 → 布局序号（同号；越界归 0 档）。 */
    fun layoutIndexOfStyle(style: Int): Int =
        if (validStyle(style)) style else LAYOUT_INDEX_MIN

    /** 该布局序号是否有预置轨迹。 */
    fun hasPreset(index: Int): Boolean = styleOfLayoutIndex(index) > 0

    /**
     * 指针时钟淡入进度：延迟内恒 0，之后按时长线性到 1。
     *
     * [elapsedMs] 为笔画重放起点至今的毫秒数。
     */
    fun clockFadeAt(elapsedMs: Long): Float {
        if (elapsedMs <= CLOCK_DELAY_MS) return 0f
        if (CLOCK_DURATION_MS <= 0L) return 1f
        return ((elapsedMs - CLOCK_DELAY_MS).toFloat() / CLOCK_DURATION_MS).coerceIn(0f, 1f)
    }

    /** 画布边长（像素）= 官方 dp × 像素密度。 */
    fun canvasPx(density: Float): Float = CANVAS_DP * density

    /** 内层某款的轨迹文件名。 */
    fun jsonEntry(style: Int): String = "handpaint/$style/json.txt"

    /** 内层某款的起始图与完成图文件名。 */
    fun startEntry(style: Int): String = "handpaint/$style/startBitmap.jpg"

    fun finishEntry(style: Int): String = "handpaint/$style/finishBitmap.jpg"

    /** 款号是否可用。 */
    fun validStyle(style: Int): Boolean = style in FIRST_STYLE..LAST_STYLE

    /** 越界款号归一到缺省，避免取到空目录。 */
    fun normalizeStyle(style: Int): Int = if (validStyle(style)) style else DEFAULT_STYLE
}