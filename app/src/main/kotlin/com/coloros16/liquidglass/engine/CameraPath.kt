package com.coloros16.liquidglass.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 相机位姿：世界坐标位置 + 欧拉角朝向（度）+ 垂直视场角（度）。
 *
 * 纯 JVM 数据对象，预览与壁纸服务共用。
 */
class CameraPose {
    var x = 0f
    var y = 0f
    var z = 0f
    var yawDeg = 0f
    var pitchDeg = 0f
    var rollDeg = 0f
    var fovDeg = 55f

    fun set(
        x: Float,
        y: Float,
        z: Float,
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float,
        fovDeg: Float,
    ) {
        this.x = x
        this.y = y
        this.z = z
        this.yawDeg = yawDeg
        this.pitchDeg = pitchDeg
        this.rollDeg = rollDeg
        this.fovDeg = fovDeg
    }

    fun copyFrom(other: CameraPose) {
        set(other.x, other.y, other.z, other.yawDeg, other.pitchDeg, other.rollDeg, other.fovDeg)
    }

    override fun toString(): String =
        "CameraPose(x=$x, y=$y, z=$z, yaw=$yawDeg, pitch=$pitchDeg, roll=$rollDeg, fov=$fovDeg)"
}

/** 相机关键帧；[t] 为 0..1 的归一化时间。 */
data class CameraKeyframe(
    val t: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val fovDeg: Float,
)

/**
 * 相机运动曲线：关键帧之间按时间参数做三次 Hermite 插值，切线取相邻帧差，
 * 因此自定义关键帧也能得到连续的速度，不会在帧点出现折角。
 *
 * 采样时间归一化到 0..1 循环；关键帧首尾位姿一致即无缝循环。
 */
class CameraPath(val keyframes: List<CameraKeyframe>) {

    private val keys: List<CameraKeyframe>

    init {
        require(keyframes.isNotEmpty()) { "CameraPath 至少需要一个关键帧" }
        keys = keyframes.sortedBy { it.t }
    }

    val loopable: Boolean get() = keys.size > 1

    fun sample(u: Float, out: CameraPose) {
        if (keys.size == 1) {
            emit(keys[0], out)
            return
        }
        val t = u.coerceIn(0f, 1f)
        var index = 0
        while (index < keys.size - 2 && t > keys[index + 1].t) index++
        val k0 = keys[index]
        val k1 = keys[index + 1]
        val span = k1.t - k0.t
        if (span <= 1e-6f) {
            emit(k1, out)
            return
        }
        val s = ((t - k0.t) / span).coerceIn(0f, 1f)
        val s2 = s * s
        val s3 = s2 * s
        val h00 = 2f * s3 - 3f * s2 + 1f
        val h10 = s3 - 2f * s2 + s
        val h01 = -2f * s3 + 3f * s2
        val h11 = s3 - s2

        out.x = hermite(k0.x, k1.x, tangent(index, span) { it.x }, tangent(index + 1, span) { it.x }, h00, h10, h01, h11, span)
        out.y = hermite(k0.y, k1.y, tangent(index, span) { it.y }, tangent(index + 1, span) { it.y }, h00, h10, h01, h11, span)
        out.z = hermite(k0.z, k1.z, tangent(index, span) { it.z }, tangent(index + 1, span) { it.z }, h00, h10, h01, h11, span)
        out.yawDeg = hermite(k0.yawDeg, k1.yawDeg, tangent(index, span) { it.yawDeg }, tangent(index + 1, span) { it.yawDeg }, h00, h10, h01, h11, span)
        out.pitchDeg = hermite(k0.pitchDeg, k1.pitchDeg, tangent(index, span) { it.pitchDeg }, tangent(index + 1, span) { it.pitchDeg }, h00, h10, h01, h11, span)
        out.rollDeg = hermite(k0.rollDeg, k1.rollDeg, tangent(index, span) { it.rollDeg }, tangent(index + 1, span) { it.rollDeg }, h00, h10, h01, h11, span)
        out.fovDeg = hermite(k0.fovDeg, k1.fovDeg, tangent(index, span) { it.fovDeg }, tangent(index + 1, span) { it.fovDeg }, h00, h10, h01, h11, span)
    }

