package com.coloros16.liquidglass.hook.aod

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import com.coloros16.liquidglass.engine.AodDrift
import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.hook.HookAssetSource
import com.coloros16.liquidglass.hook.RuntimeWorkPolicy
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.ui.aod.AodLensRenderer
import com.coloros16.liquidglass.ui.aod.AodSceneSpec
import com.coloros16.liquidglass.ui.aod.PhotoFrameSequence
import com.coloros16.liquidglass.ui.aod.PhotoSceneRenderer
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 息屏世界层：按样式契约画镜片世界，压在遮罩与压暗之下、系统内容之上。
 *
 * 求值全部走引擎（SailLensMotion），渲染复用预览侧同一份镜片渲染器，
 * 两侧共用真值不各写一套。壁纸源事件驱动解码 + 单飞，无轮询。
 */
object AodWorldLayer {

    private const val TAG = "LgAodWorld"
    private const val THROTTLE_MS = 2000L
    private const val MAX_DECODE_DIM = 1440

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val worker = RuntimeWorkPolicy.executor("lg-aod-world")
    private val decoding = AtomicBoolean(false)

    @Volatile private var wallpaper: Bitmap? = null
    @Volatile private var wallpaperId = -1
    @Volatile private var aspect = 0f
    @Volatile private var renderer: AodLensRenderer? = null
    @Volatile private var sourceKey: Bitmap? = null
    @Volatile private var liveWallpaper = false
    @Volatile private var decodeMissLogged = false

    private val pose = SailLensMotion.Pose()
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()

