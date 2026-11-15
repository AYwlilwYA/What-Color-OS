package com.coloros16.liquidglass.hook

import com.coloros16.liquidglass.log.Lg
import java.util.concurrent.ConcurrentHashMap

/** 倾斜挂载路径：16.x 六挂点 vs 17.3.12 原生 zuyqa 参数注入。 */
enum class TiltPath { LEGACY_16, NATIVE_ZUYQA }

/** 大版本架构族标记（按宿主 versionCode 分段派生）。 */
enum class ArchFamily { C16, C17, OTHER }

/** 类名解析策略引用（候选链/指纹链本体不动，profile 只带指认）。 */
enum class ClassNameResolver { LEGACY_NAME_CHAIN, FINGERPRINT_PROBE, CANDIDATE_CHAIN, FIXED_CLASS_NAMES }

/**
 * posteffect 管线架构族：接屏前一代（C15，BlurDrawable 直接继承 Drawable、无 agsl 包）、
 * 旧架构用 agsl.BlurDrawableShader + getBlurShader*，
 * 新架构用 agsl.DrawableShader + getDrawableShader*（同时才有 BlendDrawable）。
 */
enum class PosteffectArch { PRE_AGSL, LEGACY, MODERN, UNKNOWN }

/**
 * 抓屏类族：Android 14~16 用 android.window.ScreenCapture，
 * Android 17 迁到 android.window.ScreenCaptureInternal；更早系统两者皆无。
 */
enum class CaptureRev { LEGACY, INTERNAL, UNSUPPORTED, UNKNOWN }

/**
 * 宿主 profile：代码常量表条目。
 * launcherMainHook / launcherAnimHook / tiltPath 三个开关仅 com.android.launcher 消费点读取，
 * 其余宿主条目填中性值、不参与其安装决策。
 */
data class Profile(
    val name: String,
    val launcherMainHook: Boolean,
    val launcherAnimHook: Boolean,
    val tiltPath: TiltPath,
    val classNameResolver: ClassNameResolver,
    val archFamily: ArchFamily,
    val posteffectArch: PosteffectArch = PosteffectArch.UNKNOWN,
    val captureRev: CaptureRev = CaptureRev.UNKNOWN,
)

/** 小版本点：宿主 versionCode 精确点 → profile。 */
data class MinorPoint(val versionCode: Long, val profile: Profile)

/** 每宿主条目表：大版本除数（按该宿主 versionCode 形态显式写）+ 段级 profile + 升序点表 + 段级默认。 */
data class VersionSpec(
    val pkg: String,
    val majorDivisor: Long,
    val segmentProfiles: Map<Int, Profile>,
    val defaultProfile: Profile,
    val minorPoints: List<MinorPoint>,
)

/** 查池命中方式。 */
enum class MatchKind { Exact, Nearest, Segment, VerUnknown }

/** 查询结果：静态 profile 与运行时 patch 分离携带。 */
data class VDecision(
    val major: Int,
    val point: Long,
    val match: MatchKind,
    val profile: Profile,
    val patch: Map<String, Any?>,
)

/**
 * 状态栏结构档：只描述视图/槽结构差异（layoutRev），不承载任何功能开关。
 * 功能开关一律走 Prefs；本档只回答「这个宿主的状态栏长什么样」。
 */
data class StatusBarProfile(
    val layoutRev: Int,
    val hasEndSideContent: Boolean,
    val hasCompositeMobile: Boolean,
    val hasCompositeWifi: Boolean,
    val mobileSlot: String,
    val wifiSlot: String,
    /** 状态栏根视图是否由 CollapsedStatusBarFragment.onViewCreated 交付（Compose 承载版为 false）。 */
    val hasFragmentEntry: Boolean = true,
    /** 时钟 View 是否带 setShowSecondsAndUpdate / mShowSeconds（缺失则时钟秒降级）。 */
    val hasClockSeconds: Boolean = true,
)

/** 状态栏小版本点：宿主 versionCode → 结构档。 */
data class StatusBarMinorPoint(val versionCode: Long, val profile: StatusBarProfile)