    private fun tangent(index: Int, segmentSpan: Float, value: (CameraKeyframe) -> Float): Float {
        val current = keys[index]
        val prev = keys.getOrNull(index - 1)
        val next = keys.getOrNull(index + 1)
        if (prev == null && next == null) return 0f
        if (prev == null) {
            val dt = next!!.t - current.t
            return if (dt > 1e-6f) (value(next) - value(current)) / dt else 0f
        }
        if (next == null) {
            val dt = current.t - prev.t
            return if (dt > 1e-6f) (value(current) - value(prev)) / dt else 0f
        }
        val dt = next.t - prev.t
        if (dt <= 1e-6f) return 0f
        return (value(next) - value(prev)) / dt
    }

    private fun hermite(
        p0: Float,
        p1: Float,
        m0: Float,
        m1: Float,
        h00: Float,
        h10: Float,
        h01: Float,
        h11: Float,
        span: Float,
    ): Float = h00 * p0 + h10 * span * m0 + h01 * p1 + h11 * span * m1

    private fun emit(key: CameraKeyframe, out: CameraPose) {
        out.set(key.x, key.y, key.z, key.yawDeg, key.pitchDeg, key.rollDeg, key.fovDeg)
    }
}

/** 内置相机曲线与自定义文本解析。 */
object CameraPaths {

    const val ORBIT = "orbit"
    const val DOLLY = "dolly"
    const val RISE = "rise"
    const val DRIFT = "drift"
    const val STILL = "still"
    const val CUSTOM = "custom"

    val presetIds: List<String> = listOf(ORBIT, DOLLY, RISE, DRIFT, STILL)

    fun byId(id: String?): CameraPath = when (id) {
        DOLLY -> dolly()
        RISE -> rise()
        DRIFT -> drift()
        STILL -> still()
        else -> orbit()
    }

    fun orbit(): CameraPath = path(
        frame(0f, 0f, 0.10f, 0f, 0f, 2f, 0f, 55f),
        frame(0.25f, 0.95f, 0.22f, 0.18f, -7f, 3f, -1.2f, 55.5f),
        frame(0.5f, 0f, 0.30f, 0.42f, 0f, 1f, 0f, 56f),
        frame(0.75f, -0.95f, 0.20f, 0.18f, 7f, 3f, 1.2f, 55.5f),
        frame(1f, 0f, 0.10f, 0f, 0f, 2f, 0f, 55f),
    )

    fun dolly(): CameraPath = path(
        frame(0f, 0f, 0.05f, 1.55f, 0f, 0f, 0f, 54f),
        frame(0.35f, 0.18f, 0.12f, 0.55f, -2.5f, 1.5f, -0.6f, 55f),
        frame(0.65f, -0.14f, 0.18f, 0.10f, 2f, 2.5f, 0.6f, 56f),
        frame(1f, 0f, 0.05f, 1.55f, 0f, 0f, 0f, 54f),
    )

    fun rise(): CameraPath = path(
        frame(0f, -0.30f, -0.55f, 0.85f, 5f, -5f, -1.5f, 56f),
        frame(0.40f, 0.05f, 0.22f, 0.45f, 0f, 0f, 0f, 55f),
        frame(0.70f, 0.28f, 0.55f, 0.05f, -3f, 3f, 1.2f, 54.5f),
        frame(1f, -0.30f, -0.55f, 0.85f, 5f, -5f, -1.5f, 56f),
    )

    fun drift(): CameraPath = path(
        frame(0f, -0.75f, -0.15f, 0.30f, 9f, -2f, -2.5f, 56f),
        frame(0.30f, 0.10f, 0.05f, 0.10f, 2f, 0f, 0f, 55f),
        frame(0.60f, 0.80f, 0.22f, 0.32f, -9f, 1.5f, 2.5f, 56f),
        frame(1f, -0.75f, -0.15f, 0.30f, 9f, -2f, -2.5f, 56f),
    )

