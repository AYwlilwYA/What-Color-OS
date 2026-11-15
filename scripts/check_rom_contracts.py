"""Check actual DEX field/method contracts; no device connection or APK upload.

Adapted from DreamGlass scripts/check_rom_contracts.py for this repository:
- works under `python -I` (isolated mode does not put the script dir on sys.path)
-素材路径参数化 + 本仓默认候选（可零参跑通）
- 模块类清单 / scope 清单换成本仓结构，宿主 ROM 断言逐条原样保留

Usage:
  python -I scripts/check_rom_contracts.py
  python -I scripts/check_rom_contracts.py --systemui FILE --launcher FILE --module FILE [--framework FILE]
"""
import argparse, re, sys, unittest, zipfile, hashlib
from pathlib import Path

sys.dont_write_bytecode = True  # 别在仓库里留 __pycache__
sys.path.insert(0, str(Path(__file__).resolve().parent))
from dex_inspector import scan

ROOT = Path(__file__).resolve().parents[1]

SYSTEMUI_CANDIDATES = [
    r"E:\Downloads\系统界面_17.99.02.apk.1",
    r"E:\Downloads\dc_sysui_179902.apk",
    str(ROOT / "reference/systemui-16.99.12/SystemUI-16.99.12.apk"),
]
LAUNCHER_CANDIDATES = [
    r"E:\Downloads\系统桌面_17.3.12.apk.1",
    r"E:\Downloads\dc_17312.apk",
]
MODULE_CANDIDATES = [
    str(ROOT / "app/build/outputs/apk/release/app-release.apk"),
    str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"),
]
SCOPES = {
    "com.android.systemui",
    "com.android.launcher",
    "com.coloros.gallery3d",
    "com.coloros.calculator",
    "com.coloros.smartsidebar",
    "com.coloros.assistantscreen",
    "com.heytap.quicksearchbox",
    "com.coloros16.liquidglass.demo",
}


def first_existing(candidates):
    found = [Path(p) for p in candidates if p and Path(p).exists()]
    if not found:
        return None
    # 多个候选都在时取最新的那份（release/debug 并存时别拿到陈旧产物）
    return str(max(found, key=lambda p: p.stat().st_mtime))


p = argparse.ArgumentParser()
p.add_argument("--systemui", default=first_existing(SYSTEMUI_CANDIDATES))
p.add_argument("--launcher", default=first_existing(LAUNCHER_CANDIDATES))
p.add_argument("--module", default=first_existing(MODULE_CANDIDATES))
p.add_argument("--framework")
args = p.parse_args()
for name in ("systemui", "launcher", "module"):
    if not getattr(args, name):
        p.error(f"--{name} 未找到（先构建 APK 或显式传路径）")
system = scan(args.systemui)["classes"]
launcher = scan(args.launcher)["classes"]
module = scan(args.module)["classes"]
framework = scan(args.framework)["classes"] if args.framework else None