/** 状态栏查询结果（匹配档 + 结构档 + 运行时补丁快照）。 */
data class StatusBarDecision(
    val match: MatchKind,
    val profile: StatusBarProfile,
    val patch: Map<String, Any?>,
)

/** profile 常量表。 */
object Profiles {
    val LAUNCHER_PRE17 = Profile(
        name = "launcher-pre17",
        launcherMainHook = true,
        launcherAnimHook = true,
        tiltPath = TiltPath.LEGACY_16,
        classNameResolver = ClassNameResolver.LEGACY_NAME_CHAIN,
        archFamily = ArchFamily.C16,
        captureRev = CaptureRev.LEGACY,
    )
    val LAUNCHER_17PLUS = Profile(
        name = "launcher-17plus",
        launcherMainHook = true,
        launcherAnimHook = false,
        tiltPath = TiltPath.NATIVE_ZUYQA,
        classNameResolver = ClassNameResolver.FINGERPRINT_PROBE,
        archFamily = ArchFamily.C17,
        captureRev = CaptureRev.INTERNAL,
    )
    val SYSTEMUI_15 = Profile(
        name = "systemui-15-pre-agsl",
        launcherMainHook = true,
        launcherAnimHook = true,
        tiltPath = TiltPath.LEGACY_16,
        classNameResolver = ClassNameResolver.CANDIDATE_CHAIN,
        archFamily = ArchFamily.OTHER,
        posteffectArch = PosteffectArch.PRE_AGSL,
        captureRev = CaptureRev.LEGACY,
    )
    val SYSTEMUI_16 = Profile(
        name = "systemui-16-legacy",
        launcherMainHook = true,
        launcherAnimHook = true,
        tiltPath = TiltPath.LEGACY_16,
        classNameResolver = ClassNameResolver.CANDIDATE_CHAIN,
        archFamily = ArchFamily.C16,
        posteffectArch = PosteffectArch.LEGACY,
        captureRev = CaptureRev.LEGACY,
    )
    val SYSTEMUI_16_MODERN = Profile(
        name = "systemui-16-modern",
        launcherMainHook = true,
        launcherAnimHook = true,
        tiltPath = TiltPath.LEGACY_16,
        classNameResolver = ClassNameResolver.CANDIDATE_CHAIN,
        archFamily = ArchFamily.C16,
        posteffectArch = PosteffectArch.MODERN,
        captureRev = CaptureRev.LEGACY,
    )
    val SYSTEMUI_17 = Profile(
        name = "systemui-17",
        launcherMainHook = true,
        launcherAnimHook = true,
        tiltPath = TiltPath.LEGACY_16,
        classNameResolver = ClassNameResolver.CANDIDATE_CHAIN,
        archFamily = ArchFamily.C17,
        posteffectArch = PosteffectArch.MODERN,
        captureRev = CaptureRev.INTERNAL,
    )
    val APPGLASS_16 = Profile(
        name = "appglass-16",
        launcherMainHook = true,
        launcherAnimHook = true,
        tiltPath = TiltPath.LEGACY_16,
        classNameResolver = ClassNameResolver.FIXED_CLASS_NAMES,
        archFamily = ArchFamily.C16,
        captureRev = CaptureRev.LEGACY,
    )
    val APPGLASS_17 = Profile(
        name = "appglass-17",
        launcherMainHook = true,
        launcherAnimHook = true,
        tiltPath = TiltPath.LEGACY_16,
        classNameResolver = ClassNameResolver.FIXED_CLASS_NAMES,
        archFamily = ArchFamily.C17,
        captureRev = CaptureRev.INTERNAL,
    )
    val APPGLASS_11 = Profile(
        name = "appglass-11",
        launcherMainHook = true,
        launcherAnimHook = true,
        tiltPath = TiltPath.LEGACY_16,
        classNameResolver = ClassNameResolver.FIXED_CLASS_NAMES,
        archFamily = ArchFamily.OTHER,
    )
    val APPGLASS_DEFAULT = Profile(
        name = "appglass-default",
        launcherMainHook = true,
        launcherAnimHook = true,
        tiltPath = TiltPath.LEGACY_16,
        classNameResolver = ClassNameResolver.FIXED_CLASS_NAMES,
        archFamily = ArchFamily.OTHER,
    )
    val UNREGISTERED = Profile(
        name = "unregistered",
        launcherMainHook = true,
        launcherAnimHook = true,
        tiltPath = TiltPath.LEGACY_16,
        classNameResolver = ClassNameResolver.FIXED_CLASS_NAMES,
        archFamily = ArchFamily.OTHER,
    )
}

