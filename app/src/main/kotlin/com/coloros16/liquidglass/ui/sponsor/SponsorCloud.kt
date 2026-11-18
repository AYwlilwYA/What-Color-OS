package com.coloros16.liquidglass.ui.sponsor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.coloros16.liquidglass.log.Lg
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 赞助名单与头像的云端拉取：名单文本与头像图片都从远端仓库读，APK 内不打包头像。
 * 名单行格式 `首字母|名字` 或 `首字母|名字|头像`，第三段可为资源名（包内 drawable）、
 * 完整 https 直链或相对文件名（拼在名单同目录下）。
 *
 * **线程约定**：[fetch] 与 [loadAvatar] 都是阻塞网络调用，必须在子线程执行。
 * 失败一律返回 null / 空（网络不通 / 非 200 / 超大 / 解码失败都不抛给调用方），
 * 名单拉不到就整块隐藏，头像拉不到就显示首字母圆底。
 */
object SponsorCloud {

    private const val TAG = "LiquidGlassSponsor"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000
    private const val MAX_LIST_BYTES = 64 * 1024
    private const val MAX_AVATAR_BYTES = 512 * 1024
    private const val AVATAR_TTL_MS = 24L * 60 * 60 * 1000
    private const val AVATAR_CACHE_DIR = "sponsors"

    /** 名单条目：首字母 / 名字 / 头像（空=首字母圆底）。 */
    data class Row(val initial: String, val name: String, val avatar: String)

    private val mem = ConcurrentHashMap<String, Bitmap>()

    /**
     * 解析名单文本：跳过空行与 `#` 注释，段数不足 2 或首字母为空的行丢弃。
     * 头像段做白名单校验（资源名或 https 直链），拦掉本地路径与其它协议。
     */
    fun parse(text: String): List<Row> = text.lineSequence().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
        val p = t.split('|')
        val initial = p.getOrNull(0)?.trim().orEmpty()
        if (p.size < 2 || initial.isEmpty()) return@mapNotNull null
        Row(initial, p[1].trim(), safeAvatar(p.getOrNull(2)?.trim().orEmpty()))
    }.toList()

    /** 头像段白名单：资源名 / 同目录文件名（字母数字下划线点横线）或 https 直链；
     *  其余（含路径分隔符、`..`、`file:`）判空。 */
    internal fun safeAvatar(raw: String): String {
        if (raw.isEmpty()) return ""
        if (raw.startsWith("https://")) return if (raw.contains("..")) "" else raw
        if (raw.contains("..")) return ""
        return if (raw.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }) raw else ""
    }

    /** 相对文件名 → 绝对直链（以名单地址所在目录为基准）；已是绝对地址则原样返回。 */
    internal fun resolveAvatarUrl(listUrl: String, ref: String): String {
        if (ref.startsWith("https://") || ref.startsWith("http://")) return ref
        val base = listUrl.substringBeforeLast('/', "")
        return if (base.isEmpty()) ref else "$base/$ref"
    }

    /** 拉名单文本并解析。**阻塞**，须在子线程调用；失败返回 null（不缓存，下次重试）。 */
    fun fetch(listUrl: String): List<Row>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(listUrl)
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Lg.w(TAG, "sponsor list http $code")
                return null
            }
            val body = conn.inputStream.use { readCapped(it, MAX_LIST_BYTES) }
            if (body == null) {
                Lg.w(TAG, "sponsor list too large (>${MAX_LIST_BYTES}B)")
                return null
            }
            parse(String(body, Charsets.UTF_8))
                .also { Lg.i(TAG, "sponsor list fetched n=${it.size}") }
        } catch (t: Throwable) {
            Lg.w(TAG, "sponsor list fetch failed", t)
            null
        } finally {
            close(conn)
        }
    }

    /**
     * 取头像：内存 → 本地文件缓存（TTL 内直接用）→ 远端下载并落缓存。
     * **阻塞**，须在子线程调用；任一步失败返回 null，调用方保持首字母圆底。
     */
    fun loadAvatar(context: Context, listUrl: String, ref: String): Bitmap? {
        if (ref.isEmpty()) return null
        val url = resolveAvatarUrl(listUrl, ref)
        val key = sha1(url)
        mem[key]?.let { if (!it.isRecycled) return it }
        val file = File(File(context.cacheDir, AVATAR_CACHE_DIR), "$key.img")
        val cached = if (file.isFile &&
            System.currentTimeMillis() - file.lastModified() < AVATAR_TTL_MS
        ) {
            decodeFile(file)
        } else {
            null
        }
        if (cached != null) {
            mem[key] = cached
            Lg.i(TAG, "sponsor avatar cache hit key=$key")
            return cached
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = open(url)
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Lg.w(TAG, "sponsor avatar http $code key=$key")
                return null
            }
            val bytes = conn.inputStream.use { readCapped(it, MAX_AVATAR_BYTES) }
            if (bytes == null) {
                Lg.w(TAG, "sponsor avatar too large key=$key (>${MAX_AVATAR_BYTES}B)")
                return null
            }
            val bmp = decodeCircle(bytes)
            if (bmp == null) {
                Lg.w(TAG, "sponsor avatar decode failed key=$key bytes=${bytes.size}")
                return null
            }
            mem[key] = bmp
            writeCache(file, bytes)
            Lg.i(TAG, "sponsor avatar fetched key=$key ${bmp.width}x${bmp.height}")
            bmp
        } catch (t: Throwable) {
            Lg.w(TAG, "sponsor avatar fetch failed key=$key", t)
            null
        } finally {
            close(conn)
        }
    }

    /** 释放内存头像（页面销毁时调；文件缓存保留，下次进页面直接命中）。 */
    fun clearMemory() {
        mem.clear()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiquidGlass-Sponsors")
        }

    private fun close(conn: HttpURLConnection?) {
        try {
            conn?.disconnect()
        } catch (_: Throwable) {
        }
    }

    /** 限量读取：超限返回 null（防远端异常大文件把内存吃满）。 */
    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (buf.size() + n > cap) return null
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    private fun decodeFile(file: File): Bitmap? = try {
        decodeCircle(file.readBytes())
    } catch (t: Throwable) {
        Lg.w(TAG, "sponsor avatar cache read failed", t)
        null
    }

    private fun writeCache(file: File, bytes: ByteArray) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (t: Throwable) {
            Lg.w(TAG, "sponsor avatar cache write failed", t)
        }
    }

    /** 解码 + 居中裁方 + 圆形裁切，边长上限 192px（列表位 48dp，3x 密度够用）。 */
    private fun decodeCircle(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val side = minOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (side / (sample * 2) >= 192) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val w = src.width
        val h = src.height
        val crop = minOf(w, h)
        val scale = 192f / crop
        val outSide = maxOf(1, (crop * scale).toInt())
        val out = Bitmap.createBitmap(outSide, outSide, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, outSide.toFloat(), outSide.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { sh ->
                val m = android.graphics.Matrix()
                m.setScale(scale, scale)
                m.postTranslate(-(w - crop) * scale / 2f, -(h - crop) * scale / 2f)
                sh.setLocalMatrix(m)
            }
        }
        canvas.drawRoundRect(rect, outSide / 2f, outSide / 2f, paint)
        if (src != out) src.recycle()
        return out
    }

    private fun sha1(s: String): String = try {
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        s.hashCode().toString(16)
    }
}
