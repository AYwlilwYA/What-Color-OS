package com.coloros16.liquidglass.hook

import com.coloros16.liquidglass.engine.HandPaintFormat
import com.coloros16.liquidglass.engine.HandPaintSilk
import com.coloros16.liquidglass.engine.HandPaintSource
import com.coloros16.liquidglass.log.Lg
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * 手绘轨迹运行时从设备取：解息屏应用内的手绘包，再解内层某款的轨迹文本。
 *
 * 厂商原件不随包分发。解析在调用方的后台线程做，本件只做 IO 与解析、结果缓存；
 * 取不到只降级手绘功能，不影响其它样式。
 */
object DeviceHandPaint {

    private const val TAG = "LgHandPaint"

    /** 外层包解出后的缓存文件名前缀。 */
    private const val CACHE_NAME = "lg-handpaint.zip"

    @Volatile private var cachedStyle = -1

    @Volatile private var cached: List<HandPaintSilk>? = null

    @Volatile private var packPath: String? = null

    /**
     * 取某款的全量丝线；[cacheDir] 用于存放解出的外层包。
     *
     * 同款命中缓存直接返回；解析失败返回 null 且不记账，下次可重试。
     */
    fun obtain(style: Int, cacheDir: File?): List<HandPaintSilk>? {
        val id = HandPaintSource.normalizeStyle(style)
        if (id == cachedStyle) return cached
        synchronized(this) {
            if (id == cachedStyle) return cached
            val silks = runCatching { load(id, cacheDir) }.onFailure {
                Lg.i(TAG, "handpaint-load-fail style=$id ${it.javaClass.simpleName}")
            }.getOrNull()
            if (silks.isNullOrEmpty()) {
                Lg.i(TAG, "handpaint-miss style=$id 设备无可用手绘轨迹")
                return null
            }
            cachedStyle = id
            cached = silks
            Lg.i(
                TAG,
                "handpaint-ready style=$id silks=${silks.size} points=${silks.sumOf { it.points.size }} " +
                    "center=${silks[0].centerX} copies=${silks.sumOf { com.coloros16.liquidglass.engine.HandPaintMotion.copiesOf(it) }}",
            )
            return silks
        }
    }

    private fun load(style: Int, cacheDir: File?): List<HandPaintSilk>? {
        val pack = ensurePack(cacheDir) ?: return null
        val text = readEntry(pack, HandPaintSource.jsonEntry(style)) ?: return null
        val doc = HandPaintFormat.parse(text)
        return doc.silks.takeIf { it.isNotEmpty() }
    }

    /** 外层包按需解出：已有缓存文件直接用，否则从设备 APK 解一份。 */
    private fun ensurePack(cacheDir: File?): File? {
        packPath?.let { path ->
            val f = File(path)
            if (f.isFile && f.length() > 0L) return f
        }
        val dir = cacheDir ?: return null
        val out = File(dir, CACHE_NAME)
        if (!out.isFile || out.length() <= 0L) {
            var ok = false
            for (apkPath in HandPaintSource.APK_PATHS) {
                val apk = File(apkPath)
                if (!apk.isFile || !apk.canRead()) continue
                if (extract(apk, HandPaintSource.OUTER_ENTRY, out)) {
                    Lg.i(TAG, "handpaint-pack src=$apkPath bytes=${out.length()}")
                    ok = true
                    break
                }
            }
            if (!ok) return null
        }
        packPath = out.absolutePath
        return out
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
            Lg.i(TAG, "handpaint-extract-fail entry=$entryName ${t.javaClass.simpleName}")
            false
        }
    }

    /** 读内层 zip 的一个文本条目。 */
    private fun readEntry(pack: File, entryName: String): String? {
        return try {
            ZipFile(pack).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                zip.getInputStream(entry).use { input ->
                    val buf = ByteArrayOutputStream()
                    input.copyTo(buf)
                    buf.toString(Charsets.UTF_8.name())
                }
            }
        } catch (t: Throwable) {
            Lg.i(TAG, "handpaint-entry-fail entry=$entryName ${t.javaClass.simpleName}")
            null
        }
    }

    /** 热重载或换包后清账重解析。 */
    fun reset() {
        synchronized(this) {
            cachedStyle = -1
            cached = null
            packPath = null
        }
    }
}