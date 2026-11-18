package com.coloros16.liquidglass.ui.aod

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.coloros16.liquidglass.log.Lg

/** 预览桌面槽位定义：一格一个应用意图 + 一枚自产兜底图标。 */
data class HomeSlot(
    val label: String,
    val packages: List<String>,
    val iconKey: String,
)

/** 解析结果：系统图标优先，取不到用自产图标。 */
data class HomeIcon(
    val label: String,
    val drawable: Drawable?,
    val bitmap: Bitmap?,
) {
    val fromSystem: Boolean get() = drawable != null
}

/**
 * 桌面图标解析：按启动 Activity 取系统图标，失败回退自产素材。
 *
 * 系统链路是 LauncherActivityInfo.getIcon → PackageManager.getActivityIcon，
 * 不是 getApplicationIcon。
 */
object HomeIconSource {

    private const val TAG = "AodHomeIcon"
    private const val GRID_DIR = "icons/home/"

    val grid: List<HomeSlot> = listOf(
        HomeSlot("消息", listOf("com.tencent.mm"), "chat"),
        HomeSlot("视频", listOf("tv.danmaku.bili", "com.heytap.yoli"), "video"),
        HomeSlot("购物", listOf("com.taobao.taobao", "com.jingdong.app.mall"), "store"),
        HomeSlot("动态", listOf("com.sina.weibo", "com.ss.android.article.news"), "social"),
        HomeSlot("相册", listOf("com.coloros.gallery3d", "com.oplus.gallery"), "gallery"),
        HomeSlot("天气", listOf("com.coloros.weather2", "com.coloros.weather"), "weather"),
        HomeSlot("便签", listOf("com.coloros.note", "com.nearme.note"), "notes"),
        HomeSlot("禅定", listOf("com.coloros.relax", "com.oneplus.brickmode"), "zen"),
        HomeSlot("计算", listOf("com.coloros.calculator", "com.android.calculator2"), "calculator"),
        HomeSlot("文件", listOf("com.coloros.filemanager", "com.android.filemanager"), "files"),
        HomeSlot("录音", listOf("com.coloros.recorder", "com.android.soundrecorder"), "recorder"),
        HomeSlot("音乐", listOf("com.heytap.music", "com.oppo.music"), "music"),
        HomeSlot("邮件", listOf("com.android.email", "com.heytap.email"), "mail"),
        HomeSlot("日历", listOf("com.coloros.calendar", "com.android.calendar"), "calendar"),
        HomeSlot("钱包", listOf("com.finshell.wallet", "com.heytap.wallet"), "wallet"),
        HomeSlot("健康", listOf("com.heytap.health", "com.coloros.health"), "health"),
        HomeSlot("地图", listOf("com.autonavi.minimap", "com.baidu.BaiduMap"), "maps"),
        HomeSlot("时钟", listOf("com.coloros.alarmclock", "com.android.deskclock"), "clock"),
        HomeSlot("工具", listOf("com.coloros.compass2", "com.coloros.shortcuts"), "tools"),
        HomeSlot("设置", listOf("com.android.settings"), "settings"),
    )

    val dock: List<HomeSlot> = listOf(
        HomeSlot("电话", listOf("com.android.contacts", "com.android.dialer"), "phone"),
        HomeSlot("信息", listOf("com.android.mms"), "messages"),
        HomeSlot("浏览器", listOf("com.heytap.browser", "com.coloros.browser", "com.android.browser"), "browser"),
        HomeSlot("相机", listOf("com.oplus.camera", "com.oppo.camera", "com.oneplus.camera"), "camera"),
    )

    val all: List<HomeSlot> get() = grid + dock

    /** 逐槽解析；调用方负责放后台线程。 */
    fun resolve(context: Context): List<HomeIcon> {
        val out = all.map { slot -> resolveOne(context, slot) }
        val system = out.count { it.fromSystem }
        Lg.i(TAG, "home icons resolved: system=$system own=${out.size - system} total=${out.size}")
        return out
    }

    private fun resolveOne(context: Context, slot: HomeSlot): HomeIcon {
        val pm = context.packageManager
        for (pkg in slot.packages) {
            val system = systemIcon(pm, pkg)
            if (system != null) return HomeIcon(system.first, system.second, null)
        }
        return HomeIcon(slot.label, null, ownBitmap(context, slot.iconKey))
    }

    private fun systemIcon(pm: PackageManager, pkg: String): Pair<String, Drawable>? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val activities = runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull().orEmpty()
        for (info in activities) {
            val activity = info.activityInfo ?: continue
            val component = ComponentName(activity.packageName, activity.name)
            val drawable = runCatching { pm.getActivityIcon(component) }.getOrNull()
            if (drawable != null) {
                val label = runCatching { info.loadLabel(pm).toString() }.getOrNull() ?: activity.name
                return label to drawable
            }
        }
        val app = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        val drawable = runCatching { pm.getApplicationIcon(app) }.getOrNull() ?: return null
        val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() ?: return null
        return label to drawable
    }

    private fun ownBitmap(context: Context, key: String): Bitmap? {
        val bitmap = runCatching {
            context.assets.open(GRID_DIR + key + ".png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (bitmap == null) Lg.w(TAG, "own icon missing: $GRID_DIR$key.png")
        return bitmap
    }

    /** 把系统 Drawable 画进位图，便于与自产位图走同一条绘制路径。 */
    fun toBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(sizePx.coerceAtLeast(1), sizePx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(canvas)
        return bitmap
    }
}
