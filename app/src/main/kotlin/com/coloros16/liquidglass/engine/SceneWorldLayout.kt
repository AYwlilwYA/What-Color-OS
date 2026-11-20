package com.coloros16.liquidglass.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 三态场景世界几何：按场景相机的世界尺度生成山体、天穹与星场顶点。
 *
 * 纯数值生成不碰图形接口，调用方把数组上传成顶点缓冲；
 * 尺度与 [SnowMountainCamera] 的相机距离同一量级，故不能沿用曲线模式的小世界顶点。
 */
object SceneWorldLayout {

    /** 场景世界尺度：地面基准高度按相机注视点高度量级取。 */
    const val GROUND_Y = -26f

    /** 山体环带：远近半径与顶点高度幅度。 */
    const val RIDGE_FAR_RADIUS = 96f
    const val RIDGE_NEAR_RADIUS = 62f
    const val RIDGE_FAR_AMP = 26f
    const val RIDGE_NEAR_AMP = 34f
    const val RIDGE_SEGMENTS = 240

    /** 星场：数量与壳半径（薄壳，保证从三态任一相机位看都在远面内）。 */
    const val STAR_COUNT = 1400
    const val STAR_RADIUS = 150f

    /** 月球：位置与半径（世界坐标）。 */
    val MOON_POS = floatArrayOf(34f, 46f, -74f)
    const val MOON_RADIUS = 11f

    /**
     * 最远可见距离：星壳半径加上相机最远位置模长。
     *
     * 相机在星壳内部，故最远星点距离不超过两者之和；必须小于远裁剪面。
     */
    fun maxVisibleDistance(): Float {
        var maxCam = 0f
        for (p in listOf(SnowMountainCamera.AOD_POS, SnowMountainCamera.LOCK_POS, SnowMountainCamera.HOME_POS)) {
            val d = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            if (d > maxCam) maxCam = d
        }
        return STAR_RADIUS + maxCam
    }

    /**
     * 山体环带顶点：绕原点一圈的三角带，每段两三角六顶点，每顶点 xyz。
     *
     * 环带闭合，故从任意朝向看都有山脊，不会像平面那样转到侧面就消失。
     */
    fun ridgeRing(radius: Float, amp: Float, phase: Float, segments: Int, out: FloatArray) {
        val need = segments * 6 * 3
        if (out.size < need) return
        var at = 0
        for (i in 0 until segments) {
            val a0 = 2.0 * PI * i / segments
            val a1 = 2.0 * PI * (i + 1) / segments
            val x0 = (cos(a0) * radius).toFloat()
            val z0 = (sin(a0) * radius).toFloat()
            val x1 = (cos(a1) * radius).toFloat()
            val z1 = (sin(a1) * radius).toFloat()
            val h0 = ridgeHeight(i.toFloat() / segments, phase, amp)
            val h1 = ridgeHeight((i + 1).toFloat() / segments, phase, amp)
            at = put(out, at, x0, GROUND_Y, z0)
            at = put(out, at, x1, GROUND_Y, z1)
            at = put(out, at, x0, h0, z0)
            at = put(out, at, x1, GROUND_Y, z1)
            at = put(out, at, x1, h1, z1)
            at = put(out, at, x0, h0, z0)
        }
    }

    /** 环带高度：三正弦叠加，按圈参数取，首尾同相故接缝连续。 */
    fun ridgeHeight(u: Float, phase: Float, amp: Float): Float {
        val t = u * 2f * PI.toFloat()
        val n = 0.55f * sin(t * 3f + phase) +
            0.28f * sin(t * 7f + phase * 1.7f) +
            0.17f * sin(t * 13f + phase * 0.6f)
        return GROUND_Y + amp * (n * 0.5f + 0.5f)
    }

    fun ridgeVertexFloats(segments: Int): Int = segments * 6 * 3

