package com.coloros16.liquidglass.engine

import kotlin.math.cos
import kotlin.math.sin
import java.util.Random

/** 渐变色标：位置 + AARRGGBB。 */
class GradientStop(val position: Float, val argb: Int)

/** 径向渐变描述：圆心、半径与色标；调用方据此建 Paint shader。 */
class RadialSpec(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val stops: List<GradientStop>,
)

/** 线性渐变描述：两端点与色标。 */
class LinearSpec(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val stops: List<GradientStop>,
)

/** 圆点描述：圆心、半径、颜色。 */
class DotSpec(val cx: Float, val cy: Float, val radius: Float, val argb: Int)

/** 椭圆云团描述：中心、横半径、纵横比与径向色标。 */
class CloudSpec(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val yScale: Float,
    val stops: List<GradientStop>,
)

/** 山脊路径段：三次贝塞尔的两个控制点与终点。 */
class RidgeCubic(val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float, val x: Float, val y: Float)

/** 山脊闭合路径：起点 + 若干贝塞尔段 + 收尾两点。 */
class RidgePath(
    val startX: Float,
    val startY: Float,
    val firstX: Float,
    val firstY: Float,
    val cubics: List<RidgeCubic>,
    val endX: Float,
    val endY: Float,
)

/**
 * 星月场景（天空 / 银河 / 星场 / 云带 / 山脊 / 月球）的几何与配色求值。
 *
 * 纯 JVM，零 android 依赖：只算坐标、半径、色标与路径点，具体绘制由调用方做。
 * App 预览与 hook 共用同一份，避免两侧各写一套导致画面漂移。
 */
object SceneSkyMath {

    /** 星点数量；每点四通道 nx / ny / radius / brightness。 */
    const val STAR_COUNT = 96

    /** 月海坑数量；每坑四通道 dx / dy / r / alpha（相对月球半径）。 */
    const val MOON_MARK_COUNT = 38

    /** 星点与月海坑的固定随机种子：两侧必须同种子才同一幅画面。 */
    const val SEED = 11L

    const val STAR_RGB_R = 226
    const val STAR_RGB_G = 236
    const val STAR_RGB_B = 252
    const val STAR_HALO_R = 210
    const val STAR_HALO_G = 228
    const val STAR_HALO_B = 255

    /** 星点半径系数（乘调用方给的像素密度）。 */
    const val STAR_RADIUS_FACTOR = 0.55f * 0.5f

    /** 亮星才画光晕的亮度门槛与光晕倍数、光晕透明度分母。 */
    const val STAR_HALO_BRIGHTNESS = 0.90f
    const val STAR_HALO_SCALE = 3.2f
    const val STAR_HALO_ALPHA_DIV = 8

    /** 星点低于此 alpha 字节直接跳过。 */
    const val STAR_MIN_ALPHA = 4

    /** 星场横向视差系数、纵向视差系数。 */
    const val STAR_PARALLAX_X = 0.20f
    const val STAR_PARALLAX_Y = 0.12f

    /** 星场随亮度衰减的系数与下限。 */
    const val STAR_LIGHT_K = 0.86f
    const val STAR_LIGHT_FLOOR = 0.06f

    /** 星点纵向淡出起点与跨度、淡出深度。 */
    const val STAR_FADE_START = 0.46f
    const val STAR_FADE_SPAN = 0.14f
    const val STAR_FADE_DEPTH = 0.82f

    const val MILKYWAY_ANGLE_DEG = -24f
    const val MILKYWAY_PIVOT_X = 0.52f
    const val MILKYWAY_PIVOT_Y = 0.34f
    const val MILKYWAY_TOP = 0.16f
    const val MILKYWAY_BOTTOM = 0.52f
    const val MILKYWAY_LEFT = -0.3f
    const val MILKYWAY_RIGHT = 1.3f
    const val MILKYWAY_LIGHT_K = 0.85f
    const val MILKYWAY_LIGHT_FLOOR = 0.05f
    const val MILKYWAY_ALPHA = 0.55f

    /** 云带横向/纵向视差系数。 */
    const val CLOUD_PARALLAX_X = 0.34f
    const val CLOUD_PARALLAX_Y = 0.16f

    /** 云团纵横比（ry = rx × 此值）。 */
    const val CLOUD_ASPECT = 0.17f

