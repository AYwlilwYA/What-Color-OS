package com.coloros16.liquidglass.wallpaper

import android.opengl.GLES20
import com.coloros16.liquidglass.engine.CameraMatrices
import com.coloros16.liquidglass.engine.CameraPath
import com.coloros16.liquidglass.engine.CameraPaths
import com.coloros16.liquidglass.engine.CameraPose
import com.coloros16.liquidglass.engine.Mat4
import com.coloros16.liquidglass.log.Lg
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D 相机壁纸渲染器：EGL 上下文内绘制分层星空、月球与山脊，相机由 [CameraPath] 驱动。
 *
 * 所有 GL 调用必须在持有 EGL 上下文的线程执行。
 */
class Gl3DSceneRenderer {

    private var moonProgram = 0
    private var skyProgram = 0
    private var starProgram = 0
    private var ridgeProgram = 0

    private var moonViewProj = 0
    private var moonModel = 0
    private var moonLight = 0
    private var moonTime = 0
    private var starViewProj = 0
    private var starTime = 0
    private var ridgeViewProj = 0
    private var ridgeHeightBase = 0
    private var ridgeHeightScale = 0
    private var ridgeColorLow = 0
    private var ridgeColorHigh = 0

    private var moonPosAttr = 0
    private var moonNormalAttr = 0
    private var starPosAttr = 0
    private var starSizeAttr = 0
    private var starColorAttr = 0
    private var skyPosAttr = 0
    private var ridgePosAttr = 0

    private var skyBuffer: FloatBuffer? = null
    private var starBuffer: FloatBuffer? = null
    private var sphereVertexBuffer: FloatBuffer? = null
    private var sphereIndexBuffer: ShortBuffer? = null
    private var sphereIndexCount = 0
    private var ridgeFarBuffer: FloatBuffer? = null
    private var ridgeFarCount = 0
    private var ridgeNearBuffer: FloatBuffer? = null
    private var ridgeNearCount = 0

    private var camera: CameraPath = CameraPaths.byId(CameraPaths.STILL)
    private val pose = CameraPose()
    private val viewProj = Mat4()
    private val view = Mat4()
    private val projection = Mat4()
    private val model = Mat4()
    private var aspect = 1f
    private var viewportW = 1
    private var viewportH = 1
    private var initialized = false

    fun setCamera(path: CameraPath) {
        camera = path
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        aspect = viewportW.toFloat() / viewportH.toFloat()
        GLES20.glViewport(0, 0, viewportW, viewportH)
    }

    fun onSurfaceCreated() {
        if (initialized) return
        initPrograms()
        initGeometry()
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        initialized = true
        Lg.i(TAG, "3d renderer ready, sphereIdx=$sphereIndexCount stars=${starBuffer?.limit()?.div(7)}")
    }

