package com.coloros16.liquidglass.engine

/** 一处可取时钟字体的位置：设备 APK 内 asset，或设备字体文件。 */
data class ClockFontCandidate(
    /** 设备 APK 绝对路径；为 null 表示 [path] 是字体文件本身。 */
    val apkPath: String?,
    /** APK 内 asset 相对路径，或字体文件绝对路径。 */
    val path: String,
) {
    val fromApk: Boolean get() = apkPath != null
}

/**
 * 时钟字体的设备候选表：厂商原件不再随包分发，一律运行时从设备取。
 *
 * 顺序即优先级：先取息屏应用自带的时钟专用字型（与系统息屏同一份笔画），
 * 再退设备细体字库，最后由调用方退系统 sans-serif-thin。
 */
object ClockFontCandidates {

    /** 息屏应用包名候选（按代次排列）。 */
    val AOD_PACKAGES: List<String> = listOf("com.oplus.aod")

    /** 息屏应用安装目录候选；[0] 为包名占位。 */
    private val APK_DIRS: List<String> = listOf(
        "/product/app/Aod/Aod.apk",
        "/system/app/Aod/Aod.apk",
        "/product/priv-app/Aod/Aod.apk",
        "/system/priv-app/Aod/Aod.apk",
    )

    /** 时钟专用字型在息屏应用内的 asset 名：等宽档在前，比例档在后。 */
    val CLOCK_ASSETS: List<String> = listOf(
        "assets/OPlusSans2.0_ClockGX.ttf",
        "assets/OPlusSans2.0_ClockPGX.ttf",
    )

    /** 设备细体字库候选（绝对路径）。 */
    val DEVICE_THIN_FILES: List<String> = listOf(
        "/system/fonts/OplusOSUI-XThin.ttf",
        "/system/fonts/Roboto-Thin.ttf",
    )

    /** 全部候选，按优先级；调用方逐个试到成功为止。 */
    fun all(): List<ClockFontCandidate> {
        val out = ArrayList<ClockFontCandidate>()
        for (dir in APK_DIRS) {
            for (asset in CLOCK_ASSETS) out.add(ClockFontCandidate(dir, asset))
        }
        for (file in DEVICE_THIN_FILES) out.add(ClockFontCandidate(null, file))
        return out
    }

    /** 该候选是否值得试：路径非空且不是目录形态。 */
    fun usable(candidate: ClockFontCandidate): Boolean {
        if (candidate.path.isBlank()) return false
        if (candidate.path.endsWith("/")) return false
        val apk = candidate.apkPath
        return apk == null || apk.isNotBlank()
    }
}