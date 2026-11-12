package com.coloros16.liquidglass.debug

// ---------- 广播通道协议（模块进程 ↔ 注入进程代理） ----------

/** 扫描请求：模块 → 目标包（extra: pkg=目标包名）。 */
const val ACTION_SCAN_REQUEST = "com.coloros16.liquidglass.BALL_SCAN_REQUEST"

/** 扫描结果：目标包 → 模块（extra: pkg / items=JSON数组 / truncated=截断条数）。 */
const val ACTION_SCAN_RESULT = "com.coloros16.liquidglass.BALL_SCAN_RESULT"

/** 固化请求：模块 → 目标包（extra: pkg / cls / id / x / y / w / h，屏幕坐标）。 */
const val ACTION_FREEZE_REQUEST = "com.coloros16.liquidglass.BALL_FREEZE_REQUEST"

/** 固化结果：目标包 → 模块（extra: ok / label / reason）。 */
const val ACTION_FREEZE_RESULT = "com.coloros16.liquidglass.BALL_FREEZE_RESULT"

/** 抓取请求：模块 → 目标包（extra: pkg / x / y 屏幕坐标）。 */
const val ACTION_TOOL_CAPTURE_REQUEST = "com.coloros16.liquidglass.BALL_TOOL_CAPTURE_REQUEST"

/** 抓取结果：目标包 → 模块（extra: ok / text / hasBox / bx / by / bw / bh）。 */
const val ACTION_TOOL_CAPTURE_RESULT = "com.coloros16.liquidglass.BALL_TOOL_CAPTURE_RESULT"

/** UI 树请求：模块 → 目标包（extra: pkg）。 */
const val ACTION_TOOL_TREE_REQUEST = "com.coloros16.liquidglass.BALL_TOOL_TREE_REQUEST"

/** UI 树结果：目标包 → 模块（extra: ok / text）。 */
const val ACTION_TOOL_TREE_RESULT = "com.coloros16.liquidglass.BALL_TOOL_TREE_RESULT"

/** 玻璃清单请求：模块 → 目标包（extra: pkg）。 */
const val ACTION_TOOL_LIST_REQUEST = "com.coloros16.liquidglass.BALL_TOOL_LIST_REQUEST"

/** 玻璃清单结果：目标包 → 模块（extra: ok / text）。 */
const val ACTION_TOOL_LIST_RESULT = "com.coloros16.liquidglass.BALL_TOOL_LIST_RESULT"

/**
 * 点选数据链的目标包与回传白名单（球=tools/lgball imgui 球，本体不经广播）。
 */
object BallProtocol {

    /** 目标应用（AppGlassHook 作用域内、装有代理的 5 个进程）。 */
    val TARGETS = listOf(
        "com.coloros.assistantscreen" to "负一屏",
        "com.heytap.quicksearchbox" to "搜索",
        "com.coloros.gallery3d" to "相册",
        "com.coloros.calculator" to "计算器",
        "com.coloros.smartsidebar" to "侧边栏",
    )

    /** 结果广播发送方白名单（仅五目标包可回传）。 */
    val TARGET_PKG_SET: Set<String> = TARGETS.mapTo(HashSet()) { it.first }
}
