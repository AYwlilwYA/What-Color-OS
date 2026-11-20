package com.coloros16.liquidglass.engine

/**
 * 摄像头挖孔与岛几何（纯 JVM）：岛把挖孔包进去，内容分居挖孔两侧（居中挖孔）
 * 或排挖孔一侧（左/右挖孔）；水滴嵌顶缘凹口（灵动额头）；大卡顶缘包挖孔、内容排下方。
 * 真值：挖孔两侧间隙 gap_hole=8dp（参考默认），文字不压挖孔。
 */
object SeedlingCutoutGeometry {

    /** 挖孔在屏幕上的位置分类。 */
    enum class CutoutKind { NONE, CENTER, LEFT, RIGHT, WATERDROP }

    /** 挖孔矩形（屏幕 px）+ 屏宽。 */
    data class Hole(val left: Float, val top: Float, val right: Float, val bottom: Float, val screenW: Float) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * 位置分类：中心在屏中 ±10% 内=CENTER；贴顶且高<宽（横长条）=WATERDROP；
     * 中心在左半=LEFT，右半=RIGHT。
     */
    fun classify(hole: Hole): CutoutKind {
        val cx = hole.centerX
        val mid = hole.screenW / 2f
        if (kotlin.math.abs(cx - mid) <= hole.screenW * 0.1f) {
            return if (hole.top <= 1f && hole.height < hole.width) CutoutKind.WATERDROP else CutoutKind.CENTER
        }
        return if (cx < mid) CutoutKind.LEFT else CutoutKind.RIGHT
    }

    /**
     * 胶囊最小宽：挖孔宽 + 两侧间隙 + 左半（内边距+符号+间隙）+ 右半（间隙+波形+内边距），
     * 与内容宽取大——保证包住挖孔且两侧各有内容位。
     */
    fun capsuleWidth(contentWidth: Float, hole: Hole?, gapPx: Float, padPx: Float, symbolR: Float = 0f, waveW: Float = 0f): Float {
        if (hole == null) return contentWidth
        return when (classify(hole)) {
            CutoutKind.CENTER, CutoutKind.WATERDROP -> {
                // 分居两侧：左右半各需 内边距+元素+间隙+最小文字宽24+间隙，绕孔对称
                val half = padPx + maxOf(symbolR * 2f, waveW) + gapPx + 24f + gapPx
                val aroundHole = hole.width + gapPx * 2f + half * 2f
                maxOf(contentWidth, aroundHole)
            }
            else -> {
                // 单侧布局：孔+两侧间隙+符号+文字+波形+内边距全在一侧（文字区再留一份最小宽）
                val oneSide = hole.width + gapPx * 2f + padPx + symbolR * 2f + gapPx + 24f + gapPx + waveW + padPx + 24f
                maxOf(contentWidth, oneSide)
            }
        }
    }

    /** 胶囊左缘（屏幕 px）：居中挖孔=屏幕居中；左挖孔=挖孔左缘-间隙-内边距；右挖孔镜像。 */
    fun capsuleLeft(hole: Hole?, screenW: Float, capsuleW: Float, gapPx: Float, padPx: Float): Float {
        if (hole == null) return screenW / 2f - capsuleW / 2f
        return when (classify(hole)) {
            CutoutKind.LEFT -> hole.left - gapPx - padPx
            CutoutKind.RIGHT -> hole.right + gapPx + padPx - capsuleW
            else -> screenW / 2f - capsuleW / 2f
        }
    }

    /** 胶囊顶缘：对齐挖孔垂直中心（挖孔嵌在胶囊高度中部）；水滴=顶缘贴屏幕上缘。 */
    fun capsuleTop(hole: Hole?, capsuleH: Float): Float {
        if (hole == null) return 0f
        return if (classify(hole) == CutoutKind.WATERDROP) 0f
        else (hole.centerY - capsuleH / 2f).coerceAtLeast(0f)
    }

