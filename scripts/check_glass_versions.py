"""Glass-line per-version gate: assert posteffect family and anchor presence for extracted packages.

Usage:
  python -I scripts/check_glass_versions.py
  python -I scripts/check_glass_versions.py --systemui EXTRA.apk --launcher EXTRA.apk --plugins EXTRA.apk

Exit code 0 = every available package matches the table; 1 = drift found.
Missing optional packages are reported as SKIP (Downloads paths move often).
"""
import argparse
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from dex_inspector import scan

ROOT = Path(__file__).resolve().parents[1]

LEGACY_SYSTEMUI = [
    ROOT / "reference/apks/系统界面_16.00.09.apk",
    Path(r"E:/Downloads/系统界面_16.00.14.APK"),
    ROOT / "reference/apks/系统界面_16.99.06.apk",
]
MODERN_SYSTEMUI = [
    ROOT / "reference/systemui-16.99.12/SystemUI-16.99.12.apk",
    Path(r"E:/Downloads/系统界面_17.99.02.apk.1"),
]
OPTIONAL_SYSTEMUI = [ROOT / "reference/pdem30/systemui.apk"]
LAUNCHER_OLD = [ROOT / "reference/dc_16428.apk"]
LAUNCHER_NEW = [ROOT / "reference/dc_17312.apk"]
PLUGINS = [
    Path(r"E:/Downloads/SystemUIPlugin_16.000.002.apk.1"),
    ROOT / "reference/systemui-plugins-16.001.002/SystemUIPlugin-16.001.002.apk",
]

SYSTEMUI_CORE = {
    "com.oplus.posteffect.drawable.BaseDrawable": [
        "drawBlurShader(Landroid/graphics/Canvas;)V",
        "getPaint()Landroid/graphics/Paint;",
    ],
    "com.oplus.posteffect.manager.BlurDrawableManager": [],
    "com.oplusos.systemui.common.blurability.platformblur.PlatformBlurDrawable": [
        "draw(Landroid/graphics/Canvas;)V",
    ],
    "com.oplusos.systemui.common.blurability.ViewBlurProxy": [
        "getBlurDrawable(Landroid/graphics/drawable/Drawable;)Landroid/graphics/drawable/Drawable;",
    ],
    "com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable": [
        "getViewBlurProxy()Lcom/oplusos/systemui/common/blurability/ViewBlurProxy;",
    ],
    "com.oplus.systemui.statusbar.notification.row.NotificationBackgroundViewExtImp": [
        "draw(Landroid/graphics/Canvas;Landroid/graphics/drawable/Drawable;)V",
    ],
}

PRE_AGSL_CLASSES = {
    "com.oplus.posteffect.BlurDrawable": (
        ["draw(Landroid/graphics/Canvas;)V"],
        ["drawablePath:Landroid/graphics/Path;", "pathProvider:Lcom/oplus/posteffect/path/BlurDrawablePathProvider;"],
    ),
    "com.oplus.posteffect.path.BaseRoundRectPathProvider": (["getCornerRadii()[F"], []),
}

LEGACY_MARKERS = ("com.oplus.posteffect.agsl.BlurDrawableShader",)
MODERN_MARKERS = ("com.oplus.posteffect.agsl.DrawableShader", "com.oplus.posteffect.drawable.BlendDrawable")

LAUNCHER_OLD_FIELDS = {
    "com.oplus.posteffect.drawable.BaseDrawable": [
        "blurShaderPaint:Landroid/graphics/Paint;",
        "paint:Landroid/graphics/Paint;",
        "enableShader:Z",
    ],
}
LAUNCHER_NEW_FIELDS = {
    "com.oplus.posteffect.drawable.b": ["M:Landroid/graphics/Paint;", "f:Landroid/graphics/Paint;", "a:Z"],
}

PLUGIN_ANCHORS = {
    "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleView": [],
    "com.oplus.systemui.plugins.seedling.card.ui.view.CardBackgroundView": [],
}