/**
 * 版本池：宿主 versionCode 大版本分段 + 小版本点表查表。
 *
 * 匹配顺序：小版本精确命中 → 小版本 floor（≤ver 最近点，高于表取最大、低于表取最小）
 * → 点表为空走大版本段 profile（段未中走段级默认，不许空档无主）。
 * 运行时补丁（架构探测）按宿主单独存，不覆盖静态命中来源。
 */
object VersionPool {

    const val PKG_SYSTEMUI = "com.android.systemui"
    const val PATCH_LEGACY_POSTEFFECT_ARCH = "legacyPosteffectArch"

    private const val TAG = "lg-hook"
    private const val THROTTLE_MS = 2000L

    private val SPECS: List<VersionSpec> = listOf(
        VersionSpec(
            pkg = "com.android.launcher",
            majorDivisor = 10_000_000L,
            segmentProfiles = mapOf(
                16 to Profiles.LAUNCHER_PRE17,
                17 to Profiles.LAUNCHER_17PLUS,
            ),
            defaultProfile = Profiles.LAUNCHER_PRE17,
            minorPoints = listOf(
                MinorPoint(160040028L, Profiles.LAUNCHER_PRE17),
                MinorPoint(160060011L, Profiles.LAUNCHER_PRE17),
                MinorPoint(170030012L, Profiles.LAUNCHER_17PLUS),
            ),
        ),
        VersionSpec(
            pkg = PKG_SYSTEMUI,
            majorDivisor = 10_000L,
            segmentProfiles = mapOf(
                16 to Profiles.SYSTEMUI_16,
                17 to Profiles.SYSTEMUI_17,
            ),
            defaultProfile = Profiles.SYSTEMUI_16,
            minorPoints = listOf(
                MinorPoint(159913L, Profiles.SYSTEMUI_15),
                MinorPoint(160009L, Profiles.SYSTEMUI_16),
                MinorPoint(160014L, Profiles.SYSTEMUI_16),
                MinorPoint(169906L, Profiles.SYSTEMUI_16),
                MinorPoint(169912L, Profiles.SYSTEMUI_16_MODERN),
                MinorPoint(179902L, Profiles.SYSTEMUI_17),
            ),
        ),
        VersionSpec(
            pkg = "com.coloros.gallery3d",
            majorDivisor = 1_000_000L,
            segmentProfiles = mapOf(16 to Profiles.APPGLASS_16),
            defaultProfile = Profiles.APPGLASS_DEFAULT,
            minorPoints = emptyList(),
        ),
        VersionSpec(
            pkg = "com.coloros.calculator",
            majorDivisor = 1_000_000L,
            segmentProfiles = mapOf(16 to Profiles.APPGLASS_16),
            defaultProfile = Profiles.APPGLASS_DEFAULT,
            minorPoints = emptyList(),
        ),
        VersionSpec(
            pkg = "com.coloros.smartsidebar",
            majorDivisor = 10_000_000L,
            segmentProfiles = mapOf(16 to Profiles.APPGLASS_16),
            defaultProfile = Profiles.APPGLASS_DEFAULT,
            minorPoints = emptyList(),
        ),
        VersionSpec(
            pkg = "com.coloros.assistantscreen",
            majorDivisor = 10_000_000L,
            segmentProfiles = mapOf(17 to Profiles.APPGLASS_17),
            defaultProfile = Profiles.APPGLASS_DEFAULT,
            minorPoints = emptyList(),
        ),
        VersionSpec(
            pkg = "com.heytap.quicksearchbox",
            majorDivisor = 100_000L,
            segmentProfiles = mapOf(11 to Profiles.APPGLASS_11),
            defaultProfile = Profiles.APPGLASS_DEFAULT,
            minorPoints = emptyList(),
        ),
    )

