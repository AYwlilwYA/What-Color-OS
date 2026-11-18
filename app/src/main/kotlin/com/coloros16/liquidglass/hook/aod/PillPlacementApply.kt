package com.coloros16.liquidglass.hook.aod

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.coloros16.liquidglass.engine.AodStageAnchors
import com.coloros16.liquidglass.engine.edit.EditLayoutSolver
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.PillPlacement
import com.coloros16.liquidglass.log.Lg
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 大丸位置落地：把编辑配置里的归一化中心与缩放写到系统底部按钮上。
 *
 * 只写 translation 与 scale，不改布局参数（避免触发系统重新测量）；
 * 写入前记原值，撤效果时回原值。
 */
object PillPlacementApply {

    private const val TAG = "LgPillApply"
    private const val THROTTLE_MS = 2000L
    private const val EPS = 0.0005f
    private const val PX_EPS = 0.25f

    private const val ID_BOTTOM_AREA = AodStageAnchors.ID_BOTTOM_AREA
    private const val ID_START_BUTTON = AodStageAnchors.ID_START_BUTTON
    private const val ID_END_BUTTON = AodStageAnchors.ID_END_BUTTON
    private const val PKG_SYSTEMUI = AodStageAnchors.PKG_SYSTEMUI

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val ids = ConcurrentHashMap<String, Int>()

    private class Origin(
        val tx: Float,
        val ty: Float,
        val sx: Float,
        val sy: Float,
        val pivotX: Float,
        val pivotY: Float,
    )

    private val origins = HashMap<Int, Origin>()
    private val last = HashMap<Int, FloatArray>()
    private val rect = EditLayoutSolver.Rect()

    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val prev = throttleAt[key]
        if (prev != null && now - prev < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 64) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }

    /**
     * 按配置摆放两侧大丸。
     *
     * 左侧槽位配 start_button、右侧按 x 大小判定；配置里没有的槽位一律还原。
     */
    fun apply(root: View?, settings: LockLayoutSettings) {
        if (root == null) {
            logThrottled("pill-noroot", "根视图为空")
            return
        }
        val area = find(root, ID_BOTTOM_AREA) as? ViewGroup
        if (area == null) {
            logThrottled("pill-noarea", "底部区域未找到 id=$ID_BOTTOM_AREA")
            return
        }
        val start = find(area, ID_START_BUTTON)
        val end = find(area, ID_END_BUTTON)
        if (start == null && end == null) {
            logThrottled("pill-nobutton", "两侧按钮都未找到")
            return
        }
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0f || h <= 0f) {
            logThrottled("pill-nosize", "屏幕尺寸未就绪 ${w}x$h")
            return
        }
        val pills = EditLayoutSolver.enabledPills(settings)
        val left = pills.filter { it.x < 0.5f }.minByOrNull { it.x }
        val right = pills.filter { it.x >= 0.5f }.maxByOrNull { it.x }
        place(start, left, w, h, "start")
        place(end, right, w, h, "end")
    }

    private fun place(
        view: View?,
        pill: PillPlacement?,
        screenW: Float,
        screenH: Float,
        slot: String,
    ) {
        if (view == null) return
        if (pill == null || !EditLayoutSolver.pillRect(pill, screenW, screenH, rect)) {
            restore(view, slot)
            return
        }
        val vw = view.width
        val vh = view.height
        if (vw <= 0 || vh <= 0) {
            logThrottled("pill-unlaid-$slot", "按钮未布局 ${vw}x$vh")
            return
        }
        val parent = view.parent as? View ?: return
        val loc = IntArray(2)
        parent.getLocationOnScreen(loc)
        val dx = rect.centerX - loc[0] - (view.left + vw * 0.5f)
        val dy = rect.centerY - loc[1] - (view.top + vh * 0.5f)
        val scale = if (vw > 0) rect.width / vw else 1f

        val key = System.identityHashCode(view)
        val next = floatArrayOf(dx, dy, scale)
        val prev = last[key]
        if (prev != null &&
            kotlin.math.abs(prev[0] - dx) < PX_EPS &&
            kotlin.math.abs(prev[1] - dy) < PX_EPS &&
            kotlin.math.abs(prev[2] - scale) < EPS
        ) return
        if (!origins.containsKey(key)) {
            origins[key] = Origin(
                view.translationX, view.translationY, view.scaleX, view.scaleY,
                view.pivotX, view.pivotY,
            )
        }
        view.pivotX = vw * 0.5f
        view.pivotY = vh * 0.5f
        view.translationX = dx
        view.translationY = dy
        view.scaleX = scale
        view.scaleY = scale
        last[key] = next
        logThrottled(
            "pill-set-$slot",
            String.format(Locale.US, "d=(%.1f,%.1f) s=%.3f type=%s", dx, dy, scale, pill.typeId),
        )
    }

    /** 本件是否写过属性（供调用方判断要不要撤）。 */
    fun tracked(): Boolean = origins.isNotEmpty()

    /** 撤效果：两侧都还原成写入前的原值。 */
    fun restoreAll(root: View?) {
        if (root == null) return
        val area = find(root, ID_BOTTOM_AREA) as? ViewGroup ?: return
        restore(find(area, ID_START_BUTTON), "start")
        restore(find(area, ID_END_BUTTON), "end")
    }

    private fun restore(view: View?, slot: String) {
        if (view == null) return
        val key = System.identityHashCode(view)
        val origin = origins.remove(key) ?: return
        last.remove(key)
        view.translationX = origin.tx
        view.translationY = origin.ty
        view.scaleX = origin.sx
        view.scaleY = origin.sy
        view.pivotX = origin.pivotX
        view.pivotY = origin.pivotY
        logThrottled("pill-restore-$slot", "已还原")
    }

    fun forget(root: View?) {
        if (root == null) return
        val area = find(root, ID_BOTTOM_AREA) as? ViewGroup ?: return
        for (name in arrayOf(ID_START_BUTTON, ID_END_BUTTON)) {
            val v = find(area, name) ?: continue
            val key = System.identityHashCode(v)
            origins.remove(key)
            last.remove(key)
        }
    }

    private fun find(root: View, name: String): View? {
        val id = id(root, name)
        if (id == 0) return null
        return root.findViewById(id)
    }

    private fun id(view: View, name: String): Int {
        ids[name]?.let { return it }
        val value = runCatching {
            view.resources.getIdentifier(name, "id", PKG_SYSTEMUI)
        }.getOrDefault(0)
        if (value != 0) {
            ids[name] = value
        } else {
            logThrottled("pill-id-miss-$name", "id.$name 解析不到")
        }
        return value
    }
}