    fun draw(cameraProgress: Float, offsetX: Float, parallax: Float, elapsedSec: Float) {
        if (!initialized) return
        camera.sample(cameraProgress, pose)
        val sway = (offsetX.coerceIn(0f, 1f) - 0.5f) * 2f
        pose.x += sway * 0.35f * parallax
        pose.yawDeg += sway * 7f * parallax

        CameraMatrices.buildProjection(pose, aspect, projection)
        CameraMatrices.buildView(pose, view)
        viewProj.multiply(projection, view)

        GLES20.glClearColor(0.008f, 0.012f, 0.028f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        drawSky()
        drawStars(elapsedSec)
        drawMoon(elapsedSec)
        drawRidge(ridgeFarBuffer, ridgeFarCount, -6f, 1f / 4.8f, 0.10f, 0.16f, 0.26f, 0.22f, 0.32f, 0.46f)
        drawRidge(ridgeNearBuffer, ridgeNearCount, -6f, 1f / 4.0f, 0.025f, 0.035f, 0.055f, 0.06f, 0.09f, 0.13f)
    }

    private fun drawSky() {
        val buffer = skyBuffer ?: return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(skyProgram)
        GLES20.glEnableVertexAttribArray(skyPosAttr)
        buffer.position(0)
        GLES20.glVertexAttribPointer(skyPosAttr, 2, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(skyPosAttr)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawStars(elapsedSec: Float) {
        val buffer = starBuffer ?: return
        GLES20.glUseProgram(starProgram)
        GLES20.glUniformMatrix4fv(starViewProj, 1, false, viewProj.m, 0)
        GLES20.glUniform1f(starTime, elapsedSec)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        buffer.position(0)
        val stride = 7 * 4
        GLES20.glEnableVertexAttribArray(starPosAttr)
        GLES20.glVertexAttribPointer(starPosAttr, 3, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(3)
        GLES20.glEnableVertexAttribArray(starSizeAttr)
        GLES20.glVertexAttribPointer(starSizeAttr, 1, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(4)
        GLES20.glEnableVertexAttribArray(starColorAttr)
        GLES20.glVertexAttribPointer(starColorAttr, 3, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, buffer.limit() / 7)
        GLES20.glDisableVertexAttribArray(starPosAttr)
        GLES20.glDisableVertexAttribArray(starSizeAttr)
        GLES20.glDisableVertexAttribArray(starColorAttr)
        GLES20.glDepthMask(true)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun drawMoon(elapsedSec: Float) {
        val vertexBuffer = sphereVertexBuffer ?: return
        val indexBuffer = sphereIndexBuffer ?: return
        val rotation = Math.toRadians((elapsedSec * 2.2).toDouble())
        val c = cos(rotation).toFloat()
        val s = sin(rotation).toFloat()
        val radius = 1.05f
        model.identity()
        model.m[0] = c * radius
        model.m[2] = -s * radius
        model.m[5] = radius
        model.m[8] = s * radius
        model.m[10] = c * radius
        model.m[12] = 0f
        model.m[13] = 0.55f
        model.m[14] = -5.2f

        GLES20.glUseProgram(moonProgram)
        GLES20.glUniformMatrix4fv(moonViewProj, 1, false, viewProj.m, 0)
        GLES20.glUniformMatrix4fv(moonModel, 1, false, model.m, 0)
        GLES20.glUniform3f(moonLight, -0.55f, 0.42f, 0.72f)
        GLES20.glUniform1f(moonTime, elapsedSec)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(moonPosAttr)
        GLES20.glVertexAttribPointer(moonPosAttr, 3, GLES20.GL_FLOAT, false, 24, vertexBuffer)
        vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(moonNormalAttr)
        GLES20.glVertexAttribPointer(moonNormalAttr, 3, GLES20.GL_FLOAT, false, 24, vertexBuffer)
        indexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereIndexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        GLES20.glDisableVertexAttribArray(moonPosAttr)
        GLES20.glDisableVertexAttribArray(moonNormalAttr)
    }

    private fun drawRidge(
        buffer: FloatBuffer?,
        count: Int,
        base: Float,
        scale: Float,
        lowR: Float,
        lowG: Float,
        lowB: Float,
        highR: Float,
        highG: Float,
        highB: Float,
    ) {
        if (buffer == null || count <= 0) return
        GLES20.glUseProgram(ridgeProgram)
        GLES20.glUniformMatrix4fv(ridgeViewProj, 1, false, viewProj.m, 0)
        GLES20.glUniform1f(ridgeHeightBase, base)
        GLES20.glUniform1f(ridgeHeightScale, scale)
        GLES20.glUniform3f(ridgeColorLow, lowR, lowG, lowB)
        GLES20.glUniform3f(ridgeColorHigh, highR, highG, highB)
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(ridgePosAttr)
        GLES20.glVertexAttribPointer(ridgePosAttr, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(ridgePosAttr)
    }

    fun release() {
        if (!initialized) return
        GLES20.glDeleteProgram(moonProgram)
        GLES20.glDeleteProgram(skyProgram)
        GLES20.glDeleteProgram(starProgram)
        GLES20.glDeleteProgram(ridgeProgram)
        initialized = false
        Lg.i(TAG, "3d renderer released")
    }

    private fun initPrograms() {
        moonProgram = linkProgram(MOON_VS, MOON_FS)
        skyProgram = linkProgram(SKY_VS, SKY_FS)
        starProgram = linkProgram(STAR_VS, STAR_FS)
        ridgeProgram = linkProgram(RIDGE_VS, RIDGE_FS)
        moonPosAttr = GLES20.glGetAttribLocation(moonProgram, "aPos")
        moonNormalAttr = GLES20.glGetAttribLocation(moonProgram, "aNormal")
        moonViewProj = GLES20.glGetUniformLocation(moonProgram, "uViewProj")
        moonModel = GLES20.glGetUniformLocation(moonProgram, "uModel")
        moonLight = GLES20.glGetUniformLocation(moonProgram, "uLightDir")
        moonTime = GLES20.glGetUniformLocation(moonProgram, "uTime")

        skyPosAttr = GLES20.glGetAttribLocation(skyProgram, "aPos")

        starPosAttr = GLES20.glGetAttribLocation(starProgram, "aPos")
        starSizeAttr = GLES20.glGetAttribLocation(starProgram, "aSize")
        starColorAttr = GLES20.glGetAttribLocation(starProgram, "aColor")
        starViewProj = GLES20.glGetUniformLocation(starProgram, "uViewProj")
        starTime = GLES20.glGetUniformLocation(starProgram, "uTime")

        ridgePosAttr = GLES20.glGetAttribLocation(ridgeProgram, "aPos")
        ridgeViewProj = GLES20.glGetUniformLocation(ridgeProgram, "uViewProj")
        ridgeHeightBase = GLES20.glGetUniformLocation(ridgeProgram, "uHeightBase")
        ridgeHeightScale = GLES20.glGetUniformLocation(ridgeProgram, "uHeightScale")
        ridgeColorLow = GLES20.glGetUniformLocation(ridgeProgram, "uColorLow")
        ridgeColorHigh = GLES20.glGetUniformLocation(ridgeProgram, "uColorHigh")
    }

    private fun initGeometry() {
        skyBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        buildSphere(40, 80)
        buildStars(1600)
        buildRidges()
    }

    private fun buildSphere(latSteps: Int, lonSteps: Int) {
        val vertexCount = (latSteps + 1) * (lonSteps + 1)
        val vertices = directFloatBuffer(vertexCount * 6)
        for (lat in 0..latSteps) {
            val phi = PI * lat / latSteps - PI / 2.0
            val cy = sin(phi).toFloat()
            val r = cos(phi).toFloat()
            for (lon in 0..lonSteps) {
                val theta = 2.0 * PI * lon / lonSteps
                val x = (r * cos(theta)).toFloat()
                val z = (r * sin(theta)).toFloat()
                vertices.put(x).put(cy).put(z).put(x).put(cy).put(z)
            }
        }
        vertices.position(0)
        sphereVertexBuffer = vertices

        val indices = directShortBuffer(latSteps * lonSteps * 6)
        for (lat in 0 until latSteps) {
            for (lon in 0 until lonSteps) {
                val a = (lat * (lonSteps + 1) + lon).toShort()
                val b = (a + lonSteps + 1).toShort()
                indices.put(a).put(b).put((a + 1).toShort())
                indices.put(b).put((b + 1).toShort()).put((a + 1).toShort())
            }
        }
        indices.position(0)
        sphereIndexBuffer = indices
        sphereIndexCount = indices.capacity()
    }

    private fun buildStars(count: Int) {
        val random = Random(0x5EEDL)
        val buffer = directFloatBuffer(count * 7)
        for (i in 0 until count) {
            val theta = random.nextDouble() * 2.0 * PI
            val z = random.nextDouble() * 2.0 - 1.0
            val rxy = sqrt(1.0 - z * z)
            val radius = 10.0 + random.nextDouble() * 10.0
            val x = (cos(theta) * rxy * radius).toFloat()
            val y = (z * radius).toFloat()
            val zz = (sin(theta) * rxy * radius).toFloat()
            val bright = random.nextFloat()
            val size = 1.0f + bright * bright * 3.6f
            val warm = random.nextFloat()
            val cr = if (warm > 0.82f) 1.0f else 0.72f + bright * 0.18f
            val cg = 0.78f + bright * 0.20f
            val cb = if (warm < 0.18f) 1.0f else 0.86f + bright * 0.12f
            buffer.put(x).put(y).put(zz).put(size).put(cr).put(cg).put(cb)
        }
        buffer.position(0)
        starBuffer = buffer
    }

    private fun buildRidges() {
        ridgeFarBuffer = buildRidgeBuffer(z = -3.2f, base = -1.75f, amp = 0.62f, phase = 1.7f, segments = 220)
        ridgeFarBuffer?.let { ridgeFarCount = it.capacity() / 3 }
        ridgeNearBuffer = buildRidgeBuffer(z = -1.45f, base = -2.25f, amp = 0.78f, phase = 4.3f, segments = 200)
        ridgeNearBuffer?.let { ridgeNearCount = it.capacity() / 3 }
    }

    private fun buildRidgeBuffer(z: Float, base: Float, amp: Float, phase: Float, segments: Int): FloatBuffer {
        val buffer = directFloatBuffer(segments * 2 * 3 * 3)
        for (i in 0 until segments) {
            val x0 = -14f + 28f * i / segments
            val x1 = -14f + 28f * (i + 1) / segments
            val h0 = ridgeHeight(x0, phase, amp, base)
            val h1 = ridgeHeight(x1, phase, amp, base)
            buffer.put(x0).put(-6f).put(z)
            buffer.put(x1).put(-6f).put(z)
            buffer.put(x0).put(h0).put(z)
            buffer.put(x1).put(-6f).put(z)
            buffer.put(x1).put(h1).put(z)
            buffer.put(x0).put(h0).put(z)
        }
        buffer.position(0)
        return buffer
    }

    private fun ridgeHeight(x: Float, phase: Float, amp: Float, base: Float): Float {
        val n = 0.55f * sin(x * 0.55f + phase) +
            0.28f * sin(x * 1.31f + phase * 1.7f) +
            0.17f * sin(x * 2.70f + phase * 0.6f)
        return base + amp * (n * 0.5f + 0.5f)
    }

    private fun linkProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            Lg.e(TAG, "link failed: $log")
            throw IllegalStateException("link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            Lg.e(TAG, "compile failed type=$type: $log")
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("compile failed: $log")
        }
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(values)
        buffer.position(0)
        return buffer
    }

    private fun directFloatBuffer(capacity: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun directShortBuffer(capacity: Int): ShortBuffer =
        ByteBuffer.allocateDirect(capacity * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    companion object {
        private const val TAG = "lg-wallpaper"

        private const val SKY_VS = """
            attribute vec2 aPos;
            varying vec2 vPos;
            void main() {
                vPos = aPos;
                gl_Position = vec4(aPos, 0.999, 1.0);
            }
        """

        private const val SKY_FS = """
            precision mediump float;
            varying vec2 vPos;
            void main() {
                float h = vPos.y * 0.5 + 0.5;
                vec3 bottom = vec3(0.045, 0.075, 0.135);
                vec3 mid = vec3(0.020, 0.048, 0.105);
                vec3 top = vec3(0.006, 0.010, 0.030);
                vec3 col = mix(bottom, mid, smoothstep(0.0, 0.42, h));
                col = mix(col, top, smoothstep(0.42, 1.0, h));
                float glow = exp(-pow((h - 0.24) * 6.5, 2.0)) * 0.10;
                col += vec3(0.24, 0.34, 0.52) * glow;
                gl_FragColor = vec4(col, 1.0);
            }
        """

        private const val STAR_VS = """
            attribute vec3 aPos;
            attribute float aSize;
            attribute vec3 aColor;
            uniform mat4 uViewProj;
            uniform float uTime;
            varying vec3 vColor;
            void main() {
                gl_Position = uViewProj * vec4(aPos, 1.0);
                gl_PointSize = aSize * (0.85 + 0.15 * sin(uTime * 1.9 + aPos.x * 3.7 + aPos.y * 1.9));
                vColor = aColor;
            }
        """

        private const val STAR_FS = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                vec2 d = gl_PointCoord - vec2(0.5);
                float a = smoothstep(0.5, 0.05, length(d));
                if (a <= 0.01) discard;
                gl_FragColor = vec4(vColor * a, a);
            }
        """

        private const val MOON_VS = """
            attribute vec3 aPos;
            attribute vec3 aNormal;
            uniform mat4 uViewProj;
            uniform mat4 uModel;
            varying vec3 vNormal;
            varying vec3 vLocal;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                vNormal = normalize(mat3(uModel) * aNormal);
                vLocal = aPos;
                gl_Position = uViewProj * world;
            }
        """

        private const val MOON_FS = """
            precision highp float;
            varying vec3 vNormal;
            varying vec3 vLocal;
            uniform vec3 uLightDir;
            uniform float uTime;

            float hash(vec3 p) {
                p = fract(p * 0.3183099 + vec3(0.11, 0.23, 0.37));
                p *= 17.0;
                return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
            }

            float noise(vec3 p) {
                vec3 i = floor(p);
                vec3 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float n000 = hash(i + vec3(0.0, 0.0, 0.0));
                float n100 = hash(i + vec3(1.0, 0.0, 0.0));
                float n010 = hash(i + vec3(0.0, 1.0, 0.0));
                float n110 = hash(i + vec3(1.0, 1.0, 0.0));
                float n001 = hash(i + vec3(0.0, 0.0, 1.0));
                float n101 = hash(i + vec3(1.0, 0.0, 1.0));
                float n011 = hash(i + vec3(0.0, 1.0, 1.0));
                float n111 = hash(i + vec3(1.0, 1.0, 1.0));
                float nx00 = mix(n000, n100, f.x);
                float nx10 = mix(n010, n110, f.x);
                float nx01 = mix(n001, n101, f.x);
                float nx11 = mix(n011, n111, f.x);
                float nxy0 = mix(nx00, nx10, f.y);
                float nxy1 = mix(nx01, nx11, f.y);
                return mix(nxy0, nxy1, f.z);
            }

            float fbm(vec3 p) {
                float v = 0.0;
                float a = 0.5;
                for (int i = 0; i < 4; i++) {
                    v += a * noise(p);
                    p *= 2.03;
                    a *= 0.5;
                }
                return v;
            }

            void main() {
                vec3 n = normalize(vNormal);
                vec3 l = normalize(uLightDir);
                float ndl = max(dot(n, l), 0.0);

                float maria = smoothstep(0.40, 0.62, fbm(vLocal * 1.9 + vec3(3.1, 0.7, 1.4)));
                float fine = fbm(vLocal * 15.0);
                vec3 albedo = mix(vec3(0.40, 0.39, 0.37), vec3(0.78, 0.76, 0.71), fine);
                albedo *= mix(1.0, 0.58, maria * 0.85);
                albedo *= mix(0.82, 1.0, smoothstep(0.30, 0.72, fbm(vLocal * 5.5)));

                vec3 col = albedo * (0.055 + 0.945 * ndl);
                col += vec3(0.03, 0.045, 0.075) * (1.0 - ndl) * 0.9;
                col += vec3(0.20, 0.24, 0.32) * pow(1.0 - max(dot(n, vec3(0.0, 0.0, 1.0)), 0.0), 2.2) * 0.22;
                gl_FragColor = vec4(col, 1.0);
            }
        """

        private const val RIDGE_VS = """
            attribute vec3 aPos;
            uniform mat4 uViewProj;
            varying float vHeight;
            varying float vDepth;
            void main() {
                vHeight = aPos.y;
                vec4 clip = uViewProj * vec4(aPos, 1.0);
                vDepth = clip.w;
                gl_Position = clip;
            }
        """

        private const val RIDGE_FS = """
            precision mediump float;
            varying float vHeight;
            varying float vDepth;
            uniform float uHeightBase;
            uniform float uHeightScale;
            uniform vec3 uColorLow;
            uniform vec3 uColorHigh;
            void main() {
                float h = clamp((vHeight - uHeightBase) * uHeightScale, 0.0, 1.0);
                vec3 col = mix(uColorLow, uColorHigh, h * h);
                float fog = clamp(1.0 - exp(-vDepth * 0.075), 0.0, 0.72);
                col = mix(col, vec3(0.075, 0.105, 0.175), fog);
                gl_FragColor = vec4(col, 1.0);
            }
        """
    }
}