    @Volatile private var api: XposedInterface? = null
    @Volatile private var photoRenderer: PhotoSceneRenderer? = null
    @Volatile private var sequence: PhotoFrameSequence? = null
    @Volatile private var sequenceStyleId = -1
    @Volatile private var photoFrame: Bitmap? = null
    private val photoValues = PhotoStyleMotion.Values()

    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 32) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }

    /** 绑定框架接口：渲染器取模块 assets 与照片帧序列都要它。 */
    fun bind(api: XposedInterface) {
        this.api = api
    }

    /**
     * 壁纸源按需刷新：壁纸 id 变了才解码，动态壁纸判不可用。
     *
     * 由挂载/换样式/壁纸变更事件调用，解码在后台单飞，完成回调重绘。
     */
    fun ensureWallpaper(onReady: () -> Unit) {
        val wm = runCatching {
            val ctx = Lg.processContext() ?: return
            WallpaperManager.getInstance(ctx)
        }.getOrNull() ?: return
        val info = runCatching { wm.wallpaperInfo }.getOrNull()
        if (info != null) {
            if (!liveWallpaper) {
                liveWallpaper = true
                wallpaper = null
                Lg.i(TAG, "world-wallpaper live wallpaper, static source unavailable")
            }
            return
        }
        liveWallpaper = false
        val id = runCatching { wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
        if (id <= 0) {
            logThrottled("world-wallpaper-noid", "壁纸 id 取不到 id=$id")
            return
        }
        if (id == wallpaperId && wallpaper != null) return
        if (!decoding.compareAndSet(false, true)) return
        runCatching {
            worker.execute {
                try {
                    val bmp = decode(wm)
                    if (bmp != null) {
                        wallpaper = bmp
                        wallpaperId = id
                        Lg.i(TAG, "world-wallpaper decoded ${bmp.width}x${bmp.height} id=$id")
                    } else if (!decodeMissLogged) {
                        decodeMissLogged = true
                        Lg.i(TAG, "world-wallpaper decode failed id=$id")
                    }
                } catch (t: Throwable) {
                    Lg.w(TAG, "world-wallpaper decode error ${t.javaClass.simpleName}")
                } finally {
                    decoding.set(false)
                    runCatching { onReady() }
                }
            }
        }.onFailure {
            decoding.set(false)
            logThrottled("world-wallpaper-noworker", "解码任务未入队 ${it.javaClass.simpleName}")
        }
    }

    private fun decode(wm: WallpaperManager): Bitmap? {
        val pfd = runCatching { wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
        if (pfd == null) return decodeDrawable(wm)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            pfd.dup().use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds) }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (maxOf(w, h) / sample > MAX_DECODE_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            pfd.dup().use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, opts) }
                ?: decodeDrawable(wm)
        } finally {
            runCatching { pfd.close() }
        }
    }

    /**
     * 无壁纸文件时的兜底：把壁纸 Drawable 画进位图。
     *
     * ColorOS 默认壁纸走 ImageWallpaper 且 `wallpaperFile=` 为空，
     * `getWallpaperFile` 返回 null，只有 Drawable 通道拿得到像素（与预览侧同一取法）。
     */
    private fun decodeDrawable(wm: WallpaperManager): Bitmap? {
        val drawable = runCatching { wm.drawable }.getOrNull() ?: run {
            logThrottled("world-wallpaper-nodrawable", "壁纸文件与 Drawable 都取不到")
            return null
        }
        val sw = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
        val sh = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
        val scale = MAX_DECODE_DIM.toFloat() / maxOf(sw, sh)
        val tw = if (scale < 1f) (sw * scale).toInt().coerceAtLeast(1) else sw
        val th = if (scale < 1f) (sh * scale).toInt().coerceAtLeast(1) else sh
        return runCatching {
            Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888).also { bmp ->
                drawable.setBounds(0, 0, tw, th)
                drawable.draw(Canvas(bmp))
            }
        }.getOrNull()
    }

    /**
     * 画世界层：只有镜片类样式有实现，其余样式返回 false 交回上层。
     *
     * [edge] 为空表示静止态；[spinDeg] 为息屏自转角，由调用方按动态开关给。
     */
    fun drawLens(
        canvas: Canvas,
        width: Float,
        height: Float,
        spec: AodSceneSpec,
        state: SceneState,
        edge: TransitionEdge?,
        t: Float,
        dim: Float,
        aodAlpha: Float,
        spinDeg: Float,
        allowDynamic: Boolean,
        colorMix: Float,
        wallpaperScale: Float = 1f,
    ): Boolean {
        if (!spec.lens) return false
        if (width <= 0f || height <= 0f) return false
        val ctx = Lg.processContext() ?: run {
            logThrottled("world-noctx", "进程 context 为空，世界层不画")
            return false
        }
        val r = renderer ?: runCatching { AodLensRenderer(assetContext(ctx)) }
            .getOrNull()?.also { renderer = it }
        if (r == null) {
            logThrottled("world-norenderer", "镜片渲染器创建失败")
            return false
        }
        val nextAspect = height / width
        val bmp = wallpaper
        if (bmp != null && (bmp !== sourceKey || kotlin.math.abs(nextAspect - aspect) > 1e-4f)) {
            sourceKey = bmp
            aspect = nextAspect
            runCatching { r.setSource(bmp, nextAspect) }
        }
        if (bmp == null) {
            logThrottled("world-nosource", "壁纸源未就绪，世界层不画 live=$liveWallpaper id=$wallpaperId")
            return false
        }

        val anchor = spec.clockAnchors[SceneState.AOD]
        val aodX = anchor?.centerX ?: 0.5f
        val aodY = (anchor?.centerY ?: 0.6f) + AodDrift.offsetY(SystemClock.uptimeMillis())
        val expanded = SailLensMotion.expandedScale(nextAspect)
        if (edge != null) {
            SailLensMotion.sample(edge, t, aodX, aodY, expanded, pose, spinDeg = spinDeg)
        } else {
            SailLensMotion.sampleStatic(state, aodX, aodY, expanded, pose)
            if (state == SceneState.AOD && allowDynamic) pose.angleDeg = spinDeg
        }

        drawBackdrop(canvas, width, height, bmp, wallpaperScale)
        dimPaint.style = Paint.Style.FILL
        dimPaint.shader = null
        dimPaint.color = Color.argb((dim.coerceIn(0f, 1f) * 255f).toInt(), 2, 4, 10)
        canvas.drawRect(0f, 0f, width, height, dimPaint)

        val a = aodAlpha.coerceIn(0f, 1f)
        if (a > 0.002f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, width, height, (a * 255f).toInt())
            dimPaint.color = Color.BLACK
            canvas.drawRect(0f, 0f, width, height, dimPaint)
            runCatching {
                r.draw(
                    canvas, width, height, pose,
                    SailLensMotion.lensZoom(pose.scale),
                    colorMix = if (state == SceneState.AOD) colorMix else 0f,
                )
            }.onFailure {
                logThrottled("world-draw-fail", "镜片绘制失败 ${it.javaClass.simpleName}")
            }
            canvas.restoreToCount(layer)
        }
        logThrottled(
            "world-lens",
            "state=$state edge=$edge t=${"%.3f".format(t)} scale=${"%.3f".format(pose.scale)} " +
                "blur=${"%.1f".format(pose.blur)} aod=${"%.2f".format(a)}",
        )
        return true
    }

    /** 渲染器构造期读 `context.assets` 取装饰层，宿主 context 无本模块素材故换模块 assets。 */
    private fun assetContext(ctx: android.content.Context): android.content.Context {
        val a = api ?: return ctx
        return runCatching { HookAssetSource.wrap(ctx, a) }.getOrDefault(ctx)
    }

    /** 壁纸按 cover 同框铺满，与预览侧同一口径。 */
    private fun drawBackdrop(
        canvas: Canvas,
        width: Float,
        height: Float,
        bmp: Bitmap,
        zoom: Float = 1f,
    ) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0f || bh <= 0f) return
        val scale = maxOf(width / bw, height / bh) * zoom.coerceAtLeast(1e-3f)
        val dw = bw * scale
        val dh = bh * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        backdropPaint.shader = null
        backdropPaint.color = Color.WHITE
        backdropPaint.alpha = 255
        canvas.drawBitmap(bmp, null, android.graphics.RectF(left, top, left + dw, top + dh), backdropPaint)
    }

    /**
     * 画照片类世界：绿幕帧当内容窗口，方形网格从时钟位置展开铺满整屏。
     *
     * 帧序列按样式建一次（换样式才重建），解码在序列自己的后台线程；
     * 取不到帧就沿用上一帧，不阻塞绘制。
     */
    fun drawPhoto(
        canvas: Canvas,
        width: Float,
        height: Float,
        spec: AodSceneSpec,
        state: SceneState,
        edge: TransitionEdge?,
        t: Float,
        dim: Float,
        wallpaperScale: Float,
        accent: Int,
        tiltDeg: Float,
        api: XposedInterface?,
        onFrameReady: () -> Unit,
    ): Boolean {
        val photo = spec.photo ?: return false
        if (width <= 0f || height <= 0f) return false
        val ctx = Lg.processContext() ?: run {
            logThrottled("world-noctx", "进程 context 为空，世界层不画")
            return false
        }
        val bmp = wallpaper
        if (bmp == null) {
            logThrottled("world-nosource", "壁纸源未就绪，世界层不画 live=$liveWallpaper id=$wallpaperId")
            return false
        }
        val seq = ensureSequence(photo, api, onFrameReady) ?: return false
        val r = photoRenderer ?: runCatching { PhotoSceneRenderer(assetContext(ctx)) }
            .getOrNull()?.also { photoRenderer = it }
        if (r == null) {
            logThrottled("world-norenderer", "照片渲染器创建失败")
            return false
        }

        val aspect = height / width
        val anchor = spec.clockAnchors[SceneState.AOD]
        val drift = if (edge == null && state == SceneState.AOD) {
            AodDrift.offsetY(SystemClock.uptimeMillis())
        } else {
            0f
        }
        val anchorX = (anchor?.centerX ?: 0.5f) - 0.5f
        val anchorY = 0.5f - ((anchor?.centerY ?: 0.435f) + drift)
        if (edge == null) {
            PhotoStyleMotion.sampleStatic(photo, state, aspect, anchorX, anchorY, photoValues)
        } else {
            PhotoStyleMotion.sample(photo, edge, t, aspect, anchorX, anchorY, photoValues)
        }

        val index = photo.frameIndex(photoValues.v[PhotoStyle.SLOT_VIDEO])
        seq.frame(index)?.let { photoFrame = it }
        seq.prefetch(intArrayOf(index + 1, index + 2, index - 1, index - 2))
        val frame = photoFrame ?: run {
            logThrottled("world-noframe", "帧未解码 style=${photo.folder} index=$index")
            return false
        }

        val blurPx = photoValues.v[PhotoStyle.SLOT_BLUR]
        runCatching { r.setSource(bmp, aspect, blurPx) }
        drawBackdrop(canvas, width, height, bmp, wallpaperScale)

        val values = photoValues.v
        val savedRotation = values[PhotoStyle.SLOT_ROTATION]
        values[PhotoStyle.SLOT_ROTATION] = savedRotation + tiltDeg
        runCatching {
            r.draw(
                canvas, width, height, values, frame, accent,
                dim * (1f - photoValues.ratios[SceneState.AOD.ordinal]),
                blurPx, wallpaperScale,
            )
        }.onFailure {
            logThrottled("world-draw-fail", "照片绘制失败 ${it.javaClass.simpleName}")
        }
        values[PhotoStyle.SLOT_ROTATION] = savedRotation
        logThrottled(
            "world-photo",
            "style=${photo.folder} state=$state edge=$edge t=${"%.3f".format(t)} " +
                "frame=$index scale=${"%.3f".format(values[PhotoStyle.SLOT_SCALE])} " +
                "alpha=${"%.2f".format(values[PhotoStyle.SLOT_PHOTO_ALPHA])}",
        )
        return true
    }

    /** 帧序列按样式 id 建一次；assets 走模块 APK（宿主 assets 无本模块素材）。 */
    private fun ensureSequence(
        photo: PhotoStyle,
        api: XposedInterface?,
        onReady: () -> Unit,
    ): PhotoFrameSequence? {
        val existing = sequence
        if (existing != null && sequenceStyleId == photo.id) return existing
        val a = api ?: run {
            logThrottled("world-noapi", "框架接口未绑定，帧序列不可建")
            return null
        }
        val assets = HookAssetSource.open(a) ?: run {
            logThrottled("world-noassets", "模块 assets 不可用，照片类不画")
            return null
        }
        runCatching { existing?.close() }
        val created = runCatching {
            PhotoFrameSequence(assets, photo, onReady = onReady)
        }.getOrNull() ?: run {
            logThrottled("world-nosequence", "帧序列创建失败 style=${photo.folder}")
            return null
        }
        created.prefetch(intArrayOf(photo.frames, 0, 1, 2, 3))
        sequence = created
        sequenceStyleId = photo.id
        photoFrame = null
        Lg.i(TAG, "world-sequence style=${photo.folder} frames=${photo.frames}")
        return created
    }

    /** 壁纸变更事件：清 id 让下次按需重解码（不主动解码，避免无消费时做功）。 */
    fun noteWallpaperChanged() {
        wallpaperId = -1
        logThrottled("world-wallpaper-changed", "壁纸变更，下次按需重解码")
    }

    /** 热重载/摘层：释放位图与渲染器，清账本。 */
    fun release() {
        renderer = null
        sourceKey = null
        wallpaper = null
        wallpaperId = -1
        aspect = 0f
        liveWallpaper = false
        decodeMissLogged = false
        runCatching { sequence?.close() }
        sequence = null
        sequenceStyleId = -1
        photoFrame = null
        photoRenderer = null
        photoValues.reset()
        throttleAt.clear()
        pose.reset()
    }
}
