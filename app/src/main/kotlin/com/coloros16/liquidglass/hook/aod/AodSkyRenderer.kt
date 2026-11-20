package com.coloros16.liquidglass.hook.aod

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.coloros16.liquidglass.engine.CloudSpec
import com.coloros16.liquidglass.engine.CubicBezierEasing
import com.coloros16.liquidglass.engine.DotSpec
import com.coloros16.liquidglass.engine.ElementPose
import com.coloros16.liquidglass.engine.GradientStop
import com.coloros16.liquidglass.engine.LinearSpec
import com.coloros16.liquidglass.engine.RadialSpec
import com.coloros16.liquidglass.engine.RidgePath
import com.coloros16.liquidglass.engine.SceneContract
import com.coloros16.liquidglass.engine.SceneSample
import com.coloros16.liquidglass.engine.SceneSkyMath
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneTimeline
import com.coloros16.liquidglass.engine.SkyBranchPolicy
import com.coloros16.liquidglass.engine.TransitionEdge

/**
 * 星月场景程序化天空绘制：把引擎求值出的色标与路径点落成 Canvas 调用。
 *
 * 求值一律走 [SceneSkyMath]，本件只做「描述 → android.graphics 对象」的换算，
 * 不自算坐标、不自定配色。星点表与月海坑表一次生成后常驻，逐帧复用产出列表不分配。
 */
object AodSkyRenderer {

