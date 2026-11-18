package com.coloros16.liquidglass.entry

import android.os.Build
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.hook.AodHandoffHook
import com.coloros16.liquidglass.hook.AppGlassHook
import com.coloros16.liquidglass.hook.BlurDrawHook
import com.coloros16.liquidglass.hook.DemoGlassHook
import com.coloros16.liquidglass.hook.DesktopClockAnchorBridge
import com.coloros16.liquidglass.hook.DesktopClockAnchorHook
import com.coloros16.liquidglass.hook.LauncherAnimHook
import com.coloros16.liquidglass.hook.LauncherHook
import com.coloros16.liquidglass.hook.LgNativeProbe
import com.coloros16.liquidglass.hook.LauncherTiltHook
import com.coloros16.liquidglass.hook.KeyguardGlassHook
import com.coloros16.liquidglass.hook.LockClockHeightHook
import com.coloros16.liquidglass.hook.LockScreenClockHook
import com.coloros16.liquidglass.hook.SceneFilter
import com.coloros16.liquidglass.hook.StatusBarClockAnchorHook
import com.coloros16.liquidglass.hook.StatusBarHook
import com.coloros16.liquidglass.hook.SystemUiLifecycle
import com.coloros16.liquidglass.hook.TiltPath
import com.coloros16.liquidglass.hook.VersionPool
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * LibXposed 模块入口。
 *
 * 关于 @XposedEntry：本工程依赖的 LibXposed 102.0.0（本地缓存）【不存在】该注解。
 * 已实证（api 源码 src/main/java/io/github/libxposed/api/package-info.java 的
 * "Getting Started / Entry Registration" 章节）：
 * - 模块入口 = 继承 [XposedModule] 的类（保留无参构造，框架反射实例化）
 * - 入口类注册于 META-INF/xposed/java_init.list（一行一个全限定类名）
 * - annotation 1.0.0 仅含 @SinceApi / @InternalApi，无 XposedEntry
 * 故本类采用继承 XposedModule 方式；若未来框架引入 @XposedEntry 注解可平滑切换。
 *
 * 生命周期（XposedModuleInterface，可重写）：
 * - onModuleLoaded：模块注入目标进程时调用一次（此时 SystemUI 类未就绪）
 * - onPackageLoaded：目标包默认 classLoader 就绪后调用（本工程在此安装 hook）
 * - [doc/spec/110] onHotReloading / onHotReloaded：热重载两段回调（见下）
 */
class XposedEntry : XposedModule() {

    override fun onModuleLoaded(param: io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam) {
        // 先装文件日志门控，后面的日志才按「运行日志」开关入文件
        installLogGate()
        Lg.e("lg-boot", "loaded process=${param.processName} pid=${android.os.Process.myPid()}")
        // 模块注入完成（SystemUI 类尚未加载）。标记入口加载成功，便于真机确认模块生效。
        Lg.i(TAG, "module loaded, process=${param.processName}, isSystemServer=${param.isSystemServer()}")
    }

    /** logcat 详细度门控：读框架侧「运行日志」开关（开=全量，关=仅 e 级；读失败默认开）。 */
    private fun installLogGate() {
        val module = this
        Lg.installGateReader {
            try {
                Prefs.read(module).getBoolean(Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS)
            } catch (t: Throwable) {
                true
            }
        }
    }