class RomContracts(unittest.TestCase):
    def test_launcher_fields_match_bytecode_and_reflection(self):
        cs = launcher["com.oplus.posteffect.drawable.b"]
        source = (ROOT / "app/src/main/kotlin/com/coloros16/liquidglass/hook/LauncherHook.kt").read_text(
            encoding="utf-8"
        )
        contracts = {
            "FIELD_BLUR_SHADER_PAINT_17": ("M", "Landroid/graphics/Paint;"),
            "FIELD_PAINT_17": ("f", "Landroid/graphics/Paint;"),
            "FIELD_RENDER_NODE_17": ("h", "Landroid/graphics/RenderNode;"),
            "FIELD_DRAWABLE_PATH_17": ("i", "Landroid/graphics/Path;"),
            "FIELD_PAINT_ALPHA_17": ("F", "I"),
            "FIELD_ENABLE_BLUR_SHADER_17": ("a", "Z"),
        }
        for key, (name, type_) in contracts.items():
            found = re.search(r"const val " + key + r' = "([^"]+)"', source)
            self.assertIsNotNone(found, f"{key} 未在 LauncherHook.kt 找到常量定义")
            self.assertEqual(found.group(1), name, f"{key} 与字节码不符（jadx 伪名待第二阶段修正）")
            self.assertIn(name + ":" + type_, cs["fields"])
        self.assertIn("a(Lcom/oplus/posteffect/drawable/d;)V", cs["methods"])
        self.assertIn("w:Z", cs["fields"])
        self.assertEqual(launcher["com.oplus.posteffect.drawable.d"]["super"], "Lcom/oplus/posteffect/drawable/b;")

    def test_systemui_core_signatures(self):
        targets = {
            "com.oplus.posteffect.drawable.BaseDrawable": [
                "drawBlurShader(Landroid/graphics/Canvas;)V",
                "onDrawContent(Landroid/graphics/Canvas;)V",
                "getDrawableShaderPaint()Landroid/graphics/Paint;",
            ],
            "com.oplus.posteffect.drawable.BlendDrawable": ["onDrawContent(Landroid/graphics/Canvas;)V"],
            "com.oplus.posteffect.agsl.DrawableShader": ["getShaderOrNull()Landroid/graphics/RuntimeShader;"],
            "com.oplus.posteffect.manager.BlurDrawableManager": [
                "onBlurReady(Ljava/util/List;Landroid/hardware/HardwareBuffer;IFLandroid/graphics/Rect;)V"
            ],
        }
        for cls, methods in targets.items():
            for method in methods:
                self.assertIn(method, system[cls]["methods"])

    def test_systemui_lifecycle_owner_and_content_draw_bridge(self):
        app = system["com.android.systemui.application.impl.SystemUIApplicationImpl"]
        self.assertIn("onCreate()V", app["methods"])
        base = system["com.oplus.posteffect.drawable.BaseDrawable"]
        self.assertIn("getPaint()Landroid/graphics/Paint;", base["methods"])
        self.assertIn("drawBlurShader(Landroid/graphics/Canvas;)V", base["methods"])
        blend = system["com.oplus.posteffect.drawable.BlendDrawable"]
        self.assertIn("onDrawContent(Landroid/graphics/Canvas;)V", blend["methods"])
        for cls in ["hook.SystemUiLifecycle", "hook.SystemUiInstallGate", "hook.ContentDrawBridge"]:
            fqcn = "com.coloros16.liquidglass." + cls
            self.assertTrue(fqcn in module, f"{fqcn} 不在模块 DEX 里")
        # 宿主 fixture 只进 JVM 测试，绝不随 APK 出厂
        self.assertNotIn("com.android.systemui.application.impl.SystemUIApplicationImpl", module)
        self.assertNotIn("com.android.systemui.SystemUIApplication", module)

    def test_volume_mask_host_contract(self):
        wrapper = system["com.oplusos.systemui.common.blurability.platformblur.PlatformBlurDrawable"]
        self.assertIn("getHostViewName:Lkotlin/jvm/functions/Function0;", wrapper["fields"])
        self.assertIn("blurMaskColor:I", wrapper["fields"])
        self.assertIn("draw(Landroid/graphics/Canvas;)V", wrapper["methods"])
        helper = system["com.oplusos.systemui.common.blurability.platformblur.PlatformBlurHelper"]
        self.assertIn("hostView:Landroid/view/View;", helper["fields"])
        proxy = system["com.oplusos.systemui.common.blurability.ViewBlurProxy"]
        self.assertIn("getView()Landroid/view/View;", proxy["methods"])
        self.assertIn(
            "getBlurDrawable(Landroid/graphics/drawable/Drawable;)Landroid/graphics/drawable/Drawable;",
            proxy["methods"],
        )
        self.assertTrue(
            "com.coloros16.liquidglass.hook.VolumeMaskGuard" in module,
            "VolumeMaskGuard 不在模块 DEX 里",
        )
        guard = module["com.coloros16.liquidglass.hook.VolumeMaskGuard"]
        self.assertIn("protectNativeContent(Ljava/lang/Object;Ljava/lang/Object;)V", guard["methods"])
        self.assertIn("preservesContent(Ljava/lang/Object;)Z", guard["methods"])

    def test_cos17_seekbar_factory_and_actual_render_target(self):
        manager = system["com.oplus.systemui.qs.base.util.QsSeekBarBlurManager"]
        args = "(Landroid/view/View;Landroid/graphics/Rect;FFZZF)Landroid/graphics/drawable/Drawable;"
        self.assertIn("getSeekBarActiveDrawable" + args, manager["methods"])
        self.assertIn("getSeekBarBackground" + args, manager["methods"])
        self.assertNotIn(
            "getSeekBarActiveDrawable(Landroid/view/View;)Landroid/graphics/drawable/Drawable;",
            manager["methods"],
        )
        # 工厂包装层不是 drawBlurShader 的接收者：整条解包链都要在
        auto = system["com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable"]
        proxy = system["com.oplusos.systemui.common.blurability.ViewBlurProxy"]
        platform = system["com.oplusos.systemui.common.blurability.platformblur.PlatformBlurDrawable"]
        self.assertIn("getViewBlurProxy()Lcom/oplusos/systemui/common/blurability/ViewBlurProxy;", auto["methods"])
        self.assertIn(
            "getBlurDrawable(Landroid/graphics/drawable/Drawable;)Landroid/graphics/drawable/Drawable;",
            proxy["methods"],
        )
        self.assertIn("getBlurDrawable()Lcom/oplus/posteffect/BlurDrawable;", platform["methods"])
        name = "com.oplus.posteffect.BlurDrawable"
        guard = 0
        while name != "com.oplus.posteffect.drawable.BaseDrawable":
            self.assertLess(guard, 32, "BlurDrawable 继承链未到达 BaseDrawable")
            name = system[name]["super"][1:-1].replace("/", ".")
            guard += 1
        self.assertTrue(
            "com.coloros16.liquidglass.hook.QsSeekbarCompat" in module,
            "QsSeekbarCompat 不在模块 DEX 里",
        )

    def test_qs_surface_shapes_and_icon_background_contract(self):
        slider = system["com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar"]
        self.assertIn("drawActiveMixColorTrack(Landroid/graphics/Canvas;)V", slider["methods"])
        parent = system["com.coui.appcompat.seekbar.COUIVerticalSeekBar"]
        for field in [
            "mClipProgressRect:Landroid/graphics/Rect;",
            "mCurProgressRadius:F",
            "mBackgroundRoundCornerWeight:F",
        ]:
            self.assertIn(field, parent["fields"])
        smooth = system["com.oplus.posteffect.util.OplusPathAdapterCompatUtils"]
        self.assertIn("create(Landroid/graphics/Path;)Ljava/lang/Object;", smooth["methods"])
        self.assertIn(
            "addSmoothRoundRect(Ljava/lang/Object;Landroid/graphics/RectF;[FLandroid/graphics/Path$Direction;F)Z",
            smooth["methods"],
        )
        icon = system["com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSIconView"]
        self.assertIn("iconBgView:Landroid/widget/ImageView;", icon["fields"])
        for method in ["onAttachedToWindow()V", "onLayout(ZIIII)V", "updateIconBgDrawable()V"]:
            self.assertIn(method, icon["methods"])
        tile = system["com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne"]
        self.assertIn("com.oplus.systemui.qs.base.res.drawable.TileTransitionDrawable", system)
        self.assertIn(
            "draw(Landroid/graphics/Canvas;)V",
            system["com.oplus.systemui.qs.base.res.drawable.TileTransitionDrawable"]["methods"],
        )
        self.assertTrue(tile)
        for name in ["hook.QsSurfaceHook", "hook.QsSurfacePolicy"]:
            fqcn = "com.coloros16.liquidglass." + name
            self.assertTrue(fqcn in module, f"{fqcn} 不在模块 DEX 里")

    def test_module_entry_and_new_product_classes(self):
        for name in [
            "entry.XposedEntry",
            "hook.CaptureApi",
            "hook.SystemUiLifecycle",
            "hook.SystemUiInstallGate",
            "hook.ContentDrawBridge",
            "hook.QsSeekbarCompat",
            "hook.QsSurfaceHook",
            "hook.QsSurfacePolicy",
            "hook.VolumeMaskGuard",
            "hook.Cos17Contract",
            "hook.BlurDrawHook",
            "hook.LauncherHook",
            "hook.VersionPool",
            "liquidglass.LiquidGlassShader",
        ]:
            fqcn = "com.coloros16.liquidglass." + name
            self.assertTrue(fqcn in module, f"{fqcn} 不在模块 DEX 里")
        self.assertTrue("io.github.libxposed.api.XposedModule" not in module, "LibXposed 基类混进模块 DEX")
        # JVM fixture 不得进 APK
        self.assertTrue(
            "android.window.ScreenCaptureInternal" not in module,
            "ScreenCaptureInternal fixture 混进模块 DEX",
        )

    @unittest.skipUnless(args.framework, "Supply --framework for the actual connected ROM contract")
    def test_api37_capture_family_matches_window_manager(self):
        root = "android.window.ScreenCaptureInternal"
        self.assertNotIn("android.window.ScreenCapture$CaptureArgs", framework)
        self.assertIn(
            "createSyncCaptureListener()Landroid/window/ScreenCaptureInternal$SynchronousScreenCaptureListener;",
            framework[root]["methods"],
        )
        self.assertIn(
            "captureDisplay(Landroid/window/ScreenCaptureInternal$DisplayCaptureArgs;)Landroid/window/ScreenCaptureInternal$ScreenshotHardwareBuffer;",
            framework[root]["methods"],
        )
        self.assertIn(
            "setSize(II)Landroid/window/ScreenCaptureInternal$DisplayCaptureArgs$Builder;",
            framework[root + "$DisplayCaptureArgs$Builder"]["methods"],
        )
        self.assertIn(
            "captureDisplay(ILandroid/window/ScreenCaptureInternal$CaptureArgs;Landroid/window/ScreenCaptureInternal$ScreenCaptureListener;)V",
            framework["android.view.IWindowManager"]["methods"],
        )

    def test_apk_integrity_and_xposed_metadata(self):
        with zipfile.ZipFile(args.module) as z:
            self.assertIsNone(z.testzip())
            self.assertEqual(
                z.read("META-INF/xposed/java_init.list").decode().strip(),
                "com.coloros16.liquidglass.entry.XposedEntry",
            )
            scopes = {
                x.strip()
                for x in z.read("META-INF/xposed/scope.list").decode().splitlines()
                if x.strip() and not x.startswith("#")
            }
            self.assertEqual(scopes, SCOPES)
            self.assertIn("minApiVersion=102", z.read("META-INF/xposed/module.prop").decode())
            for n in z.namelist():
                if re.fullmatch(r"classes\d*\.dex", n):
                    b = z.read(n)
                    self.assertEqual(hashlib.sha1(b[32:]).digest(), b[12:32])


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0]], verbosity=2)
