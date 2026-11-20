package com.coloros16.liquidglass.hook

import android.os.SystemClock
import com.coloros16.liquidglass.engine.SeedlingAction
import com.coloros16.liquidglass.engine.SeedlingActivity
import com.coloros16.liquidglass.engine.SeedlingCodeExtractor
import com.coloros16.liquidglass.engine.SeedlingCompact
import com.coloros16.liquidglass.engine.SeedlingElement
import com.coloros16.liquidglass.engine.SeedlingExpanded
import com.coloros16.liquidglass.engine.SeedlingKind
import com.coloros16.liquidglass.engine.SeedlingTemplate
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode

/**
 * 流体云内容源：hook 插件数据到达点（UIData 落进 info 的唯一入口），
 * 反射取字段（title/des/isMilestone/remindLevel/category）喂内容模型。
 * 双档探针：C16=z3.h0#e(z3.w,UIData)，C15=i4.t#f(i4.o,UIData)（均 dexdump 实证）；
 * SeedlingUIData 全限定名两档未混淆作稳锚；探不到静默降级不炸宿主。
 */
object SeedlingDataHook {

    private const val TAG = "LiquidGlass"

    private const val CLASS_UI_DATA = "com.oplus.seedling.sdk.seedling.SeedlingUIData"

    /** 数据入口档：管理器类 / 载体类 / 方法名 / key 字段 / pkg 字段。 */
    private enum class DataPath(
        val mgr: String,
        val info: String,
        val method: String,
        val keyField: String,
        val pkgField: String,
    ) {
        C16("z3.h0", "z3.w", "e", "A", "G"),
        C15("i4.t", "i4.o", "f", "x", "x"),
    }

    @Volatile
    private var mounted = false

    @Volatile
    private var activePath: DataPath? = null