    /**
     * 球面网格顶点：经纬网格，每顶点位置 xyz（半径由调用方给）。
     *
     * 天穹与月球共用此网格，月球另给法线（同值即可，球心在原点）。
     */
    fun domeVertices(latSteps: Int, lonSteps: Int, radius: Float, out: FloatArray) {
        val need = (latSteps + 1) * (lonSteps + 1) * 3
        if (out.size < need) return
        var at = 0
        for (lat in 0..latSteps) {
            val phi = PI * lat / latSteps - PI / 2.0
            val cy = sin(phi).toFloat()
            val r = cos(phi).toFloat()
            for (lon in 0..lonSteps) {
                val theta = 2.0 * PI * lon / lonSteps
                val x = (r * cos(theta)).toFloat()
                val z = (r * sin(theta)).toFloat()
                at = put(out, at, x * radius, cy * radius, z * radius)
            }
        }
    }

    fun domeVertexFloats(latSteps: Int, lonSteps: Int): Int = (latSteps + 1) * (lonSteps + 1) * 3

    /** 球面网格索引：三角列表。 */
    fun domeIndices(latSteps: Int, lonSteps: Int, out: ShortArray) {
        val need = latSteps * lonSteps * 6
        if (out.size < need) return
        var at = 0
        for (lat in 0 until latSteps) {
            for (lon in 0 until lonSteps) {
                val a = (lat * (lonSteps + 1) + lon).toShort()
                val b = (a + lonSteps + 1).toShort()
                out[at++] = a
                out[at++] = b
                out[at++] = (a + 1).toShort()
                out[at++] = b
                out[at++] = (b + 1).toShort()
                out[at++] = (a + 1).toShort()
            }
        }
    }

    fun domeIndexCount(latSteps: Int, lonSteps: Int): Int = latSteps * lonSteps * 6

    /**
     * 星场顶点：位置 xyz + 尺寸 + 颜色 rgb，共七浮点。
     *
     * 只分布在水平线以上，避免地面下方出现星点。
     */
    fun starField(count: Int, seed: Long, out: FloatArray) {
        val need = count * 7
        if (out.size < need) return
        val random = java.util.Random(seed)
        var at = 0
        for (i in 0 until count) {
            val theta = random.nextDouble() * 2.0 * PI
            val up = random.nextDouble()
            val rxy = sqrt(1.0 - up * up)
            val radius = STAR_RADIUS.toDouble()
            val x = (cos(theta) * rxy * radius).toFloat()
            val y = (up * radius).toFloat()
            val z = (sin(theta) * rxy * radius).toFloat()
            val bright = random.nextFloat()
            val size = 1.2f + bright * bright * 3.4f
            val warm = random.nextFloat()
            val cr = if (warm > 0.82f) 1.0f else 0.74f + bright * 0.16f
            val cg = 0.80f + bright * 0.18f
            val cb = if (warm < 0.18f) 1.0f else 0.88f + bright * 0.10f
            out[at++] = x
            out[at++] = y
            out[at++] = z
            out[at++] = size
            out[at++] = cr
            out[at++] = cg
            out[at++] = cb
        }
    }

    fun starVertexFloats(count: Int): Int = count * 7

    /** 星场固定种子：两侧同种子才同分布。 */
    const val STAR_SEED = 0x5CE1EL

    /**
     * 压暗后的颜色：按暗态把颜色往夜色收，暗态 1 时收到夜色。
     *
     * 与厂商把暗态喂材质同一意图，此处按线性插值实现。
     */
    fun dimColor(r: Float, g: Float, b: Float, dark: Float, out: FloatArray) {
        if (out.size < 3) return
        val k = dark.coerceIn(0f, 1f)
        out[0] = r + (NIGHT_R - r) * k
        out[1] = g + (NIGHT_G - g) * k
        out[2] = b + (NIGHT_B - b) * k
    }

    const val NIGHT_R = 0.030f
    const val NIGHT_G = 0.045f
    const val NIGHT_B = 0.085f

    private fun put(out: FloatArray, at: Int, x: Float, y: Float, z: Float): Int {
        out[at] = x
        out[at + 1] = y
        out[at + 2] = z
        return at + 3
    }
}
