package com.coloros16.liquidglass.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 列主序 4x4 矩阵，直接对接 OpenGL `glUniformMatrix4fv`（transpose=false）。 */
class Mat4 {
    val m = FloatArray(16)

    init {
        identity()
    }

    fun identity() {
        m.fill(0f)
        m[0] = 1f
        m[5] = 1f
        m[10] = 1f
        m[15] = 1f
    }

    fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float) {
        m.fill(0f)
        val fov = fovYDeg.coerceIn(10f, 130f)
        val f = 1f / tan(Math.toRadians(fov.toDouble() * 0.5)).toFloat()
        val a = if (aspect > 0.0001f) aspect else 1f
        m[0] = f / a
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = (2f * far * near) / (near - far)
    }

    fun lookAt(
        eyeX: Float,
        eyeY: Float,
        eyeZ: Float,
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        upX: Float,
        upY: Float,
        upZ: Float,
    ) {
        var fx = centerX - eyeX
        var fy = centerY - eyeY
        var fz = centerZ - eyeZ
        var len = sqrt(fx * fx + fy * fy + fz * fz)
        if (len < 1e-6f) {
            fx = 0f
            fy = 0f
            fz = -1f
            len = 1f
        }
        fx /= len
        fy /= len
        fz /= len

        var sx = fy * upZ - fz * upY
        var sy = fz * upX - fx * upZ
        var sz = fx * upY - fy * upX
        len = sqrt(sx * sx + sy * sy + sz * sz)
        if (len < 1e-6f) {
            sx = 1f
            sy = 0f
            sz = 0f
        } else {
            sx /= len
            sy /= len
            sz /= len
        }

        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        m[0] = sx
        m[1] = ux
        m[2] = -fx
        m[3] = 0f
        m[4] = sy
        m[5] = uy
        m[6] = -fy
        m[7] = 0f
        m[8] = sz
        m[9] = uz
        m[10] = -fz
        m[11] = 0f
        m[12] = -(sx * eyeX + sy * eyeY + sz * eyeZ)
        m[13] = -(ux * eyeX + uy * eyeY + uz * eyeZ)
        m[14] = fx * eyeX + fy * eyeY + fz * eyeZ
        m[15] = 1f
    }

    fun multiply(a: Mat4, b: Mat4) {
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a.m[k * 4 + row] * b.m[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
        out.copyInto(m)
    }

    override fun toString(): String = m.joinToString(",")
}

/** 由相机位姿生成观察矩阵与投影矩阵。 */
object CameraMatrices {

    const val NEAR_PLANE = 0.05f
    const val FAR_PLANE = 120f

    fun buildView(pose: CameraPose, out: Mat4) {
        val yaw = Math.toRadians(pose.yawDeg.toDouble())
        val pitch = Math.toRadians(pose.pitchDeg.clampPitch().toDouble())
        val cp = cos(pitch)
        val fx = (sin(yaw) * cp).toFloat()
        val fy = sin(pitch).toFloat()
        val fz = (-cos(yaw) * cp).toFloat()

        // 基础右向量 = forward × worldUp，随后绕 forward 旋转 roll
        var rx = fy * 0f - fz * 1f
        var ry = fz * 0f - fx * 0f
        var rz = fx * 1f - fy * 0f
        var rlen = sqrt(rx * rx + ry * ry + rz * rz)
        if (rlen < 1e-6f) {
            rx = 1f
            ry = 0f
            rz = 0f
        } else {
            rx /= rlen
            ry /= rlen
            rz /= rlen
        }
        val ux0 = ry * fz - rz * fy
        val uy0 = rz * fx - rx * fz
        val uz0 = rx * fy - ry * fx

        val roll = Math.toRadians(pose.rollDeg.toDouble())
        val cr = cos(roll).toFloat()
        val sr = sin(roll).toFloat()
        val ux = ux0 * cr - rx * sr
        val uy = uy0 * cr - ry * sr
        val uz = uz0 * cr - rz * sr

        out.lookAt(
            pose.x, pose.y, pose.z,
            pose.x + fx, pose.y + fy, pose.z + fz,
            ux, uy, uz,
        )
    }

    fun buildProjection(pose: CameraPose, aspect: Float, out: Mat4) {
        out.perspective(pose.fovDeg, aspect, NEAR_PLANE, FAR_PLANE)
    }

    fun buildViewProjection(pose: CameraPose, aspect: Float, out: Mat4, scratch: Mat4 = Mat4()) {
        buildProjection(pose, aspect, out)
        buildView(pose, scratch)
        out.multiply(out, scratch)
    }

    fun clampPitch(value: Float): Float = value.coerceIn(-89f, 89f)
}

private fun Float.clampPitch(): Float = coerceIn(-89f, 89f)
