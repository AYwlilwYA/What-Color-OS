package com.coloros16.liquidglass.ui.aod

import android.content.res.AssetManager
import android.graphics.Bitmap
import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.log.Lg
import java.io.IOException
import java.util.concurrent.Executors
import java.util.zip.Inflater

/**
 * 照片类息屏的帧序列：zlib 原始 RGB 解压后降采样成小图，按需解码 + 少量缓存。
 * 解码只在单线程后台跑，UI 线程取不到就先沿用上一帧。
 */
class PhotoFrameSequence(
    private val assets: AssetManager,
    private val style: PhotoStyle,
    private val targetSize: Int = 360,
    private val capacity: Int = 20,
    private val onReady: () -> Unit,
) : AutoCloseable {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aod-photo-${style.folder}").apply { isDaemon = true }
    }
    private val lock = Any()
    private val cache = LinkedHashMap<Int, Bitmap>(capacity, 0.75f, true)
    private val pending = LinkedHashSet<Int>()
    private var draining = false

    @Volatile
    private var closed = false

    /** 取一帧；未解码时排队后台，返回 null 由调用方沿用上一帧。 */
    fun frame(index: Int): Bitmap? {
        synchronized(lock) {
            val hit = cache[index]
            if (hit != null) return hit
            queueLocked(index)
            return null
        }
    }

    fun prefetch(index: Int) {
        synchronized(lock) { queueLocked(index) }
    }

    /** 一次排入若干帧，顺序即优先级。 */
    fun prefetch(indices: IntArray) {
        synchronized(lock) { for (i in indices) queueLocked(i) }
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
        synchronized(lock) {
            cache.clear()
            pending.clear()
        }
    }

    private fun queueLocked(index: Int) {
        if (closed) return
        if (index < 0 || index > style.frames + 1) return
        if (cache.containsKey(index)) return
        if (!pending.add(index)) return
        if (draining) return
        draining = true
        worker.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val index = synchronized(lock) {
                val it = pending.iterator()
                if (it.hasNext()) {
                    val next = it.next()
                    it.remove()
                    next
                } else {
                    draining = false
                    null
                }
            } ?: return
            val bitmap = runCatching { decode(index) }.onFailure {
                if (!closed) Lg.w(TAG, "decode fail style=${style.folder} index=$index ${it.javaClass.simpleName}")
            }.getOrNull() ?: continue
            synchronized(lock) {
                if (closed) return
                cache[index] = bitmap
                while (cache.size > capacity) {
                    val oldest = cache.entries.iterator()
                    if (oldest.hasNext()) {
                        oldest.next()
                        oldest.remove()
                    } else {
                        break
                    }
                }
            }
            onReady()
        }
    }

    private fun decode(index: Int): Bitmap {
        val name = style.frameName(index)
        val side = if (index > style.frames) END_SIZE else style.size
        val expected = side * side * 3
        val bytes = assets.open("aod/photo/${style.folder}/$name.rgbz").use { it.readBytes() }
        val raw = inflate(bytes, expected)
        return downsample(raw, side)
    }

    private fun inflate(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        val inflater = Inflater()
        try {
            inflater.setInput(src)
            var offset = 0
            val chunk = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw IOException("incomplete mask stream")
                    }
                    continue
                }
                if (offset + n > expected) throw IOException("mask overflow")
                System.arraycopy(chunk, 0, out, offset, n)
                offset += n
            }
            if (offset != expected) throw IOException("mask size $offset != $expected")
            return out
        } finally {
            inflater.end()
        }
    }

    /** 盒式平均降采样：掩膜边缘保留羽化，避免最近邻把星点打成噪点。 */
    private fun downsample(raw: ByteArray, side: Int): Bitmap {
        val size = targetSize.coerceAtMost(side)
        val pixels = IntArray(size * size)
        val scale = side.toDouble() / size
        for (ty in 0 until size) {
            val y0 = (ty * scale).toInt()
            val y1 = (((ty + 1) * scale).toInt()).coerceIn(y0 + 1, side)
            for (tx in 0 until size) {
                val x0 = (tx * scale).toInt()
                val x1 = (((tx + 1) * scale).toInt()).coerceIn(x0 + 1, side)
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (y in y0 until y1) {
                    var i = (y * side + x0) * 3
                    for (x in x0 until x1) {
                        r += raw[i].toInt() and 0xFF
                        g += raw[i + 1].toInt() and 0xFF
                        b += raw[i + 2].toInt() and 0xFF
                        i += 3
                        count++
                    }
                }
                pixels[ty * size + tx] = (0xFF shl 24) or
                    ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val TAG = "LgAodPhoto"

        /** 结束帧固定 720 见方。 */
        const val END_SIZE = 720
    }
}
