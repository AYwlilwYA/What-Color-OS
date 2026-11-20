package com.coloros16.liquidglass.engine

/**
 * 内容层裁进样式特效窗口：镜片圆窗与照片方窗，窗口外不出现锁屏/桌面内容。
 *
 * 与两侧 shader 的窗口判定同一套坐标口径，纯 JVM 不碰 Canvas。
 */
object ContentWindow {

    /** 窗口形状。 */
    enum class Shape { NONE, CIRCLE, SQUARE }

    /** 窗口几何：中心为屏幕像素，半径/半边 likewise；NONE 时不裁。 */
    class Window {
        var shape = Shape.NONE
        var centerX = 0f
        var centerY = 0f

        /** 圆窗半径（像素）。 */
        var radius = 0f

        /** 方窗边长（像素）。 */
        var side = 0f

        /** 方窗旋转角（度），与照片特效同角。 */
        var angleDeg = 0f

        fun reset() {
            shape = Shape.NONE
            centerX = 0f
            centerY = 0f
            radius = 0f
            side = 0f
            angleDeg = 0f
        }
    }

    /**
     * 镜片圆窗：shader 在「宽归一化 + y 乘 aspect」空间比距离，
     * 故屏幕空间是正圆，半径 = scale × 屏宽。
     */
    fun lens(pose: SailLensMotion.Pose, w: Float, h: Float, out: Window) {
        out.reset()
        if (w <= 0f || h <= 0f) return
        out.shape = Shape.CIRCLE
        out.centerX = pose.centerX * w
        out.centerY = pose.centerY * h
        out.radius = pose.scale * w
    }

    /**
     * 照片方窗：与 `PhotoSceneRenderer` 的 uCenter/uSide/uAngle 逐字同口径。
     *
     * `values` 取 PhotoStyle 的 SLOT_X/Y/SCALE/ROTATION。
     */
    fun photo(values: FloatArray, w: Float, h: Float, out: Window) {
        out.reset()
        if (w <= 0f || h <= 0f) return
        val side = minOf(w, h) * values[PhotoStyle.SLOT_SCALE]
        if (side <= 1f) return
        out.shape = Shape.SQUARE
        out.centerX = w * (values[PhotoStyle.SLOT_X] + 0.5f)
        out.centerY = h * (0.5f - values[PhotoStyle.SLOT_Y])
        out.side = side
        out.angleDeg = values[PhotoStyle.SLOT_ROTATION]
    }

    /**
     * 该屏幕点是否在窗口内；NONE 一律 true（不裁的样式保持原行为）。
     *
     * 圆：与 shader 同口径把 y 乘 aspect 后比距离。
     * 方：先反旋再归一化到 [0,1]，与 shader 的 spin(coord-center,-angle)/side+0.5 一致。
     */
    fun contains(win: Window, x: Float, y: Float, w: Float, h: Float): Boolean {
        when (win.shape) {
            Shape.NONE -> return true
            Shape.CIRCLE -> {
                if (w <= 0f || h <= 0f || win.radius <= 0f) return false
                val aspect = h / w
                val dx = x / w - win.centerX / w
                val dy = (y / h - win.centerY / h) * aspect
                return dx * dx + dy * dy <= win.radius / w * (win.radius / w)
            }

            Shape.SQUARE -> {
                if (win.side <= 1f) return false
                val dx = x - win.centerX
                val dy = y - win.centerY
                val rad = Math.toRadians(-win.angleDeg.toDouble())
                val c = kotlin.math.cos(rad).toFloat()
                val s = kotlin.math.sin(rad).toFloat()
                val lx = (c * dx - s * dy) / win.side + 0.5f
                val ly = (s * dx + c * dy) / win.side + 0.5f
                return lx in 0f..1f && ly in 0f..1f
            }
        }
    }

    /**
     * 圆是否已完全盖住屏幕四角：盖住时圆外无像素，黑幕可整段跳过。
     *
     * 半径按屏宽口径（与 [lens] 一致），四角全在圆内才算覆盖。
     */
    fun coversScreen(radius: Float, cx: Float, cy: Float, w: Float, h: Float): Boolean {
        if (radius <= 0f || w <= 0f || h <= 0f) return false
        val r2 = radius * radius
        for (px in floatArrayOf(0f, w)) {
            for (py in floatArrayOf(0f, h)) {
                val dx = px - cx
                val dy = py - cy
                if (dx * dx + dy * dy > r2) return false
            }
        }
        return true
    }

    /**
     * 内容层整体是否还有可见部分：窗口太小就整层不画，省一次离屏。
     *
     * 判据取「窗口与屏幕矩形是否相交」，不是「窗口是否覆盖全屏」。
     */
    fun intersectsScreen(win: Window, w: Float, h: Float): Boolean {
        when (win.shape) {
            Shape.NONE -> return true
            Shape.CIRCLE -> {
                if (win.radius <= 0f) return false
                val nx = win.centerX.coerceIn(0f, w)
                val ny = win.centerY.coerceIn(0f, h)
                val dx = win.centerX - nx
                val dy = win.centerY - ny
                return dx * dx + dy * dy <= win.radius * win.radius
            }

            Shape.SQUARE -> {
                if (win.side <= 1f) return false
                val half = win.side * 0.5f
                val rad = Math.toRadians(win.angleDeg.toDouble())
                val c = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
                val s = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
                val ex = half * (c + s)
                val ey = half * (c + s)
                return win.centerX + ex >= 0f && win.centerX - ex <= w &&
                    win.centerY + ey >= 0f && win.centerY - ey <= h
            }
        }
    }
}