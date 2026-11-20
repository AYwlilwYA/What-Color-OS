package com.coloros16.liquidglass.diagnostics

import android.content.Context
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.log.LogCapture
import com.coloros16.liquidglass.sf.SfGlassChannel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 作用域应用打包：把注入作用域里的宿主 APK 从设备取出打成一个 zip，落 captures 目录供分享。
 * 宿主包多在 /system 下，普通读权限不够，一律走 su；单包失败不影响其余包。
 * **阻塞**，调用方必须放后台线程，且先过 root 门。
 */
object ScopeBundle {

    private const val TAG = "ScopeBundle"
    private const val FILE_PREFIX = "lg-scope-"
    private const val FILE_SUFFIX = ".zip"
    private const val PM_PATH_TIMEOUT_MS = 8000L
    private const val PULL_TIMEOUT_MS = 60000L

    /** 打包结果：zip 文件（失败为 null）+ 逐包明细。 */
    data class Result(val file: File?, val items: List<ScopeBundlePlan.Item>) {
        val summary: ScopeBundlePlan.Summary get() = ScopeBundlePlan.summarize(items)
    }

    /** 已打包文件（新→旧），与日志抓取文件同目录同分享入口。 */
    fun listBundles(context: Context): List<File> {
        val dir = LogCapture.captureDir(context)
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 读模块 APK 内 META-INF/xposed/scope.list（作用域权威清单，与框架采纳的同一份）。 */
    fun readScopeList(context: Context): List<String> {
        val text = try {
            val apk = context.applicationInfo.sourceDir
            java.util.zip.ZipFile(apk).use { z ->
                val e = z.getEntry("META-INF/xposed/scope.list") ?: return emptyList()
                z.getInputStream(e).bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "scope.list read failed", t)
            ""
        }
        return ScopeBundlePlan.parseScope(text)
    }

    /**
     * 打包主流程：逐包 `pm path` 取路径 → `su cat` 取字节 → 写 zip 条目。
     * 每个包独立 try，失败记错继续；全程无轮询。
     */
    fun bundle(context: Context, packages: List<String>): Result {
        val dir = LogCapture.captureDir(context)
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "$FILE_PREFIX$stamp$FILE_SUFFIX")
        val items = mutableListOf<ScopeBundlePlan.Item>()
        var totalEntries = 0
        try {
            ZipOutputStream(FileOutputStream(out).buffered()).use { zip ->
                for (pkg in packages) {
                    val item = runCatching { pullPackage(pkg, zip) }
                        .getOrElse { ScopeBundlePlan.Item(pkg, 0, 0, it.javaClass.simpleName) }
                    items.add(item)
                    totalEntries += item.entries
                    Lg.i(TAG, "scope pull pkg=$pkg entries=${item.entries} bytes=${item.bytes} err=${item.error}")
                }
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "scope bundle failed", t)
            out.delete()
            return Result(null, items)
        }
        if (totalEntries == 0) {
            out.delete()
            return Result(null, items)
        }
        Lg.i(TAG, "scope bundle done file=${out.name} entries=$totalEntries bytes=${out.length()}")
        return Result(out, items)
    }

    /** 取一个包的全部 APK 路径并逐个写进 zip；base.apk 优先。 */
    private fun pullPackage(pkg: String, zip: ZipOutputStream): ScopeBundlePlan.Item {
        val pm = SfGlassChannel.runSu("pm path $pkg", PM_PATH_TIMEOUT_MS)
        if (!pm.ok) return ScopeBundlePlan.Item(pkg, 0, 0, "pm path 失败")
        val paths = ScopeBundlePlan.parsePmPath(pm.out)
        if (paths.isEmpty()) return ScopeBundlePlan.Item(pkg, 0, 0, "未安装")
        var entries = 0
        var bytes = 0L
        for (path in paths) {
            val n = streamInto(path, zip, ScopeBundlePlan.entryName(pkg, path))
            if (n > 0) {
                entries++
                bytes += n
            }
        }
        return if (entries == 0) ScopeBundlePlan.Item(pkg, 0, 0, "读取失败")
        else ScopeBundlePlan.Item(pkg, entries, bytes, null)
    }

    /**
     * `su -c cat <path>` 的 stdout 原始字节流式写进 zip 条目：宿主 APK 动辄数十 MB，
     * 不能整包进内存、也不能经文本管道（二进制会被改写）。返回写入字节数，失败返回 0。
     */
    private fun streamInto(path: String, zip: ZipOutputStream, entry: String): Long {
        var p: Process? = null
        return try {
            p = ProcessBuilder("su", "-c", "cat '$path'").start()
            p.outputStream.close()
            var written = 0L
            val buf = ByteArray(64 * 1024)
            zip.putNextEntry(ZipEntry(entry))
            p.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    zip.write(buf, 0, n)
                    written += n
                }
            }
            zip.closeEntry()
            val done = p.waitFor(PULL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                Lg.w(TAG, "scope pull timeout path=$path")
                return 0
            }
            if (p.exitValue() != 0) {
                Lg.w(TAG, "scope pull exit=${p.exitValue()} path=$path")
                return 0
            }
            written
        } catch (t: Throwable) {
            Lg.w(TAG, "scope pull failed path=$path", t)
            runCatching { zip.closeEntry() }
            0
        } finally {
            p?.destroyForcibly()
        }
    }
}
