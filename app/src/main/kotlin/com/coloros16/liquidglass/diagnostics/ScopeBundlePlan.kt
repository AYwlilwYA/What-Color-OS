package com.coloros16.liquidglass.diagnostics

/**
 * 作用域应用打包的纯逻辑：作用域清单解析、包路径解析、zip 条目命名与结果汇总。
 * 零 android 依赖，取文件与压缩由调用方注入。
 */
object ScopeBundlePlan {

    /** 解析 scope.list：跳过空行与 # 注释，去重保序。 */
    fun parseScope(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()
            .toList()

    /**
     * 解析 `pm path <pkg>` 输出：每行 `package:/path/base.apk`（可能含 split）。
     * 取全部路径，base.apk 排最前便于优先取主包。
     */
    fun parsePmPath(out: String): List<String> {
        val paths = out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        return paths.sortedBy { if (it.endsWith("/base.apk")) 0 else 1 }
    }

    /** zip 内条目名：`<包名>/<文件名>`，同名 split 保留原文件名不互相覆盖。 */
    fun entryName(pkg: String, path: String): String {
        val file = path.substringAfterLast('/').ifEmpty { "base.apk" }
        return "$pkg/$file"
    }

    /** 一个包的取包结果。 */
    data class Item(val pkg: String, val entries: Int, val bytes: Long, val error: String?)

    /** 汇总：成功包数 / 总条目 / 总字节 / 失败包名（供提示与日志）。 */
    data class Summary(
        val okPackages: Int,
        val failedPackages: List<String>,
        val entries: Int,
        val bytes: Long,
    )

    fun summarize(items: List<Item>): Summary = Summary(
        okPackages = items.count { it.error == null && it.entries > 0 },
        failedPackages = items.filter { it.error != null || it.entries == 0 }.map { it.pkg },
        entries = items.sumOf { it.entries },
        bytes = items.sumOf { it.bytes },
    )
}
