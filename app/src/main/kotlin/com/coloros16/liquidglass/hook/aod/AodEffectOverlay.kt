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
import com.coloros16.liquidglass.ui.aod.EffectTextureRenderer
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 壁纸效果叠加层构建：法线贴图 → 折射亮度 + 方向高光，产出一张可叠加的 RGBA 位图。
 *
 * 只做「叠加」不做「折射壁纸」——hook 侧拿不到壁纸像素（壁纸在 SF 层），
 * 因此只渲染与壁纸无关的那一半（玻璃染色 + 高光），几何折射留给预览侧。
 * 事件驱动按需构建，单飞 + 尺寸/效果键去重，无轮询。
 */
object AodEffectOverlay {

    private const val TAG = "LgAodEffect"
    private const val THROTTLE_MS = 2000L

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val worker = RuntimeWorkPolicy.executor("lg-aod-effect")
    private val loading = AtomicBoolean(false)

    @Volatile private var overlay: Bitmap? = null

    @Volatile private var overlayKey: String = ""

    @Volatile private var api: XposedInterface? = null

    /** 在途构建期间收到的新请求；构建收尾时补做一次，避免换档被静默丢弃。 */
    private class Pending(val key: String, val effectId: String?, val w: Int, val h: Int, val onReady: () -> Unit)

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
    fun peek(effectId: String?, widthPx: Int, heightPx: Int): Bitmap? {
        val want = keyOf(effectId, widthPx, heightPx)
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
    fun request(effectId: String?, widthPx: Int, heightPx: Int, onReady: () -> Unit): Boolean {
        val spec = EffectTextureCatalog.find(effectId)
        if (spec == null) {
            if (effectId != null && effectId != EffectCatalog.LOCK_WP_NONE) {
                logThrottled("effect-notexture", "该效果无贴图档 id=$effectId")
            }
            clear()
            return false
        }
        if (widthPx <= 0 || heightPx <= 0) {
            logThrottled("effect-nosize", "尺寸未就绪 ${widthPx}x$heightPx")
            return false
        }
        val key = keyOf(effectId, widthPx, heightPx)
        val slot = overlay
        if (OverlaySlot.skipRebuild(overlayKey, key, slot != null, slot?.isRecycled == true)) return false
        if (!loading.compareAndSet(false, true)) {
            pending.offer(Pending(key, effectId, widthPx, heightPx, onReady))
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
        Lg.i(TAG, "effect-build id=$effectId src=${widthPx}x$heightPx out=${size[0]}x${size[1]}")
        val accepted = RuntimeWorkPolicy.guarded({ error ->
            loading.set(false)
            Lg.w(TAG, "effect-submit-fail ${error.javaClass.simpleName}: ${error.message}")
        }) {
            worker.execute {
                val built = try {
                    render(assets, spec, size[0], size[1])
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
        request(next.effectId, next.w, next.h, next.onReady)
    }

    private fun render(
        assets: android.content.res.AssetManager,
        spec: EffectTextureSpec,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap? {
        val source = EffectTextureRenderer.decode(assets, spec.texturePath)
        if (source == null) {
            logThrottled("effect-decode", "贴图解码失败 ${spec.texturePath}")
            return null
        }
        return try {
            EffectTextureRenderer.render(source, spec, widthPx, heightPx, blurred = false)
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

    /** 把叠加层拉伸画满目标区域。 */
    fun draw(canvas: Canvas, bitmap: Bitmap, widthPx: Float, heightPx: Float, paint: Paint, rect: RectF) {
        rect.set(0f, 0f, widthPx, heightPx)
        canvas.drawBitmap(bitmap, null, rect, paint)
    }

    private fun keyOf(effectId: String?, widthPx: Int, heightPx: Int): String =
        EffectOverlayPolicy.keyOf(effectId, widthPx, heightPx)

    private fun targetSize(widthPx: Int, heightPx: Int): IntArray =
        EffectOverlayPolicy.targetSize(widthPx, heightPx)

    fun release() {
        clear()
        pending.clear()
        api = null
    }
}