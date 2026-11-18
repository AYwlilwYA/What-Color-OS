package com.coloros16.liquidglass.engine

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 背纸开窗姿态表：照片类相框息屏里，纸后照片随遮罩帧独立旋转平移。
 *
 * 每行 = 中心 x / 中心 y / cos / sin，按视频微秒查表取帧。
 */
object FrameRearPoses {

    /** 表尾两帧是起止哨兵，中间 61 帧按 120fps 量化。 */
    const val START_INDEX = 61
    const val END_INDEX = 62
    const val LAST_FRAME_INDEX = 60
    const val FPS = 120f
    const val END_MICROS = 483667f

    private val poses: Array<FloatArray> = arrayOf(
        floatArrayOf(0.538349564f, 0.484494355f, 0.974224024f, 0.120068625f),
        floatArrayOf(0.538248739f, 0.484643321f, 0.974120798f, 0.120903248f),
        floatArrayOf(0.538186242f, 0.484758011f, 0.974100186f, 0.121069202f),
        floatArrayOf(0.538103812f, 0.484472745f, 0.974313707f, 0.119338701f),
        floatArrayOf(0.537874656f, 0.484258269f, 0.974521406f, 0.117630584f),
        floatArrayOf(0.537449375f, 0.484380854f, 0.974672557f, 0.116371524f),
        floatArrayOf(0.537063535f, 0.484407504f, 0.974778333f, 0.115482148f),
        floatArrayOf(0.536426248f, 0.484674779f, 0.974827129f, 0.115069517f),
        floatArrayOf(0.535776397f, 0.48460081f, 0.975159242f, 0.11222022f),
        floatArrayOf(0.534970735f, 0.484404668f, 0.975417675f, 0.109951283f),
        floatArrayOf(0.534099629f, 0.484538942f, 0.97563845f, 0.107974721f),
        floatArrayOf(0.53296885f, 0.484765542f, 0.975945751f, 0.105160904f),
        floatArrayOf(0.531778149f, 0.484564903f, 0.976415268f, 0.100708237f),
        floatArrayOf(0.530304106f, 0.484623265f, 0.976761058f, 0.097297276f),
        floatArrayOf(0.528750471f, 0.484389521f, 0.977249078f, 0.092266809f),
        floatArrayOf(0.527012134f, 0.484663863f, 0.977722995f, 0.087101495f),
        floatArrayOf(0.524879794f, 0.484663863f, 0.978183043f, 0.08177322f),
        floatArrayOf(0.522942131f, 0.484663863f, 0.978718128f, 0.075098278f),
        floatArrayOf(0.520683616f, 0.484663863f, 0.979154325f, 0.069178983f),
        floatArrayOf(0.518525487f, 0.484663863f, 0.979636468f, 0.061978352f),
        floatArrayOf(0.516255296f, 0.484663863f, 0.979919689f, 0.057326497f),
        floatArrayOf(0.513672396f, 0.484663863f, 0.980403079f, 0.048360388f),
        floatArrayOf(0.510937908f, 0.484663863f, 0.980583768f, 0.044546596f),
        floatArrayOf(0.514583333f, 0.484663863f, 0.981595092f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
        floatArrayOf(0.538427516f, 0.484363412f, 0.974261424f, 0.119764776f),
        floatArrayOf(0.5f, 0.484027778f, 1.0f, 0.0f),
    )

    /** 微秒 → 帧下标；<=0 取起始哨兵，>=END_MICROS 取结束哨兵。 */
    fun indexOf(micros: Float): Int = when {
        micros <= 0f -> START_INDEX
        micros >= END_MICROS -> END_INDEX
        else -> min(LAST_FRAME_INDEX, (micros * FPS / 1_000_000f).roundToInt())
    }

    /** 该帧的姿态（中心 x / 中心 y / cos / sin）；下标越界回结束哨兵。 */
    fun poseAt(index: Int): FloatArray =
        poses[index.coerceIn(0, poses.lastIndex)]

    /**
     * 反投影矩阵：把屏幕 UV 变回背纸自身开窗坐标。
     *
     * 平移项 = -R·center，即先撤销该帧的旋转与中心偏移。
     */
    fun matrix(micros: Float): FloatArray {
        val p = poseAt(indexOf(micros))
        val x = p[0]
        val y = p[1]
        val c = p[2]
        val s = p[3]
        return floatArrayOf(
            c, -s, 0f,
            s, c, 0f,
            -c * x - s * y, s * x - c * y, 1f,
        )
    }
}
