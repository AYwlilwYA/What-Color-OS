package com.coloros16.liquidglass.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.coloros16.liquidglass.engine.SeedlingAnimType
import com.coloros16.liquidglass.engine.SeedlingTakeoverMotion
import com.coloros16.liquidglass.engine.SeedlingTransitionEvent
import com.coloros16.liquidglass.log.Lg
import java.util.concurrent.ConcurrentHashMap

/**
 * 流体云接管独立窗口渲染器。
 *
 * 参考实现真值（smali 取证）：WindowManager.addView 建独立窗口，与宿主状态栏窗口分离。
 * 我们照此：type=TYPE_STATUS_BAR_SUB_PANEL(2017)（SystemUI 进程 system uid 可用，浮在状态栏 2000 之上），
 * gravity=TOP|CENTER_HORIZONTAL，cutoutMode=ALWAYS，硬件加速+不焦点+布局无限制，format=TRANSLUCENT。
 * 窗口几何随岛内容 updateViewLayout（不重建）；接管关/热重载 removeViewImmediate。
 *
 * 岛本体完全自绘：玻璃背景走本仓管线（BlurDrawHook.drawSeedlingGlassOnCanvas 同款契约，SF claim 必过），
 * 内容层第一批先画占位（媒体/计时等系统事件内容接入归后续批）。动画喂 engine/SeedlingTakeoverMotion。
 */
object SeedlingIslandWindow {

    private const val TAG = "LiquidGlass"
    private const val THROTTLE_MS = 2000L

    /** 系统窗口类型：STATUS_BAR_SUB_PANEL，浮在状态栏之上（SystemUI system uid 可用）。 */
    private const val WINDOW_TYPE = 2017

    /** 窗口标题（诊断 dumpsys window 可辨）。 */
    private const val WINDOW_TITLE = "LgIsland"

    /** 胶囊静止高度 dp（参考 style 真值换算；按内容自适应）。 */
    private const val CAPSULE_HEIGHT_DP = 30f
    private const val CAPSULE_MIN_WIDTH_DP = 100f

    /** 大卡内容基准高 440dp（参考几何计算器真值），媒体卡按内容收敛到 180dp。 */
    private const val CARD_HEIGHT_MEDIA_DP = 180f
    private const val CARD_HEIGHT_COMPACT_DP = 120f
    /** 大卡左右边距（原生实测 1080 屏卡宽 984px=328dp）。 */
    private const val CARD_MARGIN_DP = 16f

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var windowManager: WindowManager? = null

    @Volatile
    private var islandView: IslandView? = null

    @Volatile
    private var layoutParams: WindowManager.LayoutParams? = null

    @Volatile
    private var attached = false

    @Volatile
    private var density = 3f

    @Volatile
    private var screenW = 1440

    /** 当前 morph 进度（0=胶囊 1=大卡），动画驱动。 */
    @Volatile
    private var morphU = 0f

    /** 测试隔离：true 暂禁 S6 面板收起规矩（验大卡手势绕开玻璃线面板态 stale，默认 false 不影响生产）。 */
    @Volatile
    var s6Disabled = false

    /** 当前活动内容（媒体等真实内容；null=占位）。 */
    @Volatile
    var activity: com.coloros16.liquidglass.engine.SeedlingActivity? = null
        private set

