package com.coloros16.liquidglass.hook.aod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import com.coloros16.liquidglass.engine.EffectOverlayPolicy
import com.coloros16.liquidglass.engine.OverlaySlot
import com.coloros16.liquidglass.engine.SingleFlightPending
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureSpec
import com.coloros16.liquidglass.hook.HookAssetSource
import com.coloros16.liquidglass.hook.RuntimeWorkPolicy
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.ui.aod.AodBitmap
import com.coloros16.liquidglass.ui.aod.EffectTextureRenderer
import com.coloros16.liquidglass.ui.aod.MeizuGlassRenderer
import com.coloros16.liquidglass.ui.aod.TiltShiftRenderer
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 壁纸效果层构建：与预览同一条四类分派（魅族玻璃 / 贴图折射 / 景深 / 模糊）。
 *
 * 底图像素由 [AodWorldLayer.backdropOrNull] 提供（与世界层同一张：照片/壁纸/场景底图）；
 * 取不到时贴图档退回「只叠玻璃染色与高光」，其余三类降级为不画并记日志。
 * 事件驱动按需构建，单飞 + 效果/强度/尺寸/底图身份键去重，无轮询。
 */
object AodEffectOverlay {

    private const val TAG = "LgAodEffect"
    private const val THROTTLE_MS = 2000L
    private const val BLUR_RADIUS_PX = 18f
    private const val FOG_GRAY_PATH = "effect/meizu/fog.png"

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val worker = RuntimeWorkPolicy.executor("lg-aod-effect")
    private val loading = AtomicBoolean(false)

    @Volatile private var overlay: Bitmap? = null

    @Volatile private var overlayKey: String = ""

    @Volatile private var api: XposedInterface? = null

    /** 在途构建期间收到的新请求；构建收尾时补做一次，避免换档被静默丢弃。 */
    private class Pending(
        val key: String,
        val effectId: String?,
        val specialId: String?,
        val intensity: Float,
        val wall: Bitmap?,
        val w: Int,
        val h: Int,
        val onReady: () -> Unit,
    )

