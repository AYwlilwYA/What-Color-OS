/* DreamGlass contributors, 2026-10-29. GPL-3.0. No screen/notification capture. */
package com.coloros16.liquidglass.hook

import android.app.Application
import android.app.WallpaperManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.*
import android.view.*
import android.widget.TextView
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.hook.capture.CapturePolicy
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Uses actual attached plugin views, not a clock class resolved from the SystemUI loader.
 * Native glyph rendering, parent occlusion, content and click handlers remain authoritative.
 * No source or unsupported live wallpaper -> native appearance. AOD is always excluded.
 */
object KeyguardGlassHook {
    const val KEY_ENABLE="keyguard_glass_enable"
    const val KEY_CLOCK="keyguard_glass_clock"
    const val KEY_DATE="keyguard_glass_date"
    const val KEY_CARDS="keyguard_glass_cards"
    const val KEY_SHORTCUTS="keyguard_glass_shortcuts"
    /** 缩放跟随的最小更新间隔：系统缩放动画逐帧回调，材质重设限到约 30fps。 */
    private const val ZOOM_UPDATE_MS=33L
    private val ui=Handler(Looper.getMainLooper())
    private val commitBatch=ArrayList<Entry>()
    private var commitPosted=false
    private val entries=WeakHashMap<View,Entry>()
    private val nativeEffects=WeakHashMap<View,RenderEffect?>()
    private val asyncEffects=WeakHashMap<View,Boolean>()
    private var asyncEffectSetter:java.lang.reflect.Method?=null
    private val deoptimizedGlyphs=HashSet<Class<*>>()
    private val pendingLayout=WeakHashMap<View,Boolean>()
    private val panelRetry=WeakHashMap<View,Boolean>()
    private val liftedCardBases=WeakHashMap<View,ArrayList<Pair<View,Drawable?>>>()
    private val pluginRoots=WeakHashMap<View,Int>()
    private val nativeMounts=WeakHashMap<View,Boolean>()
    private val watchedRoots=WeakHashMap<View,Boolean>()
    private val visibleRect=Rect() // UI-thread scratch, not allocated per glyph/frame.
    private var nativeWallpaper:WeakReference<Bitmap>?=null
    private var nativeWallpaperAt=-1000L
    @Volatile private var nativeController:WeakReference<Any>?=null
    private var nativeCopy:java.lang.reflect.Method?=null
    private class WallpaperCapture(val node:RenderNode,val width:Int,val height:Int) { var inUse=false }
    private var pendingWallpaper:WallpaperCapture?=null
    @Volatile private var ownedWallpaper:Bitmap?=null
    /** 当前锁屏壁纸缩放（系统墙面动画下发）；抓屏时记录烘焙缩放，材质按差值折算采样窗。 */
    @Volatile private var wallpaperScale=1f
    @Volatile private var wallpaperScaleAtCapture=1f
    @Volatile private var liveWallpaper=false
    @Volatile private var liveWallpaperAt=-1000L
    @Volatile private var forceRefresh=false
    @Volatile private var liveFrameArmed=false
    private var lastLiveCaptureAt=0L
    /** 动态壁纸连续「画面未变」的抓帧数：只在画面确实不动时退让，一变立即回零。 */
    @Volatile private var liveStillFrames=0
    private var liveRetryCount=0
    private var zoomTrailing=false
    private var scaleAt=-1000L
    @Volatile private var scaleRoute=false
    private val worker=RuntimeWorkPolicy.executor("DreamGlass-keyguard-wallpaper")
    private val loading=AtomicBoolean(false)
    private var api:XposedInterface?=null
    private var mounted=false
    private var ownChange=false
    private var scanning=false
    private var enabled=false;private var clock=true;private var date=true;private var cards=true;private var shortcuts=true
    private var strength=1f;private var blur=6f;private var readAt=-1000L
    private var powerAt=-1000L;private var powerAllowed=false
    @Volatile private var source:Bitmap?=null
    @Volatile private var epoch=0
    private var retryAt=0L
    private var sourceKey=""
    private var loadedKey=""
    private var application:Context?=null
    private var memberAt=-1000L
    private var memberAllowed=false
    private fun membershipGranted():Boolean {
        val now=SystemClock.uptimeMillis()
        if(now-memberAt<700L)return memberAllowed
        memberAt=now
        memberAllowed=try {
            val prefs=api?.let(Prefs::read)
            prefs!=null && prefs.getBoolean(Prefs.KEY_MASTER,false) && prefs.getBoolean(Prefs.KEY_GLASS_ENABLE,true)
        } catch(_:Throwable) { false }
        return memberAllowed
    }