    private val UNREGISTERED_SPEC = VersionSpec(
        pkg = "",
        majorDivisor = 10_000_000L,
        segmentProfiles = emptyMap(),
        defaultProfile = Profiles.UNREGISTERED,
        minorPoints = emptyList(),
    )

    private val specByPkg: Map<String, VersionSpec> = SPECS.associateBy { it.pkg }

    /** 状态栏结构档 v1：16.99.12 实测结构（右侧容器 + 复合移动/ Wi-Fi 视图）。 */
    private val STATUSBAR_V1 = StatusBarProfile(
        layoutRev = 1,
        hasEndSideContent = true,
        hasCompositeMobile = true,
        hasCompositeWifi = true,
        mobileSlot = "mobile",
        wifiSlot = "wifi",
    )

    /** 17.99.02 档：状态栏根改 Compose 承载（无 Fragment 入口）、时钟无秒字段方法，其余结构同 v1。 */
    private val STATUSBAR_V1_COMPOSE_ENTRY = STATUSBAR_V1.copy(
        hasFragmentEntry = false,
        hasClockSeconds = false,
    )

    /** 状态栏小版本点表（差异才加点；未命中走最近点，与宿主池同一语义；升序）。 */
    private val STATUSBAR_POINTS: List<StatusBarMinorPoint> = listOf(
        StatusBarMinorPoint(159913L, STATUSBAR_V1),
        StatusBarMinorPoint(160009L, STATUSBAR_V1),
        StatusBarMinorPoint(160014L, STATUSBAR_V1),
        StatusBarMinorPoint(169906L, STATUSBAR_V1),
        StatusBarMinorPoint(169912L, STATUSBAR_V1),
        StatusBarMinorPoint(179902L, STATUSBAR_V1_COMPOSE_ENTRY),
    )

    /** 状态栏小版本点表（单测结构校验用）。 */
    val statusBarPoints: List<StatusBarMinorPoint>
        get() = STATUSBAR_POINTS

    /**
     * 状态栏结构查询唯一入口：命中语义与 [query] 一致（精确 → 最近点 → 段默认），
     * ver < 0（读取失败）取最小已知点并标 [MatchKind.VerUnknown]。
     * 运行时门禁真值以补丁优先（[notePatch] 写 "statusBarLayout"）。
     */
    fun statusBar(pkg: String, ver: Long): StatusBarDecision {
        val patch = snapshotPatches(pkg)
        val decision = if (ver < 0) {
            StatusBarDecision(MatchKind.VerUnknown, STATUSBAR_POINTS.first().profile, patch)
        } else {
            val exact = STATUSBAR_POINTS.firstOrNull { it.versionCode == ver }
            if (exact != null) {
                StatusBarDecision(MatchKind.Exact, exact.profile, patch)
            } else {
                val floor = STATUSBAR_POINTS.lastOrNull { it.versionCode <= ver }
                    ?: STATUSBAR_POINTS.first()
                StatusBarDecision(MatchKind.Nearest, floor.profile, patch)
            }
        }
        logStatusBarQuery(pkg, ver, decision)
        return decision
    }

    private fun logStatusBarQuery(pkg: String, ver: Long, d: StatusBarDecision) {
        try {
            val now = System.currentTimeMillis()
            val key = "sb:$pkg"
            val last = throttleAt[key]
            if (last != null && now - last < THROTTLE_MS) return
            throttleAt[key] = now
            if (throttleAt.size > 32) throttleAt.clear()
            val patchTxt = if (d.patch.isEmpty()) "none" else d.patch.entries.joinToString(",") { "${it.key}=${it.value}" }
            Lg.i(TAG, "lg-vermatch-sb pkg=$pkg ver=$ver match=${d.match} rev=${d.profile.layoutRev} patch=$patchTxt")
        } catch (_: Throwable) {
        }
    }

    private val patchesByPkg = ConcurrentHashMap<String, ConcurrentHashMap<String, Any?>>()

    private val throttleAt = ConcurrentHashMap<String, Long>()

    /** 池内全部宿主条目（单测结构校验用）。 */
    val specs: List<VersionSpec>
        get() = SPECS

