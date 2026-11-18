package com.coloros16.liquidglass.hook.statusbar

import android.content.SharedPreferences
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 状态栏改动器：只做「幂等写入 + 原值还原」。
 *
 * 生效时点：
 * - 状态栏根视图到手（CollapsedStatusBarFragment.onViewCreated after）
 * - 图标容器每次布局后（StatusIconContainer.onLayout after；系统协程会反复覆写可见性，必须每次纠正）
 * - 容器有子 View 新增（onViewAdded after；由 Hook 侧合并 post 后再调）
 *
 * 所有写入都先记原值；关功能或卸载时 [restoreAll] 逐项还原。任何异常只跳过本项。
 */
object StatusBarEnforcer {

    private const val TAG = "LgStatusBar"

    private val originalVisibility = WeakHashMap<View, Int>()
    private val originalPadding = WeakHashMap<View, IntArray>()
    private val paddingTouched = WeakHashMap<View, Boolean>()
    private val originalTransform = WeakHashMap<View, FloatArray>()
    private val originalTypeLayout = WeakHashMap<View, IntArray>()
    private val throttleAt = ConcurrentHashMap<String, Long>()
    private var rootRef: WeakReference<View>? = null
    private var containerRef: WeakReference<ViewGroup>? = null

    /** 顺序回传通道（设置页预览条读当前真实顺序）；null = 未装载，只读不写。 */
    @Volatile
    private var orderPrefs: SharedPreferences? = null

    private var lastPublishedOrder: String? = null

    /** 电量控件的资源 id（独立 include 无 getSlot，按 id 特判收进槽序）。 */
    private var batterySlotId = Int.MIN_VALUE

    /** 类 → getSlot 方法（miss 记在 missSlotClasses，避免反复反射）。 */
    private val slotMethod = WeakHashMap<Class<*>, Method>()
    private val missSlotClasses = WeakHashMap<Class<*>, Boolean>()

    @Volatile
    private var applied = false

    @Volatile
    private var reorderPending = false
    private var lastReorderKey: String? = null
    private var lastReorderContainer: WeakReference<ViewGroup>? = null

    @Volatile
    private var orderDragActive = false

