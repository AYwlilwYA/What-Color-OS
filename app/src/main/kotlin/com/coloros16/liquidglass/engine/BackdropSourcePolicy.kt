package com.coloros16.liquidglass.engine

/**
 * 世界层底图与效果层输入源的选择口径（纯 JVM）。
 *
 * 底图：用户照片 → 系统壁纸（受接管开关门控）→ 模块场景底图。
 * 效果层输入取同一张底图：纹理要压在用户看到的那张图上，换底图就换纹理源。
 */
object BackdropSourcePolicy {

    enum class Kind { STYLE_PHOTO, SYSTEM_WALLPAPER, SCENE_SKY, NONE }

    /** 世界层底图优先级：用户照片最高，其次系统壁纸（开关开才用），最后模块场景底图。 */
    fun backdropKind(hasStylePhoto: Boolean, useSystemWallpaper: Boolean, hasSystemWallpaper: Boolean, hasSceneSky: Boolean): Kind = when {
        hasStylePhoto -> Kind.STYLE_PHOTO
        useSystemWallpaper && hasSystemWallpaper -> Kind.SYSTEM_WALLPAPER
        hasSceneSky -> Kind.SCENE_SKY
        else -> Kind.NONE
    }


    /** 用户照片是否需要解码：URI 非空才解，空串表示未选。 */
    fun needsDecode(uri: String?): Boolean = !uri.isNullOrBlank()

    /** URI scheme 白名单：只解本地内容源，拒网络与未知协议。 */
    fun schemeAllowed(scheme: String?): Boolean =
        scheme != null && scheme.lowercase() in setOf("content", "android.resource", "file")

    /** 采样倍数：从 1 翻倍，直到长边除以倍数不超过上限。 */
    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > maxEdge) sample *= 2
        return sample
    }
}