    /** 读宿主 versionCode（失败 -1），版本读取统一收口在池本体。 */
    fun readVersion(pkg: String): Long = HostVersion.hostVersionCode(pkg)

    /** 版本查询唯一入口；宿主无关纯查表，附带该宿主运行时补丁快照。 */
    fun query(pkg: String, ver: Long): VDecision {
        val spec = specByPkg[pkg] ?: UNREGISTERED_SPEC
        val decision = decide(spec, ver, snapshotPatches(pkg))
        logQuery(pkg, ver, decision)
        return decision
    }

    /** 运行时补丁层写回（架构探测等），不覆盖静态命中来源；value 为 null 视为清除该键。 */
    fun notePatch(pkg: String, key: String, value: Any?) {
        val m = patchesByPkg.getOrPut(pkg) { ConcurrentHashMap<String, Any?>() }
        if (value == null) m.remove(key) else m[key] = value
    }

    /** 取某宿主的运行时补丁快照。 */
    fun patches(pkg: String): Map<String, Any?> = snapshotPatches(pkg)

    /** 条目表结构校验：点表必须严格升序（含无重复/无重叠）、除数与段号必须为正。 */
    fun validate(spec: VersionSpec): List<String> {
        val errs = mutableListOf<String>()
        val tag = spec.pkg.ifEmpty { "<unregistered>" }
        if (spec.majorDivisor <= 0) errs += "$tag: majorDivisor must be > 0"
        if (spec.minorPoints.any { it.versionCode < 0 }) errs += "$tag: minorPoint must be >= 0"
        for (i in 1 until spec.minorPoints.size) {
            if (spec.minorPoints[i].versionCode <= spec.minorPoints[i - 1].versionCode) {
                errs += "$tag: minorPoints must be strictly ascending at ${spec.minorPoints[i].versionCode}"
            }
        }
        if (spec.segmentProfiles.keys.any { it < 0 }) errs += "$tag: segment major must be >= 0"
        return errs
    }

    private fun decide(spec: VersionSpec, ver: Long, patch: Map<String, Any?>): VDecision {
        if (ver < 0) {
            val first = spec.minorPoints.firstOrNull()
            val point = first?.versionCode ?: 0L
            val profile = first?.profile ?: spec.defaultProfile
            val major = if (first != null) (point / spec.majorDivisor).toInt() else 0
            return VDecision(major, point, MatchKind.VerUnknown, profile, patch)
        }
        val major = (ver / spec.majorDivisor).toInt()
        spec.minorPoints.find { it.versionCode == ver }?.let {
            return VDecision(major, it.versionCode, MatchKind.Exact, it.profile, patch)
        }
        if (spec.minorPoints.isNotEmpty()) {
            val floor = spec.minorPoints.lastOrNull { it.versionCode <= ver } ?: spec.minorPoints.first()
            return VDecision(major, floor.versionCode, MatchKind.Nearest, floor.profile, patch)
        }
        val segment = spec.segmentProfiles[major] ?: spec.defaultProfile
        return VDecision(major, 0L, MatchKind.Segment, segment, patch)
    }

    private fun snapshotPatches(pkg: String): Map<String, Any?> {
        val m = patchesByPkg[pkg] ?: return emptyMap()
        return HashMap(m)
    }

    private fun logQuery(pkg: String, ver: Long, d: VDecision) {
        try {
            val now = System.currentTimeMillis()
            val last = throttleAt[pkg]
            if (last != null && now - last < THROTTLE_MS) return
            throttleAt[pkg] = now
            if (throttleAt.size > 16) throttleAt.clear()
            val patchTxt = if (d.patch.isEmpty()) {
                "none"
            } else {
                d.patch.entries.joinToString(",") { "${it.key}=${it.value}" }
            }
            Lg.i(
                TAG,
                "lg-vermatch pkg=$pkg ver=$ver major=${d.major} point=${d.point} " +
                    "match=${d.match} profile=${d.profile.name} patch=$patchTxt"
            )
        } catch (t: Throwable) {
            // 日志失败不影响查表结果
        }
    }
}
