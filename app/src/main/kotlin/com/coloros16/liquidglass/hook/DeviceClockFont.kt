package com.coloros16.liquidglass.hook

import android.graphics.Typeface
import com.coloros16.liquidglass.engine.ClockFontCandidate
import com.coloros16.liquidglass.engine.ClockFontCandidates
import com.coloros16.liquidglass.log.Lg
import java.io.File
import java.util.zip.ZipFile

/**
 * 时钟字型运行时从设备取：厂商原件不随包分发。
 *
 * 先试息屏应用 APK 内的时钟专用字型（解出缓存文件再 createFromFile），
 * 再试设备细体字库；全失败返回 null 交调用方退系统族。
 * 结果缓存，失败只降级字型不影响其它功能。
 */
object DeviceClockFont {

    private const val TAG = "LgClockFont"

    /** 解压缓存文件名（落在调用方给的缓存目录）。 */
    private const val CACHE_PREFIX = "lg-clock-"

    @Volatile private var cached: Typeface? = null

    @Volatile private var resolved = false

    /**
     * 取设备时钟字型；[cacheDir] 用于存放从 APK 解出的字体文件。
     *
     * 只解析一次（成功或失败都记账），后续直接返回缓存。
     */
    fun obtain(cacheDir: File?): Typeface? {
        if (resolved) return cached
        synchronized(this) {
            if (resolved) return cached
            cached = resolve(cacheDir)
            resolved = true
            return cached
        }
    }

    private fun resolve(cacheDir: File?): Typeface? {
        for (candidate in ClockFontCandidates.all()) {
            if (!ClockFontCandidates.usable(candidate)) continue
            val face = runCatching { load(candidate, cacheDir) }.getOrNull()
            if (face != null) {
                Lg.i(TAG, "device-font-ready src=${candidate.path}")
                return face
            }
        }
        Lg.i(TAG, "device-font-miss: 设备无可用时钟字型，退系统族")
        return null
    }

    private fun load(candidate: ClockFontCandidate, cacheDir: File?): Typeface? {
        if (!candidate.fromApk) {
            val file = File(candidate.path)
            if (!file.isFile || !file.canRead()) return null
            return Typeface.createFromFile(file)
        }
        val apk = File(candidate.apkPath!!)
        if (!apk.isFile || !apk.canRead()) return null
        val dir = cacheDir ?: return null
        val out = File(dir, CACHE_PREFIX + candidate.path.substringAfterLast('/'))
        if (!out.isFile || out.length() <= 0L) {
            if (!extract(apk, candidate.path, out)) return null
        }
        return Typeface.createFromFile(out)
    }

    /** 从 APK 解出单个 asset；失败清掉半成品避免下次误用。 */
    private fun extract(apk: File, entryName: String, out: File): Boolean {
        return try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(entryName) ?: return false
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            out.isFile && out.length() > 0L
        } catch (t: Throwable) {
            runCatching { out.delete() }
            Lg.i(TAG, "device-font-extract-fail entry=$entryName ${t.javaClass.simpleName}")
            false
        }
    }

    /** 热重载或换包后清账重解析。 */
    fun reset() {
        synchronized(this) {
            cached = null
            resolved = false
        }
    }
}