    /**
     * 胶囊内容布局：返回 [符号中心x, 文字区左, 文字区右, 波形中心x]（屏幕 px，-1=不画）。
     * 居中挖孔：符号在左半、文字在右半（挖孔两侧）；左挖孔：内容全排右侧；右挖孔镜像；
     * 水滴/无挖孔：符号左端、文字居中、波形右端（原布局）。
     */
    fun capsuleContent(
        capsuleLeft: Float, capsuleW: Float, hole: Hole?, gapPx: Float, padPx: Float,
        symbolR: Float, waveW: Float,
    ): FloatArray {
        val l = capsuleLeft
        val r = capsuleLeft + capsuleW
        val cyNone = floatArrayOf(l + padPx + symbolR, l + padPx + symbolR * 2f + gapPx, r - padPx - waveW - gapPx, r - padPx - waveW / 2f)
        if (hole == null) return cyNone
        val hl = hole.left - gapPx
        val hr = hole.right + gapPx
        return when (classify(hole)) {
            CutoutKind.CENTER -> {
                val tl = hr + gapPx
                val tr = r - padPx - waveW - gapPx
                if (tr - tl < 24f) {
                    // 右半放不下文字：降级单侧布局（内容全排孔右，胶囊已保证包孔）
                    floatArrayOf(hr + gapPx + symbolR, hr + gapPx + symbolR * 2f + gapPx, r - padPx - waveW - gapPx, r - padPx - waveW / 2f)
                } else {
                    floatArrayOf(
                        (l + padPx + hl) / 2f,                    // 符号中心=左半中心
                        tl,                                       // 文字区左=挖孔右+间隙
                        tr,                                       // 文字区右=波形左
                        r - padPx - waveW / 2f,
                    )
                }
            }
            CutoutKind.LEFT -> floatArrayOf(
                hr + gapPx + symbolR,
                hr + gapPx + symbolR * 2f + gapPx,
                r - padPx - waveW - gapPx,
                r - padPx - waveW / 2f,
            )
            CutoutKind.RIGHT -> floatArrayOf(
                l + padPx + symbolR,
                l + padPx + symbolR * 2f + gapPx,
                hl - gapPx - waveW - gapPx,
                hl - gapPx - waveW / 2f,
            )
            CutoutKind.WATERDROP, CutoutKind.NONE -> cyNone
        }
    }

    /** 大卡顶缘：默认在挖孔下方（card_below_camera 真值）；水滴=贴顶（凹口包水滴）。 */
    fun cardTop(hole: Hole?, gapPx: Float): Float {
        if (hole == null) return 0f
        return if (classify(hole) == CutoutKind.WATERDROP) 0f else hole.bottom + gapPx
    }

    /** 内容是否与挖孔相交（文字不压挖孔校验）。 */
    fun overlapsHole(contentLeft: Float, contentRight: Float, hole: Hole?): Boolean {
        if (hole == null) return false
        return contentLeft < hole.right && contentRight > hole.left
    }

    /**
     * 水滴灵动额头凹口半宽：孔半宽 + 两侧间隙（胶囊/大卡顶缘 Path 在此区间下凹包水滴）。
     * 非水滴返回 0（不凹）。
     */
    fun notchHalfWidth(hole: Hole?, gapPx: Float): Float {
        if (hole == null || classify(hole) != CutoutKind.WATERDROP) return 0f
        return hole.width / 2f + gapPx
    }

    /**
     * 灵动额头凹口几何（纯 JVM）：返回 [凹口左, 凹口右, 槽底y]，非水滴返回 null。
     * 槽底=孔底+间隙；凹口中心钳进卡矩形（防孔偏心时凹口出界）。
     * Path 构造归渲染侧（engine 零 android.* 铁律）。
     */
    fun notchGeometry(
        left: Float, top: Float, right: Float,
        cornerR: Float, hole: Hole?, gapPx: Float,
    ): FloatArray? {
        val hw = notchHalfWidth(hole, gapPx)
        if (hw <= 0f) return null
        val cx = hole!!.centerX.coerceIn(left + hw + cornerR, right - hw - cornerR)
        val depth = (hole.bottom - top + gapPx).coerceAtLeast(cornerR)
        return floatArrayOf(cx - hw, cx + hw, top + depth)
    }
}
