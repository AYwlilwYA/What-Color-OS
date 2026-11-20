package com.coloros16.liquidglass.hook.aod

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import com.coloros16.liquidglass.engine.AodDrift
import com.coloros16.liquidglass.engine.BackdropFit
import com.coloros16.liquidglass.engine.BackdropSourcePolicy
import com.coloros16.liquidglass.engine.HandPaintGeometry
import com.coloros16.liquidglass.engine.HandPaintSilk
import com.coloros16.liquidglass.engine.HandPaintSource
import com.coloros16.liquidglass.engine.HandPaintTimeline
import com.coloros16.liquidglass.engine.PhotoRevealPolicy
import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.WorldCenterPolicy
import com.coloros16.liquidglass.hook.DesktopClockAnchorBridge
import com.coloros16.liquidglass.hook.DeviceHandPaint
import com.coloros16.liquidglass.hook.HookAssetSource
import com.coloros16.liquidglass.hook.RuntimeWorkPolicy
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.ui.aod.AodLensRenderer
import com.coloros16.liquidglass.ui.aod.AodSceneSpec
import com.coloros16.liquidglass.ui.aod.HandPaintRenderer
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
    private const val FAST_THROTTLE_MS = 120L
    private const val MAX_DECODE_DIM = 1440
    private const val SCENE_SKY_ASSET = "aod/scene_sky.webp"

    /** 底图绘制后的空回调默认值；具名常量避免默认参里的花括号干扰源码扫描。 */
    private val NOOP: () -> Unit = {}

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val fastAt = ConcurrentHashMap<String, Long>()
    private var fastDoneEdge: TransitionEdge? = null
    private val worker = RuntimeWorkPolicy.executor("lg-aod-world")
    private val photoWorker = RuntimeWorkPolicy.executor("lg-aod-photo")
    private val decoding = AtomicBoolean(false)

    @Volatile private var wallpaper: Bitmap? = null
    @Volatile private var wallpaperId = -1

    @Volatile private var stylePhoto: Bitmap? = null
    @Volatile private var stylePhotoUri = ""
    private val stylePhotoLoading = AtomicBoolean(false)

    /** 模块场景底图：壁纸与用户照片都不可用时的第三路，与预览侧同一素材。 */
    @Volatile private var sceneSky: Bitmap? = null
    private val sceneSkyLoading = AtomicBoolean(false)
    @Volatile private var sceneSkyMissLogged = false

    @Volatile private var framePhotoBitmap: Bitmap? = null
    @Volatile private var framePhotoUri = ""
    private val framePhotoLoading = AtomicBoolean(false)
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
    private var revealCache: Bitmap? = null
    private var revealKey: String? = null

    @Volatile private var handPaintSilks: List<HandPaintSilk>? = null
    @Volatile private var handPaintRequested = -1
    @Volatile private var handPaintRenderer: HandPaintRenderer? = null
    @Volatile private var handPaintSide = 0
    private val handPaintLoading = AtomicBoolean(false)
    private val handPaintPlay = HandPaintTimeline.Play()

    /** 转场期按帧日志：边内 120ms 采样，落位那一帧不受节流，端点才不会被吞。 */
    private fun logLensFrame(edge: TransitionEdge?, t: Float, msg: String) {
        if (edge == null) {
            logThrottled("world-lens", msg)
            return
        }
        if (t < 1f) {
            val now = SystemClock.uptimeMillis()
            val last = fastAt[edge.name]
            if (last != null && now - last < FAST_THROTTLE_MS) return
            fastAt[edge.name] = now
            Lg.i(TAG, "world-lens: $msg")
            return
        }
        if (fastDoneEdge == edge) return
        fastDoneEdge = edge
        Lg.i(TAG, "world-lens: $msg")
    }

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

    /** 已解码的静态壁纸；动态壁纸或尚未解码时为 null，调用方据此降级。 */
    fun wallpaperOrNull(): Bitmap? = wallpaper?.takeIf { !it.isRecycled && !liveWallpaper }

    /** 已解码的用户照片；未选或尚未解码时为 null。 */
    fun stylePhotoOrNull(): Bitmap? = stylePhoto?.takeIf { !it.isRecycled }

    /** 已解码的相框前景照片；未选或尚未解码时为 null。 */
    fun framePhotoOrNull(): Bitmap? = framePhotoBitmap?.takeIf { !it.isRecycled }

    /** 已解码的模块场景底图；尚未解码或素材缺失时为 null。 */
    fun sceneSkyOrNull(): Bitmap? = sceneSky?.takeIf { !it.isRecycled }

    /**
     * 世界层底图：用户照片 → 系统壁纸（接管开关开才用）→ 模块场景底图 → 无底。
     *
     * 与预览侧共用 [BackdropSourcePolicy] 的优先级，四路逐路对齐、两侧不各写一套。
     */
    fun backdropOrNull(useSystemWallpaper: Boolean): Bitmap? {
        val wall = wallpaperOrNull()
        return when (
            BackdropSourcePolicy.backdropKind(
                stylePhotoOrNull() != null, useSystemWallpaper, wall != null, sceneSkyOrNull() != null,
            )
        ) {
            BackdropSourcePolicy.Kind.STYLE_PHOTO -> stylePhotoOrNull()
            BackdropSourcePolicy.Kind.SYSTEM_WALLPAPER -> wall
            BackdropSourcePolicy.Kind.SCENE_SKY -> sceneSkyOrNull()
            BackdropSourcePolicy.Kind.NONE -> null
        }
    }

    /**
     * 当前底图来自哪一路：按位图身份判，不按能力位猜。
     *
     * 能力位为真不等于这一路被选中（照片优先于壁纸），故诊断必须回显实际取到的那张。
     */
    fun backdropSourceTag(useSystemWallpaper: Boolean): String {
        val bmp = backdropOrNull(useSystemWallpaper) ?: return "none"
        return when {
            bmp === stylePhotoOrNull() -> "photo"
            bmp === sceneSkyOrNull() -> "sky"
            bmp === wallpaperOrNull() -> "wall"
            else -> "other"
        }
    }

    /**
     * 底图缺失诊断：区分「解码在途」与「压根没有可用源」。
     *
     * 原文案一律写「未就绪」，把结构性缺失说成时序问题（听起来等等就好，实际永远不会有）。
     */
    fun backdropMissReason(useSystemWallpaper: Boolean): String {
        val wall = wallpaperOrNull()
        val photo = stylePhotoOrNull() != null
        val sky = sceneSkyOrNull() != null
        val loading = decoding.get() || stylePhotoLoading.get() || sceneSkyLoading.get()
        val head = if (loading) "decoding" else "no-source"
        return "$head live=$liveWallpaper id=$wallpaperId photo=$photo sky=$sky " +
            "wall=${wall != null} sys=$useSystemWallpaper skyReq=${sceneSkyLoading.get()}"
    }

    /**
     * 模块场景底图按需解码：一次性后台单飞，就绪回调重绘。
     *
     * 素材在模块 APK 里，宿主 context 取不到，必须走 [HookAssetSource]；
     * 解码失败只记一次诊断，不重试不轮询（素材缺失是结构性问题，重试无用）。
     */
    fun ensureSceneSky(onReady: () -> Unit) {
        if (sceneSkyOrNull() != null) return
        if (!sceneSkyLoading.compareAndSet(false, true)) return
        val a = api ?: run {
            sceneSkyLoading.set(false)
            logThrottled("world-sky-noapi", "框架接口未绑定，场景底图不解码")
            return
        }
        runCatching {
            worker.execute {
                try {
                    val bmp = decodeSceneSky(a)
                    if (bmp != null) {
                        sceneSky = bmp
                        Lg.i(TAG, "world-sky decoded ${bmp.width}x${bmp.height}")
                    } else if (!sceneSkyMissLogged) {
                        sceneSkyMissLogged = true
                        Lg.i(TAG, "world-sky unavailable: 模块素材不可读，第三路底图结构性缺失")
                    }
                } catch (t: Throwable) {
                    logThrottled("world-sky-fail", "场景底图解码失败 ${t.javaClass.simpleName}")
                } finally {
                    sceneSkyLoading.set(false)
                    runCatching { onReady() }
                }
            }
        }.onFailure {
            sceneSkyLoading.set(false)
            logThrottled("world-sky-noworker", "解码任务未入队 ${it.javaClass.simpleName}")
        }
    }

    private fun decodeSceneSky(a: XposedInterface): Bitmap? {
        val assets = HookAssetSource.open(a) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(SCENE_SKY_ASSET).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = BackdropSourcePolicy.sampleSizeFor(
                bounds.outWidth, bounds.outHeight, MAX_DECODE_DIM,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return assets.open(SCENE_SKY_ASSET).use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun releaseSceneSky() {
        val old = sceneSky ?: return
        sceneSky = null
        if (old !== wallpaper && old !== stylePhoto && old !== framePhotoBitmap) {
            runCatching { old.recycle() }
        }
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
     * 用户照片按需解码：URI 变了才解，后台单飞，完成回调重绘。
     *
     * 空 URI 立即清位图（取消选照片要马上生效）；解码失败保持上一张并记诊断。
     */
    fun ensureStylePhoto(uri: String, onReady: () -> Unit) {
        if (uri == stylePhotoUri) return
        stylePhotoUri = uri
        if (!BackdropSourcePolicy.needsDecode(uri)) {
            releaseStylePhoto()
            logThrottled("world-stylephoto-clear", "未选用户照片，底图回系统壁纸")
            runCatching { onReady() }
            return
        }
        decodeUserPhoto(uri, "stylephoto", stylePhotoLoading, onReady) { bmp ->
            releaseStylePhoto()
            stylePhoto = bmp
        }
    }

    /** 相框前景照片按需解码：与用户照片同一解码件，各自单飞互不阻塞。 */
    fun ensureFramePhoto(uri: String, onReady: () -> Unit) {
        if (uri == framePhotoUri) return
        framePhotoUri = uri
        if (!BackdropSourcePolicy.needsDecode(uri)) {
            releaseFramePhoto()
            logThrottled("world-framephoto-clear", "未选相框照片，相框回退单照片")
            runCatching { onReady() }
            return
        }
        decodeUserPhoto(uri, "framephoto", framePhotoLoading, onReady) { bmp ->
            releaseFramePhoto()
            framePhotoBitmap = bmp
        }
    }

    /**
     * SAF 内容 URI 解码：scheme 白名单 → 边界探测 → 采样解码，全程后台。
     *
     * 宿主进程读用户授予的持久 URI 权限；读不到只记诊断，不重试不轮询。
     */
    private fun decodeUserPhoto(
        uri: String,
        label: String,
        loading: AtomicBoolean,
        onReady: () -> Unit,
        accept: (Bitmap) -> Unit,
    ) {
        if (!loading.compareAndSet(false, true)) return
        val ctx = Lg.processContext() ?: run {
            loading.set(false)
            logThrottled("world-$label-noctx", "进程 context 为空，用户照片不解码")
            return
        }
        runCatching {
            photoWorker.execute {
                try {
                    val bmp = decodeContentUri(ctx, uri, label)
                    if (bmp != null) {
                        accept(bmp)
                        Lg.i(TAG, "world-$label decoded ${bmp.width}x${bmp.height}")
                    }
                } catch (t: Throwable) {
                    logThrottled("world-$label-fail", "解码失败 ${t.javaClass.simpleName}")
                } finally {
                    loading.set(false)
                    runCatching { onReady() }
                }
            }
        }.onFailure {
            loading.set(false)
            logThrottled("world-$label-noworker", "解码任务未入队 ${it.javaClass.simpleName}")
        }
    }

    private fun decodeContentUri(ctx: android.content.Context, uri: String, label: String): Bitmap? {
        val parsed = runCatching { android.net.Uri.parse(uri) }.getOrNull() ?: return null
        if (!BackdropSourcePolicy.schemeAllowed(parsed.scheme)) {
            logThrottled("world-$label-scheme", "scheme 不在白名单 ${parsed.scheme}")
            return null
        }
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            logThrottled("world-$label-nobounds", "尺寸探测失败，可能无 URI 读权限")
            return null
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = BackdropSourcePolicy.sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DECODE_DIM)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun releaseStylePhoto() {
        val old = stylePhoto ?: return
        stylePhoto = null
        if (old !== wallpaper && old !== framePhotoBitmap && old !== sceneSky) {
            runCatching { old.recycle() }
        }
    }

    private fun releaseFramePhoto() {
        val old = framePhotoBitmap ?: return
        framePhotoBitmap = null
        photoRenderer?.forgetPair()
        if (old !== wallpaper && old !== stylePhoto && old !== sceneSky) {
            runCatching { old.recycle() }
        }
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
        useSystemWallpaper: Boolean = true,
        onBackdrop: () -> Unit = NOOP,
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
        val bmp = backdropOrNull(useSystemWallpaper)
        if (bmp != null && (bmp !== sourceKey || kotlin.math.abs(nextAspect - aspect) > 1e-4f)) {
            sourceKey = bmp
            aspect = nextAspect
            runCatching { r.setSource(bmp, nextAspect) }
        }
        if (bmp == null) {
            logThrottled(
                "world-nosource",
                "世界层无底图，不画 " + backdropMissReason(useSystemWallpaper),
            )
            return false
        }

        val anchor = spec.clockAnchors[SceneState.AOD]
        val machine = DesktopClockAnchorBridge.machine
        val live = if (machine.hasAnchor(SceneState.AOD)) machine.anchorOf(SceneState.AOD) else null
        val center = WorldCenterPolicy.center(
            true, anchor?.centerX ?: 0.5f, anchor?.centerY ?: 0.6f,
            live?.centerX ?: Float.NaN, live?.centerY ?: Float.NaN,
        )
        val aodX = center.first
        val aodY = center.second + AodDrift.offsetY(SystemClock.uptimeMillis())
        val expanded = SailLensMotion.expandedScale(nextAspect)
        if (edge != null) {
            SailLensMotion.sample(edge, t, aodX, aodY, expanded, pose, spinDeg = spinDeg)
        } else {
            SailLensMotion.sampleStatic(state, aodX, aodY, expanded, pose)
            if (state == SceneState.AOD && allowDynamic) pose.angleDeg = spinDeg
        }

        drawBackdrop(canvas, width, height, bmp, wallpaperScale)
        onBackdrop()
        drawBlurClear(canvas, width, height, r, edge, t, wallpaperScale)
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
        logLensFrame(
            edge, t,
            "state=$state edge=$edge t=${"%.3f".format(t)} scale=${"%.3f".format(pose.scale)} " +
                "blur=${"%.1f".format(pose.blur)} aod=${"%.2f".format(a)} " +
                "cx=${"%.3f".format(aodX)} cy=${"%.3f".format(aodY)} live=${live != null} " +
                "spin=${"%.1f".format(spinDeg)} dyn=$allowDynamic",
        )
        return true
    }

    /** 渲染器构造期读 `context.assets` 取装饰层，宿主 context 无本模块素材故换模块 assets。 */
    private fun assetContext(ctx: android.content.Context): android.content.Context {
        val a = api ?: return ctx
        return runCatching { HookAssetSource.wrap(ctx, a) }.getOrDefault(ctx)
    }

    /**
     * 锁屏↔桌面整屏轻模糊转清晰：画在锐利底图之后、压暗之前，两者被同等压暗。
     *
     * 与预览侧同一曲线同一顺序；预模糊图未就绪时跳过并记诊断。
     */
    private fun drawBlurClear(
        canvas: Canvas,
        width: Float,
        height: Float,
        r: AodLensRenderer,
        edge: TransitionEdge?,
        t: Float,
        wallpaperScale: Float,
    ) {
        val a = SailLensMotion.blurClearAlpha(edge, t)
        if (a <= 0.002f) {
            logThrottled("world-blurclear-off", "轻模糊不参与 edge=$edge a=${"%.3f".format(a)}")
            return
        }
        if (!r.hasSoftBackdrop()) {
            logThrottled("world-blurclear-nosoft", "预模糊底图未就绪 edge=$edge a=${"%.3f".format(a)}")
            return
        }
        r.drawSoftBackdrop(canvas, width, height, a, wallpaperScale)
        logThrottled(
            "world-blurclear",
            "edge=$edge t=${"%.3f".format(t)} a=${"%.3f".format(a)} zoom=${"%.3f".format(wallpaperScale)}",
        )
    }

    /** 壁纸按 cover 同框铺满，与预览侧同一口径。 */
    private fun drawBackdrop(
        canvas: Canvas,
        width: Float,
        height: Float,
        bmp: Bitmap,
        zoom: Float = 1f,
    ) {
        val box = BackdropFit.coverRect(width, height, bmp.width, bmp.height, zoom) ?: return
        backdropPaint.shader = null
        backdropPaint.color = Color.WHITE
        backdropPaint.alpha = 255
        canvas.drawBitmap(
            bmp, null,
            android.graphics.RectF(box[0], box[1], box[2], box[3]),
            backdropPaint,
        )
    }

    /**
     * 只画壁纸底 + 压暗：给自身不画底的世界层分支用（手绘）。
     *
     * 壁纸源未就绪时只画压暗返回 false，调用方据此知道底没画上。
     * 缩放取系统分层真值，与预览侧 cover 折算同一口径。
     */
    fun drawWallpaperBase(
        canvas: Canvas,
        width: Float,
        height: Float,
        dim: Float,
        wallpaperScale: Float,
        useSystemWallpaper: Boolean = true,
        onBackdrop: () -> Unit = NOOP,
    ): Boolean {
        if (width <= 0f || height <= 0f) return false
        val bmp = backdropOrNull(useSystemWallpaper)
        if (bmp != null) {
            drawBackdrop(canvas, width, height, bmp, wallpaperScale)
            onBackdrop()
        } else logThrottled(
            "world-base-nosource",
            "无底图，只画压暗 " + backdropMissReason(useSystemWallpaper),
        )
        dimPaint.style = Paint.Style.FILL
        dimPaint.shader = null
        dimPaint.color = Color.argb((dim.coerceIn(0f, 1f) * 255f).toInt(), 2, 4, 10)
        canvas.drawRect(0f, 0f, width, height, dimPaint)
        return bmp != null
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
        useSystemWallpaper: Boolean = true,
        framePair: Boolean = false,
        cropX: Float = 0.5f,
        cropY: Float = 0.5f,
        cropSize: Float = 1f,
        cropAngle: Float = 0f,
        onBackdrop: () -> Unit = NOOP,
        onFrameReady: () -> Unit,
    ): Boolean {
        val photo = spec.photo ?: return false
        if (width <= 0f || height <= 0f) return false
        val ctx = Lg.processContext() ?: run {
            logThrottled("world-noctx", "进程 context 为空，世界层不画")
            return false
        }
        val bmp = backdropOrNull(useSystemWallpaper)
        if (bmp == null) {
            logThrottled(
                "world-nosource",
                "世界层无底图，不画 " + backdropMissReason(useSystemWallpaper),
            )
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
        val machine = DesktopClockAnchorBridge.machine
        val live = if (machine.hasAnchor(SceneState.AOD)) machine.anchorOf(SceneState.AOD) else null
        val center = WorldCenterPolicy.center(
            WorldCenterPolicy.photoFollowsClock(photo.id),
            anchor?.centerX ?: 0.5f, anchor?.centerY ?: 0.435f,
            live?.centerX ?: Float.NaN, live?.centerY ?: Float.NaN,
        )
        val anchorX = center.first - 0.5f
        val anchorY = 0.5f - (center.second + drift)
        val paired = PhotoStyleMotion.pairedActive(photo.id, framePair, framePhotoOrNull() != null)
        if (edge == null) {
            PhotoStyleMotion.sampleStatic(photo, state, aspect, anchorX, anchorY, photoValues, paired)
        } else {
            PhotoStyleMotion.sample(photo, edge, t, aspect, anchorX, anchorY, photoValues, paired)
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
        onBackdrop()

        val values = photoValues.v
        val savedRotation = values[PhotoStyle.SLOT_ROTATION]
        values[PhotoStyle.SLOT_ROTATION] = savedRotation + tiltDeg
        val reveal = revealBackdrop(
            bmp, width, height, values[PhotoStyle.SLOT_PHOTO_ALPHA],
            PhotoRevealPolicy.targetOf(edge, state), wallpaperScale,
        )
        val pair = if (paired) framePhotoOrNull() else null
        runCatching {
            r.draw(
                canvas, width, height, values, frame, accent,
                dim * (1f - photoValues.ratios[SceneState.AOD.ordinal]),
                blurPx, wallpaperScale,
                pair, cropX, cropY, cropSize, cropAngle,
                revealPhoto = reveal,
            )
        }.onFailure {
            logThrottled("world-draw-fail", "照片绘制失败 ${it.javaClass.simpleName}")
        }
        values[PhotoStyle.SLOT_ROTATION] = savedRotation
        logThrottled(
            "world-photo",
            "style=${photo.folder} state=$state edge=$edge t=${"%.3f".format(t)} " +
                "frame=$index scale=${"%.3f".format(values[PhotoStyle.SLOT_SCALE])} " +
                "alpha=${"%.2f".format(values[PhotoStyle.SLOT_PHOTO_ALPHA])} " +
                "src=${if (stylePhotoOrNull() != null) "photo" else "wall"} " +
                "pair=${pair != null} crop=${"%.2f".format(cropX)},${"%.2f".format(cropY)}," +
                "${"%.2f".format(cropSize)},${"%.1f".format(cropAngle)} " +
                "cx=${"%.3f".format(center.first)} cy=${"%.3f".format(center.second)} " +
                "follow=${WorldCenterPolicy.photoFollowsClock(photo.id)} live=${live != null}",
        )
        return true
    }

    /**
     * 揭开目标底图：photoAlpha<1 时内容要混向目标态画面，否则 shader 会混到黑。
     *
     * 宿主内容层不在本层绘制范围内，故目标快照取壁纸底（含系统缩放）；
     * 按目标态+尺寸+壁纸身份判重，输入没变就复用，不逐帧建位图。
     */
    private fun revealBackdrop(
        bmp: Bitmap,
        width: Float,
        height: Float,
        photoAlpha: Float,
        target: SceneState,
        zoom: Float,
    ): Bitmap? {
        if (!PhotoRevealPolicy.needed(photoAlpha, target)) {
            logThrottled(
                "world-reveal-off",
                "alpha=${"%.3f".format(photoAlpha)} target=$target",
            )
            return null
        }
        val key = PhotoRevealPolicy.keyOf(
            target, width.toInt(), height.toInt(), System.identityHashCode(bmp),
        ) + ":${"%.3f".format(zoom)}"
        val cached = revealCache
        if (cached != null && revealKey == key && !cached.isRecycled) {
            logThrottled("world-reveal-hit", "target=$key")
            return cached
        }
        if (cached != null && revealKey == key && cached.isRecycled) {
            revealKey = null
            logThrottled("world-reveal-stale", "缓存已回收，重建 target=$target")
        }
        val rw = PhotoRevealPolicy.snapshotSide(width)
        val rh = PhotoRevealPolicy.snapshotSide(height)
        val shot = runCatching {
            val out = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            val off = Canvas(out)
            off.scale(rw / width, rh / height)
            drawBackdrop(off, width, height, bmp, zoom)
            out
        }.getOrNull() ?: run {
            logThrottled("world-reveal-fail", "快照建失败 ${rw}x$rh")
            return null
        }
        photoRenderer?.forgetReveal()
        revealCache?.recycle()
        revealCache = shot
        revealKey = key
        logThrottled("world-reveal-build", "target=$target size=${rw}x$rh alpha=${"%.3f".format(photoAlpha)}")
        return shot
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

    /**
     * 画手绘世界：完整轨迹累积重放，画过的保留，停笔不占时长。
     *
     * 轨迹从设备息屏应用取（厂商原件不随包分发），解析在后台单飞；
     * 未就绪时返回 false 交回上层走遮罩路径，就绪回调触发重绘。
     */
    fun drawHandPaint(
        canvas: Canvas,
        width: Float,
        height: Float,
        spec: AodSceneSpec,
        state: SceneState,
        edge: TransitionEdge?,
        dim: Float,
        aodAlpha: Float,
        wallpaperScale: Float = 1f,
        useSystemWallpaper: Boolean = true,
        onBackdrop: () -> Unit = NOOP,
        onFrameReady: () -> Unit,
    ): Boolean {
        val style = spec.handPaintStyle
        if (style <= 0 || width <= 0f || height <= 0f) return false
        val ctx = Lg.processContext() ?: run {
            logThrottled("world-noctx", "进程 context 为空，世界层不画")
            return false
        }
        val silks = handPaintSilks ?: run {
            requestHandPaint(style, ctx, onFrameReady)
            logThrottled("world-handpaint-pending", "手绘轨迹未就绪 style=$style")
            return false
        }
        val silk = silks.firstOrNull() ?: return false
        val density = runCatching { ctx.resources.displayMetrics.density }.getOrDefault(1f)
        val r = ensureHandPaintRenderer(HandPaintSource.canvasPx(density).toInt()) ?: return false
        val now = SystemClock.uptimeMillis()
        val still = edge == null && state == SceneState.AOD
        if (still && HandPaintTimeline.start(handPaintPlay, style, now, silk.points.size)) {
            Lg.i(TAG, "world-handpaint-start style=$style points=${silk.points.size} dur=${handPaintPlay.durationMs}ms")
        }
        val progress = if (still) HandPaintTimeline.progressAt(handPaintPlay, now) else 1f
        val added = r.advance(style, silk, progress)
        if (added > 0) {
            logThrottled(
                "world-handpaint",
                "style=$style added=$added drawn=${r.drawn()}/${silk.points.size} p=${"%.3f".format(progress)} still=$still",
            )
        }
        val base = drawWallpaperBase(
            canvas, width, height, dim, wallpaperScale, useSystemWallpaper, onBackdrop,
        )
        logThrottled(
            "world-handpaint-base",
            "style=$style base=$base zoom=${"%.3f".format(wallpaperScale)} dim=${"%.2f".format(dim)} " +
                "src=${backdropSourceTag(useSystemWallpaper)} sys=$useSystemWallpaper",
        )
        presentHandPaint(canvas, width, height, r, aodAlpha.coerceIn(0f, 1f), density)
        if (still && HandPaintTimeline.needsFrame(handPaintPlay, now)) onFrameReady()
        return true
    }

    /**
     * 贴累积画布：矩形与预览侧共用引擎口径，按官方 dp 边长折算的小方图。
     *
     * 官方画布不是整屏，故不能按目标短边铺满。
     */
    private fun presentHandPaint(
        canvas: Canvas,
        width: Float,
        height: Float,
        r: HandPaintRenderer,
        alpha: Float,
        density: Float,
    ) {
        val rect = HandPaintGeometry.presentRect(
            HandPaintSource.canvasPx(density), HandPaintSource.CENTER_Y, width, height,
        )
        r.present(canvas, rect[0], rect[1], rect[2], alpha)
    }

    /** 手绘播放是否还在推进：供挂载方决定要不要续帧（播完即停）。 */
    fun handPaintPlaying(): Boolean =
        handPaintSilks != null && HandPaintTimeline.needsFrame(handPaintPlay, SystemClock.uptimeMillis())

    /**
     * 指针时钟淡入系数：笔画重放到官方延迟后才出时钟。
     *
     * 非息屏静止态恒 1（转场与锁屏/桌面直接显示完整时钟）。
     */
    fun handPaintClockFade(state: SceneState, edge: TransitionEdge?): Float {
        if (edge != null || state != SceneState.AOD) return 1f
        if (handPaintSilks == null || !handPaintPlay.running) return 1f
        val elapsed = SystemClock.uptimeMillis() - handPaintPlay.originMs
        return HandPaintSource.clockFadeAt(elapsed)
    }

    /** 轨迹按需解析：后台单飞，完成回调重绘；失败记账避免每帧重试。 */
    private fun requestHandPaint(style: Int, ctx: android.content.Context, onReady: () -> Unit) {
        if (handPaintRequested == style) return
        if (!handPaintLoading.compareAndSet(false, true)) return
        handPaintRequested = style
        val dir = runCatching { ctx.cacheDir }.getOrNull()
        runCatching {
            worker.execute {
                try {
                    val silks = DeviceHandPaint.obtain(style, dir)
                    if (silks != null) handPaintSilks = silks
                } catch (t: Throwable) {
                    Lg.w(TAG, "world-handpaint-load-fail style=$style ${t.javaClass.simpleName}")
                } finally {
                    handPaintLoading.set(false)
                    runCatching { onReady() }
                }
            }
        }.onFailure {
            handPaintLoading.set(false)
            logThrottled("world-handpaint-noworker", "解析任务未入队 ${it.javaClass.simpleName}")
        }
    }

    /** 累积渲染器按画布边长建一次，尺寸变了才重建。 */
    private fun ensureHandPaintRenderer(requested: Int): HandPaintRenderer? {
        val side = requested.coerceIn(1, HandPaintRenderer.MAX_SIDE)
        val existing = handPaintRenderer
        if (existing != null && handPaintSide == side) return existing
        runCatching { existing?.close() }
        val created = runCatching { HandPaintRenderer(side) }.getOrNull() ?: run {
            logThrottled("world-handpaint-norenderer", "手绘渲染器创建失败 side=$side")
            return null
        }
        handPaintRenderer = created
        handPaintSide = side
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
        releaseStylePhoto()
        stylePhotoUri = ""
        stylePhotoLoading.set(false)
        releaseFramePhoto()
        framePhotoUri = ""
        framePhotoLoading.set(false)
        releaseSceneSky()
        sceneSkyLoading.set(false)
        sceneSkyMissLogged = false
        runCatching { sequence?.close() }
        sequence = null
        sequenceStyleId = -1
        photoFrame = null
        photoRenderer?.forgetReveal()
        photoRenderer = null
        photoValues.reset()
        runCatching { revealCache?.recycle() }
        revealCache = null
        revealKey = null
        runCatching { handPaintRenderer?.close() }
        handPaintRenderer = null
        handPaintSide = 0
        handPaintSilks = null
        handPaintRequested = -1
        handPaintLoading.set(false)
        handPaintPlay.reset()
        DeviceHandPaint.reset()
        throttleAt.clear()
        pose.reset()
    }
}