    /** 云团低于此透明度直接跳过。 */
    const val CLOUD_MIN_ALPHA = 0.002f

    /** 后层（layer 0）与前层（layer 1）的视差与强度系数。 */
    const val CLOUD_BACK_PARALLAX_X = 0.65f
    const val CLOUD_BACK_PARALLAX_Y = 0.5f
    const val CLOUD_FRONT_PARALLAX_X = 1f
    const val CLOUD_FRONT_PARALLAX_Y = 0.8f
    const val CLOUD_BACK_STRENGTH = 1f
    const val CLOUD_FRONT_STRENGTH = 0.8f

    /** 山脊纵向漂移基准与系数。 */
    const val RIDGE_DRIFT_BASE = 0.47f
    const val RIDGE_DRIFT_K = 0.42f

    /** 山脊路径左右与底部的越界量。 */
    const val RIDGE_OVERSCAN_X = 0.06f
    const val RIDGE_OVERSCAN_Y = 1.06f

    /** 后山脊渐变起点、前山脊渐变起点。 */
    const val RIDGE_BACK_GRADIENT_TOP = 0.66f
    const val RIDGE_FRONT_GRADIENT_TOP = 0.78f

    /** 月球半径系数（相对宽度，再乘元素缩放）。 */
    const val MOON_RADIUS = 0.355f

    /** 月球外两层光晕的半径倍数与透明度。 */
    const val MOON_GLOW_OUTER_SCALE = 2.0f
    const val MOON_GLOW_OUTER_ALPHA = 0.07f
    const val MOON_GLOW_INNER_SCALE = 1.15f
    const val MOON_GLOW_INNER_ALPHA = 0.11f

    /** 月球本体径向渐变的高光偏移与半径倍数。 */
    const val MOON_BODY_DX = -0.30f
    const val MOON_BODY_DY = -0.38f
    const val MOON_BODY_RADIUS = 1.30f

    /** 月海坑渐变半径倍数。 */
    const val MOON_MARK_RADIUS = 1.55f

    /** 月球底部薄雾椭圆的四个边界系数与径向参数。 */
    const val MOON_VEIL_LEFT = -1.05f
    const val MOON_VEIL_TOP = 0.42f
    const val MOON_VEIL_RIGHT = 1.05f
    const val MOON_VEIL_BOTTOM = 0.92f
    const val MOON_VEIL_CY = 0.64f
    const val MOON_VEIL_RADIUS = 0.90f

    /** 描边宽度与内缩量（dp，乘调用方像素密度）。 */
    const val MOON_STROKE_DP = 1.2f
    const val MOON_STROKE_INSET_DP = 0.6f

    /** 云带后层与前层的整体强度（调用方给的初值）。 */
    const val CLOUD_BACK_LAYER_STRENGTH = 0.85f
    const val CLOUD_FRONT_LAYER_STRENGTH = 0.45f

    private const val TWO_PI = 6.2831855f

    /** 四通道扁平数组的通道数。 */
    const val STRIDE = 4

    /**
     * 星点表与月海坑表一次生成：共用同一条 Random 序列。
     *
     * first = 星点（nx∈[0,1)、ny∈[0,0.60)、radius∈[0.6,1.95)、brightness∈[0.18,1.0)）；
     * second = 月海坑（dx/dy∈[-0.82,0.82]、r∈[0.018,0.08)、alpha∈[0.06,0.16)）。
     * 必须一次生成：分两次各起 Random 会让月海坑落到另一条序列上。
     */
    fun fields(): Pair<FloatArray, FloatArray> {
        val rnd = Random(SEED)
        val stars = FloatArray(STAR_COUNT * STRIDE)
        var i = 0
        while (i < stars.size) {
            stars[i] = rnd.nextFloat()
            stars[i + 1] = rnd.nextFloat() * 0.60f
            stars[i + 2] = 0.6f + rnd.nextFloat() * 1.35f
            stars[i + 3] = 0.18f + rnd.nextFloat() * 0.82f
            i += STRIDE
        }
        val marks = FloatArray(MOON_MARK_COUNT * STRIDE)
        i = 0
        while (i < marks.size) {
            val angle = rnd.nextFloat() * TWO_PI
            val dist = 0.10f + rnd.nextFloat() * 0.52f
            marks[i] = (cos(angle) * dist).coerceIn(-0.82f, 0.82f)
            marks[i + 1] = (sin(angle) * dist).coerceIn(-0.82f, 0.82f)
            marks[i + 2] = 0.018f + rnd.nextFloat() * 0.062f
            marks[i + 3] = 0.06f + rnd.nextFloat() * 0.10f
            i += STRIDE
        }
        return Pair(stars, marks)
    }

