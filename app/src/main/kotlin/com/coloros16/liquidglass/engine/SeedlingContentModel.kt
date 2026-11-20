package com.coloros16.liquidglass.engine

/**
 * 接管岛内容模型（纯 JVM，App 预览与 hook 共用）。
 * 活动=一个上岛事件；胶囊内容=左元素+文字+右元素；大卡内容=模板+标题副题+进度+动作。
 */
enum class SeedlingTemplate { MEDIA, CALL, TIMER, MESSAGE, PROGRESS, STATUS, GENERIC, HERO }

/** 内容元素：文字 / 图标 / 波形 / 进度环（图标用符号名，渲染端映射绘制）。 */
sealed class SeedlingElement {
    data class Text(val text: String, val mono: Boolean = false, val scrollable: Boolean = true) : SeedlingElement()
    data class Symbol(val name: String, val spinning: Boolean = false) : SeedlingElement()
    data class Waveform(val active: Boolean) : SeedlingElement()
    data class Ring(val fraction: Float) : SeedlingElement()
}

data class SeedlingCompact(
    val leading: SeedlingElement? = null,
    val label: String = "",
    val trailing: SeedlingElement? = null,
    val contentId: String = "",
)

data class SeedlingAction(
    val id: String,
    val label: String,
    val symbol: String,
    val primary: Boolean = true,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

/** S3 分段进度模型（AOSP ProgressStyle 语义：segments 多色 + points 里程碑 + tracker 移动图标）。 */
data class SeedlingProgress(
    val fraction: Float = 0f,
    val indeterminate: Boolean = false,
    val segments: List<Segment> = emptyList(),
    val points: List<Float> = emptyList(),
    val trackerSymbol: String? = null,
    val startLabel: String = "",
    val endLabel: String = "",
) {
    data class Segment(val fraction: Float, val colorArgb: Int)

    /**  tracker 位置：分段累计里按 fraction 定位（纯 JVM 可单测）。 */
    fun trackerPosition(): Float = fraction.coerceIn(0f, 1f)

    /** 当前落在哪个分段（0-based；空分段返回 -1）。 */
    fun activeSegment(): Int {
        if (segments.isEmpty()) return -1
        var acc = 0f
        segments.forEachIndexed { i, s ->
            acc += s.fraction
            if (fraction <= acc) return i
        }
        return segments.size - 1
    }
}

data class SeedlingExpanded(
    val template: SeedlingTemplate = SeedlingTemplate.GENERIC,
    val title: String = "",
    val subtitle: String = "",
    val body: String = "",
    val progressFraction: Float? = null,
    val progress: SeedlingProgress? = null,
    val milestone: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val positionAtElapsedMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val playing: Boolean = false,
    val countdownEndAtElapsedMs: Long = 0L,
    val lyricsLine: String = "",
    val lyricsTranslation: String = "",
    val actions: List<SeedlingAction> = emptyList(),
)

data class SeedlingActivity(
    val id: String,
    val source: String,
    val kind: SeedlingKind,
    val priority: Int = kind.defaultPriority,
    val exclusive: Boolean = kind.exclusive,
    val startedAtElapsedMs: Long = 0L,
    val updatedAtElapsedMs: Long = 0L,
    val compact: SeedlingCompact = SeedlingCompact(),
    val expanded: SeedlingExpanded = SeedlingExpanded(),
    val expandable: Boolean = true,
    val demoteAfterMs: Long? = null,
    val demotedPriority: Int = 40,
    val navigation: Boolean = false,
    val staleAtElapsedMs: Long = 0L,
    val hideWhenSourceForeground: Boolean = true,
)

/** 三槽布局：主岛 + 左右副岛 + 排队。 */
data class SeedlingLayout(
    val main: SeedlingActivity? = null,
    val left: SeedlingActivity? = null,
    val right: SeedlingActivity? = null,
    val queued: List<SeedlingActivity> = emptyList(),
) {
    val isEmpty: Boolean get() = main == null && left == null && right == null
}

/**
 * 活动调度器：upsert/remove → 优先级择优主岛，其余排队。
 * 评分：被隐藏 -1；展开中 1000；过期降 1000；消息超时降级 demotedPriority；导航类保底 62；同分新者优先。
 * 划掉=进隐藏集（活动保留可召回），remove=活动彻底消失；TIMER 响铃中（priority 85）不可划掉。
 * 同来源消息去重：同 source 的 MESSAGE 只留最新一条。
 */
class SeedlingScheduler {
    private val activities = LinkedHashMap<String, SeedlingActivity>()
    var expandedId: String? = null
    private val hiddenIds = LinkedHashSet<String>()

    /** 当前前台应用包名（进来源隐藏消费；null=未知不隐藏）。 */
    var foregroundPackage: String? = null

    fun upsert(activity: SeedlingActivity) {
        if (activity.kind == SeedlingKind.MESSAGE) {
            val stale = activities.values.filter {
                it.kind == SeedlingKind.MESSAGE && it.source == activity.source && it.id != activity.id
            }
            stale.forEach { remove(it.id) }
        }
        activities[activity.id] = activity
    }

    /** 划掉；TIMER 响铃中（priority==85 且未过期）拒划返回 false。 */
    fun hide(id: String, nowElapsedMs: Long = 0L, staleMs: Long = 0L): Boolean {
        val a = activities[id] ?: return false
        val ringing = a.kind == SeedlingKind.TIMER && a.priority == 85 &&
            !(staleMs > 0 && a.updatedAtElapsedMs > 0 && nowElapsedMs - a.updatedAtElapsedMs > staleMs)
        if (ringing) return false
        hiddenIds.add(id)
        if (expandedId == id) expandedId = null
        return true
    }

    fun recallAll(): Int {
        val n = hiddenIds.size
        hiddenIds.clear()
        return n
    }

    fun isHidden(id: String): Boolean = id in hiddenIds

    fun hiddenCount(): Int = hiddenIds.size

    fun remove(id: String) {
        activities.remove(id)
        hiddenIds.remove(id)
        if (expandedId == id) expandedId = null
    }

    fun clear() {
        activities.clear()
        hiddenIds.clear()
        expandedId = null
    }

    fun get(id: String): SeedlingActivity? = activities[id]

    /** 全部活动快照（过期引擎算最近到期用）。 */
    fun all(): List<SeedlingActivity> = activities.values.toList()

    /** 按来源移除该包全部活动（通知 removed 撤整包，防误撤别条）。 */
    fun removeBySource(source: String): Int {
        val ids = activities.values.filter { it.source == source }.map { it.id }
        ids.forEach { remove(it) }
        return ids.size
    }

    /** 按活动 id 前缀移除（内容源结束时只撤自己那路，防误撤别路）。 */
    fun removeByIdPrefix(prefix: String): Int {
        val ids = activities.keys.filter { it.startsWith(prefix) }
        ids.forEach { remove(it) }
        return ids.size
    }

    fun layout(
        nowElapsedMs: Long,
        staleMs: Long = 0L,
        kindAllowed: (SeedlingKind) -> Boolean = { true },
    ): SeedlingLayout {
        val fg = foregroundPackage
        val candidates = activities.values.filter {
            it.id !in hiddenIds && kindAllowed(it.kind) &&
                !(fg != null && it.hideWhenSourceForeground && it.source == fg)
        }.sortedWith(
            compareByDescending<SeedlingActivity> { score(it, nowElapsedMs, staleMs) }
                .thenByDescending { it.updatedAtElapsedMs },
        )
        val main = candidates.firstOrNull()
        val rest = candidates.drop(1)
        return SeedlingLayout(
            main = main,
            left = rest.getOrNull(0),
            right = rest.getOrNull(1),
            queued = rest.drop(2),
        )
    }

    private fun score(a: SeedlingActivity, now: Long, staleMs: Long): Int {
        if (a.id in hiddenIds) return -1
        if (a.id == expandedId) return 1000
        var s = a.priority
        val demote = a.demoteAfterMs
        if (demote != null && a.updatedAtElapsedMs > 0 && now - a.updatedAtElapsedMs >= demote) {
            s = a.demotedPriority
        }
        if (a.navigation) s = maxOf(s, 62)
        if (a.exclusive) s += 500
        if (staleMs > 0 && a.updatedAtElapsedMs > 0 && now - a.updatedAtElapsedMs > staleMs) s -= 1000
        return s
    }
}