    // ⛔ [spec/82 已停用 2026-09-17] 曾在此实现 `onSystemServerStarting`，向 system_server 挂
    // `WindowManagerService#mirrorWallpaperSurface` 通道 hook。**真机实证该注入会导致显示管线卡死**
    // （屏幕 ON、`mWakefulness=Awake`，但 SurfaceFlinger 合成全黑；撤掉后恢复）。故整条注入撤除。
    // 详见 doc/spec/82 §九。

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // 框架可能注入超出 scope 的包，必须按包名过滤。
        // [2026-08-13 用户决定] BlurService 端全移除：仅 SystemUI 进程安装 hook（com.oplus.blur
        // 进程不再注入，系统模糊管线完全原样，玻璃完全靠 SystemUI 端每元素背景源）。
        // [2026-08-13 spec/10] Launcher 进程（com.android.launcher）加入：桌面文件夹背景液态玻璃化，
        // 与 SystemUI 共用同一套 posteffect drawBlurShader hook（FQCN 相同，两份独立 hook 实例）。
        if (param.packageName != PACKAGE_SYSTEMUI && param.packageName != PACKAGE_LAUNCHER &&
            param.packageName != PACKAGE_GALLERY && param.packageName != PACKAGE_CALCULATOR &&
            param.packageName != PACKAGE_SMART_SIDEBAR && param.packageName != PACKAGE_ASSISTANT_SCREEN &&
            param.packageName != PACKAGE_QUICK_SEARCH_BOX && param.packageName != PACKAGE_DEMO
        ) return
        val classLoader = param.getDefaultClassLoader() ?: return
        // [doc/spec/110] 存宿主 classLoader：同 ClassLoader 复用重载时 onHotReloaded 直接取用；
        // 新 ClassLoader 场景下新类静态为空，由 resolveClassLoader 的兜底链接管。
        cacheClassLoader(param.packageName, classLoader)
        if (param.packageName == PACKAGE_SYSTEMUI) {
            // 等应用初始化完成再装 hook；装载点挂失败时不锁死、不往未知时序里硬装
            val mounted = SystemUiLifecycle.install(this, classLoader) {
                doInstall(PACKAGE_SYSTEMUI, classLoader)
            }
            if (!mounted) {
                Lg.e(TAG, "SystemUI lifecycle mount failed, hooks not installed this load")
            }
        } else {
            doInstall(param.packageName, classLoader)
        }
    }

    /**
     * [doc/spec/110 热重载] 第一段：**旧类**回调，Drip 据此决定是否继续重载（default 恒 false ⇒
     * 不 override 则整条热重载在第一道门就被 refused，1~3ms 瞬败且无 message —— 实测根因）。
     *
     * 返回 true 前必须停掉模块自有常驻异步（几何线程池 / 主线程延时回调 / 单飞标志），
     * 否则旧类线程随每次重载泄漏、旧延时回调会带着旧逻辑穿插新代码。
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // 注意：HotReloadingParam **不**继承 ModuleLoadedParam，只暴露 extras/savedState，拿不到 processName
        Lg.i(TAG, "hot-reload: approving (old-class callback)")
        try {
            BlurDrawHook.shutdownForHotReload()
        } catch (t: Throwable) {
            Lg.w(TAG, "hot-reload: shutdown step failed", t)
        }
        try {
            com.coloros16.liquidglass.hook.aod.AodStageHook.shutdownForHotReload()
        } catch (t: Throwable) {
            Lg.w(TAG, "hot-reload: aod stage shutdown failed", t)
        }
        try {
            AodHandoffHook.shutdownForHotReload()
        } catch (t: Throwable) {
            Lg.w(TAG, "hot-reload: aod handoff shutdown failed", t)
        }
        return true
    }

    /**
     * [doc/spec/110 热重载] 第二段：**新类**回调。
     *
     * ⚠️ 两个动作缺一不可（只 opt-in onHotReloading 不够）：
     * - 框架 default 本方法 = `oldHookHandles.forEach { unhook }`：**不 override 也会卸光旧 hook**
     *   （但不会重装 ⇒ reload 后模块全灭）；这里显式复刻卸载 + 立即重装成**新代码**的 hook。
     * - 装载回调（onPackageLoaded）**不会重放**，必须在此手动重跑安装。
     *
     * 保守顺序：**先解析 classLoader，失败则一条旧 hook 都不卸** —— 宁可继续跑旧代码，
     * 也不接受「卸了装不回」的全灭状态（本项目一贯原则：宁可不生效也不失效）。
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        val pkg = param.processName
        if (pkg == null) {
            Lg.e(TAG, "hot-reload: null processName, keep old hooks")
            return
        }
        if (pkg != PACKAGE_SYSTEMUI && pkg != PACKAGE_LAUNCHER &&
            pkg != PACKAGE_GALLERY && pkg != PACKAGE_CALCULATOR && pkg != PACKAGE_SMART_SIDEBAR &&
            pkg != PACKAGE_ASSISTANT_SCREEN && pkg != PACKAGE_QUICK_SEARCH_BOX && pkg != PACKAGE_DEMO
        ) {
            // 非目标进程：按框架语义卸掉旧 hook（这些进程本就不该有模块 hook）
            param.oldHookHandles.forEach { h ->
                try {
                    h.unhook()
                } catch (t: Throwable) {
                }
            }
            return
        }
        val cl = resolveClassLoader(pkg, param)
        if (cl == null) {
            Lg.e(TAG, "hot-reload: classLoader unresolved for $pkg, KEEP OLD HOOKS (no unhook)")
            return
        }
        // 旧 hook → 新 hook 的交接（等价框架 default，再立即重装）
        var unhooked = 0
        param.oldHookHandles.forEach { h ->
            try {
                h.unhook()
                unhooked++
            } catch (t: Throwable) {
            }
        }
        Lg.i(TAG, "hot-reload: old hooks unhooked=$unhooked/$pkg, reinstalling into $pkg")
        try {
            doInstall(pkg, cl, hotReload = true)
        } catch (t: Throwable) {
            Lg.e(TAG, "hot-reload: reinstall threw ${t.javaClass.name}: ${t.message}", t)
        }
        Lg.i(TAG, "hot-reload: reinstall done for $pkg, version=$MODULE_VERSION")
    }

    /** [doc/spec/110] 安装主入口（onPackageLoaded 与 onHotReloaded 共用同一套门槛与分发）。 */
    private fun doInstall(packageName: String, classLoader: ClassLoader, hotReload: Boolean = false) {
        Lg.e("lg-boot", "host=$packageName pid=${android.os.Process.myPid()}")
        // 模块默认全关（Prefs.masterEnabled=false 直接跳过，零侵入）。
        if (!Prefs.masterEnabled(this)) {
            Lg.d(TAG, "package loaded but master disabled, skip install")
            return
        }
        // 热重载后新类静态为空，重装一次文件日志门控
        installLogGate()
        if (packageName == PACKAGE_SYSTEMUI && crashGuardBlocks(this, hotReload)) {
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Lg.w(TAG, "device SDK=${Build.VERSION.SDK_INT} < 29(Q), skip install")
            return
        }
        Lg.i(TAG, "installing hooks into $packageName")
        when (packageName) {
            // SystemUI：Hook B 绘制替换 / Hook C 场景过滤（占位实现，保持现状）。
            // [2026-08-13 用户决定] BlurService 端全移除：模块不再干预 com.oplus.blur 进程，
            // 系统模糊管线完全原样；玻璃完全靠 SystemUI 端每元素背景源。
            // [spec/116 P0] 只读 versionCode 打日志铺观测，不做门控（SystemUI 适配是后续阶段）。
            PACKAGE_SYSTEMUI -> {
                val hostVer = VersionPool.readVersion(PACKAGE_SYSTEMUI)
                Lg.i(TAG, "host version: pkg=$PACKAGE_SYSTEMUI versionCode=$hostVer (SystemUI no gating in P0)")
                installHooks(classLoader)
                // 架构探测已在 install 内完成，查询时补丁随 lg-vermatch 一并带出
                VersionPool.query(PACKAGE_SYSTEMUI, hostVer)
            }
            // [spec/10] Launcher：桌面文件夹背景液态玻璃化（闭合文件夹图标 + 打开文件夹卡片）。
            // [spec/64] Launcher：桌面动画参数调节（弹簧刚度/阻尼 + 图标透明度时长，默认全关）。
            // [spec/65] Launcher：iOS 动态倾斜/透视（实验性，默认关）。
            // [spec/116 P0/P2] 版本门控：<17.3.12（或读取失败 -1）三 hook 全装、tilt 走 16.x 现状路径；
            // >=17.3.12 禁用 anim hook，tilt 改走原生 zuyqa 参数注入路径。
            PACKAGE_LAUNCHER -> {
                val hostVer = VersionPool.readVersion(PACKAGE_LAUNCHER)
                val profile = VersionPool.query(PACKAGE_LAUNCHER, hostVer).profile
                if (profile.tiltPath == TiltPath.NATIVE_ZUYQA) {
                    Lg.i(TAG, "host version: pkg=$PACKAGE_LAUNCHER versionCode=$hostVer, branch=17.3.12+: skip LauncherAnimHook (17.x fields obfuscated), tilt=native zuyqa path")
                    if (profile.launcherMainHook) LauncherHook.install(this, classLoader)
                    LauncherTiltHook.installNativeZuyqa(this, classLoader)
                } else {
                    Lg.i(TAG, "host version: pkg=$PACKAGE_LAUNCHER versionCode=$hostVer, branch=<17.3.12 (or unknown -1): install all 3 hooks")
                    if (profile.launcherMainHook) LauncherHook.install(this, classLoader)
                    if (profile.launcherAnimHook) LauncherAnimHook.install(this, classLoader)
                    LauncherTiltHook.install(this, classLoader)
                }
                DesktopClockAnchorHook.install(this, classLoader)
            }
            // 第三方 App（相册/计算器/智能侧边栏/速览）：A/B 族模糊挂载点登记（组件清单 + 预设按组件命中）。
            // 渲染腿尚未接通（进程内无抓屏权限拿不到背景源），背景仍由系统模糊渲染，见 AppGlassHook 头注。
            PACKAGE_GALLERY, PACKAGE_CALCULATOR, PACKAGE_SMART_SIDEBAR, PACKAGE_ASSISTANT_SCREEN,
            PACKAGE_QUICK_SEARCH_BOX -> {
                VersionPool.query(packageName, VersionPool.readVersion(packageName))
                AppGlassHook.install(this, classLoader, packageName)
            }
            // 验通道用 demo：模块代码直调 SF 模糊接口（非注入进程自调不走）
            PACKAGE_DEMO -> {
                Lg.i(TAG, "demo probe host pid=${android.os.Process.myPid()}")
                DemoGlassHook.install(this)
            }
            else -> {}
        }
        // 冷启动注入在重负载设备上易撞 AMS 超时，探针顺带挂进常驻进程便于热重载验证
        try {
            if (packageName == PACKAGE_SYSTEMUI) {
                DemoGlassHook.installIntoViewDraw(this)
            } else {
                DemoGlassHook.install(this)
            }
        } catch (t: Throwable) {
            Lg.e(TAG, "probe install FAILED in $packageName: ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            val lib = java.io.File("/data/data/$packageName/code_cache/liblgprobe11.so")
            if (lib.exists()) {
                System.load(lib.absolutePath)
                Lg.e("lg-native-probe", "window blur probe in $packageName lib=${lib.length()}")
                Lg.e("lg-native-probe", "result=" + LgNativeProbe.blurFirstWindow(0))
            } else {
                Lg.e("lg-native-probe", "lib missing: $lib")
            }
        } catch (t: Throwable) {
            Lg.e("lg-native-probe", "blur probe FAILED in $packageName: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 循环崩守卫：窗口内到达安装点的不同 SystemUI pid 达到阈值 → 强制关总开关并跳过本次安装。
     * 账本用 commit 落盘（进程随时会崩，apply 异步写可能丢）；读写失败一律 fail-open 不拦安装。
     *
     * 两条豁免（只豁免「不是崩溃」的重启，真崩循环照拦）：
     * - 热重载：同进程换代码，pid 不变本就不计数，但会刷新账本时间戳，故整条跳过。
     * - 装机宽限：模块 APK 刚更新过（lastUpdateTime 变了）→ 清账本重新起算，
     *   且本次安装点落在宽限窗内不计数；窗口过后同一包连续崩仍会攒满阈值。
     */
    private fun crashGuardBlocks(module: XposedEntry, hotReload: Boolean = false): Boolean {
        return try {
            val prefs = Prefs.read(module)
            val d = CrashGuardPolicy.decide(
                now = System.currentTimeMillis(),
                pid = android.os.Process.myPid(),
                ledger = prefs.getString(Prefs.KEY_CRASH_GUARD_PIDS, "") ?: "",
                apkStamp = moduleApkStamp(module),
                lastStamp = prefs.getLong(Prefs.KEY_CRASH_GUARD_APK_STAMP, 0L),
                hotReload = hotReload,
            )
            if (d.reason == "hot-reload") {
                Lg.i(TAG, "crash-guard skip: hot-reload")
                return false
            }
            val edit = prefs.edit()
                .putString(Prefs.KEY_CRASH_GUARD_PIDS, d.ledger)
                .putLong(Prefs.KEY_CRASH_GUARD_APK_STAMP, d.apkStamp)
            if (d.forceMasterOff) edit.putBoolean(Prefs.KEY_MASTER, false)
            edit.commit()
            if (d.block) {
                Lg.e(TAG, "crash-guard: ${d.reason}, master forced OFF")
            } else {
                Lg.i(TAG, "crash-guard ${d.reason}")
            }
            d.block
        } catch (t: Throwable) {
            Lg.e(TAG, "crash-guard failed ${t.javaClass.simpleName}: ${t.message}, continue install")
            false
        }
    }

    /**
     * 模块自身 APK 的 lastUpdateTime（装机即变）；取不到返回 0 = 不启用宽限，守卫按原口径工作。
     * 宿主进程内 processContext 拿到的是宿主 context，故模块包名写死常量而非 ctx.packageName。
     */
    private fun moduleApkStamp(module: XposedEntry): Long = try {
        val ctx = Lg.processContext()
        if (ctx == null) {
            0L
        } else {
            ctx.packageManager.getPackageInfo(PACKAGE_SELF, 0).lastUpdateTime
        }
    } catch (t: Throwable) {
        Lg.i(TAG, "crash-guard apk stamp unavailable ${t.javaClass.simpleName}")
        0L
    }

    /**
     * 逐个装载：每项独立兜异常，一项失败只降级该项并打栈，不连坐后面的项。
     *
     * 顺序直调时任一项抛异常会静默中断整条链（框架侧吞栈），后续功能全部装不上。
     */
    private fun installStep(name: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            Lg.e(TAG, "install-step-fail $name ${t.javaClass.name}: ${t.message}", t)
        }
    }

    private fun installHooks(classLoader: ClassLoader) {
        // 仅 SystemUI 进程：Hook B 绘制替换 / Hook C 场景过滤（占位实现；类名/方法名来自旧版反编译，待新版核对）。
        // Hook A（BlurService 端原图注入）已随 [2026-08-13 用户决定] 全移除——系统模糊管线原样，
        // 玻璃源 = SystemUI 端每元素背景源（BlurDrawHook 内 captureDisplay）。
        installStep("BlurDrawHook") { BlurDrawHook.install(this, classLoader) }
        // 锁屏玻璃（时钟/卡片/圆钮）：PR #3 DreamGlass 方案，RenderEffect 合成期挂载
        installStep("KeyguardGlassHook") { KeyguardGlassHook.install(this, classLoader) }
        // [spec/81 2026-09-17 停用] 锁屏时钟玻璃化：真机品红二分实证 `View.draw` hook 未命中
        // 时钟数字 View（数字仍是系统原样），接管本身没发生 → 保持停用，设置页入口已移除。
        // LockScreenClockHook.install(this, classLoader)
        installStep("SceneFilter") { SceneFilter.install(this, classLoader) }
        // 状态栏：显隐 / 边距 / 排序 / 三合一（内部按功能逐项门控）
        installStep("StatusBarHook") { StatusBarHook.install(this, classLoader) }
        // alive 息屏衔接进度泵（空壳计数器，验证链）
        installStep("AodHandoffHook") { AodHandoffHook.install(this, classLoader) }
        // 桌面时钟锚点接收（锁屏→桌面平移的 HOME 锚点来源）
        installStep("DesktopClockAnchorBridge") { DesktopClockAnchorBridge.install(this, classLoader) }
        // 状态栏时钟矩形（HOME 锚点第二优先级，进程内直取）
        installStep("StatusBarClockAnchorHook") { StatusBarClockAnchorHook.install(this, classLoader) }
        // 锁屏时钟高度基准（HOME 锚点缩放的分子分母）
        installStep("LockClockHeightHook") { LockClockHeightHook.install(this, classLoader) }
        // 三态转场舞台：把自绘遮罩层挂进 AOD 根布局，消费求值桥输出
        installStep("AodStageHook") {
            com.coloros16.liquidglass.hook.aod.AodStageHook.install(this, classLoader)
        }
        // 锁屏↔桌面边沿：系统无统一进度泵，用锁屏显隐/退场动画起内部时间轴
        installStep("KeyguardEdgeHook") {
            com.coloros16.liquidglass.hook.aod.KeyguardEdgeHook.install(this, classLoader)
        }
        // [spec/82] ⛔ 曾有 display-root 通道探针挂在这里，**已删除** —— 见 DisplayRootChannel 头部说明：
        // 它在屏幕暗/AOD 时会连续 120 秒每 5 秒抓一次整屏，与 UI 合成抢 SF，
        // 造成「进桌面卡顿」「SystemUI 重启后异常」。通道本身已验证通过，不再需要常驻探针。
    }

    // ------------------------------------------------------------ [doc/spec/110] 热重载 classLoader 解析链 ------------------------------------------------------------
    // onHotReloaded 跑在**新加载的类**上 ⇒ 新类静态为空，onPackageLoaded 存的缓存多半取不到，
    // 兜底链按「宿主线程 contextClassLoader → 宿主已知 Application 类」逐级退化。
    // [2026-10-13 修复] 实测三级全空 → KEEP OLD HOOKS（新代码永远进不了进程）：
    // ① 新类静态缓存必空 ② TCCL 在 Drip 重载线程上不可靠 ③ Class.forName 用的还是同一个不可靠 TCCL。
    // 现把 **①.5 旧 hook 反查**插到最前：HookHandle.getExecutable().declaringClass.classLoader ——
    // hook 目标方法声明在宿主类（com.android.systemui.* / com.oplus.*）里，其 classLoader 就是宿主
    // 本尊，不依赖任何静态字段或线程状态（boot 类返回 null 由 firstNotNullOfOrNull 自动跳过）。

    private fun resolveClassLoader(pkg: String, param: HotReloadedParam): ClassLoader? {
        cachedClassLoader(pkg)?.let { return it }
        param.oldHookHandles.firstNotNullOfOrNull { h ->
            try {
                h.executable?.declaringClass?.classLoader
            } catch (t: Throwable) {
                null
            }
        }?.let { return it }
        try {
            Thread.currentThread().contextClassLoader?.let { return it }
        } catch (t: Throwable) {
        }
        return try {
            val tcl = Thread.currentThread().contextClassLoader
            val candidates = when (pkg) {
                // 新名在前旧名在后（与 SystemUiLifecycle.resolveOnCreate 同序）
                PACKAGE_SYSTEMUI -> arrayOf(
                    "com.android.systemui.application.impl.SystemUIApplicationImpl",
                    "com.android.systemui.SystemUIApplication",
                )
                PACKAGE_LAUNCHER -> arrayOf("com.android.launcher3.LauncherApplication")
                else -> emptyArray<String>()
            }
            candidates.firstNotNullOfOrNull { name ->
                try {
                    Class.forName(name, false, tcl)?.classLoader
                } catch (t: Throwable) {
                    Lg.w(TAG, "classLoader candidate miss: $name")
                    null
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun cacheClassLoader(pkg: String, cl: ClassLoader) {
        cachedLoaders[pkg] = cl
    }

    private fun cachedClassLoader(pkg: String): ClassLoader? = cachedLoaders[pkg]

    companion object {
        /** SystemUI 进程（通知中心/控制中心）：Hook B 绘制替换 / Hook C 场景过滤。
         *  [2026-08-13 用户决定] BlurService 进程（com.oplus.blur）已全移除干预，不再有 PACKAGE_BLUR 常量。 */
        const val PACKAGE_SYSTEMUI = "com.android.systemui"
        /** [spec/10] Launcher 进程（桌面）：文件夹背景液态玻璃化（OplusPreviewBackground 闭合图标 +
         *  Folder.dispatchDraw 打开卡片）。与 SystemUI 共用同一套 posteffect drawBlurShader hook。 */
        const val PACKAGE_LAUNCHER = "com.android.launcher"
        /** 相册进程：照片页/按钮的视图背景模糊组件登记（AppGlassHook A 族）。 */
        const val PACKAGE_GALLERY = "com.coloros.gallery3d"
        /** 计算器进程：对话框/弹出菜单窗口模糊组件登记（AppGlassHook B 族）。 */
        const val PACKAGE_CALCULATOR = "com.coloros.calculator"
        /** 智能侧边栏进程：侧边栏面板窗口模糊组件登记（AppGlassHook B 族）。 */
        const val PACKAGE_SMART_SIDEBAR = "com.coloros.smartsidebar"
        /** 速览（负一屏）进程：卡片/控件窗口模糊组件登记（AppGlassHook B 族）。 */
        const val PACKAGE_ASSISTANT_SCREEN = "com.coloros.assistantscreen"
        /** 全局搜索进程：COUI 长按菜单/弹窗窗口模糊组件登记（AppGlassHook B 族）。 */
        const val PACKAGE_QUICK_SEARCH_BOX = "com.heytap.quicksearchbox"

        const val PACKAGE_DEMO = "com.coloros16.liquidglass.demo"
        /** 模块自身包名（守卫读自己的 APK 更新时间用；宿主进程内 ctx.packageName 是宿主）。 */
        const val PACKAGE_SELF = "com.coloros16.liquidglass"
        /** [doc/spec/110] 宿主 classLoader 缓存（onPackageLoaded 存 / onHotReloaded 读）。 */
        private val cachedLoaders = java.util.concurrent.ConcurrentHashMap<String, ClassLoader>()
        /** [doc/spec/110] 模块版本标记（热重载完成日志用，便于 daemon 侧对账 loaded version）。 */
        private const val MODULE_VERSION = "spec-113"
        private const val TAG = "LiquidGlass"
    }
}