    fun install(api:XposedInterface,loader:ClassLoader) {
        if(mounted || Application.getProcessName()!="com.android.systemui")return
        this.api=api
        KeyguardGlyphDraw.install(api)
        try {
            // Install discovery even when disabled: the next screen-on can discover an already
            // attached plugin after enabling. Disabled consider() returns before any tree scan.
            installPluginRoutes(api,loader)
            installControllerRoute(api,loader)
            installWallpaperScaleRoute(api,loader)
            installAsyncGlyphRoute(api,loader)
            ui.post { guarded { discoverWindows() } }
            api.hook(View::class.java.getDeclaredMethod("onAttachedToWindow")).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                guarded { (chain.getThisObject() as? View)?.let(::consider) };result
            }
            api.hook(View::class.java.getDeclaredMethod("setRenderEffect",RenderEffect::class.java)).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if(ownChange || Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
                val view=chain.getThisObject() as? View
                val native=chain.getArg(0) as? RenderEffect
                var replacement:RenderEffect?=native
                guarded {
                    // Plugin roots can be plain FrameLayout/ImageView and set their original
                    // effect before getView() returns. Retain that effect for exact restoration.
                    if(view!=null) {
                        if(nativeEffects.size>=128)nativeEffects.clear()
                        nativeEffects[view]=native
                        asyncEffects[view]=false
                        entries[view]?.let { entry -> entry.originalEffect=native
                            if(entry.applied) {
                                // 字形路径的玻璃在自有节点上，视图级原生效果会覆盖节点输出，改由节点独占。
                                if(entry.role==KeyguardGlassPolicy.TEXT && KeyguardGlyphDraw.owns(view)) {
                                    replacement=null
                                    if(native!=null)RuntimeDiagnostics.mark("keyguard_glyph_native_effect",
                                        "已接管视图级效果：${view.javaClass.simpleName}#${resourceId(view)} ${view.width}x${view.height}")
                                } else replacement=entry.compose(native)
                            }
                        }
                        // 祖先容器上的原生重着色会覆盖玻璃节点输出，必须一并摘除。
                        if(native!=null && replacement!=null && coversGlassText(view)) {
                            replacement=null
                            RuntimeDiagnostics.mark("keyguard_ancestor_effect",
                                "已摘除祖先重着色：${view.javaClass.simpleName}#${resourceId(view)} ${view.width}x${view.height}")
                        }
                    }
                }
                val args=chain.getArgs().toTypedArray();args[0]=replacement;chain.proceed(args)
            }
            api.hook(View::class.java.getDeclaredMethod("setBackgroundDrawable",Drawable::class.java)).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if(ownChange || Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
                val view=chain.getThisObject() as? View
                val entry=entries[view]
                val background=chain.getArg(0) as? Drawable
                if(entry!=null && !membershipGranted())entry.restore()
                if(entry!=null && entry.role!=KeyguardGlassPolicy.TEXT) {
                    entry.originalBackground=background;entry.dirty()
                    if(entry.applied) {
                        val args=chain.getArgs().toTypedArray();args[0]=entry.background;return@intercept chain.proceed(args)
                    }
                }
                val result=chain.proceed()
                guarded { if(view!=null && entry==null && view.isAttachedToWindow)consider(view) };result
            }
            api.hook(android.widget.ImageView::class.java.getDeclaredMethod("setImageDrawable",Drawable::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    if(ownChange || Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
                    val entry=entries[chain.getThisObject() as? View]
                    if(entry!=null && !membershipGranted())entry.restore()
                    if(entry?.attachment?.imageSlot==true) {
                        entry.attachment.originalImage=chain.getArg(0) as? Drawable;entry.dirty()
                        if(entry.applied) {
                            val args=chain.getArgs().toTypedArray();args[0]=entry.background;return@intercept chain.proceed(args)
                        }
                    }
                    if(entry?.role==KeyguardGlassPolicy.SHORTCUT) {
                        (chain.getThisObject() as? View)?.let { KeyguardLayeredShortcut.unbind(it) }
                        entry.dirty()
                    }
                    chain.proceed()
                }
            mounted=true
            Lg.processContext()?.applicationContext?.let(::ensureApplication)
            ui.post { guarded { discoverWindows() } }
            RuntimeDiagnostics.mark("keyguard_hook","锁屏视图发现入口已安装；实际命中与效果待核验")
        } catch(error:Throwable) { failure(error) }
    }
    /** Theme clocks can write directly to mNativeRenderNode and bypass View.setRenderEffect.
     * Compose at that actual setter too, and restore through the last native setter route.
     * Never replace the theme plugin, glyph painter, transition blur or AOD renderer.
     */
    private fun installAsyncGlyphRoute(api:XposedInterface,loader:ClassLoader) {
        runCatching {
            val type=Class.forName("com.oplus.animation.OplusAsyncAnimatorUtils",false,loader)
            val setter=type.getDeclaredMethod("setRenderEffect",View::class.java,RenderEffect::class.java)
            check(java.lang.reflect.Modifier.isStatic(setter.modifiers) && setter.returnType==Boolean::class.javaPrimitiveType)
            asyncEffectSetter=setter
            api.hook(setter).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if(ownChange || Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
                val view=chain.getArg(0) as? View ?: return@intercept chain.proceed()
                if(!view.javaClass.name.contains("keyguard",true))return@intercept chain.proceed()
                val native=chain.getArg(1) as? RenderEffect
                var replacement=native
                guarded {
                    if(nativeEffects.size>=128)nativeEffects.clear()
                    nativeEffects[view]=native;asyncEffects[view]=true
                    entries[view]?.takeIf { it.role==KeyguardGlassPolicy.TEXT }?.let { entry ->
                        entry.originalEffect=native
                        if(entry.applied) {
                            if(KeyguardGlyphDraw.owns(view)) {
                                replacement=null
                                if(native!=null)RuntimeDiagnostics.mark("keyguard_glyph_native_effect",
                                    "已接管异步视图级效果：${view.javaClass.simpleName}#${resourceId(view)} ${view.width}x${view.height}")
                            } else replacement=entry.compose(native)
                        }
                    }
                    if(native!=null && replacement!=null && coversGlassText(view)) {
                        replacement=null
                        RuntimeDiagnostics.mark("keyguard_ancestor_effect",
                            "已摘除异步祖先重着色：${view.javaClass.simpleName}#${resourceId(view)} ${view.width}x${view.height}")
                    }
                }
                val args=chain.getArgs().toTypedArray();args[1]=replacement
                val result=chain.proceed(args)
                RuntimeDiagnostics.mark("keyguard_theme_effect","已命中主题时钟原生 RenderNode 效果入口")
                result
            }
            api.deoptimize(setter)
        }.onFailure { Lg.w("DreamGlass","theme async effect route unavailable; keep View route",it) }
    }
    /** 该视图是否为某个已接管字形时钟的祖先。 */
    private fun coversGlassText(view:View):Boolean {
        for((target,entry) in entries) {
            if(entry.role!=KeyguardGlassPolicy.TEXT || !KeyguardGlyphDraw.owns(target))continue
            var node:View?=target.parent as? View
            while(node!=null) { if(node===view)return true;node=node.parent as? View }
        }
        return false
    }
    private fun setGlyphEffect(view:View,effect:RenderEffect?) {
        view.setRenderEffect(effect)
        // A direct native update does not update View's Java-side effect cache. Keep both
        // in sync so returning from a theme transition cannot retain an obsolete effect.
        if(asyncEffects[view]==true)asyncEffectSetter?.invoke(null,view,effect)
    }
    private val pluginLoaders=HashSet<ClassLoader>()
    private fun installPluginRoutes(api:XposedInterface,loader:ClassLoader) {
        if(!pluginLoaders.add(loader))return
        try {
            val base=Class.forName("com.oplus.keyguard.plugin.AbstractSubPlugin",false,loader)
            val clock=Class.forName("com.oplus.keyguard.plugin.ClockPlugin",false,loader)
            val widget=Class.forName("com.oplus.keyguard.plugin.WidgetPlugin",false,loader)
            val method=base.getDeclaredMethod("getView",Integer.TYPE)
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                val target=result as? View
                val kind=when { clock.isInstance(chain.getThisObject())->KeyguardGlassPolicy.TEXT
                    widget.isInstance(chain.getThisObject())->KeyguardGlassPolicy.CARD;else->KeyguardGlassPolicy.NONE }
                if(target!=null && kind!=KeyguardGlassPolicy.NONE)ui.post { guarded {
                    if(kind==KeyguardGlassPolicy.TEXT)installNativeMounts(target)
                    pluginRoots[target]=kind
                    watchRoot(target)
                    RuntimeDiagnostics.mark("keyguard_plugin_view_$kind","插件已返回实际视图：${target.javaClass.simpleName}")
                    // Replace earlier descendant attachments, avoiding two filters on one glyph.
                    entries.keys.toList().filter { child ->
                        var p:View?=child.parent as? View;var inside=false
                        while(p!=null) { if(p===target) { inside=true;break };p=p.parent as? View };inside
                    }.forEach { child -> entries.remove(child)?.let { old ->
                        RuntimeDiagnostics.mark("keyguard_entry_superseded","插件根替换子挂载：${child.javaClass.simpleName}#${resourceId(child)} by ${target.javaClass.simpleName}")
                        old.restore();child.viewTreeObserver.removeOnPreDrawListener(old);child.removeOnAttachStateChangeListener(old)
                    } }
                    scanTree(target)
                } }
                result
            }
            val peer=clock.getDeclaredMethod("setPeerWallpaperBitmap",Bundle::class.java)
            val transfer=Class.forName("com.oplus.keyguard.binder.WallpaperPeerTransfer",false,loader)
            val resolve=transfer.getDeclaredMethod("resolveBitmapFromBinder",IBinder::class.java).apply { isAccessible=true }
            val instance=if(java.lang.reflect.Modifier.isStatic(resolve.modifiers))null else transfer.getField("INSTANCE").get(null)
            api.hook(peer).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                guarded {
                    preferences()
                    val bundle=chain.getArg(0) as? Bundle
                    if(bundle!=null)for(key in bundle.keySet()) {
                        val value=runCatching { bundle.get(key) }.getOrNull()
                        val image=when(value) { is Bitmap->value;is IBinder->runCatching { resolve.invoke(instance,value) as? Bitmap }.getOrNull();else->null }
                        if(image!=null) { acceptWallpaper(image);break }
                    }
                }
                result
            }
            RuntimeDiagnostics.mark("keyguard_plugin","已接入原厂时钟/组件 getView 和壁纸传递接口")
        } catch(error:Throwable) {
            RuntimeDiagnostics.mark("keyguard_plugin_error","${error.javaClass.simpleName}: ${error.message}")
            Lg.w("DreamGlass","keyguard plugin route unavailable",error)
        }
    }
    /** 原生壁纸控制器在宿主加载器里，单独安装以免被插件加载器挡掉。 */
    private fun installControllerRoute(api:XposedInterface,loader:ClassLoader) {
        try {
            val type=Class.forName("com.oplus.systemui.keyguard.wallpaper.OplusLockScreenWallpaperController",false,loader)
            val method=type.getDeclaredMethod("copyWallpaperBitmap").apply { isAccessible=true }
            nativeCopy=method
            // The controller may be constructed during SystemUIApplication.onCreate, before
            // our safe post-onCreate installation. Retain its live instance at later access.
            for(access in type.declaredMethods.filter { it.name in setOf("getWallpaperType","onKeyguardDone","onConfigChanged") })
                api.hook(access).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    chain.getThisObject()?.let { nativeController=WeakReference(it) };chain.proceed()
                }
            for(ctor in type.declaredConstructors)api.hook(ctor).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed();chain.getThisObject()?.let { nativeController=WeakReference(it) };result
            }
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                chain.getThisObject()?.let { nativeController=WeakReference(it) }
                guarded { (result as? Bitmap)?.let(::acceptWallpaper) };result
            }
            RuntimeDiagnostics.update("keyguard_controller_route","原生壁纸控制器已接入 copyWallpaperBitmap")
        } catch(error:Throwable) {
            RuntimeDiagnostics.update("keyguard_controller_route","原生壁纸控制器不可用：${error.javaClass.simpleName}")
            Lg.w("DreamGlass","native wallpaper copy route unavailable",error)
        }
    }
    /** 锁屏壁纸缩放动画会逐帧回调监听器；挂在这里拿实时缩放做采样窗折算。 */
    private fun installWallpaperScaleRoute(api:XposedInterface,loader:ClassLoader) {
        try {
            val type=Class.forName("com.oplus.systemui.keyguard.anim.OplusWallpaperAnimControllerImpl\$LockScreenWallpaperAnimController",false,loader)
            val method=type.getDeclaredMethod("notifyLockScreenWallpaperScale",Float::class.javaPrimitiveType)
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val scale=(chain.getArg(0) as? Float)?.takeIf { it.isFinite() && it>0f }
                if(scale!=null)applyWallpaperScale(scale)
                chain.proceed()
            }
            RuntimeDiagnostics.update("keyguard_wallpaper_scale_route","已接入锁屏壁纸缩放回调")
            scaleRoute=true
        } catch(error:Throwable) {
            RuntimeDiagnostics.update("keyguard_wallpaper_scale_route","锁屏壁纸缩放回调不可用：${error.javaClass.simpleName}")
            Lg.w("DreamGlass","keyguard wallpaper scale route unavailable",error)
        }
    }
    private fun applyWallpaperScale(scale:Float) {
        val next=scale.coerceIn(.5f,2f)
        if(kotlin.math.abs(next-wallpaperScale)<1e-3f)return
        wallpaperScale=next
        val now=SystemClock.uptimeMillis()
        if(now-scaleAt<ZOOM_UPDATE_MS) {
            // 尾帧兜底：动画最后一次回调常落在节流窗口内，补一次刷新保证停在终值。
            if(!zoomTrailing) {
                zoomTrailing=true
                ui.postDelayed({ zoomTrailing=false;refreshWallpaperZoom() },ZOOM_UPDATE_MS)
            }
            return
        }
        scaleAt=now
        refreshWallpaperZoom()
    }
    private fun refreshWallpaperZoom() {
        for(entry in entries.values.toList()) { entry.dirty();entry.view.get()?.invalidate() }
    }
    /** 当前是否动态壁纸：动态源没有静止帧，必须按帧刷新；1s TTL 缓存。 */
    private fun liveWallpaperNow():Boolean {
        val now=SystemClock.uptimeMillis()
        if(now-liveWallpaperAt<1000L)return liveWallpaper
        liveWallpaperAt=now
        val ctx=application
        liveWallpaper=if(ctx==null) false else try {
            WallpaperManager.getInstance(ctx).wallpaperInfo!=null
        } catch(_:Throwable) { false }
        return liveWallpaper
    }
    /** 锁屏可见且为动态壁纸时按帧回调刷新背景源；无可见元素/解锁/熄屏/停播即撤。
     *  刷新间隔走抓屏配置（bg_periodic_capture_interval_ms），失败重试走 bg_capture_retry_* 三键。 */
    private val liveFrame=Choreographer.FrameCallback {
        liveFrameArmed=false
        if(!enabled || !liveWallpaperNow() || !CapturePolicy.isPeriodicCaptureEnabled())return@FrameCallback
        val target=entries.values.firstOrNull { entry -> entry.view.get()?.let { active(it,entry.role) }==true }
            ?.view?.get() ?: return@FrameCallback
        val now=SystemClock.uptimeMillis()
        if(source==null && now<retryAt) {
            // 取不到来源：按配置的重试开关/次数上限延迟重试，避免可视期每帧空跑反射/抓屏。
            if(!CapturePolicy.bgCaptureRetryEnabled() || liveRetryCount>=CapturePolicy.bgCaptureRetryLimit())return@FrameCallback
            liveRetryCount++
            ui.postDelayed({armLiveFrame()},CapturePolicy.bgCaptureRetryMs().toLong())
            return@FrameCallback
        }
        // 画面在动→按配置间隔抓；画面连续未变→按 50ms 步进退让、上限 200ms，一变即回满速。
        val ease=(liveStillFrames*50L).coerceAtMost(200L)
        val wait=maxOf(CapturePolicy.periodicCaptureIntervalMs().toLong(),ease)
        if(now-lastLiveCaptureAt>=wait) {
            lastLiveCaptureAt=now
            forceRefresh=true
            request(target)
        }
        armLiveFrame()
    }
    /** 武装帧回调；已武装/已停用/非动态壁纸/周期刷新关闭/无可见元素时直接返回。 */
    private fun armLiveFrame() {
        if(liveFrameArmed || !enabled || !liveWallpaperNow() || !CapturePolicy.isPeriodicCaptureEnabled())return
        if(entries.values.none { entry -> entry.view.get()?.let { active(it,entry.role) }==true })return
        liveFrameArmed=true
        Choreographer.getInstance().postFrameCallback(liveFrame)
    }
    /** 立即重抓一帧背景（亮屏兜底/缩放兜底用）；保留旧帧直到新帧就绪。 */
    private fun refreshSourceOnce() {
        if(!enabled || loading.get())return
        val target=entries.values.firstOrNull { entry -> entry.view.get()?.let { active(it,entry.role) }==true }
            ?.view?.get() ?: return
        forceRefresh=true
        request(target)
    }
    private fun acceptWallpaper(image:Bitmap) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { ui.post { guarded { acceptWallpaper(image) } };return }
        LockWallpaperBackdrop.accept(image)
        preferences();if(!enabled || image.isRecycled || image.width<=0 || image.height<=0)return
        val now=SystemClock.uptimeMillis()
        if(nativeWallpaper?.get()===image && source!=null || now-nativeWallpaperAt<1000L)return
        val node=RenderNode("DreamGlass native wallpaper input")
        node.setPosition(0,0,image.width,image.height)
        val canvas=node.beginRecording(image.width,image.height)
        try { canvas.drawBitmap(image,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG)) } finally { node.endRecording() }
        pendingWallpaper?.takeIf { !it.inUse }?.node?.discardDisplayList()
        pendingWallpaper=WallpaperCapture(node,image.width,image.height)
        nativeWallpaperAt=now;nativeWallpaper=WeakReference(image)
        epoch++;source=null;ownedWallpaper=null;sourceKey="";loadedKey="";retryAt=0
        RuntimeDiagnostics.mark("keyguard_native_source","已取得 SystemUI 当前锁屏壁纸输入，不依赖文件读取权限")
        entries.values.toList().forEach { it.dirty();it.onPreDraw();it.view.get()?.invalidate() }
    }
    /** 同一主线程轮次内就绪的元素合并为一次挂载，避免逐个元素先后切成玻璃。 */
    private fun requestCommit(entry:Entry) {
        if(commitBatch.any { it===entry })return
        commitBatch.add(entry)
        if(commitPosted)return
        commitPosted=true
        ui.post {
            commitPosted=false
            val batch=ArrayList(commitBatch);commitBatch.clear()
            for(item in batch)guarded { item.commit() }
            if(batch.isNotEmpty())RuntimeDiagnostics.mark("keyguard_commit_batch","同帧挂载元素=${batch.size}")
        }
    }
    private fun guarded(block:()->Unit) { RuntimeWorkPolicy.guarded(::failure,block) }
    private fun failure(error:Throwable) {
        RuntimeDiagnostics.mark("keyguard_error","${error.javaClass.simpleName}: ${error.message}")
        retryAt=SystemClock.uptimeMillis()+RuntimeWorkPolicy.failureDelayMs(error)
        try { Lg.w("DreamGlass","keyguard glass fallback",error) } catch(_:Throwable) { }
    }
    private fun resourceId(view:View)=try { view.resources.getResourceEntryName(view.id) } catch(_:Throwable) { "" }
    private fun visibleSurface(view:View):Boolean {
        if(!view.isShown || view.width<=0 || view.height<=0)return false
        var parent:View?=view;var count=0
        while(parent!=null && count++<32) {
            if(parent.alpha<=.01f || parent.visibility!=View.VISIBLE) {
                RuntimeDiagnostics.mark("keyguard_visible_fail",
                    "${view.javaClass.simpleName}#${resourceId(view)} blocked=${parent.javaClass.simpleName}#${resourceId(parent)} alpha=${parent.alpha} vis=${parent.visibility}")
                return false
            }
            parent=parent.parent as? View
        }
        val ok=view.getGlobalVisibleRect(visibleRect)
        if(!ok)RuntimeDiagnostics.mark("keyguard_visible_fail",
            "${view.javaClass.simpleName}#${resourceId(view)} globalVisibleRect=false ${view.width}x${view.height}")
        return ok
    }
    private fun removeEntry(view:View) {
        entries.remove(view)?.let { entry ->
            RuntimeDiagnostics.mark("keyguard_entry_removed",
                "${view.javaClass.simpleName}#${resourceId(view)} attached=${view.isAttachedToWindow} shown=${view.isShown} ${view.width}x${view.height}")
            entry.restore();view.viewTreeObserver.removeOnPreDrawListener(entry);view.removeOnAttachStateChangeListener(entry)
        }
    }
    private fun watchRoot(root:View) {
        if(watchedRoots.put(root,true)!=null)return
        val weak=WeakReference(root)
        var last=-1000L
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val target=weak.get()
            val now=SystemClock.uptimeMillis()
            if(target!=null && !scanning && now-last>=200L) {
                last=now;guarded { scanTree(target) }
            }
        }
        ui.postDelayed({ guarded { weak.get()?.takeIf { it.isAttachedToWindow }?.let(::scanTree) } },1200L)
    }
    private fun names(view:View):List<String> {
        val out=ArrayList<String>();var node:View?=view;var depth=0
        while(node!=null && depth++<24) {
            out.add(node.javaClass.name+"#"+resourceId(node));out.add(node.javaClass.name)
            // Theme wrappers are called MyCustomizedImageView/FrameLayout; their superclass
            // still identifies the actual keyguard control, even when its ID is replaced.
            var base:Class<*>?=node.javaClass.superclass;var count=0
            while(base!=null && count++<5 && !base.name.startsWith("android.")) { out.add(base.name);base=base.superclass }
            node=node.parent as? View
        }
        return out
    }
    private fun discoverWindows() {
        preferences();if(!enabled)return
        val roots=android.view.inspector.WindowInspector.getGlobalWindowViews()
        for(root in roots)scanTree(root)
        if(com.coloros16.liquidglass.BuildConfig.DEBUG) {
            val lines=ArrayList<String>();var count=0
            fun describe(v:View,inside:Boolean,depth:Int) {
                if(count++>=1600 || depth>24 || lines.size>=28)return
                val name=v.javaClass.simpleName;val id=resourceId(v)
                val kg=inside || v.javaClass.name.contains("keyguard",true)
                if(kg && visibleSurface(v) && (entries[v]!=null || listOf("affordance","camera","bottom","button","widget").any { (name+id).contains(it,true) }))
                    lines.add("$name#$id ${v.width}x${v.height} visible=${v.visibility==View.VISIBLE} role=${entries[v]?.role ?: 0} applied=${entries[v]?.applied==true}")
                if(v is ViewGroup)for(i in 0 until v.childCount)describe(v.getChildAt(i),kg,depth+1)
            }
            pluginRoots.keys.toList().forEach { describe(it,true,0) }
            roots.forEach { describe(it,false,0) }
            lines.joinToString("; ").chunked(450).take(4).forEachIndexed { i,chunk -> RuntimeDiagnostics.update("keyguard_targets_$i",chunk) }
        }
        // A disabled-at-start module may have missed the wallpaper event. Read only the native
        // controller's current wallpaper once, when an active lockscreen target actually needs it.
        if(source==null && pendingWallpaper==null && entries.values.any { e -> e.view.get()?.let { active(it,e.role) }==true }) {
            nativeController?.get()?.let { controller -> runCatching {
                (nativeCopy?.invoke(controller) as? Bitmap)?.let(::acceptWallpaper)
            } }
        }
    }
    /** 稳定可见性：只看挂载与尺寸；visibility/alpha/可见矩形都是转场瞬时态（AOD↔锁屏会整树压零），不作撤挂依据。 */
    private fun stableVisible(view:View):Boolean {
        if(!view.isAttachedToWindow || view.width<=0 || view.height<=0)return false
        return true
    }
    private fun scanTree(root:View) {
        preferences();if(!enabled)return
        entries.keys.toList().filter { !stableVisible(it) }.forEach(::removeEntry)
        var count=0
        fun scan(v:View,depth:Int) {
            if(count++>=1600 || depth>24)return
            if(!v.isShown || v.alpha<=.01f)return
            consider(v)
            if(v is ViewGroup)for(i in 0 until v.childCount)scan(v.getChildAt(i),depth+1)
        }
        val before=scanning;scanning=true
        try { scan(root,0) } finally { scanning=before }
        RuntimeDiagnostics.update("keyguard_binding_count","当前登记 ${entries.size}/64；仅保留实际可见对象")
    }
    private fun trusted(view:View):Int {
        var node:View?=view;var count=0
        while(node!=null && count++<24) {
            pluginRoots[node]?.let { return it }
            node=node.parent as? View
        }
        return KeyguardGlassPolicy.NONE
    }
    private fun installNativeMounts(view:View) {
        api?.let { KeyguardNativeMounts.install(it,view.javaClass.classLoader ?: return,::registerNativeMount) }
    }
    private fun registerNativeMount(view:View) { guarded {
        if(nativeMounts.put(view,true)!=true) {
            // Promote from leaf to the explicit clock/date mount. One material per
            // subtree: native colouring happens first, our refraction happens last.
            entries.keys.toList().filter { child ->
                var p:View?=child;var inside=false
                while(p!=null) { if(p===view) { inside=true;break };p=p.parent as? View };inside
            }.forEach(::removeEntry)
        }
        consider(view)
    } }
    /** 小组件玻璃挂面板背景层，但卡片引擎视图自带不透明/磨砂底盖在玻璃上 → 掀掉子树不透明底让玻璃透出，内容图标保留。可还原。 */
    private fun liftWidgetCardBase(panel:View) {
        if(panel !is ViewGroup || panel.childCount==0)return
        val saved=ArrayList<Pair<View,Drawable?>>()
        var surface=false
        fun walk(node:View,depth:Int) {
            if(depth>6)return
            if(node is android.view.SurfaceView || node is android.view.TextureView) { surface=true;return }
            if(node!==panel) {
                node.background?.let { bg ->
                    if(bg.opacity!=PixelFormat.TRANSPARENT) { saved.add(node to bg);node.background=null }
                }
            }
            if(node is ViewGroup)for(i in 0 until node.childCount)walk(node.getChildAt(i),depth+1)
        }
        walk(panel,0)
        if(surface) { RuntimeDiagnostics.mark("keyguard_card_surface","小组件含 Surface/Texture 子视图，背景玻璃被独立 surface 遮挡：${panel.javaClass.simpleName}");return }
        if(saved.isNotEmpty()) {
            liftedCardBases[panel]=saved
            RuntimeDiagnostics.mark("keyguard_card_base_lifted","掀掉卡片引擎不透明底 ${saved.size} 层让玻璃透出：${panel.javaClass.simpleName}#${resourceId(panel)}")
        }
    }
    private fun restoreWidgetCardBase(panel:View) {
        liftedCardBases.remove(panel)?.forEach { (node,bg) -> if(node.background==null)node.background=bg }
    }
    /** 显式内面板：按类名/资源 id 识别即时卡与空卡视图（不依赖背景是否已设置）。 */
    private fun explicitWidgetPanel(root:android.view.ViewGroup):View? {
        var found:View?=null
        fun walk(node:android.view.ViewGroup,depth:Int) {
            if(found!=null || depth>8)return
            for(index in 0 until node.childCount) {
                val child=node.getChildAt(index)
                if(child.width>0 && child.height>0 && child.isShown &&
                    KeyguardGlassPolicy.isWidgetPanel(child.javaClass.name,resourceId(child))) { found=child;return }
                if(child is android.view.ViewGroup)walk(child,depth+1)
            }
        }
        walk(root,0)
        return found
    }
    /** 内面板未就绪（卡片引擎异步 addView）时挂一次性布局监听补判，触发即自摘。 */
    private fun schedulePanelRetry(root:android.view.ViewGroup) {
        if(panelRetry.size>=32 || panelRetry.put(root,true)!=null)return
        val listener=object:View.OnLayoutChangeListener {
            override fun onLayoutChange(v:View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int) {
                v.removeOnLayoutChangeListener(this);panelRetry.remove(v)
                guarded { if(v is android.view.ViewGroup)consider(v) }
            }
        }
        root.addOnLayoutChangeListener(listener)
    }
    /** 小组件插件根带外扩阴影与留白，玻璃只挂到内部真正可见的面板；找不到就不挂，绝不挂外容器。 */
    private fun innerCardPanel(root:android.view.ViewGroup):View? {
        var best:View?=null;var bestArea=0
        fun walk(node:android.view.ViewGroup,depth:Int) {
            if(depth>8)return
            for(index in 0 until node.childCount) {
                val child=node.getChildAt(index)
                val surface=child.background!=null || child.foreground!=null || child.isClickable
                if(child.width>0 && child.height>0 && surface &&
                    child.width<root.width && child.height<root.height) {
                    val aspect=child.width.toFloat()/child.height
                    val area=child.width*child.height
                    if(aspect in .55f..2.2f && area>bestArea && area>=root.width*root.height*.45f) { best=child;bestArea=area }
                }
                if(child is android.view.ViewGroup)walk(child,depth+1)
            }
        }
        walk(root,0)
        return best
    }
    private fun consider(view:View) {
        if(Looper.myLooper()!=Looper.getMainLooper() || entries.containsKey(view) || entries.size>=64 || !view.isAttachedToWindow)return
        preferences();if(!enabled)return
        if(view.javaClass.name.startsWith("com.oplus.keyguard.clock.digital."))installNativeMounts(view)
        if(view.javaClass.name.startsWith("com.oplus.keyguard.clock."))api?.let { installPluginRoutes(it,view.javaClass.classLoader) }
        if(KeyguardGlassPolicy.isClockGlyphRoot(view.javaClass.name) && view.background?.opacity!=PixelFormat.OPAQUE)
            pluginRoots[view]=KeyguardGlassPolicy.TEXT
        val path=names(view)
        val nativeMount=nativeMounts[view]==true
        var trust=if(nativeMount)KeyguardGlassPolicy.TEXT else trusted(view)
        // 小组件外格只作发现根：自身不挂玻璃，向上补 CARD 信任让内面板可达
        val cellRoot=view is ViewGroup && KeyguardGlassPolicy.isWidgetCellRoot(view.javaClass.name,resourceId(view))
        if(cellRoot) {
            if(trust==KeyguardGlassPolicy.NONE)trust=KeyguardGlassPolicy.CARD
            pluginRoots[view]=KeyguardGlassPolicy.CARD
            watchRoot(view)
        }
        if(trust==KeyguardGlassPolicy.NONE && path.none { it.contains("keyguard",true) })return
        var parent=view.parent as? View
        while(parent!=null) {
            val ancestor=entries[parent]
            // A clock plugin is one glyph RenderNode. A widget card owns only its own subtree.
            if(ancestor?.role==KeyguardGlassPolicy.TEXT || ancestor?.role==KeyguardGlassPolicy.CARD)return
            parent=parent.parent as? View
        }
        // Decide card geometry after layout: an unmeasured tall widget column must not look square.
        if(view.width<=0 || view.height<=0) {
            if(pendingLayout.size<128 && pendingLayout.put(view,true)==null) {
                view.addOnLayoutChangeListener(object:View.OnLayoutChangeListener {
                    override fun onLayoutChange(v:View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int) {
                        v.removeOnLayoutChangeListener(this);pendingLayout.remove(v);guarded { consider(v) }
                    }
                })
            }
            return
        }
        if(!scanning && view is ViewGroup && (cellRoot || view.javaClass.name.contains("KeyguardStyleClock") ||
            view.javaClass.name.contains("KeyguardStyleWidget") || view.javaClass.name.endsWith("KeyguardStatusView") ||
            view.javaClass.name.endsWith("KeyguardBottomAreaView") || resourceId(view) in setOf("keyguard_widget_container","keyguard_widget_area"))) {
            scanning=true
            try {
                var count=0
                fun scan(node:View,depth:Int) {
                    if(count++>=192 || depth>12)return
                    if(node!==view)consider(node)
                    if(node is ViewGroup)for(i in 0 until node.childCount)scan(node.getChildAt(i),depth+1)
                }
                scan(view,0)
            } finally { scanning=false }
        }
        if(!stableVisible(view))return
        if(view is SurfaceView)return
        // COS17 buttons are groups containing separate background-circle and icon ImageViews.
        // Putting a Drawable behind the group leaves the opaque native circle on top of it.
        if(view is ViewGroup && resourceId(view) in setOf("start_button","end_button") &&
            (0 until view.childCount).any { resourceId(view.getChildAt(it))=="affordance_background_circle" })return
        // A full-screen plugin container also includes wallpaper and controls, not just digits.
        // Keep it as a discovery root and attach materials only to its actual glyph descendants.
        val fullScreenGroup=view is ViewGroup && view.width>=view.resources.displayMetrics.widthPixels*.9f &&
            view.height>=view.resources.displayMetrics.heightPixels*.65f
        if(fullScreenGroup && !nativeMount)return
        val glyphCandidate=nativeMount || view is TextView || KeyguardGlassPolicy.isClockGlyphRoot(view.javaClass.name) ||
            view !is ViewGroup && listOf("clock","digit","time","hour","minute").any { (view.javaClass.simpleName+resourceId(view)).contains(it,true) }
        val role=KeyguardGlassPolicy.role(path,resourceId(view),view is TextView,view.background!=null,
            view.isClickable,view.width.toFloat()/view.height,
            if(trust==KeyguardGlassPolicy.TEXT && !glyphCandidate)KeyguardGlassPolicy.NONE else trust,
            view !is ViewGroup && view !is SurfaceView && view !is TextureView)
        if(role==KeyguardGlassPolicy.CARD && view is ViewGroup &&
            !KeyguardGlassPolicy.isWidgetPanel(view.javaClass.name,resourceId(view))) {
            // 内面板判定：显式面板视图（instant_card/InstantCardView/EmptyCardView）优先，几何启发式兜底
            val panel=explicitWidgetPanel(view) ?: innerCardPanel(view)
            if(panel==null) {
                RuntimeDiagnostics.mark("keyguard_card_skip",
                    "未找到内层面板，跳过外容器：${view.javaClass.simpleName} ${view.width}x${view.height}")
                schedulePanelRetry(view)
                return
            }
            // 外容器绝不直接挂玻璃：把内面板送进判定链（此时已布局、背景/outline 就绪）
            val savedTrust=pluginRoots[panel]
            pluginRoots[panel]=KeyguardGlassPolicy.CARD
            guarded { consider(panel) }
            if(savedTrust==null)pluginRoots.remove(panel) else pluginRoots[panel]=savedTrust
            if(entries[panel]==null) {
                RuntimeDiagnostics.mark("keyguard_card_skip",
                    "内面板未挂载（${panel.javaClass.simpleName} ${panel.width}x${panel.height}），外容器保持原样：${view.javaClass.simpleName}")
                schedulePanelRetry(view)
            }
            return
        }
        if(role==KeyguardGlassPolicy.NONE || view is android.widget.EditText)return
        if(role==KeyguardGlassPolicy.TEXT && deoptimizedGlyphs.size<32 && deoptimizedGlyphs.add(view.javaClass)) {
            var type:Class<*>?=view.javaClass
            while(type!=null && !type.name.startsWith("android.")) {
                type.declaredMethods.filter { it.name=="applyRenderEffectInternal" || it.name=="setBlurRatio" }
                    .forEach { runCatching { api?.deoptimize(it) } }
                type=type.superclass
            }
        }
        if(role==KeyguardGlassPolicy.CARD || role==KeyguardGlassPolicy.TEXT) {
            entries.keys.toList().filter { child ->
                var p:View?=child.parent as? View;var inside=false
                while(p!=null) { if(p===view) { inside=true;break };p=p.parent as? View };inside
            }.forEach { child -> entries.remove(child)?.let { old ->
                RuntimeDiagnostics.mark("keyguard_entry_superseded","祖先挂载替换子挂载：${child.javaClass.simpleName}#${resourceId(child)} by ${view.javaClass.simpleName}")
                old.restore();child.viewTreeObserver.removeOnPreDrawListener(old);child.removeOnAttachStateChangeListener(old)
            } }
        }
        ensureApplication(Lg.processContext()?.applicationContext ?: view.context.applicationContext)
        Entry(view,role).also { entry -> entries[view]=entry;view.viewTreeObserver.addOnPreDrawListener(entry);view.addOnAttachStateChangeListener(entry);entry.onPreDraw() }
        RuntimeDiagnostics.mark("keyguard_detected_$role","锁屏角色=$role，${view.javaClass.simpleName}；保留字形、子控件与点击行为")
    }
    private fun ensureApplication(context:Context) {
        if(application!=null || context.packageName!="com.android.systemui")return
        application=context
        val filter=IntentFilter().apply { addAction(Intent.ACTION_WALLPAPER_CHANGED);addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);addAction("android.intent.action.USER_SWITCHED") }
        context.registerReceiver(object:BroadcastReceiver() {
            override fun onReceive(c:Context?,intent:Intent?) { guarded {
                var rediscover=false
                if(intent?.action==Intent.ACTION_WALLPAPER_CHANGED || intent?.action=="android.intent.action.USER_SWITCHED") {
                    epoch++;source=null;ownedWallpaper=null;sourceKey="";loadedKey="";nativeWallpaper=null;retryAt=0
                    pendingWallpaper?.takeIf { !it.inUse }?.node?.discardDisplayList();pendingWallpaper=null
                    rediscover=true
                    // 壁纸切换（含动态→静态）立即重取一帧；锁屏当时不可见则有限次延迟重试兜住
                    forceRefresh=true
                    RuntimeDiagnostics.mark("keyguard_wallpaper_changed","壁纸已变更：清缓存并重取背景源（含动态→静态切换）")
                    refreshSourceOnce()
                    for(delay in longArrayOf(400L,1200L))ui.postDelayed({ guarded { refreshSourceOnce() } },delay)
                } else if(intent?.action==Intent.ACTION_SCREEN_OFF)epoch++
                else if(intent?.action==Intent.ACTION_SCREEN_ON)rediscover=true
                readAt=-1000L;powerAt=-1000L
                if(rediscover)discoverWindows()
                entries.values.toList().forEach { it.onPreDraw();it.view.get()?.invalidate() }
                if(intent?.action==Intent.ACTION_SCREEN_ON) {
                    ui.post { guarded { discoverWindows() } }
                    ui.postDelayed({ guarded { powerAt=-1000L;discoverWindows();entries.values.toList().forEach { it.view.get()?.invalidate() } } },450L)
                    // 无法接入原生缩放回调时，用亮屏后的有限次重抓兜住 AOD→锁屏的壁纸缩放。
                    if(!scaleRoute)for(delay in longArrayOf(150L,380L,700L))
                        ui.postDelayed({ guarded { refreshSourceOnce() } },delay)
                }
            } }
        },filter,Context.RECEIVER_NOT_EXPORTED)
    }
    private fun preferences() {
        if(!membershipGranted()) {
            if(enabled) { epoch++;source=null }
            enabled=false;clock=false;date=false;cards=false;shortcuts=false;readAt=-1000L
            return
        }
        val now=SystemClock.uptimeMillis();if(now-readAt<700)return
        readAt=now
        val prefs=api?.let(Prefs::read) ?: return
        val before=enabled
        enabled=prefs.getBoolean(Prefs.KEY_MASTER,false) && prefs.getBoolean(Prefs.KEY_GLASS_ENABLE,true) && prefs.getBoolean(KEY_ENABLE,true)
        RuntimeDiagnostics.update("keyguard_enabled",if(enabled)"开启" else "关闭")
        if(enabled && !before)ui.post { guarded { discoverWindows() } }
        clock=prefs.getBoolean(KEY_CLOCK,true);date=prefs.getBoolean(KEY_DATE,true)
        cards=prefs.getBoolean(KEY_CARDS,true);shortcuts=prefs.getBoolean(KEY_SHORTCUTS,true)
        blur=(try { prefs.getFloat(Prefs.KEY_LOCKSCREEN_CLOCK_BLUR,6f) } catch(_:ClassCastException) { prefs.getInt(Prefs.KEY_LOCKSCREEN_CLOCK_BLUR,6).toFloat() }).coerceIn(0f,24f)
    }
    private fun active(view:View,role:Int):Boolean {
        preferences()
        if(!enabled)return false
        val now=SystemClock.uptimeMillis()
        if(now-powerAt>=400L) {
            powerAt=now
            val power=view.context.getSystemService(PowerManager::class.java)
            powerAllowed=power?.isInteractive==true && !power.isPowerSaveMode && view.display?.state==Display.STATE_ON
        }
        // 只按稳定挂载状态判定：入场动画会瞬时压低祖先 alpha 与可见矩形。
        val allowed=when(role) {
            KeyguardGlassPolicy.TEXT-> {
                val id=resourceId(view).lowercase()
                if(id.contains("date") || id.contains("week") || id.contains("message"))date else clock
            }
            KeyguardGlassPolicy.CARD->cards
            else->shortcuts
        }
        return powerAllowed && allowed &&
            view.isShown && view.windowVisibility==View.VISIBLE
    }
    /** One owned decode + hardware blur per wallpaper/config generation, never on the main Looper. */
    private fun request(view:View) {
        val context=application ?: run {
            RuntimeDiagnostics.mark("keyguard_request_gate","skip: application=null");return
        }
        val dm=view.resources.displayMetrics
        val key="${dm.widthPixels}x${dm.heightPixels}/$blur"
        val refresh=forceRefresh
        if(!refresh && (source!=null && loadedKey==key || SystemClock.uptimeMillis()<retryAt)) {
            if(source==null)RuntimeDiagnostics.mark("keyguard_request_gate",
                "skip: retryAt in ${(retryAt-SystemClock.uptimeMillis()).coerceAtLeast(0)}ms source=null")
            return
        }
        if(!loading.compareAndSet(false,true))return
        if(refresh)forceRefresh=false
        val version=epoch;sourceKey=key;val radius=blur
        val baseScale=wallpaperScale
        val captured=pendingWallpaper?.also { it.inUse=true }
        val cached=if(refresh) null else ownedWallpaper
        val crop=Rect(0,0,dm.widthPixels,dm.heightPixels)
        val keyguardSurface=keyguardSurface(view)
        val live=liveWallpaperNow()
        try { worker.execute {
            var result:Bitmap?=null
            var raw:Bitmap?=null
            try {
                val image=if(captured!=null) {
                    val scale=minOf(1f,1600f/captured.width,1600f/captured.height)
                    HardwareBackdropBlur().use { it.render(captured.node,captured.width,captured.height,
                        (captured.width*scale).toInt().coerceAtLeast(1),(captured.height*scale).toInt().coerceAtLeast(1),.01f) { version!=epoch } }
                } else cached ?: currentWallpaper(context,crop,keyguardSurface,live) ?: return@execute
                raw=image
                if(version!=epoch)return@execute
                if(live) {
                    // 与已发布上一帧做全像素比对（复用 ownedWallpaper，不再多持一份强引用）
                    val prev=ownedWallpaper
                    liveStillFrames=if(prev!=null && !prev.isRecycled && prev.sameAs(image)) (liveStillFrames+1).coerceAtMost(8) else 0
                }
                val scale=maxOf(dm.widthPixels.toFloat()/image.width,dm.heightPixels.toFloat()/image.height)
                result=if(radius>0f)HardwareBackdropBlur().use { it.render(image,image.width,image.height,radius/scale) { version!=epoch } } else image
            } catch(error:Throwable) {
                if(error !is java.util.concurrent.CancellationException)ui.post { failure(error) }
            }
            finally {
                captured?.node?.discardDisplayList()
                val published=result;val publishedRaw=raw
                ui.post { guarded {
                    if(pendingWallpaper===captured)pendingWallpaper=null
                    loading.set(false)
                    if(version==epoch && sourceKey==key) {
                        source=published;ownedWallpaper=publishedRaw;loadedKey=key
                        if(published!=null)wallpaperScaleAtCapture=baseScale
                        if(published==null)retryAt=SystemClock.uptimeMillis()+30_000L
                        RuntimeDiagnostics.update("keyguard_source",if(published!=null)"锁屏壁纸 GPU 材质已就绪" else "尚未取得锁屏壁纸；等待原生壁纸回调，保留原生外观")
                    } else {
                        RuntimeDiagnostics.mark("keyguard_publish_stale",
                            "丢弃过期结果 epoch=$version/$epoch key=${sourceKey==key} published=${published!=null}")
                    }
                    entries.values.toList().forEach { it.onPreDraw();it.view.get()?.invalidate() }
                    if(published!=null && live) { liveRetryCount=0;armLiveFrame() }
                } }
            }
        } } catch(error:Throwable) {
            captured?.inUse=false
            loading.set(false);failure(error)
        }
    }
    /** 锁屏窗口自身的 surface，抓屏时排掉，避免把原生时钟烘进玻璃背景源。 */
    private fun keyguardSurface(view:View):android.view.SurfaceControl?=runCatching {
        val attached=view.rootSurfaceControl ?: return@runCatching null
        attached.javaClass.getDeclaredField("mSurfaceControl").apply { isAccessible=true }.get(attached) as? android.view.SurfaceControl
    }.getOrNull()
    /** 当前锁屏壁纸：动态壁纸取实时帧；静态壁纸优先原生控制器位图，其次抓屏 / 壁纸文件。 */
    private fun currentWallpaper(context:Context,crop:Rect,surface:android.view.SurfaceControl?,live:Boolean):Bitmap? {
        if(live) {
            captureWallpaper(context,crop,surface)?.let {
                RuntimeDiagnostics.mark("keyguard_source_route","display 抓屏(动态) ${it.width}x${it.height} excl=${if(surface!=null)1 else 0}")
                return it
            }
        }
        val controller=nativeController?.get();val copy=nativeCopy
        if(controller!=null && copy!=null) {
            val image=runCatching { copy.invoke(controller) as? Bitmap }.getOrNull()
            if(image!=null && !image.isRecycled && image.width>0 && image.height>0) {
                RuntimeDiagnostics.mark("keyguard_source_route","原生壁纸控制器 ${image.width}x${image.height}")
                return image
            }
        }
        captureWallpaper(context,crop,surface)?.let {
            RuntimeDiagnostics.mark("keyguard_source_route","display 抓屏 ${it.width}x${it.height} excl=${if(surface!=null)1 else 0}")
            return it
        }
        readWallpaper(context)?.let {
            RuntimeDiagnostics.mark("keyguard_source_route","锁屏壁纸文件 ${it.width}x${it.height}")
            return it
        }
        RuntimeDiagnostics.mark("keyguard_source_route","三条来源均不可用")
        return null
    }
    /** 动态壁纸没有壁纸文件，改用进程内既有抓屏路线取当前画面作玻璃背景源。 */
    private fun captureWallpaper(context:Context,crop:Rect,surface:android.view.SurfaceControl?):Bitmap? {
        if(surface==null)return null
        val scale=CapturePolicy.bgCaptureScale()
        val image=runCatching { DisplayRootChannel.captureViaDisplayToken(context,crop,scale,arrayOf(surface)) }.getOrNull()
        // 空缓冲（全透明）会表现为玻璃背景消失；丢弃并保留上一帧。
        if(image!=null && image.width>0 && image.height>0 && hasContent(image))return image
        return null
    }
    private fun hasContent(image:Bitmap):Boolean {
        val stepX=(image.width/5).coerceAtLeast(1)
        val stepY=(image.height/5).coerceAtLeast(1)
        var y=stepY/2
        while(y<image.height) {
            var x=stepX/2
            while(x<image.width) {
                if(Color.alpha(image.getPixel(x,y))>=8)return true
                x+=stepX
            }
            y+=stepY
        }
        return false
    }
    // The host must already have this privileged permission. The module app requests none.
    @android.annotation.SuppressLint("MissingPermission")
    private fun readWallpaper(context:Context):Bitmap? {
        // SystemUI may run as user 0 while another user owns the visible keyguard.
        // If the active-user context cannot be established, do not show a different user's image.
        val user=try { Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int } catch(_:Throwable) { return null }
        val userContext=if(user==android.os.Process.myUid()/100000)context else try {
            Context::class.java.getMethod("createContextAsUser",UserHandle::class.java,Integer.TYPE)
                .invoke(context,UserHandle.getUserHandleForUid(user*100000),0) as Context
        } catch(_:Throwable) { return null }
        val wm=WallpaperManager.getInstance(userContext)
        val internal=context.checkSelfPermission("android.permission.READ_WALLPAPER_INTERNAL")==PackageManager.PERMISSION_GRANTED
        val lock=if(internal)wm.getWallpaperFile(WallpaperManager.FLAG_LOCK) else null
        val descriptor=lock ?: if(internal && wm.wallpaperInfo==null)wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) else null
        descriptor ?: return null
        descriptor.use { fd ->
            val info=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            fd.dup().use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,info) }
            if(info.outWidth<=0 || info.outHeight<=0)return null
            var sample=1
            while(maxOf(info.outWidth,info.outHeight)/sample>1600)sample*=2
            return fd.dup().use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,BitmapFactory.Options().apply {
                inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888
            }) }
        }
    }
    private fun effectiveWallpaperZoom():Float {
        val base=wallpaperScaleAtCapture
        return if(base>0f)(wallpaperScale/base).coerceIn(.4f,3f) else 1f
    }
    private class Entry(view:View,val role:Int):ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        val view=WeakReference(view)
        val attachment=KeyguardGlassAttachment(view,role,nativeEffects[view],::change,::setGlyphEffect,nativeMounts[view]==true)
        var originalEffect:RenderEffect?
            get()=attachment.originalEffect
            set(value) { attachment.originalEffect=value }
        var originalBackground:Drawable?
            get()=attachment.originalBackground
            set(value) { attachment.originalBackground=value }
        val background get()=attachment.background
        private val matrix=Matrix();private val values=FloatArray(9);private val origin=IntArray(2)
        private var previous=0
        private val clearedAncestors=ArrayList<Pair<View,RenderEffect>>()
        val applied get()=attachment.applied
        fun dirty() { previous=0 }
        fun compose(native:RenderEffect?):RenderEffect?=if(membershipGranted())attachment.compose(native) else native
        private var blockedUntil=0L
        override fun onPreDraw():Boolean {
            if(!membershipGranted()) { guarded {restore()};return true }
            if(SystemClock.uptimeMillis()<blockedUntil)return true
            try { refresh() } catch(error:Throwable) {
                blockedUntil=SystemClock.uptimeMillis()+RuntimeWorkPolicy.failureDelayMs(error)
                guarded { restore() };failure(error)
            }
            return true
        }
        fun refresh() {
            val v=view.get() ?: return
            if(!active(v,role)) {
                if(applied)RuntimeDiagnostics.mark("keyguard_entry_inactive",
                    "${v.javaClass.simpleName}#${resourceId(v)} role=$role shown=${v.isShown} ${v.width}x${v.height}")
                restore();return
            }
            if(!v.isHardwareAccelerated || v.width<=0 || v.height<=0) { restore();return }
            request(v)
            armLiveFrame()
            val image=source ?: run {
                if(applied)RuntimeDiagnostics.mark("keyguard_entry_nosource",
                    "${v.javaClass.simpleName}#${resourceId(v)} role=$role loading=${loading.get()} retryIn=${(retryAt-SystemClock.uptimeMillis()).coerceAtLeast(0)}ms")
                restore();return
            }
            val hash=geometry(v,image)
            if(hash!=previous || !applied) {
                if(pendingHash!=hash) { pendingHash=hash;KeyguardGlassHook.requestCommit(this) }
            }
        }
        private var pendingHash=0
        /** 同帧批量挂载入口：几何在此时重新核对，避免布局在排队期间变化。 */
        fun commit() {
            val v=view.get() ?: return
            val wanted=pendingHash;pendingHash=0
            if(wanted==0 || wanted==previous && applied)return
            if(!active(v,role) || !v.isHardwareAccelerated || v.width<=0 || v.height<=0)return
            val image=source ?: return
            val hash=geometry(v,image)
            if(hash!=previous || !applied) {
                val first=!applied
                val outline=Outline();originalBackground?.getOutline(outline)
                if(outline.isEmpty)v.outlineProvider?.getOutline(v,outline)
                val radius=if(role==KeyguardGlassPolicy.SHORTCUT)minOf(v.width,v.height)*.5f else
                    if(outline.radius>0f)outline.radius else minOf(v.width,v.height)*.24f
                val dm=v.resources.displayMetrics
                attachment.apply(image,values,dm.widthPixels,dm.heightPixels,strength,radius,effectiveWallpaperZoom())
                previous=hash;v.invalidate()
                if(role==KeyguardGlassPolicy.CARD && KeyguardGlassPolicy.isWidgetPanel(v.javaClass.name,resourceId(v)))liftWidgetCardBase(v)
                if(role==KeyguardGlassPolicy.TEXT && KeyguardGlyphDraw.owns(v))clearAncestorEffects(v)
                if(first) {
                    RuntimeDiagnostics.mark("keyguard_material","锁屏角色=${role}已挂载GPU材质；真机外观待确认")
                    RuntimeDiagnostics.mark("keyguard_material_$role","${v.javaClass.simpleName}#${resourceId(v)} ${v.width}x${v.height}")
                    if(nativeMounts[v]==true)RuntimeDiagnostics.mark("keyguard_native_mount_applied","主题最终父层已合成玻璃：${v.javaClass.simpleName}#${resourceId(v)} ${v.width}x${v.height}")
                    if(role==KeyguardGlassPolicy.TEXT && resourceId(v).contains("digital_time"))
                        RuntimeDiagnostics.update("keyguard_digits","当前数字 GPU 材质已应用：${v.width}x${v.height}")
                    if(com.coloros16.liquidglass.BuildConfig.DEBUG && role==KeyguardGlassPolicy.TEXT && resourceId(v).contains("digital_time"))runCatching {
                        Class.forName("com.dreamglass.cos17.KeyguardGlyphAudit").getMethod("capture",View::class.java,
                            RenderEffect::class.java,RenderEffect::class.java).invoke(null,v,attachment.originalEffect,attachment.compose(null))
                    }.onFailure { RuntimeDiagnostics.mark("keyguard_glyph_audit_error","${it.javaClass.simpleName}: ${it.message}") }
                }
            }
        }
        /** 填充视图到屏幕的映射并给出挂载哈希。 */
        private fun geometry(v:View,image:Bitmap):Int {
            matrix.reset();v.transformMatrixToGlobal(matrix);matrix.getValues(values)
            v.getLocationOnScreen(origin);values[2]=origin[0].toFloat();values[5]=origin[1].toFloat()
            var hash=System.identityHashCode(image)
            for(value in values)hash=hash*31+value.toRawBits()
            hash=hash*31+v.width;hash=hash*31+v.height;hash=hash*31+strength.toRawBits()
            hash=hash*31+effectiveWallpaperZoom().toRawBits()
            if(role==KeyguardGlassPolicy.SHORTCUT && v is android.widget.ImageView)hash=hash*31+System.identityHashCode(v.drawable)
            return hash
        }
        private fun change(block:()->Unit) { ownChange=true;try { block() } finally { ownChange=false } }
        /** 玻璃字形挂载后，主动摘掉祖先容器上已存在的原生重着色，避免覆盖节点输出。 */
        private fun clearAncestorEffects(v:View) {
            var node=v.parent as? View
            while(node!=null) {
                val parent=node
                val effect=nativeEffects[parent]
                if(effect!=null && clearedAncestors.none { it.first===parent }) {
                    clearedAncestors.add(parent to effect)
                    nativeEffects[parent]=null
                    change { parent.setRenderEffect(null) }
                    RuntimeDiagnostics.mark("keyguard_ancestor_effect",
                        "主动摘除祖先重着色：${parent.javaClass.simpleName}#${resourceId(parent)} ${parent.width}x${parent.height}")
                }
                node=parent.parent as? View
            }
        }
        fun restore() {
            val v=view.get() ?: return
            pendingHash=0
            if(!applied)return
            previous=0;attachment.restore()
            if(role==KeyguardGlassPolicy.CARD)restoreWidgetCardBase(v)
            if(clearedAncestors.isNotEmpty()) {
                for((node,effect) in clearedAncestors) {
                    if(node.parent==null)continue
                    nativeEffects[node]=effect
                    change { node.setRenderEffect(effect) }
                }
                clearedAncestors.clear()
            }
        }
        override fun onViewAttachedToWindow(v:View) { }
        override fun onViewDetachedFromWindow(v:View) { guarded {
            RuntimeDiagnostics.mark("keyguard_entry_detached","${v.javaClass.simpleName}#${resourceId(v)} role=$role")
            restore();v.viewTreeObserver.removeOnPreDrawListener(this);v.removeOnAttachStateChangeListener(this)
            entries.remove(v);nativeEffects.remove(v);asyncEffects.remove(v);nativeMounts.remove(v)
        } }
    }
}
