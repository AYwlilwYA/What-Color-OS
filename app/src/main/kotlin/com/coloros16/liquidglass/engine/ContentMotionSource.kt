package com.coloros16.liquidglass.engine

/**
 * 内容层动效来源选择：系统分层真值优先，求值器 src/dst 只作兜底。
 *
 * 分层真值给的是「锁屏层/桌面层各自该怎么动」，与「谁是起点谁是终点」无关；
 * 求值器 src/dst 是通用交叠通道，两者语义不同，不能混用。
 */
object ContentMotionSource {

    /** 动效来源。 */
    enum class Source {
        /** 系统分层真值（[SystemSceneMotion.Layers]）。 */
        LAYERS,

        /** 求值器通用交叠通道（无分层真值时）。 */
        PARAMS,
    }

    /**
     * 选来源：有世界层的样式走分层真值，其余走求值器配方。
     *
     * 世界层样式（镜片/照片/手绘/天空）的内容动效由系统分层描述；
     * 非 SCENE 样式的接缝由配方描述（揭开不淡出 / 整屏推移），
     * 分层真值里没有位移通道，走它会丢掉推移。
     *
     * [hasLayers] 由调用方按是否真的求值过分层给，不看数值是否中性
     * （中性也是有效真值，代表该层本次不动）。
     */
    fun choose(hasLayers: Boolean, hasWorld: Boolean): Source =
        if (hasLayers && hasWorld) Source.LAYERS else Source.PARAMS

    /** 兼容旧调用：无世界层信息时按有世界层处理。 */
    fun choose(hasLayers: Boolean): Source = choose(hasLayers, hasWorld = true)

    /**
     * 锁屏↔桌面两条边是否抑制缩放。
     *
     * 这两边系统只做透明度交叠，内容不缩放；分层里的 scale 是给别的边用的。
     */
    fun suppressScale(edge: TransitionEdge?): Boolean =
        edge == TransitionEdge.LOCK_TO_HOME || edge == TransitionEdge.HOME_TO_LOCK

    /** 取该层缩放：抑制边一律原样，其余取分层值。 */
    fun scaleOf(layerScale: Float, edge: TransitionEdge?): Float =
        if (suppressScale(edge)) 1f else layerScale

    /**
     * 把分层真值写进三态姿态包。
     *
     * 息屏层只取透明度（缩放与位移由时钟锚点链单独负责，避免乘两次）；
     * 锁屏/桌面取透明度与缩放，位移一律 0（分层通道没有位移真值）。
     * [contentGate] 为样式层的内容闸（镜片款交叠期压内容），缺省 1。
     */
    fun fill(layers: SystemSceneMotion.Layers, edge: TransitionEdge?, contentGate: Float, out: ContentLayerMapper.Poses) {
        val gate = contentGate.coerceIn(0f, 1f)
        out.aod.set(layers.aodAlpha, 1f, 0f, 0f)
        out.lock.set(layers.lockAlpha * gate, scaleOf(layers.lockScale, edge), 0f, 0f)
        out.home.set(layers.homeAlpha * gate, scaleOf(layers.homeScale, edge), 0f, 0f)
    }
}