    private val pending = SingleFlightPending<Pending>()

    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 64) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }

    fun bind(api: XposedInterface) {
        this.api = api
    }

    /**
     * 当前可用叠加层；键不匹配返回 null（构建中先用旧层，不阻塞绘制）。
     *
     * 读写顺序相反：写方先键后图，读方先图后键，保证取到的图不早于该键。
     */
    fun peek(
        effectId: String?,
        specialId: String?,
        intensity: Float,
        wall: Bitmap?,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap? {
        val want = keyOf(effectId, specialId, intensity, wall, widthPx, heightPx)
        val current = overlay
        val key = overlayKey
        if (!OverlaySlot.usable(key, want, current != null, current?.isRecycled == true)) return null
        return current
    }

    /**
     * 请求构建：键相同直接返回，构建中不重复排队。
     *
     * 返回 true 表示已排队（调用方可在新层就绪后重绘）。
     */
    fun request(
        effectId: String?,
        specialId: String?,
        intensity: Float,
        wall: Bitmap?,
        widthPx: Int,
        heightPx: Int,
        onReady: () -> Unit,
    ): Boolean {
        val kind = MeizuGlassRenderer.kindOf(effectId)
        val spec = EffectTextureCatalog.find(effectId)
        val blurred = effectId == EffectCatalog.LOCK_WP_BLUR
        val depth = specialId == EffectCatalog.LOCK_SP_BOKEH
        if (kind == null && spec == null && !blurred && !depth) {
            if (effectId != null && effectId != EffectCatalog.LOCK_WP_NONE) {
                logThrottled("effect-nokind", "该效果无可用画法 id=$effectId sp=$specialId")
            }
            clear()
            return false
        }
        if (widthPx <= 0 || heightPx <= 0) {
            logThrottled("effect-nosize", "尺寸未就绪 ${widthPx}x$heightPx")
            return false
        }
        if (kind != null || blurred || depth) {
            if (wall == null) {
                logThrottled("effect-nowall", "无底图，该类效果需要底图 id=$effectId sp=$specialId")
                clear()
                return false
            }
        }
        val key = keyOf(effectId, specialId, intensity, wall, widthPx, heightPx)
        val slot = overlay
        if (OverlaySlot.skipRebuild(overlayKey, key, slot != null, slot?.isRecycled == true)) return false
        if (!loading.compareAndSet(false, true)) {
            pending.offer(Pending(key, effectId, specialId, intensity, wall, widthPx, heightPx, onReady))
            logThrottled("effect-busy", "已有构建在途，登记待办 id=$effectId")
            return false
        }
        val a = api
        if (a == null) {
            loading.set(false)
            logThrottled("effect-noapi", "框架接口未绑定")
            return false
        }
        val assets = HookAssetSource.open(a)
        if (assets == null) {
            loading.set(false)
            logThrottled("effect-noassets", "模块 assets 不可用 id=$effectId")
            return false
        }
        val size = targetSize(widthPx, heightPx)
        Lg.i(
            TAG,
            "effect-build id=$effectId sp=$specialId k=$kind tex=${spec != null} " +
                "wall=${wall != null} i=$intensity src=${widthPx}x$heightPx out=${size[0]}x${size[1]}",
        )
        val accepted = RuntimeWorkPolicy.guarded({ error ->
            loading.set(false)
            Lg.w(TAG, "effect-submit-fail ${error.javaClass.simpleName}: ${error.message}")
        }) {
            worker.execute {
                val built = try {
                    render(assets, effectId, kind, spec, blurred, depth, intensity, wall, size[0], size[1])
                } catch (t: Throwable) {
                    Lg.w(TAG, "effect-render-fail ${t.javaClass.simpleName}: ${t.message}")
                    null
                }
                loading.set(false)
                if (built != null) {
                    publish(key, built)
                    Lg.i(TAG, "effect-ready id=$effectId out=${built.width}x${built.height}")
                    onReady()
                }
                drainPending()
            }
        }
        if (!accepted) {
            loading.set(false)
            drainPending()
        }
        return accepted
    }

    /**
     * 补做在途期间登记的最后一次请求。
     *
     * 只留最后一次：中间档已被更新的取代，补做也是白画。
     * 键与当前槽相同则不重排，避免自我循环。
     */
    private fun drainPending() {
        val next = pending.take() ?: return
        val slot = overlay
        if (!SingleFlightPending.shouldDrain(next.key, overlayKey, slot != null, slot?.isRecycled == true)) {
            logThrottled("effect-pending-skip", "待办与当前层一致，跳过 id=${next.effectId}")
            return
        }
        Lg.i(TAG, "effect-pending id=${next.effectId} src=${next.w}x${next.h}")
        request(next.effectId, next.specialId, next.intensity, next.wall, next.w, next.h, next.onReady)
    }

    /**
     * 四类分派与预览逐条同序：魅族玻璃 > 贴图折射 > 景深 > 模糊。
     *
     * 贴图档有壁纸底时先按需糊底再折射、再叠玻璃染色；无底图只叠染色那一半。
     * 中间位图用完即回收，入参壁纸由持有方管理不在此回收。
     */
    private fun render(
        assets: android.content.res.AssetManager,
        effectId: String?,
        kind: Int?,
        spec: EffectTextureSpec?,
        blurred: Boolean,
        depth: Boolean,
        intensity: Float,
        wall: Bitmap?,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap? {
        if (kind != null) return renderMeizu(assets, kind, intensity, wall, widthPx, heightPx)
        if (spec != null) return renderTexture(assets, spec, wall, widthPx, heightPx)
        if (wall == null) return null
        val base = Bitmap.createScaledBitmap(wall, widthPx, heightPx, true)
        val out = when {
            depth -> TiltShiftRenderer.render(base, widthPx, heightPx)
            blurred -> TiltShiftRenderer.blurred(base, BLUR_RADIUS_PX)
            else -> null
        }
        if (out == null) {
            if (base !== wall) base.recycle()
            return null
        }
        if (out !== base && base !== wall) base.recycle()
        return out
    }

    /** 魅族三档：雾花取灰度图当位移场，其余两档只用壁纸本身。 */
    private fun renderMeizu(
        assets: android.content.res.AssetManager,
        kind: Int,
        intensity: Float,
        wall: Bitmap?,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap? {
        if (wall == null) return null
        val base = Bitmap.createScaledBitmap(wall, widthPx, heightPx, true)
        val gray = if (kind == MeizuGlassRenderer.FOG) {
            EffectTextureRenderer.decode(assets, FOG_GRAY_PATH)
        } else {
            null
        }
        if (kind == MeizuGlassRenderer.FOG && gray == null) {
            logThrottled("effect-fog-nogray", "雾花灰度图缺失，回退程序化磨砂")
        }
        val out = MeizuGlassRenderer.render(base, kind, widthPx, heightPx, intensity, gray, gray)
        gray?.recycle()
        if (base !== wall) base.recycle()
        if (out == null) logThrottled("effect-meizu-fail", "魅族玻璃渲染不可用 kind=$kind")
        return out
    }

    /** 贴图档：需要背景模糊时先糊底，再按法线折射，最后叠玻璃染色与高光。 */
    private fun renderTexture(
        assets: android.content.res.AssetManager,
        spec: EffectTextureSpec,
        wall: Bitmap?,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap? {
        val source = EffectTextureRenderer.decode(assets, spec.texturePath)
        if (source == null) {
            logThrottled("effect-decode", "贴图解码失败 ${spec.texturePath}")
            return null
        }
        try {
            if (wall == null) {
                logThrottled("effect-tex-nowall", "贴图档无底图，只叠玻璃染色 id=${spec.effectId}")
                return EffectTextureRenderer.render(source, spec, widthPx, heightPx, blurred = false)
            }
            val base = Bitmap.createScaledBitmap(wall, widthPx, heightPx, true)
            val backdrop = if (EffectTextureCatalog.needsBlur(spec, false)) {
                AodBitmap.blur(base, spec.blurStep, spec.blurRadius)
            } else {
                base
            }
            val refracted = EffectTextureRenderer.refract(backdrop, source, spec, widthPx, heightPx, false)
            val glass = EffectTextureRenderer.render(source, spec, widthPx, heightPx, false)
            EffectTextureRenderer.draw(Canvas(refracted), glass, widthPx.toFloat(), heightPx.toFloat())
            glass.recycle()
            if (backdrop !== base && backdrop !== wall) backdrop.recycle()
            if (base !== wall && base !== refracted) base.recycle()
            return refracted
        } finally {
            source.recycle()
        }
    }

    /**
     * 换层：只摘引用不主动回收。
     *
     * 绘制线程可能已经取走旧层正在画，后台线程 recycle 会让它画到已回收位图上。
     * 旧层交由 GC 在无人引用后释放。
     */
    private fun publish(key: String, bitmap: Bitmap) {
        overlayKey = key
        overlay = bitmap
    }

    fun clear() {
        overlayKey = ""
        overlay = null
    }

    /** 把叠加层画进调用方给的取景框；框由 `BackdropFit` 折算，与壁纸底同框。 */
    fun draw(canvas: Canvas, bitmap: Bitmap, rect: RectF, paint: Paint) {
        canvas.drawBitmap(bitmap, null, rect, paint)
    }

    /** 键含效果/特殊效果/强度/壁纸身份：任一变了都要重建，否则滑杆与换壁纸不生效。 */
    private fun keyOf(
        effectId: String?,
        specialId: String?,
        intensity: Float,
        wall: Bitmap?,
        widthPx: Int,
        heightPx: Int,
    ): String {
        val base = EffectOverlayPolicy.keyOf(effectId, widthPx, heightPx)
        val wallId = if (wall == null) 0 else System.identityHashCode(wall)
        val wallSize = if (wall == null) "-" else "${wall.width}x${wall.height}"
        return "$base|$specialId|$intensity|$wallId|$wallSize"
    }

    private fun targetSize(widthPx: Int, heightPx: Int): IntArray =
        EffectOverlayPolicy.targetSize(widthPx, heightPx)

    fun release() {
        clear()
        pending.clear()
        api = null
    }
}