FRAMEWORK_CAPTURE = [
    (ROOT / "reference/pdem30/framework.jar", "C15"),
    (ROOT / "reference/sys-services-16.0.9.402/framework.jar", "C16"),
]

CAPTURE_ANCHORS = {
    "android.window.ScreenCapture": [
        "captureDisplay(Landroid/window/ScreenCapture$DisplayCaptureArgs;)Landroid/window/ScreenCapture$ScreenshotHardwareBuffer;",
        "createSyncCaptureListener()Landroid/window/ScreenCapture$SynchronousScreenCaptureListener;",
    ],
    "android.window.ScreenCapture$DisplayCaptureArgs$Builder": [
        "<init>(Landroid/os/IBinder;)V",
        "setSize(II)Landroid/window/ScreenCapture$DisplayCaptureArgs$Builder;",
        "build()Landroid/window/ScreenCapture$DisplayCaptureArgs;",
    ],
    "android.window.ScreenCapture$CaptureArgs$Builder": [
        "setSourceCrop(Landroid/graphics/Rect;)Landroid/window/ScreenCapture$CaptureArgs$Builder;",
        "setExcludeLayers([Landroid/view/SurfaceControl;)Landroid/window/ScreenCapture$CaptureArgs$Builder;",
        "setFrameScale(F)Landroid/window/ScreenCapture$CaptureArgs$Builder;",
        "setUid(J)Landroid/window/ScreenCapture$CaptureArgs$Builder;",
        "build()Landroid/window/ScreenCapture$CaptureArgs;",
    ],
    "android.window.ScreenCapture$SynchronousScreenCaptureListener": [
        "getBuffer()Landroid/window/ScreenCapture$ScreenshotHardwareBuffer;",
    ],
    "android.window.ScreenCapture$ScreenshotHardwareBuffer": [
        "getHardwareBuffer()Landroid/hardware/HardwareBuffer;",
    ],
    "android.hardware.display.OplusDisplayManager": [
        "getInstance()Landroid/hardware/display/OplusDisplayManager;",
        "getPhysicalDisplayToken(J)Landroid/os/IBinder;",
    ],
    "android.view.IWindowManager": [
        "captureDisplay(ILandroid/window/ScreenCapture$CaptureArgs;Landroid/window/ScreenCapture$ScreenCaptureListener;)V",
    ],
    "android.view.Display": ["getAddress()Landroid/view/DisplayAddress;"],
    "android.view.DisplayAddress$Physical": ["getPhysicalDisplayId()J"],
}


def check_capture(errors, tag, path):
    classes = report(errors, tag, path)
    if classes is None:
        return
    for cls, members in CAPTURE_ANCHORS.items():
        info = classes.get(cls)
        if info is None:
            errors.append(f"{tag}: missing class {cls}")
            continue
        have = set(info["methods"])
        for member in members:
            if member not in have:
                errors.append(f"{tag}: {cls} missing {member}")
    print(f"[OK] {tag}: capture anchors")


def report(errors, tag, path):
    if not path.exists():
        print(f"[SKIP] {tag}: {path}")
        return None
    try:
        data = scan(str(path))
    except Exception as exc:  # noqa: BLE001 - gate script must surface raw failure
        errors.append(f"{tag}: scan failed: {exc}")
        print(f"[FAIL] {tag}: scan failed: {exc}")
        return None
    print(f"[SCAN] {tag}: classes={len(data['classes'])} sha256={data['sha256'][:16]}")
    return data["classes"]


