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

    /** 首次接管排序前的子 View 顺序（关排序时还原）。 */
    private var defaultOrder: List<WeakReference<View>>? = null

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

    private fun applyRoot(root: View, cfg: StatusBarConfig) {
        try {
            val notifyArea = childView(root, "notification_icon_area")
            applyVisibility(notifyArea, cfg.hideNotificationIcons, "hide_notification_icons")
            if (!cfg.hideNotificationIcons) restoreVisibility(notifyArea)
        } catch (t: Throwable) {
            logThrottled("sb-apply-root", "sb-skip root ${t.javaClass.simpleName}")
        }
        try {
            val battery = childView(root, "battery")
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
        applyMargins(root, cfg)
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
    }

    /** 左右边距：dp < 0 = 用记录的原值（即系统默认）。只改水平方向。 */
    private fun applyContainerPadding(root: View, idName: String, leftDp: Int, rightDp: Int, density: Float) {
        val v = childView(root, idName) ?: return
        val orig = originalPadding.getOrPut(v) { intArrayOf(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom) }
        val left = if (leftDp < 0) orig[0] else (leftDp * density).toInt()
        val right = if (rightDp < 0) orig[2] else (rightDp * density).toInt()
        if (v.paddingLeft != left || v.paddingRight != right) {
            try {
                v.setPadding(left, v.paddingTop, right, v.paddingBottom)
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

    private fun applyOrder(container: ViewGroup, cfg: StatusBarConfig) {
        if (cfg.iconOrder.isEmpty()) {
            restoreOrder(container)
            return
        }
        val count = container.childCount
        if (count < 2) return
        val children = ArrayList<View>(count)
        for (i in 0 until count) {
            try {
                container.getChildAt(i)?.let { children.add(it) }
            } catch (_: Throwable) {
            }
        }
        if (children.size < 2) return
        if (defaultOrder == null) defaultOrder = children.map { WeakReference(it) }
        val rank = HashMap<String, Int>()
        cfg.iconOrder.forEachIndexed { index, name -> rank[name] = index }
        val desired = children.sortedBy { rank[slotOf(it)] ?: Int.MAX_VALUE }
        if (desired == children) return
        if (reorderPending) return
        reorderPending = true
        try {
            children.forEach { container.removeView(it) }
            desired.forEachIndexed { index, v -> container.addView(v, index) }
            logThrottled("sb-apply-order", "sb-apply order=${desired.joinToString(",") { slotOf(it) }}")
        } finally {
            reorderPending = false
        }
    }

    /** 关排序时按首次接管前的子 View 顺序还原（新出现的 View 排在末尾，交给后续系统插入）。 */
    private fun restoreOrder(container: ViewGroup) {
        val snapshot = defaultOrder ?: return
        val count = container.childCount
        if (count < 2) {
            defaultOrder = null
            return
        }
        val rank = HashMap<View, Int>()
        snapshot.forEachIndexed { index, ref -> ref.get()?.let { rank[it] = index } }
        val children = ArrayList<View>(count)
        for (i in 0 until count) {
            try {
                container.getChildAt(i)?.let { children.add(it) }
            } catch (_: Throwable) {
            }
        }
        if (children.size < 2) {
            defaultOrder = null
            return
        }
        val desired = children.sortedBy { rank[it] ?: Int.MAX_VALUE }
        defaultOrder = null
        if (desired == children) return
        if (reorderPending) return
        reorderPending = true
        try {
            children.forEach { container.removeView(it) }
            desired.forEachIndexed { index, v -> container.addView(v, index) }
            logThrottled("sb-restore-order", "sb-restore order=${desired.joinToString(",") { slotOf(it) }}")
        } finally {
            reorderPending = false
        }
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
