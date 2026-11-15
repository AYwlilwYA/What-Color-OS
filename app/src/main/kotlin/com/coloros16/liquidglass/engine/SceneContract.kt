package com.coloros16.liquidglass.engine

/** 元素位姿：场景内单个元素的采样结果。 */
class ElementPose(
    var id: String = "",
    var x: Float = 0f,
    var y: Float = 0f,
    var scale: Float = 1f,
    var alpha: Float = 1f,
    var rotationDeg: Float = 0f,
)

/** 单个元素的通道轨道；缺省通道取默认值。 */
class ElementTrack(
    val id: String,
    val x: KeyframeTrack? = null,
    val y: KeyframeTrack? = null,
    val scale: KeyframeTrack? = null,
    val alpha: KeyframeTrack? = null,
    val rotation: KeyframeTrack? = null,
) {

    fun sample(t: Float, out: ElementPose) {
        out.id = id
        out.x = x?.sample(t) ?: 0f
        out.y = y?.sample(t) ?: 0f
        out.scale = scale?.sample(t) ?: 1f
        out.alpha = alpha?.sample(t) ?: 1f
        out.rotationDeg = rotation?.sample(t) ?: 0f
    }
}

/** 场景契约：相机 / 明暗 / 元素时间线；三态是同一契约的不同 t 采样。 */
class SceneContract(
    val id: String,
    val cameraX: KeyframeTrack? = null,
    val cameraY: KeyframeTrack? = null,
    val cameraScale: KeyframeTrack? = null,
    val cameraRotation: KeyframeTrack? = null,
    val light: KeyframeTrack? = null,
    val elements: List<ElementTrack> = emptyList(),
)

/** 场景采样结果（复用实例，逐帧不分配）。相机 x/y 为 0..1 归一化坐标。 */
class SceneSample(
    val elements: MutableList<ElementPose> = ArrayList(),
) {
    var contractId: String? = null
    var cameraX: Float = 0.5f
    var cameraY: Float = 0.5f
    var cameraScale: Float = 1f
    var cameraRotationDeg: Float = 0f
    var light: Float = 1f

    fun reset() {
        contractId = null
        cameraX = 0.5f
        cameraY = 0.5f
        cameraScale = 1f
        cameraRotationDeg = 0f
        light = 1f
        elements.clear()
    }

    fun sample(contract: SceneContract, t: Float) {
        contractId = contract.id
        cameraX = contract.cameraX?.sample(t) ?: 0.5f
        cameraY = contract.cameraY?.sample(t) ?: 0.5f
        cameraScale = contract.cameraScale?.sample(t) ?: 1f
        cameraRotationDeg = contract.cameraRotation?.sample(t) ?: 0f
        light = contract.light?.sample(t) ?: 1f
        while (elements.size > contract.elements.size) {
            elements.removeAt(elements.size - 1)
        }
        for (i in contract.elements.indices) {
            val pose = if (i < elements.size) elements[i] else ElementPose().also { elements.add(it) }
            contract.elements[i].sample(t, pose)
        }
    }
}
