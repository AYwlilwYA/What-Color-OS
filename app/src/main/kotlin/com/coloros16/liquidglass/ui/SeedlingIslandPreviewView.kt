package com.coloros16.liquidglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.coloros16.liquidglass.engine.SeedlingActivity
import com.coloros16.liquidglass.engine.SeedlingCardModePolicy
import com.coloros16.liquidglass.engine.SeedlingCompact
import com.coloros16.liquidglass.engine.SeedlingElement
import com.coloros16.liquidglass.engine.SeedlingExpanded
import com.coloros16.liquidglass.engine.SeedlingKind
import com.coloros16.liquidglass.engine.SeedlingRectSpring
import com.coloros16.liquidglass.engine.SeedlingSpring
import com.coloros16.liquidglass.engine.SeedlingTemplate

/**
 * 接管岛 App 内预览：与 hook 共用 engine 求值器（弹簧/内容模型），
 * 自绘胶囊↔大卡形态（材质用简化渐变替代 AGSL，几何/动画/内容排版与真机同源）。
 */
class SeedlingIslandPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val rect = SeedlingRectSpring(0f, 0f, 360f * density / 3f, 40f * density, stiffness = 380f, dampingRatio = 0.62f)
    private val morphSpring = SeedlingSpring(0f, 0f, stiffness = 900f, dampingRatio = 1f)
    private var lastFrameMs = 0L
    private var running = false

    var bounceDp: Float = 5f
        set(value) {
            field = value
            val z = SeedlingSpring.bounceDpToDamping(value)
            rect.x.dampingRatio = z
            rect.y.dampingRatio = z
            rect.w.dampingRatio = z
            rect.h.dampingRatio = z
        }

    var speedScale: Float = 1f

    var activity: SeedlingActivity? = demoMedia()
        set(value) {
            field = value
            retarget()
            invalidate()
        }

    /** 大卡样式模式（与 hook 共用 SeedlingCardModePolicy 求值）。 */
    var cardMode: SeedlingCardModePolicy.Mode = SeedlingCardModePolicy.Mode.APPLE
        set(value) {
            field = value
            retarget()
            invalidate()
        }

    /** 多活动候选（OP 竖排/APPLE 换卡池）；空时回落单 activity。 */
    var candidates: List<SeedlingActivity> = emptyList()
        set(value) {
            field = value
            retarget()
            invalidate()
        }

    /** APPLE 式展开锁定 id（横滑环形轮换）。 */
    private var expandedId: String? = null

    private fun candidatePool(): List<SeedlingActivity> =
        candidates.ifEmpty { listOfNotNull(activity) }

    /** 当前展开显示的活动：APPLE=cardStack 单卡（expandedId 者）；OP=首卡（其余竖排）。 */
    private fun shownActivity(): SeedlingActivity? {
        val pool = candidatePool()
        if (pool.isEmpty()) return null
        return SeedlingCardModePolicy.cardStack(cardMode, pool, expandedId).firstOrNull()
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    private fun capsuleW(): Float {
        val label = activity?.compact?.label ?: "Island"
        textPaint.textSize = 13f * density
        val maxW = (width * 0.6f).coerceAtLeast(120f * density)
        return (textPaint.measureText(label) + 64f * density).coerceIn(120f * density, maxW)
    }

    private fun retarget() {
        // 布局前 width/height=0：setter 触发时跳过，onSizeChanged 会补
        if (width <= 0 || height <= 0) return
        val expand = morphSpring.target > 0.5f
        val w = if (expand) width * 0.7f else capsuleW()
        var h = (if (expand) 180f else 40f) * density
        // OP 竖排 n 卡总高超出预览板时解卡高（engine opCardHeight 与 hook 同源，gap 不缩总高恰=avail）
        if (expand && cardMode == SeedlingCardModePolicy.Mode.OP) {
            val n = candidatePool().size.coerceAtLeast(1)
            val gapPx = 12f * density
            val avail = height - 32f * density
            h = SeedlingCardModePolicy.opCardHeight(n, h, gapPx, avail, 24f * density)
        }
        val left = (width - w) / 2f
        val top = 24f * density
        rect.setTarget(left, top, w, h)
        startFrames()
    }

    fun toggle() {
        morphSpring.setTarget(if (morphSpring.target < 0.5f) 1f else 0f)
        retarget()
    }

    private fun startFrames() {
        if (running) return
        running = true
        lastFrameMs = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.064f) * speedScale
        lastFrameMs = now
        val activeRect = rect.advance(dt)
        val activeMorph = morphSpring.advance(dt)

        val l = rect.x.value
        val t = rect.y.value
        val w = rect.w.value.coerceAtLeast(1f)
        val h = rect.h.value.coerceAtLeast(1f)
        val region = RectF(l, t, l + w, t + h)
        val morph = morphSpring.value.coerceIn(0f, 1f)
        val r = h / 2f + (24f * density - h / 2f) * morph

        // 材质：黑→玻璃渐变（预览用半透明蓝灰渐变示意，真机走 AGSL）
        val glassT = morph * morph * (3f - 2f * morph)
        bgPaint.color = blend(0xE6000000.toInt(), 0xB04A6A8A.toInt(), glassT)
        canvas.drawRoundRect(region, r, r, bgPaint)
        // OP 式展开：先把竖排卡位背景画齐（内容后画盖其上），预览容器需容纳 n 卡
        val opExtras = if (morph > 0.5f && cardMode == SeedlingCardModePolicy.Mode.OP) candidatePool().drop(1) else emptyList()
        opExtras.forEachIndexed { idx, _ ->
            val ct = SeedlingCardModePolicy.stackTop(t, idx + 1, h, 12f * density)
            canvas.drawRoundRect(RectF(l, ct, l + w, ct + h), 24f * density, 24f * density, bgPaint)
        }
        if (glassT > 0f) {
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f * density
                color = 0x60FFFFFF
                alpha = (255 * glassT).toInt()
            }
            canvas.drawRoundRect(region, r, r, strokePaint)
        }

        val act = if (morph > 0.5f) shownActivity() else activity
        if (act == null) {
            textPaint.textSize = 14f * density
            canvas.drawText("Island", l + w / 2f, t + h / 2f + 5f * density, textPaint)
        } else if (morph < 0.5f) {
            textPaint.textSize = 13f * density
            val label = act.compact.label
            var shown = label
            while (textPaint.measureText(shown) > w - 64f * density && shown.length > 4) {
                shown = shown.dropLast(2) + "…"
            }
            canvas.drawText(shown, l + w / 2f, t + h / 2f + 5f * density, textPaint)
        } else {
            val exp = act.expanded
            textPaint.textSize = 16f * density
            canvas.drawText(exp.title, l + w / 2f, t + 30f * density, textPaint)
            textPaint.textSize = 12f * density
            textPaint.alpha = 180
            if (exp.subtitle.isNotEmpty()) canvas.drawText(exp.subtitle, l + w / 2f, t + 50f * density, textPaint)
            textPaint.alpha = 255
            exp.progress?.let { pr ->
                val barL = l + 24f * density
                val barR = l + w - 24f * density
                val barY = t + h - 56f * density
                val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF; strokeWidth = 4f * density; strokeCap = Paint.Cap.ROUND }
                canvas.drawLine(barL, barY, barR, barY, track)
                var acc = 0f
                pr.segments.forEach { seg ->
                    val s0 = barL + (barR - barL) * acc
                    val s1 = barL + (barR - barL) * (acc + seg.fraction)
                    val done = ((pr.fraction - acc) / seg.fraction).coerceIn(0f, 1f)
                    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = seg.colorArgb; strokeWidth = 4f * density; strokeCap = Paint.Cap.ROUND }
                    canvas.drawLine(s0, barY, s0 + (s1 - s0) * done, barY, sp)
                    acc += seg.fraction
                }
            }
            val n = exp.actions.size
            if (n > 0) {
                val btnR = 16f * density
                val gap = w / (n + 1)
                val cy = t + h - 26f * density
                exp.actions.forEachIndexed { i, _ ->
                    val cx = l + gap * (i + 1)
                    val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
                    canvas.drawCircle(cx, cy, btnR, bp)
                }
            }
            // OP 式：竖排其余候选简化卡内容（背景已在前置 pass 画，与 hook drawOpStackExtras 同几何）
            opExtras.forEachIndexed { idx, a ->
                val ct = SeedlingCardModePolicy.stackTop(t, idx + 1, h, 12f * density)
                textPaint.textSize = 15f * density
                canvas.drawText(a.expanded.title.ifEmpty { a.compact.label }, l + w / 2f, ct + 30f * density, textPaint)
                if (a.expanded.subtitle.isNotEmpty()) {
                    textPaint.textSize = 11f * density
                    textPaint.alpha = 170
                    canvas.drawText(a.expanded.subtitle, l + w / 2f, ct + 50f * density, textPaint)
                    textPaint.alpha = 255
                }
            }
        }

        if (activeRect || activeMorph) {
            postInvalidateOnAnimation()
        } else {
            running = false
        }
    }

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                val expanded = morphSpring.target > 0.5f
                // APPLE 式大卡横滑=环形换卡（与 hook CARD_CYCLE 同向：左滑下一张）
                if (expanded && cardMode == SeedlingCardModePolicy.Mode.APPLE &&
                    kotlin.math.abs(dx) > 2 * slop && kotlin.math.abs(dx) > kotlin.math.abs(dy)
                ) {
                    val pool = candidatePool()
                    val newId = SeedlingCardModePolicy.cycleExpanded(cardMode, pool, expandedId ?: shownActivity()?.id, if (dx < 0) 1 else -1)
                    if (newId != null) {
                        expandedId = newId
                        invalidate()
                    }
                    return true
                }
                // OP 式大卡点副卡=提升为主卡
                if (expanded && cardMode == SeedlingCardModePolicy.Mode.OP) {
                    val t = rect.y.value
                    val h = rect.h.value
                    val gapPx = 12f * density
                    val pool = candidatePool()
                    val hit = pool.drop(1).firstOrNull { a ->
                        val idx = pool.indexOf(a)
                        val ct = SeedlingCardModePolicy.stackTop(t, idx, h, gapPx)
                        event.y >= ct && event.y <= ct + h && event.x >= rect.x.value && event.x <= rect.x.value + rect.w.value
                    }
                    if (hit != null) {
                        expandedId = hit.id
                        activity = hit
                        return true
                    }
                }
                performClick()
                toggle()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cw = capsuleW()
        rect.snap((w - cw) / 2f, 24f * density, cw, 40f * density)
        // 布局前 setter 跳过的 retarget 在此补（OP 卡高自适应依赖 height）
        retarget()
        invalidate()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val ia = 1f - t
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * ia + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        /** 预览候选池：覆盖内容真实化的各类活动（媒体/消息/计时/录音/配送/充电/耳机）。 */
        fun demoCandidates(): List<SeedlingActivity> = listOf(
            demoMedia(),
            SeedlingActivity(
                id = "preview:msg", source = "preview", kind = SeedlingKind.MESSAGE,
                compact = SeedlingCompact(leading = SeedlingElement.Symbol("MESSAGE"), label = "新消息：在吗"),
                expanded = SeedlingExpanded(
                    template = SeedlingTemplate.MESSAGE,
                    title = "新消息", subtitle = "在吗？晚上一起吃饭",
                ),
            ),
            SeedlingActivity(
                id = "preview:timer", source = "preview", kind = SeedlingKind.TIMER,
                compact = SeedlingCompact(leading = SeedlingElement.Symbol("TIMER"), label = "计时 03:24"),
                expanded = SeedlingExpanded(
                    template = SeedlingTemplate.TIMER,
                    title = "计时", subtitle = "剩余 03:24",
                ),
            ),
            SeedlingActivity(
                id = "preview:recording", source = "preview", kind = SeedlingKind.RECORDING,
                compact = SeedlingCompact(leading = SeedlingElement.Symbol("MIC"), label = "录音中 00:12"),
                expanded = SeedlingExpanded(
                    template = SeedlingTemplate.GENERIC,
                    title = "录音中", subtitle = "00:12",
                ),
            ),
            SeedlingActivity(
                id = "preview:delivery", source = "preview", kind = SeedlingKind.LIVE_UPDATE,
                compact = SeedlingCompact(leading = SeedlingElement.Symbol("NAVIGATION"), label = "外卖配送中 · 12分钟"),
                expanded = SeedlingExpanded(
                    template = SeedlingTemplate.PROGRESS,
                    title = "外卖配送中", subtitle = "骑手距你 1.2km",
                ),
            ),
            com.coloros16.liquidglass.engine.SeedlingStatusPolicy.chargingActivity(
                68, 2, SystemClock.elapsedRealtime()
            ),
            com.coloros16.liquidglass.engine.SeedlingStatusPolicy.headsetActivity(
                true, SystemClock.elapsedRealtime()
            ),
        )

        fun demoMedia(): SeedlingActivity = SeedlingActivity(
            id = "preview:media",
            source = "preview",
            kind = SeedlingKind.MEDIA,
            compact = SeedlingCompact(
                leading = SeedlingElement.Symbol("MUSIC"),
                label = "夜曲 - 周杰伦",
                trailing = SeedlingElement.Waveform(true),
            ),
            expanded = SeedlingExpanded(
                template = SeedlingTemplate.MEDIA,
                title = "夜曲",
                subtitle = "周杰伦 · 十一月的萧邦",
                durationMs = 227000,
                positionMs = 63000,
                positionAtElapsedMs = SystemClock.elapsedRealtime(),
                playing = true,
                actions = listOf(
                    com.coloros16.liquidglass.engine.SeedlingAction("prev", "上一首", "SKIP_PREV"),
                    com.coloros16.liquidglass.engine.SeedlingAction("play_pause", "暂停", "PAUSE"),
                    com.coloros16.liquidglass.engine.SeedlingAction("next", "下一首", "SKIP_NEXT"),
                ),
            ),
        )
    }
}