    /** 云团表：cx / cy / rx（归一化）/ alpha。 */
    fun cloudBlobs(): FloatArray = floatArrayOf(
        0.06f, 0.88f, 0.46f, 0.11f,
        0.28f, 0.92f, 0.52f, 0.09f,
        0.52f, 0.86f, 0.58f, 0.10f,
        0.78f, 0.91f, 0.50f, 0.08f,
        0.18f, 0.74f, 0.34f, 0.05f,
        0.64f, 0.76f, 0.42f, 0.05f,
    )

    /** 后山脊折线控制点（归一化 x/y 交替）。 */
    fun backRidge(): FloatArray = floatArrayOf(
        0.00f, 0.792f, 0.16f, 0.716f, 0.31f, 0.792f, 0.47f, 0.678f,
        0.62f, 0.772f, 0.79f, 0.702f, 1.00f, 0.786f,
    )

    /** 前山脊折线控制点（归一化 x/y 交替）。 */
    fun frontRidge(): FloatArray = floatArrayOf(
        0.00f, 0.900f, 0.19f, 0.842f, 0.38f, 0.918f, 0.55f, 0.826f,
        0.71f, 0.906f, 0.86f, 0.852f, 1.00f, 0.918f,
    )

    /** ARGB 通道插值；t 越界先钳到 0..1。 */
    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + ((y - x) * f).toInt()) and 0xFF
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** 三段配色：u≤0.5 走 a→b，u>0.5 走 b→c。 */
    fun mix3(a: Int, b: Int, c: Int, u: Float): Int =
        if (u <= 0.5f) lerpColor(a, b, u * 2f) else lerpColor(b, c, (u - 0.5f) * 2f)

    /** 保留 RGB、替换 alpha。 */
    fun withAlpha(argb: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    fun argb(alphaByte: Int, r: Int, g: Int, b: Int): Int =
        (alphaByte.coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b

    /** 天空竖直渐变四色标。 */
    fun skyGradient(w: Float, h: Float, u: Float): LinearSpec = LinearSpec(
        0f, 0f, 0f, h,
        listOf(
            GradientStop(0f, mix3(0xFF03050A.toInt(), 0xFF060B16.toInt(), 0xFF0A1426.toInt(), u)),
            GradientStop(0.46f, mix3(0xFF070D18.toInt(), 0xFF0C1626.toInt(), 0xFF132340.toInt(), u)),
            GradientStop(0.80f, mix3(0xFF0F1A2B.toInt(), 0xFF1B2C49.toInt(), 0xFF2B4268.toInt(), u)),
            GradientStop(1f, mix3(0xFF1B2739.toInt(), 0xFF2C4467.toInt(), 0xFF48678F.toInt(), u)),
        ),
    )

    /** 天空底部与右上的两团柔光。 */
    fun skyGlows(w: Float, h: Float): List<RadialSpec> = listOf(
        RadialSpec(
            w * 0.50f, h * 1.04f, w * 0.85f,
            listOf(GradientStop(0f, withAlpha(0xFF9FB4D8.toInt(), 0.10f)), GradientStop(1f, 0x009FB4D8)),
        ),
        RadialSpec(
            w * 0.76f, h * 0.30f, w * 0.70f,
            listOf(GradientStop(0f, withAlpha(0xFF6E86B8.toInt(), 0.06f)), GradientStop(1f, 0x006E86B8)),
        ),
    )

    /** 银河带透明度：随场景亮度衰减。 */
    fun milkyWayAlpha(light: Float): Float =
        (1f - light * MILKYWAY_LIGHT_K).coerceIn(MILKYWAY_LIGHT_FLOOR, 1f) * MILKYWAY_ALPHA

    /** 银河带渐变（在旋转后的坐标系里画，旋转由调用方施加）。 */
    fun milkyWayGradient(w: Float, h: Float, light: Float): LinearSpec {
        val a = milkyWayAlpha(light)
        return LinearSpec(
            0f, h * MILKYWAY_TOP, 0f, h * MILKYWAY_BOTTOM,
            listOf(
                GradientStop(0f, 0x00B9D0FF),
                GradientStop(0.32f, argb((a * 58f).toInt(), 205, 219, 245)),
                GradientStop(0.62f, argb((a * 34f).toInt(), 205, 219, 245)),
                GradientStop(1f, 0x00B9D0FF),
            ),
        )
    }

    /** 星场基础透明度：随场景亮度衰减。 */
    fun starAlphaBase(light: Float): Float =
        (1f - light * STAR_LIGHT_K).coerceIn(STAR_LIGHT_FLOOR, 1f)

    /** 星点纵向淡出：靠下的星点压暗，避免与山脊抢视觉。 */
    fun starFade(ny: Float): Float =
        if (ny > STAR_FADE_START) 1f - (ny - STAR_FADE_START) / STAR_FADE_SPAN * STAR_FADE_DEPTH else 1f

    /**
     * 星场逐点求值：把四通道表按相机与密度换算成像素圆点。
     *
     * [densityPx] = 像素密度 × UI 缩放；alpha 不足的点直接不产出。
     * 亮星额外产出一个光晕点（同圆心、更大半径、更淡）。
     */
    fun stars(
        field: FloatArray,
        w: Float,
        h: Float,
        light: Float,
        cameraX: Float,
        cameraY: Float,
        cameraScale: Float,
        densityPx: Float,
        out: MutableList<DotSpec>,
    ) {
        out.clear()
        val alphaBase = starAlphaBase(light)
        val dx = (0.5f - cameraX) * w * STAR_PARALLAX_X
        val dy = (0.5f - cameraY) * h * STAR_PARALLAX_Y
        var i = 0
        while (i < field.size) {
            val nx = field[i]
            val ny = field[i + 1]
            val radius = field[i + 2]
            val brightness = field[i + 3]
            val a = (alphaBase * brightness * starFade(ny) * 255f).toInt().coerceIn(0, 255)
            if (a > STAR_MIN_ALPHA) {
                val sx = ((nx - 0.5f) * cameraScale + 0.5f) * w + dx
                val sy = ((ny - 0.5f) * cameraScale + 0.5f) * h + dy
                val r = radius * STAR_RADIUS_FACTOR * densityPx
                out.add(DotSpec(sx, sy, r, argb(a, STAR_RGB_R, STAR_RGB_G, STAR_RGB_B)))
                if (brightness > STAR_HALO_BRIGHTNESS) {
                    out.add(
                        DotSpec(
                            sx, sy, r * STAR_HALO_SCALE,
                            argb(a / STAR_HALO_ALPHA_DIV, STAR_HALO_R, STAR_HALO_G, STAR_HALO_B),
                        ),
                    )
                }
            }
            i += STRIDE
        }
    }

    /**
     * 云带逐团求值：layer 0 为后层、1 为前层，视差与强度各不同。
     *
     * 调用方按 [CloudSpec.yScale] 压扁圆形成椭圆。
     */
    fun clouds(
        blobs: FloatArray,
        w: Float,
        h: Float,
        strength: Float,
        layer: Int,
        cameraX: Float,
        cameraY: Float,
        out: MutableList<CloudSpec>,
    ) {
        out.clear()
        val back = layer == 0
        val px = (0.5f - cameraX) * w * CLOUD_PARALLAX_X *
            (if (back) CLOUD_BACK_PARALLAX_X else CLOUD_FRONT_PARALLAX_X)
        val py = (0.5f - cameraY) * h * CLOUD_PARALLAX_Y *
            (if (back) CLOUD_BACK_PARALLAX_Y else CLOUD_FRONT_PARALLAX_Y)
        val k = if (back) CLOUD_BACK_STRENGTH else CLOUD_FRONT_STRENGTH
        var i = 0
        while (i < blobs.size) {
            val rx = blobs[i + 2] * w
            val a = blobs[i + 3] * strength * k
            if (a > CLOUD_MIN_ALPHA && rx > 0f) {
                out.add(
                    CloudSpec(
                        blobs[i] * w + px,
                        blobs[i + 1] * h + py,
                        rx,
                        CLOUD_ASPECT,
                        listOf(
                            GradientStop(0f, argb((a * 255f).toInt(), 158, 176, 208)),
                            GradientStop(0.58f, argb((a * 120f).toInt(), 138, 156, 190)),
                            GradientStop(1f, 0x0080919F),
                        ),
                    ),
                )
            }
            i += STRIDE
        }
    }

    /** 山脊纵向漂移量（像素）。 */
    fun ridgeDrift(h: Float, cameraY: Float): Float = (RIDGE_DRIFT_BASE - cameraY) * h * RIDGE_DRIFT_K

    /**
     * 山脊闭合路径：折线控制点转三次贝塞尔，两控制点同 x（中点）保证平滑。
     *
     * 首尾越界到画面外，避免左右露缝。
     */
    fun ridgePath(points: FloatArray, w: Float, h: Float, drift: Float): RidgePath {
        val cubics = ArrayList<RidgeCubic>()
        var i = 2
        while (i < points.size) {
            val prevX = w * points[i - 2]
            val prevY = h * points[i - 1] + drift
            val nextX = w * points[i]
            val nextY = h * points[i + 1] + drift
            val midX = (prevX + nextX) / 2f
            cubics.add(RidgeCubic(midX, prevY, midX, nextY, nextX, nextY))
            i += 2
        }
        return RidgePath(
            -w * RIDGE_OVERSCAN_X, h * RIDGE_OVERSCAN_Y,
            -w * RIDGE_OVERSCAN_X, h * points[1] + drift,
            cubics,
            w * (1f + RIDGE_OVERSCAN_X), h * RIDGE_OVERSCAN_Y,
        )
    }

    /** 后山脊渐变。 */
    fun ridgeBackGradient(w: Float, h: Float, u: Float, drift: Float): LinearSpec = LinearSpec(
        0f, h * RIDGE_BACK_GRADIENT_TOP + drift, 0f, h,
        listOf(
            GradientStop(0f, mix3(0xFF0B1220.toInt(), 0xFF101A2D.toInt(), 0xFF16233C.toInt(), u)),
            GradientStop(1f, mix3(0xFF05080F.toInt(), 0xFF080C15.toInt(), 0xFF0A1020.toInt(), u)),
        ),
    )

    /** 前山脊渐变。 */
    fun ridgeFrontGradient(w: Float, h: Float, u: Float, drift: Float): LinearSpec = LinearSpec(
        0f, h * RIDGE_FRONT_GRADIENT_TOP + drift, 0f, h,
        listOf(
            GradientStop(0f, mix3(0xFF060A10.toInt(), 0xFF080D16.toInt(), 0xFF0C1220.toInt(), u)),
            GradientStop(1f, 0xFF010204.toInt()),
        ),
    )

    /** 月球半径（像素）。 */
    fun moonRadius(w: Float, elementScale: Float): Float = w * MOON_RADIUS * elementScale

    /** 月球外两层光晕。 */
    fun moonGlows(cx: Float, cy: Float, radius: Float): List<RadialSpec> = listOf(
        RadialSpec(
            cx, cy, radius * MOON_GLOW_OUTER_SCALE,
            listOf(GradientStop(0f, withAlpha(0xFFA7C2EE.toInt(), MOON_GLOW_OUTER_ALPHA)), GradientStop(1f, 0x00A7C2EE)),
        ),
        RadialSpec(
            cx, cy, radius * MOON_GLOW_INNER_SCALE,
            listOf(GradientStop(0f, withAlpha(0xFFDCE7F7.toInt(), MOON_GLOW_INNER_ALPHA)), GradientStop(1f, 0x00DCE7F7)),
        ),
    )

    /** 月球本体六段径向渐变（高光偏左上）。 */
    fun moonBody(cx: Float, cy: Float, radius: Float): RadialSpec = RadialSpec(
        cx + radius * MOON_BODY_DX, cy + radius * MOON_BODY_DY, radius * MOON_BODY_RADIUS,
        listOf(
            GradientStop(0f, 0xFFFFFFFF.toInt()),
            GradientStop(0.22f, 0xFFFEF9EF.toInt()),
            GradientStop(0.46f, 0xFFF1E7D5.toInt()),
            GradientStop(0.66f, 0xFFD5C8B2.toInt()),
            GradientStop(0.85f, 0xFFA79B87.toInt()),
            GradientStop(1f, 0xFF6E665A.toInt()),
        ),
    )

    /** 月海：四团大尺度暗斑（在月球圆内裁切后画）。 */
    fun moonSeas(cx: Float, cy: Float, radius: Float): List<RadialSpec> = listOf(
        RadialSpec(
            cx - radius * 0.28f, cy - radius * 0.06f, radius * 0.58f,
            listOf(GradientStop(0f, 0x3D2A2519), GradientStop(1f, 0x00272319)),
        ),
        RadialSpec(
            cx + radius * 0.22f, cy + radius * 0.32f, radius * 0.50f,
            listOf(GradientStop(0f, 0x382A2519), GradientStop(1f, 0x00272319)),
        ),
        RadialSpec(
            cx + radius * 0.32f, cy - radius * 0.24f, radius * 0.42f,
            listOf(GradientStop(0f, 0x2B2A2519), GradientStop(1f, 0x00272319)),
        ),
        RadialSpec(
            cx - radius * 0.46f, cy + radius * 0.34f, radius * 0.46f,
            listOf(GradientStop(0f, 0x33262118), GradientStop(1f, 0x00262118)),
        ),
    )

    /** 月海坑逐坑求值。 */
    fun moonCraters(marks: FloatArray, cx: Float, cy: Float, radius: Float, out: MutableList<RadialSpec>) {
        out.clear()
        var i = 0
        while (i < marks.size) {
            val mx = cx + marks[i] * radius
            val my = cy + marks[i + 1] * radius
            val mr = marks[i + 2] * radius
            val ma = marks[i + 3]
            out.add(
                RadialSpec(
                    mx, my, mr * MOON_MARK_RADIUS,
                    listOf(
                        GradientStop(0f, argb((ma * 255f).toInt(), 68, 60, 49)),
                        GradientStop(0.48f, argb((ma * 70f).toInt(), 124, 113, 96)),
                        GradientStop(1f, 0x00786E60),
                    ),
                ),
            )
            i += STRIDE
        }
    }

    /** 月球左上高光。 */
    fun moonSpecular(cx: Float, cy: Float, radius: Float): RadialSpec = RadialSpec(
        cx - radius * 0.44f, cy - radius * 0.50f, radius * 0.86f,
        listOf(GradientStop(0f, 0x24FFFFFF), GradientStop(1f, 0x00FFFFFF)),
    )

    /** 月球昼夜分界：斜向线性暗化。 */
    fun moonTerminator(cx: Float, cy: Float, radius: Float): LinearSpec = LinearSpec(
        cx + radius * 0.95f, cy - radius * 0.75f, cx - radius * 0.85f, cy + radius * 0.95f,
        listOf(
            GradientStop(0f, 0x00000000),
            GradientStop(0.42f, 0x12000000),
            GradientStop(1f, 0x8F05070C.toInt()),
        ),
    )

    /** 月球右下暗面。 */
    fun moonNightSide(cx: Float, cy: Float, radius: Float): RadialSpec = RadialSpec(
        cx + radius * 0.52f, cy + radius * 0.42f, radius * 1.18f,
        listOf(
            GradientStop(0f, 0xE60A0E16.toInt()),
            GradientStop(0.62f, 0xC80A0E16.toInt()),
            GradientStop(1f, 0x000A0E16),
        ),
    )

    /** 月球边缘描边渐变。 */
    fun moonRimGradient(cx: Float, cy: Float, radius: Float): LinearSpec = LinearSpec(
        cx - radius, cy - radius, cx + radius, cy + radius,
        listOf(
            GradientStop(0f, 0x33FFFFFF),
            GradientStop(0.55f, 0x0DFFFFFF),
            GradientStop(1f, 0x00FFFFFF),
        ),
    )

    /** 月球底部薄雾（在椭圆内裁切后画）。 */
    fun moonVeil(cx: Float, cy: Float, radius: Float): RadialSpec = RadialSpec(
        cx, cy + radius * MOON_VEIL_CY, radius * MOON_VEIL_RADIUS,
        listOf(
            GradientStop(0f, argb(46, 168, 186, 218)),
            GradientStop(0.6f, argb(20, 150, 168, 200)),
            GradientStop(1f, 0x0096A8C8),
        ),
    )
}