    /** 场景时间轴的播放缓动，与预览侧同一条曲线。 */
    val PLAY_CURVE = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)

    /** 云带后层与前层的整体强度。 */
    const val BACK_STRENGTH = SceneSkyMath.CLOUD_BACK_LAYER_STRENGTH
    const val FRONT_STRENGTH = SceneSkyMath.CLOUD_FRONT_LAYER_STRENGTH

    /** 低于此层透明度不画月球。 */
    const val MOON_MIN_ALPHA = 0.002f

    /** 天空底色（压暗用），与预览侧同一支色。 */
    const val DIM_RGB_R = 2
    const val DIM_RGB_G = 4
    const val DIM_RGB_B = 10

    private val fields = SceneSkyMath.fields()
    private val starField = fields.first
    private val moonMarks = fields.second
    private val cloudBlobs = SceneSkyMath.cloudBlobs()
    private val backRidge = SceneSkyMath.backRidge()
    private val frontRidge = SceneSkyMath.frontRidge()

    private val sample = SceneSample()
    private val starDots = ArrayList<DotSpec>()
    private val cloudSpecs = ArrayList<CloudSpec>()
    private val frontClouds = ArrayList<CloudSpec>()
    private val craterSpecs = ArrayList<RadialSpec>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    /** 上一帧产出条数，供诊断区分「没进分支」与「进了被门槛滤空」。 */
    class Counts {
        var stars = 0
        var cloudsBack = 0
        var cloudsFront = 0
        var craters = 0
        var moon = false
        var light = 1f
        var cameraX = 0.5f
        var cameraY = 0.5f
    }

    /**
     * 画整幅程序化天空：底色渐变 → 柔光 → 银河 → 星场 → 云带后层 →
     * 山脊 → 月球（按层透明度）→ 云带前层 → 压暗。
     *
     * [densityPx] = 像素密度 × UI 缩放，只影响星点半径与月球描边宽度。
     * 月球位姿取自契约元素轨道；契约没给元素就不画月球，不自造位姿。
     */
    fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        contract: SceneContract,
        state: SceneState,
        edge: TransitionEdge?,
        t: Float,
        aodAlpha: Float,
        wallpaperScale: Float,
        dim: Float,
        densityPx: Float,
        out: Counts,
    ): Boolean {
        if (w <= 0f || h <= 0f) return false
        val u = SceneTimeline.uOf(edge, t, state, PLAY_CURVE)
        sample.sample(contract, u)
        out.light = sample.light
        out.cameraX = sample.cameraX
        out.cameraY = sample.cameraY

        SceneSkyMath.stars(
            starField, w, h, sample.light,
            sample.cameraX, sample.cameraY, sample.cameraScale,
            densityPx, starDots,
        )
        SceneSkyMath.clouds(
            cloudBlobs, w, h, BACK_STRENGTH, 0,
            sample.cameraX, sample.cameraY, cloudSpecs,
        )
        out.stars = starDots.size
        out.cloudsBack = cloudSpecs.size
        val moonPose = sample.elements.getOrNull(0)
        out.moon = SkyBranchPolicy.moonDrawable(moonPose != null, aodAlpha, MOON_MIN_ALPHA)
        out.craters = 0
        if (out.moon && moonPose != null) {
            val radius = SceneSkyMath.moonRadius(w, moonPose.scale)
            if (radius > 0f) {
                SceneSkyMath.moonCraters(
                    moonMarks, moonPose.x * w, moonPose.y * h, radius, craterSpecs,
                )
                out.craters = craterSpecs.size
            } else {
                out.moon = false
            }
        }

        drawSky(canvas, w, h, u)
        drawMilkyWay(canvas, w, h)
        paintStars(canvas)

        val world = canvas.save()
        canvas.scale(wallpaperScale, wallpaperScale, w / 2f, h / 2f)
        paintClouds(canvas, cloudSpecs)
        drawRidges(canvas, w, h, u)
        if (out.moon && moonPose != null) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, alphaByte(aodAlpha))
            drawMoon(canvas, w, h, moonPose, densityPx)
            canvas.restoreToCount(layer)
        }
        SceneSkyMath.clouds(
            cloudBlobs, w, h, FRONT_STRENGTH, 1,
            sample.cameraX, sample.cameraY, frontClouds,
        )
        out.cloudsFront = frontClouds.size
        paintClouds(canvas, frontClouds)
        canvas.restoreToCount(world)

        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = dimColor(dim)
        canvas.drawRect(0f, 0f, w, h, fill)
        return true
    }

    /** 层透明度 → alpha 字节（截断，与引擎 withAlpha 同口径）。 */
    fun alphaByte(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255f).toInt()

    /** 压暗色：保留天空底色 RGB，只换 alpha。 */
    fun dimColor(dim: Float): Int =
        SceneSkyMath.argb(alphaByte(dim), DIM_RGB_R, DIM_RGB_G, DIM_RGB_B)

    private fun drawSky(canvas: Canvas, w: Float, h: Float, u: Float) {
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
        fill.shader = linearOf(SceneSkyMath.milkyWayGradient(w, h, sample.light))
        canvas.drawRect(
            w * SceneSkyMath.MILKYWAY_LEFT, h * SceneSkyMath.MILKYWAY_TOP,
            w * SceneSkyMath.MILKYWAY_RIGHT, h * SceneSkyMath.MILKYWAY_BOTTOM, fill,
        )
        fill.shader = null
        canvas.restoreToCount(save)
    }

    private fun paintStars(canvas: Canvas) {
        fill.style = Paint.Style.FILL
        fill.shader = null
        for (d in starDots) {
            fill.color = d.argb
            canvas.drawCircle(d.cx, d.cy, d.radius, fill)
        }
    }

    private fun paintClouds(canvas: Canvas, specs: List<CloudSpec>) {
        for (c in specs) {
            val save = canvas.save()
            canvas.translate(c.cx, c.cy)
            canvas.scale(1f, c.yScale)
            fill.style = Paint.Style.FILL
            fill.shader = radialAtOrigin(c.rx, c.stops)
            canvas.drawCircle(0f, 0f, c.rx, fill)
            fill.shader = null
            canvas.restoreToCount(save)
        }
    }

    private fun drawRidges(canvas: Canvas, w: Float, h: Float, u: Float) {
        val drift = SceneSkyMath.ridgeDrift(h, sample.cameraY)
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

    private fun drawMoon(canvas: Canvas, w: Float, h: Float, pose: ElementPose, densityPx: Float) {
        val radius = SceneSkyMath.moonRadius(w, pose.scale)
        if (radius <= 0f) return
        val cx = pose.x * w
        val cy = pose.y * h

        for (g in SceneSkyMath.moonGlows(cx, cy, radius)) radialOf(canvas, g)

        fill.style = Paint.Style.FILL
        fill.shader = radialOf(SceneSkyMath.moonBody(cx, cy, radius))
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null

        path.reset()
        path.addCircle(cx, cy, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(path)

        for (g in SceneSkyMath.moonSeas(cx, cy, radius)) radialOf(canvas, g)

        SceneSkyMath.moonCraters(moonMarks, cx, cy, radius, craterSpecs)
        for (g in craterSpecs) radialOf(canvas, g)

        radialOf(canvas, SceneSkyMath.moonSpecular(cx, cy, radius))

        fill.shader = linearOf(SceneSkyMath.moonTerminator(cx, cy, radius))
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null

        fill.shader = radialOf(SceneSkyMath.moonNightSide(cx, cy, radius))
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null
        canvas.restoreToCount(save)

        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = SceneSkyMath.MOON_STROKE_DP * densityPx
        stroke.shader = linearOf(SceneSkyMath.moonRimGradient(cx, cy, radius))
        canvas.drawCircle(cx, cy, radius - SceneSkyMath.MOON_STROKE_INSET_DP * densityPx, stroke)
        stroke.shader = null

        path.reset()
        oval.set(
            cx + radius * SceneSkyMath.MOON_VEIL_LEFT, cy + radius * SceneSkyMath.MOON_VEIL_TOP,
            cx + radius * SceneSkyMath.MOON_VEIL_RIGHT, cy + radius * SceneSkyMath.MOON_VEIL_BOTTOM,
        )
        path.addOval(oval, Path.Direction.CW)
        val veil = canvas.save()
        canvas.clipPath(path)
        radialOf(canvas, SceneSkyMath.moonVeil(cx, cy, radius))
        canvas.restoreToCount(veil)
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
        colorsOf(s.stops), positionsOf(s.stops),
        Shader.TileMode.CLAMP,
    )

    private fun radialOf(s: RadialSpec): RadialGradient = RadialGradient(
        s.cx, s.cy, s.radius,
        colorsOf(s.stops), positionsOf(s.stops),
        Shader.TileMode.CLAMP,
    )

    private fun radialAtOrigin(radius: Float, stops: List<GradientStop>): RadialGradient =
        RadialGradient(
            0f, 0f, radius,
            colorsOf(stops), positionsOf(stops),
            Shader.TileMode.CLAMP,
        )

    private fun radialOf(canvas: Canvas, s: RadialSpec) {
        fill.style = Paint.Style.FILL
        fill.shader = radialOf(s)
        canvas.drawCircle(s.cx, s.cy, s.radius, fill)
        fill.shader = null
    }

    private fun colorsOf(stops: List<GradientStop>): IntArray =
        IntArray(stops.size) { stops[it].argb }

    private fun positionsOf(stops: List<GradientStop>): FloatArray =
        FloatArray(stops.size) { stops[it].position }
}