    /** 保修重排用的 protected 方法（解析一次；失败回退 remove/add）。 */
    private val orderDetachMethod: Method? by lazy {
        try {
            ViewGroup::class.java.getDeclaredMethod("detachViewFromParent", View::class.java)
                .apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    private val orderAttachMethod: Method? by lazy {
        try {
            ViewGroup::class.java.getDeclaredMethod(
                "attachViewToParent", View::class.java, Int::class.javaPrimitiveType,
                ViewGroup.LayoutParams::class.java,
            ).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    /** 首次接管排序前的子 View 顺序（关排序时还原）。 */
    private var defaultOrder: List<WeakReference<View>>? = null

    /** 电量原位（排序期间搬进图标容器，出序/关闸时搬回）。 */

    /** 通知图标区原父容器与原索引（移到右侧后用于还原）。 */
    private var notifyOriginalParent: WeakReference<ViewGroup>? = null
    private var notifyOriginalIndex = -1

    /** 时钟秒开关状态（只在切换时写系统，避免每帧重排版）。 */
    private val clockSecondsApplied = WeakHashMap<View, Boolean>()
    private val clockSecondsOriginal = WeakHashMap<View, Boolean>()

    /** 状态栏根视图（PhoneStatusBarView）。 */
    fun onStatusBarView(root: View, cfg: StatusBarConfig) {
        rootRef = WeakReference(root)
        applied = true
        applyRoot(root, cfg)
    }

    /** 装载顺序回传通道（hook 装载时一次）。 */
    fun attachOrderPrefs(prefs: SharedPreferences?) {
        orderPrefs = prefs
    }

    /**
     * 读通道：把状态栏全部图标槽的顺序写进框架侧 prefs（设置页预览条初始化用）。
     * 从状态栏根做文档序遍历（结构序=视觉序，平移动画不触发重写）；只读不改视图，总闸关着也照跑。
     */
    fun publishOrder(container: ViewGroup) {
        val prefs = orderPrefs ?: return
        try {
            if (rootRef?.get() == null) deriveRootFrom(container)
            val scope = rootRef?.get() ?: container
            if (batterySlotId == Int.MIN_VALUE) {
                childView(scope, "battery")?.let { batterySlotId = it.id }
            }
            val seq = StringBuilder(container.childCount * 12)
            val seen = HashSet<String>()
            collectSlotSeq(scope, seq, seen, 0)
            val value = seq.toString()
            if (value.isEmpty() || value == lastPublishedOrder) return
            lastPublishedOrder = value
            prefs.edit().putString(Prefs.KEY_STATUSBAR_ICON_ORDER_CURRENT, value).apply()
            logThrottled("sb-order-pub", "sb-order-pub value=$value")
        } catch (t: Throwable) {
            logThrottled("sb-order-pub-err", "sb-order-pub 失败 ${t.javaClass.simpleName}")
        }
    }

    /** 深度优先收集图标槽：getSlot 命中者与电量特判（独立 include 无 getSlot），去重保序；通知 key 非图标槽跳过。 */
    private fun collectSlotSeq(node: View, seq: StringBuilder, seen: HashSet<String>, depth: Int) {
        if (depth > 16 || seen.size >= 64) return
        var slot = slotOf(node)
        if (slot.isEmpty() && node.id == batterySlotId && batterySlotId != Int.MAX_VALUE && batterySlotId != Int.MIN_VALUE) {
            slot = "battery"
        }
        if (StatusBarSettings.isIconSlot(slot) && seen.add(slot)) {
            if (seq.isNotEmpty()) seq.append(',')
            seq.append(slot)
        }
        if (node is ViewGroup) {
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) ?: continue
                collectSlotSeq(child, seq, seen, depth + 1)
            }
        }
    }

    /** 图标容器布局完成（系统已摆位、动画已下达）。 */
    fun onContainerLayout(container: View, cfg: StatusBarConfig) {
        applied = true
        if (container is ViewGroup) containerRef = WeakReference(container)
        if (rootRef?.get() == null && container is ViewGroup) deriveRootFrom(container)
        applyContainer(container, cfg)
        // 根视图侧的元素也在每次布局后补齐（旋转/重建后自愈）
        rootRef?.get()?.let { applyRoot(it, cfg) }
        applySideRoots(container, cfg)
    }

    /** 容器新增子 View：新图标进来时补一次（Hook 侧已合并 post）。 */
    fun onChildAdded(container: View, cfg: StatusBarConfig) {
        applied = true
        if (container is ViewGroup) {
            containerRef = WeakReference(container)
            publishOrder(container)
        }
        if (rootRef?.get() == null && container is ViewGroup) deriveRootFrom(container)
        applyContainer(container, cfg)
        rootRef?.get()?.let { applyRoot(it, cfg) }
        applySideRoots(container, cfg)
    }

    /**
     * 根视图上溯：无 Fragment 交付入口的档位（Compose 承载）从图标容器沿父链找状态栏根。
     * 找到即缓存（后续布局零开销）；找不到只打节流日志，根侧功能降级不崩。
     */
    private fun deriveRootFrom(container: View) {
        try {
            var node: View? = container.parent as? View
            var depth = 0
            while (node != null && depth < 12) {
                if (childView(node, "notification_icon_area") != null ||
                    childView(node, "status_bar_contents") != null
                ) {
                    rootRef = WeakReference(node)
                    logThrottled("sb-root-derive", "sb-root-derive class=${node.javaClass.simpleName}")
                    return
                }
                node = node.parent as? View
                depth++
            }
            logThrottled("sb-root-derive-miss", "sb-root-derive 未找到根 depth=$depth")
        } catch (t: Throwable) {
            logThrottled("sb-root-derive-err", "sb-root-derive 失败 ${t.javaClass.simpleName}")
        }
    }

    fun isApplied(): Boolean = applied

    /** 逐项还原（关功能 / 卸载）。 */
    fun restoreAll() {
        var n = 0
        try {
            val visIt = originalVisibility.entries.iterator()
            while (visIt.hasNext()) {
                val e = visIt.next()
                try {
                    if (e.key.visibility != e.value) e.key.visibility = e.value
                    n++
                } catch (_: Throwable) {
                }
                visIt.remove()
            }
            val padIt = originalPadding.entries.iterator()
            while (padIt.hasNext()) {
                val e = padIt.next()
                try {
                    val p = e.value
                    e.key.setPadding(p[0], p[1], p[2], p[3])
                    n++
                } catch (_: Throwable) {
                }
                padIt.remove()
            }
            paddingTouched.clear()
            val tfIt = originalTransform.entries.iterator()
            while (tfIt.hasNext()) {
                val e = tfIt.next()
                try {
                    val t = e.value
                    e.key.scaleX = t[0]
                    e.key.scaleY = t[1]
                    e.key.translationX = t[2]
                    e.key.translationY = t[3]
                    n++
                } catch (_: Throwable) {
                }
                tfIt.remove()
            }
            val layoutIt = originalTypeLayout.entries.iterator()
            while (layoutIt.hasNext()) {
                val e = layoutIt.next()
                try {
                    val v = e.key
                    val o = e.value
                    if (v is FrameLayout) restoreTypeRightOf(v, o)
                    n++
                } catch (_: Throwable) {
                }
                layoutIt.remove()
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "sb-restore 失败 ${t.javaClass.simpleName}", t)
        }
        applied = false
        try {
            val views = ArrayList<View>(notifClockOrig.keys)
            restoreNotifClock(views.firstOrNull(), views.getOrNull(1), null)
            // 首个以外的残留项逐个还原（restoreNotifClock 一次只处理传入的两个）
            views.drop(2).forEach { restoreNotifClock(it, null, null) }
            notifSettingsOrig.keys.toList().forEach { applyNotifHideSettings(it, false) }
        } catch (t: Throwable) {
            logThrottled("sb-restore-notif", "sb-skip notif restore ${t.javaClass.simpleName}")
        }
        try {
            containerRef?.get()?.let { restoreOrder(it) }
        } catch (_: Throwable) {
        }
        try {
            StatusBarDuoHost.detach()
        } catch (_: Throwable) {
        }
        logThrottled("sb-restore", "sb-restore keys=$n")
    }

    // ------------------------------------------------------------ 根视图侧（通知图标 / 边距）

    /**
     * 侧树（锁屏 / 下拉顶栏）：容器沿父链上溯已知根 id，命中即对该根跑根侧应用
     * （锁屏树无 end_side_content 挂 status_icon_area；下拉栏电池 id=batteryRemainingIcon）。
     */
    private fun applySideRoots(container: View, cfg: StatusBarConfig) {
        try {
            findAncestorRoot(container, "keyguard_header")?.let { applyRoot(it, cfg) }
            findAncestorRoot(container, "quick_qs_status_icons")?.let { applyRoot(it, cfg) }
            findAncestorRoot(container, "header")?.let { applyRoot(it, cfg) }
        } catch (t: Throwable) {
            logThrottled("sb-side-err", "sb-skip side root ${t.javaClass.simpleName}")
        }
    }

    /**
     * 下拉第一行左侧容器（运营商所在行）的缩放与偏移。
     * 默认值（100%/0/0）零写入；改过则记原变换，回默认或关闸时还原。
     */
    private fun applyQsClock(root: View, cfg: StatusBarConfig) {
        val v = childView(root, "qs_clock_container")
        if (v == null) {
            logThrottled("sb-qsclock-miss", "sb-qsclock skip 容器缺失")
            return
        }
        if (!cfg.qsClockTouched) {
            if (originalTransform.containsKey(v)) {
                restoreTransform(v)
                logThrottled("sb-qsclock-restore", "sb-qsclock restore 回系统原样")
            }
            return
        }
        val density = root.resources.displayMetrics.density
        val tx = cfg.qsClockDxDp * density
        val ty = cfg.qsClockDyDp * density
        originalTransform.getOrPut(v) {
            floatArrayOf(v.scaleX, v.scaleY, v.translationX, v.translationY)
        }
        if (v.scaleX == cfg.qsClockScale && v.scaleY == cfg.qsClockScale &&
            v.translationX == tx && v.translationY == ty
        ) {
            return
        }
        try {
            v.pivotX = 0f
            v.pivotY = v.height / 2f
            v.scaleX = cfg.qsClockScale
            v.scaleY = cfg.qsClockScale
            v.translationX = tx
            v.translationY = ty
            logThrottled(
                "sb-qsclock-apply",
                "sb-qsclock apply scale=${cfg.qsClockScale} dx=${cfg.qsClockDxDp}dp dy=${cfg.qsClockDyDp}dp h=${v.height}",
            )
        } catch (t: Throwable) {
            logThrottled("sb-qsclock-err", "sb-skip qsclock ${t.javaClass.simpleName}")
        }
    }

    /** 从容器沿父链找指定 id 的根；普通树走不到该 id 时返回 null。 */
    private fun findAncestorRoot(start: View, idName: String): View? {
        val targetId = StatusBarCompat.id(idName)
        if (targetId == 0) return null
        var node: View? = start
        var depth = 0
        while (node != null && depth < 14) {
            if (node.id == targetId) return node
            node = node.parent as? View
            depth++
        }
        return null
    }

    private fun applyRoot(root: View, cfg: StatusBarConfig) {
        try {
            val notifyArea = childView(root, "notification_icon_area")
            applyVisibility(notifyArea, cfg.hideNotificationIcons, "hide_notification_icons")
            if (!cfg.hideNotificationIcons) restoreVisibility(notifyArea)
        } catch (t: Throwable) {
            logThrottled("sb-apply-root", "sb-skip root ${t.javaClass.simpleName}")
        }
        try {
            val battery = childView(root, "battery") ?: childView(root, "batteryRemainingIcon")
            applyVisibility(battery, cfg.duoEnable, "duo_battery")
            if (!cfg.duoEnable) restoreVisibility(battery)
        } catch (t: Throwable) {
            logThrottled("sb-apply-battery", "sb-skip battery ${t.javaClass.simpleName}")
        }
        try {
            StatusBarDuoHost.sync(root, cfg)
        } catch (t: Throwable) {
            logThrottled("sb-apply-duo", "sb-skip duo ${t.javaClass.simpleName}")
        }
        try {
            applyNotificationPosition(root, cfg)
        } catch (t: Throwable) {
            logThrottled("sb-apply-notify-pos", "sb-skip notify_pos ${t.javaClass.simpleName}")
        }
        try {
            applyClockSeconds(root, cfg)
        } catch (t: Throwable) {
            logThrottled("sb-apply-clock", "sb-skip clock ${t.javaClass.simpleName}")
        }
        try {
            applyQsClock(root, cfg)
        } catch (t: Throwable) {
            logThrottled("sb-apply-qsclock", "sb-skip qs_clock ${t.javaClass.simpleName}")
        }
        try {
            applyNotifClock(root, cfg)
        } catch (t: Throwable) {
            logThrottled("sb-apply-notifclock", "sb-skip notif_clock ${t.javaClass.simpleName}")
        }
        applyMargins(root, cfg)
    }

    // ------------------------------------------------------------ 通知中心大时钟（分离式面板）

    /** 通知中心时钟的原约束快照（按 View 记，关功能时逐项还原）。 */
    private class NotifClockOrig(
        val startToStart: Int, val startToEnd: Int,
        val endToStart: Int, val endToEnd: Int,
        val topToTop: Int, val topToBottom: Int,
        val bottomToTop: Int, val bottomToBottom: Int,
        val baselineToBaseline: Int,
        val marginStart: Int, val marginEnd: Int, val topMargin: Int, val bottomMargin: Int,
        val width: Int, val height: Int,
        val horizontalBias: Float,
        val scaleX: Float, val scaleY: Float, val pivotX: Float, val pivotY: Float,
        val textSizePx: Float,
        val visibility: Int,
    )

    private val notifClockOrig = WeakHashMap<View, NotifClockOrig>()

    /** ConstraintLayout.LayoutParams 字段读写器（宿主自带该类，反射一次缓存）。 */
    private object ConstraintLp {
        private val fields = ConcurrentHashMap<String, java.lang.reflect.Field>()

        fun isInstance(lp: Any?): Boolean =
            lp != null && lp.javaClass.name == "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"

        private fun field(lp: Any, name: String): java.lang.reflect.Field? =
            fields.getOrPut(name) {
                try {
                    var c: Class<*>? = lp.javaClass
                    while (c != null) {
                        try {
                            return@getOrPut c.getDeclaredField(name).apply { isAccessible = true }
                        } catch (_: NoSuchFieldException) {
                            c = c.superclass
                        }
                    }
                    throw NoSuchFieldException(name)
                } catch (t: Throwable) {
                    throw t
                }
            }

        fun int(lp: Any, name: String, def: Int): Int = try {
            field(lp, name)?.getInt(lp) ?: def
        } catch (_: Throwable) {
            def
        }

        fun float(lp: Any, name: String, def: Float): Float = try {
            field(lp, name)?.getFloat(lp) ?: def
        } catch (_: Throwable) {
            def
        }

        fun setInt(lp: Any, name: String, value: Int): Boolean = try {
            field(lp, name)?.setInt(lp, value)
            true
        } catch (_: Throwable) {
            false
        }

        fun setFloat(lp: Any, name: String, value: Float): Boolean = try {
            field(lp, name)?.setFloat(lp, value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private const val UNSET = -1

    /**
     * 通知中心大时钟与日期：容器 settings_button_container 内的 qs_footer_clock / oplus_date。
     * 大小走 scale（系统自己在下拉过程逐帧写 scale，这里按倍数叠加而非覆盖）；
     * 位置与日期上下排走约束改写；全部先记原值，关闸或回默认逐项还原。
     */
    private fun applyNotifClock(root: View, cfg: StatusBarConfig) {
        val container = childView(root, "settings_button_container") as? ViewGroup
        if (container == null) {
            if (cfg.notifClockTouched || (cfg.notifClockEnable && cfg.notifHideSettingsBtn)) {
                logThrottled("sb-notifclock-miss", "sb-notifclock skip 容器缺失 settings_button_container")
            }
            return
        }
        val clock = childView(container, "qs_footer_clock") as? android.widget.TextView
        val date = childView(container, "oplus_date") as? android.widget.TextView
        val settings = childView(container, "settings_button")
        if (clock == null) {
            logThrottled("sb-notifclock-noclock", "sb-notifclock skip 时钟缺失 qs_footer_clock")
            return
        }
        val active = cfg.notifClockEnable
        if (!active) {
            restoreNotifClock(clock, date, settings)
            return
        }
        try {
            applyNotifHideSettings(settings, cfg.notifHideSettingsBtn)
        } catch (t: Throwable) {
            logThrottled("sb-notifsettings-err", "sb-skip notif_settings ${t.javaClass.simpleName}")
        }
        if (!cfg.notifClockTouched) {
            restoreNotifClock(clock, date, null)
            return
        }
        val density = root.resources.displayMetrics.density
        noteNotifOrig(clock)
        if (date != null) noteNotifOrig(date)
        try {
            applyNotifClockScale(clock, cfg.notifClockScale)
        } catch (t: Throwable) {
            logThrottled("sb-notifscale-err", "sb-skip notif_scale ${t.javaClass.simpleName}")
        }
        try {
            applyNotifDateStyle(date, cfg, density)
        } catch (t: Throwable) {
            logThrottled("sb-notifdate-err", "sb-skip notif_date ${t.javaClass.simpleName}")
        }
        try {
            applyNotifLayout(container, clock, date, cfg, density)
        } catch (t: Throwable) {
            logThrottled("sb-notiflayout-err", "sb-skip notif_layout ${t.javaClass.simpleName}")
        }
    }

    private fun noteNotifOrig(v: View) {
        if (notifClockOrig.containsKey(v)) return
        val lp = v.layoutParams
        val tv = v as? android.widget.TextView
        notifClockOrig[v] = NotifClockOrig(
            startToStart = if (lp != null) ConstraintLp.int(lp, "startToStart", UNSET) else UNSET,
            startToEnd = if (lp != null) ConstraintLp.int(lp, "startToEnd", UNSET) else UNSET,
            endToStart = if (lp != null) ConstraintLp.int(lp, "endToStart", UNSET) else UNSET,
            endToEnd = if (lp != null) ConstraintLp.int(lp, "endToEnd", UNSET) else UNSET,
            topToTop = if (lp != null) ConstraintLp.int(lp, "topToTop", UNSET) else UNSET,
            topToBottom = if (lp != null) ConstraintLp.int(lp, "topToBottom", UNSET) else UNSET,
            bottomToTop = if (lp != null) ConstraintLp.int(lp, "bottomToTop", UNSET) else UNSET,
            bottomToBottom = if (lp != null) ConstraintLp.int(lp, "bottomToBottom", UNSET) else UNSET,
            baselineToBaseline = if (lp != null) ConstraintLp.int(lp, "baselineToBaseline", UNSET) else UNSET,
            marginStart = (lp as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0,
            marginEnd = (lp as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0,
            topMargin = (lp as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0,
            bottomMargin = (lp as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0,
            width = lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            height = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            horizontalBias = if (lp != null) ConstraintLp.float(lp, "horizontalBias", 0.5f) else 0.5f,
            scaleX = v.scaleX, scaleY = v.scaleY, pivotX = v.pivotX, pivotY = v.pivotY,
            textSizePx = tv?.textSize ?: 0f,
            visibility = v.visibility,
        )
    }

    /**
     * 时钟大小：系统在下拉动画里逐帧写 scaleX/scaleY，这里记它当前值当基准做乘法，
     * 并把上次自己乘过的倍数除掉，避免每次布局累乘放大。
     */
    private fun applyNotifClockScale(clock: View, scale: Float) {
        val last = notifScaleApplied[clock] ?: 1f
        if (last == scale) return
        val baseX = clock.scaleX / last
        val baseY = clock.scaleY / last
        clock.pivotX = 0f
        clock.pivotY = clock.height / 2f
        clock.scaleX = baseX * scale
        clock.scaleY = baseY * scale
        notifScaleApplied[clock] = scale
        logThrottled("sb-notifscale", "sb-notifscale scale=$scale base=$baseX h=${clock.height}")
    }

    private val notifScaleApplied = WeakHashMap<View, Float>()

    /** 日期字号与显隐：0 = 跟随系统（还原原字号）；位置 3 = 隐藏。 */
    private fun applyNotifDateStyle(date: android.widget.TextView?, cfg: StatusBarConfig, density: Float) {
        if (date == null) return
        val orig = notifClockOrig[date]
        val hide = cfg.notifDatePosition == 3
        val wantVis = if (hide) View.GONE else (orig?.visibility ?: View.VISIBLE)
        if (date.visibility != wantVis) {
            date.visibility = wantVis
            logThrottled("sb-notifdate-vis", "sb-notifdate vis=$wantVis")
        }
        if (hide) return
        val wantPx = if (cfg.notifDateSizeSp > 0) cfg.notifDateSizeSp * density else (orig?.textSizePx ?: 0f)
        if (wantPx > 0f && kotlin.math.abs(date.textSize - wantPx) > 0.5f) {
            date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, wantPx)
            logThrottled("sb-notifdate-size", "sb-notifdate size=${cfg.notifDateSizeSp}sp px=$wantPx")
        }
    }

    /** 水平位置与日期上下排：改 ConstraintLayout 约束（非 ConstraintLayout 宿主直接降级）。 */
    private fun applyNotifLayout(
        container: ViewGroup,
        clock: android.widget.TextView,
        date: android.widget.TextView?,
        cfg: StatusBarConfig,
        density: Float,
    ) {
        val clockLp = clock.layoutParams
        if (!ConstraintLp.isInstance(clockLp)) {
            logThrottled("sb-notiflayout-lp", "sb-notiflayout skip lp=${clockLp?.javaClass?.simpleName}")
            return
        }
        val parentId = container.id
        val clockId = clock.id
        val settingsId = childView(container, "settings_button")?.id ?: UNSET
        val gapPx = (cfg.notifClockDateGapDp * density).toInt()
        val vertical = cfg.notifDatePosition == 1 || cfg.notifDatePosition == 2
        val dateAbove = cfg.notifDatePosition == 1
        var changed = false

        // 时钟水平约束：先清四向，再按目标方向重建
        changed = ConstraintLp.setInt(clockLp, "startToStart", UNSET) || changed
        changed = ConstraintLp.setInt(clockLp, "startToEnd", UNSET) || changed
        changed = ConstraintLp.setInt(clockLp, "endToStart", UNSET) || changed
        changed = ConstraintLp.setInt(clockLp, "endToEnd", UNSET) || changed
        when (cfg.notifClockGravity) {
            1 -> {
                changed = ConstraintLp.setInt(clockLp, "startToStart", parentId) || changed
            }
            2 -> {
                changed = ConstraintLp.setInt(clockLp, "startToStart", parentId) || changed
                changed = ConstraintLp.setInt(clockLp, "endToEnd", parentId) || changed
                changed = ConstraintLp.setFloat(clockLp, "horizontalBias", 0.5f) || changed
            }
            3 -> {
                changed = ConstraintLp.setInt(clockLp, "endToEnd", parentId) || changed
            }
            else -> {
                val o = notifClockOrig[clock]
                if (o != null) {
                    ConstraintLp.setInt(clockLp, "startToStart", o.startToStart)
                    ConstraintLp.setInt(clockLp, "startToEnd", o.startToEnd)
                    ConstraintLp.setInt(clockLp, "endToStart", o.endToStart)
                    ConstraintLp.setInt(clockLp, "endToEnd", o.endToEnd)
                    ConstraintLp.setFloat(clockLp, "horizontalBias", o.horizontalBias)
                }
            }
        }

        if (date != null && cfg.notifDatePosition != 3) {
            val dateLp = date.layoutParams
            if (ConstraintLp.isInstance(dateLp)) {
                val dateId = date.id
                ConstraintLp.setInt(dateLp, "startToStart", UNSET)
                ConstraintLp.setInt(dateLp, "startToEnd", UNSET)
                ConstraintLp.setInt(dateLp, "endToStart", UNSET)
                ConstraintLp.setInt(dateLp, "endToEnd", UNSET)
                ConstraintLp.setInt(dateLp, "baselineToBaseline", UNSET)
                ConstraintLp.setInt(dateLp, "topToTop", UNSET)
                ConstraintLp.setInt(dateLp, "topToBottom", UNSET)
                ConstraintLp.setInt(dateLp, "bottomToTop", UNSET)
                ConstraintLp.setInt(dateLp, "bottomToBottom", UNSET)
                val dm = dateLp as? ViewGroup.MarginLayoutParams
                if (vertical) {
                    // 上下排：日期与时钟同侧对齐，纵向靠边，间距走 margin
                    ConstraintLp.setInt(dateLp, "startToStart", clockId)
                    ConstraintLp.setInt(dateLp, "endToEnd", clockId)
                    ConstraintLp.setFloat(dateLp, "horizontalBias", 0f)
                    if (dateAbove) {
                        ConstraintLp.setInt(dateLp, "bottomToTop", clockId)
                        ConstraintLp.setInt(dateLp, "topToTop", parentId)
                        dm?.bottomMargin = gapPx
                        dm?.topMargin = 0
                    } else {
                        ConstraintLp.setInt(dateLp, "topToBottom", clockId)
                        ConstraintLp.setInt(dateLp, "bottomToBottom", parentId)
                        dm?.topMargin = gapPx
                        dm?.bottomMargin = 0
                    }
                    dm?.marginStart = 0
                    dm?.marginEnd = 0
                    // 纵向排布时时钟不再撑满高度，避免与日期争位
                    ConstraintLp.setInt(clockLp, "topToTop", if (dateAbove) UNSET else parentId)
                    ConstraintLp.setInt(clockLp, "bottomToBottom", if (dateAbove) parentId else UNSET)
                    ConstraintLp.setInt(clockLp, "topToBottom", if (dateAbove) dateId else UNSET)
                    ConstraintLp.setInt(clockLp, "bottomToTop", if (dateAbove) UNSET else dateId)
                    changed = true
                } else {
                    // 同行：日期接在时钟右侧，基线对齐，间距走 marginStart
                    ConstraintLp.setInt(dateLp, "startToEnd", clockId)
                    if (settingsId != UNSET) ConstraintLp.setInt(dateLp, "endToStart", settingsId)
                    ConstraintLp.setInt(dateLp, "baselineToBaseline", clockId)
                    dm?.marginStart = gapPx
                    dm?.topMargin = 0
                    dm?.bottomMargin = 0
                    val o = notifClockOrig[clock]
                    if (o != null) {
                        ConstraintLp.setInt(clockLp, "topToTop", o.topToTop)
                        ConstraintLp.setInt(clockLp, "topToBottom", o.topToBottom)
                        ConstraintLp.setInt(clockLp, "bottomToTop", o.bottomToTop)
                        ConstraintLp.setInt(clockLp, "bottomToBottom", o.bottomToBottom)
                    }
                    changed = true
                }
                date.layoutParams = dateLp
            }
        }
        if (changed) {
            clock.layoutParams = clockLp
            container.requestLayout()
            logThrottled(
                "sb-notiflayout",
                "sb-notiflayout gravity=${cfg.notifClockGravity} datePos=${cfg.notifDatePosition} gap=${cfg.notifClockDateGapDp}dp",
            )
        }
    }

    /** 隐藏通知设置按钮（记原可见性，关闭时还原）。 */
    private fun applyNotifHideSettings(settings: View?, hide: Boolean) {
        if (settings == null) {
            if (hide) logThrottled("sb-notifsettings-miss", "sb-notifsettings skip 按钮缺失 settings_button")
            return
        }
        if (hide) {
            if (!notifSettingsOrig.containsKey(settings)) notifSettingsOrig[settings] = settings.visibility
            if (settings.visibility != View.GONE) {
                settings.visibility = View.GONE
                logThrottled("sb-notifsettings", "sb-notifsettings hidden")
            }
        } else {
            val o = notifSettingsOrig.remove(settings) ?: return
            if (settings.visibility != o) {
                settings.visibility = o
                logThrottled("sb-notifsettings-restore", "sb-notifsettings restore vis=$o")
            }
        }
    }

    private val notifSettingsOrig = WeakHashMap<View, Int>()

    /** 通知中心时钟整体还原：约束、margin、尺寸、缩放、字号、可见性逐项回原值。 */
    private fun restoreNotifClock(clock: View?, date: View?, settings: View?) {
        listOfNotNull(clock, date).forEach { v ->
            val o = notifClockOrig.remove(v) ?: return@forEach
            try {
                val lp = v.layoutParams
                if (ConstraintLp.isInstance(lp)) {
                    ConstraintLp.setInt(lp, "startToStart", o.startToStart)
                    ConstraintLp.setInt(lp, "startToEnd", o.startToEnd)
                    ConstraintLp.setInt(lp, "endToStart", o.endToStart)
                    ConstraintLp.setInt(lp, "endToEnd", o.endToEnd)
                    ConstraintLp.setInt(lp, "topToTop", o.topToTop)
                    ConstraintLp.setInt(lp, "topToBottom", o.topToBottom)
                    ConstraintLp.setInt(lp, "bottomToTop", o.bottomToTop)
                    ConstraintLp.setInt(lp, "bottomToBottom", o.bottomToBottom)
                    ConstraintLp.setInt(lp, "baselineToBaseline", o.baselineToBaseline)
                    ConstraintLp.setFloat(lp, "horizontalBias", o.horizontalBias)
                    (lp as? ViewGroup.MarginLayoutParams)?.let {
                        it.marginStart = o.marginStart
                        it.marginEnd = o.marginEnd
                        it.topMargin = o.topMargin
                        it.bottomMargin = o.bottomMargin
                    }
                    lp.width = o.width
                    lp.height = o.height
                    v.layoutParams = lp
                }
                v.scaleX = o.scaleX
                v.scaleY = o.scaleY
                v.pivotX = o.pivotX
                v.pivotY = o.pivotY
                notifScaleApplied.remove(v)
                if (v is android.widget.TextView && o.textSizePx > 0f) {
                    v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, o.textSizePx)
                }
                v.visibility = o.visibility
                (v.parent as? View)?.requestLayout()
            } catch (t: Throwable) {
                logThrottled("sb-notifrestore-err", "sb-skip notif_restore ${t.javaClass.simpleName}")
            }
        }
        if (settings != null) {
            val o = notifSettingsOrig.remove(settings)
            if (o != null) settings.visibility = o
        }
        if (clock != null || date != null) logThrottled("sb-notifrestore", "sb-notifclock restore 回系统原样")
    }

    /** 通知图标区移到右侧容器首位（记原父/原序，关闭时原位还原）。 */
    private fun applyNotificationPosition(root: View, cfg: StatusBarConfig) {
        val area = childView(root, "notification_icon_area") ?: return
        val current = area.parent as? ViewGroup
        if (!cfg.notificationIconsRight) {
            val origParent = notifyOriginalParent?.get() ?: return
            notifyOriginalParent = null
            if (current === origParent) return
            try {
                current?.removeView(area)
                origParent.addView(area, notifyOriginalIndex.coerceIn(0, origParent.childCount))
                logThrottled("sb-restore-notify-pos", "sb-restore notify_pos")
            } catch (t: Throwable) {
                logThrottled("sb-restore-notify-pos-err", "sb-skip notify_pos restore ${t.javaClass.simpleName}")
            }
            return
        }
        val end = childView(root, "status_bar_end_side_content") as? ViewGroup ?: return
        if (current === end) return
        if (notifyOriginalParent == null) {
            notifyOriginalParent = WeakReference(current)
            notifyOriginalIndex = current?.indexOfChild(area) ?: -1
        }
        try {
            current?.removeView(area)
            end.addView(area, 0)
            logThrottled("sb-apply-notify-pos", "sb-apply notify_pos right")
        } catch (t: Throwable) {
            logThrottled("sb-apply-notify-pos-err", "sb-skip notify_pos ${t.javaClass.simpleName}")
        }
    }

    /** 时钟显示秒：调用系统公开开关，只在状态切换时写一次，关闭时恢复原值。 */
    private fun applyClockSeconds(root: View, cfg: StatusBarConfig) {
        if (!StatusBarCompat.structure().profile.hasClockSeconds) {
            if (cfg.clockShowSeconds) {
                logThrottled("sb-clock-sec-unsupported", "sb-skip clock_seconds 档位无该方法")
            }
            return
        }
        val clock = childView(root, "clock") ?: return
        if (cfg.clockShowSeconds) {
            if (clockSecondsApplied.contains(clock)) return
            val original = readBoolField(clock, "mShowSeconds") ?: false
            clockSecondsOriginal[clock] = original
            if (invokeBool(clock, "setShowSecondsAndUpdate", true)) {
                clockSecondsApplied[clock] = true
                logThrottled("sb-apply-clock-seconds", "sb-apply clock_seconds original=$original")
            }
            return
        }
        if (clockSecondsApplied.remove(clock) != true) return
        val original = clockSecondsOriginal.remove(clock) ?: false
        invokeBool(clock, "setShowSecondsAndUpdate", original)
        logThrottled("sb-restore-clock-seconds", "sb-restore clock_seconds original=$original")
    }

    private fun readBoolField(target: Any, name: String): Boolean? = try {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.getBoolean(target)
    } catch (_: Throwable) {
        null
    }

    private fun invokeBool(target: Any, name: String, value: Boolean): Boolean = try {
        val m = target.javaClass.getMethod(name, Boolean::class.javaPrimitiveType)
        m.invoke(target, value)
        true
    } catch (_: Throwable) {
        false
    }

    private fun applyMargins(root: View, cfg: StatusBarConfig) {
        val density = root.resources.displayMetrics.density
        applyContainerPadding(root, "status_bar_start_side_container", cfg.marginLeftDp, cfg.marginRightDp, density)
        applyContainerPadding(root, "status_bar_end_side_container", cfg.marginLeftDp, cfg.marginRightDp, density)
        // 控制中心顶栏走独立键：header 已 clipToPadding=false，内缩不裁子 View
        applyContainerPadding(root, "header", cfg.ccMarginLeftDp, cfg.ccMarginRightDp, density)
    }

    /** 左右边距：dp < 0 = 用记录的原值（即系统默认）。只改水平方向。 */
    private fun applyContainerPadding(root: View, idName: String, leftDp: Int, rightDp: Int, density: Float) {
        val v = childView(root, idName) ?: return
        if (leftDp < 0 && rightDp < 0) {
            // 未配置自定义边距：绝不碰系统 padding（含 cutout 避让的动态调整）
            if (paddingTouched.remove(v) == true) {
                val orig = originalPadding.remove(v)
                if (orig != null && (v.paddingLeft != orig[0] || v.paddingRight != orig[2])) {
                    try {
                        v.setPadding(orig[0], v.paddingTop, orig[2], v.paddingBottom)
                        logThrottled("sb-apply-margin", "sb-restore margin $idName")
                    } catch (t: Throwable) {
                        logThrottled("sb-apply-margin-err", "sb-skip margin restore $idName ${t.javaClass.simpleName}")
                    }
                }
            }
            return
        }
        val orig = originalPadding.getOrPut(v) { intArrayOf(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom) }
        val left = if (leftDp < 0) orig[0] else (leftDp * density).toInt()
        val right = if (rightDp < 0) orig[2] else (rightDp * density).toInt()
        logThrottled(
            "sb-margin-probe-$idName",
            "sb-margin probe $idName cur=${v.paddingLeft},${v.paddingRight} orig=${orig[0]},${orig[2]} target=$left,$right cfg=$leftDp,$rightDp",
        )
        if (v.paddingLeft != left || v.paddingRight != right) {
            try {
                v.setPadding(left, v.paddingTop, right, v.paddingBottom)
                paddingTouched[v] = true
                logThrottled("sb-apply-margin", "sb-apply margin $idName left=${leftDp}dp right=${rightDp}dp")
            } catch (t: Throwable) {
                logThrottled("sb-apply-margin-err", "sb-skip margin $idName ${t.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------ 图标容器侧（复合视图内部元素）

    private fun applyContainer(container: View, cfg: StatusBarConfig) {
        if (container !is ViewGroup) return
        val count = container.childCount
        val mobiles = ArrayList<View>(2)
        for (i in 0 until count) {
            val child = try {
                container.getChildAt(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            try {
                when {
                    child.isMobileComposite() -> {
                        mobiles.add(child)
                        applyMobile(child, cfg)
                    }
                    child.isWifiComposite() -> applyWifi(child, cfg)
                    else -> Unit
                }
            } catch (t: Throwable) {
                logThrottled("sb-apply-child", "sb-skip child[$i] ${t.javaClass.simpleName}")
            }
        }
        try {
            applyDualSim(mobiles, cfg)
        } catch (t: Throwable) {
            logThrottled("sb-apply-dual-err", "sb-skip dual ${t.javaClass.simpleName}")
        }
        try {
            applyOrder(container, cfg)
        } catch (t: Throwable) {
            logThrottled("sb-apply-order-err", "sb-skip order ${t.javaClass.simpleName}")
        }
    }

    private fun applyMobile(v: View, cfg: StatusBarConfig) {
        if (cfg.duoEnable) {
            applyVisibility(v, true, "duo_mobile")
            return
        }
        restoreVisibility(v)

        applyVisibility(v.view("mobile_type"), cfg.hideNetworkType, "hide_network_type")
        if (!cfg.hideNetworkType) restoreVisibility(v.view("mobile_type"))

        applyVisibility(v.view("mobile_roaming"), cfg.hideRoaming, "hide_roaming")
        applyVisibility(v.view("mobile_roaming_large"), cfg.hideRoaming, "hide_roaming")
        applyVisibility(v.view("mobile_roaming_space"), cfg.hideRoaming, "hide_roaming")
        if (!cfg.hideRoaming) {
            restoreVisibility(v.view("mobile_roaming"))
            restoreVisibility(v.view("mobile_roaming_large"))
            restoreVisibility(v.view("mobile_roaming_space"))
        }

        applyVisibility(v.view("data_inout"), cfg.hideDataActivity, "hide_data_activity")
        if (!cfg.hideDataActivity) restoreVisibility(v.view("data_inout"))

        // 网络类型移到信号图标右侧（不改隐藏逻辑；两者同时开启时隐藏优先）
        try {
            applyTypeRightOfSignal(v, cfg.typeRightOfSignal && !cfg.hideNetworkType)
        } catch (t: Throwable) {
            logThrottled("sb-type-right-err", "sb-skip type_right ${t.javaClass.simpleName}")
        }

        // 整卡按网络状态隐藏：数据关闭 / Wi-Fi 连接（未开启相关开关时不查系统服务）
        val hideByState = if (cfg.hideMobileWhenDataOff || cfg.hideMobileWhenWifi) {
            (cfg.hideMobileWhenDataOff && !StatusBarState.dataEnabled()) ||
                (cfg.hideMobileWhenWifi && StatusBarState.wifiConnected())
        } else {
            false
        }
        applyVisibility(v, hideByState, "hide_mobile_state")
        if (!hideByState) restoreVisibility(v)

        // 保持副卡 5G 标识（同一 mobile_type 视图，隐藏开关优先）
        val type = v.view("mobile_type")
        if (cfg.keepSim2FiveG && !cfg.hideNetworkType) {
            if (type != null && isSecondarySub(v)) forceVisible(type, "keep_sim2_5g")
        } else if (!cfg.hideNetworkType) {
            restoreVisibility(type)
        }
    }

    private fun applyWifi(v: View, cfg: StatusBarConfig) {
        if (cfg.duoEnable) {
            applyVisibility(v, true, "duo_wifi")
            return
        }
        restoreVisibility(v)

        applyVisibility(v.view("wifi_inout"), cfg.hideWifiActivity, "hide_wifi_activity")
        if (!cfg.hideWifiActivity) restoreVisibility(v.view("wifi_inout"))

        applyVisibility(v.view("wifi_left"), cfg.hideWifiTech, "hide_wifi_tech")
        if (!cfg.hideWifiTech) restoreVisibility(v.view("wifi_left"))
    }

    // ------------------------------------------------------------ 网络类型位置

    private fun applyTypeRightOfSignal(mobile: View, on: Boolean) {
        val frame = mobile.view("mobile_combo_real") as? FrameLayout ?: return
        val signal = mobile.view("mobile_signal") ?: return
        val type = mobile.view("mobile_type") ?: return
        if (!on) {
            val orig = originalTypeLayout.remove(frame) ?: return
            restoreTypeRightOf(frame, orig)
            return
        }
        val signalWidth = signal.width
        val typeWidth = type.width
        if (signalWidth <= 0 || typeWidth <= 0) return
        val lpSignal = signal.layoutParams as? FrameLayout.LayoutParams ?: return
        val lpType = type.layoutParams as? FrameLayout.LayoutParams ?: return
        val frameLp = frame.layoutParams ?: return
        val gap = maxOf(2, (typeWidth * 0.10f).toInt())
        val desiredWidth = signalWidth + gap + typeWidth
        if (!originalTypeLayout.containsKey(frame)) {
            originalTypeLayout[frame] = intArrayOf(
                lpSignal.gravity,
                lpType.gravity,
                frameLp.width,
                if (frame.clipChildren) 1 else 0,
                if (frame.clipToPadding) 1 else 0,
            )
        }
        val startCenter = Gravity.START or Gravity.CENTER_VERTICAL
        var changed = false
        if (lpSignal.gravity != startCenter) {
            lpSignal.gravity = startCenter
            signal.layoutParams = lpSignal
            changed = true
        }
        if (lpType.gravity != startCenter) {
            lpType.gravity = startCenter
            type.layoutParams = lpType
            changed = true
        }
        val tx = (signalWidth + gap).toFloat()
        if (type.translationX != tx) {
            type.translationX = tx
            changed = true
        }
        if (frame.clipChildren) {
            frame.clipChildren = false
            changed = true
        }
        if (frame.clipToPadding) {
            frame.clipToPadding = false
            changed = true
        }
        if (frameLp.width != desiredWidth) {
            frameLp.width = desiredWidth
            frame.layoutParams = frameLp
            changed = true
        }
        if (changed) {
            logThrottled("sb-apply-type-right", "sb-apply type_right w=$desiredWidth gap=$gap")
        }
    }

    private fun restoreTypeRightOf(frame: FrameLayout, o: IntArray) {
        val signal = frame.view("mobile_signal")
        val type = frame.view("mobile_type")
        try {
            (signal?.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.gravity = o[0]
                signal.layoutParams = it
            }
            (type?.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.gravity = o[1]
                type.layoutParams = it
            }
            type?.translationX = 0f
            frame.layoutParams?.let {
                it.width = o[2]
                frame.layoutParams = it
            }
            frame.clipChildren = o[3] == 1
            frame.clipToPadding = o[4] == 1
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ 双卡堆叠

    private fun applyDualSim(mobiles: List<View>, cfg: StatusBarConfig) {
        if (!cfg.dualSimMerge || mobiles.size < 2) {
            mobiles.forEach { restoreTransform(it) }
            return
        }
        val defaultSub = StatusBarState.defaultDataSubId()
        val primary = mobiles.firstOrNull { defaultSub >= 0 && subIdOf(it) == defaultSub } ?: mobiles.first()
        mobiles.forEach { if (it === primary) restoreTransform(it) }
        for (v in mobiles) {
            if (v === primary) continue
            applyStacked(primary, v, cfg)
        }
    }

    /** 副卡叠到主卡下方：缩放 [StatusBarConfig.secondaryScale]，纵向偏移 = 图标高 × [StatusBarConfig.dualRowGap]。 */
    private fun applyStacked(primary: View, secondary: View, cfg: StatusBarConfig) {
        val pw = primary.width
        val ph = primary.height
        val w = secondary.width
        val h = secondary.height
        if (pw <= 0 || ph <= 0 || w <= 0 || h <= 0) return
        val scale = cfg.secondaryScale.coerceIn(0.1f, 1f)
        originalTransform.getOrPut(secondary) {
            floatArrayOf(secondary.scaleX, secondary.scaleY, secondary.translationX, secondary.translationY)
        }
        secondary.pivotX = w / 2f
        secondary.pivotY = h / 2f
        // 容器按 0 起点摆位 + translationX 动画，视觉中心 = left + width/2 + translationX
        val primaryVisualCenter = primary.left + primary.width / 2f + primary.translationX
        val targetTx = primaryVisualCenter - (secondary.left + w / 2f)
        val targetTy = ph * cfg.dualRowGap
        var changed = false
        if (secondary.scaleX != scale || secondary.scaleY != scale) {
            secondary.scaleX = scale
            secondary.scaleY = scale
            changed = true
        }
        if (secondary.translationX != targetTx) {
            secondary.translationX = targetTx
            changed = true
        }
        if (secondary.translationY != targetTy) {
            secondary.translationY = targetTy
            changed = true
        }
        if (changed) {
            logThrottled(
                "sb-apply-dual",
                "sb-apply dual_stack scale=$scale tx=$targetTx ty=$targetTy center=$primaryVisualCenter",
            )
        }
    }

    private fun restoreTransform(v: View) {
        val orig = originalTransform.remove(v) ?: return
        try {
            v.scaleX = orig[0]
            v.scaleY = orig[1]
            v.translationX = orig[2]
            v.translationY = orig[3]
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ 图标排序

    /**
     * 图标排序：只算目标序，结构改动排到下一帧执行。
     * 本函数在 onLayout 回调内被调用，直接改子 View 会重入 requestLayout 导致崩溃。
     */
    private fun applyOrder(container: ViewGroup, cfg: StatusBarConfig) {
        if (container.isInTouchMode && container.hasFocus()) {
            logThrottled("sb-order-defer-touch", "sb-order defer: 容器持有焦点(交互中)")
            return
        }
        if (orderDragActive) {
            logThrottled("sb-order-defer-drag", "sb-order defer: 拖拽进行中")
            return
        }
        if (cfg.iconOrder.isEmpty()) {
            logThrottled("sb-order-empty", "sb-order cfg 为空(走还原) children=${container.childCount}")
            scheduleReorder(container, null)
            return
        }
        val children = collectOrderChildren(container)
        if (children.size < 2) {
            val msg = "sb-order skip 子View不足 n=${children.size} cfg=${cfg.iconOrder.size}项" +
                " duo=${cfg.duoEnable}(三合一开启会隐藏 mobile/wifi 致可排序项不足)"
            logThrottled("sb-order-few", msg)
            orderReceipt(msg)
            return
        }
        if (defaultOrder == null) defaultOrder = children.map { WeakReference(it) }
        if (cfg.iconOrder.contains("battery")) {
            logThrottled("sb-order-battery-unsupported", "sb-order battery 不参与容器排序(系统测量协议不兼容)")
        }
        val rank = HashMap<String, Int>()
        cfg.iconOrder.forEachIndexed { index, name -> rank[name] = index }
        val slots = children.map { slotOf(it) }
        val desired = children.sortedBy { rank[slotOf(it)] ?: Int.MAX_VALUE }
        if (desired == children) {
            logThrottled(
                "sb-order-same",
                "sb-order 已是目标序 slots=${slots.joinToString(",")} cfg=${cfg.iconOrder.joinToString(",")}",
            )
            return
        }
        logThrottled("sb-order-plan", "sb-order plan slots=${slots.joinToString(",")} -> ${desired.joinToString(",") { slotOf(it) }}")
        scheduleReorder(container, desired)
    }

    /** 把重排排到下一帧（脱离 layout 遍历），同容器同目标只排一次。 */
    private fun scheduleReorder(container: ViewGroup, desired: List<View>?) {
        if (reorderPending) {
            logThrottled("sb-order-pending", "sb-order skip 已有待执行重排")
            return
        }
        val key = desired?.joinToString(",") { slotOf(it) } ?: "@restore"
        if (key == lastReorderKey && container === lastReorderContainer?.get()) {
            logThrottled("sb-order-dedup", "sb-order skip 同目标已排过 key=$key")
            return
        }
        reorderPending = true
        lastReorderKey = key
        lastReorderContainer = WeakReference(container)
        container.post {
            reorderPending = false
            try {
                if (orderDragActive) {
                    logThrottled("sb-order-drop-drag", "sb-order drop: 拖拽进行中")
                    return@post
                }
                if (desired == null) doRestoreOrder(container) else doReorder(container, desired)
            } catch (t: Throwable) {
                logThrottled("sb-order-exec-err", "sb-skip order exec ${t.javaClass.simpleName}")
            }
        }
    }

    /** 帧内执行重排：detach/attach 保状态，逐个校验仍在容器内。 */
    private fun doReorder(container: ViewGroup, desired: List<View>) {
        val live = desired.filter { it.parent === container }
        if (live.size < 2) {
            logThrottled("sb-order-stale", "sb-order skip 目标已离树 live=${live.size}/${desired.size}")
            return
        }
        val current = collectOrderChildren(container)
        if (live == current.filter { live.contains(it) } && live.size == current.size) {
            logThrottled("sb-order-already", "sb-order skip 帧内已是目标序 n=${live.size}")
            return
        }
        val detach = orderDetachMethod
        val attach = orderAttachMethod
        if (detach == null || attach == null) {
            try {
                live.forEach { container.removeView(it) }
                live.forEachIndexed { index, v -> container.addView(v, index) }
                container.requestLayout()
                container.invalidate()
                val done = "sb-apply order=${live.joinToString(",") { slotOf(it) }} mode=readd"
                logThrottled("sb-apply-order", done)
                orderReceiptAt = 0L
                orderReceipt(done)
            } catch (t: Throwable) {
                logThrottled("sb-order-readd-err", "sb-skip order readd ${t.javaClass.simpleName}")
            }
            return
        }
        // detach 与 attach 必须成对：逐个记录原 index，任一步失败按原序全部 attach 回去
        val detached = ArrayList<Pair<View, Int>>(live.size)
        var failed = false
        try {
            live.forEach { v ->
                val idx = container.indexOfChild(v)
                detach.invoke(container, v)
                detached.add(v to idx)
            }
            live.forEachIndexed { index, v -> attach.invoke(container, v, index, v.layoutParams) }
        } catch (t: Throwable) {
            failed = true
            logThrottled("sb-order-attach-err", "sb-skip order attach ${t.javaClass.simpleName}")
        }
        if (failed) {
            var restored = 0
            detached.sortedBy { it.second }.forEach { (v, idx) ->
                try {
                    if (v.parent == null) {
                        attach.invoke(container, v, idx.coerceIn(0, container.childCount), v.layoutParams)
                        restored++
                    }
                } catch (_: Throwable) {
                }
            }
            Lg.e(TAG, "sb-order rollback restored=$restored/${detached.size}")
            orderReceiptAt = 0L
            orderReceipt("sb-order rollback restored=$restored/${detached.size}")
            try {
                container.requestLayout()
                container.invalidate()
            } catch (_: Throwable) {
            }
            return
        }
        try {
            container.requestLayout()
            container.invalidate()
        } catch (_: Throwable) {
        }
        val done = "sb-apply order=${live.joinToString(",") { slotOf(it) }} mode=attach"
        logThrottled("sb-apply-order", done)
        orderReceiptAt = 0L
        orderReceipt(done)
    }

    private fun collectOrderChildren(container: ViewGroup): ArrayList<View> {
        val out = ArrayList<View>(container.childCount)
        for (i in 0 until container.childCount) {
            try {
                container.getChildAt(i)?.let { out.add(it) }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /** 关排序：按首次接管前的顺序还原（新出现的 View 排末尾）。 */
    private fun restoreOrder(container: ViewGroup) {
        if (defaultOrder == null) return
        scheduleReorder(container, null)
    }

    private fun doRestoreOrder(container: ViewGroup) {
        val snapshot = defaultOrder ?: return
        defaultOrder = null
        lastReorderKey = null
        val children = collectOrderChildren(container)
        if (children.size < 2) return
        val rank = HashMap<View, Int>()
        snapshot.forEachIndexed { index, ref -> ref.get()?.let { rank[it] = index } }
        val desired = children.sortedBy { rank[it] ?: Int.MAX_VALUE }
        if (desired == children) return
        doReorder(container, desired)
        logThrottled("sb-restore-order", "sb-restore order=${desired.joinToString(",") { slotOf(it) }}")
    }

    /** 排序关键节点落盘回执：logcat 单进程配额会整批丢安装/首帧期日志，文件不受影响。 */
    private var orderReceiptAt = 0L

    private fun orderReceipt(msg: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - orderReceiptAt < 5_000L) return
        orderReceiptAt = now
        try {
            val ctx = Lg.processContext()
            if (ctx == null) {
                logThrottled("sb-order-receipt-noctx", "sb-order-receipt skip: context 未就绪")
                return
            }
            val f = java.io.File(ctx.filesDir, "sb-order-receipt.txt")
            f.appendText("${System.currentTimeMillis()} $msg\n")
            if (f.length() > 16_384L) {
                f.writeText(f.readLines().takeLast(30).joinToString("\n") + "\n")
            }
        } catch (t: Throwable) {
            logThrottled("sb-order-receipt-err", "sb-order-receipt failed ${t.javaClass.simpleName}")
        }
    }

    /** 系统拖拽排序开始/结束（hook 侧回传）：拖拽期间冻结模块重排，避免抢同一批子 View。 */
    fun noteOrderDrag(active: Boolean) {
        if (orderDragActive == active) return
        orderDragActive = active
        logThrottled("sb-order-drag", "sb-order drag=$active")
    }

    private fun slotOf(v: View): String {
        val cls = v.javaClass
        val cached = slotMethod[cls]
        if (cached != null) return invokeSlot(cached, v)
        if (missSlotClasses.containsKey(cls)) return ""
        val method = try {
            cls.getMethod("getSlot")
        } catch (_: Throwable) {
            null
        }
        if (method == null) {
            missSlotClasses[cls] = true
            return ""
        }
        slotMethod[cls] = method
        return invokeSlot(method, v)
    }

    private fun invokeSlot(method: Method, v: View): String = try {
        method.invoke(v) as? String ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun subIdOf(v: View): Int {
        val method = try {
            v.javaClass.getMethod("getSubId")
        } catch (_: Throwable) {
            return -1
        }
        return try {
            (method.invoke(v) as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun isSecondarySub(v: View): Boolean {
        val subId = subIdOf(v)
        val defaultSub = StatusBarState.defaultDataSubId()
        return subId >= 0 && defaultSub >= 0 && subId != defaultSub
    }

    // ------------------------------------------------------------ 基础操作

    private fun View.isMobileComposite(): Boolean =
        javaClass.name.endsWith("ModernStatusBarMobileView")

    private fun View.isWifiComposite(): Boolean =
        javaClass.name.endsWith("ModernStatusBarWifiView")

    private fun View.view(name: String): View? {
        val id = StatusBarCompat.id(name)
        if (id == 0) return null
        return try {
            findViewById<View>(id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun childView(root: View, name: String): View? = root.view(name)

    /** 强制隐藏；hide=false 时由调用方走 [restoreVisibility]（避免把系统后续状态写死）。 */
    private fun applyVisibility(v: View?, hide: Boolean, feature: String) {
        if (v == null) return
        if (!hide) return
        try {
            if (v.visibility != View.GONE) {
                if (!originalVisibility.containsKey(v)) originalVisibility[v] = v.visibility
                v.visibility = View.GONE
                logThrottled("sb-apply-$feature", "sb-apply $feature")
            }
        } catch (t: Throwable) {
            logThrottled("sb-apply-$feature-err", "sb-skip $feature ${t.javaClass.simpleName}")
        }
    }

    /** 闪现闸回压前记系统期望值：锁屏电量可能先被闸压 GONE，根侧来不及记原值（已记则不动）。 */
    fun noteVisibilityOrig(v: View, value: Int) {
        if (!originalVisibility.containsKey(v)) originalVisibility[v] = value
    }

    /** 强制可见（保持副卡 5G 等），原值记录后可还原。 */
    private fun forceVisible(v: View, feature: String) {
        try {
            if (v.visibility != View.VISIBLE) {
                if (!originalVisibility.containsKey(v)) originalVisibility[v] = v.visibility
                v.visibility = View.VISIBLE
                logThrottled("sb-apply-$feature", "sb-apply $feature")
            }
        } catch (t: Throwable) {
            logThrottled("sb-apply-$feature-err", "sb-skip $feature ${t.javaClass.simpleName}")
        }
    }

    /** 还原曾经被我们隐藏的项（只还原我们动过的；系统之后的新状态继续由系统写）。 */
    private fun restoreVisibility(v: View?) {
        if (v == null) return
        val orig = try {
            originalVisibility.remove(v)
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            if (v.visibility == View.GONE && orig != View.GONE) v.visibility = orig
        } catch (_: Throwable) {
        }
    }

    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < 2000L) return
        throttleAt[key] = now
        if (throttleAt.size > 256) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }
}