    /** 打开来源 App：按包名取启动 Intent（NEW_TASK），无启动入口返回 false。 */
    private fun openSourceApp(pkg: String?): Boolean {
        if (!com.coloros16.liquidglass.engine.SeedlingOpenSourcePolicy.launchablePackage(pkg)) return false
        val ctx = BlurDrawHook.resolveSystemUiContext() ?: Lg.processContext() ?: return false
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg!!)
            if (intent == null) {
                Lg.i(TAG, "seedling-island: open source skipped, no launch intent pkg=$pkg")
                return false
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            Lg.i(TAG, "seedling-island: open source pkg=$pkg")
            true
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-island: open source failed pkg=$pkg (${t.message})")
            false
        }
    }

    /** 展开卡下滑=小窗打开来源（card_swipe_floating 规则消费）；不可展开活动单击同此路径。 */
    private fun openSourceForActivity(a: com.coloros16.liquidglass.engine.SeedlingActivity?): Boolean {
        val act = a ?: return false
        if (SeedlingNotifSource.ownsActivity(act.id)) {
            if (SeedlingNotifSource.openContentIntent(act.id)) return true
        }
        return openSourceApp(act.source)
    }

    /** 三槽调度器：主岛=activity，左右副岛=其余活动（批 D）。 */
    private val scheduler = com.coloros16.liquidglass.engine.SeedlingScheduler()

    @Volatile
    private var satellites: List<com.coloros16.liquidglass.engine.SeedlingActivity> = emptyList()

    /** OP 竖排候选快照（relayout 事件驱动更新，draw 只读不每帧重算）。 */
    @Volatile
    private var stackCandidates: List<com.coloros16.liquidglass.engine.SeedlingActivity> = emptyList()

    @Volatile
    private var milestoneShownAtMs = 0L

    /** 大卡自动收起计时（5s；按住暂停；长按临时展开=永不自动收）。 */
    private val autoCollapse = com.coloros16.liquidglass.engine.SeedlingAutoCollapse()
    private var autoCollapseHoldTemp = false
    private val autoCollapseRunnable = Runnable {
        autoCollapse.cancel()
        logThrottled("island-autocollapse", "seedling-island: auto collapse fired")
        collapseToCapsule()
    }

    /** 划掉隐藏后可召回：摄像头周边 12dp 是召回热区（胶囊态常驻可触）。 */
    private var recallArmed = false
    private var recallRect = android.graphics.RectF()

    /** 无操作隐藏（idle_hide 默认关；开启后 180s 无交互撤窗，交互重置）。 */
    private val idleHide = com.coloros16.liquidglass.engine.SeedlingIdleHide()
    private val idleHideRunnable = Runnable {
        if (idleHide.shouldHide(SystemClock.uptimeMillis())) {
            Lg.i(TAG, "seedling-island: idle hide fired")
            removeInternal()
        }
    }

    private fun rescheduleIdleHide() {
        mainHandler.removeCallbacks(idleHideRunnable)
        idleHide.enabled = SeedlingTakeoverHook.takeoverIdleHide()
        val due = idleHide.dueInMs(SystemClock.uptimeMillis()) ?: return
        mainHandler.postDelayed(idleHideRunnable, due + 20L)
    }

    /** 内容更新入口：媒体源等喂活动；接管开时确保窗口在屏并重绘。null=按来源路撤活动（防误撤别路）。 */
    fun onActivity(a: com.coloros16.liquidglass.engine.SeedlingActivity?, ctx: Context?, sourceTag: String = "demo") {
        val prevId = activity?.id
        val prevLabel = activity?.compact?.label
        if (a != null) {
            if (a.expanded.milestone) milestoneShownAtMs = SystemClock.uptimeMillis()
            // 晃动提醒：里程碑或高优先（≥85）更新触发（胶囊态才有视觉意义）
            if ((a.expanded.milestone || a.priority >= 85) && morphU < 0.3f) startShake()
            scheduler.upsert(a)
        } else {
            val prefix = when (sourceTag) {
                "media" -> "media:"
                "recording" -> "recording:"
                "notif" -> "notif:"
                "data" -> "seedling:"
                "status" -> "status:"
                else -> "demo:"
            }
            val n = scheduler.removeByIdPrefix(prefix)
            logThrottled("island-source-remove", "seedling-island: source=$sourceTag removed n=$n")
        }
        val layout = relayout()
        activity = layout.main
        satellites = listOfNotNull(layout.left, layout.right)
        recallArmed = scheduler.hiddenCount() > 0
        idleHide.touch(SystemClock.uptimeMillis())
        rescheduleIdleHide()
        // S2 出现/内容变化过冲 + S5 交叉淡化（胶囊态、小位移；大卡 morph 不走）
        val newMain = layout.main
        if (newMain != null && morphU < 0.3f) {
            if (newMain.id != prevId) {
                laneEpochMs = SystemClock.uptimeMillis()
                startCapsuleAppear()
            } else if (newMain.compact.label != prevLabel) {
                laneEpochMs = SystemClock.uptimeMillis()
                startContentCrossfade(prevLabel ?: "")
            }
        }
        // alertOnStart：独占/验证码/里程碑新活动自动弹大卡（事件驱动一次性过渡）
        if (a != null && newMain != null && newMain.id == a.id && morphU < 0.3f &&
            com.coloros16.liquidglass.engine.SeedlingAlertPolicy.shouldAutoExpand(a, a.id != prevId, prevId)
        ) {
            Lg.i(TAG, "seedling-island: auto expand alert id=${a.id} kind=${a.kind}")
            mainHandler.postDelayed({
                if (activity?.id == a.id && morphU < 0.3f) toggleMorph()
            }, 350L)
        }
        if (layout.main != null && ctx != null && SeedlingTakeoverHook.takeoverEnabled()) {
            ensureShown(ctx)
        } else if (layout.isEmpty) {
            mainHandler.post { removeInternal() }
        } else {
            mainHandler.post { islandView?.invalidate() }
        }
    }

    @Volatile
    private var appearEnvelope: com.coloros16.liquidglass.engine.SeedlingOvershootEnvelope? = null

    @Volatile
    private var appearStartMs = 0L

    @Volatile
    private var crossfade: com.coloros16.liquidglass.engine.SeedlingCrossfade? = null

    @Volatile
    private var crossfadeStartMs = 0L

    @Volatile
    private var crossfadeOldLabel = ""

    /** 长文字滚动周期起点：换内容/换文案时重置。 */
    @Volatile
    private var laneEpochMs = 0L

    /** 晃动提醒：里程碑/高优先更新触发欠阻尼弹簧横向偏移（k1200 ζ0.18 真值）。 */
    private val shake = com.coloros16.liquidglass.engine.SeedlingShake()
    @Volatile
    private var shakeStartMs = 0L
    private val shakeFrame = object : Runnable {
        override fun run() {
            val v = islandView ?: return
            val elapsed = SystemClock.uptimeMillis() - shakeStartMs
            v.shakeOffsetPx = shake.offsetAt(elapsed)
            v.invalidate()
            if (!shake.settled(elapsed)) mainHandler.postDelayed(this, 16L) else v.shakeOffsetPx = 0f
        }
    }

    private fun startShake() {
        shakeStartMs = SystemClock.uptimeMillis()
        mainHandler.removeCallbacks(shakeFrame)
        mainHandler.post(shakeFrame)
        logThrottled("island-shake", "seedling-island: shake triggered")
    }

    /** S2：胶囊出现/换活动——双段过冲（+16dp宽/+3dp高，300+500ms，内容 scale1.1+blur9→0）。 */
    private fun startCapsuleAppear() {
        val v = islandView ?: return
        val d = density
        val targetW = capsuleWidthForContent()
        val targetH = CAPSULE_HEIGHT_DP * d
        appearEnvelope = com.coloros16.liquidglass.engine.SeedlingOvershootEnvelope(
            fromWidth = (targetW * 0.6f).coerceAtLeast(20f * d),
            toWidth = targetW,
            fromHeight = targetH,
            toHeight = targetH,
            extraWidthPx = 16f * d,
            extraHeightPx = 3f * d,
        )
        appearStartMs = SystemClock.uptimeMillis()
        mainHandler.post(appearFrame)
    }

    private val appearFrame = object : Runnable {
        override fun run() {
            val env = appearEnvelope ?: return
            val s = env.sample(SystemClock.uptimeMillis() - appearStartMs)
            val v = islandView ?: return
            v.islandW = s.width
            v.islandH = s.height
            v.contentScale = s.contentScale
            v.contentBlurDp = s.contentBlurDp
            v.invalidate()
            v.requestLayout()
            if (!s.done) mainHandler.postDelayed(this, 16L) else appearEnvelope = null
        }
    }

    /** S5：同活动文字变化——200ms 交叉淡化（旧文淡出新文淡入）。 */
    private fun startContentCrossfade(oldLabel: String) {
        crossfadeOldLabel = oldLabel
        crossfade = com.coloros16.liquidglass.engine.SeedlingCrossfade(200L)
        crossfadeStartMs = SystemClock.uptimeMillis()
        mainHandler.post(crossfadeFrame)
    }

    private val crossfadeFrame = object : Runnable {
        override fun run() {
            val cf = crossfade ?: return
            val s = cf.sample(SystemClock.uptimeMillis() - crossfadeStartMs)
            val v = islandView ?: return
            v.crossfadeFromAlpha = s.fromAlpha
            v.crossfadeToAlpha = s.toAlpha
            v.invalidate()
            if (!s.done) mainHandler.postDelayed(this, 16L) else {
                crossfade = null
                v.crossfadeFromAlpha = 0f
                v.crossfadeToAlpha = 1f
            }
        }
    }

    @Volatile
    private var capsuleWidthPx = 360f

    @Volatile
    private var cardWidthPx = 1000f

    private fun logThrottled(key: String, msg: String, intervalMs: Long = THROTTLE_MS) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last == null || now - last >= intervalMs) {
            throttleAt[key] = now
            if (throttleAt.size > 256) throttleAt.clear()
            Lg.i(TAG, "[$key] $msg")
        }
    }

    /** 接管开 + 有岛内容时确保窗口在屏（幂等）。由 SeedlingTakeoverHook 事件驱动调用。 */
    fun ensureShown(ctx: Context) {
        mainHandler.post { showInternal(ctx) }
    }

    /** 场景门控显隐：规则不满足撤窗（活动内容保留，场景恢复再 ensureShown）。 */
    fun onSceneVisibilityChanged(visible: Boolean) {
        mainHandler.post {
            // 配置变化（旋转等）挖孔可能移位：事件驱动重取
            refreshCutout()
            if (!visible) {
                if (attached) {
                    Lg.i(TAG, "seedling-island: scene gate hidden (rules)")
                    removeInternal()
                }
            } else if (activity != null) {
                val ctx = BlurDrawHook.resolveSystemUiContext() ?: Lg.processContext()
                if (ctx != null) showInternal(ctx)
            }
        }
    }

    private fun showInternal(ctx: Context) {
        try {
            val appCtx = ctx.applicationContext ?: ctx
            val wmgr = windowManager
                ?: (appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?.also { windowManager = it }
            if (wmgr == null) {
                logThrottled("island-win-nowm", "seedling-island: WindowManager unresolved")
                return
            }
            val dm = appCtx.resources.displayMetrics
            density = dm.density
            screenW = dm.widthPixels
            capsuleWidthPx = (CAPSULE_MIN_WIDTH_DP * density).coerceAtLeast(200f)
            cardWidthPx = screenW - 2f * CARD_MARGIN_DP * density

            if (islandView == null) {
                islandView = IslandView(appCtx)
            }
            val v = islandView ?: return
            if (!attached) {
                val lp = buildLayoutParams()
                layoutParams = lp
                wmgr.addView(v, lp)
                attached = true
                Lg.i(TAG, "seedling-island: independent window attached type=$WINDOW_TYPE title=$WINDOW_TITLE")
                if (!installTouchRegion(v)) {
                    Lg.e(TAG, "seedling-island: touch region install failed, removing window (fail-safe)")
                    removeInternal()
                    return
                }
                // insets 到达事件驱动重取挖孔（首帧 rootWindowInsets 可能未就绪）
                v.setOnApplyWindowInsetsListener { _, insets ->
                    refreshCutout()
                    insets
                }
            }
            refreshCutout()
            updateGeometry()
            v.invalidate()
            // 触摸区随岛几何刷新：requestLayout 触发框架重算 internal insets（岛外始终穿透）
            v.requestLayout()
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-island: show failed", t)
            // fail-safe：addView 失败撤窗不留死窗
            removeInternal()
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.x = 0
        lp.y = 0
        lp.title = WINDOW_TITLE
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.windowAnimations = 0
        try {
            lp.setFitInsetsTypes(0)
            lp.setFitInsetsSides(0)
            lp.setCanPlayMoveAnimation(false)
        } catch (t: Throwable) {
            // 低版本无此 API，忽略
        }
        return lp
    }

    /** 胶囊宽度按内容自适应：文字宽 + 左右元素余量；有挖孔时保证包住挖孔（两侧间隙+内边距）。 */
    private fun capsuleWidthForContent(): Float {
        val act = activity
        val minW = (CAPSULE_MIN_WIDTH_DP * density).coerceAtLeast(200f)
        val base = if (act == null) minW else {
            val label = act.compact.label.ifEmpty { act.expanded.title }
            if (label.isEmpty()) minW else {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * density }
                val textW = paint.measureText(label)
                val pad = 32f * density
                (textW + pad).coerceIn(minW, screenW * 0.6f)
            }
        }
        val hole = cutoutHole
        val follow = SeedlingTakeoverHook.takeoverAnchorFollow()
        val wrap = hole != null && (follow || com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.classify(hole) ==
            com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.CutoutKind.CENTER)
        val w = if (wrap) {
            com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.capsuleWidth(
                base, hole, HOLE_GAP_DP * density, HOLE_PAD_DP * density, 8f * density, 20f * density,
            )
        } else base
        return w.coerceAtMost(screenW * 0.92f)
    }

    /** 挖孔两侧间隙 / 胶囊两端内边距（参考 style_gap_hole_dp 默认 8）。 */
    private const val HOLE_GAP_DP = 8f
    private const val HOLE_PAD_DP = 14f

    /** 过渡期岛宽（sample.widthPx 弹簧缓动通道）；null=静止态用内容自适应宽。 */
    @Volatile
    private var transitionWidthPx: Float? = null

    /** 按 morph 进度算窗口内岛几何：胶囊包挖孔（左缘按挖孔方位），大卡顶缘在挖孔下方。 */
    private fun updateGeometry() {
        val v = islandView ?: return
        val cardH = if (activity?.kind == com.coloros16.liquidglass.engine.SeedlingKind.MEDIA) {
            CARD_HEIGHT_MEDIA_DP * density
        } else {
            CARD_HEIGHT_COMPACT_DP * density
        }
        val h = lerp(CAPSULE_HEIGHT_DP * density, cardH, morphU)
        val w = transitionWidthPx ?: lerp(capsuleWidthForContent(), cardWidthPx, morphU)
        v.islandW = w
        v.islandH = h
        val hole = cutoutHole
        val centeredLeft = (screenW - w) / 2f
        val capsuleLeft = if (SeedlingTakeoverHook.takeoverAnchorFollow()) {
            com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.capsuleLeft(
                hole, screenW.toFloat(), w, HOLE_GAP_DP * density, HOLE_PAD_DP * density,
            ).coerceIn(0f, (screenW - w).coerceAtLeast(0f))
        } else centeredLeft
        // 大卡水平居中；胶囊按「跟随挖孔」开关贴边或居中
        v.islandLeftPx = lerp(capsuleLeft, centeredLeft, morphU)
        v.hole = hole
        val capTop = statusBarOffsetPx()
        val cardTop = com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.cardTop(hole, HOLE_GAP_DP * density)
        v.islandTop = lerp(capTop, cardTop, morphU)
        v.morphU = morphU
        // OP 竖排展开态：卡顶定位后按可用高自适应卡高（主卡背景/热区与副卡同高，整体不越屏）
        if (morphU > 0.5f && SeedlingTakeoverHook.takeoverCardMode() == com.coloros16.liquidglass.engine.SeedlingCardModePolicy.Mode.OP) {
            v.islandH = v.opFitCardHeight()
        }
        updateRecallRect(v)
    }

    /** 召回热区=挖孔矩形外扩 12dp；与岛本体重叠时让位（岛 tap 优先，召回只在岛外余区）。 */
    private fun updateRecallRect(v: IslandView) {
        val pad = 12f * density
        val hole = cutoutHole
        val islandLeft = v.islandLeftPx
        val islandRect = android.graphics.RectF(islandLeft, v.islandTop, islandLeft + v.islandW, v.islandTop + v.islandH)
        val raw = if (hole != null) {
            android.graphics.RectF(hole.left - pad, hole.top - pad, hole.right + pad, hole.bottom + pad)
        } else {
            val cx = v.width / 2f
            val cy = v.islandTop + v.islandH / 2f
            android.graphics.RectF(cx - 24f * density, cy - 24f * density, cx + 24f * density, cy + 24f * density)
        }
        recallRect = if (android.graphics.RectF.intersects(raw, islandRect)) android.graphics.RectF() else raw
    }

    /** 当前挖孔矩形（屏幕 px；null=无挖孔/未取到）。事件驱动刷新：insets 到达/配置变化。 */
    @Volatile
    private var cutoutHole: com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.Hole? = null

    /**
     * 自动获取摄像头位置：多级链 rootWindowInsets → Display.getCutout →
     * WindowManager.getCurrentWindowMetrics().windowInsets（全公开 API）。
     * 取到写 cutoutHole；变化时重算几何+重绘（事件驱动，零轮询）。
     */
    private fun refreshCutout() {
        val v = islandView
        // 旋转后屏宽可能变化：先刷新密度/屏宽再取孔
        val ctx = v?.context
        if (ctx != null) {
            val dm = ctx.resources.displayMetrics
            density = dm.density
            screenW = dm.widthPixels
        }
        var cutout: android.view.DisplayCutout? = v?.rootWindowInsets?.displayCutout
        if (cutout == null) {
            cutout = try {
                @Suppress("DEPRECATION")
                v?.display?.cutout
            } catch (_: Throwable) {
                null
            }
        }
        if (cutout == null) {
            cutout = try {
                windowManager?.currentWindowMetrics?.windowInsets?.displayCutout
            } catch (_: Throwable) {
                null
            }
        }
        val rect = cutout?.boundingRects?.firstOrNull()
        val old = cutoutHole
        cutoutHole = if (rect != null && rect.width() > 0) {
            com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.Hole(
                rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), screenW.toFloat(),
            )
        } else null
        if (old != cutoutHole) {
            Lg.i(TAG, "seedling-island: cutout=$cutoutHole")
            updateGeometry()
            islandView?.invalidate()
            islandView?.requestLayout()
        }
    }

    /** 岛顶部偏移：有挖孔对齐其垂直中心（水滴贴顶）；无挖孔状态栏高度附近。 */
    private fun statusBarOffsetPx(): Float {
        val hole = cutoutHole
        if (hole != null) {
            return com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.capsuleTop(hole, CAPSULE_HEIGHT_DP * density)
        }
        return 20f * density / 3f
    }

    /** 点按岛本体：胶囊↔大卡切换（参考交互：tap 展开/收起）；不可展开活动只收不展。 */
    private fun toggleMorph() {
        val expand = morphU < 0.5f
        if (expand && activity?.expandable == false) {
            logThrottled("island-noexpand", "seedling-island: expand skipped (non-expandable ${activity?.id})")
            return
        }
        val type = if (expand) SeedlingAnimType.EXPAND_OUT else SeedlingAnimType.SHRINK_IN
        onTransitionEvent(SeedlingTransitionEvent(type, longArrayOf(300L, 500L), lastPlaceholderWidthOr(), false))
    }

    /** 收起规矩入口：大卡态收胶囊（灭屏/下拉等事件驱动）。 */
    fun collapseToCapsule() {
        if (morphU > 0.05f) {
            mainHandler.post {
                onTransitionEvent(SeedlingTransitionEvent(SeedlingAnimType.SHRINK_IN, longArrayOf(300L, 500L), lastPlaceholderWidthOr(), false))
            }
        }
    }

    /** 精确撤单条活动（通知 removed 回调）：重排后空布局撤窗。 */
    fun removeActivityById(id: String) {
        mainHandler.post {
            scheduler.remove(id)
            val layout = relayout()
            activity = layout.main
            satellites = listOfNotNull(layout.left, layout.right)
            recallArmed = scheduler.hiddenCount() > 0
            Lg.i(TAG, "seedling-island: activity removed id=$id main=${layout.main?.id}")
            if (layout.isEmpty) {
                removeInternal()
            } else {
                updateGeometry()
                islandView?.invalidate()
                islandView?.requestLayout()
            }
        }
    }

    /** 小岛当前是否可见（区域抓屏判活跃用）。 */
    fun isIslandVisible(): Boolean {
        val v = islandView ?: return false
        return try {
            v.isAttachedToWindow && v.visibility == android.view.View.VISIBLE && v.width > 0
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 抓屏所需屏幕矩形：岛本体 + 副岛（WiFi 图标 / 信号点）并集，外扩一点余量。
     * 只取这一小块，避免为一个小岛抓整屏。不可见返回 null。
     */
    fun captureBoundsScreen(): android.graphics.RectF? {
        val v = islandView ?: return null
        if (!isIslandVisible()) return null
        return try {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val out = android.graphics.RectF(v.lastBodyScreenRect(loc[0], loc[1]))
            for (r in v.satelliteBoundsScreen(loc[0], loc[1])) out.union(r)
            val pad = 6f * v.resources.displayMetrics.density
            out.inset(-pad, -pad)
            out
        } catch (t: Throwable) {
            null
        }
    }

    /** 横滑划掉：进隐藏集（可召回），布局重排后无可见活动则撤窗。 */
    fun dismissIsland() {
        mainHandler.post {
            val id = activity?.id ?: return@post
            val ok = scheduler.hide(id, android.os.SystemClock.elapsedRealtime())
            if (!ok) {
                Lg.i(TAG, "seedling-island: dismiss refused (ringing timer) id=$id")
                return@post
            }
            val layout = relayout()
            activity = layout.main
            satellites = listOfNotNull(layout.left, layout.right)
            recallArmed = scheduler.hiddenCount() > 0
            Lg.i(TAG, "seedling-island: dismissed by fling id=$id hidden=${scheduler.hiddenCount()}")
            if (layout.isEmpty) {
                removeInternal()
            } else {
                updateGeometry()
                islandView?.invalidate()
                islandView?.requestLayout()
            }
        }
    }

    /** 召回被划掉的内容：清隐藏集重排（点摄像头周边热区触发）。 */
    fun recallHidden() {
        mainHandler.post {
            val n = scheduler.recallAll()
            if (n == 0) return@post
            recallArmed = false
            val layout = relayout()
            activity = layout.main
            satellites = listOfNotNull(layout.left, layout.right)
            Lg.i(TAG, "seedling-island: hidden content recalled n=$n")
            if (layout.main != null) {
                startCapsuleAppear()
                updateGeometry()
                islandView?.invalidate()
                islandView?.requestLayout()
            }
        }
    }

    /** 副岛提升为主岛：更新时间戳顶置（参考顶置语义简化为最新优先）。 */
    fun promoteSatellite(id: String) {
        mainHandler.post {
            val sat = scheduler.get(id) ?: return@post
            scheduler.upsert(sat.copy(updatedAtElapsedMs = android.os.SystemClock.elapsedRealtime()))
            val layout = relayout()
            activity = layout.main
            satellites = listOfNotNull(layout.left, layout.right)
            updateGeometry()
            islandView?.invalidate()
            islandView?.requestLayout()
        }
    }

    /** 统一重排：布局 + 展开锁定保持 + 过期闹钟重排（事件驱动一次性）。 */
    private fun relayout(): com.coloros16.liquidglass.engine.SeedlingLayout {
        val layout = scheduler.layout(
            android.os.SystemClock.elapsedRealtime(),
            kindAllowed = { SeedlingTakeoverHook.kindSwitches().allows(it) },
        )
        // 展开锁定：大卡态保持已锁活动（1000 分），收起释放；活动被 remove 时调度器自清
        scheduler.expandedId = if (morphU > 0.5f) scheduler.expandedId ?: layout.main?.id else null
        stackCandidates = listOfNotNull(layout.main, layout.left, layout.right) + layout.queued
        rescheduleExpiry()
        return layout
    }

    /** 大卡模式切换（APPLE↔OP）：重算布局并按新模式重绘。 */
    fun relayoutForModeChange() {
        mainHandler.post {
            val layout = relayout()
            activity = layout.main
            satellites = listOfNotNull(layout.left, layout.right)
            updateGeometry()
            islandView?.invalidate()
        }
    }

    /** 过期引擎：最近到期（staleAt/消息降级点）排一次性延迟重算，无轮询。 */
    private val expiryRunnable = Runnable {
        mainHandler.post {
            val prevMain = activity?.id
            val layout = relayout()
            activity = layout.main
            satellites = listOfNotNull(layout.left, layout.right)
            recallArmed = scheduler.hiddenCount() > 0
            if (layout.main?.id != prevMain) {
                Lg.i(TAG, "seedling-island: expiry relayout main=${layout.main?.id}")
                if (morphU > 0.5f && layout.main == null) collapseToCapsule()
            }
            if (layout.isEmpty) {
                removeInternal()
            } else {
                updateGeometry()
                islandView?.invalidate()
                islandView?.requestLayout()
            }
        }
    }

    private fun rescheduleExpiry() {
        mainHandler.removeCallbacks(expiryRunnable)
        val delay = com.coloros16.liquidglass.engine.SeedlingExpiry.scheduleDelayMs(
            scheduler.all(), android.os.SystemClock.elapsedRealtime(),
        ) ?: return
        mainHandler.postDelayed(expiryRunnable, delay)
        logThrottled("island-expiry-arm", "seedling-island: expiry alarm in ${delay}ms")
    }

    private fun lastPlaceholderWidthOr(): Int =
        SeedlingTakeoverHook.lastPlaceholderWidthPx.takeIf { it > 0 } ?: 430

    /** 当前过渡帧循环（单飞：新过渡取消旧循环，防多循环争写 morphU 抖动）。 */
    private var morphFrame: Runnable? = null
    private val transitionGate = com.coloros16.liquidglass.engine.SeedlingTransitionGate()

    /** 喂过渡事件 → engine 求值 → 更新 morph（事件驱动，动画期帧回调）。 */
    fun onTransitionEvent(event: SeedlingTransitionEvent) {
        val dir = SeedlingTakeoverMotion.morphDirection(event.type)
        if (dir == 0) return
        morphFrame?.let { mainHandler.removeCallbacks(it) }
        morphFrame = null
        val token = transitionGate.begin()
        if (dir > 0) {
            // 展开起手即锁主岛（动画期高优先级事件不抢卡）
            scheduler.expandedId = activity?.id
            islandView?.shakeOffsetPx = 0f
        }
        if (dir < 0) {
            autoCollapseHoldTemp = false
            autoCollapse.cancel()
            mainHandler.removeCallbacks(autoCollapseRunnable)
            scheduler.expandedId = null
        }
        val fromU = morphU
        val startMs = SystemClock.uptimeMillis()
        val speed = SeedlingTakeoverHook.takeoverSpeed()
        val duration = event.totalDurationMs(
            if (dir > 0) SeedlingTakeoverMotion.FALLBACK_EXPAND_MS else SeedlingTakeoverMotion.FALLBACK_COLLAPSE_MS,
            speed,
        )
        logThrottled("seedling-morph-begin", "seedling-morph: begin dir=$dir fromU=$fromU dur=$duration token=$token type=${event.type}", 120L)
        val frame = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.uptimeMillis() - startMs
                val sample = SeedlingTakeoverMotion.sample(
                    elapsed, event, fromU, capsuleWidthPx, cardWidthPx,
                )
                morphU = sample.u
                transitionWidthPx = sample.widthPx
                updateGeometry()
                islandView?.invalidate()
                islandView?.requestLayout()
                logThrottled("seedling-morph-progress", "seedling-morph: progress dir=$dir u=$morphU elapsed=$elapsed dur=$duration w=${sample.widthPx}", 200L)
                if (elapsed < duration) {
                    mainHandler.postDelayed(this, 16L)
                } else {
                    // 过渡完成：morph 收敛到目标端点（防弹簧未达端点留中间值）；旧循环完成回调不误清新循环
                    morphU = if (dir > 0) 1f else 0f
                    transitionWidthPx = null
                    updateGeometry()
                    islandView?.invalidate()
                    val gateDone = transitionGate.complete(token)
                    logThrottled("seedling-morph-end", "seedling-morph: end dir=$dir finalU=$morphU elapsed=$elapsed dur=$duration gateDone=$gateDone", 120L)
                    if (gateDone) {
                        morphFrame = null
                        scheduler.expandedId = if (dir > 0) activity?.id else null
                        if (dir > 0) armAutoCollapse()
                    }
                }
            }
        }
        morphFrame = frame
        mainHandler.post(frame)
    }

    /** 展开完成排自动收起（一次性延迟；长按临时展开不排）。 */
    private fun armAutoCollapse() {
        if (autoCollapseHoldTemp || !autoCollapse.enabled) return
        val now = SystemClock.uptimeMillis()
        autoCollapse.arm(now)
        val due = autoCollapse.dueInMs(now) ?: return
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.postDelayed(autoCollapseRunnable, due)
        logThrottled("island-autocollapse-arm", "seedling-island: auto collapse armed in ${due}ms")
    }

    /** 按住暂停自动收起计时；抬手按剩余时长续排。 */
    private fun pauseAutoCollapse() {
        if (!autoCollapse.isArmed()) return
        autoCollapse.pause(SystemClock.uptimeMillis())
        mainHandler.removeCallbacks(autoCollapseRunnable)
    }

    private fun resumeAutoCollapse() {
        if (!autoCollapse.isArmed()) return
        val now = SystemClock.uptimeMillis()
        autoCollapse.resume(now)
        val due = autoCollapse.dueInMs(now) ?: return
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.postDelayed(autoCollapseRunnable, due)
    }

    /** 歌词更新：把当前词行填进媒体活动的大卡内容（事件驱动重绘）。 */
    fun refreshLyrics() {
        mainHandler.post {
            val act = activity ?: return@post
            if (act.kind != com.coloros16.liquidglass.engine.SeedlingKind.MEDIA) return@post
            val line = SeedlingLyricsBridge.currentLine()
            val updated = act.copy(
                expanded = act.expanded.copy(
                    lyricsLine = line?.text ?: "",
                    lyricsTranslation = line?.translation ?: "",
                ),
            )
            scheduler.upsert(updated)
            activity = updated
            islandView?.invalidate()
        }
    }

    /** 前台应用变化：进来源隐藏（事件驱动重排，大卡态来源被隐藏自动收胶囊）。 */
    fun onForegroundChanged(pkg: String?) {
        mainHandler.post {
            scheduler.foregroundPackage = pkg
            val prevMain = activity?.id
            val layout = relayout()
            activity = layout.main
            satellites = listOfNotNull(layout.left, layout.right)
            recallArmed = scheduler.hiddenCount() > 0
            if (layout.main?.id != prevMain) {
                Lg.i(TAG, "seedling-island: foreground=$pkg main=${layout.main?.id}")
                if (morphU > 0.5f && layout.main == null) collapseToCapsule()
            }
            if (layout.isEmpty) {
                removeInternal()
            } else {
                updateGeometry()
                islandView?.invalidate()
                islandView?.requestLayout()
            }
        }
    }

    /** 接管关/热重载撤窗。 */
    fun remove() {
        mainHandler.post { removeInternal() }
    }

    private fun removeInternal() {
        mainHandler.removeCallbacks(expiryRunnable)
        mainHandler.removeCallbacks(autoCollapseRunnable)
        autoCollapse.cancel()
        val v = islandView
        val wmgr = windowManager
        if (v != null) uninstallTouchRegion(v)
        if (v != null && wmgr != null && attached) {
            try {
                wmgr.removeViewImmediate(v)
                Lg.i(TAG, "seedling-island: independent window removed")
            } catch (t: Throwable) {
                Lg.w(TAG, "seedling-island: remove failed", t)
            }
        }
        attached = false
        islandView = null
        layoutParams = null
        morphU = 0f
        mainHandler.removeCallbacks(shakeFrame)
        morphFrame?.let { mainHandler.removeCallbacks(it) }
        morphFrame = null
        transitionWidthPx = null
        transitionGate.reset()
    }

    // ------------------------------------------------------------ 触摸区限定岛矩形

    private var touchProxy: Any? = null
    private var touchAddMethod: java.lang.reflect.Method? = null
    private var touchRemoveMethod: java.lang.reflect.Method? = null
    private var touchSetInsets: java.lang.reflect.Method? = null
    private var touchRegionField: java.lang.reflect.Field? = null
    private var touchListenerClass: Class<*>? = null

    /** 当前岛矩形（窗口坐标系）；触摸区=主岛∪副岛矩形外扩触摸余量。 */
    private fun islandTouchRegion(): android.graphics.Region {
        val v = islandView ?: return android.graphics.Region()
        val pad = (8f * density).toInt()
        val bodyRect = v.toScreenRect(RectF(v.islandLeftPx, v.islandTop, v.islandLeftPx + v.islandW, v.islandTop + v.islandH))
        val region = android.graphics.Region(bodyRect.left.toInt() - pad, bodyRect.top.toInt() - pad, bodyRect.right.toInt() + pad, bodyRect.bottom.toInt() + pad)
        if (morphU < 0.3f) {
            v.satelliteBounds().forEach { r ->
                val sr = v.toScreenRect(r)
                region.union(android.graphics.Rect(sr.left.toInt() - pad, sr.top.toInt() - pad, sr.right.toInt() + pad, sr.bottom.toInt() + pad))
            }
        }
        // OP 式大卡竖排副卡可触区（主卡下方 stackTop 偏移，与 drawOpStackExtras 同几何）
        if (morphU > 0.5f && SeedlingTakeoverHook.takeoverCardMode() == com.coloros16.liquidglass.engine.SeedlingCardModePolicy.Mode.OP) {
            v.opStackLocalRects().forEach { r ->
                val sr = v.toScreenRect(r)
                region.union(android.graphics.Rect(sr.left.toInt() - pad, sr.top.toInt() - pad, sr.right.toInt() + pad, sr.bottom.toInt() + pad))
            }
        }
        if (recallArmed && !recallRect.isEmpty) {
            region.union(android.graphics.Rect(recallRect.left.toInt(), recallRect.top.toInt(), recallRect.right.toInt(), recallRect.bottom.toInt()))
        }
        return region
    }

    /** 反射 OnComputeInternalInsetsListener 动态 Proxy：窗口全屏但只岛矩形可触，岛外触摸穿透。 */
    private fun installTouchRegion(v: View): Boolean {
        try {
            val listenerCls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val infoCls = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val setInsets = infoCls.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
            val regionField = infoCls.getField("touchableRegion")
            val addM = android.view.ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", listenerCls)
            val removeM = android.view.ViewTreeObserver::class.java.getMethod("removeOnComputeInternalInsetsListener", listenerCls)
            val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "onComputeInternalInsets" -> {
                        val info = args?.getOrNull(0)
                        if (info != null) {
                            try {
                                setInsets.invoke(info, 3)
                                val region = regionField.get(info) as android.graphics.Region
                                val target = islandTouchRegion()
                                if (target.isEmpty) region.setEmpty() else region.set(target)
                            } catch (t: Throwable) {
                                Lg.w(TAG, "seedling-island: touch region apply failed: ${t.message}")
                            }
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "lg-island-insets-listener"
                    else -> null
                }
            }
            val proxy = java.lang.reflect.Proxy.newProxyInstance(listenerCls.classLoader, arrayOf(listenerCls), handler)
            addM.invoke(v.viewTreeObserver, proxy)
            touchProxy = proxy
            touchAddMethod = addM
            touchRemoveMethod = removeM
            touchSetInsets = setInsets
            touchRegionField = regionField
            touchListenerClass = listenerCls
            Lg.i(TAG, "seedling-island: touch region installed (island-only touchable)")
            return true
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-island: touch region install error", t)
            return false
        }
    }

    private fun uninstallTouchRegion(v: View) {
        val proxy = touchProxy ?: return
        try {
            touchRemoveMethod?.invoke(v.viewTreeObserver, proxy)
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-island: touch region uninstall failed: ${t.message}")
        }
        touchProxy = null
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * 岛本体自绘 View：玻璃背景（本仓管线）+ 内容占位。窗口 MATCH_PARENT，岛在窗口内顶部居中。
     * 玻璃走 BlurDrawHook.drawSeedlingGlassOnCanvas（region 登记 → SF claim / app 腿渲染）。
     */
    private class ActionHit(val rect: android.graphics.RectF, val id: String)

    private class IslandView(ctx: Context) : View(ctx) {
        var islandW = 360f
        var islandH = 120f
        var islandTop = 20f
        var islandLeftPx = 0f
        var hole: com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.Hole? = null
        var morphU = 0f
        var contentScale = 1f
        var contentBlurDp = 0f
        var crossfadeFromAlpha = 0f
        var crossfadeToAlpha = 1f
        var shakeOffsetPx = 0f
        var islandPressed = false

        // 当前帧 body 变换（触摸命中需同变换，否则晃动/入场/按压期热区与视觉错位）
        private var bodyShakeX = 0f
        private var bodyDropY = 0f
        private var bodyScale = 1f

        /** 触摸点→body 局部坐标（撤销平移+缩放，与 onDraw 变换互逆）。 */
        fun toBodyLocal(x: Float, y: Float): Pair<Float, Float> =
            com.coloros16.liquidglass.engine.SeedlingTouchTransform.toLocal(
                x, y, islandLeftPx, islandW, islandH, islandTop, bodyShakeX, bodyDropY, bodyScale,
            )

        /** OP 竖排副卡 body 局部热区（触摸区并集用）。 */
        fun opStackLocalRects(): List<RectF> = cardStackHits.map { it.rect }

        /** body 局部矩形→屏幕坐标（触摸区并集用，与 onDraw 变换同向）。 */
        fun toScreenRect(r: RectF): RectF {
            val a = com.coloros16.liquidglass.engine.SeedlingTouchTransform.toScreen(
                r.left, r.top, r.right, r.bottom, islandLeftPx, islandW, islandH, islandTop, bodyShakeX, bodyDropY, bodyScale,
            )
            return RectF(a[0], a[1], a[2], a[3])
        }

        private val contentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        /** S6 收起边沿锁：组合条件 false→true 才收一次，防 onDraw 每帧重触发反馈风暴。 */
        private var collapseTriggered = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // S6 收起规矩：下拉面板展开期大卡收胶囊（仅大卡态读面板态，边沿触发收一次，morph<0.5 复位）
            if (morphU > 0.5f) {
                val shouldCollapse = !s6Disabled && BlurDrawHook.isPanelExpanded()
                if (shouldCollapse && !collapseTriggered) collapseToCapsule()
                collapseTriggered = shouldCollapse
            } else {
                collapseTriggered = false
            }
            val w = islandW.toInt().coerceAtLeast(1)
            val h = islandH.toInt().coerceAtLeast(1)
            val d = resources.displayMetrics.density
            // 晃动偏移（胶囊态）+ 大卡入场 scale/drop + 按压缩放（真值 0.96/4dp）
            val shakeX = if (morphU < 0.5f) shakeOffsetPx else 0f
            val cardT = com.coloros16.liquidglass.engine.SeedlingCardTransform.enterProgress(morphU)
            val enterScale = com.coloros16.liquidglass.engine.SeedlingCardTransform.enterScale(cardT)
            val pressScale = com.coloros16.liquidglass.engine.SeedlingCardTransform.pressScale(islandPressed)
            val drawScale = enterScale * pressScale
            val dropY = com.coloros16.liquidglass.engine.SeedlingCardTransform.enterDropPx(cardT, 4f, d)
            val left = islandLeftPx + shakeX
            val top = islandTop + dropY
            bodyShakeX = shakeX
            bodyDropY = dropY
            bodyScale = drawScale
            val bodySave = canvas.save()
            if (drawScale != 1f) {
                canvas.scale(drawScale, drawScale, left + islandW / 2f, top + islandH / 2f)
            }
            // OP 竖排展开态 islandH 已是自适应卡高（updateGeometry），主卡背景/玻璃/内容与副卡同高
            val opStackActive = morphU > 0.5f && SeedlingTakeoverHook.takeoverCardMode() == com.coloros16.liquidglass.engine.SeedlingCardModePolicy.Mode.OP
            val region = RectF(left, top, left + islandW, top + islandH)
            val cornerR = Math.min(w, h) / 2f + (24f * resources.displayMetrics.density - Math.min(w, h) / 2f) * morphU
            val id = System.identityHashCode(this)
            // S1 材质转场：胶囊态纯黑底，展开期黑底淡出、玻璃淡入（对齐原生 blur 0→540×alpha 契约）
            val glassT = smoothstep(morphU)
            // 水滴灵动额头：顶缘中央下凹半圆槽包水滴（几何 engine 同源，Path 渲染侧构造）
            val notch = com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.notchGeometry(
                left, top, left + islandW, cornerR, hole, 8f * d,
            )
            if (glassT < 1f) {
                val blackAlpha = (0xE6 * (1f - glassT)).toInt().coerceIn(0, 255)
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (blackAlpha shl 24) }
                if (notch != null) {
                    canvas.drawPath(foreheadPath(region, cornerR, notch), bgPaint)
                } else {
                    canvas.drawRoundRect(region, cornerR, cornerR, bgPaint)
                }
            }
            if (SeedlingTakeoverHook.takeoverGlassEnabled() && glassT > 0f) {
                try {
                    val layerCount = canvas.saveLayerAlpha(region, (255 * glassT).toInt().coerceIn(0, 255))
                    BlurDrawHook.registerSeedlingCardRegion(
                        id, region, Rect(0, 0, w, h), this,
                        source = "seedling-takeover",
                        cornerRadiusOverride = cornerR,
                    )
                    BlurDrawHook.drawSeedlingGlassOnCanvas(
                        canvas, this, id, w, h, region,
                        offsetX = left, offsetY = top,
                    )
                    canvas.restoreToCount(layerCount)
                } catch (t: Throwable) {
                    Lg.e(TAG, "seedling-island: glass draw failed", t)
                }
            }
            // S4 环境共生：描边随背景明暗显隐（阈值 0.13≈33/255，ColorOS 真值）+ 月牙呼吸
            if (SeedlingTakeoverHook.takeoverAmbientStroke()) drawAmbientStroke(canvas, region, w, h)
            // 内容层：真实活动（媒体等）优先，无活动画占位
            val act = activity
            if (act != null) {
                drawContent(canvas, act, left, top, w.toFloat(), h.toFloat())
            } else {
                val label = if (morphU > 0.5f) "Island Card" else "Island"
                canvas.drawText(label, left + islandW / 2f, top + islandH / 2f + 12f, contentPaint)
            }
            // OP 式大卡：主卡下方竖排其余候选卡（卡1卡2），卡高与主卡同源（islandH 已自适应）
            if (opStackActive) {
                drawOpStackExtras(canvas, left, top, w.toFloat(), h.toFloat())
            }
            // 副岛（批 D）：胶囊态在主岛两侧画小圆点岛（图标+短文字），大卡态隐藏
            if (morphU < 0.3f) drawSatellites(canvas, left, top, w.toFloat(), h.toFloat())
            canvas.restoreToCount(bodySave)
            BlurDrawHook.startIslandRegionCapture()
            logThrottled("island-view-draw", "seedling-island: draw w=$w h=$h morph=$morphU left=$left top=$top hole=$hole act=${act?.id} sats=${satellites.size} opStack=$opStackActive")
        }

        /** 胶囊态=左符号+文字+右波形；大卡态=标题/副题+进度条+动作按钮。 */
        private fun drawContent(canvas: Canvas, act: com.coloros16.liquidglass.engine.SeedlingActivity, left: Float, top: Float, w: Float, h: Float) {
            val d = resources.displayMetrics.density
            if (morphU <= 0.5f) {
                drawCapsuleContent(canvas, act, left, top, w, h, d)
            } else {
                drawCardContent(canvas, act, left, top, w, h, d)
            }
        }

        private fun drawCapsuleContent(canvas: Canvas, act: com.coloros16.liquidglass.engine.SeedlingActivity, left: Float, top: Float, w: Float, h: Float, d: Float) {
            run {
                // S2 内容缩放脉冲 + S5 交叉淡化（旧文淡出/新文淡入）
                val cx = left + w / 2f
                val cy = top + h / 2f
                val saved = canvas.save()
                canvas.scale(contentScale, contentScale, cx, cy)
                val textPaint = Paint(contentPaint).apply { textSize = 12f * d }
                // S5 tnum：计时类数字等宽防抖
                if (act.kind == com.coloros16.liquidglass.engine.SeedlingKind.TIMER) {
                    textPaint.fontFeatureSettings = "tnum"
                    textPaint.typeface = android.graphics.Typeface.MONOSPACE
                }
                // S2 内容模糊脉冲：BlurMaskFilter 只糊文字层（不动整 View 层类型）
                if (contentBlurDp > 0.2f) {
                    textPaint.maskFilter = android.graphics.BlurMaskFilter(contentBlurDp * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                var label = act.compact.label.ifEmpty { act.expanded.title }
                // 计时/录音逐秒走时：绘制期按真值时钟重算文案（内容动画期帧回调，到点即停）
                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                if (act.kind == com.coloros16.liquidglass.engine.SeedlingKind.TIMER && act.expanded.countdownEndAtElapsedMs > 0L) {
                    com.coloros16.liquidglass.engine.SeedlingTimerText
                        .countdownLabel(act.expanded.countdownEndAtElapsedMs, nowElapsed)?.let { label = it }
                    if (!com.coloros16.liquidglass.engine.SeedlingTimerText.countdownDone(act.expanded.countdownEndAtElapsedMs, nowElapsed)) {
                        postInvalidateOnAnimation()
                    }
                } else if (act.kind == com.coloros16.liquidglass.engine.SeedlingKind.TIMER && act.startedAtElapsedMs > 0L && act.expanded.countdownEndAtElapsedMs <= 0L) {
                    // 正计时通知（chronometer 非倒计时）：now-base 走时
                    com.coloros16.liquidglass.engine.SeedlingTimerText
                        .countUpLabel(act.startedAtElapsedMs, nowElapsed, "计时")?.let { label = it }
                    postInvalidateOnAnimation()
                }
                if (act.kind == com.coloros16.liquidglass.engine.SeedlingKind.RECORDING && act.startedAtElapsedMs > 0L) {
                    com.coloros16.liquidglass.engine.SeedlingTimerText
                        .countUpLabel(act.startedAtElapsedMs, nowElapsed, "录音中")?.let { label = it }
                    postInvalidateOnAnimation()
                }
                // 摄像头包绕布局：居中挖孔=符号左半/文字右半；左/右挖孔=内容排一侧；无挖孔=原布局
                val layout = com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.capsuleContent(
                    left, w, hole, 8f * d, 14f * d, 8f * d, 20f * d,
                )
                val symCx = layout[0]
                val textL = layout[1]
                val textR = layout[2]
                val waveCx = layout[3]
                val textCx = (textL + textR) / 2f
                val maxTextW = (textR - textL).coerceAtLeast(24f * d)
                fun truncate(s: String): String {
                    var shown = s
                    while (textPaint.measureText(shown) > maxTextW && shown.length > 4) {
                        shown = shown.dropLast(2) + "…"
                    }
                    return shown
                }
                if (crossfadeFromAlpha > 0.01f && crossfadeOldLabel.isNotEmpty()) {
                    val oldPaint = Paint(textPaint).apply { alpha = (255 * crossfadeFromAlpha).toInt() }
                    canvas.drawText(truncate(crossfadeOldLabel), textCx, cy + 5f * d, oldPaint)
                }
                textPaint.alpha = (255 * crossfadeToAlpha).toInt()
                // 长文字滚动：超宽走 lane（28dp/s 两端停 1s），未超宽居中静止
                val fullW = textPaint.measureText(label)
                val lane = com.coloros16.liquidglass.engine.SeedlingLaneScroll(fullW, maxTextW, 28f * d)
                if (lane.scrollable && crossfadeFromAlpha <= 0.01f) {
                    val offset = lane.offsetAt(android.os.SystemClock.uptimeMillis() - laneEpochMs)
                    val clipSave = canvas.save()
                    canvas.clipRect(textL, top, textR, top + h)
                    val lanePaint = Paint(textPaint).apply { textAlign = Paint.Align.LEFT }
                    canvas.drawText(label, textL - offset, cy + 5f * d, lanePaint)
                    canvas.restoreToCount(clipSave)
                    postInvalidateOnAnimation()
                } else {
                    canvas.drawText(truncate(label), textCx, cy + 5f * d, textPaint)
                }
                // 左侧元素：媒体有封面画圆角小图，否则画符号（位置随挖孔布局）
                val cover = if (act.kind == com.coloros16.liquidglass.engine.SeedlingKind.MEDIA) SeedlingMediaSource.currentCover else null
                if (cover != null && !cover.isRecycled) {
                    val cs = 12f * d
                    val cr = RectF(symCx - cs, cy - cs, symCx + cs, cy + cs)
                    val coverSave = canvas.save()
                    val clip = android.graphics.Path().apply { addRoundRect(cr, 4f * d, 4f * d, android.graphics.Path.Direction.CW) }
                    canvas.clipPath(clip)
                    canvas.drawBitmap(cover, null, cr, null)
                    canvas.restoreToCount(coverSave)
                } else {
                    drawSymbol(canvas, act.compact.leading, symCx, cy, 8f * d)
                }
                val trailing = act.compact.trailing
                if (trailing is com.coloros16.liquidglass.engine.SeedlingElement.Waveform && trailing.active) {
                    drawWaveform(canvas, waveCx, cy, 10f * d)
                    postInvalidateOnAnimation()
                }
                canvas.restoreToCount(saved)
            }
        }

        private fun drawCardContent(canvas: Canvas, act: com.coloros16.liquidglass.engine.SeedlingActivity, left: Float, top: Float, w: Float, h: Float, d: Float) {
            run {
                val exp = act.expanded
                val titlePaint = Paint(contentPaint).apply { textSize = 16f * d }
                val subPaint = Paint(contentPaint).apply { textSize = 12f * d; alpha = 180 }
                // 大卡内容避让挖孔：左/右挖孔嵌入卡顶时，顶部行让到挖孔侧旁
                val cardHole = hole
                val holeKind = cardHole?.let { com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.classify(it) }
                val holeInCard = cardHole != null && cardHole.bottom > top && cardHole.top < top + 60f * d &&
                    (holeKind == com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.CutoutKind.LEFT ||
                        holeKind == com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.CutoutKind.RIGHT)
                // 媒体大卡：左侧圆角封面（有封面时文字右移让位；左挖孔嵌顶时封面下移避让）
                val cardCover = if (act.kind == com.coloros16.liquidglass.engine.SeedlingKind.MEDIA) SeedlingMediaSource.currentCover else null
                var textCx = left + w / 2f
                val coverTop = if (holeInCard && holeKind == com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.CutoutKind.LEFT) {
                    cardHole!!.bottom - top + 8f * d
                } else 16f * d
                if (cardCover != null && !cardCover.isRecycled) {
                    val cs = 30f * d
                    val cr = RectF(left + 20f * d, top + coverTop, left + 20f * d + cs * 2f, top + coverTop + cs * 2f)
                    val coverSave = canvas.save()
                    val clip = android.graphics.Path().apply { addRoundRect(cr, 8f * d, 8f * d, android.graphics.Path.Direction.CW) }
                    canvas.clipPath(clip)
                    canvas.drawBitmap(cardCover, null, cr, null)
                    canvas.restoreToCount(coverSave)
                    textCx = left + 20f * d + cs * 2f + (w - 40f * d - cs * 2f) / 2f
                }
                // 右挖孔嵌顶：标题/副题左移让位
                if (holeInCard && holeKind == com.coloros16.liquidglass.engine.SeedlingCutoutGeometry.CutoutKind.RIGHT && cardCover == null) {
                    textCx = left + (cardHole!!.left - 8f * d - left) / 2f
                }
                canvas.drawText(exp.title, textCx, top + 30f * d, titlePaint)
                if (exp.subtitle.isNotEmpty()) {
                    canvas.drawText(exp.subtitle, textCx, top + 50f * d, subPaint)
                }
                // 歌词行（有词显词，无词回退歌名已在上）；播放期按播放位逐帧重选行（事件驱动帧回调）
                if (SeedlingTakeoverHook.takeoverLyricsEnabled() && SeedlingLyricsBridge.hasLyrics()) {
                    val line = SeedlingLyricsBridge.currentLine()
                    val lyricText = line?.text ?: ""
                    val lyricTr = line?.translation ?: ""
                    if (lyricText.isNotEmpty()) {
                        val lyricPaint = Paint(contentPaint).apply { textSize = 14f * d; alpha = 230 }
                        canvas.drawText(lyricText, textCx, top + 74f * d, lyricPaint)
                        if (lyricTr.isNotEmpty()) {
                            val trPaint = Paint(contentPaint).apply { textSize = 11f * d; alpha = 150 }
                            canvas.drawText(lyricTr, textCx, top + 92f * d, trPaint)
                        }
                    }
                    if (exp.playing && !stackSecondaryDrawing) postInvalidateOnAnimation()
                }
                if (exp.durationMs > 0) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val pos = exp.positionMs + ((now - exp.positionAtElapsedMs) * exp.playbackSpeed).toLong()
                    val frac = if (seekDragActive) seekDragFraction else (pos.toFloat() / exp.durationMs).coerceIn(0f, 1f)
                    val barLeft = left + 24f * d
                    val barRight = left + w - 24f * d
                    val barY = top + h - 56f * d
                    seekBarRect.set(barLeft, barY - 12f * d, barRight, barY + 12f * d)
                    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF; strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND }
                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0FFFFFF.toInt(); strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND }
                    canvas.drawLine(barLeft, barY, barRight, barY, trackPaint)
                    canvas.drawLine(barLeft, barY, barLeft + (barRight - barLeft) * frac, barY, fillPaint)
                    val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
                    canvas.drawCircle(barLeft + (barRight - barLeft) * frac, barY, if (seekDragActive) 6f * d else 4f * d, knobPaint)
                    if (exp.playing && !seekDragActive && !stackSecondaryDrawing) postInvalidateOnAnimation()
                } else {
                    seekBarRect.setEmpty()
                }
                // S3 分段进度（LiveUpdate：segments 多色 + points 里程碑点 + tracker）
                exp.progress?.let { pr ->
                    val barLeft = left + 24f * d
                    val barRight = left + w - 24f * d
                    val barY = top + h - 56f * d
                    val barW = barRight - barLeft
                    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND }
                    canvas.drawLine(barLeft, barY, barRight, barY, trackPaint)
                    var acc = 0f
                    pr.segments.forEach { seg ->
                        val segStart = barLeft + barW * acc
                        val segEnd = barLeft + barW * (acc + seg.fraction).coerceAtMost(1f)
                        val done = (pr.fraction - acc).coerceIn(0f, seg.fraction) / seg.fraction.coerceAtLeast(0.001f)
                        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = seg.colorArgb; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND }
                        canvas.drawLine(segStart, barY, segStart + (segEnd - segStart) * done, barY, sp)
                        acc += seg.fraction
                    }
                    val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
                    pr.points.forEach { pt ->
                        val px = barLeft + barW * pt.coerceIn(0f, 1f)
                        val reached = pr.fraction >= pt
                        pointPaint.alpha = if (reached) 255 else 100
                        canvas.drawCircle(px, barY, 3f * d, pointPaint)
                    }
                    pr.trackerSymbol?.let {
                        val tx = barLeft + barW * pr.trackerPosition()
                        drawSymbol(canvas, com.coloros16.liquidglass.engine.SeedlingElement.Symbol(it), tx, barY - 10f * d, 6f * d)
                    }
                }
                // 通知简单进度（无分段时，progressFraction 单色条）
                if (exp.progress == null) {
                    exp.progressFraction?.let { frac ->
                        val barLeft = left + 24f * d
                        val barRight = left + w - 24f * d
                        val barY = top + h - 56f * d
                        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND }
                        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0FFFFFF.toInt(); strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND }
                        canvas.drawLine(barLeft, barY, barRight, barY, trackPaint)
                        canvas.drawLine(barLeft, barY, barLeft + (barRight - barLeft) * frac.coerceIn(0f, 1f), barY, fillPaint)
                    }
                }
                // S7 里程碑：玻璃高光扫过（一次性 1.2s 动画，事件驱动）
                if (exp.milestone) {
                    val elapsed = android.os.SystemClock.uptimeMillis() - milestoneShownAtMs
                    if (elapsed in 0..1200) {
                        val t = elapsed / 1200f
                        val sweepX = left + w * t
                        val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = android.graphics.LinearGradient(
                                sweepX - 30f * d, top, sweepX + 30f * d, top,
                                intArrayOf(0x00FFFFFF, 0x60FFFFFF, 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP,
                            )
                        }
                        val sweepR = Math.min(w, h) / 2f + (24f * d - Math.min(w, h) / 2f) * morphU
                        canvas.drawRoundRect(RectF(left, top, left + w, top + h), sweepR, sweepR, sweepPaint)
                        if (!stackSecondaryDrawing) postInvalidateDelayed(16L)
                    }
                }
                // 动作按钮行（点击热区在 onTouchEvent 判定）
                actionRects.clear()
                val n = exp.actions.size
                if (n > 0) {
                    val btnR = 16f * d
                    val gap = w / (n + 1)
                    val cy = top + h - 26f * d
                    exp.actions.forEachIndexed { i, a ->
                        val cx = left + gap * (i + 1)
                        actionRects += ActionHit(android.graphics.RectF(cx - btnR, cy - btnR, cx + btnR, cy + btnR), a.id)
                        val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (a.enabled) 0x55FFFFFF else 0x22FFFFFF }
                        canvas.drawCircle(cx, cy, btnR, bp)
                        drawSymbol(canvas, com.coloros16.liquidglass.engine.SeedlingElement.Symbol(a.symbol), cx, cy, 8f * d)
                    }
                    logThrottled("op-card-actions-${act.id}-$stackSecondaryDrawing", "seedling-island: card actions id=${act.id} n=$n cy=$cy cx0=${left + gap} h=$h top=$top secondary=$stackSecondaryDrawing")
                }
            }
        }

        private val actionRects = mutableListOf<ActionHit>()
        private val cardStackHits = mutableListOf<ActionHit>()
        private val seekBarRect = android.graphics.RectF()
        private var seekDragActive = false
        private var seekDragFraction = 0f

        /** OP 副卡绘制期=true：内容同源但逐帧动画/里程碑扫光只归主卡（防副卡常亮动画耗电）。 */
        private var stackSecondaryDrawing = false

        /** OP 竖排可用高 = 屏高 - 卡顶 - 底距（竖排整体不越屏）。 */
        fun opAvailHeight(): Float =
            (resources.displayMetrics.heightPixels - islandTop - 24f * resources.displayMetrics.density).coerceAtLeast(0f)

        /** OP 竖排自适应卡高（engine opCardHeight 同源，绘制/热区/主卡背景共用）。 */
        fun opFitCardHeight(): Float = com.coloros16.liquidglass.engine.SeedlingCardModePolicy.opCardHeight(
            stackCandidates.size.coerceAtLeast(1), islandH, 12f * resources.displayMetrics.density,
            opAvailHeight(), 80f * resources.displayMetrics.density,
        )

        /** 灵动额头顶缘 Path：圆角矩形顶缘中央下凹 U 槽（notch=[左,右,槽底y]，engine notchGeometry 同源）。 */
        private fun foreheadPath(region: RectF, cornerR: Float, notch: FloatArray): android.graphics.Path {
            val p = android.graphics.Path()
            val nl = notch[0]
            val nr = notch[1]
            val ny = notch[2]
            val cx = (nl + nr) / 2f
            val hw = (nr - nl) / 2f
            p.moveTo(region.left + cornerR, region.top)
            p.lineTo(nl, region.top)
            p.cubicTo(nl + hw * 0.3f, region.top, nl + hw * 0.3f, ny, cx, ny)
            p.cubicTo(nr - hw * 0.3f, ny, nr - hw * 0.3f, region.top, nr, region.top)
            p.lineTo(region.right - cornerR, region.top)
            p.quadTo(region.right, region.top, region.right, region.top + cornerR)
            p.lineTo(region.right, region.bottom - cornerR)
            p.quadTo(region.right, region.bottom, region.right - cornerR, region.bottom)
            p.lineTo(region.left + cornerR, region.bottom)
            p.quadTo(region.left, region.bottom, region.left, region.bottom - cornerR)
            p.lineTo(region.left, region.top + cornerR)
            p.quadTo(region.left, region.top, region.left + cornerR, region.top)
            p.close()
            return p
        }

        /** OP 式竖排：主卡（drawContent 已画）下方按 stackTop 偏移画其余候选全功能卡（drawCardContent 同源）。 */
        private fun drawOpStackExtras(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
            val d = resources.displayMetrics.density
            val candidates = stackCandidates
            cardStackHits.clear()
            if (candidates.size <= 1) return
            val gap = 12f * d
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0202024.toInt() }
            candidates.drop(1).forEachIndexed { idx, a ->
                val ct = com.coloros16.liquidglass.engine.SeedlingCardModePolicy.stackTop(top, idx + 1, h, gap)
                val r = RectF(left, ct, left + w, ct + h)
                // 热区用 body 局部坐标（与 toBodyLocal 触摸判定同源；绘制 r 用屏幕绝对）
                val localTop = ct - top
                cardStackHits += ActionHit(RectF(0f, localTop, w, localTop + h), a.id)
                val rr = 24f * d
                canvas.drawRoundRect(r, rr, rr, bgPaint)
                // 副卡全功能内容与主卡同源；热区只归主卡（快照还原），副卡静态帧不逐帧动画
                val savedActions = ArrayList(actionRects)
                val savedSeek = RectF(seekBarRect)
                stackSecondaryDrawing = true
                drawCardContent(canvas, a, left, ct, w, h, d)
                stackSecondaryDrawing = false
                logThrottled("op-stack-card", "seedling-island: op card id=${a.id} ct=$ct h=$h actions=${a.expanded.actions.size} btnCy=${ct + h - 26f * d} avail=${opAvailHeight()}")
                actionRects.clear()
                actionRects.addAll(savedActions)
                seekBarRect.set(savedSeek)
            }
        }

        /** S4：岛周描边——背景亮（brightness>33）显细亮边，暗背景隐；月牙装饰呼吸（mini 态）。 */
        private fun drawAmbientStroke(canvas: Canvas, region: RectF, w: Int, h: Int) {
            val d = resources.displayMetrics.density
            val cx = region.centerX().toInt()
            val cy = region.centerY().toInt()
            val brightness = BlurDrawHook.localBrightnessAt(cx, cy)
            val r = Math.min(w, h) / 2f + (24f * d - Math.min(w, h) / 2f) * morphU
            if (brightness in 0..255) {
                val light = brightness > 33
                val strokeAlpha = if (light) 0x50 else 0x18
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1f * d
                    color = (strokeAlpha shl 24) or 0xFFFFFF
                }
                canvas.drawRoundRect(region, r, r, strokePaint)
            }
            // 月牙呼吸：胶囊态左侧一弯细月，alpha 正弦 4s 周期（渲染帧驱动，无自调度）
            if (morphU < 0.3f) {
                val t = (android.os.SystemClock.uptimeMillis() % 4000L) / 4000f
                val breath = 0.5f + 0.4f * kotlin.math.sin(t * 6.283f)
                val mr = 4f * d
                val mx = region.left + 12f * d
                val my = region.centerY()
                val layer = canvas.saveLayer(mx - mr * 2, my - mr * 2, mx + mr * 2, my + mr * 2, null)
                val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFF
                    alpha = (255 * breath * 0.35f).toInt().coerceIn(0, 255)
                }
                canvas.drawCircle(mx, my, mr, moonPaint)
                val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                }
                canvas.drawCircle(mx + mr * 0.5f, my - mr * 0.2f, mr * 0.85f, cutPaint)
                canvas.restoreToCount(layer)
            }
        }

        /** 副岛热区（点按提升为主岛）。 */
        private val satelliteRects = mutableListOf<ActionHit>()

        fun satelliteBounds(): List<android.graphics.RectF> = satelliteRects.map { it.rect }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            BlurDrawHook.startIslandRegionCapture()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == VISIBLE) BlurDrawHook.startIslandRegionCapture()
        }

        /** 岛本体屏幕矩形（含当前帧晃动/位移/缩放变换）。 */
        fun lastBodyScreenRect(originX: Int, originY: Int): android.graphics.RectF {
            val l = islandLeftPx + bodyShakeX
            val t = islandTop + bodyDropY
            val cx = l + islandW / 2f
            val cy = t + islandH / 2f
            val hw = islandW / 2f * bodyScale
            val hh = islandH / 2f * bodyScale
            return android.graphics.RectF(
                originX + cx - hw, originY + cy - hh,
                originX + cx + hw, originY + cy + hh,
            )
        }

        /** 副岛（WiFi 图标 / 信号点）屏幕矩形。 */
        fun satelliteBoundsScreen(originX: Int, originY: Int): List<android.graphics.RectF> =
            satelliteRects.map {
                android.graphics.RectF(
                    originX + it.rect.left, originY + it.rect.top,
                    originX + it.rect.right, originY + it.rect.bottom,
                )
            }

        private fun drawSatellites(canvas: Canvas, mainLeft: Float, top: Float, mainW: Float, mainH: Float) {
            satelliteRects.clear()
            val sats = satellites
            if (sats.isEmpty()) return
            val d = resources.displayMetrics.density
            val satH = mainH * 0.8f
            val satTop = top + (mainH - satH) / 2f
            val gap = 8f * d
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD0000000.toInt() }
            val textPaint = Paint(contentPaint).apply { textSize = 11f * d }
            sats.forEachIndexed { i, sat ->
                val label = sat.compact.label.ifEmpty { sat.expanded.title }
                val textW = if (label.isNotEmpty()) textPaint.measureText(label.take(8)) else 0f
                val satW = (textW + 36f * d).coerceAtLeast(satH)
                val cx = if (i == 0) mainLeft - gap - satW / 2f else mainLeft + mainW + gap + satW / 2f
                if (cx - satW / 2f < 0 || cx + satW / 2f > width) return@forEachIndexed
                val rect = RectF(cx - satW / 2f, satTop, cx + satW / 2f, satTop + satH)
                canvas.drawRoundRect(rect, satH / 2f, satH / 2f, bgPaint)
                drawSymbol(canvas, sat.compact.leading ?: com.coloros16.liquidglass.engine.SeedlingElement.Symbol("INFO"), cx - satW / 2f + 14f * d, satTop + satH / 2f, 6f * d)
                if (label.isNotEmpty()) {
                    canvas.drawText(label.take(8), cx + 6f * d, satTop + satH / 2f + 4f * d, textPaint)
                }
                satelliteRects += ActionHit(rect, sat.id)
            }
        }

        private fun drawSymbol(canvas: Canvas, el: com.coloros16.liquidglass.engine.SeedlingElement?, cx: Float, cy: Float, r: Float) {
            if (el !is com.coloros16.liquidglass.engine.SeedlingElement.Symbol) return
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
            when (el.name) {
                "PLAY" -> {
                    val path = android.graphics.Path()
                    path.moveTo(cx - r * 0.5f, cy - r)
                    path.lineTo(cx + r, cy)
                    path.lineTo(cx - r * 0.5f, cy + r)
                    path.close()
                    canvas.drawPath(path, p)
                }
                "PAUSE" -> {
                    canvas.drawRect(cx - r * 0.7f, cy - r, cx - r * 0.2f, cy + r, p)
                    canvas.drawRect(cx + r * 0.2f, cy - r, cx + r * 0.7f, cy + r, p)
                }
                "SKIP_NEXT", "SKIP_PREV" -> {
                    val dir = if (el.name == "SKIP_NEXT") 1f else -1f
                    val path = android.graphics.Path()
                    path.moveTo(cx - dir * r, cy - r)
                    path.lineTo(cx + dir * r * 0.3f, cy)
                    path.lineTo(cx - dir * r, cy + r)
                    path.close()
                    canvas.drawPath(path, p)
                    canvas.drawRect(cx + dir * r * 0.4f, cy - r, cx + dir * r * 0.7f, cy + r, p)
                }
                "HEART" -> {
                    val path = android.graphics.Path()
                    path.moveTo(cx, cy + r * 0.9f)
                    path.cubicTo(cx - r * 1.4f, cy - r * 0.1f, cx - r * 0.7f, cy - r * 1.1f, cx, cy - r * 0.35f)
                    path.cubicTo(cx + r * 0.7f, cy - r * 1.1f, cx + r * 1.4f, cy - r * 0.1f, cx, cy + r * 0.9f)
                    path.close()
                    canvas.drawPath(path, p)
                }
                "MIC" -> {
                    canvas.drawRoundRect(cx - r * 0.35f, cy - r, cx + r * 0.35f, cy + r * 0.2f, r * 0.35f, r * 0.35f, p)
                    val arc = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.18f }
                    canvas.drawArc(cx - r * 0.7f, cy - r * 0.5f, cx + r * 0.7f, cy + r * 0.7f, 0f, 180f, false, arc)
                    canvas.drawLine(cx, cy + r * 0.7f, cx, cy + r, arc)
                }
                "TIMER" -> {
                    val ring = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.18f }
                    canvas.drawCircle(cx, cy, r * 0.85f, ring)
                    canvas.drawLine(cx, cy, cx, cy - r * 0.55f, ring)
                    canvas.drawLine(cx, cy, cx + r * 0.4f, cy, ring)
                }
                "BATTERY" -> {
                    val body = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.16f }
                    canvas.drawRoundRect(cx - r, cy - r * 0.55f, cx + r * 0.7f, cy + r * 0.55f, r * 0.15f, r * 0.15f, body)
                    canvas.drawRect(cx + r * 0.75f, cy - r * 0.25f, cx + r, cy + r * 0.25f, p)
                    canvas.drawRect(cx - r * 0.75f, cy - r * 0.3f, cx + r * 0.1f, cy + r * 0.3f, p)
                }
                "HEADSET" -> {
                    val band = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.18f }
                    canvas.drawArc(cx - r * 0.8f, cy - r * 0.9f, cx + r * 0.8f, cy + r * 0.7f, 180f, 180f, false, band)
                    canvas.drawRoundRect(cx - r * 0.95f, cy - r * 0.1f, cx - r * 0.45f, cy + r * 0.7f, r * 0.2f, r * 0.2f, p)
                    canvas.drawRoundRect(cx + r * 0.45f, cy - r * 0.1f, cx + r * 0.95f, cy + r * 0.7f, r * 0.2f, r * 0.2f, p)
                }
                "PHONE" -> {
                    val path = android.graphics.Path()
                    path.moveTo(cx - r * 0.8f, cy - r * 0.6f)
                    path.cubicTo(cx - r * 0.9f, cy + r * 0.3f, cx - r * 0.3f, cy + r * 0.9f, cx + r * 0.6f, cy + r * 0.8f)
                    path.lineTo(cx + r * 0.8f, cy + r * 0.4f)
                    path.lineTo(cx + r * 0.3f, cy + r * 0.1f)
                    path.lineTo(cx + r * 0.0f, cy + r * 0.4f)
                    path.cubicTo(cx - r * 0.3f, cy + r * 0.2f, cx - r * 0.5f, cy - r * 0.1f, cx - r * 0.4f, cy - r * 0.4f)
                    path.lineTo(cx - r * 0.1f, cy - r * 0.7f)
                    path.lineTo(cx - r * 0.4f, cy - r * 0.9f)
                    path.close()
                    canvas.drawPath(path, p)
                }
                "PHONE_END" -> {
                    val path = android.graphics.Path()
                    path.moveTo(cx - r * 0.9f, cy + r * 0.2f)
                    path.cubicTo(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.5f, cy - r * 0.5f, cx + r * 0.9f, cy + r * 0.2f)
                    path.lineTo(cx + r * 0.6f, cy + r * 0.5f)
                    path.lineTo(cx + r * 0.2f, cy + r * 0.2f)
                    path.lineTo(cx + r * 0.2f, cy - r * 0.1f)
                    path.cubicTo(cx, cy - r * 0.2f, cx - r * 0.2f, cy - r * 0.1f, cx - r * 0.2f, cy - r * 0.1f)
                    path.lineTo(cx - r * 0.2f, cy + r * 0.2f)
                    path.lineTo(cx - r * 0.6f, cy + r * 0.5f)
                    path.close()
                    canvas.drawPath(path, p)
                }
                "SPEAKER" -> {
                    canvas.drawRect(cx - r * 0.7f, cy - r * 0.3f, cx - r * 0.3f, cy + r * 0.3f, p)
                    val path = android.graphics.Path()
                    path.moveTo(cx - r * 0.3f, cy - r * 0.3f)
                    path.lineTo(cx + r * 0.2f, cy - r * 0.7f)
                    path.lineTo(cx + r * 0.2f, cy + r * 0.7f)
                    path.lineTo(cx - r * 0.3f, cy + r * 0.3f)
                    path.close()
                    canvas.drawPath(path, p)
                    val arc = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.15f }
                    canvas.drawArc(cx + r * 0.1f, cy - r * 0.5f, cx + r * 0.8f, cy + r * 0.5f, -50f, 100f, false, arc)
                }
                "CLOSE" -> {
                    val stroke = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.22f; strokeCap = Paint.Cap.ROUND }
                    canvas.drawLine(cx - r * 0.6f, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f, stroke)
                    canvas.drawLine(cx + r * 0.6f, cy - r * 0.6f, cx - r * 0.6f, cy + r * 0.6f, stroke)
                }
                "LOCK_OPEN" -> {
                    val body = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.16f }
                    canvas.drawRoundRect(cx - r * 0.6f, cy - r * 0.1f, cx + r * 0.6f, cy + r * 0.8f, r * 0.15f, r * 0.15f, body)
                    canvas.drawArc(cx - r * 0.4f, cy - r * 0.9f, cx + r * 0.4f, cy + r * 0.1f, 180f, 200f, false, body)
                }
                "MESSAGE" -> {
                    canvas.drawRoundRect(cx - r * 0.8f, cy - r * 0.6f, cx + r * 0.8f, cy + r * 0.4f, r * 0.2f, r * 0.2f, p)
                    val path = android.graphics.Path()
                    path.moveTo(cx - r * 0.3f, cy + r * 0.4f)
                    path.lineTo(cx - r * 0.1f, cy + r * 0.8f)
                    path.lineTo(cx + r * 0.2f, cy + r * 0.4f)
                    path.close()
                    canvas.drawPath(path, p)
                }
                "NAVIGATION" -> {
                    val path = android.graphics.Path()
                    path.moveTo(cx, cy - r * 0.9f)
                    path.lineTo(cx + r * 0.7f, cy + r * 0.8f)
                    path.lineTo(cx, cy + r * 0.4f)
                    path.lineTo(cx - r * 0.7f, cy + r * 0.8f)
                    path.close()
                    canvas.drawPath(path, p)
                }
                "MUSIC" -> {
                    val stroke = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.16f }
                    canvas.drawLine(cx + r * 0.4f, cy - r * 0.8f, cx + r * 0.4f, cy + r * 0.4f, stroke)
                    canvas.drawLine(cx - r * 0.4f, cy - r * 0.6f, cx - r * 0.4f, cy + r * 0.6f, stroke)
                    canvas.drawLine(cx - r * 0.4f, cy - r * 0.6f, cx + r * 0.4f, cy - r * 0.8f, stroke)
                    canvas.drawCircle(cx - r * 0.55f, cy + r * 0.6f, r * 0.25f, p)
                    canvas.drawCircle(cx + r * 0.25f, cy + r * 0.4f, r * 0.25f, p)
                }
                else -> canvas.drawCircle(cx, cy, r * 0.6f, p)
            }
        }

        private fun drawWaveform(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = r * 0.28f; strokeCap = Paint.Cap.ROUND }
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
            val tSec = (android.os.SystemClock.uptimeMillis() % 100000L) / 1000.0
            val spacing = r * 0.55f
            for (i in 0..3) {
                val x = cx + (i - 1.5f) * spacing
                val amp = com.coloros16.liquidglass.engine.SeedlingWaveform.amplitude(i, tSec)
                if (amp < 0.5f) {
                    canvas.drawCircle(x, cy, r * 0.14f, dotPaint)
                } else {
                    val half = amp * r / 2f
                    canvas.drawLine(x, cy - half, x, cy + half, p)
                }
            }
        }

        private var downX = 0f
        private var downY = 0f
        private var downAtMs = 0L
        private val velocityTracker = android.view.VelocityTracker.obtain()
        private var longPressFired = false
        private val longPressRunnable = Runnable {
            longPressFired = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            logThrottled("island-longpress", "seedling-island: long-press temp expand")
            if (morphU < 0.5f) {
                autoCollapseHoldTemp = true
                toggleMorph()
            }
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            val d = resources.displayMetrics.density
            val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
            velocityTracker.addMovement(event)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAtMs = android.os.SystemClock.uptimeMillis()
                    longPressFired = false
                    pauseAutoCollapse()
                    idleHide.touch(android.os.SystemClock.uptimeMillis())
                    rescheduleIdleHide()
                    islandPressed = true
                    invalidate()
                    // 进度条拖动起手：大卡态命中进度条热区 → 进拖动模式（不排长按）
                    val (downLx, downLy) = toBodyLocal(event.x, event.y)
                    if (morphU > 0.5f && !seekBarRect.isEmpty && seekBarRect.contains(downLx, downLy)) {
                        seekDragActive = true
                        seekDragFraction = ((downLx - seekBarRect.left) / seekBarRect.width()).coerceIn(0f, 1f)
                        invalidate()
                        return true
                    }
                    mainHandler.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (seekDragActive) {
                        val (moveLx, _) = toBodyLocal(event.x, event.y)
                        seekDragFraction = ((moveLx - seekBarRect.left) / seekBarRect.width()).coerceIn(0f, 1f)
                        invalidate()
                        return true
                    }
                    if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    resumeAutoCollapse()
                    islandPressed = false
                    invalidate()
                    if (seekDragActive) {
                        seekDragActive = false
                        logThrottled("island-seek", "seedling-island: seek drag end fraction=$seekDragFraction")
                        SeedlingMediaSource.dispatchSeek(seekDragFraction)
                        invalidate()
                        return true
                    }
                    if (longPressFired) return true
                    val dx = event.x - downX
                    val dy = event.y - downY
                    velocityTracker.computeCurrentVelocity(1000)
                    val vx = velocityTracker.xVelocity
                    val vy = velocityTracker.yVelocity
                    val gesture = com.coloros16.liquidglass.engine.SeedlingGesturePolicy.classify(
                        dx, dy, vx, vy, slop, d,
                        expanded = morphU > 0.5f,
                        hasSatellites = morphU < 0.3f && satellites.isNotEmpty(),
                        cardSwipeFloating = com.coloros16.liquidglass.engine.SeedlingDisplayRules().cardSwipeFloating,
                        launchableSource = com.coloros16.liquidglass.engine.SeedlingOpenSourcePolicy.launchablePackage(activity?.source),
                        appleCycle = SeedlingTakeoverHook.takeoverCardMode() == com.coloros16.liquidglass.engine.SeedlingCardModePolicy.Mode.APPLE,
                    )
                    when (gesture) {
                        com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.FLING_DISMISS -> {
                            logThrottled("island-fling", "seedling-island: fling dismiss vx=${vx.toInt()}")
                            dismissIsland()
                            return true
                        }
                        com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.ROTATE_SATELLITE_LEFT,
                        com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.ROTATE_SATELLITE_RIGHT,
                        -> {
                            // 横滑轮换：右滑=左副岛顶上，左滑=右副岛顶上（超过两个依次轮）
                            val target = if (gesture == com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.ROTATE_SATELLITE_RIGHT) {
                                satellites.first()
                            } else {
                                satellites.last()
                            }
                            logThrottled("island-rotate", "seedling-island: horizontal rotate promote ${target.id}")
                            promoteSatellite(target.id)
                            return true
                        }
                        com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.PROMOTE_LEFT,
                        com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.PROMOTE_RIGHT,
                        -> {
                            val target = if (gesture == com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.PROMOTE_LEFT) {
                                satellites.first()
                            } else {
                                satellites.last()
                            }
                            logThrottled("island-vfling", "seedling-island: vertical fling promote ${target.id}")
                            promoteSatellite(target.id)
                            return true
                        }
                        com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.CARD_CYCLE_LEFT,
                        com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.CARD_CYCLE_RIGHT,
                        -> {
                            // 苹果式大卡横滑换卡：左滑下一张/右滑上一张，环形轮换展开锁定 id
                            val dir = if (gesture == com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.CARD_CYCLE_LEFT) 1 else -1
                            val lay = scheduler.layout(android.os.SystemClock.elapsedRealtime(), kindAllowed = { SeedlingTakeoverHook.kindSwitches().allows(it) })
                            val candidates = listOfNotNull(lay.main, lay.left, lay.right) + lay.queued
                            val newId = com.coloros16.liquidglass.engine.SeedlingCardModePolicy.cycleExpanded(com.coloros16.liquidglass.engine.SeedlingCardModePolicy.Mode.APPLE, candidates, scheduler.expandedId, dir)
                            if (newId != null && newId != scheduler.expandedId) {
                                scheduler.expandedId = newId
                                val nl = relayout()
                                activity = nl.main
                                satellites = listOfNotNull(nl.left, nl.right)
                                updateGeometry()
                                islandView?.invalidate()
                                islandView?.requestLayout()
                                logThrottled("island-card-cycle", "seedling-island: card cycle to $newId")
                            }
                            return true
                        }
                        com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.CARD_COLLAPSE -> {
                            logThrottled("island-swipe", "seedling-island: swipe up collapse")
                            collapseToCapsule()
                            return true
                        }
                        com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.CARD_FLOATING_OPEN -> {
                            logThrottled("island-swipe-down", "seedling-island: swipe down floating open")
                            if (openSourceForActivity(activity)) collapseToCapsule()
                            return true
                        }
                        com.coloros16.liquidglass.engine.SeedlingGesturePolicy.Gesture.NONE -> {}
                    }
                    val (upLx, upLy) = toBodyLocal(event.x, event.y)
                    val hit = actionRects.firstOrNull { it.rect.contains(upLx, upLy) }
                    if (hit != null && morphU > 0.5f) {
                        logThrottled("island-action", "seedling-island: action=${hit.id}")
                        val actId = activity?.id
                        if (hit.id == "open_source") {
                            openSourceForActivity(activity)
                        } else if (actId != null && SeedlingNotifSource.ownsActivity(actId)) {
                            SeedlingNotifSource.dispatchAction(actId, hit.id)
                        } else {
                            SeedlingMediaSource.dispatchAction(hit.id)
                        }
                        return true
                    }
                    val stackHit = cardStackHits.firstOrNull { it.rect.contains(upLx, upLy) }
                    if (stackHit != null && morphU > 0.5f) {
                        logThrottled("island-stack-tap", "seedling-island: stack card tapped id=${stackHit.id}")
                        scheduler.expandedId = stackHit.id
                        val nl = relayout()
                        activity = nl.main
                        satellites = listOfNotNull(nl.left, nl.right)
                        updateGeometry()
                        islandView?.invalidate()
                        islandView?.requestLayout()
                        return true
                    }
                    val satHit = satelliteRects.firstOrNull { it.rect.contains(upLx, upLy) }
                    if (satHit != null && morphU < 0.3f) {
                        logThrottled("island-satellite", "seedling-island: satellite promoted id=${satHit.id}")
                        promoteSatellite(satHit.id)
                        return true
                    }
                    // 召回热区：划掉过内容后点摄像头周边余区 → 召回
                    if (recallArmed && !recallRect.isEmpty && recallRect.contains(event.x, event.y) &&
                        kotlin.math.abs(dx) <= slop && kotlin.math.abs(dy) <= slop
                    ) {
                        logThrottled("island-recall", "seedling-island: recall zone tapped")
                        recallHidden()
                        return true
                    }
                    if (kotlin.math.abs(dx) <= slop && kotlin.math.abs(dy) <= slop) {
                        logThrottled("island-touch", "seedling-island: island tapped at (${event.x.toInt()},${event.y.toInt()})")
                        performClick()
                        val act = activity
                        val tap = if (act != null && morphU < 0.5f) {
                            com.coloros16.liquidglass.engine.SeedlingOpenSourcePolicy.tapAction(
                                act.kind, act.expandable, BlurDrawHook.isKeyguardLockedForIsland(),
                            )
                        } else {
                            com.coloros16.liquidglass.engine.SeedlingOpenSourcePolicy.TapAction.EXPAND
                        }
                        if (tap == com.coloros16.liquidglass.engine.SeedlingOpenSourcePolicy.TapAction.OPEN_SOURCE) {
                            // 直开失败：可展开活动回落展开；不可展开活动（充电/耳机岛）不展开，仅日志
                            if (!openSourceForActivity(act) && act != null &&
                                com.coloros16.liquidglass.engine.SeedlingOpenSourcePolicy.fallbackExpandOnOpenFail(act.expandable)
                            ) toggleMorph()
                        } else {
                            toggleMorph()
                        }
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    resumeAutoCollapse()
                    islandPressed = false
                    invalidate()
                    if (seekDragActive) {
                        seekDragActive = false
                        invalidate()
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}