    fun still(): CameraPath = path(
        frame(0f, 0f, 0.08f, 0f, 0f, 1f, 0f, 55f),
    )

    fun custom(text: String?): CameraPath? = CameraPathSpec.parse(text)

    fun resolve(id: String?, customText: String?): CameraPath =
        if (id == CUSTOM) custom(customText) ?: orbit() else byId(id)

    private fun path(vararg frames: CameraKeyframe): CameraPath = CameraPath(frames.toList())

    private fun frame(
        t: Float,
        x: Float,
        y: Float,
        z: Float,
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float,
        fovDeg: Float,
    ) = CameraKeyframe(t, x, y, z, yawDeg, pitchDeg, rollDeg, fovDeg)
}

/** 自定义相机曲线的文本格式：每行 `t,x,y,z,yaw,pitch,roll,fov`，`#` 开头为注释。 */
object CameraPathSpec {

    fun format(path: CameraPath): String = buildString {
        appendLine("# t,x,y,z,yaw,pitch,roll,fov  (t 为 0..1 循环进度)")
        path.keyframes.forEach { k ->
            appendLine(
                formatNumber(k.t) + "," + formatNumber(k.x) + "," + formatNumber(k.y) + "," +
                    formatNumber(k.z) + "," + formatNumber(k.yawDeg) + "," +
                    formatNumber(k.pitchDeg) + "," + formatNumber(k.rollDeg) + "," +
                    formatNumber(k.fovDeg),
            )
        }
    }.trimEnd()

    fun parse(text: String?): CameraPath? {
        if (text.isNullOrBlank()) return null
        val frames = ArrayList<CameraKeyframe>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(',', ' ', '\t', ';').filter { it.isNotBlank() }
            if (parts.size != 8) return null
            val values = FloatArray(8)
            for (i in 0 until 8) {
                values[i] = parts[i].toFloatOrNull() ?: return null
                if (!values[i].isFinite()) return null
            }
            frames.add(
                CameraKeyframe(
                    t = values[0].coerceIn(0f, 1f),
                    x = values[1].coerceIn(-20f, 20f),
                    y = values[2].coerceIn(-20f, 20f),
                    z = values[3].coerceIn(-20f, 20f),
                    yawDeg = values[4].coerceIn(-180f, 180f),
                    pitchDeg = values[5].coerceIn(-89f, 89f),
                    rollDeg = values[6].coerceIn(-180f, 180f),
                    fovDeg = values[7].coerceIn(20f, 110f),
                ),
            )
        }
        if (frames.isEmpty()) return null
        frames.sortBy { it.t }
        return CameraPath(frames)
    }

    private fun formatNumber(value: Float): String {
        val rounded = (value * 1000f).toInt() / 1000f
        return if (abs(rounded - rounded.toInt()) < 1e-4f) rounded.toInt().toString() else rounded.toString()
    }
}

/** 相机朝向单位向量计算，渲染端与测试共用。 */
object CameraOrientation {

    fun forward(yawDeg: Float, pitchDeg: Float): FloatArray {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val cp = cos(pitch)
        return floatArrayOf(
            (sin(yaw) * cp).toFloat(),
            sin(pitch).toFloat(),
            (-cos(yaw) * cp).toFloat(),
        )
    }

    fun wrapDegrees(value: Float): Float {
        var v = value % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }

    fun cycleProgress(elapsedMs: Long, cycleMs: Long, speed: Float): Float {
        val safeCycle = cycleMs.coerceAtLeast(1000L)
        val safeSpeed = speed.coerceIn(0.1f, 8f)
        val total = elapsedMs * safeSpeed
        var u = (total % safeCycle) / safeCycle
        if (u < 0f) u += 1f
        return if (u >= 1f) 0f else u
    }
}