    @Volatile
    var onActivity: ((SeedlingActivity?, String) -> Unit)? = null

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (mounted) return
        val dataCls = try {
            Class.forName(CLASS_UI_DATA, false, classLoader)
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-data: UIData anchor absent, skipped (${t.javaClass.simpleName})")
            return
        }
        for (path in DataPath.entries) {
            try {
                val mgrCls = Class.forName(path.mgr, false, classLoader)
                val infoCls = Class.forName(path.info, false, classLoader)
                val method = mgrCls.getDeclaredMethod(path.method, infoCls, dataCls)
                api.hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val info = chain.getArg(0)
                            val data = chain.getArg(1)
                            if (info != null && data != null) onData(info, data, path)
                        } catch (t: Throwable) {
                            Lg.e(TAG, "seedling-data: extract failed", t)
                        }
                        result
                    }
                mounted = true
                activePath = path
                Lg.i(TAG, "seedling-data: mounted ${path.mgr}#${path.method} (UIData arrival, path=$path)")
                return
            } catch (_: Throwable) {
                // 该档不可达，试下一档
            }
        }
        Lg.w(TAG, "seedling-data: no data path reachable (C16/C15 both absent), skipped")
    }

    private fun str(obj: Any, name: String): String? = try {
        obj.javaClass.getField(name).get(obj) as? String
    } catch (_: Throwable) {
        null
    }

    private fun bool(obj: Any, name: String): Boolean = try {
        obj.javaClass.getField(name).getBoolean(obj)
    } catch (_: Throwable) {
        false
    }

    private fun int(obj: Any, name: String): Int = try {
        obj.javaClass.getField(name).getInt(obj)
    } catch (_: Throwable) {
        0
    }

    /** 字段名漂移兜底：取第一个非空公开 String 字段当 key。 */
    private fun firstStringField(obj: Any): String? = try {
        obj.javaClass.fields.firstNotNullOfOrNull { f ->
            if (f.type == String::class.java) (f.get(obj) as? String)?.takeIf { it.isNotEmpty() } else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun onData(info: Any, data: Any, path: DataPath) {
        // key/pkg 字段名两档可能漂移：先按档取，回落扫 info 的 String 字段 + UIData.dataSourcePkgName
        val key = str(info, path.keyField) ?: firstStringField(info) ?: return
        val pkg = str(info, path.pkgField) ?: str(data, "dataSourcePkgName") ?: "unknown"
        val title = str(data, "title") ?: ""
        val des = str(data, "des") ?: ""
        val milestone = bool(data, "isMilestone")
        val remindLevel = int(data, "remindLevel")
        val category = str(data, "category") ?: ""
        val shouldShow = try {
            data.javaClass.getField("shouldShow").getBoolean(data)
        } catch (_: Throwable) {
            true
        }
        if (!shouldShow) {
            Lg.i(TAG, "seedling-data: shouldShow=false key=$key")
            return
        }
        val kind0 = kindOf(pkg, category)
        val code = SeedlingCodeExtractor.extract(title, des)
        val kind = if (code != null) SeedlingKind.CODE else kind0
        val label = if (code != null) {
            "验证码 $code"
        } else if (des.isNotEmpty() && title.isNotEmpty()) "$title · $des" else title.ifEmpty { des }
        val activity = SeedlingActivity(
            id = "seedling:$key",
            source = pkg,
            kind = kind,
            priority = if (milestone || remindLevel >= 11) 85 else kind.defaultPriority,
            startedAtElapsedMs = SystemClock.elapsedRealtime(),
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
            compact = SeedlingCompact(
                leading = SeedlingElement.Symbol(symbolOf(kind)),
                label = label,
                contentId = "seedling:$key:$title",
            ),
            expanded = SeedlingExpanded(
                template = templateOf(kind),
                title = if (code != null) "验证码" else title,
                subtitle = if (code != null) code else des,
                milestone = milestone || remindLevel >= 11,
                actions = defaultActions(kind),
            ),
        )
        Lg.i(TAG, "seedling-data: key=$key pkg=$pkg kind=$kind milestone=$milestone remind=$remindLevel title=$title code=${code != null}")
        onActivity?.invoke(activity, "data")
    }

    private fun kindOf(pkg: String, category: String): SeedlingKind {
        val p = pkg.lowercase()
        val c = category.lowercase()
        return when {
            "alarmclock" in p || "deskclock" in p || "timer" in c -> SeedlingKind.TIMER
            "soundrecorder" in p || "recorder" in p || "record" in c -> SeedlingKind.RECORDING
            "incallui" in p || "telecom" in p || "call" in c -> SeedlingKind.CALL
            "music" in p || "media" in c -> SeedlingKind.MEDIA
            "mms" in p || "message" in c || "sms" in p -> SeedlingKind.MESSAGE
            "meituan" in p || "eleme" in p || "didi" in p || "delivery" in c || "taxi" in c -> SeedlingKind.LIVE_UPDATE
            else -> SeedlingKind.UNKNOWN
        }
    }

    private fun symbolOf(kind: SeedlingKind): String = when (kind) {
        SeedlingKind.TIMER -> "TIMER"
        SeedlingKind.CALL -> "PHONE"
        SeedlingKind.MEDIA -> "MUSIC"
        SeedlingKind.MESSAGE -> "MESSAGE"
        SeedlingKind.LIVE_UPDATE -> "NAVIGATION"
        SeedlingKind.RECORDING -> "MIC"
        SeedlingKind.STATUS -> "MIC"
        else -> "INFO"
    }

    private fun templateOf(kind: SeedlingKind): SeedlingTemplate = when (kind) {
        SeedlingKind.TIMER -> SeedlingTemplate.TIMER
        SeedlingKind.CALL -> SeedlingTemplate.CALL
        SeedlingKind.MEDIA -> SeedlingTemplate.MEDIA
        SeedlingKind.MESSAGE -> SeedlingTemplate.MESSAGE
        SeedlingKind.LIVE_UPDATE -> SeedlingTemplate.PROGRESS
        else -> SeedlingTemplate.GENERIC
    }

    private fun defaultActions(kind: SeedlingKind): List<SeedlingAction> = when (kind) {
        SeedlingKind.TIMER -> listOf(
            SeedlingAction("pause", "暂停", "PAUSE"),
            SeedlingAction("stop", "停止", "CLOSE"),
        )
        SeedlingKind.CALL -> listOf(
            SeedlingAction("answer", "接听", "PHONE"),
            SeedlingAction("hangup", "挂断", "PHONE_END", primary = false),
        )
        else -> emptyList()
    }
}