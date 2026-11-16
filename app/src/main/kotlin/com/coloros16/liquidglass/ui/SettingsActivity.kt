package com.coloros16.liquidglass.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.coloros16.liquidglass.App
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.debug.RootBallLauncher
import com.coloros16.liquidglass.glass.GlassRegistry
import com.coloros16.liquidglass.hook.statusbar.StatusBarSettings
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.sf.SfGlassChannel
import com.coloros16.liquidglass.log.LogCapture
import com.coloros16.liquidglass.ui.aod.AodConfigsController
import com.coloros16.liquidglass.ui.aod.AodDisplayController
import com.coloros16.liquidglass.ui.aod.AodMenuController
import com.coloros16.liquidglass.update.UpdateChecker
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * 模块配置界面（Material You 动态取色，二级菜单）。
 *
 * 二级菜单结构：
 * - 一级 = 大类（总开关 / 液态玻璃材质 / 背景与抓屏 / 文字与可读性 / 遮罩 / 流体云 /
 *   锁屏时钟 / 追踪器 / 日志与诊断 / 场景过滤），列表页展示。
 * - 二级 = 各类内具体配置（开关 / 滑杆 / 下拉），点进大类后切换子视图。
 *   全部配置清单与接线状态见 doc/spec/34-配置开关清单.md。
 *
 * 配置读写模型（2026-08-11 修正）：
 * 本 Activity 运行在模块 APK 进程，必须把配置写入【框架侧存储】——
 * 经 [App.serviceRef]（XposedService）→ [Prefs.remote] → edit().apply()，
 * 底层写 LibXposed 框架存储（LSPosed 等框架的内部数据库）；
 * hook 端（SystemUI / Launcher / 锁屏时钟进程）经 XposedInterface.getRemotePreferences 读同一份。
 * 注意：框架 binder 注入晚于 onCreate（真机实测异步），写入/回显需等待 [App.addServiceStateListener] 就绪，
 * 不可直接降级本地 XML（hook 端读不到 XML）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var xmlPrefs: SharedPreferences
    private lateinit var contentFrame: FrameLayout

    /** 当前在架页（换页动画期间内容区会同时挂新旧两页，一律以跟踪引用为准）。 */
    private var currentPageView: View? = null
    /** 正在退场的旧页，下一次换页时兜底摘除。 */
    private var outgoingView: View? = null
    /** 已渲染屏 id，用于识别同屏刷新与记滚动位。 */
    private var renderedId: String? = null
    /** 各屏滚动位置记忆：离开时记、回来时还原。 */
    private val scrollMem = HashMap<String, Int>()

    /** 当前所在二级大类（null = 正在一级列表页）。 */
    private var currentCategory: String? = null

    /** [spec/64] 当前所在三级「桌面动画参数」组（null = 不在三级页）。 */
    private var currentAnimGroup: String? = null

    /** [2026-09-17 升级提示] 本次启动是否已触发过检查（防重复起线程）。 */
    private var updateCheckStarted = false

    /** [spec/64] 一键预设档位：刚度 / 阻尼 / 透明度时长（ms）；null = 取该组默认值。 */
    private data class AnimPreset(val labelRes: Int, val stiffness: Int?, val damping: Int?, val fade: Int?)

    /**
     * [spec/64] 三档预设，阻尼一律取 100 = 临界阻尼（ζ=1.0），**数学上无过冲**，仅速度不同。
     * 折算关系（与 SwiftUI 同模型）：刚度 = (2π/T)²，T = 感知时长（秒）；阻尼值 = 阻尼比 ζ × 100。
     *
     * 为何不用 ζ<1：欠阻尼必然过冲（`过冲量 = e^(-πζ/√(1-ζ²))`），
     * 表现为「画面已铺满全屏仍在继续胀大再回缩」。小元件位移下 0.6% 过冲不可见，
     * 但应用打开是「图标 → 全屏」的大位移放大，同样的 ζ 会被放大成肉眼可见的运动
     * （ζ=0.70 时过冲 4.6%，1440px 下约 66px）—— 真机实测 ζ<1 的档位均被否决。
     */
    private val animPresets = listOf(
        // 按组取 ZuyQA 原始默认值：打开/打断 580/140/340，关闭 120/87/510
        AnimPreset(R.string.settings_anim_preset_default, null, null, null),
        AnimPreset(R.string.settings_anim_preset_soft, 158, 100, 500),
        AnimPreset(R.string.settings_anim_preset_mid, 220, 100, 420),
        AnimPreset(R.string.settings_anim_preset_crisp, 320, 100, 350),
    )

    /** 二级大类定义。 */
    private data class Category(val id: String, val titleRes: Int, val descRes: Int, val layoutRes: Int)

    private val categories = listOf(
        Category("master", R.string.cat_master, R.string.cat_master_desc, R.layout.view_cat_master),
        Category("material", R.string.cat_material, R.string.cat_material_desc, R.layout.view_cat_material),
        Category("corner", R.string.cat_corner, R.string.cat_corner_desc, R.layout.view_cat_corner_radius),
        Category("background", R.string.cat_background, R.string.cat_background_desc, R.layout.view_cat_background),
        Category("text", R.string.cat_text, R.string.cat_text_desc, R.layout.view_cat_text),
        Category("mask", R.string.cat_mask, R.string.cat_mask_desc, R.layout.view_cat_mask),
        Category("seedling", R.string.cat_seedling, R.string.cat_seedling_desc, R.layout.view_cat_seedling),
        // [spec/81 2026-09-17] 锁屏时钟分类页已删除（hook 未命中数字 View，功能整体停用，无存活控件）
        // [spec/64] 桌面动画参数（三组开关 + 三级参数页）
        Category("anim", R.string.cat_anim, R.string.cat_anim_desc, R.layout.view_cat_anim),
        // [spec/65] iOS 动态倾斜/透视（实验性，默认关）
        Category("tilt", R.string.cat_tilt, R.string.cat_tilt_desc, R.layout.view_cat_tilt),
        Category("desktop", R.string.cat_desktop, R.string.cat_desktop_desc, R.layout.view_cat_desktop),
        // 其他组件：音量条 + 第三方 App（相册/计算器/智能侧边栏）的组件级玻璃开关
        Category("components", R.string.cat_components, R.string.cat_components_desc, R.layout.view_cat_components),
        Category("statusbar", R.string.cat_statusbar, R.string.cat_statusbar_desc, R.layout.view_cat_statusbar),
        // [2026-09-17] 追踪器分类页已删除（追踪器整体移除，无存活控件）
        Category("log", R.string.cat_log, R.string.cat_log_desc, R.layout.view_cat_log),
        Category("config", R.string.cat_config, R.string.cat_config_desc, R.layout.view_cat_config),
        Category("about", R.string.cat_about, R.string.cat_about_desc, R.layout.view_cat_about),
    )

    /** 三栏壳：底栏所属栏。 */
    private enum class NavColumn { HOME, FUNC, SETTINGS }

    /** 路由表条目：屏 id → 所属栏 / 标题 / 布局 / 分类绑定 id / 是否待接入。 */
    private class ScreenDef(
        val id: String,
        val column: NavColumn,
        val titleRes: Int,
        val layoutRes: Int = 0,
        val categoryId: String? = null,
        val pending: Boolean = false,
    )

    /** 19 屏路由表（三个一级屏 + 16 个待接入屏）+ 14 个分类屏 + 桌面动画参数三级屏。 */
    private val screens: Map<String, ScreenDef> = buildMap {
        put("home", ScreenDef("home", NavColumn.HOME, 0))
        put("func", ScreenDef("func", NavColumn.FUNC, R.string.settings_tab_func))
        put(
            "settings",
            ScreenDef("settings", NavColumn.SETTINGS, R.string.settings_tab_settings, R.layout.view_shell_settings),
        )
        put(
            "glass",
            ScreenDef("glass", NavColumn.FUNC, R.string.settings_func_glass, R.layout.view_shell_glass),
        )
        put(
            "glass-sampling",
            ScreenDef(
                "glass-sampling", NavColumn.FUNC,
                R.string.settings_glass_group_sampling, R.layout.view_glass_sampling,
            ),
        )
        put(
            "sysobj",
            ScreenDef("sysobj", NavColumn.FUNC, R.string.settings_obj_system, R.layout.view_obj_system),
        )
        put(
            "deskobj",
            ScreenDef("deskobj", NavColumn.FUNC, R.string.settings_obj_desktop, R.layout.view_obj_desktop),
        )
        put(
            "deskedit",
            ScreenDef("deskedit", NavColumn.FUNC, R.string.settings_obj_desktop_edit, R.layout.view_obj_desktop_edit),
        )
        put(
            "corner",
            ScreenDef("corner", NavColumn.FUNC, R.string.settings_func_corner, R.layout.view_shell_corner),
        )
        put(
            "cornerconfig",
            ScreenDef(
                "cornerconfig", NavColumn.FUNC,
                R.string.settings_corner_config_default, R.layout.view_shell_corner_config,
            ),
        )
        put(
            "classassign",
            ScreenDef(
                "classassign", NavColumn.FUNC,
                R.string.settings_class_assign_glass, R.layout.view_class_assign,
            ),
        )
        put(
            "classassign-corner",
            ScreenDef(
                "classassign-corner", NavColumn.FUNC,
                R.string.settings_corner_class_assign, R.layout.view_class_assign,
            ),
        )
        put(
            "panel",
            ScreenDef("panel", NavColumn.FUNC, R.string.settings_func_panel, R.layout.view_shell_panel),
        )
        put(
            "launcher",
            ScreenDef("launcher", NavColumn.FUNC, R.string.settings_func_launcher, R.layout.view_shell_launcher),
        )
        put(
            "seedling",
            ScreenDef("seedling", NavColumn.FUNC, R.string.settings_func_seedling, R.layout.view_shell_seedling),
        )
        put(
            "aod",
            ScreenDef("aod", NavColumn.FUNC, R.string.settings_func_aod, R.layout.view_shell_aod),
        )
        put(
            "aod-display",
            ScreenDef(
                "aod-display",
                NavColumn.FUNC,
                R.string.settings_aod_display_title,
                R.layout.view_shell_aod_display,
            ),
        )
        put(
            "aod-configs",
            ScreenDef(
                "aod-configs",
                NavColumn.FUNC,
                R.string.settings_aod_configs_title,
                R.layout.view_shell_aod_configs,
            ),
        )
        put(
            "config",
            ScreenDef("config", NavColumn.SETTINGS, R.string.settings_config_io_row, R.layout.view_cat_config),
        )
        put(
            "logpage",
            ScreenDef("logpage", NavColumn.SETTINGS, R.string.settings_logpage_row, R.layout.view_shell_logpage),
        )
        put(
            "diagpage",
            ScreenDef("diagpage", NavColumn.SETTINGS, R.string.settings_diagpage_row, R.layout.view_shell_diagpage),
        )
        put(
            "about",
            ScreenDef("about", NavColumn.SETTINGS, R.string.settings_about_row, R.layout.view_cat_about),
        )
        put(
            "sponsor",
            ScreenDef("sponsor", NavColumn.HOME, R.string.settings_home_sponsor, R.layout.view_cat_sponsor),
        )
        categories.forEach { cat ->
            put("cat/${cat.id}", ScreenDef("cat/${cat.id}", NavColumn.FUNC, cat.titleRes, cat.layoutRes, cat.id))
        }
        put("cat/anim-params", ScreenDef("cat/anim-params", NavColumn.FUNC, 0, R.layout.view_anim_params))
    }

    /** 每栏独立返回栈（栈底恒为该栏根屏）。 */
    private val stacks: Map<NavColumn, MutableList<String>> = mapOf(
        NavColumn.HOME to mutableListOf("home"),
        NavColumn.FUNC to mutableListOf("func"),
        NavColumn.SETTINGS to mutableListOf("settings"),
    )

    /** 当前所在栏。 */
    private var currentTab = NavColumn.HOME

    /** 已赞助名单内存缓存（null=未拉取过；失败不缓存，下次进主页重试）。 */
    private var sponsorCache: List<Pair<String, String>>? = null

    private fun stack(): MutableList<String> = stacks.getValue(currentTab)

    private fun topScreen(): String = stack().last()

    private fun rootOf(tab: NavColumn): String = when (tab) {
        NavColumn.HOME -> "home"
        NavColumn.FUNC -> "func"
        NavColumn.SETTINGS -> "settings"
    }

    private var backHitOverlay: View? = null
    private var restartScopeBtn: ImageButton? = null
    private var bottomNav: LinearLayout? = null
    private var toolbarRow: View? = null
    private var navTabViews: Map<NavColumn, TextView> = emptyMap()

    /** 节流日志表（守卫/重复路径防刷屏）。 */
    private val navLogAt = ConcurrentHashMap<String, Long>()

    private fun logNavThrottled(key: String, msg: String, intervalMs: Long = 2000L) {
        val now = SystemClock.uptimeMillis()
        val last = navLogAt[key]
        if (last != null && now - last < intervalMs) return
        navLogAt[key] = now
        Lg.i(TAG_NAV, msg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedThemeMode()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // [spec/88] 导航图标 / 扩大热区（backHitOverlay）/ 手势与系统返回，三处共用同一套返回逻辑
        toolbar.setNavigationOnClickListener { navigateUp() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateUp()
            }
        })

        xmlPrefs = Prefs.read(this)
        contentFrame = findViewById(R.id.settings_content)
        toolbarRow = findViewById(R.id.toolbarRow)
        backHitOverlay = findViewById(R.id.backHitOverlay)
        backHitOverlay?.setOnClickListener { navigateUp() }
        restartScopeBtn = findViewById(R.id.restartScopeBtn)
        restartScopeBtn?.setOnClickListener { restartScope() }
        setupBottomNav()
        trackColdStart()
        maybeShowFirstRunDeclaration()

        // [spec/88] 状态栏/挖孔避让 + 返回热区几何诊断（根因与实测数据见 doc/spec/88）
        setupWindowInsets(toolbar)

        // TEMP-VERIFY: 配置链路真机验证用（验证后移除）——带 auto_enable=true 启动时自动写总开关
        if (intent?.getBooleanExtra("auto_enable", false) == true) {
            writeMasterEnabled(true)
        }

        // 框架就绪后刷新回显（framework 侧为权威值；仅重新绑定当前大类控件）；
        // [2026-10-10] 服务死亡（null 回调）→ 提示用户自愈方式（binder 只在进程启动注入一次，无重连）。
        App.addServiceStateListener(object : App.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                if (service != null) bindCurrentPage()
                else showServiceLostNotice()
            }
        }, false)

        Lg.i(TAG_NAV, "initial route: tab=$currentTab stack=${stack()}")
        renderTop()

        // [2026-09-17 升级提示] 应用启动即后台查一次最新版本，有新版本弹提示（不阻塞界面）
        checkUpdateOnLaunch()
    }

    // ------------------------------------------------------------ [2026-09-17] 启动升级提示 ------------------------------------------------------------

    /**
     * 启动时检查 GitHub Releases 最新版本，比当前 [appVersionName] 新则弹提示。
     *
     * 网络在子线程（[UpdateChecker.fetchLatest] 阻塞），结果回主线程弹窗；
     * 检查失败/无新版本/该版本已被「忽略此版本」→ 全程静默，不打扰用户。
     */
    private fun checkUpdateOnLaunch() {
        if (updateCheckStarted) return
        updateCheckStarted = true
        Thread {
            val latest = UpdateChecker.fetchLatest() ?: return@Thread
            val current = appVersionName() ?: return@Thread
            if (!UpdateChecker.isNewer(latest.version, current)) return@Thread
            if (xmlPrefs.getString(XML_KEY_IGNORED_UPDATE, null) == latest.version) return@Thread
            runOnUiThread {
                if (!isFinishing && !isDestroyed) showUpdateDialog(current, latest)
            }
        }.start()
    }

    /** 当前安装版本名（如 "0.1.1"）；读取失败返回 null（则本次不提示）。 */
    private fun appVersionName(): String? = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (t: Throwable) {
        Lg.w(TAG, "read versionName failed", t)
        null
    }

    /** 升级提示弹窗：去下载（跳 release 页/APK 直链）/ 忽略此版本（记本地，不再提示该版本）/ 以后再说。 */
    private fun showUpdateDialog(current: String, latest: UpdateChecker.Release) {
        val base = getString(R.string.update_dialog_message, current, latest.version)
        val notes = latest.notes
            ?.let { n -> if (n.length > 600) n.take(600) + "…" else n }
            ?.let { n -> getString(R.string.update_dialog_notes, n) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(if (notes != null) "$base\n\n$notes" else base)
            .setPositiveButton(R.string.update_dialog_download) { _, _ ->
                // 优先 APK 直链（一键下载）；无资产则退到 release 页面
                val target = latest.apkUrl ?: latest.pageUrl
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                } catch (t: Throwable) {
                    Lg.w(TAG, "open update url failed: $target", t)
                    Toast.makeText(this, R.string.update_dialog_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.update_dialog_ignore) { _, _ ->
                xmlPrefs.edit().putString(XML_KEY_IGNORED_UPDATE, latest.version).apply()
            }
            .setNegativeButton(R.string.update_dialog_later, null)
            .show()
            .also { DialogGlass.arm(it) }
    }

    // ------------------------------------------------------------ [spec/88] 状态栏/挖孔避让 + 返回热区 ------------------------------------------------------------

    /** [spec/88] toolbar 顶上的 inset 占位条（activity_settings.xml:toolbarTopSpacer）。 */
    private lateinit var toolbarTopSpacer: View

    /** 最近一次收到的系统条带 inset（屏幕坐标系，px）；-1 = 尚未收到任何 inset。 */
    private var insetTop = -1
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0

    /** 已应用到 [toolbarTopSpacer] 的高度 px —— 用于反推 toolbar 的「无补偿基准 top」。 */
    private var appliedSpacerHeight = 0

    /** 单轮 settle 内 spacer 连改次数上限（防布局振荡把主线程拖死）。 */
    private var spacerAdjustCount = 0

    /** 上一条已打印的诊断日志（相同则不重打，防刷屏）。 */
    private var lastInsetLog = ""

    /**
     * [spec/88] 注册窗口 inset 监听：把 toolbar 顶边顶到状态栏/挖孔之下，内容区避开导航栏。
     *
     * 关键点是**按屏幕坐标实测几何自校正**，而不是无条件按 inset 加 padding：
     *
     * ```
     * baseTop = toolbar 屏幕 top − 已加 spacer 高   // 与 spacer 无关的不变量
     * spacer  = max(0, 状态栏高 − baseTop)
     * ```
     *
     * 于是三种形态自动收敛到正确值，且因 baseTop 恒定，一次调整即收敛、不会振荡：
     * - edge-to-edge（Android 15+ 强制，toolbar 屏幕 top=0）→ 补满状态栏高；
     * - 旧式 fitsSystemWindows（系统已把 DecorView 顶下去，baseTop=状态栏高）→ 补 0，不重复让位；
     * - 悬浮/小窗（窗口顶边不在屏幕顶，toolbar 屏幕 top 远大于状态栏高）→ 补 0，不凭空压低 toolbar。
     */
    private fun setupWindowInsets(toolbar: View) {
        toolbarTopSpacer = findViewById(R.id.toolbarTopSpacer)
        val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val sys = insets.getInsets(types)
            insetTop = sys.top
            insetBottom = sys.bottom
            insetLeft = sys.left
            insetRight = sys.right
            applyToolbarInset(toolbar)
            applyContentInset()
            insets
        }
        // 首帧 insets 分发与 toolbar 落位先后不定，布局稳定后再量一次兜底
        toolbar.post {
            applyToolbarInset(toolbar)
            applyContentInset()
        }
    }

    /**
     * [spec/88] 按屏幕坐标算出 toolbar 需要的顶部占位高度并应用。
     *
     * 由 inset 监听与 toolbar 的 OnLayoutChangeListener 双路触发：改了 spacer 会在下一帧布局后再量一次，
     * [baseTop] 不变 → 算出的值必然相同 → 第二轮即停。
     */
    private fun applyToolbarInset(toolbar: View) {
        if (!toolbar.isAttachedToWindow || !::toolbarTopSpacer.isInitialized) return
        val loc = IntArray(2)
        toolbar.getLocationOnScreen(loc)
        // 无补偿基准 top = 当前屏幕 top − 已加的 spacer（spacer 只往下推 toolbar，故可精确反推）
        val baseTop = loc[1] - appliedSpacerHeight
        val need = if (insetTop < 0) 0 else (insetTop - baseTop).coerceAtLeast(0)
        if (need != appliedSpacerHeight) {
            spacerAdjustCount++
            if (spacerAdjustCount > MAX_SPACER_ADJUST) {
                Lg.e(TAG, "toolbar inset settle failed, keep spacer=$appliedSpacerHeight (baseTop=$baseTop insetTop=$insetTop)")
                return
            }
            appliedSpacerHeight = need
            toolbarTopSpacer.layoutParams = toolbarTopSpacer.layoutParams.apply { height = need }
            Lg.i(TAG, "toolbar inset adjust #$spacerAdjustCount: baseTop=$baseTop insetTop=$insetTop -> spacer=$need")
        }
        logInset(baseTop, need)
    }

    /** [spec/88] 内容区避开导航栏/手势条（左右一并处理）。用「到窗口边的既有空隙」做差，旧式已让位的形态补 0。 */
    private fun applyContentInset() {
        if (!::contentFrame.isInitialized) return
        val decor = window?.decorView ?: return
        val content = contentFrame
        if (!content.isAttachedToWindow) return
        val d = IntArray(2)
        decor.getLocationOnScreen(d)
        val c = IntArray(2)
        content.getLocationOnScreen(c)
        val gapLeft = c[0] - d[0]
        val gapRight = (d[0] + decor.width) - (c[0] + content.width)
        val gapBottom = (d[1] + decor.height) - (c[1] + content.height)
        val padL = (insetLeft - gapLeft).coerceAtLeast(0)
        val padR = (insetRight - gapRight).coerceAtLeast(0)
        if (padL != content.paddingLeft || padR != content.paddingRight) {
            content.setPadding(padL, 0, padR, 0)
            Lg.i(
                TAG,
                "content inset: inset=${insetLeft},${insetTop},${insetRight},${insetBottom} " +
                    "gap(L/R/B)=$gapLeft/$gapRight/$gapBottom -> pad(L/R/B)=$padL/$padR/0"
            )
        }
        // 底部 inset 归底栏：底栏贴 decor 底边，同一套 gap 差值自校正
        val nav = bottomNav
        if (nav != null && nav.isAttachedToWindow) {
            val n = IntArray(2)
            nav.getLocationOnScreen(n)
            val gapNavBottom = (d[1] + decor.height) - (n[1] + nav.height)
            val padNavB = (insetBottom - gapNavBottom).coerceAtLeast(0)
            if (padNavB != nav.paddingBottom) {
                nav.setPadding(nav.paddingLeft, 0, nav.paddingRight, padNavB)
                val baseHeight = (60 * resources.displayMetrics.density).toInt()
                nav.layoutParams = nav.layoutParams.apply { height = baseHeight + padNavB }
                Lg.i(TAG, "bottom nav inset: gapBottom=$gapNavBottom insetBottom=$insetBottom -> padB=$padNavB")
            }
        }
    }

    /** [spec/88] inset 诊断日志（只在数值变化时打印；真机根因判定依据，见 doc/spec/88）。 */
    private fun logInset(baseTop: Int, need: Int) {
        val msg = "toolbar inset: insetTop=$insetTop baseTop=$baseTop spacer=$need applied=$appliedSpacerHeight"
        if (msg != lastInsetLog) {
            lastInsetLog = msg
            Lg.i(TAG, msg)
        }
    }

    // ------------------------------------------------------------ 三栏壳路由 ------------------------------------------------------------

    /** 功能栏根屏内容：全部大类列表。 */
    private fun buildCategoryListView() {
        val listView = layoutInflater.inflate(R.layout.view_categories, contentFrame, false)
        contentFrame.addView(listView)
        val list = listView.findViewById<LinearLayout>(R.id.categoryList)
        list.removeAllViews()
        for (cat in categories) {
            val row = layoutInflater.inflate(R.layout.item_category, list, false)
            row.findViewById<TextView>(R.id.catTitle).text = getString(cat.titleRes)
            row.findViewById<TextView>(R.id.catDesc).text = getString(cat.descRes)
            row.setOnClickListener { openCategory(cat.id) }
            list.addView(row)
        }
    }

    // ------------------------------------------------------------ 主页 / 功能页 ------------------------------------------------------------

    /** 组头 ⓘ 说明弹窗：讲清该概念是干什么的。 */
    private fun showConceptInfo(titleRes: Int, bodyRes: Int) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(bodyRes)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        DialogGlass.arm(dialog)
    }

    /** 主页：效果预览、总开关、Root 状态、快捷跳转、已赞助人员、系统信息。 */
    private fun bindHome(view: View) {
        view.findViewById<LiquidGlassPreviewView>(R.id.homePreview)?.let { applyPreviewParams(it) }

        val masterSwitch = view.findViewById<Switch>(R.id.homeMasterSwitch)
        val stateChip = view.findViewById<TextView>(R.id.homeMasterState)
        val listener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writeMasterEnabled(checked)
            applyMasterChip(stateChip, checked)
            Lg.i(TAG_NAV, "home master switch -> $checked")
        }
        masterSwitch.setOnCheckedChangeListener(null)
        val enabled = masterEnabled()
        masterSwitch.isChecked = enabled
        masterSwitch.setOnCheckedChangeListener(listener)
        applyMasterChip(stateChip, enabled)

        // root 探测会起 su 子进程，放后台；结果回填前确认主页视图还挂在窗口上
        val rootChip = view.findViewById<TextView>(R.id.homeRootState)
        rootChip.text = getString(R.string.settings_home_root_checking)
        Thread {
            val granted = LogCapture.probeSu()
            runOnUiThread {
                if (!view.isAttachedToWindow) {
                    logNavThrottled("root-probe-stale", "root probe result dropped: home view detached")
                    return@runOnUiThread
                }
                rootChip.text = getString(
                    if (granted) R.string.settings_home_root_granted else R.string.settings_home_root_missing
                )
                Lg.i(TAG_NAV, "root probe: granted=$granted")
            }
        }.start()

        setupRow(
            view.findViewById(R.id.homeSponsorRow),
            getString(R.string.settings_home_sponsor),
            getString(R.string.settings_home_sponsor_desc),
        ).setOnClickListener { pushScreen("sponsor") }
        setupRow(
            view.findViewById(R.id.homeAuthorRow),
            getString(R.string.settings_home_author),
            AUTHOR_NAME,
        ).setOnClickListener { openExternal(AUTHOR_URL) }
        setupRow(
            view.findViewById(R.id.homeGroupRow),
            getString(R.string.settings_home_group),
            getString(R.string.settings_home_group_desc),
        ).setOnClickListener { showGroupPicker() }
        setRowIcon(view.findViewById(R.id.homeSponsorRow), R.drawable.ic_row_heart)
        setRowIcon(view.findViewById(R.id.homeAuthorRow), R.drawable.ic_row_person)
        setRowIcon(view.findViewById(R.id.homeGroupRow), R.drawable.ic_row_people)
        loadSponsors(view)
        view.findViewById<View>(R.id.infoHomeJump)?.setOnClickListener {
            showConceptInfo(R.string.settings_home_quick_jump, R.string.info_quick_jump)
        }

        setupRow(
            view.findViewById(R.id.homeVersionRow),
            getString(R.string.settings_home_version),
            null,
            appVersionName() ?: "—",
        )
        setupRow(
            view.findViewById(R.id.homeVersionPoolRow),
            getString(R.string.settings_home_version_pool),
            getString(R.string.settings_home_version_pool_desc),
            getString(R.string.settings_home_version_pool_value),
        )
    }

    /** 群组选择：QQ 复制群号并拉起，Telegram 直接跳频道。 */
    private fun showGroupPicker() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_home_group)
            .setItems(
                arrayOf(getString(R.string.settings_home_group_qq), getString(R.string.settings_home_group_tg)),
            ) { _, which ->
                if (which == 0) copyGroupAndOpenQq() else openExternal(GROUP_URL)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { DialogGlass.arm(it) }
    }

    /** 复制 QQ 群号到剪贴板并拉起 QQ。 */
    private fun copyGroupAndOpenQq() {
        try {
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("qq group", QQ_GROUP_NUMBER))
            Toast.makeText(this, R.string.settings_home_group_qq_copied, Toast.LENGTH_SHORT).show()
            val intent = packageManager.getLaunchIntentForPackage(QQ_PACKAGE)
            if (intent != null) {
                startActivity(intent)
                Lg.i(TAG_NAV, "qq group copied, qq launched")
            } else {
                Lg.w(TAG_NAV, "qq not installed, group number copied only")
            }
        } catch (t: Throwable) {
            Lg.w(TAG_NAV, "launch qq failed", t)
        }
    }

    /** 已赞助名单：GitHub raw 拉取（首字母|名字），拉到才显示，空/失败隐藏。 */
    private fun loadSponsors(view: View) {
        val head = view.findViewById<View>(R.id.homeSponsorsHead)
        val card = view.findViewById<View>(R.id.homeSponsorsCard)
        val list = view.findViewById<LinearLayout>(R.id.homeSponsorsList)
        fun render(rows: List<Pair<String, String>>) {
            if (!view.isAttachedToWindow) return
            if (rows.isEmpty()) {
                head.visibility = View.GONE
                card.visibility = View.GONE
                return
            }
            list.removeAllViews()
            val colors = intArrayOf(
                R.drawable.bg_avatar_blue, R.drawable.bg_avatar_gold, R.drawable.bg_avatar_red,
            )
            rows.forEachIndexed { i, (initial, name) ->
                val col = layoutInflater.inflate(R.layout.item_sponsor, list, false)
                val initialView = col.findViewById<TextView>(R.id.sponsorInitial)
                initialView.text = initial
                initialView.setBackgroundResource(colors[i % colors.size])
                col.findViewById<TextView>(R.id.sponsorName).text = name
                list.addView(col)
            }
            head.visibility = View.VISIBLE
            card.visibility = View.VISIBLE
            Lg.i(TAG_NAV, "sponsors rendered n=${rows.size}")
        }
        sponsorCache?.let { render(it); return }
        Thread {
            val rows = fetchSponsors()
            if (rows != null) sponsorCache = rows
            runOnUiThread { render(rows ?: emptyList()) }
        }.start()
    }

    /** 拉 GitHub raw 名单；null=网络失败（不缓存，下次进主页重试）。 */
    private fun fetchSponsors(): List<Pair<String, String>>? = try {
        val conn = URL(SPONSORS_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.inputStream.bufferedReader().use { r ->
            r.readLines().mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) {
                    null
                } else {
                    val p = t.split('|', limit = 2)
                    if (p.size == 2 && p[0].trim().isNotEmpty()) p[0].trim() to p[1].trim() else null
                }
            }
        }.also { Lg.i(TAG_NAV, "sponsors fetched n=${it.size}") }
    } catch (t: Throwable) {
        Lg.w(TAG_NAV, "sponsors fetch failed", t)
        null
    }

    /** 功能页：搜索框 + 六大功能分类，按中文名/说明/关键词过滤。 */
    private fun bindFunc(view: View) {
        val entries = listOf(
            FuncEntry(
                R.id.funcRowGlass, "glass",
                R.string.settings_func_glass, R.string.settings_func_glass_desc,
                "玻璃 液态 折射 色散 高光 玻璃化 绘制 预设 配置 材质 blur glass",
            ),
            FuncEntry(
                R.id.funcRowCorner, "corner",
                R.string.settings_func_corner, R.string.settings_func_corner_desc,
                "圆角 圆角化 半径 corner radius",
            ),
            FuncEntry(
                R.id.funcRowPanel, "panel",
                R.string.settings_func_panel, R.string.settings_func_panel_desc,
                "面板 通知 控制中心 音量 飘散 动效 灰底 panel notification volume",
            ),
            FuncEntry(
                R.id.funcRowLauncher, "launcher",
                R.string.settings_func_launcher, R.string.settings_func_launcher_desc,
                "桌面 launcher 动画 打开 关闭 打断 倾斜 透视 anim tilt",
            ),
            FuncEntry(
                R.id.funcRowSeedling, "seedling",
                R.string.settings_func_seedling, R.string.settings_func_seedling_desc,
                "流体云 灵动岛 小胶囊 卡片 seedling capsule", devBadge = true,
            ),
            FuncEntry(
                R.id.funcRowAod, "aod",
                R.string.settings_func_aod, R.string.settings_func_aod_desc,
                "息屏 锁屏 alive aod always on 亮屏", devBadge = true,
            ),
            FuncEntry(
                R.id.funcRowStatusbar, "cat/statusbar",
                R.string.settings_func_statusbar, R.string.settings_func_statusbar_desc,
                "状态栏 时钟 信号 电量 wifi 5G 图标 排序 双卡 statusbar clock signal",
            ),
        )
        val devText = getString(R.string.settings_in_development)
        entries.forEachIndexed { index, entry ->
            val row = setupRow(
                view.findViewById(entry.rowId),
                getString(entry.titleRes),
                getString(entry.descRes),
                if (entry.devBadge) devText else String.format("%02d", index + 1),
            )
            val valueView = row.findViewById<TextView>(R.id.rowValue)
            valueView.setTextColor(resources.getColor(R.color.lg_brass, theme))
            if (entry.devBadge) {
                valueView.setBackgroundResource(R.drawable.bg_chip_warn)
            } else {
                valueView.setBackgroundResource(0)
                valueView.letterSpacing = 0.08f
            }
            setRowIcon(
                row,
                when (entry.target) {
                    "glass" -> R.drawable.ic_row_glass
                    "corner" -> R.drawable.ic_row_corner
                    "panel" -> R.drawable.ic_row_panel
                    "launcher" -> R.drawable.ic_row_launcher
                    "seedling" -> R.drawable.ic_row_cloud
                    "cat/statusbar" -> R.drawable.ic_row_statusbar
                    else -> R.drawable.ic_row_moon
                },
            )
            row.setOnClickListener { pushScreen(entry.target) }
        }

        val search = view.findViewById<EditText>(R.id.funcSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase().orEmpty()
                var visible = 0
                entries.forEach { entry ->
                    val haystack =
                        getString(entry.titleRes) + getString(entry.descRes) + entry.keywords
                    val match = query.isEmpty() || haystack.lowercase().contains(query)
                    view.findViewById<View>(entry.rowId).visibility = if (match) View.VISIBLE else View.GONE
                    if (match) visible++
                }
                logNavThrottled("func-search", "func search q=\"$query\" visible=$visible")
            }
        })
    }

    /** 功能页条目定义（行 id / 目标屏 / 文案 / 搜索关键词）。 */
    private data class FuncEntry(
        val rowId: Int,
        val target: String,
        val titleRes: Int,
        val descRes: Int,
        val keywords: String,
        val devBadge: Boolean = false,
    )

    /** 统一填充一行（标题 + 说明 + 右侧值），返回该行便于接点击。 */
    private fun setupRow(row: View, title: String, desc: String?, value: String? = null): View {
        row.findViewById<TextView>(R.id.rowTitle).text = title
        val descView = row.findViewById<TextView>(R.id.rowDesc)
        descView.text = desc
        descView.visibility = if (desc.isNullOrEmpty()) View.GONE else View.VISIBLE
        val valueView = row.findViewById<TextView>(R.id.rowValue)
        if (value.isNullOrEmpty()) {
            valueView.visibility = View.GONE
        } else {
            valueView.text = value
            valueView.visibility = View.VISIBLE
        }
        return row
    }

    /** 行前图标（缺省为通用六边形）。 */
    private fun setRowIcon(row: View, iconRes: Int) {
        row.findViewById<ImageView>(R.id.rowIcon)?.setImageResource(iconRes)
    }

    /** 外部链接：优先拉起浏览器，失败复制链接并提示。 */
    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            Lg.i(TAG_NAV, "open external: $url")
        } catch (t: Throwable) {
            Lg.w(TAG_NAV, "open external failed: $url", t)
            try {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("link", url))
                Toast.makeText(this, R.string.settings_open_failed, Toast.LENGTH_SHORT).show()
            } catch (t2: Throwable) {
                Lg.w(TAG_NAV, "copy link failed", t2)
            }
        }
    }

    /** 主页效果预览：按当前材质参数喂入（只读不写，缺省值兜底）。 */
    private fun applyPreviewParams(preview: LiquidGlassPreviewView) {
        preview.blurRadius = prefFloat(Prefs.KEY_BLUR_RADIUS, Prefs.DEFAULT_BLUR_RADIUS)
        preview.vibrancy = prefFloat(Prefs.KEY_VIBRANCY, Prefs.DEFAULT_VIBRANCY)
        preview.highlightFalloff = prefFloat(Prefs.KEY_HIGHLIGHT_FALLOFF, Prefs.DEFAULT_HIGHLIGHT_FALLOFF)
        preview.refractionHeightRatio =
            prefFloat(Prefs.KEY_REFRACTION_HEIGHT_RATIO, Prefs.DEFAULT_REFRACTION_HEIGHT_RATIO)
        preview.refractionHeightMode =
            prefInt(Prefs.KEY_REFRACTION_HEIGHT_MODE, Prefs.DEFAULT_REFRACTION_HEIGHT_MODE)
        preview.refractionHeightPx = prefFloat(Prefs.KEY_REFRACTION_HEIGHT_PX, Prefs.DEFAULT_REFRACTION_HEIGHT_PX)
        preview.refractionAmount = prefFloat(Prefs.KEY_REFRACTION_AMOUNT, Prefs.DEFAULT_REFRACTION_AMOUNT)
        preview.dispersion = prefFloat(Prefs.KEY_DISPERSION, Prefs.DEFAULT_DISPERSION)
        preview.depth = prefFloat(Prefs.KEY_DEPTH, Prefs.DEFAULT_DEPTH)
        preview.highlight = prefFloat(Prefs.KEY_HIGHLIGHT, Prefs.DEFAULT_HIGHLIGHT)
        preview.highlightWidth = prefFloat(Prefs.KEY_HIGHLIGHT_WIDTH, Prefs.DEFAULT_HIGHLIGHT_WIDTH)
        preview.highlightFloor = prefFloat(Prefs.KEY_HIGHLIGHT_FLOOR, Prefs.DEFAULT_HIGHLIGHT_FLOOR)
        preview.highlightInnerAtt = prefFloat(Prefs.KEY_HIGHLIGHT_INNER_ATT, Prefs.DEFAULT_HIGHLIGHT_INNER_ATT)
        preview.surfacePolish = prefBoolean(Prefs.KEY_HIGHLIGHT_ENHANCE, Prefs.DEFAULT_HIGHLIGHT_ENHANCE)
        preview.innerGlow = prefFloat(Prefs.KEY_INNER_GLOW, Prefs.DEFAULT_INNER_GLOW)
        preview.edgeStroke = prefFloat(Prefs.KEY_EDGE_STROKE, Prefs.DEFAULT_EDGE_STROKE)
        preview.edgeStrokeWidth = prefFloat(Prefs.KEY_EDGE_STROKE_WIDTH, Prefs.DEFAULT_EDGE_STROKE_WIDTH)
        preview.maskGlassDim = prefFloat(Prefs.KEY_MASK_GLASS_DIM, Prefs.DEFAULT_MASK_GLASS_DIM)
        preview.lightAngle = prefFloat(Prefs.KEY_LIGHT_ANGLE, Prefs.DEFAULT_LIGHT_ANGLE)
        preview.refresh()
    }

    /** 读浮点配置：框架侧优先，回退本地 XML。 */
    private fun prefFloat(key: String, default: Float): Float =
        frameworkPrefs()?.getFloat(key, default) ?: xmlPrefs.getFloat(key, default)

    /** 读整型配置：框架侧优先，回退本地 XML。 */
    private fun prefInt(key: String, default: Int): Int =
        frameworkPrefs()?.getInt(key, default) ?: xmlPrefs.getInt(key, default)

    /** 读字符串配置：框架侧优先，回退本地 XML。 */
    private fun prefString(key: String, default: String): String =
        frameworkPrefs()?.getString(key, default) ?: xmlPrefs.getString(key, default) ?: default

    /** 读布尔配置：框架侧优先，回退本地 XML。 */
    private fun prefBoolean(key: String, default: Boolean): Boolean =
        frameworkPrefs()?.getBoolean(key, default) ?: xmlPrefs.getBoolean(key, default)

    // ------------------------------------------------------------ 玻璃页 / 玻璃化对象 ------------------------------------------------------------

    /** 玻璃页：绘制总控 / 预设 / 配置管理 / 类名分配 / 玻璃化对象 / 流体云 / 可读性 / 背景与采样。 */
    private fun bindGlassPage(view: View) {
        val glassSwitch = view.findViewById<Switch>(R.id.glassSwitch)
        val glassListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_GLASS_ENABLE, checked)
        }
        glassSwitch.setOnCheckedChangeListener(glassListener)
        refreshBooleanSwitch(glassSwitch, glassListener, Prefs.KEY_GLASS_ENABLE, Prefs.DEFAULT_GLASS_ENABLE)

        SfGlassTest.attach(this)
        SfModuleInstaller.attach(this)

        val modeGroup = view.findViewById<RadioGroup>(R.id.glassRenderModeGroup)
        val currentMode = (frameworkPrefs()?.getString(Prefs.KEY_RENDER_MODE, Prefs.DEFAULT_RENDER_MODE)
            ?: xmlPrefs.getString(Prefs.KEY_RENDER_MODE, Prefs.DEFAULT_RENDER_MODE))
            ?: Prefs.DEFAULT_RENDER_MODE
        modeGroup.setOnCheckedChangeListener(null)
        modeGroup.check(if (currentMode == Prefs.RENDER_MODE_SF) R.id.glassRadioRenderSf else R.id.glassRadioRenderApp)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.glassRadioRenderSf) Prefs.RENDER_MODE_SF else Prefs.RENDER_MODE_APP
            Lg.i(TAG_NAV, "glass page render mode -> $mode")
            writePrefString(Prefs.KEY_RENDER_MODE, mode)
        }

        view.findViewById<View>(R.id.glassBtnGetModule).setOnClickListener { showModuleInstallDialog() }
        view.findViewById<View>(R.id.glassBtnDalog).setOnClickListener { showGlassTestDialog() }

        setupRow(
            view.findViewById(R.id.presetAppearanceRow),
            getString(R.string.settings_preset_appearance),
            getString(R.string.settings_preset_appearance_desc),
            getString(R.string.settings_preset_apply),
        ).setOnClickListener { applyQualityPreset(highQuality = true) }
        setRowIcon(view.findViewById(R.id.presetAppearanceRow), R.drawable.ic_row_sparkle)
        setupRow(
            view.findViewById(R.id.presetBatteryRow),
            getString(R.string.settings_preset_battery),
            getString(R.string.settings_preset_battery_desc),
            getString(R.string.settings_preset_apply),
        ).setOnClickListener { applyQualityPreset(highQuality = false) }
        setRowIcon(view.findViewById(R.id.presetBatteryRow), R.drawable.ic_row_sparkle)
        setupRow(
            view.findViewById(R.id.presetCustomRow),
            getString(R.string.settings_preset_custom),
            getString(R.string.settings_preset_custom_desc),
        ).setOnClickListener { showPresetManageDialog() }
        setRowIcon(view.findViewById(R.id.presetCustomRow), R.drawable.ic_row_sparkle)

        val configList = view.findViewById<LinearLayout>(R.id.glassConfigList)
        configList.removeAllViews()
        // 默认档只读行：不是存储实体，不可改名/删除，点击只做选中并进材质页
        val defaultRow = layoutInflater.inflate(R.layout.item_row, configList, false)
        setupRow(
            defaultRow,
            getString(R.string.glass_preset_default),
            getString(R.string.settings_glass_config_default_desc),
            null,
        )
        setRowIcon(defaultRow, R.drawable.ic_row_generic)
        defaultRow.setOnClickListener {
            selectGlassPreset(Prefs.GLASS_PRESET_DEFAULT_ID)
            pushScreen("cat/material")
        }
        configList.addView(defaultRow)
        val presets = readGlassPresets()
        presets.forEachIndexed { index, preset ->
            val row = layoutInflater.inflate(R.layout.item_row, configList, false)
            val label = preset.name.ifBlank { getString(R.string.settings_glass_config_default, index + 1) }
            setupRow(
                row,
                label,
                getString(R.string.settings_glass_config_desc),
                getString(R.string.settings_glass_config_value),
            )
            setRowIcon(row, R.drawable.ic_row_config)
            row.setOnLongClickListener {
                showPresetRenameDialog(preset.id)
                true
            }
            row.setOnClickListener {
                selectGlassPreset(preset.id)
                pushScreen("cat/material")
            }
            configList.addView(row)
        }
        view.findViewById<View>(R.id.glassAddConfig).setOnClickListener {
            createGlassPreset()
            Lg.i(TAG_NAV, "glass config created, rebind list")
        }
        view.findViewById<View>(R.id.infoGlassControl)?.setOnClickListener {
            showConceptInfo(R.string.settings_glass_group_control, R.string.info_glass_control)
        }
        view.findViewById<View>(R.id.infoGlassPreset)?.setOnClickListener {
            showConceptInfo(R.string.settings_glass_group_preset, R.string.info_glass_preset)
        }
        view.findViewById<View>(R.id.infoGlassConfig)?.setOnClickListener {
            showConceptInfo(R.string.settings_glass_group_config, R.string.info_glass_config)
        }
        view.findViewById<View>(R.id.infoGlassAssign)?.setOnClickListener {
            showConceptInfo(R.string.settings_glass_group_assign, R.string.info_glass_assign)
        }

        setupRow(
            view.findViewById(R.id.glassClassAssignRow),
            getString(R.string.settings_class_assign_glass),
            getString(R.string.settings_class_assign_glass_desc),
        ).setOnClickListener { pushScreen("classassign") }
        setRowIcon(view.findViewById(R.id.glassClassAssignRow), R.drawable.ic_row_assign)

        setupRow(
            view.findViewById(R.id.glassObjSystemRow),
            getString(R.string.settings_obj_system),
            getString(R.string.settings_obj_system_desc),
        ).setOnClickListener { pushScreen("sysobj") }
        setRowIcon(view.findViewById(R.id.glassObjSystemRow), R.drawable.ic_row_phone)
        setupRow(
            view.findViewById(R.id.glassObjDesktopRow),
            getString(R.string.settings_obj_desktop),
            getString(R.string.settings_obj_desktop_desc),
        ).setOnClickListener { pushScreen("deskobj") }
        setRowIcon(view.findViewById(R.id.glassObjDesktopRow), R.drawable.ic_row_launcher)

        bindGlassSwitch(view, R.id.switchGalleryGlass, Prefs.KEY_GALLERY_GLASS, Prefs.DEFAULT_GALLERY_GLASS)
        bindGlassSwitch(
            view, R.id.switchAssistantScreenGlass,
            Prefs.KEY_ASSISTANT_SCREEN_GLASS, Prefs.DEFAULT_ASSISTANT_SCREEN_GLASS,
        )
        bindGlassSwitch(view, R.id.switchQuickSearchGlass, Prefs.KEY_QUICK_SEARCH_GLASS, Prefs.DEFAULT_QUICK_SEARCH_GLASS)
        bindGlassSwitch(view, R.id.switchCalculatorGlass, Prefs.KEY_CALCULATOR_GLASS, Prefs.DEFAULT_CALCULATOR_GLASS)
        bindGlassSwitch(view, R.id.switchSmartSidebarGlass, Prefs.KEY_SMART_SIDEBAR_GLASS, Prefs.DEFAULT_SMART_SIDEBAR_GLASS)

        val objSwitchIds = listOf(
            R.id.switchGalleryGlass, R.id.switchAssistantScreenGlass, R.id.switchQuickSearchGlass,
            R.id.switchCalculatorGlass, R.id.switchSmartSidebarGlass,
        )
        view.findViewById<View>(R.id.glassObjAllOn).setOnClickListener {
            objSwitchIds.forEach { id -> view.findViewById<Switch>(id)?.isChecked = true }
            Lg.i(TAG_NAV, "glass obj all on")
        }
        view.findViewById<View>(R.id.glassObjAllOff).setOnClickListener {
            objSwitchIds.forEach { id -> view.findViewById<Switch>(id)?.isChecked = false }
            Lg.i(TAG_NAV, "glass obj all off")
        }

        bindGlassSwitch(
            view, R.id.switchSeedlingCardGlass,
            Prefs.KEY_SEEDLING_CARD_GLASS_ENABLE, Prefs.DEFAULT_SEEDLING_CARD_GLASS_ENABLE,
        )
        bindGlassSwitch(
            view, R.id.switchSeedlingCapsuleGlass,
            Prefs.KEY_SEEDLING_CAPSULE_GLASS, Prefs.DEFAULT_SEEDLING_CAPSULE_GLASS,
        )
        bindGlassSwitch(
            view, R.id.switchSeedlingForceTransparentBg,
            Prefs.KEY_SEEDLING_CARD_FORCE_TRANSPARENT_BG, Prefs.DEFAULT_SEEDLING_CARD_FORCE_TRANSPARENT_BG,
        )

        bindMask(view)
        bindText(view)

        setupRow(
            view.findViewById(R.id.glassSamplingRow),
            getString(R.string.settings_glass_sampling_entry),
            getString(R.string.settings_glass_sampling_entry_desc),
        ).setOnClickListener { pushScreen("glass-sampling") }
    }

    /** 一键预设：高画质 = 满帧率 + 满采样；省电 = 低帧率 + 低采样 + 拉长抓屏间隔。 */
    private fun applyQualityPreset(highQuality: Boolean) {
        val hz = if (highQuality) Prefs.PANEL_CAPTURE_HZ_MAX else Prefs.PANEL_CAPTURE_HZ_MIN
        val scale = if (highQuality) 1.0f else 0.25f
        val interval = if (highQuality) 500 else 1000
        writePrefInt(Prefs.KEY_PANEL_CAPTURE_HZ, hz)
        writePrefFloat(Prefs.KEY_BG_CAPTURE_SCALE, scale)
        writePrefInt(Prefs.KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS, interval)
        Lg.i(TAG_NAV, "quality preset applied: high=$highQuality hz=$hz scale=$scale interval=$interval")
        Toast.makeText(
            this,
            getString(
                if (highQuality) R.string.settings_preset_appearance_applied
                else R.string.settings_preset_battery_applied
            ),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 玻璃化对象·系统界面：音量条玻璃（通知中心/控制中心跟随玻璃总开关）。 */
    private fun bindObjSystem(view: View) {
        bindGlassSwitch(
            view, R.id.switchVolumeSeekBarGlass,
            Prefs.KEY_VOLUME_SEEKBAR_GLASS, Prefs.DEFAULT_VOLUME_SEEKBAR_GLASS,
        )
        bindGlassSwitch(
            view, R.id.switchVolumePanelClean,
            Prefs.KEY_VOLUME_PANEL_CLEAN, Prefs.DEFAULT_VOLUME_PANEL_CLEAN,
        )
        bindGlassSwitch(
            view, R.id.switchControlProgressFill,
            Prefs.KEY_CONTROL_PROGRESS_FILL, Prefs.DEFAULT_CONTROL_PROGRESS_FILL,
        )
        bindGlassSwitch(
            view, R.id.switchQsHideSecondaryGlass,
            Prefs.KEY_QS_HIDE_SECONDARY_GLASS, Prefs.DEFAULT_QS_HIDE_SECONDARY_GLASS,
        )
    }

    /** 玻璃化对象·桌面：Dock / 卡片 / 搜索界面 / 长按菜单 / 最近任务 + 编辑界面入口。 */
    private fun bindObjDesktop(view: View) {
        bindGlassSwitch(view, R.id.switchDockGlass, Prefs.KEY_DOCK_GLASS_ENABLE, Prefs.DEFAULT_DOCK_GLASS_ENABLE)
        bindGlassSwitch(view, R.id.switchCardGlass, Prefs.KEY_CARD_GLASS_ENABLE, Prefs.DEFAULT_CARD_GLASS_ENABLE)
        bindGlassSwitch(
            view, R.id.switchPageIndicatorGlass,
            Prefs.KEY_PAGE_INDICATOR_GLASS, Prefs.DEFAULT_PAGE_INDICATOR_GLASS,
        )
        bindGlassSwitch(
            view, R.id.switchPopupMenuGlass,
            Prefs.KEY_POPUP_MENU_GLASS_ENABLE, Prefs.DEFAULT_POPUP_MENU_GLASS_ENABLE,
        )
        bindGlassSwitch(
            view, R.id.switchClearAllBtnGlass,
            Prefs.KEY_CLEAR_ALL_BTN_GLASS, Prefs.DEFAULT_CLEAR_ALL_BTN_GLASS,
        )
        setupRow(
            view.findViewById(R.id.deskEditRow),
            getString(R.string.settings_obj_desktop_edit),
            getString(R.string.settings_obj_desktop_edit_desc),
        ).setOnClickListener { pushScreen("deskedit") }
    }

    /** 玻璃化对象·桌面编辑界面：整理/完成按钮 + 编辑工具条按钮。 */
    private fun bindObjDesktopEdit(view: View) {
        bindGlassSwitch(
            view, R.id.switchEditToolbarGlass,
            Prefs.KEY_EDIT_TOOLBAR_GLASS, Prefs.DEFAULT_EDIT_TOOLBAR_GLASS,
        )
        bindGlassSwitch(
            view, R.id.switchEditToolBtnGlass,
            Prefs.KEY_EDIT_TOOLBTN_GLASS, Prefs.DEFAULT_EDIT_TOOLBTN_GLASS,
        )
    }

    /** 圆角页：配置管理（首版单配置）+ 类名分配入口。 */
    private fun bindCornerPage(view: View) {
        setupRow(
            view.findViewById(R.id.cornerConfigRow),
            getString(R.string.settings_corner_config_default),
            getString(R.string.settings_corner_config_desc),
            getString(R.string.settings_glass_config_value),
        ).setOnClickListener { pushScreen("cornerconfig") }
        view.findViewById<View>(R.id.cornerAddConfig).setOnClickListener {
            Lg.i(TAG_NAV, "corner add config tap -> deferred")
            Toast.makeText(this, R.string.settings_corner_add_config_toast, Toast.LENGTH_SHORT).show()
        }
        setupRow(
            view.findViewById(R.id.cornerClassAssignRow),
            getString(R.string.settings_corner_class_assign),
            getString(R.string.settings_corner_class_assign_desc),
        ).setOnClickListener { pushScreen("classassign-corner") }
    }

    /** 圆角配置页：实时预览 + 圆角大小 / 圆角自适应 / 跟随父容器。 */
    private fun bindCornerConfig(view: View) {
        val preview = view.findViewById<View>(R.id.cornerPreviewBox)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x33FFFFFF)
            setStroke(2, 0x66FFFFFF)
        }
        preview.background = bg

        val seek = view.findViewById<SeekBar>(R.id.seekCornerRadius)
        val valueText = view.findViewById<TextView>(R.id.cornerRadiusValue)
        val span = (Prefs.UNIFIED_CORNER_PX_MAX - Prefs.UNIFIED_CORNER_PX_MIN).toInt()
        seek.max = span
        fun applyUi(px: Float) {
            valueText.text = getString(R.string.settings_corner_radius_value, px.roundToInt())
            bg.cornerRadius = px
            preview.invalidate()
        }
        val current = prefFloat(Prefs.KEY_UNIFIED_CORNER_PX, Prefs.DEFAULT_UNIFIED_CORNER_PX)
            .coerceIn(Prefs.UNIFIED_CORNER_PX_MIN, Prefs.UNIFIED_CORNER_PX_MAX)
        seek.progress = (current - Prefs.UNIFIED_CORNER_PX_MIN).roundToInt().coerceIn(0, span)
        applyUi(current)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                applyUi(Prefs.UNIFIED_CORNER_PX_MIN + progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit

            override fun onStopTrackingTouch(sb: SeekBar) {
                val px = Prefs.UNIFIED_CORNER_PX_MIN + sb.progress
                writePrefFloat(Prefs.KEY_UNIFIED_CORNER_PX, px)
                writePrefBoolean(Prefs.KEY_UNIFIED_CORNER_ENABLE, true)
                Lg.i(TAG_NAV, "corner radius -> ${px.roundToInt()}px (unified enabled)")
            }
        })

        val adaptive = view.findViewById<Switch>(R.id.switchCornerAdaptive)
        val adaptiveListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writeCornerSwitchGlobal(Prefs.KEY_CORNER_ADAPTIVE_OFF, checked)
        }
        adaptive.setOnCheckedChangeListener(null)
        adaptive.isChecked = readCornerSwitchGlobal(Prefs.KEY_CORNER_ADAPTIVE_OFF)
        adaptive.setOnCheckedChangeListener(adaptiveListener)

        val followParent = view.findViewById<Switch>(R.id.switchCornerFollowParent)
        val followListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writeCornerSwitchGlobal(Prefs.KEY_CORNER_SYNC_EXTERNAL_OFF, checked)
        }
        followParent.setOnCheckedChangeListener(null)
        followParent.isChecked = readCornerSwitchGlobal(Prefs.KEY_CORNER_SYNC_EXTERNAL_OFF)
        followParent.setOnCheckedChangeListener(followListener)
    }

    /** 全局圆角开关状态：关闭名单为空 = 全部开启。 */
    private fun readCornerSwitchGlobal(key: String): Boolean =
        GlassRegistry.parseClassList(
            frameworkPrefs()?.getString(key, null) ?: xmlPrefs.getString(key, null),
        ).isEmpty()

    /** 全局圆角开关：开 = 清空关闭名单；关 = 把当前探测到的类全部加入关闭名单。 */
    private fun writeCornerSwitchGlobal(key: String, enabled: Boolean) {
        val off = if (enabled) emptySet() else detectedGlassClasses().toSet()
        putConfig(key) { it.putString(key, GlassRegistry.encodeClassList(off)).apply() }
        Lg.i(TAG_NAV, "corner switch global $key enabled=$enabled off=${off.size}")
    }

    // ------------------------------------------------------------ 类名分配（玻璃 / 圆角两套） ------------------------------------------------------------

    private enum class AssignMode { GLASS, CORNER }

    /** 类名分配页：搜索 + 三档筛选 + 批量分配 + 逐类配置下拉（改动立即生效，无应用按钮）。 */
    private fun bindClassAssign(view: View, mode: AssignMode) {
        val container = view.findViewById<LinearLayout>(R.id.caList)
        val search = view.findViewById<EditText>(R.id.caSearch)
        val chipOn = view.findViewById<TextView>(R.id.caChipOn)
        val chipOff = view.findViewById<TextView>(R.id.caChipOff)
        val chipAll = view.findViewById<TextView>(R.id.caChipAll)
        val batchCount = view.findViewById<TextView>(R.id.caBatchCount)
        val batchSpinner = view.findViewById<Spinner>(R.id.caBatchSpinner)

        val presetNames = readGlassPresets().mapIndexed { index, preset ->
            preset.name.ifBlank { getString(R.string.settings_glass_config_default, index + 1) }
        }
        val options = when (mode) {
            AssignMode.GLASS -> listOf(getString(R.string.ca_option_default)) + presetNames
            AssignMode.CORNER ->
                listOf(getString(R.string.ca_option_corner_default), getString(R.string.settings_corner_config_default))
        }

        val selected = linkedSetOf<String>()
        var filter = 0
        var query = ""

        fun isEnabled(cls: String): Boolean = when (mode) {
            AssignMode.GLASS -> readGlassPresets().any { cls in it.classes }
            AssignMode.CORNER -> readCornerOverrides().containsKey(cls)
        }

        fun currentOption(cls: String): Int = when (mode) {
            AssignMode.GLASS -> readGlassPresets().indexOfFirst { cls in it.classes }.let { if (it >= 0) it + 1 else 0 }
            AssignMode.CORNER -> if (readCornerOverrides().containsKey(cls)) 1 else 0
        }

        fun updateBatch() {
            batchCount.text = getString(R.string.ca_batch_count, selected.size)
        }

        lateinit var rebuild: () -> Unit

        fun showAssignDialog(cls: String) {
            val names = when (mode) {
                AssignMode.GLASS -> readGlassPresets().mapIndexed { index, preset ->
                    preset.name.ifBlank { getString(R.string.settings_glass_config_default, index + 1) }
                }
                AssignMode.CORNER -> listOf(getString(R.string.settings_corner_config_default))
            }
            if (names.isEmpty()) {
                Toast.makeText(this, R.string.ca_enable_no_config, Toast.LENGTH_SHORT).show()
                return
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ca_enable_title)
                .setItems(names.toTypedArray()) { _, which ->
                    applyClassAssignment(mode, cls, which + 1)
                    rebuild()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .also { DialogGlass.arm(it) }
        }

        fun buildRow(cls: String): View {
            val row = layoutInflater.inflate(R.layout.item_class_assign, container, false)
            row.findViewById<TextView>(R.id.caTitle).text = classDisplayText(cls).substringBefore(" — ")
            row.findViewById<TextView>(R.id.caClass).text = cls.substringAfterLast('.')
            val enabled = isEnabled(cls)
            val state = row.findViewById<TextView>(R.id.caState)
            state.text = getString(if (enabled) R.string.ca_state_on else R.string.ca_state_off)
            if (enabled) {
                state.setBackgroundResource(R.drawable.bg_chip)
            } else {
                state.setBackgroundResource(0)
                state.alpha = 0.6f
            }

            val cb = row.findViewById<CheckBox>(R.id.caCheck)
            cb.isChecked = selected.contains(cls)
            cb.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(cls) else selected.remove(cls)
                updateBatch()
            }

            val spinner = row.findViewById<Spinner>(R.id.caPreset)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val initial = currentOption(cls)
            spinner.setSelection(initial)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, sel: View?, position: Int, id: Long) {
                    if (position == initial) return
                    applyClassAssignment(mode, cls, position)
                    rebuild()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

            if (!enabled) row.setOnClickListener { showAssignDialog(cls) }
            return row
        }

        rebuild = {
            container.removeAllViews()
            val all = (detectedGlassClasses() + readGlassPresets().flatMap { it.classes } + readCornerOverrides().keys)
                .distinct()
                .sorted()
            val filtered = all.filter { cls ->
                val hitQuery = query.isEmpty() ||
                    classDisplayText(cls).contains(query, ignoreCase = true) ||
                    cls.contains(query, ignoreCase = true)
                val hitFilter = when (filter) {
                    0 -> isEnabled(cls)
                    1 -> !isEnabled(cls)
                    else -> true
                }
                hitQuery && hitFilter
            }
            if (filtered.isEmpty()) {
                container.addView(
                    TextView(this).apply {
                        text = getString(R.string.ca_empty)
                        gravity = Gravity.CENTER
                        alpha = 0.6f
                        setPadding(0, 48, 0, 48)
                    }
                )
            }
            filtered.groupBy { sceneOfClass(it) }.forEach { (scene, classes) ->
                container.addView(
                    TextView(this).apply {
                        text = scene
                        textSize = 13f
                        alpha = 0.7f
                        setPadding(4, 20, 4, 6)
                    }
                )
                classes.forEach { cls -> container.addView(buildRow(cls)) }
            }
            updateBatch()
        }

        val chips = listOf(chipOn, chipOff, chipAll)
        fun applyChipUi() {
            chips.forEachIndexed { index, chip -> chip.alpha = if (index == filter) 1f else 0.55f }
        }
        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                filter = index
                applyChipUi()
                Lg.i(TAG_NAV, "class assign filter -> $index (${mode.name})")
                rebuild()
            }
        }
        applyChipUi()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                logNavThrottled("ca-search-${mode.name}", "class assign search q=\"$query\" (${mode.name})")
                rebuild()
            }
        })

        val batchOptions = when (mode) {
            AssignMode.GLASS -> listOf(getString(R.string.ca_batch_placeholder)) + options
            AssignMode.CORNER -> listOf(getString(R.string.ca_batch_placeholder_corner)) + options
        }
        batchSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, batchOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        batchSpinner.setSelection(0)
        batchSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, sel: View?, position: Int, id: Long) {
                if (position == 0) return
                if (selected.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, R.string.ca_batch_count_zero, Toast.LENGTH_SHORT).show()
                    batchSpinner.setSelection(0)
                    return
                }
                val targets = selected.toList()
                targets.forEach { applyClassAssignment(mode, it, position) }
                Lg.i(TAG_NAV, "class assign batch: ${targets.size} classes -> option $position (${mode.name})")
                selected.clear()
                batchSpinner.setSelection(0)
                rebuild()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        rebuild()
    }

    /** 按场景给类分组（桌面 / 系统界面 / 相册 / 计算器 / 速览 / 侧边栏 / 其他）。 */
    private fun sceneOfClass(cls: String): String = when {
        cls.contains("launcher") -> getString(R.string.ca_scene_launcher)
        cls.contains("systemui") -> getString(R.string.ca_scene_systemui)
        cls.contains("gallery") -> getString(R.string.ca_scene_gallery)
        cls.contains("calculator") -> getString(R.string.ca_scene_calculator)
        cls.contains("assistantscreen") -> getString(R.string.ca_scene_assistant)
        cls.contains("smartsidebar") -> getString(R.string.ca_scene_sidebar)
        else -> getString(R.string.ca_scene_other)
    }

    /** 分配一个类：玻璃 = 写进目标配置（先从其它配置移除）；圆角 = 写/清该类圆角覆盖。 */
    private fun applyClassAssignment(mode: AssignMode, cls: String, optionIndex: Int) {
        when (mode) {
            AssignMode.GLASS -> {
                val target = if (optionIndex <= 0) null else readGlassPresets().getOrNull(optionIndex - 1)
                assignGlassClass(cls, target)
            }
            AssignMode.CORNER -> {
                val px = if (optionIndex <= 0) 0f
                else prefFloat(Prefs.KEY_UNIFIED_CORNER_PX, Prefs.DEFAULT_UNIFIED_CORNER_PX)
                writeCornerOverride(cls, px)
                Lg.i(TAG_NAV, "assign corner class ${cls.substringAfterLast('.')} px=$px")
            }
        }
    }

    /** 玻璃类归属：一个类同时只落在一个配置里（null = 回到默认参数）。 */
    private fun assignGlassClass(cls: String, target: GlassRegistry.Preset?) {
        val ok = editGlassPresets { list ->
            for (i in list.indices) {
                val preset = list[i]
                if (cls in preset.classes) list[i] = preset.copy(classes = preset.classes.filterNot { it == cls })
            }
            if (target != null) {
                val i = list.indexOfFirst { it.id == target.id }
                if (i >= 0 && cls !in list[i].classes) {
                    list[i] = list[i].copy(classes = list[i].classes + cls)
                }
            }
        }
        if (ok) Lg.i(TAG_NAV, "assign glass class ${cls.substringAfterLast('.')} -> ${target?.name ?: "默认"}")
    }

    /** 系统模块安装：警示弹窗两路（root 直装 / 导出到下载目录）。 */
    private fun showModuleInstallDialog() {
        Lg.i(TAG_NAV, "sf module install dialog shown")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_sf_get_module)
            .setMessage(R.string.settings_sf_install_warning)
            .setPositiveButton(R.string.settings_sf_install_root) { _, _ ->
                Lg.i(TAG_NAV, "sf module install choice: root")
                SfModuleInstaller.rootInstall { msg -> onSfInstallDone(msg) }
            }
            .setNegativeButton(R.string.settings_sf_install_export) { _, _ ->
                Lg.i(TAG_NAV, "sf module install choice: export")
                SfModuleInstaller.exportToDownloads { msg -> onSfInstallDone(msg) }
            }
            .show()
            .also { DialogGlass.arm(it) }
    }

    /** 系统级别玻璃效果测试：弹 SF 测试框。 */
    private fun showGlassTestDialog() {
        Lg.i(TAG_NAV, "sf glass test requested")
        SfGlassTest.show { msg -> Lg.i(TAG_NAV, "sf glass test done: $msg") }
    }

    /** 二级设置页：压入分类屏并渲染绑定。 */
    private fun openCategory(catId: String) {
        if (categories.none { it.id == catId }) {
            logNavThrottled("open-unknown-cat", "openCategory skipped: unknown id=$catId")
            return
        }
        pushScreen("cat/$catId")
    }

    /** [spec/64] 三级页：桌面动画参数（打开 / 关闭 / 打断）。 */
    private fun openAnimParams(group: String) {
        currentAnimGroup = group
        pushScreen("cat/anim-params")
    }

    /** 压入目标屏到其所属栏的返回栈并渲染。 */
    private fun pushScreen(id: String) {
        val def = screens[id]
        if (def == null) {
            logNavThrottled("push-unknown", "pushScreen skipped: unknown id=$id")
            return
        }
        if (def.column != currentTab) currentTab = def.column
        val st = stacks.getValue(def.column)
        if (st.last() == id) {
            logNavThrottled("push-dup", "pushScreen skipped: $id already on top")
            return
        }
        st.add(id)
        Lg.i(TAG_NAV, "open id=$id tab=$currentTab stack=$st")
        renderTop(1)
    }

    /** 底栏切换：再点当前栏回到该栏根屏。 */
    private fun selectTab(tab: NavColumn) {
        if (tab == currentTab) {
            val st = stacks.getValue(tab)
            if (st.size > 1) {
                st.clear()
                st.add(rootOf(tab))
                Lg.i(TAG_NAV, "retap tab=$tab -> reset stack=$st")
                renderTop()
            } else {
                logNavThrottled("retap-root", "retap tab=$tab already at root")
            }
            return
        }
        val from = currentTab
        currentTab = tab
        Lg.i(TAG_NAV, "tab switch $from -> $tab stack=${stack()}")
        renderTop()
    }

    /**
     * 统一返回动作：导航图标、扩大的热区（backHitOverlay）、手势/系统返回
     * （onBackPressedDispatcher）三处共用 —— 栏内逐屏回退，退到该栏根屏后再返回则结束。
     */
    private fun navigateUp() {
        val st = stack()
        if (st.size > 1) {
            val from = st.removeAt(st.size - 1)
            Lg.i(TAG_NAV, "back id=$from -> ${st.last()} tab=$currentTab stack=$st")
            renderTop(-1)
        } else {
            Lg.i(TAG_NAV, "back at root id=${st.last()} tab=$currentTab -> finish")
            finish()
        }
    }

    /** 渲染当前栈顶屏：标题 / 返回可见性 / 顶栏图标位 / 内容 / 底栏高亮 / 控件绑定。dir 只控换页动画方向（1 进入 / -1 返回 / 0 换栏或刷新）。 */
    private fun renderTop(dir: Int = 0) {
        val id = topScreen()
        val def = screens[id]
        if (def == null) {
            logNavThrottled("render-unknown", "render skipped: unknown id=$id")
            return
        }
        val st = stack()
        val showBack = st.size > 1
        supportActionBar?.setDisplayHomeAsUpEnabled(showBack)
        backHitOverlay?.visibility = if (showBack) View.VISIBLE else View.GONE
        restartScopeBtn?.visibility = if (id == "func") View.VISIBLE else View.GONE
        supportActionBar?.subtitle =
            if (id == "home") getString(R.string.settings_shell_subtitle) else null
        supportActionBar?.title = when {
            id == "home" -> getString(R.string.app_name)
            id == "cat/anim-params" -> getString(animGroupTitleRes(currentAnimGroup ?: Prefs.ANIM_GROUP_OPEN))
            def.titleRes != 0 -> getString(def.titleRes)
            else -> id
        }
        val page: View = when {
            id == "home" -> {
                currentCategory = null
                currentAnimGroup = null
                layoutInflater.inflate(R.layout.view_shell_home, contentFrame, false)
            }
            id == "func" -> {
                currentCategory = null
                currentAnimGroup = null
                layoutInflater.inflate(R.layout.view_shell_func, contentFrame, false)
            }
            id == "cat/anim-params" -> {
                currentCategory = "anim"
                layoutInflater.inflate(R.layout.view_anim_params, contentFrame, false)
            }
            def.categoryId != null -> {
                currentCategory = def.categoryId
                currentAnimGroup = null
                layoutInflater.inflate(def.layoutRes, contentFrame, false)
            }
            def.layoutRes != 0 -> {
                currentCategory = null
                currentAnimGroup = null
                layoutInflater.inflate(def.layoutRes, contentFrame, false)
            }
            else -> {
                val ph = layoutInflater.inflate(R.layout.view_shell_placeholder, contentFrame, false)
                ph.findViewById<TextView>(R.id.placeholderText).text = getString(
                    when (id) {
                        "home" -> R.string.settings_shell_home_placeholder
                        "settings" -> R.string.settings_shell_settings_placeholder
                        else -> R.string.settings_shell_pending_placeholder
                    }
                )
                currentCategory = null
                currentAnimGroup = null
                if (def.pending) {
                    logNavThrottled("pending-$id", "pending screen shown: $id (content not wired)")
                }
                ph
            }
        }
        bottomNav?.visibility = if (id == "aod-display") View.GONE else View.VISIBLE
        toolbarRow?.visibility = if (id == "aod-display") View.GONE else View.VISIBLE
        swapPage(page, dir, id)
        updateTabHighlight()
        Lg.i(TAG_NAV, "render id=$id tab=$currentTab stack=$st back=$showBack dir=$dir")
        bindCurrentPage()
    }

    /**
     * 换页：dir=1 新页右侧滑入（旧页左移）、dir=-1 旧页右滑退场（新页从左归位）、
     * dir=0 同屏刷新即时替换 / 换栏旧页即撤+新页单独淡入。离开记滚动位、回来还原。
     * 任一时刻最多一层页面内容（防双层半透明叠加透底）。
     */
    private fun swapPage(page: View, dir: Int, id: String) {
        outgoingView?.let { o ->
            o.animate().cancel()
            o.setOnTouchListener(null)
            contentFrame.removeView(o)
            outgoingView = null
        }
        val old = currentPageView
        val oldId = renderedId
        if (old != null) {
            if (oldId != null) scrollMem[oldId] = findScrollable(old)?.scrollY ?: old.scrollY
            old.animate().cancel()
            old.translationX = 0f
            old.alpha = 1f
        }
        page.setBackgroundResource(R.drawable.bg_screen)
        contentFrame.addView(page)
        currentPageView = page
        renderedId = id
        val samePage = old != null && oldId == id
        if (old != null && !samePage) {
            val w = contentFrame.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
            val dur = 320L
            val cleanup = Runnable {
                old.setOnTouchListener(null)
                contentFrame.removeView(old)
                if (outgoingView === old) outgoingView = null
            }
            when {
                dir > 0 -> {
                    // 新页整层滑入盖在旧页上，旧页全程不动垫底，避免露出底图
                    outgoingView = old
                    old.setOnTouchListener { _, _ -> true }
                    page.translationX = w.toFloat()
                    page.animate().translationX(0f).setDuration(dur).setInterpolator(ease)
                        .withEndAction(cleanup).start()
                }
                dir < 0 -> {
                    outgoingView = old
                    old.setOnTouchListener { _, _ -> true }
                    old.bringToFront()
                    page.translationX = -w * 0.26f
                    page.animate().translationX(0f).setDuration(dur).setInterpolator(ease).start()
                    old.animate().translationX(w.toFloat())
                        .setDuration(dur).setInterpolator(ease).withEndAction(cleanup).start()
                }
                else -> {
                    // 换栏直切，无半透明帧，防止透出底图
                    old.animate().cancel()
                    old.setOnTouchListener(null)
                    contentFrame.removeView(old)
                    if (outgoingView === old) outgoingView = null
                }
            }
        } else if (old != null) {
            contentFrame.removeView(old)
        }
        val saved = scrollMem[id] ?: 0
        if (saved != 0) {
            val scroller = findScrollable(page)
            if (scroller != null) {
                page.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        page.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        scroller.scrollTo(0, saved)
                    }
                })
            }
        }
    }

    /** 当前在架页：动画期间内容区可能同时挂旧页，读页一律走这里，不看 child 下标。 */
    private fun currentPage(): View? = currentPageView ?: contentFrame.getChildAt(0)

    /** 找页内的滚动容器（优先 ScrollView；都没有就取第一个自己能滚的容器）。 */
    private fun findScrollable(root: View): View? {
        if (root is ScrollView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findScrollable(root.getChildAt(i))?.let { return it }
            }
            if (root.canScrollVertically(-1) || root.canScrollVertically(1) || root.scrollY != 0) return root
        }
        return null
    }

    /** 底栏三栏点击接线。 */
    private fun setupBottomNav() {
        bottomNav = findViewById(R.id.bottomNav)
        navTabViews = mapOf(
            NavColumn.HOME to findViewById<TextView>(R.id.navTabHome),
            NavColumn.FUNC to findViewById<TextView>(R.id.navTabFunc),
            NavColumn.SETTINGS to findViewById<TextView>(R.id.navTabSettings),
        )
        navTabViews.forEach { (tab, view) -> view.setOnClickListener { selectTab(tab) } }
        listOf(
            R.id.navTabHomeBox to NavColumn.HOME,
            R.id.navTabFuncBox to NavColumn.FUNC,
            R.id.navTabSettingsBox to NavColumn.SETTINGS,
        ).forEach { (id, tab) ->
            findViewById<View>(id)?.setOnClickListener { selectTab(tab) }
        }
    }

    /** 底栏选中态：图标与文字着色 + 顶部黄铜指示条。 */
    private fun updateTabHighlight() {
        navTabViews.forEach { (tab, view) ->
            val selected = tab == currentTab
            view.setTextColor(
                resources.getColor(if (selected) R.color.lg_blued_ink else R.color.lg_ink_400, theme)
            )
            view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            val icon = when (tab) {
                NavColumn.HOME -> findViewById<ImageView>(R.id.navIconHome)
                NavColumn.FUNC -> findViewById<ImageView>(R.id.navIconFunc)
                NavColumn.SETTINGS -> findViewById<ImageView>(R.id.navIconSettings)
            }
            icon?.setColorFilter(
                resources.getColor(if (selected) R.color.lg_blued_ink else R.color.lg_ink_400, theme)
            )
            val indicator = when (tab) {
                NavColumn.HOME -> findViewById<View>(R.id.navIndHome)
                NavColumn.FUNC -> findViewById<View>(R.id.navIndFunc)
                NavColumn.SETTINGS -> findViewById<View>(R.id.navIndSettings)
            }
            indicator?.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    /** 按当前栈顶重新绑定控件（首次进入 + 框架就绪后权威值刷新共用）。 */
    private fun bindCurrentPage() {
        val view = currentPage()
        if (view == null) {
            logNavThrottled("bind-empty", "bind skipped: content empty")
            return
        }
        when (val id = topScreen()) {
            "home" -> bindHome(view)
            "func" -> bindFunc(view)
            "glass" -> bindGlassPage(view)
            "glass-sampling" -> bindSampling(view)
            "corner" -> bindCornerPage(view)
            "cornerconfig" -> bindCornerConfig(view)
            "classassign" -> bindClassAssign(view, AssignMode.GLASS)
            "classassign-corner" -> bindClassAssign(view, AssignMode.CORNER)
            "sysobj" -> bindObjSystem(view)
            "deskobj" -> bindObjDesktop(view)
            "deskedit" -> bindObjDesktopEdit(view)
            "panel" -> bindPanel(view)
            "launcher" -> bindLauncherPage(view)
            "seedling" -> bindSeedlingPage(view)
            "aod" -> bindAodPage(view)
            "aod-display" -> bindAodDisplay(view)
            "aod-configs" -> bindAodConfigs(view)
            "settings" -> bindSettingsRoot(view)
            "logpage" -> bindLogPage(view)
            "diagpage" -> bindDiagPage(view)
            "config" -> bindConfig(view)
            "about" -> bindAbout(view)
            // 赞助页为静态展示页，无控件需要绑定
            "sponsor" -> {}
            "cat/anim-params" -> {
                val group = currentAnimGroup
                if (group == null) {
                    logNavThrottled("bind-no-group", "bind skipped: anim params without group")
                    return
                }
                bindAnimParams(view, group)
            }
            else -> if (id.startsWith("cat/")) {
                bindCategory(id.removePrefix("cat/"))
            } else {
                logNavThrottled("bind-skip", "bind skipped: screen $id has no binder")
            }
        }
    }

    /** 绑定当前大类控件（首次进入 + 框架就绪后权威值刷新共用）。 */
    private fun bindCategory(catId: String) {
        val view = currentPage()
        if (view == null) {
            logNavThrottled("bind-cat-empty", "bindCategory skipped: content empty ($catId)")
            return
        }
        when (catId) {
            "master" -> bindMaster(view)
            "material" -> bindMaterial(view)
            "corner" -> bindCornerRadius(view)
            "background" -> bindBackground(view)
            "text" -> bindText(view)
            "mask" -> bindMask(view)
            "seedling" -> bindSeedling(view)
            "anim" -> bindAnim(view)
            "tilt" -> bindTilt(view)
            "desktop" -> bindDesktop(view)
            "components" -> bindComponents(view)
            "statusbar" -> bindStatusBar(view)
            "log" -> bindLog(view)
            "config" -> bindConfig(view)
            "about" -> bindAbout(view)
            else -> logNavThrottled("bind-unknown-cat", "bindCategory skipped: unknown id=$catId")
        }
    }

    // ------------------------------------------------------------ 各二级大类绑定 ------------------------------------------------------------

    /** 总开关。 */
    private fun bindMaster(view: View) {
        val switch = view.findViewById<Switch>(R.id.switchMaster)
        val switchListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writeMasterEnabled(checked)
        }
        switch.setOnCheckedChangeListener(switchListener)
        refreshMasterSwitch(switch, switchListener)
        // 玻璃渲染总开关（关=回退系统模糊，倾斜/飞入不受影响；hook 端 500ms TTL 直读）
        val switchGlass = view.findViewById<Switch>(R.id.switchGlass)
        val glassListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_GLASS_ENABLE, checked)
        }
        switchGlass.setOnCheckedChangeListener(glassListener)
        refreshBooleanSwitch(switchGlass, glassListener, Prefs.KEY_GLASS_ENABLE, Prefs.DEFAULT_GLASS_ENABLE)

        // 注入宿主 Activity（测试框弹窗与模块打包需要）
        SfGlassTest.attach(this)
        SfModuleInstaller.attach(this)

        // 渲染模式两档：回读 render_mode 并回显，切换即写入
        val modeGroup = view.findViewById<RadioGroup>(R.id.renderModeGroup)
        val currentMode = (frameworkPrefs()?.getString(Prefs.KEY_RENDER_MODE, Prefs.DEFAULT_RENDER_MODE)
            ?: xmlPrefs.getString(Prefs.KEY_RENDER_MODE, Prefs.DEFAULT_RENDER_MODE))
            ?: Prefs.DEFAULT_RENDER_MODE
        modeGroup.setOnCheckedChangeListener(null)
        modeGroup.check(if (currentMode == Prefs.RENDER_MODE_SF) R.id.radioRenderSf else R.id.radioRenderApp)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.radioRenderSf) Prefs.RENDER_MODE_SF else Prefs.RENDER_MODE_APP
            Lg.i(TAG, "render mode selected: $mode")
            writePrefString(Prefs.KEY_RENDER_MODE, mode)
        }

        // 获取系统模块：警示弹窗两路（root 直装 / 导出到下载目录）
        view.findViewById<View>(R.id.btnGetSystemModule).setOnClickListener { showModuleInstallDialog() }

        // 点击打开dalog：弹 SF 玻璃测试框
        view.findViewById<View>(R.id.btnOpenDalog).setOnClickListener { showGlassTestDialog() }
    }

    /** 系统模块安装回执：弹 Toast + 记日志（回调线程不确定，统一切主线程）。 */
    private fun onSfInstallDone(msg: String) {
        Lg.i(TAG, "sf module install done: $msg")
        runOnUiThread {
            if (!isFinishing && !isDestroyed) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** 液态玻璃材质：内部轻模糊 / 鲜艳度 / 高光衰减 / 折射环带高度 / 折射强度。 */
    private fun bindMaterial(view: View) {
        // [spec/89] 顶部实时效果预览：滑杆拖动中即时刷新（落盘仍走 onStopTrackingTouch，配置写入时机不变）。
        // 视差 X/Y 不喂预览 —— 它会把采样区整体移出预览框，画面会变成空白玻璃（见 doc/spec/89）。
        val preview: LiquidGlassPreviewView? = view.findViewById(R.id.materialPreview)

        // 顶栏预设：选中预设后下方滑杆改读/改写该预设的参数键，未设的键回落全局值
        val glassPresets = readGlassPresets()
        val presetId = normalizeSelectedPresetId(glassPresets)
        val presetParams: Map<String, Float>? = glassPresets.find { it.id == presetId }?.params
        bindGlassPresetBar(view, glassPresets, presetId)

        // [2026-09-16 恢复配置化] 模糊半径：0~32 px（默认 8）。原被硬编码 0（清晰透镜），
        // 用户反馈「横幅太透」。>0 时 shader 走 5×5 内部轻模糊（25 次采样，仅作用内部不糊折射环带）。
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekBlurRadius), valueText = view.findViewById(R.id.blurRadiusValue),
            key = Prefs.KEY_BLUR_RADIUS, default = Prefs.DEFAULT_BLUR_RADIUS,
            fromProgress = { p -> p.toFloat() },
            toProgress = { v -> v.roundToInt().coerceIn(0, Prefs.BLUR_RADIUS_UI_MAX) },
            format = { v -> getString(R.string.settings_blur_radius_value, v.roundToInt()) },
            onChanged = { v -> preview?.let { it.blurRadius = v; it.refresh() } },
            presetParams = presetParams, presetId = presetId,
        )
        // vibrancy：1.0~3.0（progress 0~100 → 1.0+2.0×p/100，默认 1.54）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekVibrancy), valueText = view.findViewById(R.id.vibrancyValue),
            key = Prefs.KEY_VIBRANCY, default = Prefs.DEFAULT_VIBRANCY,
            fromProgress = { p -> 1.0f + p / 50f },
            toProgress = { v -> ((v - 1.0f) * 50f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_vibrancy_value, v) },
            onChanged = { v -> preview?.let { it.vibrancy = v; it.refresh() } },
            presetParams = presetParams, presetId = presetId,
        )
        // highlightFalloff：1~4（默认 2，越大越聚光）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekHighlightFalloff), valueText = view.findViewById(R.id.highlightFalloffValue),
            key = Prefs.KEY_HIGHLIGHT_FALLOFF, default = Prefs.DEFAULT_HIGHLIGHT_FALLOFF,
            presetParams = presetParams, presetId = presetId,
            fromProgress = { p -> 1f + p },
            toProgress = { v -> (v - 1f).roundToInt().coerceIn(0, 3) },
            format = { v -> getString(R.string.settings_highlight_falloff_value, v.roundToInt()) },
            onChanged = { v -> preview?.let { it.highlightFalloff = v; it.refresh() } },
        )
        // [doc/spec/95 2026-10-10] 折射区域（相对·短边比例）：0.10~1.00，默认 0.50 = 改动前「短边一半」。
        // progress 0~90 → 0.10~1.00，步进 0.01；落盘仍走 onStopTrackingTouch，渲染端 500ms TTL 重读。
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekRefractionHeightRatio), valueText = view.findViewById(R.id.refractionHeightRatioValue),
            key = Prefs.KEY_REFRACTION_HEIGHT_RATIO, default = Prefs.DEFAULT_REFRACTION_HEIGHT_RATIO,
            presetParams = presetParams, presetId = presetId,
            fromProgress = { p -> Prefs.REFRACTION_HEIGHT_RATIO_MIN + p / 100f },
            toProgress = { v -> ((v - Prefs.REFRACTION_HEIGHT_RATIO_MIN) * 100f).roundToInt().coerceIn(0, 90) },
            format = { v -> getString(R.string.settings_refraction_height_ratio_value, v) },
            onChanged = { v -> preview?.let { it.refractionHeightRatio = v; it.refresh() } },
        )
        // [doc/spec/96 2026-10-10] 折射区域（绝对·像素）：8~400 px，默认 64，步进 1。仅绝对模式下显示。
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekRefractionHeightPx), valueText = view.findViewById(R.id.refractionHeightPxValue),
            key = Prefs.KEY_REFRACTION_HEIGHT_PX, default = Prefs.DEFAULT_REFRACTION_HEIGHT_PX,
            presetParams = presetParams, presetId = presetId,
            fromProgress = { p -> Prefs.REFRACTION_HEIGHT_PX_MIN + p },
            toProgress = { v -> (v - Prefs.REFRACTION_HEIGHT_PX_MIN).roundToInt().coerceIn(0, 392) },
            format = { v -> getString(R.string.settings_refraction_height_px_value, v.roundToInt()) },
            onChanged = { v -> preview?.let { it.refractionHeightPx = v; it.refresh() } },
        )
        // [doc/spec/96] 模式切换（相对 ↔ 绝对）：两个滑杆互斥显示，切模式立即写 KEY_REFRACTION_HEIGHT_MODE。
        val modeChips = view.findViewById<ChipGroup>(R.id.refractionHeightModeChips)
        val ratioLabel = view.findViewById<View>(R.id.refractionHeightRatioLabel)
        val seekRatio = view.findViewById<SeekBar>(R.id.seekRefractionHeightRatio)
        val ratioValue = view.findViewById<View>(R.id.refractionHeightRatioValue)
        val ratioDesc = view.findViewById<View>(R.id.refractionHeightRatioDesc)
        val pxLabel = view.findViewById<View>(R.id.refractionHeightPxLabel)
        val seekPx = view.findViewById<SeekBar>(R.id.seekRefractionHeightPx)
        val pxValue = view.findViewById<View>(R.id.refractionHeightPxValue)
        val pxDesc = view.findViewById<View>(R.id.refractionHeightPxDesc)
        fun applyRefractionMode(mode: Int, persist: Boolean) {
            val abs = mode == Prefs.REFRACTION_HEIGHT_MODE_ABS
            val ratioVis = if (abs) View.GONE else View.VISIBLE
            val pxVis = if (abs) View.VISIBLE else View.GONE
            ratioLabel.visibility = ratioVis
            seekRatio.visibility = ratioVis
            ratioValue.visibility = ratioVis
            ratioDesc.visibility = ratioVis
            pxLabel.visibility = pxVis
            seekPx.visibility = pxVis
            pxValue.visibility = pxVis
            pxDesc.visibility = pxVis
            if (persist) writePrefInt(Prefs.KEY_REFRACTION_HEIGHT_MODE, mode)
            preview?.let { it.refractionHeightMode = mode; it.refresh() }
        }
        val currentMode = (frameworkPrefs()?.let {
            Prefs.readIntCompat(it, Prefs.KEY_REFRACTION_HEIGHT_MODE, Prefs.DEFAULT_REFRACTION_HEIGHT_MODE)
        } ?: Prefs.readIntCompat(xmlPrefs, Prefs.KEY_REFRACTION_HEIGHT_MODE, Prefs.DEFAULT_REFRACTION_HEIGHT_MODE))
        modeChips.removeAllViews()
        listOf(
            Prefs.REFRACTION_HEIGHT_MODE_RATIO to R.string.settings_refraction_height_ratio,
            Prefs.REFRACTION_HEIGHT_MODE_ABS to R.string.settings_refraction_height_px,
        ).forEach { (mode, labelRes) ->
            modeChips.addView(Chip(view.context).apply {
                id = View.generateViewId()
                text = getString(labelRes)
                isCheckable = true
                isChecked = mode == currentMode
                setOnClickListener { applyRefractionMode(mode, persist = true) }
            })
        }
        applyRefractionMode(currentMode, persist = false)
        // refractionAmount：0~150 px（折射强度，默认 100）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekRefractionAmount), valueText = view.findViewById(R.id.refractionAmountValue),
            key = Prefs.KEY_REFRACTION_AMOUNT, default = Prefs.DEFAULT_REFRACTION_AMOUNT,
            fromProgress = { p -> p.toFloat() },
            toProgress = { v -> v.roundToInt().coerceIn(0, Prefs.REFRACTION_AMOUNT_UI_MAX) },
            format = { v -> getString(R.string.settings_refraction_amount_value, v.roundToInt()) },
            onChanged = { v -> preview?.let { it.refractionAmount = v; it.refresh() } },
            presetParams = presetParams, presetId = presetId,
        )
        // [2026-08-15 材质参数控件补齐] 色散/深度/高光/高光宽度/光角度/视差（读 KEY_*，readMaterialParams
        // 已接线，改动约 1s 内生效）
        // dispersion：0~30 → 0.00~0.30（默认 0 = 关闭色散）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekDispersion), valueText = view.findViewById(R.id.dispersionValue),
            key = Prefs.KEY_DISPERSION, default = Prefs.DEFAULT_DISPERSION,
            fromProgress = { p -> p / 100f },
            toProgress = { v -> (v * 100f).roundToInt().coerceIn(0, 30) },
            format = { v -> getString(R.string.settings_dispersion_value, v) },
            onChanged = { v -> preview?.let { it.dispersion = v; it.refresh() } },
            presetParams = presetParams, presetId = presetId,
        )
        // depth：0~100 → 0~15（默认 0.72）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekDepth), valueText = view.findViewById(R.id.depthValue),
            key = Prefs.KEY_DEPTH, default = Prefs.DEFAULT_DEPTH,
            fromProgress = { p -> p / 100f * 15f },
            toProgress = { v -> (v / 15f * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_depth_value, v) },
            onChanged = { v -> preview?.let { it.depth = v; it.refresh() } },
            presetParams = presetParams, presetId = presetId,
        )
        // highlight：0~100 → 0~2（默认 0.5）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekHighlight), valueText = view.findViewById(R.id.highlightValue),
            key = Prefs.KEY_HIGHLIGHT, default = Prefs.DEFAULT_HIGHLIGHT,
            fromProgress = { p -> p / 100f * 2f },
            toProgress = { v -> (v / 2f * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_highlight_value, v) },
            onChanged = { v -> preview?.let { it.highlight = v; it.refresh() } },
            presetParams = presetParams, presetId = presetId,
        )
        // highlightWidth：0~200 px（默认 12；2026-10-13 上限放开 60→200）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekHighlightWidth), valueText = view.findViewById(R.id.highlightWidthValue),
            key = Prefs.KEY_HIGHLIGHT_WIDTH, default = Prefs.DEFAULT_HIGHLIGHT_WIDTH,
            presetParams = presetParams, presetId = presetId,
            fromProgress = { p -> p.toFloat() },
            toProgress = { v -> v.roundToInt().coerceIn(0, 200) },
            format = { v -> getString(R.string.settings_highlight_width_value, v.roundToInt()) },
            onChanged = { v -> preview?.let { it.highlightWidth = v; it.refresh() } },
        )
        // highlightFloor：0~100 → 0~1（默认 0.4）。[doc/spec/67] 弧线高光保底：
        // 0 = 纯方向性（圆角弧线因法线与光源垂直而偏暗）、1 = 整圈均匀一条亮线（等于"直接描一条边"）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekHighlightFloor), valueText = view.findViewById(R.id.highlightFloorValue),
            key = Prefs.KEY_HIGHLIGHT_FLOOR, default = Prefs.DEFAULT_HIGHLIGHT_FLOOR,
            presetParams = presetParams, presetId = presetId,
            fromProgress = { p -> p / 100f },
            toProgress = { v -> (v * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_highlight_floor_value, (v * 100f).roundToInt()) },
            onChanged = { v -> preview?.let { it.highlightFloor = v; it.refresh() } },
        )
        // highlightInnerAtt：0~100 → 0~1（默认 0=现状）。内侧高光向玻璃深处再衰减。
        val switchHighlightEnhance = view.findViewById<Switch>(R.id.switchHighlightEnhance)
        val highlightEnhanceListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_HIGHLIGHT_ENHANCE, checked)
            preview?.let { it.surfacePolish = checked; it.refresh() }
        }
        switchHighlightEnhance.setOnCheckedChangeListener(highlightEnhanceListener)
        refreshBooleanSwitch(
            switchHighlightEnhance, highlightEnhanceListener,
            Prefs.KEY_HIGHLIGHT_ENHANCE, Prefs.DEFAULT_HIGHLIGHT_ENHANCE
        )
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekHighlightInnerAtt), valueText = view.findViewById(R.id.highlightInnerAttValue),
            key = Prefs.KEY_HIGHLIGHT_INNER_ATT, default = Prefs.DEFAULT_HIGHLIGHT_INNER_ATT,
            presetParams = presetParams, presetId = presetId,
            fromProgress = { p -> p / 100f },
            toProgress = { v -> (v * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_highlight_inner_att_value, (v * 100f).roundToInt()) },
            onChanged = { v -> preview?.let { it.highlightInnerAtt = v; it.refresh() } },
        )
        // innerGlow：0~100 → 0~1（默认 0=关）。边缘向中心的全向发光（复用折射环带宽度）。
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekInnerGlow), valueText = view.findViewById(R.id.innerGlowValue),
            key = Prefs.KEY_INNER_GLOW, default = Prefs.DEFAULT_INNER_GLOW,
            fromProgress = { p -> p / 100f },
            toProgress = { v -> (v * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_inner_glow_value, (v * 100f).roundToInt()) },
            onChanged = { v -> preview?.let { it.innerGlow = v; it.refresh() } },
            presetParams = presetParams, presetId = presetId,
        )
        // edgeStroke：0~100 → 0~1（默认 0=关）。贴边向内半透明黑压暗（立体描边）。
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekEdgeStroke), valueText = view.findViewById(R.id.edgeStrokeValue),
            key = Prefs.KEY_EDGE_STROKE, default = Prefs.DEFAULT_EDGE_STROKE,
            fromProgress = { p -> p / 100f },
            toProgress = { v -> (v * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_edge_stroke_value, (v * 100f).roundToInt()) },
            onChanged = { v -> preview?.let { it.edgeStroke = v; it.refresh() } },
            presetParams = presetParams, presetId = presetId,
        )
        // edgeStrokeWidth：0~60 → 0~6.0 px（默认 1.2）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekEdgeStrokeWidth), valueText = view.findViewById(R.id.edgeStrokeWidthValue),
            key = Prefs.KEY_EDGE_STROKE_WIDTH, default = Prefs.DEFAULT_EDGE_STROKE_WIDTH,
            fromProgress = { p -> p / 10f },
            toProgress = { v -> (v * 10f).roundToInt().coerceIn(0, 60) },
            format = { v -> getString(R.string.settings_edge_stroke_width_value, v) },
            onChanged = { v -> preview?.let { it.edgeStrokeWidth = v; it.refresh() } },
            presetParams = presetParams, presetId = presetId,
        )
        // 玻璃内压暗：0~100 → 0.00~1.00（默认 0=不压暗）：喂 shader uMaskAlpha，与遮罩开关/面板遮罩独立
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekMaskGlassDim), valueText = view.findViewById(R.id.maskGlassDimValue),
            key = Prefs.KEY_MASK_GLASS_DIM, default = Prefs.DEFAULT_MASK_GLASS_DIM,
            presetParams = presetParams, presetId = presetId,
            fromProgress = { p -> p / 100f },
            toProgress = { v -> (v * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_mask_glass_dim_value, v) },
            onChanged = { v -> preview?.let { it.maskGlassDim = v; it.refresh() } },
        )
        // lightAngle：0~360°（默认 45）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekLightAngle), valueText = view.findViewById(R.id.lightAngleValue),
            key = Prefs.KEY_LIGHT_ANGLE, default = Prefs.DEFAULT_LIGHT_ANGLE,
            presetParams = presetParams, presetId = presetId,
            fromProgress = { p -> p.toFloat() },
            toProgress = { v -> v.roundToInt().coerceIn(0, 360) },
            format = { v -> getString(R.string.settings_light_angle_value, v.roundToInt()) },
            onChanged = { v -> preview?.let { it.lightAngle = v; it.refresh() } },
        )
        // parallaxX：-100~100 px（默认 0 = 关闭视差）——[spec/89] 不喂预览：视差会把采样区移出预览框
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekParallaxX), valueText = view.findViewById(R.id.parallaxXValue),
            key = Prefs.KEY_PARALLAX_X, default = Prefs.DEFAULT_PARALLAX_X,
            fromProgress = { p -> p - 100f },
            toProgress = { v -> (v + 100f).roundToInt().coerceIn(0, 200) },
            format = { v -> getString(R.string.settings_parallax_x_value, v.roundToInt()) },
            presetParams = presetParams, presetId = presetId,
        )
        // parallaxY：-100~100 px（默认 0 = 关闭视差）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekParallaxY), valueText = view.findViewById(R.id.parallaxYValue),
            key = Prefs.KEY_PARALLAX_Y, default = Prefs.DEFAULT_PARALLAX_Y,
            fromProgress = { p -> p - 100f },
            toProgress = { v -> (v + 100f).roundToInt().coerceIn(0, 200) },
            format = { v -> getString(R.string.settings_parallax_y_value, v.roundToInt()) },
            presetParams = presetParams, presetId = presetId,
        )
    }

    // ------------------------------------------------------------ 玻璃效果预设 ------------------------------------------------------------

    /** 会话内预设缓存：读改写都留在设置页进程内，避开远程存储读写往返取到旧值。 */
    private var glassPresetsCache: List<GlassRegistry.Preset>? = null

    /** 读预设列表（框架侧优先，回退本地 XML）。 */
    private fun readGlassPresets(): List<GlassRegistry.Preset> {
        glassPresetsCache?.let { return it }
        val src = frameworkPrefs() ?: xmlPrefs
        val parsed = GlassRegistry.parsePresets(src.getString(Prefs.KEY_GLASS_PRESETS, null))
        glassPresetsCache = parsed
        return parsed
    }

    /** 读探测到的玻璃类清单（类勾选对话框的数据源）。 */
    private fun detectedGlassClasses(): List<String> =
        GlassRegistry.parseClasses(frameworkPrefs()?.getString(Prefs.KEY_GLASS_CLASSES, null)).map { it.cls }

    /** 类清单行文案：标签（简单类名）— 位置描述；该类没登记位置时只显示标签行。 */
    private fun classDisplayText(cls: String): String {
        val base = GlassRegistry.classLabel(cls)
        val key = GlassRegistry.classLocationKey(cls) ?: return base
        val resId = resources.getIdentifier(key, "string", packageName)
        if (resId == 0) return base
        val loc = getString(resId)
        return if (loc.isEmpty()) base else "$base — $loc"
    }

    /** 改一次预设列表后写回（本地权威 + 框架在线同步）。 */
    private fun editGlassPresets(block: (MutableList<GlassRegistry.Preset>) -> Unit): Boolean {
        val list = readGlassPresets().toMutableList()
        block(list)
        glassPresetsCache = list
        Lg.i(TAG, "write glass_presets count=${list.size}")
        putConfig(Prefs.KEY_GLASS_PRESETS) {
            it.putString(Prefs.KEY_GLASS_PRESETS, GlassRegistry.encodePresets(list)).apply()
        }
        return true
    }

    /** 顶栏选中态：选中 id 已不存在时回落默认并写回本地；null = 编辑全局参数。 */
    private fun normalizeSelectedPresetId(presets: List<GlassRegistry.Preset>): String? {
        val raw = xmlPrefs.getString(Prefs.KEY_UI_GLASS_PRESET, Prefs.GLASS_PRESET_DEFAULT_ID)
            ?: Prefs.GLASS_PRESET_DEFAULT_ID
        if (raw == Prefs.GLASS_PRESET_DEFAULT_ID) return null
        if (presets.any { it.id == raw }) return raw
        xmlPrefs.edit().putString(Prefs.KEY_UI_GLASS_PRESET, Prefs.GLASS_PRESET_DEFAULT_ID).apply()
        return null
    }

    private fun selectGlassPreset(id: String) {
        xmlPrefs.edit().putString(Prefs.KEY_UI_GLASS_PRESET, id).apply()
    }

    /** 新预设序号：取历史序号与现存 id 数字后缀的较大者再加一，保证 id 不与旧预设复用。 */
    private fun nextGlassPresetSeq(presets: List<GlassRegistry.Preset>): Int? {
        val src = frameworkPrefs() ?: xmlPrefs
        var max = Prefs.readIntCompat(src, Prefs.KEY_GLASS_PRESET_SEQ, 0)
        for (p in presets) {
            val suffix = p.id.removePrefix("p").toIntOrNull() ?: continue
            if (suffix > max) max = suffix
        }
        val next = max + 1
        putConfig(Prefs.KEY_GLASS_PRESET_SEQ) { it.putInt(Prefs.KEY_GLASS_PRESET_SEQ, next).apply() }
        return next
    }

    /** 新建预设（名字与 id 共用同一序号），建好即选中它。 */
    private fun createGlassPreset() {
        var created: GlassRegistry.Preset? = null
        val ok = editGlassPresets { list ->
            val seq = nextGlassPresetSeq(list) ?: return@editGlassPresets
            val preset = GlassRegistry.Preset(
                id = "p$seq",
                name = getString(R.string.glass_preset_name_fmt, seq),
                classes = emptyList(),
                params = emptyMap(),
            )
            list.add(preset)
            created = preset
        }
        if (!ok) return
        val preset = created ?: return
        selectGlassPreset(preset.id)
        Toast.makeText(this, getString(R.string.glass_preset_created, preset.name), Toast.LENGTH_SHORT).show()
        bindCurrentPage()
    }

    /** 删除预设；被它占用的类自动回到默认效果，选中态若指向它则回落默认。 */
    private fun deleteGlassPreset(id: String) {
        var removed: String? = null
        val ok = editGlassPresets { list ->
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                removed = list[idx].name
                list.removeAt(idx)
            }
        }
        if (!ok) return
        if (xmlPrefs.getString(Prefs.KEY_UI_GLASS_PRESET, Prefs.GLASS_PRESET_DEFAULT_ID) == id) {
            selectGlassPreset(Prefs.GLASS_PRESET_DEFAULT_ID)
        }
        removed?.let {
            Toast.makeText(this, getString(R.string.glass_preset_deleted, it), Toast.LENGTH_SHORT).show()
        }
        bindCurrentPage()
    }

    /** 勾选 / 取消某个玻璃类归属某预设。 */
    private fun toggleGlassPresetClass(presetId: String, cls: String, checked: Boolean) {
        editGlassPresets { list ->
            val i = list.indexOfFirst { it.id == presetId }
            if (i >= 0) {
                val preset = list[i]
                val classes = preset.classes.toMutableList()
                if (checked) {
                    if (!classes.contains(cls)) classes.add(cls)
                } else {
                    classes.remove(cls)
                }
                list[i] = preset.copy(classes = classes)
            }
        }
    }

    /** 写一个预设参数键（滑杆拖动结束时调用）。 */
    private fun writeGlassPresetParam(presetId: String, key: String, value: Float) {
        editGlassPresets { list ->
            val i = list.indexOfFirst { it.id == presetId }
            if (i >= 0) {
                val preset = list[i]
                val params = preset.params.toMutableMap()
                params[key] = value
                list[i] = preset.copy(params = params)
            }
        }
    }

    /** 材质分类顶栏：预设横滑条 + 「配置」按钮。 */
    private fun bindGlassPresetBar(view: View, presets: List<GlassRegistry.Preset>, selectedId: String?) {
        val bar = view.findViewById<LinearLayout>(R.id.glassPresetBar)
        bar.removeAllViews()
        fun chip(text: String, checkable: Boolean, checked: Boolean): Chip = Chip(view.context).apply {
            this.text = text
            isCheckable = checkable
            isChecked = checkable && checked
            isCloseIconVisible = false
        }
        val defaultChip = chip(getString(R.string.glass_preset_default), true, selectedId == null)
        defaultChip.setOnClickListener {
            selectGlassPreset(Prefs.GLASS_PRESET_DEFAULT_ID)
            bindCurrentPage()
        }
        bar.addView(defaultChip)
        presets.forEach { preset ->
            val presetChip = chip(preset.name, true, preset.id == selectedId)
            presetChip.setOnClickListener {
                selectGlassPreset(preset.id)
                bindCurrentPage()
            }
            presetChip.setOnLongClickListener {
                showPresetClassDialog(preset.id) { bindCurrentPage() }
                true
            }
            bar.addView(presetChip)
        }
        val addChip = chip(getString(R.string.glass_preset_add), false, false)
        addChip.setOnClickListener { createGlassPreset() }
        bar.addView(addChip)
        view.findViewById<View>(R.id.glassPresetConfigBtn).setOnClickListener { showPresetManageDialog() }
    }

    /** 对话框列表行：带水波纹背景，给了点击回调才可点。 */
    private fun glassPresetRow(text: String, onClick: (() -> Unit)?): TextView {
        val row = TextView(this)
        row.text = text
        row.textSize = 15f
        val pad = (12 * resources.displayMetrics.density).toInt()
        row.setPadding(0, pad, 0, pad)
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val bg = ta.getDrawable(0)
        ta.recycle()
        if (bg != null) row.background = bg
        if (onClick != null) row.setOnClickListener { onClick() }
        return row
    }

    /** 预设管理页：逐条列出「预设名: 已应用类」，底部可新增预设。 */
    /** 重命名玻璃预设（配置管理行与预设管理弹窗长按入口）。 */
    private fun showPresetRenameDialog(presetId: String, after: (() -> Unit)? = null) {
        val preset = readGlassPresets().firstOrNull { it.id == presetId } ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(preset.name)
            setSelection(preset.name.length)
            setSingleLine()
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.glass_preset_rename_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.glass_preset_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                editGlassPresets { list ->
                    val i = list.indexOfFirst { it.id == presetId }
                    if (i >= 0) list[i] = list[i].copy(name = name)
                }
                Toast.makeText(this, R.string.glass_preset_renamed, Toast.LENGTH_SHORT).show()
                Lg.i(TAG_NAV, "preset rename id=$presetId name=$name")
                bindCurrentPage()
            }
            .create()
        dialog.setOnDismissListener { after?.invoke() }
        dialog.show()
        DialogGlass.arm(dialog)
    }

    private fun showPresetManageDialog() {
        val root = layoutInflater.inflate(R.layout.view_dialog_preset_list, contentFrame, false)
        val container = root.findViewById<LinearLayout>(R.id.presetListContainer)
        fun rebuild() {
            container.removeAllViews()
            val presets = readGlassPresets()
            if (presets.isEmpty()) {
                container.addView(glassPresetRow(getString(R.string.glass_preset_empty), null))
            }
            presets.forEach { preset ->
                val detail = if (preset.classes.isEmpty()) {
                    getString(R.string.glass_preset_none_applied)
                } else {
                    preset.classes.joinToString(", ") { classDisplayText(it) }
                }
                val rowView = glassPresetRow("${preset.name}: $detail") {
                    showPresetClassDialog(preset.id) { rebuild() }
                }
                rowView.setOnLongClickListener {
                    showPresetRenameDialog(preset.id) { rebuild() }
                    true
                }
                container.addView(rowView)
            }
            container.addView(glassPresetRow(getString(R.string.glass_preset_add_row)) {
                createGlassPreset()
                rebuild()
            })
        }
        rebuild()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.glass_preset_manage_title)
            .setView(root)
            .setPositiveButton(R.string.glass_preset_close, null)
            .create()
        dialog.setOnDismissListener { bindCurrentPage() }
        dialog.show()
        DialogGlass.arm(dialog)
    }

    /** 预设的玻璃类多选对话框：已被其他预设占用的类置灰不可勾，另附删除预设按钮。 */
    private fun showPresetClassDialog(presetId: String, onClosed: () -> Unit) {
        val presets = readGlassPresets()
        val target = presets.firstOrNull { it.id == presetId } ?: return
        val occupied = mutableSetOf<String>()
        presets.forEach { p -> if (p.id != presetId) occupied.addAll(p.classes) }
        val classes = detectedGlassClasses().toMutableList()
        target.classes.forEach { if (!classes.contains(it)) classes.add(it) }

        val root = layoutInflater.inflate(R.layout.view_dialog_preset_classes, contentFrame, false)
        val container = root.findViewById<LinearLayout>(R.id.presetClassContainer)
        val scroll = root.findViewById<ScrollView>(R.id.presetClassScroll)
        if (classes.isEmpty()) {
            container.addView(glassPresetRow(getString(R.string.glass_preset_class_empty), null))
        }
        val pad = (8 * resources.displayMetrics.density).toInt()
        if (target.params.isEmpty() && target.classes.isNotEmpty()) {
            val hint = TextView(this)
            hint.text = getString(R.string.glass_preset_empty_params_hint)
            hint.textSize = 12f
            hint.alpha = 0.7f
            hint.setPadding(pad, pad, pad, pad)
            container.addView(hint, 0)
        }
        classes.forEach { cls ->
            val box = CheckBox(this)
            box.text = classDisplayText(cls)
            box.textSize = 13f
            box.setPadding(0, pad, 0, pad)
            if (occupied.contains(cls)) {
                box.isChecked = false
                box.isEnabled = false
                box.alpha = 0.4f
            } else {
                box.isChecked = target.classes.contains(cls)
                box.setOnCheckedChangeListener { _, checked -> toggleGlassPresetClass(presetId, cls, checked) }
            }
            container.addView(box)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(target.name)
            .setView(root)
            .setPositiveButton(R.string.glass_preset_close, null)
            .setNeutralButton(R.string.glass_preset_delete) { _, _ -> deleteGlassPreset(presetId) }
            .create()
        dialog.setOnDismissListener { onClosed() }
        dialog.show()
        DialogGlass.arm(dialog)
        scroll.post {
            val max = (320 * resources.displayMetrics.density).toInt()
            if (scroll.height > max) {
                val lp = scroll.layoutParams
                lp.height = max
                scroll.layoutParams = lp
            }
        }
    }

    // ------------------------------------------------------------ 圆角独立（按类覆盖） ------------------------------------------------------------

    /** 圆角覆盖滑杆 UI 上限（dp）；渲染侧另按元素短边一半封顶。 */
    private val cornerOverrideMaxDp = 200

    /** 读框架侧圆角覆盖表；框架未就绪 → 空表。 */
    private fun readCornerOverrides(): Map<String, Float> =
        GlassRegistry.parseCornerOverrides(
            frameworkPrefs()?.getString(Prefs.KEY_CORNER_OVERRIDES, null)
                ?: xmlPrefs.getString(Prefs.KEY_CORNER_OVERRIDES, null),
        )

    /** 写一个类的圆角覆盖（px）；0 = 移除覆盖（恢复跟随实测）。 */
    private fun writeCornerOverride(cls: String, px: Float) {
        val map = readCornerOverrides().toMutableMap()
        if (px > 0f) map[cls] = px else map.remove(cls)
        putConfig(Prefs.KEY_CORNER_OVERRIDES) {
            it.putString(Prefs.KEY_CORNER_OVERRIDES, GlassRegistry.encodeCornerOverrides(map)).apply()
        }
        Lg.i(TAG, "write glass_corner_overrides count=${map.size}")
    }

    /** 读一个类的圆角开关状态（关闭名单反向：不在名单 = 开，默认开）。 */
    private fun readCornerSwitchOn(key: String, cls: String): Boolean =
        cls !in GlassRegistry.parseClassList(
            frameworkPrefs()?.getString(key, null) ?: xmlPrefs.getString(key, null),
        )

    /** 写一个类的圆角开关（关 = 进关闭名单；开 = 移出）。 */
    private fun writeCornerSwitch(key: String, cls: String, enabled: Boolean) {
        val current = frameworkPrefs()?.getString(key, null) ?: xmlPrefs.getString(key, null)
        val off = GlassRegistry.parseClassList(current).toMutableSet()
        if (enabled) off.remove(cls) else off.add(cls)
        putConfig(key) { it.putString(key, GlassRegistry.encodeClassList(off)).apply() }
        Lg.i(TAG, "write corner switch $key cls=$cls enabled=$enabled off=${off.size}")
    }

    /** 圆角独立页：探测到的类逐块展示（值行点开滑杆编辑 + 自适应/外部同步两开关）。 */
    private fun bindCornerRadius(view: View) {
        val list = view.findViewById<LinearLayout>(R.id.cornerClassList)
        list.removeAllViews()
        val overrides = readCornerOverrides()
        val classes = (detectedGlassClasses() + overrides.keys).distinct().sorted()
        if (classes.isEmpty()) {
            list.addView(glassPresetRow(getString(R.string.glass_preset_class_empty), null))
            return
        }
        val density = resources.displayMetrics.density
        classes.forEach { cls ->
            val px = overrides[cls]?.takeIf { it > 0f }
            val valueText = if (px == null) {
                getString(R.string.corner_follow_measured)
            } else {
                getString(R.string.corner_custom_value, (px / density).roundToInt())
            }
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            block.addView(glassPresetRow("${classDisplayText(cls)}\n$valueText") {
                showCornerRadiusDialog(cls) { bindCurrentPage() }
            })
            block.addView(buildCornerSwitchRow(
                cls, Prefs.KEY_CORNER_ADAPTIVE_OFF, getString(R.string.corner_adaptive_switch),
                getString(R.string.corner_adaptive_desc),
            ))
            block.addView(buildCornerSwitchRow(
                cls, Prefs.KEY_CORNER_SYNC_EXTERNAL_OFF, getString(R.string.corner_sync_external_switch),
                getString(R.string.corner_sync_external_desc),
            ))
            list.addView(block)
        }
    }

    /** 一行圆角开关（Switch + 说明），绑定 [writeCornerSwitch]。 */
    private fun buildCornerSwitchRow(cls: String, key: String, title: String, desc: String): View {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, pad / 2)
        }
        val sw = Switch(this).apply {
            text = title
            isChecked = readCornerSwitchOn(key, cls)
            setPadding(pad / 2, 0, pad / 2, 0)
        }
        sw.setOnCheckedChangeListener { _, checked -> writeCornerSwitch(key, cls, checked) }
        column.addView(sw)
        val descTa = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        val descColor = descTa.getColor(0, 0xFFFFFFFF.toInt())
        descTa.recycle()
        column.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(descColor)
            setPadding(pad, 0, pad, pad / 2)
        })
        return column
    }

    /** 单类圆角编辑对话框：滑杆 0（跟随实测）~ 200dp，拖动结束即写；另附「恢复跟随实测」按钮。 */
    private fun showCornerRadiusDialog(cls: String, onClosed: () -> Unit) {
        val density = resources.displayMetrics.density
        val currentPx = readCornerOverrides()[cls]?.takeIf { it > 0f } ?: 0f
        val pad = (20 * density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val valueText = TextView(this).apply { textSize = 16f }
        fun dpText(dp: Int): String =
            if (dp <= 0) getString(R.string.corner_follow_measured)
            else getString(R.string.corner_custom_value, dp)
        var currentDp = (currentPx / density).roundToInt().coerceIn(0, cornerOverrideMaxDp)
        valueText.text = dpText(currentDp)
        val seek = SeekBar(this).apply {
            max = cornerOverrideMaxDp
            progress = currentDp
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                currentDp = progress
                valueText.text = dpText(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                writeCornerOverride(cls, currentDp * density)
            }
        })
        column.addView(valueText)
        column.addView(seek)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(GlassRegistry.classLabel(cls))
            .setView(column)
            .setPositiveButton(R.string.glass_preset_close, null)
            .setNeutralButton(R.string.corner_reset) { _, _ -> writeCornerOverride(cls, 0f) }
            .create()
        dialog.setOnDismissListener { onClosed() }
        dialog.show()
        DialogGlass.arm(dialog)
    }

    /** 背景与抓屏：模式 / 即时抓屏速率 / 最大抓屏间隔 / 清晰度 / 失败重试 / 区域级抓屏。 */
    private fun bindBackground(view: View) {
        bindSampling(view)
        bindCcBlur(view)
    }

    /** 抓屏与采样：模式 / 速率 / 间隔 / 缩放 / 重试 / 区域级 / 壁纸解码边长。 */
    private fun bindSampling(view: View) {
        // 模式：0=即时抓屏 1=变化抓屏 2=静态（默认 1；仅变化抓屏显示最大抓屏间隔）
        val modeSpinner = view.findViewById<Spinner>(R.id.spinnerCaptureMode)
        modeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, listOf(
                getString(R.string.settings_capture_mode_immediate),
                getString(R.string.settings_capture_mode_on_change),
                getString(R.string.settings_capture_mode_static),
            )
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val maxIntervalGroup = listOf<View>(
            view.findViewById(R.id.periodicCaptureIntervalLabel),
            view.findViewById(R.id.seekPeriodicCaptureInterval),
            view.findViewById(R.id.periodicCaptureIntervalValue),
            view.findViewById(R.id.periodicCaptureIntervalDesc),
        )
        fun applyModeUi(mode: Int) {
            val visibility = if (mode == Prefs.BG_CAPTURE_MODE_ON_CHANGE) View.VISIBLE else View.GONE
            maxIntervalGroup.forEach { it.visibility = visibility }
        }
        val currentMode = (frameworkPrefs()?.let { Prefs.readIntCompat(it, Prefs.KEY_BG_CAPTURE_MODE, Prefs.DEFAULT_BG_CAPTURE_MODE) }
            ?: Prefs.readIntCompat(xmlPrefs, Prefs.KEY_BG_CAPTURE_MODE, Prefs.DEFAULT_BG_CAPTURE_MODE))
            .coerceIn(Prefs.BG_CAPTURE_MODE_IMMEDIATE, Prefs.BG_CAPTURE_MODE_STATIC)
        applyModeUi(currentMode)
        modeSpinner.setSelection(currentMode)
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                selView: View?,
                position: Int,
                id: Long,
            ) {
                val mode = position.coerceIn(Prefs.BG_CAPTURE_MODE_IMMEDIATE, Prefs.BG_CAPTURE_MODE_STATIC)
                writePrefInt(Prefs.KEY_BG_CAPTURE_MODE, mode)
                applyModeUi(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        // 即时抓屏速率：30~120Hz（默认 60），瞬态场景在任何模式下都按它抓
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekPanelCaptureHz), valueText = view.findViewById(R.id.panelCaptureHzValue),
            key = Prefs.KEY_PANEL_CAPTURE_HZ, default = Prefs.DEFAULT_PANEL_CAPTURE_HZ,
            max = Prefs.PANEL_CAPTURE_HZ_MAX - Prefs.PANEL_CAPTURE_HZ_MIN, step = 1, offset = Prefs.PANEL_CAPTURE_HZ_MIN,
            format = { v -> getString(R.string.settings_panel_capture_hz_value, v) },
        )

        // 最大抓屏间隔（仅变化抓屏显示）：100~5000ms（默认 500）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekPeriodicCaptureInterval), valueText = view.findViewById(R.id.periodicCaptureIntervalValue),
            key = Prefs.KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS, default = Prefs.DEFAULT_BG_PERIODIC_CAPTURE_INTERVAL_MS,
            max = 4900, step = 1, offset = Prefs.BG_PERIODIC_CAPTURE_INTERVAL_MIN_MS,
            format = { ms -> getString(R.string.settings_periodic_capture_interval_value, ms) },
        )

        // 桌面抓屏最小间隔（亮屏桌面 / 锁屏息屏各自一条，0 = 不节流）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekLauncherCaptureInterval),
            valueText = view.findViewById(R.id.launcherCaptureIntervalValue),
            key = Prefs.KEY_LAUNCHER_BG_CAPTURE_MIN_INTERVAL_MS,
            default = Prefs.DEFAULT_LAUNCHER_BG_CAPTURE_MIN_INTERVAL_MS,
            max = 50, step = 20, offset = 0,
            format = { ms -> getString(R.string.settings_launcher_capture_interval_value, ms) },
        )
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekLauncherCaptureIntervalLocked),
            valueText = view.findViewById(R.id.launcherCaptureIntervalLockedValue),
            key = Prefs.KEY_LAUNCHER_BG_CAPTURE_MIN_INTERVAL_LOCKED_MS,
            default = Prefs.DEFAULT_LAUNCHER_BG_CAPTURE_MIN_INTERVAL_LOCKED_MS,
            max = 50, step = 20, offset = 0,
            format = { ms -> getString(R.string.settings_launcher_capture_interval_locked_value, ms) },
        )

        // 降采样档位 spinner（0.25/0.5/0.75/1.0，默认 0.5）
        bindScaleSpinner(view)

        // 失败重试总开关（默认开）
        val switchRetry = view.findViewById<Switch>(R.id.switchCaptureRetryEnabled)
        val retryListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_BG_CAPTURE_RETRY_ENABLED, checked)
        }
        switchRetry.setOnCheckedChangeListener(retryListener)
        refreshBooleanSwitch(
            switchRetry, retryListener,
            Prefs.KEY_BG_CAPTURE_RETRY_ENABLED, Prefs.DEFAULT_BG_CAPTURE_RETRY_ENABLED
        )

        // 区域级抓屏实验开关（默认关）
        val switchRegion = view.findViewById<Switch>(R.id.switchRegionCaptureExperimental)
        val regionListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_BG_REGION_CAPTURE_EXPERIMENTAL, checked)
        }
        switchRegion.setOnCheckedChangeListener(regionListener)
        refreshBooleanSwitch(
            switchRegion, regionListener,
            Prefs.KEY_BG_REGION_CAPTURE_EXPERIMENTAL, Prefs.DEFAULT_BG_REGION_CAPTURE_EXPERIMENTAL
        )
        // 壁纸解码最大边长（512~8192，步进 8）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekWallpaperMaxDim), valueText = view.findViewById(R.id.wallpaperMaxDimValue),
            key = Prefs.KEY_BG_WALLPAPER_MAX_DIM, default = Prefs.DEFAULT_BG_WALLPAPER_MAX_DIM,
            max = 960, step = 8, offset = 512,
            format = { v -> getString(R.string.settings_bg_wallpaper_max_dim_value, v) },
        )

    }

    /** 控制中心模糊：力度 / 保留系统模糊 / 移除面板灰底。 */
    private fun bindCcBlur(view: View) {
        // [spec/61] 系统控制中心模糊力度（0~200%，默认 100=系统原值；开启时阻止界面缩小）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekCcBlurStrength), valueText = view.findViewById(R.id.ccBlurStrengthValue),
            key = Prefs.KEY_CC_BLUR_STRENGTH, default = Prefs.DEFAULT_CC_BLUR_STRENGTH,
            max = 200, step = 1, offset = 0,
            format = { v -> getString(R.string.settings_cc_blur_strength_value, v) },
        )
        // 保留系统控制中心模糊（默认开）
        val switchKeepBlur = view.findViewById<Switch>(R.id.switchKeepSystemCcBlur)
        val keepBlurListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_KEEP_SYSTEM_CC_BLUR, checked)
        }
        switchKeepBlur.setOnCheckedChangeListener(keepBlurListener)
        refreshBooleanSwitch(
            switchKeepBlur, keepBlurListener,
            Prefs.KEY_KEEP_SYSTEM_CC_BLUR, Prefs.DEFAULT_KEEP_SYSTEM_CC_BLUR
        )

        // [2026-09-16 面板材质底色] 移除系统面板背景那层 MixColor 灰底（默认开；模糊保留）
        val switchRemoveMixColor = view.findViewById<Switch>(R.id.switchRemovePanelMixColor)
        val removeMixColorListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_REMOVE_PANEL_MIX_COLOR, checked)
        }
        switchRemoveMixColor.setOnCheckedChangeListener(removeMixColorListener)
        refreshBooleanSwitch(
            switchRemoveMixColor, removeMixColorListener,
            Prefs.KEY_REMOVE_PANEL_MIX_COLOR, Prefs.DEFAULT_REMOVE_PANEL_MIX_COLOR
        )
    }

    /** 文字与可读性：文字反色 / 弹出通知文字跟随。 */
    private fun bindText(view: View) {
        // 文字背景反转取色开关（doc/spec/21；改动需重启 SystemUI 生效）
        val switchTextContrast = view.findViewById<Switch>(R.id.switchTextContrast)
        val textContrastListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_TEXT_CONTRAST_ENABLE, checked)
        }
        switchTextContrast.setOnCheckedChangeListener(textContrastListener)
        refreshBooleanSwitch(
            switchTextContrast, textContrastListener,
            Prefs.KEY_TEXT_CONTRAST_ENABLE, Prefs.DEFAULT_TEXT_CONTRAST_ENABLE
        )

        // [spec/47] 弹出通知文字跟随整体颜色（与遮罩无关，默认开）
        val switchHeadsUp = view.findViewById<Switch>(R.id.switchHeadsUpTextFollow)
        val headsUpListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_HEADS_UP_TEXT_FOLLOW, checked)
        }
        switchHeadsUp.setOnCheckedChangeListener(headsUpListener)
        refreshBooleanSwitch(
            switchHeadsUp, headsUpListener,
            Prefs.KEY_HEADS_UP_TEXT_FOLLOW, Prefs.DEFAULT_HEADS_UP_TEXT_FOLLOW
        )

        // [spec/60] 文字周期强制刷新间隔（0~5000ms，默认 500；0=关闭周期刷新，仅事件触发；
        // tick 每次读最新值，改动即时生效）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekTextForceRefresh), valueText = view.findViewById(R.id.textForceRefreshValue),
            key = Prefs.KEY_TEXT_FORCE_REFRESH_INTERVAL_MS, default = Prefs.DEFAULT_TEXT_FORCE_REFRESH_INTERVAL_MS,
            max = 5000, step = 1, offset = 0,
            format = { v -> getString(R.string.settings_text_force_refresh_interval_value, v) },
        )
    }

    /** 遮罩：黑遮罩开关 / 不透明度 / 文字颜色。 */
    private fun bindMask(view: View) {
        // [spec/35+40+41] 黑遮罩开关（默认开；改动需重启 SystemUI 生效）
        val switchMask = view.findViewById<Switch>(R.id.switchMask)
        val maskListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_MASK_ENABLE, checked)
        }
        switchMask.setOnCheckedChangeListener(maskListener)
        refreshBooleanSwitch(switchMask, maskListener, Prefs.KEY_MASK_ENABLE, Prefs.DEFAULT_MASK_ENABLE)

        // [spec/63 文字强制白独立开关 2026-08-26]（默认开；与遮罩独立，可只要白字不要压暗 / 单独关解决闪动；改动需重启 SystemUI 生效）
        val switchTextForceWhite = view.findViewById<Switch>(R.id.switchTextForceWhite)
        val textForceWhiteListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_TEXT_FORCE_WHITE_ENABLE, checked)
        }
        switchTextForceWhite.setOnCheckedChangeListener(textForceWhiteListener)
        refreshBooleanSwitch(switchTextForceWhite, textForceWhiteListener, Prefs.KEY_TEXT_FORCE_WHITE_ENABLE, Prefs.DEFAULT_TEXT_FORCE_WHITE_ENABLE)

        // 遮罩不透明度滑杆（0~100 → 0.00~1.00，默认 0.45）：只管面板底层 scrim 黑 tint
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekMaskAlpha), valueText = view.findViewById(R.id.maskAlphaValue),
            key = Prefs.KEY_MASK_ALPHA, default = Prefs.DEFAULT_MASK_ALPHA,
            fromProgress = { p -> p / 100f },
            toProgress = { v -> (v * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_mask_alpha_value, v) },
        )

        // 遮罩下文字颜色 spinner（纯白/浅灰/灰 → ARGB int）
        bindMaskTextColorSpinner(view)
    }

    /** 流体云：展开卡片玻璃化 + 判定参数（spec/44）。 */
    private fun bindSeedling(view: View) {
        // 流体云展开卡片玻璃化开关（默认关；改动需重启 SystemUI 生效）
        val switchSeedling = view.findViewById<Switch>(R.id.switchSeedlingCardGlass)
        val seedlingListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_SEEDLING_CARD_GLASS_ENABLE, checked)
        }
        switchSeedling.setOnCheckedChangeListener(seedlingListener)
        refreshBooleanSwitch(
            switchSeedling, seedlingListener,
            Prefs.KEY_SEEDLING_CARD_GLASS_ENABLE, Prefs.DEFAULT_SEEDLING_CARD_GLASS_ENABLE
        )

        // [2026-09-16 用户需求变更] 小胶囊（小岛）也玻璃化（默认开）
        val switchCapsule = view.findViewById<Switch>(R.id.switchSeedlingCapsuleGlass)
        val capsuleListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_SEEDLING_CAPSULE_GLASS, checked)
        }
        switchCapsule.setOnCheckedChangeListener(capsuleListener)
        refreshBooleanSwitch(
            switchCapsule, capsuleListener,
            Prefs.KEY_SEEDLING_CAPSULE_GLASS, Prefs.DEFAULT_SEEDLING_CAPSULE_GLASS
        )

        // 展开大卡片判定高度系数（0.5~3.0，默认 1.2）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekSeedlingExpandRatio), valueText = view.findViewById(R.id.seedlingExpandRatioValue),
            key = Prefs.KEY_SEEDLING_CARD_EXPAND_HEIGHT_RATIO, default = Prefs.DEFAULT_SEEDLING_CARD_EXPAND_HEIGHT_RATIO,
            fromProgress = { p -> 0.5f + p / 100f },
            toProgress = { v -> ((v - 0.5f) * 100f).roundToInt().coerceIn(0, 250) },
            format = { v -> getString(R.string.settings_seedling_expand_ratio_value, v) },
        )
        // 展开大卡片圆角启发式系数（0.05~0.50，默认 0.22）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekSeedlingCornerRatio), valueText = view.findViewById(R.id.seedlingCornerRatioValue),
            key = Prefs.KEY_SEEDLING_CARD_CORNER_RATIO, default = Prefs.DEFAULT_SEEDLING_CARD_CORNER_RATIO,
            fromProgress = { p -> 0.05f + p / 1000f },
            toProgress = { v -> ((v - 0.05f) * 1000f).roundToInt().coerceIn(0, 450) },
            format = { v -> getString(R.string.settings_seedling_corner_ratio_value, v) },
        )
        // 透明宿主卡片 background（默认开：用户硬需求，透明让玻璃透出；关闭恢复系统半透明背景）
        val switchForceT = view.findViewById<Switch>(R.id.switchSeedlingForceTransparentBg)
        val forceListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_SEEDLING_CARD_FORCE_TRANSPARENT_BG, checked)
        }
        switchForceT.setOnCheckedChangeListener(forceListener)
        refreshBooleanSwitch(
            switchForceT, forceListener,
            Prefs.KEY_SEEDLING_CARD_FORCE_TRANSPARENT_BG, Prefs.DEFAULT_SEEDLING_CARD_FORCE_TRANSPARENT_BG
        )
    }

    // [spec/81 2026-09-17] bindLockscreen 已删除：锁屏时钟 hook 真机未命中数字 View，
    // 功能整体停用，「锁屏」分类页及其入口一并移除。

    /** [doc/spec/66] 桌面大类：Dock 栏液态玻璃化（默认关；Launcher 侧 TTL 500ms 重读，改动无需重启桌面）。 */
    private fun bindDesktop(view: View) {
        val switchDock = view.findViewById<Switch>(R.id.switchDockGlass)
        val dockListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_DOCK_GLASS_ENABLE, checked)
        }
        switchDock.setOnCheckedChangeListener(dockListener)
        refreshBooleanSwitch(
            switchDock, dockListener,
            Prefs.KEY_DOCK_GLASS_ENABLE, Prefs.DEFAULT_DOCK_GLASS_ENABLE
        )
        bindGlassSwitch(view, R.id.switchPageIndicatorGlass, Prefs.KEY_PAGE_INDICATOR_GLASS, Prefs.DEFAULT_PAGE_INDICATOR_GLASS)
        bindGlassSwitch(view, R.id.switchCardGlass, Prefs.KEY_CARD_GLASS_ENABLE, Prefs.DEFAULT_CARD_GLASS_ENABLE)
    }

    /** 其他组件：音量条 + 相册 / 计算器 / 智能侧边栏 / 速览的组件级玻璃开关 + 息屏三开关。 */
    private fun bindComponents(view: View) {
        bindGlassSwitch(view, R.id.switchVolumeSeekBarGlass, Prefs.KEY_VOLUME_SEEKBAR_GLASS, Prefs.DEFAULT_VOLUME_SEEKBAR_GLASS)
        bindGlassSwitch(view, R.id.switchVolumePanelBackdrop, Prefs.KEY_VOLUME_PANEL_BACKDROP, Prefs.DEFAULT_VOLUME_PANEL_BACKDROP)
        bindGlassSwitch(view, R.id.switchNotificationPopupGlass, Prefs.KEY_NOTIFICATION_POPUP_GLASS, Prefs.DEFAULT_NOTIFICATION_POPUP_GLASS)
        bindGlassSwitch(view, R.id.switchGalleryGlass, Prefs.KEY_GALLERY_GLASS, Prefs.DEFAULT_GALLERY_GLASS)
        bindGlassSwitch(view, R.id.switchCalculatorGlass, Prefs.KEY_CALCULATOR_GLASS, Prefs.DEFAULT_CALCULATOR_GLASS)
        bindGlassSwitch(view, R.id.switchSmartSidebarGlass, Prefs.KEY_SMART_SIDEBAR_GLASS, Prefs.DEFAULT_SMART_SIDEBAR_GLASS)
        bindGlassSwitch(view, R.id.switchAssistantScreenGlass, Prefs.KEY_ASSISTANT_SCREEN_GLASS, Prefs.DEFAULT_ASSISTANT_SCREEN_GLASS)
        bindGlassSwitch(view, R.id.switchQuickSearchGlass, Prefs.KEY_QUICK_SEARCH_GLASS, Prefs.DEFAULT_QUICK_SEARCH_GLASS)
        bindGlassSwitch(view, R.id.switchGalleryShaderProbe, Prefs.KEY_GALLERY_SHADER_PROBE, Prefs.DEFAULT_GALLERY_SHADER_PROBE)
        bindGlassSwitch(view, R.id.switchClearAllBtnGlass, Prefs.KEY_CLEAR_ALL_BTN_GLASS, Prefs.DEFAULT_CLEAR_ALL_BTN_GLASS)
        bindGlassSwitch(view, R.id.switchEditToolbarGlass, Prefs.KEY_EDIT_TOOLBAR_GLASS, Prefs.DEFAULT_EDIT_TOOLBAR_GLASS)
        bindGlassSwitch(view, R.id.switchEditToolBtnGlass, Prefs.KEY_EDIT_TOOLBTN_GLASS, Prefs.DEFAULT_EDIT_TOOLBTN_GLASS)
        bindGlassSwitch(view, R.id.switchPopupMenuGlass, Prefs.KEY_POPUP_MENU_GLASS_ENABLE, Prefs.DEFAULT_POPUP_MENU_GLASS_ENABLE)
        bindGlassSwitch(view, R.id.switchAliveAodHandoff, Prefs.KEY_ALIVE_AOD_HANDOFF, Prefs.DEFAULT_ALIVE_AOD_HANDOFF)
        bindGlassSwitch(view, R.id.switchAliveAodStyle, Prefs.KEY_ALIVE_AOD_STYLE, Prefs.DEFAULT_ALIVE_AOD_STYLE)
        bindGlassSwitch(view, R.id.switchAliveAodGlass, Prefs.KEY_ALIVE_AOD_GLASS, Prefs.DEFAULT_ALIVE_AOD_GLASS)
    }

    override fun onResume() {
        super.onResume()
        if (currentCategory == "log" || topScreen() == "diagpage") {
            currentPage()?.let { view ->
                refreshRootBallStatus(view)
            }
        }
    }

    // ------------------------------------------------------------ [root 悬浮球] ---------------------------------------------------------------

    private var rootBallStatusRow: TextView? = null
    private var rootBallStatusChip: TextView? = null

    /** root 悬浮球三件：启动/停止点击 + 状态行。 */
    private fun bindRootBall(view: View) {
        rootBallStatusRow = view.findViewById(R.id.rootBallStatusRow)
        rootBallStatusChip = view.findViewById(R.id.rootBallStatusChip)
        view.findViewById<View>(R.id.rootBallStartRow)?.setOnClickListener { v ->
            v.isEnabled = false
            v.alpha = 0.4f
            setBallStatus(getString(R.string.settings_root_ball_starting), R.string.settings_chip_ball_starting)
            RootBallLauncher.start() { msg ->
                v.isEnabled = true
                v.alpha = 1f
                setBallStatus(msg, ballChipOf(msg))
            }
        }
        view.findViewById<View>(R.id.rootBallStopRow)?.setOnClickListener { v ->
            v.isEnabled = false
            v.alpha = 0.4f
            RootBallLauncher.stop { msg ->
                v.isEnabled = true
                v.alpha = 1f
                setBallStatus(msg, ballChipOf(msg))
            }
        }
    }

    private fun refreshRootBallStatus(view: View) {
        val row = view.findViewById<TextView>(R.id.rootBallStatusRow) ?: return
        rootBallStatusRow = row
        rootBallStatusChip = view.findViewById(R.id.rootBallStatusChip)
        RootBallLauncher.status { setBallStatus(it, ballChipOf(it)) }
    }

    /** 悬浮球状态行：完整文案 + 右侧短态 chip 一起写。 */
    private fun setBallStatus(msg: String, chipRes: Int) {
        rootBallStatusRow?.text = msg
        rootBallStatusChip?.setText(chipRes)
    }

    /** 悬浮球回执 → 短态 chip 文案。 */
    private fun ballChipOf(msg: String): Int = when {
        msg.contains("失败") -> R.string.settings_chip_ball_error
        msg.contains("正在") -> R.string.settings_chip_ball_starting
        msg.contains("已启动") || msg.contains("已在运行") -> R.string.settings_chip_ball_running
        else -> R.string.settings_chip_ball_idle
    }

    /** 组件级玻璃开关绑定（写 Prefs + 按权威值刷新；hook 端 TTL 500ms 重读，改动即时生效）。 */
    private fun bindGlassSwitch(view: View, switchId: Int, key: String, default: Boolean) {
        val switch = view.findViewById<Switch>(switchId)
        if (switch == null) {
            logNavThrottled("bind-missing-$switchId", "bind skipped: switch id=$switchId not in layout ($key)")
            return
        }
        val listener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(key, checked)
        }
        switch.setOnCheckedChangeListener(listener)
        refreshBooleanSwitch(switch, listener, key, default)
    }

    // ------------------------------------------------------------ [spec/64] 桌面动画参数 ------------------------------------------------------------

    /** 桌面动画二级页：三组开关 + 三组参数入口。 */
    private fun bindAnim(view: View) {
        bindAnimGroupSwitch(view, R.id.switchAnimOpen, Prefs.ANIM_GROUP_OPEN)
        bindAnimGroupSwitch(view, R.id.switchAnimClose, Prefs.ANIM_GROUP_CLOSE)
        bindAnimGroupSwitch(view, R.id.switchAnimBreak, Prefs.ANIM_GROUP_BREAK)
        view.findViewById<View>(R.id.rowAnimOpen).setOnClickListener { openAnimParams(Prefs.ANIM_GROUP_OPEN) }
        view.findViewById<View>(R.id.rowAnimClose).setOnClickListener { openAnimParams(Prefs.ANIM_GROUP_CLOSE) }
        view.findViewById<View>(R.id.rowAnimBreak).setOnClickListener { openAnimParams(Prefs.ANIM_GROUP_BREAK) }
    }

    /** 单个动画组的启用开关（默认 false = 沿用原版动画）。 */
    private fun bindAnimGroupSwitch(view: View, switchId: Int, group: String) {
        val switch = view.findViewById<Switch>(switchId)
        val key = Prefs.animEnabledKey(group)
        val listener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(key, checked)
        }
        switch.setOnCheckedChangeListener(listener)
        refreshBooleanSwitch(switch, listener, key, Prefs.DEFAULT_ANIM_GROUP_ENABLED)
    }

    /** 三级参数页：启用开关 + 4 组（阻尼/刚度）+ 图标透明度时长。键名按组动态拼装。 */
    private fun bindAnimParams(view: View, group: String) {
        bindAnimGroupSwitch(view, R.id.switchAnimEnabled, group)
        bindAnimPresets(view, group)
        bindAnimDamping(view, R.id.seekAnimXDamping, R.id.animXDampingValue, group, Prefs.ANIM_PARAM_X_DAMPING)
        bindAnimStiffness(view, R.id.seekAnimXStiffness, R.id.animXStiffnessValue, group, Prefs.ANIM_PARAM_X_STIFFNESS)
        bindAnimDamping(view, R.id.seekAnimYDamping, R.id.animYDampingValue, group, Prefs.ANIM_PARAM_Y_DAMPING)
        bindAnimStiffness(view, R.id.seekAnimYStiffness, R.id.animYStiffnessValue, group, Prefs.ANIM_PARAM_Y_STIFFNESS)
        bindAnimDamping(view, R.id.seekAnimWidthDamping, R.id.animWidthDampingValue, group, Prefs.ANIM_PARAM_WIDTH_DAMPING)
        bindAnimStiffness(view, R.id.seekAnimWidthStiffness, R.id.animWidthStiffnessValue, group, Prefs.ANIM_PARAM_WIDTH_STIFFNESS)
        bindAnimDamping(view, R.id.seekAnimHeightDamping, R.id.animHeightDampingValue, group, Prefs.ANIM_PARAM_HEIGHT_DAMPING)
        bindAnimStiffness(view, R.id.seekAnimHeightStiffness, R.id.animHeightStiffnessValue, group, Prefs.ANIM_PARAM_HEIGHT_STIFFNESS)
        // 图标透明度时长：50~1500ms，步进 10（对应 anim_<组>_fade_duration）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekAnimFadeDuration), valueText = view.findViewById(R.id.animFadeDurationValue),
            key = Prefs.animKey(group, Prefs.ANIM_PARAM_FADE_DURATION), default = Prefs.animDefaultFadeDuration(group),
            max = (Prefs.ANIM_FADE_DURATION_MAX - Prefs.ANIM_FADE_DURATION_MIN) / Prefs.ANIM_FADE_DURATION_STEP,
            step = Prefs.ANIM_FADE_DURATION_STEP, offset = Prefs.ANIM_FADE_DURATION_MIN,
            format = { v -> getString(R.string.settings_anim_fade_value, v) },
        )
    }

    /** 阻尼滑杆（10~300，默认按组；运行时 ÷100 → 0.05~5.0，显示实际生效值）。 */
    private fun bindAnimDamping(view: View, seekId: Int, valueId: Int, group: String, param: String) {
        bindIntSeekBarStep(
            seek = view.findViewById(seekId), valueText = view.findViewById(valueId),
            key = Prefs.animKey(group, param), default = Prefs.animDefaultDamping(group),
            max = Prefs.ANIM_DAMPING_UI_MAX - Prefs.ANIM_DAMPING_UI_MIN, step = 1, offset = Prefs.ANIM_DAMPING_UI_MIN,
            format = { v ->
                val actual = (v / Prefs.ANIM_DAMPING_DIVISOR)
                    .coerceIn(Prefs.ANIM_DAMPING_RUNTIME_MIN, Prefs.ANIM_DAMPING_RUNTIME_MAX)
                getString(R.string.settings_anim_damping_value, v, actual)
            },
        )
    }

    /** 刚度滑杆（1~3000，默认按组；运行时 clamp 1~5000）。 */
    private fun bindAnimStiffness(view: View, seekId: Int, valueId: Int, group: String, param: String) {
        bindIntSeekBarStep(
            seek = view.findViewById(seekId), valueText = view.findViewById(valueId),
            key = Prefs.animKey(group, param), default = Prefs.animDefaultStiffness(group),
            max = Prefs.ANIM_STIFFNESS_UI_MAX - Prefs.ANIM_STIFFNESS_UI_MIN, step = 1, offset = Prefs.ANIM_STIFFNESS_UI_MIN,
            format = { v -> getString(R.string.settings_anim_stiffness_value, v) },
        )
    }

    /** [spec/64] 一键预设 Chip + 「同步打开」按钮（后者仅打断组可见）。 */
    private fun bindAnimPresets(view: View, group: String) {
        val chips = view.findViewById<ChipGroup>(R.id.animPresetChips)
        chips.removeAllViews()
        animPresets.forEach { preset ->
            chips.addView(Chip(view.context).apply {
                text = getString(preset.labelRes)
                isCheckable = false
                setOnClickListener { applyAnimPreset(group, preset) }
            })
        }
        // 打断动画大多与打开动画同参，提供手工「同步打开」按钮（不做自动联动）
        val isBreak = group == Prefs.ANIM_GROUP_BREAK
        val syncBtn = view.findViewById<View>(R.id.btnSyncFromOpen)
        val syncDesc = view.findViewById<View>(R.id.txtSyncFromOpenDesc)
        syncBtn.visibility = if (isBreak) View.VISIBLE else View.GONE
        syncDesc.visibility = if (isBreak) View.VISIBLE else View.GONE
        syncBtn.setOnClickListener {
            if (copyAnimGroup(from = Prefs.ANIM_GROUP_OPEN, to = Prefs.ANIM_GROUP_BREAK)) {
                bindCurrentPage()
                Toast.makeText(this, R.string.settings_anim_sync_done, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.settings_anim_sync_not_ready, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 把预设档位写入本组全部 9 个参数，并刷新滑块回显。 */
    private fun applyAnimPreset(group: String, preset: AnimPreset) {
        val stiffness = preset.stiffness ?: Prefs.animDefaultStiffness(group)
        val damping = preset.damping ?: Prefs.animDefaultDamping(group)
        val fade = preset.fade ?: Prefs.animDefaultFadeDuration(group)
        // ANIM_SPRING_PARAMS 顺序为「阻尼,刚度」交替，以 _stiffness 后缀区分
        Prefs.ANIM_SPRING_PARAMS.forEach { param ->
            writePrefInt(Prefs.animKey(group, param), if (param.endsWith("_stiffness")) stiffness else damping)
        }
        writePrefInt(Prefs.animKey(group, Prefs.ANIM_PARAM_FADE_DURATION), fade)
        bindCurrentPage()
        Toast.makeText(
            this,
            getString(R.string.settings_anim_preset_applied, getString(preset.labelRes)),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * 整组复制弹簧参数：读 [from] 已落盘的值写入 [to]，缺失项回退到 [from] 组的默认值。
     *
     * 框架未就绪时**返回 false 且不写入** —— 不可降级读 [xmlPrefs]：
     * hook 端读不到 XML，且 `anim_*` 键只存在于框架侧存储，
     * 降级读必然拿到默认值，再经 [writePrefInt] 排队落盘将**静默覆盖**用户配置。
     */
    private fun copyAnimGroup(from: String, to: String): Boolean {
        val prefs = frameworkPrefs() ?: return false
        Prefs.ANIM_SPRING_PARAMS.forEach { param ->
            val def = if (param.endsWith("_stiffness")) {
                Prefs.animDefaultStiffness(from)
            } else {
                Prefs.animDefaultDamping(from)
            }
            writePrefInt(Prefs.animKey(to, param), Prefs.readIntCompat(prefs, Prefs.animKey(from, param), def))
        }
        writePrefInt(
            Prefs.animKey(to, Prefs.ANIM_PARAM_FADE_DURATION),
            Prefs.readIntCompat(
                prefs,
                Prefs.animKey(from, Prefs.ANIM_PARAM_FADE_DURATION),
                Prefs.animDefaultFadeDuration(from),
            ),
        )
        return true
    }

    /** 动画组 → 标题资源。 */
    private fun animGroupTitleRes(group: String): Int = when (group) {
        Prefs.ANIM_GROUP_CLOSE -> R.string.settings_anim_close_enabled
        Prefs.ANIM_GROUP_BREAK -> R.string.settings_anim_break_enabled
        else -> R.string.settings_anim_open_enabled
    }

    // ------------------------------------------------------------ [spec/65] iOS 动态倾斜/透视（实验性） ------------------------------------------------------------

    /** 倾斜与透视二级页：开关 + 倾斜强度 + 透视扭曲程度。改动需重启桌面进程生效。 */
    private fun bindTilt(view: View) {
        bindNotificationDissolve(view)
        bindAnimTiltControls(view)
        bindNotifTiltControls(view)
    }

    /** 删除通知粒子飘散：开关 + 速度。 */
    private fun bindNotificationDissolve(view: View) {
        val dissolve = view.findViewById<Switch>(R.id.switchNotificationDissolve)
        if (dissolve == null) {
            logNavThrottled("bind-missing-dissolve", "bind skipped: dissolve widgets not in layout")
            return
        }
        bindGlassSwitch(
            view, R.id.switchNotificationDissolve,
            Prefs.KEY_NOTIFICATION_DISSOLVE_ENABLE, Prefs.DEFAULT_NOTIFICATION_DISSOLVE_ENABLE,
        )
        val speed = view.findViewById<SeekBar>(R.id.seekNotificationDissolveSpeed)
        val speedValue = view.findViewById<TextView>(R.id.notificationDissolveSpeedValue)
        if (speed != null && speedValue != null) {
            bindIntSeekBarStep(
                seek = speed, valueText = speedValue,
                key = Prefs.KEY_NOTIFICATION_DISSOLVE_SPEED, default = Prefs.DEFAULT_NOTIFICATION_DISSOLVE_SPEED,
                max = Prefs.NOTIFICATION_DISSOLVE_SPEED_MAX - Prefs.NOTIFICATION_DISSOLVE_SPEED_MIN, step = 1,
                offset = Prefs.NOTIFICATION_DISSOLVE_SPEED_MIN,
                format = { v -> getString(R.string.settings_notification_dissolve_speed_value, v / 100f) },
            )
        }
        if (Build.VERSION.SDK_INT < 33) {
            dissolve.isEnabled = false
            speed?.isEnabled = false
        }
    }

    /** 桌面动态倾斜：开关 + 强度 + 透视。 */
    private fun bindAnimTiltControls(view: View) {
        val switch = view.findViewById<Switch>(R.id.switchAnimTilt)
        if (switch == null) {
            logNavThrottled("bind-missing-tilt", "bind skipped: anim tilt widgets not in layout")
            return
        }
        val listener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_ANIM_TILT_ENABLED, checked)
        }
        switch.setOnCheckedChangeListener(listener)
        refreshBooleanSwitch(switch, listener, Prefs.KEY_ANIM_TILT_ENABLED, Prefs.DEFAULT_ANIM_TILT_ENABLED)

        val strength = view.findViewById<SeekBar>(R.id.seekAnimTiltStrength)
        val strengthValue = view.findViewById<TextView>(R.id.animTiltStrengthValue)
        if (strength != null && strengthValue != null) {
            bindIntSeekBarStep(
                seek = strength, valueText = strengthValue,
                key = Prefs.KEY_ANIM_TILT_STRENGTH, default = Prefs.DEFAULT_ANIM_TILT_STRENGTH,
                max = Prefs.ANIM_TILT_STRENGTH_MAX, step = 1, offset = 0,
                format = { v -> getString(R.string.settings_anim_tilt_strength_value, v) },
            )
        }
        val perspective = view.findViewById<SeekBar>(R.id.seekAnimTiltPerspective)
        val perspectiveValue = view.findViewById<TextView>(R.id.animTiltPerspectiveValue)
        if (perspective != null && perspectiveValue != null) {
            bindIntSeekBarStep(
                seek = perspective, valueText = perspectiveValue,
                key = Prefs.KEY_ANIM_TILT_PERSPECTIVE, default = Prefs.DEFAULT_ANIM_TILT_PERSPECTIVE,
                max = Prefs.ANIM_TILT_PERSPECTIVE_MAX, step = 1, offset = 0,
                format = { v -> getString(R.string.settings_anim_tilt_perspective_value, v) },
            )
        }
    }

    /** 通知卡倾斜：开关 + 角度 + 飞入缩放。 */
    private fun bindNotifTiltControls(view: View) {
        val switch = view.findViewById<Switch>(R.id.switchNotifTilt)
        if (switch == null) {
            logNavThrottled("bind-missing-notiftilt", "bind skipped: notif tilt widgets not in layout")
            return
        }
        val listener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_NOTIF_TILT_ENABLE, checked)
        }
        switch.setOnCheckedChangeListener(listener)
        refreshBooleanSwitch(switch, listener, Prefs.KEY_NOTIF_TILT_ENABLE, Prefs.DEFAULT_NOTIF_TILT_ENABLE)

        val deg = view.findViewById<SeekBar>(R.id.seekNotifTiltStrength)
        val degValue = view.findViewById<TextView>(R.id.notifTiltStrengthValue)
        if (deg != null && degValue != null) {
            bindIntSeekBarStep(
                seek = deg, valueText = degValue,
                key = Prefs.KEY_NOTIF_TILT_DEG, default = Prefs.DEFAULT_NOTIF_TILT_DEG,
                max = Prefs.NOTIF_TILT_DEG_MAX, step = 1, offset = 0,
                format = { v -> getString(R.string.settings_notif_tilt_strength_value, v) },
            )
        }
        val fly = view.findViewById<SeekBar>(R.id.seekNotifFlyScale)
        val flyValue = view.findViewById<TextView>(R.id.notifFlyScaleValue)
        if (fly != null && flyValue != null) {
            bindIntSeekBarStep(
                seek = fly, valueText = flyValue,
                key = Prefs.KEY_NOTIF_FLY_SCALE, default = Prefs.DEFAULT_NOTIF_FLY_SCALE,
                max = 80, step = 1, offset = 0,
                format = { v -> getString(R.string.settings_notif_fly_scale_value, v) },
            )
        }
    }

    /** 面板效果：通知 / 控制中心 / 音量面板。 */
    private fun bindPanel(view: View) {
        bindNotificationDissolve(view)
        bindNotifTiltControls(view)
        bindGlassSwitch(
            view, R.id.switchNotificationPopupGlass,
            Prefs.KEY_NOTIFICATION_POPUP_GLASS, Prefs.DEFAULT_NOTIFICATION_POPUP_GLASS,
        )
        bindCcBlur(view)
        bindGlassSwitch(
            view, R.id.switchVolumePanelClean,
            Prefs.KEY_VOLUME_PANEL_CLEAN, Prefs.DEFAULT_VOLUME_PANEL_CLEAN,
        )
        bindGlassSwitch(
            view, R.id.switchVolumePanelBackdrop,
            Prefs.KEY_VOLUME_PANEL_BACKDROP, Prefs.DEFAULT_VOLUME_PANEL_BACKDROP,
        )
    }

    /** 桌面 Launcher：动画三组 + 参数入口 + 动态倾斜。 */
    private fun bindLauncherPage(view: View) {
        bindAnim(view)
        bindAnimTiltControls(view)
    }

    /** 流体云：只保留指向玻璃页的入口。 */
    private fun bindSeedlingPage(view: View) {
        val row = setupRow(
            view.findViewById(R.id.seedlingGlassRow),
            getString(R.string.settings_seedling_glass_row_title),
            getString(R.string.settings_seedling_glass_row_desc),
            null,
        )
        setRowIcon(row, R.drawable.ic_row_cloud)
        row.setOnClickListener { pushScreen("glass") }
    }

    /** 息屏与锁屏：二级菜单（接管开关 + 显示设置 / 配置入口）。 */
    private fun bindAodPage(view: View) {
        val existing = view.tag as? AodMenuController
        if (existing != null) {
            existing.refresh()
            return
        }
        val controller = AodMenuController(
            context = this,
            root = view,
            readBoolean = { key, default -> prefBoolean(key, default) },
            writeBoolean = { key, value -> writePrefBoolean(key, value) },
            openDisplay = { pushScreen("aod-display") },
            openConfigs = { pushScreen("aod-configs") },
        )
        view.tag = controller
        controller.bind()
    }

    /** 显示设置：三态编辑器（预览 + 三态条 + 样式/壁纸/效果三钮）。 */
    private fun bindAodDisplay(view: View) {
        val existing = view.tag as? AodDisplayController
        if (existing != null) {
            existing.refresh()
            return
        }
        val controller = AodDisplayController(
            context = this,
            root = view,
            localPrefs = xmlPrefs,
            readString = { key, default -> prefString(key, default) },
            readBoolean = { key, default -> prefBoolean(key, default) },
            writeString = { key, value -> writePrefString(key, value) },
            writeBoolean = { key, value -> writePrefBoolean(key, value) },
            close = { navigateUp() },
        )
        view.tag = controller
        controller.bind()
    }

    /** 配置页：新建 / 使用 / 重命名 / 删除已保存配置。 */
    private fun bindAodConfigs(view: View) {
        AodConfigsController(
            context = this,
            root = view,
            localPrefs = xmlPrefs,
            readString = { key, default -> prefString(key, default) },
            writeString = { key, value -> writePrefString(key, value) },
            writeBoolean = { key, value -> writePrefBoolean(key, value) },
        ).bind()
    }

    // [2026-09-17] bindTracker 已删除：追踪器（通用运动追踪开关 + 三个遍历间隔 SeekBar）整体移除。

    /** 日志与诊断：运行日志开关 + logcat 流式抓取。 */
    private fun bindLog(view: View) {
        bindLogPage(view)
        bindDiagPage(view)
    }

    /** 日志页：运行日志 / 引擎全量 / 日志采集。 */
    private fun bindLogPage(view: View) {
        view.findViewById<Switch>(R.id.switchEnableLogs)?.let { switchLogs ->
            val logsListener = CompoundButton.OnCheckedChangeListener { _, checked ->
                writePrefBoolean(Prefs.KEY_ENABLE_LOGS, checked)
            }
            switchLogs.setOnCheckedChangeListener(logsListener)
            refreshBooleanSwitch(switchLogs, logsListener, Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS)
        }
        view.findViewById<Switch>(R.id.switchEngineFullLog)?.let { switchEngineFullLog ->
            val engineFullLogListener = CompoundButton.OnCheckedChangeListener { _, checked ->
                writePrefBoolean(Prefs.KEY_ENGINE_FULL_LOG, checked)
            }
            switchEngineFullLog.setOnCheckedChangeListener(engineFullLogListener)
            refreshBooleanSwitch(
                switchEngineFullLog, engineFullLogListener,
                Prefs.KEY_ENGINE_FULL_LOG, Prefs.DEFAULT_ENGINE_FULL_LOG,
            )
        }
        captureStartRow = view.findViewById(R.id.captureStartRow)
        captureStopRow = view.findViewById(R.id.captureStopRow)
        val includeAllRow = view.findViewById<Switch>(R.id.switchCaptureAll)
        captureStatusRow = view.findViewById(R.id.captureStatusRow)
        captureStatusChip = view.findViewById(R.id.captureStatusChip)
        captureListView = view.findViewById(R.id.captureList)
        captureStartRow?.setOnClickListener { startLogCapture(includeAllRow?.isChecked == true) }
        captureStopRow?.setOnClickListener {
            LogCapture.stop()
            refreshCaptureUi()
        }
        refreshCaptureUi()
    }

    /** 诊断页：裁切旁路 / 相册探针 / 点选元素玻璃 / 悬浮球。 */
    private fun bindDiagPage(view: View) {
        bindGlassSwitch(view, R.id.switchElementGlass, Prefs.KEY_ELEMENT_GLASS_ENABLE, Prefs.DEFAULT_ELEMENT_GLASS_ENABLE)
        bindGlassSwitch(view, R.id.switchClipBypassMask, Prefs.KEY_CLIP_BYPASS_MASK, Prefs.DEFAULT_CLIP_BYPASS)
        bindGlassSwitch(view, R.id.switchClipBypassShape, Prefs.KEY_CLIP_BYPASS_SHAPE, Prefs.DEFAULT_CLIP_BYPASS)
        bindGlassSwitch(view, R.id.switchClipBypassCanvas, Prefs.KEY_CLIP_BYPASS_CANVAS, Prefs.DEFAULT_CLIP_BYPASS)
        bindGlassSwitch(view, R.id.switchGalleryShaderProbe, Prefs.KEY_GALLERY_SHADER_PROBE, Prefs.DEFAULT_GALLERY_SHADER_PROBE)
        bindRootBall(view)
    }

    private var captureStartRow: View? = null
    private var captureStopRow: View? = null
    private var captureStatusRow: TextView? = null
    private var captureStatusChip: TextView? = null
    private var captureListView: LinearLayout? = null

    /** 刷新抓取按钮态/状态行/文件列表。 */
    private fun refreshCaptureUi() {
        val running = LogCapture.isRunning()
        captureStartRow?.isEnabled = !running
        captureStopRow?.isEnabled = running
        captureStartRow?.alpha = if (running) 0.4f else 1f
        captureStopRow?.alpha = if (running) 1f else 0.4f
        if (running) {
            captureStatusRow?.text = getString(R.string.settings_capture_running, LogCapture.currentFile?.name ?: "?")
            captureStatusChip?.setText(R.string.settings_chip_capture_running)
        } else {
            captureStatusRow?.setText(R.string.settings_capture_idle)
            captureStatusChip?.setText(R.string.settings_chip_capture_idle)
        }
        val host = captureListView ?: return
        host.removeAllViews()
        val files = LogCapture.listCaptures(this)
        if (files.isEmpty()) {
            host.addView(TextView(this).apply {
                setText(R.string.settings_capture_empty)
                setPadding(0, 12, 0, 12)
            })
            return
        }
        for (f in files) {
            val row = TextView(this).apply {
                text = getString(R.string.settings_capture_item, f.name, f.length() / 1024)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setPadding(0, 24, 0, 24)
                setOnClickListener { shareCaptureFile(f) }
            }
            host.addView(row)
        }
    }

    /** 后台探测 su → 起 logcat 子进程（不可用 Toast 提示）。 */
    private fun startLogCapture(includeAll: Boolean) {
        captureStartRow?.isEnabled = false
        captureStartRow?.alpha = 0.4f
        captureStatusRow?.setText(R.string.settings_capture_probing)
        captureStatusChip?.setText(R.string.settings_chip_capture_probing)
        val logsOff = !(frameworkPrefs()?.getBoolean(Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS)
            ?: xmlPrefs.getBoolean(Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS))
        Thread {
            val su = LogCapture.probeSu()
            val ok = su && LogCapture.start(applicationContext, includeAll)
            runOnUiThread {
                if (!su) Toast.makeText(this, R.string.settings_capture_no_root, Toast.LENGTH_LONG).show()
                else if (!ok) Toast.makeText(this, R.string.settings_capture_failed, Toast.LENGTH_SHORT).show()
                if (ok && logsOff) {
                    Toast.makeText(this, R.string.settings_capture_logs_off_hint, Toast.LENGTH_LONG).show()
                }
                refreshCaptureUi()
            }
        }.start()
    }

    /** 分享单个抓取文件（FileProvider content uri → 系统分享面板）。 */
    private fun shareCaptureFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.settings_share_log)))
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.settings_share_log_failed, t.message ?: "error"), Toast.LENGTH_SHORT).show()
        }
    }

    /** 关于：TG/GitHub 完整网址点击跳转浏览器，QQ/开发者点击复制。 */
    // ------------------------------------------------------------ 配置导入导出 ------------------------------------------------------------

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { exportConfig(it) } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { confirmImport(it) } }

    /** 配置分类：导出/导入两行按钮。 */
    private fun bindConfig(view: View) {
        view.findViewById<View>(R.id.configExportRow).setOnClickListener {
            exportLauncher.launch("liquid-glass-config.json")
        }
        view.findViewById<View>(R.id.configImportRow).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    /** 导出框架侧全部配置键为 JSON；按值类型分四桶，避免回读时 int/float 类型歧义。 */
    private fun exportConfig(uri: Uri) {
        val prefs = frameworkPrefs()
        if (prefs == null) {
            Toast.makeText(this, R.string.config_framework_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val floats = JSONObject()
                val ints = JSONObject()
                val bools = JSONObject()
                val strs = JSONObject()
                for ((k, v) in prefs.all) when (v) {
                    is Float -> floats.put(k, v.toDouble())
                    is Int -> ints.put(k, v)
                    is Boolean -> bools.put(k, v)
                    is String -> strs.put(k, v)
                    else -> {}
                }
                val root = JSONObject()
                    .put("format", "lg-config")
                    .put("version", 1)
                    .put("float", floats)
                    .put("int", ints)
                    .put("boolean", bools)
                    .put("string", strs)
                val count = floats.length() + ints.length() + bools.length() + strs.length()
                val out = contentResolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("openOutputStream 返回 null")
                out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.config_export_done, count), Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Lg.w(TAG, "export config failed", t)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.config_export_failed, t.message ?: "error"), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 读所选文件、校验格式与项数，弹覆盖确认框；确认后走 [applyImport]。 */
    private fun confirmImport(uri: Uri) {
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("openInputStream 返回 null")
                val root = JSONObject(text)
                if (root.optString("format") != "lg-config") {
                    runOnUiThread {
                        Toast.makeText(this, R.string.config_import_invalid, Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val floats = root.optJSONObject("float") ?: JSONObject()
                val ints = root.optJSONObject("int") ?: JSONObject()
                val bools = root.optJSONObject("boolean") ?: JSONObject()
                val strs = root.optJSONObject("string") ?: JSONObject()
                val count = floats.length() + ints.length() + bools.length() + strs.length()
                if (count == 0) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.config_import_no_items, Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.config_import_confirm_title)
                        .setMessage(getString(R.string.config_import_confirm_message, count))
                        .setPositiveButton(R.string.config_import_confirm_ok) { _, _ ->
                            applyImport(floats, ints, bools, strs, count)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                        .also { DialogGlass.arm(it) }
                }
            } catch (t: Throwable) {
                Lg.w(TAG, "import config parse failed", t)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.config_import_failed, t.message ?: "error"), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 覆盖写入框架侧（四桶批量一次 apply），成功后刷新当前分类控件值。 */
    private fun applyImport(floats: JSONObject, ints: JSONObject, bools: JSONObject, strs: JSONObject, count: Int) {
        val prefs = frameworkPrefs()
        if (prefs == null) {
            Toast.makeText(this, R.string.config_framework_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val editor = prefs.edit()
            var it1 = floats.keys()
            while (it1.hasNext()) { val k = it1.next(); editor.putFloat(k, floats.getDouble(k).toFloat()) }
            var it2 = ints.keys()
            while (it2.hasNext()) { val k = it2.next(); editor.putInt(k, ints.getInt(k)) }
            var it3 = bools.keys()
            while (it3.hasNext()) { val k = it3.next(); editor.putBoolean(k, bools.getBoolean(k)) }
            var it4 = strs.keys()
            while (it4.hasNext()) { val k = it4.next(); editor.putString(k, strs.getString(k)) }
            editor.apply()
            bindCurrentPage()
            Toast.makeText(this, getString(R.string.config_import_done, count), Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Lg.w(TAG, "import config apply failed", t)
            Toast.makeText(this, getString(R.string.config_import_failed, t.message ?: "error"), Toast.LENGTH_SHORT).show()
        }
    }

    /** 状态栏定制：总控 / 图标显隐 / 双卡与边距 / 时钟与排序（键族 Prefs.KEY_STATUSBAR_*）。 */
    private fun bindStatusBar(view: View) {
        bindGlassSwitch(view, R.id.switchSbEnable, Prefs.KEY_STATUSBAR_ENABLE, Prefs.DEFAULT_STATUSBAR_ENABLE)
        val simplifyPreset = listOf(
            Prefs.KEY_STATUSBAR_HIDE_NOTIFICATION_ICONS,
            Prefs.KEY_STATUSBAR_HIDE_NETWORK_TYPE,
            Prefs.KEY_STATUSBAR_HIDE_ROAMING,
            Prefs.KEY_STATUSBAR_HIDE_DATA_ACTIVITY,
            Prefs.KEY_STATUSBAR_HIDE_WIFI_ACTIVITY,
            Prefs.KEY_STATUSBAR_HIDE_WIFI_TECH,
        )
        val legacySimplify = frameworkPrefs()?.getBoolean(Prefs.KEY_STATUSBAR_SIMPLIFY, false)
            ?: xmlPrefs.getBoolean(Prefs.KEY_STATUSBAR_SIMPLIFY, false)
        if (legacySimplify) {
            simplifyPreset.forEach { writePrefBoolean(it, true) }
            writePrefBoolean(Prefs.KEY_STATUSBAR_SIMPLIFY, false)
        }
        view.findViewById<View>(R.id.btnSbSimplify).setOnClickListener {
            simplifyPreset.forEach { writePrefBoolean(it, true) }
            writePrefBoolean(Prefs.KEY_STATUSBAR_SIMPLIFY, false)
            bindCurrentPage()
            Toast.makeText(this, R.string.sb_simplify_applied, Toast.LENGTH_SHORT).show()
        }
        bindGlassSwitch(
            view, R.id.switchSbHideNotifIcons,
            Prefs.KEY_STATUSBAR_HIDE_NOTIFICATION_ICONS, Prefs.DEFAULT_STATUSBAR_HIDE_NOTIFICATION_ICONS,
        )
        bindGlassSwitch(
            view, R.id.switchSbHideNetworkType,
            Prefs.KEY_STATUSBAR_HIDE_NETWORK_TYPE, Prefs.DEFAULT_STATUSBAR_HIDE_NETWORK_TYPE,
        )
        bindGlassSwitch(
            view, R.id.switchSbTypeRightOfSignal,
            Prefs.KEY_STATUSBAR_TYPE_RIGHT_OF_SIGNAL, Prefs.DEFAULT_STATUSBAR_TYPE_RIGHT_OF_SIGNAL,
        )
        bindGlassSwitch(view, R.id.switchSbHideRoaming, Prefs.KEY_STATUSBAR_HIDE_ROAMING, Prefs.DEFAULT_STATUSBAR_HIDE_ROAMING)
        bindGlassSwitch(
            view, R.id.switchSbHideDataActivity,
            Prefs.KEY_STATUSBAR_HIDE_DATA_ACTIVITY, Prefs.DEFAULT_STATUSBAR_HIDE_DATA_ACTIVITY,
        )
        bindGlassSwitch(
            view, R.id.switchSbHideWifiActivity,
            Prefs.KEY_STATUSBAR_HIDE_WIFI_ACTIVITY, Prefs.DEFAULT_STATUSBAR_HIDE_WIFI_ACTIVITY,
        )
        bindGlassSwitch(view, R.id.switchSbHideWifiTech, Prefs.KEY_STATUSBAR_HIDE_WIFI_TECH, Prefs.DEFAULT_STATUSBAR_HIDE_WIFI_TECH)
        bindGlassSwitch(
            view, R.id.switchSbHideMobileDataOff,
            Prefs.KEY_STATUSBAR_HIDE_MOBILE_WHEN_DATA_OFF, Prefs.DEFAULT_STATUSBAR_HIDE_MOBILE_WHEN_DATA_OFF,
        )
        bindGlassSwitch(
            view, R.id.switchSbHideMobileWifi,
            Prefs.KEY_STATUSBAR_HIDE_MOBILE_WHEN_WIFI, Prefs.DEFAULT_STATUSBAR_HIDE_MOBILE_WHEN_WIFI,
        )
        bindGlassSwitch(view, R.id.switchSbKeepSim25g, Prefs.KEY_STATUSBAR_KEEP_SIM2_5G, Prefs.DEFAULT_STATUSBAR_KEEP_SIM2_5G)
        bindGlassSwitch(view, R.id.switchSbDuo, Prefs.KEY_STATUSBAR_DUO, Prefs.DEFAULT_STATUSBAR_DUO)
        bindGlassSwitch(
            view, R.id.switchSbDualMerge,
            Prefs.KEY_STATUSBAR_DUAL_SIM_MERGE, Prefs.DEFAULT_STATUSBAR_DUAL_SIM_MERGE,
        )
        bindGlassSwitch(
            view, R.id.switchSbClockSeconds,
            Prefs.KEY_STATUSBAR_CLOCK_SECONDS, Prefs.DEFAULT_STATUSBAR_CLOCK_SECONDS,
        )
        bindGlassSwitch(
            view, R.id.switchSbNotifRight,
            Prefs.KEY_STATUSBAR_NOTIFICATION_ICONS_RIGHT, Prefs.DEFAULT_STATUSBAR_NOTIFICATION_ICONS_RIGHT,
        )

        bindIntSeekBarStep(
            view.findViewById(R.id.seekSbDualGap), view.findViewById(R.id.valSbDualGap),
            Prefs.KEY_STATUSBAR_DUAL_ROW_GAP, Prefs.DEFAULT_STATUSBAR_DUAL_ROW_GAP,
            Prefs.STATUSBAR_DUAL_ROW_GAP_MAX - Prefs.STATUSBAR_DUAL_ROW_GAP_MIN, 1,
            Prefs.STATUSBAR_DUAL_ROW_GAP_MIN,
        ) { v -> "$v%" }
        bindIntSeekBarStep(
            view.findViewById(R.id.seekSbSecondaryScale), view.findViewById(R.id.valSbSecondaryScale),
            Prefs.KEY_STATUSBAR_SECONDARY_SCALE, Prefs.DEFAULT_STATUSBAR_SECONDARY_SCALE,
            Prefs.STATUSBAR_SECONDARY_SCALE_MAX - Prefs.STATUSBAR_SECONDARY_SCALE_MIN, 1,
            Prefs.STATUSBAR_SECONDARY_SCALE_MIN,
        ) { v -> "$v%" }
        bindIntSeekBarStep(
            view.findViewById(R.id.seekSbMarginLeft), view.findViewById(R.id.valSbMarginLeft),
            Prefs.KEY_STATUSBAR_MARGIN_LEFT, Prefs.DEFAULT_STATUSBAR_MARGIN_LEFT,
            Prefs.STATUSBAR_MARGIN_MAX - Prefs.STATUSBAR_MARGIN_MIN, 1,
            Prefs.STATUSBAR_MARGIN_MIN,
        ) { v -> if (v < 0) "默认" else "${v}dp" }
        bindIntSeekBarStep(
            view.findViewById(R.id.seekSbMarginRight), view.findViewById(R.id.valSbMarginRight),
            Prefs.KEY_STATUSBAR_MARGIN_RIGHT, Prefs.DEFAULT_STATUSBAR_MARGIN_RIGHT,
            Prefs.STATUSBAR_MARGIN_MAX - Prefs.STATUSBAR_MARGIN_MIN, 1,
            Prefs.STATUSBAR_MARGIN_MIN,
        ) { v -> if (v < 0) "默认" else "${v}dp" }

        bindStatusBarOrder(view)
    }

    /** 图标排序：全量真实槽横滑浏览，长按拖动换位、松手吸附写回；空串 = 系统顺序。 */
    private fun bindStatusBarOrder(view: View) {
        val defaults = listOf("wifi", "mobile", "alarm_clock", "accessibility", "safe", "mute", "battery")
        val nameRes = mapOf(
            "wifi" to R.string.sb_slot_wifi,
            "mobile" to R.string.sb_slot_mobile,
            "alarm_clock" to R.string.sb_slot_alarm,
            "accessibility" to R.string.sb_slot_accessibility,
            "safe" to R.string.sb_slot_safe,
            "mute" to R.string.sb_slot_mute,
            "battery" to R.string.sb_slot_battery,
            "block_banner" to R.string.sb_slot_block_banner,
            "satellite" to R.string.sb_slot_satellite,
            "logging" to R.string.sb_slot_logging,
            "data_saver" to R.string.sb_slot_data_saver,
            "cast" to R.string.sb_slot_cast,
            "connected_display" to R.string.sb_slot_connected_display,
            "vpn" to R.string.sb_slot_vpn,
            "nfc" to R.string.sb_slot_nfc,
            "high_performance" to R.string.sb_slot_high_performance,
            "volume" to R.string.sb_slot_volume,
            "bluetooth" to R.string.sb_slot_bluetooth,
            "sos" to R.string.sb_slot_sos,
            "location" to R.string.sb_slot_location,
            "microphone" to R.string.sb_slot_microphone,
            "camera" to R.string.sb_slot_camera,
            "zen" to R.string.sb_slot_zen,
            "nosim_all" to R.string.sb_slot_nosim_all,
            "secure_pay" to R.string.sb_slot_secure_pay,
        )
        val iconRes = mapOf(
            "wifi" to R.drawable.ic_sb_slot_wifi,
            "mobile" to R.drawable.ic_sb_slot_mobile,
            "alarm_clock" to R.drawable.ic_sb_slot_alarm,
            "accessibility" to R.drawable.ic_sb_slot_accessibility,
            "safe" to R.drawable.ic_sb_slot_safe,
            "mute" to R.drawable.ic_sb_slot_mute,
            "battery" to R.drawable.ic_sb_slot_battery,
        )
        val current = (frameworkPrefs()?.getString(KEY_SB_ICON_ORDER_CURRENT, null)
            ?: xmlPrefs.getString(KEY_SB_ICON_ORDER_CURRENT, null)).orEmpty()
        val stored = (frameworkPrefs()?.getString(Prefs.KEY_STATUSBAR_ICON_ORDER, null)
            ?: xmlPrefs.getString(Prefs.KEY_STATUSBAR_ICON_ORDER, null)).orEmpty()
        val curSeq = StatusBarSettings.parseOrder(current)
        val storedSeq = StatusBarSettings.parseOrder(stored)
        val base = storedSeq.ifEmpty { curSeq }
        val order: MutableList<String> = if (base.isEmpty()) {
            defaults.toMutableList()
        } else {
            (base + curSeq.filter { it !in base }).toMutableList()
        }

        val density = resources.displayMetrics.density
        val chipPx = (36 * density).roundToInt()
        val gapPx = (10 * density).roundToInt()
        val stepPx = chipPx + gapPx
        val strip = view.findViewById<FrameLayout>(R.id.sbOrderStrip)
        val hsv = view.findViewById<HorizontalScrollView>(R.id.sbOrderScroll)
        val row = view.findViewById<LinearLayout>(R.id.sbOrderRow)
        val viewOfSlot = HashMap<String, FrameLayout>()
        fun slotLabel(slot: String): String = nameRes[slot]?.let { getString(it) } ?: slot
        fun makeChip(slot: String) = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(chipPx, chipPx).apply { rightMargin = gapPx }
            val pad = (6 * density).toInt()
            val icon = iconRes[slot]
            if (icon != null) {
                addView(ImageView(this@SettingsActivity).apply {
                    setImageResource(icon)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(pad, pad, pad, pad)
                })
            } else {
                addView(TextView(this@SettingsActivity).apply {
                    text = slotLabel(slot)
                    textSize = 9f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(resources.getColor(R.color.lg_ink_100, theme))
                    setPadding(2, 0, 2, 0)
                })
            }
            contentDescription = slotLabel(slot)
            tag = slot
        }
        fun rebuild() {
            row.removeAllViews()
            order.forEach { slot ->
                val chip = viewOfSlot.getOrPut(slot) { makeChip(slot) }
                (chip.parent as? ViewGroup)?.removeView(chip)
                row.addView(chip)
            }
            for (i in 0 until row.childCount) row.getChildAt(i).translationX = 0f
        }
        fun persist() {
            writePrefString(Prefs.KEY_STATUSBAR_ICON_ORDER, order.joinToString(","))
            Lg.i(TAG_NAV, "sb order write=${order.joinToString(",")}")
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var pendingPress: Runnable? = null
        var downX = 0f
        var downY = 0f
        var scrollStart = 0
        var scrolling = false
        var dragView: View? = null
        var dragLayoutIndex = 0
        var dragFrom = 0
        var dragTo = 0
        fun slotOf(v: View): String = v.tag as String
        fun targetPosOf(v: View, translation: Float): Int {
            val layoutIndex = row.indexOfChild(v)
            return ((layoutIndex * stepPx + translation + chipPx / 2f) / stepPx)
                .toInt().coerceIn(0, order.lastIndex)
        }
        fun shiftExceptDragged() {
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                if (c === dragView) continue
                val pos = order.indexOf(slotOf(c))
                c.animate().translationX((pos - i) * stepPx.toFloat()).setDuration(120L).start()
            }
        }
        fun cancelPending() {
            pendingPress?.let { mainHandler.removeCallbacks(it) }
            pendingPress = null
        }
        fun endDrag(commit: Boolean) {
            val dragged = dragView ?: return
            cancelPending()
            dragView = null
            strip.parent?.requestDisallowInterceptTouchEvent(false)
            val settleX = (dragTo - dragLayoutIndex) * stepPx.toFloat()
            if (commit && dragTo != dragFrom) {
                persist()
                Lg.i(TAG_NAV, "sb order drag $dragFrom->$dragTo")
            }
            dragged.animate().translationX(settleX).setDuration(140L).withEndAction {
                dragged.elevation = 0f
                dragged.scaleX = 1f
                dragged.scaleY = 1f
                rebuild()
            }.start()
        }
        strip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    scrollStart = hsv.scrollX
                    scrolling = false
                    cancelPending()
                    val localX = downX - row.left + hsv.scrollX
                    val pressIdx = (localX / stepPx).toInt().coerceIn(0, order.lastIndex)
                    val chip = if (localX < 0f || localX >= row.width) null else row.getChildAt(pressIdx)
                    if (chip != null) {
                        Lg.i(TAG_NAV, "sb drag down idx=$pressIdx slot=${slotOf(chip)}")
                        pendingPress = Runnable {
                            pendingPress = null
                            dragView = chip
                            dragLayoutIndex = row.indexOfChild(chip)
                            dragFrom = order.indexOf(slotOf(chip))
                            dragTo = dragFrom
                            strip.parent?.requestDisallowInterceptTouchEvent(true)
                            strip.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            chip.elevation = 8 * density
                            chip.scaleX = 1.08f
                            chip.scaleY = 1.08f
                            Lg.i(TAG_NAV, "sb order drag start ${slotOf(chip)}")
                        }
                        mainHandler.postDelayed(pendingPress!!, 350L)
                    } else {
                        Lg.i(TAG_NAV, "sb drag down miss x=${event.x}")
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dragged = dragView
                    if (dragged != null) {
                        val dx = event.x - downX
                        dragged.translationX = dx
                        val newTo = targetPosOf(dragged, dx)
                        if (newTo != dragTo) {
                            val old = dragTo
                            dragTo = newTo
                            val moved = order.removeAt(old)
                            order.add(newTo, moved)
                            Lg.i(TAG_NAV, "sb drag shift $old->$newTo order=${order.joinToString(",")}")
                            shiftExceptDragged()
                        }
                    } else {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (pendingPress != null) {
                            if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                                Lg.i(TAG_NAV, "sb drag cancel slop-h -> scroll")
                                cancelPending()
                                scrolling = true
                            } else if (kotlin.math.abs(dy) > touchSlop) {
                                Lg.i(TAG_NAV, "sb drag cancel slop")
                                cancelPending()
                            }
                        }
                        if (scrolling) {
                            val viewport = (hsv.width - hsv.paddingLeft - hsv.paddingRight).coerceAtLeast(1)
                            val maxScroll = (row.width - viewport).coerceAtLeast(0)
                            hsv.scrollX = (scrollStart - dx).toInt().coerceIn(0, maxScroll)
                        }
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> {
                    Lg.i(TAG_NAV, "sb drag up dragging=${dragView != null} from=$dragFrom to=$dragTo")
                    scrolling = false
                    if (dragView != null) endDrag(commit = true)
                    else cancelPending()
                }
            }
            true
        }
        rebuild()
        view.findViewById<View>(R.id.sbOrderReset).setOnClickListener {
            writePrefString(Prefs.KEY_STATUSBAR_ICON_ORDER, "")
            order.clear()
            order.addAll(if (curSeq.isNotEmpty()) curSeq else defaults)
            rebuild()
            Lg.i(TAG_NAV, "sb order reset")
        }
    }

    private fun bindAbout(view: View) {
        // TG 频道完整网址 → 点击跳转浏览器；失败回退复制
        val tgRow = view.findViewById<View>(R.id.aboutTgRow)
        tgRow.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/c16lg666")))
            } catch (t: Throwable) {
                Lg.w(TAG, "about open tg failed, fallback copy", t)
                bindCopyRow(tgRow, getString(R.string.about_tg))
            }
        }
        bindCopyRow(view.findViewById(R.id.aboutQqRow), getString(R.string.about_qq))
        bindCopyRow(view.findViewById(R.id.aboutDeveloperRow), getString(R.string.about_developer))
        // GitHub 完整网址 → 点击跳转浏览器；失败回退复制
        val githubRow = view.findViewById<View>(R.id.aboutGithubRow)
        githubRow.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AYwlilwYA/What-Color-OS")))
            } catch (t: Throwable) {
                Lg.w(TAG, "about open github failed, fallback copy", t)
                bindCopyRow(githubRow, getString(R.string.about_github))
            }
        }
    }

    /** 重启作用域：root 杀掉 SystemUI 让其重载模块。 */
    private fun restartScope() {
        val btn = restartScopeBtn ?: return
        btn.isEnabled = false
        Toast.makeText(this, R.string.settings_restart_scope_running, Toast.LENGTH_SHORT).show()
        Lg.i(TAG_NAV, "restart scope: kill com.android.systemui")
        Thread {
            val result = SfGlassChannel.runSu("pkill -f com.android.systemui; true")
            runOnUiThread {
                btn.isEnabled = true
                if (result.ok) {
                    Toast.makeText(this, R.string.settings_restart_scope_done, Toast.LENGTH_SHORT).show()
                    Lg.i(TAG_NAV, "restart scope done: ${result.summary}")
                } else {
                    val reason = result.err ?: result.summary
                    Toast.makeText(
                        this, getString(R.string.settings_restart_scope_failed, reason), Toast.LENGTH_LONG,
                    ).show()
                    Lg.w(TAG_NAV, "restart scope failed: $reason")
                }
            }
        }.start()
    }

    /** 设置栏首页：外观 / 配置 / 日志与诊断 / 关于。 */
    private fun bindSettingsRoot(view: View) {
        val themeRow = setupRow(
            view.findViewById(R.id.settingsThemeRow),
            getString(R.string.settings_theme_row),
            getString(R.string.settings_theme_row_desc),
            currentThemeLabel(),
        )
        themeRow.setOnClickListener { showThemeDialog() }
        setRowIcon(themeRow, R.drawable.ic_row_palette)

        val languageRow = setupRow(
            view.findViewById(R.id.settingsLanguageRow),
            getString(R.string.settings_language_row),
            getString(R.string.settings_language_row_desc),
            currentLanguageLabel(),
        )
        languageRow.setOnClickListener { openAppLanguageSettings() }
        setRowIcon(languageRow, R.drawable.ic_row_globe)

        bindGlassSwitch(
            view, R.id.switchSettingsEnableLogs,
            Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS,
        )

        val configRow = setupRow(
            view.findViewById(R.id.settingsConfigIoRow),
            getString(R.string.settings_config_io_row),
            getString(R.string.settings_config_io_row_desc),
            null,
        )
        configRow.setOnClickListener { pushScreen("config") }
        setRowIcon(configRow, R.drawable.ic_row_archive)

        val logRow = setupRow(
            view.findViewById(R.id.settingsLogPageRow),
            getString(R.string.settings_logpage_row),
            getString(R.string.settings_logpage_row_desc),
            null,
        )
        logRow.setOnClickListener { pushScreen("logpage") }
        setRowIcon(logRow, R.drawable.ic_row_list)

        val diagRow = setupRow(
            view.findViewById(R.id.settingsDiagPageRow),
            getString(R.string.settings_diagpage_row),
            getString(R.string.settings_diagpage_row_desc),
            null,
        )
        diagRow.setOnClickListener { pushScreen("diagpage") }
        setRowIcon(diagRow, R.drawable.ic_row_pulse)

        val aboutRow = setupRow(
            view.findViewById(R.id.settingsAboutRow),
            getString(R.string.settings_about_row),
            getString(R.string.settings_about_row_desc),
            null,
        )
        aboutRow.setOnClickListener { pushScreen("about") }
        setRowIcon(aboutRow, R.drawable.ic_row_info)
    }

    /** 总开关状态胶囊：开启蓝色、关闭中性。 */
    private fun applyMasterChip(chip: TextView, enabled: Boolean) {
        chip.text = getString(
            if (enabled) R.string.settings_home_master_on else R.string.settings_home_master_off
        )
        chip.setTextColor(resources.getColor(if (enabled) R.color.lg_blued_ink else R.color.lg_ink_300, theme))
        chip.setBackgroundResource(if (enabled) R.drawable.bg_chip_on else R.drawable.bg_chip)
    }

    /** 每次冷启动计数 +1（本地存储）。 */
    private fun trackColdStart() {
        val count = xmlPrefs.getInt(XML_KEY_OPEN_COUNT, 0) + 1
        xmlPrefs.edit().putInt(XML_KEY_OPEN_COUNT, count).apply()
        Lg.i(TAG_NAV, "cold start count=$count")
    }

    /** 首次启动弹应用声明（免费、退款、免责）；确认后不再弹。 */
    private fun maybeShowFirstRunDeclaration() {
        if (xmlPrefs.getBoolean(XML_KEY_DECLARED, false)) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.first_run_title)
            .setMessage(R.string.first_run_message)
            .setPositiveButton(R.string.first_run_ok) { _, _ ->
                xmlPrefs.edit().putBoolean(XML_KEY_DECLARED, true).apply()
                logNavThrottled("first-run-ack", "first run declaration acknowledged")
            }
            .setCancelable(false)
            .create()
        DialogGlass.arm(dialog)
        dialog.show()
        logNavThrottled("first-run-show", "first run declaration shown")
    }

    /** 启动时套用本地保存的主题。 */
    private fun applySavedThemeMode() {
        val mode = Prefs.read(this).getInt(XML_KEY_THEME_MODE, THEME_DARK)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun currentThemeLabel(): String = getString(
        when (xmlPrefs.getInt(XML_KEY_THEME_MODE, THEME_DARK)) {
            THEME_LIGHT -> R.string.settings_theme_light
            THEME_DARK -> R.string.settings_theme_dark
            else -> R.string.settings_theme_follow
        }
    )

    /** 主题三选一：写入本地并立即生效。 */
    private fun showThemeDialog() {
        val labels = arrayOf(
            getString(R.string.settings_theme_follow),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_theme_dialog_title)
            .setSingleChoiceItems(labels, xmlPrefs.getInt(XML_KEY_THEME_MODE, THEME_DARK)) { dialog, which ->
                xmlPrefs.edit().putInt(XML_KEY_THEME_MODE, which).apply()
                AppCompatDelegate.setDefaultNightMode(
                    when (which) {
                        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                        THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
                dialog.dismiss()
                logNavThrottled("theme-select", "theme mode=$which")
            }
            .show()
    }

    private fun currentLanguageLabel(): String =
        resources.configuration.locales[0].getDisplayName(resources.configuration.locales[0])

    /** 跳系统应用语言设置；旧系统回退应用详情页。 */
    private fun openAppLanguageSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (t: Throwable) {
                Lg.w(TAG_NAV, "open language settings failed: ${intent.action}", t)
            }
        }
        Toast.makeText(this, R.string.settings_language_open_failed, Toast.LENGTH_SHORT).show()
    }

    /** 给关于条目绑定点击复制（点击复制整行文案到剪贴板）。 */
    private fun bindCopyRow(row: View, text: String) {
        row.setOnClickListener {
            try {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("about", text))
                Toast.makeText(this, getString(R.string.about_copy_hint), Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Lg.w(TAG, "about copy failed", t)
            }
        }
    }

    // ------------------------------------------------------------ 存储访问 ------------------------------------------------------------

    /** 框架侧存储（与 hook 端 getRemotePreferences 同一份；框架未注入/未就绪时为 null）。 */
    private fun frameworkPrefs(): SharedPreferences? =
        App.serviceRef?.let { Prefs.remote(it) }

    /** 配置写入：先落本地（权威，离线可写），框架在线时同步推一份；离线由绑定时全量同步补推。 */
    private fun putConfig(key: String, write: (SharedPreferences.Editor) -> Unit) {
        write(Prefs.write(this))
        val fw = frameworkPrefs()
        if (fw == null) {
            Lg.i(TAG, "put $key local only (offline)")
            return
        }
        try {
            write(fw.edit())
        } catch (t: Throwable) {
            Lg.w(TAG, "put $key push failed", t)
        }
    }

    /** 按框架侧存储（权威值）刷新总开关回显；刷新不触发监听器写入。 */
    private fun refreshMasterSwitch(switch: Switch, listener: CompoundButton.OnCheckedChangeListener) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = masterEnabled()
        switch.setOnCheckedChangeListener(listener)
    }

    /** 读总开关：优先框架侧存储，回退本地 XML。 */
    private fun masterEnabled(): Boolean =
        frameworkPrefs()?.getBoolean(Prefs.KEY_MASTER, false)
            ?: xmlPrefs.getBoolean(Prefs.KEY_MASTER, false)

    /** 写总开关（本地权威 + 框架在线同步）。 */
    private fun writeMasterEnabled(checked: Boolean) {
        putConfig(Prefs.KEY_MASTER) { it.putBoolean(Prefs.KEY_MASTER, checked).apply() }
        Lg.i(TAG, "write master_enabled=$checked")
    }

    // ------------------------------------------------------------ 通用布尔开关 ------------------------------------------------------------

    /** 按框架侧存储（权威值）刷新布尔开关回显；刷新不触发监听器写入。 */
    private fun refreshBooleanSwitch(
        switch: Switch,
        listener: CompoundButton.OnCheckedChangeListener,
        key: String,
        default: Boolean,
    ) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = frameworkPrefs()?.getBoolean(key, default) ?: xmlPrefs.getBoolean(key, default)
        switch.setOnCheckedChangeListener(listener)
    }

    /** 写布尔配置到框架侧存储（hook 端可读）；框架未就绪时排队等待就绪后再写。 */
    private fun writePrefBoolean(key: String, value: Boolean) {
        putConfig(key) { it.putBoolean(key, value).apply() }
        Lg.i(TAG, "write $key=$value")
    }

    // ------------------------------------------------------------ 通用 String 写入（背景档位） ------------------------------------------------------------

    /** 写 String 配置（本地权威 + 框架在线同步）。 */
    private fun writePrefString(key: String, value: String) {
        putConfig(key) { it.putString(key, value).apply() }
        Lg.i(TAG, "write $key=$value")
    }

    // ------------------------------------------------------------ 液态玻璃材质滑杆 ------------------------------------------------------------

    /**
     * 绑定单个 float 材质滑杆（回显 + 拖动结束写 framework prefs）。
     *
     * @param onChanged [spec/89] 拖动中（含初始回显）实时回调当前值，用于喂效果预览；
     *                  **落盘仍只在 onStopTrackingTouch**，不改变配置写入时机。
     * @param presetParams 预设编辑态：该键在预设里有值显示预设值，否则显示全局值。
     * @param presetId 非空时拖动结束写进该预设的参数键，空时写全局键。
     */
    /** 手动同步滑杆填充层 level（0..10000），防 ROM 不下发 level 时 0% 残留填充。 */
    private fun SeekBar.syncTrackLevel() {
        if (max > 0) progressDrawable?.setLevel(progress * 10000 / max)
    }

    private fun bindFloatSeekBar(
        seek: SeekBar,
        valueText: TextView,
        key: String,
        default: Float,
        fromProgress: (Int) -> Float,
        toProgress: (Float) -> Int,
        format: (Float) -> String,
        onChanged: ((Float) -> Unit)? = null,
        presetParams: Map<String, Float>? = null,
        presetId: String? = null,
    ) {
        val current = presetParams?.get(key)
            ?: frameworkPrefs()?.getFloat(key, default)
            ?: xmlPrefs.getFloat(key, default)
        seek.progress = toProgress(current)
        val initial = fromProgress(seek.progress)
        valueText.text = format(initial)
        onChanged?.invoke(initial)
        seek.syncTrackLevel()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                sb.syncTrackLevel()
                val v = fromProgress(progress)
                valueText.text = format(v)
                onChanged?.invoke(v)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                val v = fromProgress(sb.progress)
                if (presetId != null) writeGlassPresetParam(presetId, key, v) else writePrefFloat(key, v)
            }
        })
    }

    /** 写 float 配置（本地权威 + 框架在线同步）。 */
    private fun writePrefFloat(key: String, value: Float) {
        putConfig(key) { it.putFloat(key, value).apply() }
        Lg.i(TAG, "write $key=$value")
    }

    // ------------------------------------------------------------ [2026-10-10] 框架服务死亡提示

    /** 本会话是否已弹过「服务断开」提示（防刷）。 */
    private var serviceLostNoticeShown = false

    /**
     * 框架 binder 死亡提示：LibXposed 的 binder 只在模块进程启动时注入一次，死后**无重连机制** ——
     * 此后设置页读全走 XML 兜底（显示旧值/默认值）、写全部进队列永远发不出（真机实测 2026-10-10：
     * 「总开关打开退出就变关 / 参数重开回旧值」）。唯一自愈 = 完全关闭设置页（杀进程）后重开。
     */
    private fun showServiceLostNotice() {
        if (serviceLostNoticeShown) return
        if (!::contentFrame.isInitialized) return
        serviceLostNoticeShown = true
        runOnUiThread {
            try {
                Snackbar.make(
                    contentFrame,
                    "配置服务已断开，当前修改不会生效。请从最近任务划掉本页后重新打开。",
                    Snackbar.LENGTH_LONG,
                ).show()
            } catch (ignored: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------ 通用 int 滑杆 ------------------------------------------------------------

    /** 绑定单个 int 滑杆（0~max，回显 + 拖动结束写 framework prefs）。 */
    private fun bindIntSeekBar(
        seek: SeekBar,
        valueText: TextView,
        key: String,
        default: Int,
        max: Int,
        format: (Int) -> String,
    ) {
        seek.max = max
        // [2026-08-13 类型修复] int 配置兼容读（存量 Float → getInt 抛；readIntCompat 回退 getFloat）
        val current = frameworkPrefs()?.let { Prefs.readIntCompat(it, key, default) }
            ?: Prefs.readIntCompat(xmlPrefs, key, default)
        seek.progress = current.coerceIn(0, max)
        valueText.text = format(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                valueText.text = format(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                writePrefInt(key, sb.progress)
            }
        })
    }

    /** 带步进/偏移的 int 滑杆（progress → value = progress*step + offset，反向 value → progress）。
     *  [2026-08-13 类型修复] 替代 bindFloatSeekBar 写 int 配置（原 Float 写入 → 读端 getInt ClassCastException）。 */
    private fun bindIntSeekBarStep(
        seek: SeekBar,
        valueText: TextView,
        key: String,
        default: Int,
        max: Int,
        step: Int,
        offset: Int,
        format: (Int) -> String,
    ) {
        seek.max = max
        // [2026-08-13 类型修复] int 配置兼容读（存量 Float → getInt 抛；readIntCompat 回退 getFloat）
        val current = frameworkPrefs()?.let { Prefs.readIntCompat(it, key, default) }
            ?: Prefs.readIntCompat(xmlPrefs, key, default)
        val progress = ((current - offset) / step).coerceIn(0, max)
        seek.progress = progress
        valueText.text = format(progress * step + offset)
        seek.syncTrackLevel()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                sb.syncTrackLevel()
                valueText.text = format(progress * step + offset)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                writePrefInt(key, sb.progress * step + offset)
            }
        })
    }

    /** 写 int 配置（本地权威 + 框架在线同步）。 */
    private fun writePrefInt(key: String, value: Int) {
        putConfig(key) { it.putInt(key, value).apply() }
        Lg.i(TAG, "write $key=$value")
    }

    // ------------------------------------------------------------ 降采样 spinner ------------------------------------------------------------

    /** 降采样档位 spinner 回显 + 写入（0.25/0.5/0.75/1.0；先 setSelection 再挂监听，避免回显触发写入）。 */
    private fun bindScaleSpinner(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.spinnerCaptureScale)
        val scales = listOf(0.25f, 0.5f, 0.75f, 1.0f)
        val labels = scales.map { getString(R.string.settings_capture_scale_value, it) }
        spinner.adapter = ArrayAdapter(
            this@SettingsActivity, android.R.layout.simple_spinner_item, labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val current = frameworkPrefs()?.getFloat(Prefs.KEY_BG_CAPTURE_SCALE, Prefs.DEFAULT_BG_CAPTURE_SCALE)
            ?: xmlPrefs.getFloat(Prefs.KEY_BG_CAPTURE_SCALE, Prefs.DEFAULT_BG_CAPTURE_SCALE)
        val idx = scales.indexOfFirst { Math.abs(it - current) < 0.01f }.coerceAtLeast(0)
        spinner.setSelection(idx)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                writePrefFloat(Prefs.KEY_BG_CAPTURE_SCALE, scales[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    /** [spec/35] 遮罩下文字颜色 spinner 回显 + 写入（纯白/浅灰/灰 → ARGB int；先 setSelection 再挂监听，避免回显触发写入）。
     *  改动需重启 SystemUI 生效（hook 端 install 时读取并缓存）。 */
    private fun bindMaskTextColorSpinner(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.spinnerMaskTextColor)
        val colors = listOf(
            0xFFFFFFFF.toInt(),   // 纯白
            0xFFE6E6E6.toInt(),   // 浅灰（90% 白）
            0xFFB3B3B3.toInt(),   // 灰（70% 白）
        )
        val labels = listOf(
            getString(R.string.settings_mask_text_color_white),
            getString(R.string.settings_mask_text_color_lightgray),
            getString(R.string.settings_mask_text_color_gray),
        )
        spinner.adapter = ArrayAdapter(
            this@SettingsActivity, android.R.layout.simple_spinner_item, labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val current = frameworkPrefs()?.getInt(Prefs.KEY_MASK_TEXT_COLOR, Prefs.DEFAULT_MASK_TEXT_COLOR)
            ?: xmlPrefs.getInt(Prefs.KEY_MASK_TEXT_COLOR, Prefs.DEFAULT_MASK_TEXT_COLOR)
        val idx = colors.indexOfFirst { it == current }.coerceAtLeast(0)
        spinner.setSelection(idx)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                writePrefInt(Prefs.KEY_MASK_TEXT_COLOR, colors[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    companion object {
        private const val TAG = "LiquidGlassSettings"
        private const val TAG_NAV = "SettingsNav"
        /** hook 侧回传的真机状态栏当前图标顺序（逗号 slot 串，未回传时为空）。 */
        private const val KEY_SB_ICON_ORDER_CURRENT = "statusbar_icon_order_current"

        /** [spec/88] 单轮 inset settle 内 spacer 允许的最大连续调整次数（正常第 1 次就收敛）。 */
        private const val MAX_SPACER_ADJUST = 4

        /** [2026-09-17] 已「忽略此版本」的版本号（本地 XML 存储，不写框架侧——纯 UI 本地状态）。 */
        private const val XML_KEY_IGNORED_UPDATE = "ignored_update_version"

        /** 主题模式：0 跟随系统 / 1 浅色 / 2 深色（本地 XML，仅影响设置页）。 */
        private const val XML_KEY_THEME_MODE = "ui_theme_mode"
        private const val THEME_FOLLOW_SYSTEM = 0
        private const val THEME_LIGHT = 1
        private const val THEME_DARK = 2

        /** 冷启动计数与首启声明标记（本地 XML，断网可用）。 */
        private const val XML_KEY_OPEN_COUNT = "lg_open_count"
        private const val XML_KEY_DECLARED = "lg_declared_v1"

        /** 主页快捷跳转目标（赞助锚点指向 README 的赞助小节）。 */
        private const val AUTHOR_URL = "https://github.com/AYwlilwYA"
        private const val GROUP_URL = "https://t.me/c16lg666"
        private const val QQ_GROUP_NUMBER = "1102985644"
        private const val QQ_PACKAGE = "com.tencent.mobileqq"
        private const val SPONSORS_URL =
            "https://raw.githubusercontent.com/AYwlilwYA/What-Color-OS/main/sponsors.txt"
        private const val AUTHOR_NAME = "AYwlilwYA"
    }
}

// ============================================================ [spec/89] 材质参数页实时效果预览 ============================================================

/**
 * 「液态玻璃材质」参数页顶部的效果预览。
 *
 * 用与 hook 端**同一份 AGSL**（[LiquidGlassShader]）实时渲染一块示例玻璃：下面垫一张本 View 自绘的
 * 高对比背景样图（对角渐变 + 柔光色块 + 白色网格），样图做水平往复滚动 —— 折射环带、色散、内部模糊、
 * vibrancy、边缘高光都能在滚动中看出差别；网格线是观察折射扭曲的参照。
 *
 * 纯 UI 展示，与 hook 零耦合：
 * - 参数由 [SettingsActivity.bindMaterial] 从滑杆当前值经 `onChanged` 喂入，本类**不读不写 Prefs、
 *   不新增配置键**（视差 X/Y 不喂 —— 会把采样区移出预览框，见 doc/spec/89）；
 * - 背景样图是自己画的位图，不抓屏、不碰任何 hook 状态。
 *
 * 坐标约定（BlurDrawHook.kt 4017-4025 实证）：`main(coord)` 拿到的是 canvas 当前变换下的**本地坐标**，
 * 所以先 `canvas.translate(cardLeft, cardTop)` 再 `drawRoundRect(0,0,w,h)`，并把 `uViewport` 设成
 * 卡片自身尺寸 —— 几何中心正好落在 coord=(w/2,h/2)，`srcCoord` 也退化为 `srcRect.xy + coord` 1:1。
 *
 * 降级：API < 33（`RuntimeShader` 最低要求）或 AGSL 编译/下发失败时画一块半透明圆角，不崩、不留白。
 */
class LiquidGlassPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 下列 12 项由 `bindFloatSeekBar(onChanged=...)` 实时喂入（见 [SettingsActivity.bindMaterial]）；
     *  折射区域**模式**由模式 Chip 组喂入（[refractionHeightMode]）。 */
    var blurRadius = Prefs.DEFAULT_BLUR_RADIUS
    var vibrancy = Prefs.DEFAULT_VIBRANCY
    var highlightFalloff = Prefs.DEFAULT_HIGHLIGHT_FALLOFF
    /** [doc/spec/95] 折射区域·相对模式：refractionHeight = 短边 × 本值，默认 0.5 = 短边一半。 */
    var refractionHeightRatio = Prefs.DEFAULT_REFRACTION_HEIGHT_RATIO
    /** [doc/spec/96] 折射区域·模式：0=相对（短边比例）1=绝对（像素）。 */
    var refractionHeightMode = Prefs.DEFAULT_REFRACTION_HEIGHT_MODE
    /** [doc/spec/96] 折射区域·绝对模式像素值（仅 [refractionHeightMode]=1 时生效）。 */
    var refractionHeightPx = Prefs.DEFAULT_REFRACTION_HEIGHT_PX
    var refractionAmount = Prefs.DEFAULT_REFRACTION_AMOUNT
    var dispersion = Prefs.DEFAULT_DISPERSION
    var depth = Prefs.DEFAULT_DEPTH
    var highlight = Prefs.DEFAULT_HIGHLIGHT
    var highlightWidth = Prefs.DEFAULT_HIGHLIGHT_WIDTH
    var highlightFloor = Prefs.DEFAULT_HIGHLIGHT_FLOOR
    var highlightInnerAtt = Prefs.DEFAULT_HIGHLIGHT_INNER_ATT
    var surfacePolish = Prefs.DEFAULT_HIGHLIGHT_ENHANCE
    var innerGlow = Prefs.DEFAULT_INNER_GLOW
    var edgeStroke = Prefs.DEFAULT_EDGE_STROKE
    var edgeStrokeWidth = Prefs.DEFAULT_EDGE_STROKE_WIDTH
    var maskGlassDim = Prefs.DEFAULT_MASK_GLASS_DIM
    var lightAngle = Prefs.DEFAULT_LIGHT_ANGLE

    /** 参数变了由外部调 [refresh] 重绘；背景滚动则由 [onDraw] 自续帧驱动。 */
    fun refresh() = invalidate()

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var shader: RuntimeShader? = null
    private var shaderBroken = false
    private var bg: Bitmap? = null
    private var bgLow: Bitmap? = null
    private var bgShader: BitmapShader? = null
    private var bgLowShader: BitmapShader? = null

    /** 背景样图横向取景偏移 px（0~[travel]）。 */
    private var scrollX = 0f

    /** 样图比视图宽出的滚动行程 px。 */
    private var travel = 0f

    private var startTimeMs = SystemClock.uptimeMillis()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        buildBackground(w, h)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, CORNER_RATIO * minOf(view.width, view.height))
            }
        }
        clipToOutline = true
        scrollX = 0f
        startTimeMs = SystemClock.uptimeMillis()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        val backdrop = bg
        if (w > 0 && h > 0 && backdrop != null) {
            // 背景往复滚动：按系统时钟取相位，重复 invalidate 不会加速/变相
            if (travel > 0f) {
                val t = (SystemClock.uptimeMillis() - startTimeMs) / 1000.0
                scrollX = (travel * (0.5 - 0.5 * cos(2.0 * PI * t / SCROLL_PERIOD_SEC))).toFloat()
            }
            canvas.drawBitmap(backdrop, -scrollX, 0f, bgPaint)

            val pad = (CARD_PADDING_DP * density).toInt()
            val cw = w - pad * 2
            val ch = h - pad * 2
            if (cw > 0 && ch > 0) drawGlass(canvas, pad, cw, ch)
        }
        // 只在可见时续帧：切走/脱离窗口后链条自动断开，动画即停，无泄漏
        if (isShown) postInvalidateDelayed(FRAME_INTERVAL_MS)
    }

    /** 渲染示例玻璃；shader 不可用时降级为半透明圆角。 */
    private fun drawGlass(canvas: Canvas, pad: Int, cw: Int, ch: Int) {
        val sh = ensureShader()
        val src = bgShader
        if (sh == null || src == null) {
            drawFallback(canvas, pad, cw, ch)
            return
        }
        val shortSide = minOf(cw, ch).toFloat()
        val cornerR = shortSide * CORNER_RATIO
        val cardLeft = pad.toFloat()
        val cardTop = pad.toFloat()
        val params = LiquidGlassShader.Params(
            viewportWidth = cw.toFloat(),
            viewportHeight = ch.toFloat(),
            cornerRadius = cornerR,
            blurRadius = blurRadius,
            // [doc/spec/95 + spec/96] 与 hook 端统一出口 Prefs.refractionHeightPx **同一恒等式**：
            // 相对 = 短边 × 短边比例（默认 0.5）；绝对 = 固定像素。预览与装机后的真实变化一致。
            refractionHeight = if (refractionHeightMode == Prefs.REFRACTION_HEIGHT_MODE_ABS) {
                refractionHeightPx
            } else {
                shortSide * refractionHeightRatio
            },
            refractionAmount = refractionAmount,
            depth = depth,
            dispersion = dispersion,
            highlight = highlight,
            highlightWidth = highlightWidth,
            highlightFalloff = highlightFalloff,
            highlightFloor = highlightFloor,
            highlightInnerAtt = highlightInnerAtt,
            innerGlow = innerGlow,
            vibrancy = vibrancy,
            lightAngleDegrees = lightAngle,
            parallaxX = 0f,
            parallaxY = 0f,
            surfacePolish = surfacePolish,
            edgeStroke = edgeStroke,
            edgeStrokeWidth = edgeStrokeWidth,
        )
        // 卡片窗口在样图中的区域（bitmap 坐标）；AGSL 侧 srcCoord = srcRect.xy + coord/uViewport * srcRect.zw
        val srcRect = RectF(cardLeft + scrollX, cardTop, cardLeft + scrollX + cw, cardTop + ch)
        val lowRect = RectF(
            srcRect.left / LOW_SCALE, srcRect.top / LOW_SCALE,
            srcRect.right / LOW_SCALE, srcRect.bottom / LOW_SCALE,
        )
        try {
            LiquidGlassShader.setUniforms(
                sh, params, src, bgLowShader,
                sourceRect = srcRect, lowResSourceRect = lowRect,
                maskAlpha = maskGlassDim,
            )
        } catch (t: Throwable) {
            shaderBroken = true
            Lg.e(TAG, "preview setUniforms failed, fallback", t)
            drawFallback(canvas, pad, cw, ch)
            return
        }
        glassPaint.shader = sh
        val save = canvas.save()
        try {
            // 与 hook 同一套坐标约定：先平移到卡片原点，再画本地 (0,0,w,h)
            canvas.translate(cardLeft, cardTop)
            canvas.drawRoundRect(0f, 0f, cw.toFloat(), ch.toFloat(), cornerR, cornerR, glassPaint)
        } finally {
            glassPaint.shader = null
            canvas.restoreToCount(save)
        }
    }

    /** 降级：不透明度约 1/8 的白圆角，起码能看出「这里是一块玻璃」。 */
    private fun drawFallback(canvas: Canvas, pad: Int, cw: Int, ch: Int) {
        val r = minOf(cw, ch) * CORNER_RATIO
        glassPaint.shader = null
        glassPaint.color = 0x32FFFFFF
        canvas.drawRoundRect(
            pad.toFloat(), pad.toFloat(), (pad + cw).toFloat(), (pad + ch).toFloat(),
            r, r, glassPaint,
        )
    }

    /** 懒编译 AGSL（只编一次）；失败后置 [shaderBroken]，后续直接走降级，不再反复抛。 */
    private fun ensureShader(): RuntimeShader? {
        shader?.let { return it }
        if (shaderBroken || Build.VERSION.SDK_INT < 33) return null
        return try {
            LiquidGlassShader.create().also { shader = it }
        } catch (t: Throwable) {
            shaderBroken = true
            Lg.e(TAG, "preview AGSL compile failed", t)
            null
        }
    }

    /** 程序生成背景样图：对角高饱和渐变 + 柔光色块 + 白色网格（网格用于观察折射扭曲）。 */
    private fun buildBackground(w: Int, h: Int) {
        travel = (w / 2).toFloat()
        val bw = (w + travel).toInt()
        val bmp = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, bw.toFloat(), h.toFloat(),
                intArrayOf(
                    0xFF1A2038.toInt(), 0xFF0C0E14.toInt(),
                ),
                null, Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, bw.toFloat(), h.toFloat(), base)

        val blobs = intArrayOf(
            0xCCE86A4F.toInt(), 0xCC5F7BF0.toInt(),
            0xCCC7952F.toInt(), 0xCC8E4FD1.toInt(),
        )
        blobs.forEachIndexed { i, col ->
            val cx = bw * (i + 0.5f) / blobs.size
            val cy = h * (if (i % 2 == 0) 0.34f else 0.7f)
            val r = h * 0.45f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, r, col, col and 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
            c.drawCircle(cx, cy, r, p)
        }

        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22FFFFFF
            strokeWidth = 2f
        }
        val step = (h * 0.25f).coerceAtLeast(8f)
        var x = step
        while (x < bw) {
            c.drawLine(x, 0f, x, h.toFloat(), grid)
            x += step
        }
        var y = step
        while (y < h) {
            c.drawLine(0f, y, bw.toFloat(), y, grid)
            y += step
        }

        bg = bmp
        bgLow = Bitmap.createScaledBitmap(
            bmp, (bw / 4).coerceAtLeast(1), (h / 4).coerceAtLeast(1), true
        )
        bgShader = LiquidGlassShader.createSourceBitmapShader(bmp)
        bgLowShader = bgLow?.let { LiquidGlassShader.createLowResSourceBitmapShader(it) }
    }

    private companion object {
        const val TAG = "LiquidGlassPreview"

        /** 背景往复滚动周期（秒）。 */
        const val SCROLL_PERIOD_SEC = 7.0

        /** 绘制帧间隔 ms（≈30fps，预览够用且省电）。 */
        const val FRAME_INTERVAL_MS = 33L

        /** 玻璃卡片四周留白 dp（露出原背景做前后对比）。 */
        const val CARD_PADDING_DP = 14f

        /** 预览圆角 = 短边 ×此比例（纯审美固定值，不接配置）。 */
        const val CORNER_RATIO = 0.3f

        /** uSourceLowRes 相对 uSource 的降采样比（与 hook 一致 1/4）。 */
        const val LOW_SCALE = 4f
    }
}