def check_systemui(errors, tag, path, expected_arch):
    classes = report(errors, tag, path)
    if classes is None:
        return
    has_legacy = any(m in classes for m in LEGACY_MARKERS)
    has_modern = all(m in classes for m in MODERN_MARKERS)
    if expected_arch == "legacy":
        if not has_legacy or has_modern:
            errors.append(f"{tag}: expected legacy posteffect, got legacy={has_legacy} modern={has_modern}")
    elif expected_arch == "modern":
        if not has_modern or has_legacy:
            errors.append(f"{tag}: expected modern posteffect, got legacy={has_legacy} modern={has_modern}")
    else:
        if not classes.get("com.oplus.posteffect.drawable.BaseDrawable"):
            if classes.get("com.oplus.posteffect.BlurDrawable"):
                check_pre_agsl(errors, tag, classes)
            else:
                print(f"[SKIP] {tag}: no posteffect BlurDrawable (glass line not applicable)")
            return
    for cls, methods in SYSTEMUI_CORE.items():
        info = classes.get(cls)
        if info is None:
            errors.append(f"{tag}: missing class {cls}")
            continue
        have = set(info["methods"])
        for method in methods:
            if method not in have:
                errors.append(f"{tag}: {cls} missing {method}")
    print(f"[OK] {tag}: arch={'legacy' if has_legacy else 'modern'}")


def check_pre_agsl(errors, tag, classes):
    for cls, (methods, fields) in PRE_AGSL_CLASSES.items():
        info = classes.get(cls)
        if info is None:
            errors.append(f"{tag}: PRE_AGSL missing class {cls}")
            continue
        have_m = set(info["methods"])
        for member in methods:
            if member not in have_m:
                errors.append(f"{tag}: {cls} missing {member}")
        have_f = set(info.get("fields") or [])
        for field in fields:
            if field not in have_f:
                errors.append(f"{tag}: {cls} missing field {field}")
    print(f"[OK] {tag}: arch=pre-agsl anchors")


def check_launcher(errors, tag, path, fields):
    classes = report(errors, tag, path)
    if classes is None:
        return
    for cls, expected in fields.items():
        info = classes.get(cls)
        if info is None:
            errors.append(f"{tag}: missing class {cls}")
            continue
        have = set(info["fields"])
        lost = [f for f in expected if f not in have]
        if lost:
            errors.append(f"{tag}: {cls} missing fields {lost}")
    print(f"[OK] {tag}: launcher anchors")


def check_plugins(errors, tag, path):
    classes = report(errors, tag, path)
    if classes is None:
        return
    for cls in PLUGIN_ANCHORS:
        if cls not in classes:
            errors.append(f"{tag}: missing class {cls}")
    print(f"[OK] {tag}: plugin anchors")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--systemui", action="append", default=[])
    ap.add_argument("--launcher", action="append", default=[])
    ap.add_argument("--plugins", action="append", default=[])
    args = ap.parse_args()
    errors = []

    for path in [Path(p) for p in args.systemui]:
        check_systemui(errors, f"systemui:{path.name}", path, "auto")
    for path in LEGACY_SYSTEMUI:
        check_systemui(errors, f"systemui:{path.name}", path, "legacy")
    for path in MODERN_SYSTEMUI:
        check_systemui(errors, f"systemui:{path.name}", path, "modern")
    for path in OPTIONAL_SYSTEMUI:
        check_systemui(errors, f"systemui:{path.name}", path, "optional")

    for path in [Path(p) for p in args.launcher]:
        check_launcher(errors, f"launcher:{path.name}", path, LAUNCHER_OLD_FIELDS)
    for path in LAUNCHER_OLD:
        check_launcher(errors, f"launcher:{path.name}", path, LAUNCHER_OLD_FIELDS)
    for path in LAUNCHER_NEW:
        check_launcher(errors, f"launcher:{path.name}", path, LAUNCHER_NEW_FIELDS)

    for path in [Path(p) for p in args.plugins] + PLUGINS:
        check_plugins(errors, f"plugins:{path.name}", path)

    for path, tag in FRAMEWORK_CAPTURE:
        check_capture(errors, f"capture:{tag}", path)

    if errors:
        print("\nDRIFT:")
        for err in errors:
            print("  -", err)
        return 1
    print("\nALL GLASS VERSION ANCHORS OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
