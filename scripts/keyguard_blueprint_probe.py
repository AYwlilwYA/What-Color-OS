"""只读探针：真机锁屏走 View 蓝图还是 Compose 场景（AOD-114 唯一未验前置）。

零设备写入：不装包、不重启、不 kill、不改配置、不设 PIN。
只在息屏态取一次 uiautomator dump + 一次 dumpsys window，判完即止（无循环无轮询）。
息屏态本身就能显示 keyguard 层级，故不需要唤醒/解锁，避免动到设备状态。

判据（三态，缺样本一律 FAIL 不 PASS）：
  A  VIEW_BLUEPRINT  keyguard_root_view 子树里有真实子 View ⇒ findViewById 路线可行
  B  COMPOSE_ONLY    子树只有 ComposeView 且其下无可寻址子节点 ⇒ 只有 testTag，取不到
  C  UNKNOWN         dump 拿不到锁屏窗口 / 无 keyguard_root_view ⇒ 前置仍未验，不得当结论

用法：python -I scripts/keyguard_blueprint_probe.py <serial>
产物：_logs/<时间戳>-keyguard-blueprint-{dump.xml,window.txt,verdict.txt}
"""
import io
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SER = sys.argv[1] if len(sys.argv) > 1 else ""
if not SER:
    print("必须显式给序列号：python -I " + sys.argv[0] + " <serial>", flush=True)
    sys.exit(2)
STAMP = time.strftime("%Y-%m-%d-%H%M%S")
OUT = "_logs"
DUMP_REMOTE = "/data/local/tmp/lg-keyguard-probe.xml"

TARGETS = [
    "weather_smartspace_view",
    "notification_stack_scroller",
    "nssl_placeholder",
    "shared_notification_container",
    "start_button",
    "end_button",
    "keyguard_root_view",
]
COMPOSE_HINT = re.compile(r"compose|ComposeView", re.IGNORECASE)


def sh(cmd, timeout=60):
    r = subprocess.run(
        ["adb.exe", "-s", SER, "shell", cmd],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=timeout,
    )
    return r.stdout or ""


def field(text, key):
    for line in text.splitlines():
        if key + "=" in line:
            return line.strip().split(key + "=", 1)[1].split()[0].rstrip(",")
    return "?"


def main():
    os.makedirs(OUT, exist_ok=True)
    base = os.path.join(OUT, STAMP + "-keyguard-blueprint")

    power = sh("dumpsys power")
    window = sh("dumpsys window")
    wake = field(power, "mWakefulness")
    kg = field(window, "isKeyguardShowing")
    focus = ""
    for line in window.splitlines():
        if "mCurrentFocus" in line or "mFocusedApp" in line:
            focus += line.strip() + "\n"
    io.open(base + "-window.txt", "w", encoding="utf-8").write(
        "wakefulness=%s\nkeyguardShowing=%s\n%s\n%s" % (wake, kg, focus, window)
    )
    print("wakefulness=%s keyguardShowing=%s" % (wake, kg), flush=True)

    sh("rm -f " + DUMP_REMOTE)
    dump_out = sh("uiautomator dump " + DUMP_REMOTE, timeout=90)
    xml_text = sh("cat " + DUMP_REMOTE, timeout=90)
    sh("rm -f " + DUMP_REMOTE)
    io.open(base + "-dump.xml", "w", encoding="utf-8").write(xml_text)
    print("dump_cmd=%s xml_bytes=%d" % (dump_out.strip()[:120], len(xml_text)), flush=True)

    lines = []

    def emit(s):
        lines.append(s)
        print(s, flush=True)

    emit("serial=%s wakefulness=%s keyguardShowing=%s" % (SER, wake, kg))

    if "<hierarchy" not in xml_text:
        emit("VERDICT UNKNOWN 原因=dump 无 hierarchy（拿不到窗口树，前置仍未验）")
        io.open(base + "-verdict.txt", "w", encoding="utf-8").write("\n".join(lines))
        return 1

    root = ET.fromstring(xml_text)
    nodes = list(root.iter("node"))
    emit("node_total=%d" % len(nodes))
    if not nodes:
        emit("VERDICT UNKNOWN 原因=节点数为 0（空样本不得判 PASS）")
        io.open(base + "-verdict.txt", "w", encoding="utf-8").write("\n".join(lines))
        return 1

    pkgs = {}
    for n in nodes:
        p = n.get("package", "?")
        pkgs[p] = pkgs.get(p, 0) + 1
    emit("packages=" + ", ".join("%s:%d" % kv for kv in sorted(pkgs.items(), key=lambda x: -x[1])))

    hits = {}
    for t in TARGETS:
        found = [n for n in nodes if t in (n.get("resource-id") or "")]
        hits[t] = found
        if found:
            n = found[0]
            emit("id %-32s HIT cls=%s bounds=%s pkg=%s" % (
                t, n.get("class"), n.get("bounds"), n.get("package")))
        else:
            emit("id %-32s miss" % t)

    compose = [n for n in nodes if COMPOSE_HINT.search(n.get("class") or "")]
    emit("compose_nodes=%d" % len(compose))
    for n in compose[:8]:
        emit("  compose cls=%s bounds=%s pkg=%s" % (n.get("class"), n.get("bounds"), n.get("package")))

    anchor = hits["keyguard_root_view"]
    if not anchor:
        emit("VERDICT UNKNOWN 原因=树里无 keyguard_root_view（可能未停在锁屏，或该 ROM 不用此 id）")
        io.open(base + "-verdict.txt", "w", encoding="utf-8").write("\n".join(lines))
        return 1

    parent = {c: p for p in root.iter("node") for c in p}
    subtree = []
    stack = list(anchor[0])
    while stack:
        n = stack.pop()
        subtree.append(n)
        stack.extend(list(n))
    emit("keyguard_root_view subtree_nodes=%d" % len(subtree))
    for n in subtree[:20]:
        emit("  child cls=%s id=%s bounds=%s" % (
            n.get("class"), n.get("resource-id"), n.get("bounds")))

    addressable = [n for n in subtree if (n.get("resource-id") or "").strip()]
    others = [t for t in TARGETS if t != "keyguard_root_view" and hits[t]]
    if addressable or others:
        emit("VERDICT VIEW_BLUEPRINT 依据=子树可寻址节点 %d 个 + 目标 id 命中 %d 项" % (
            len(addressable), len(others)))
        emit("目标命中=" + ",".join(others))
        io.open(base + "-verdict.txt", "w", encoding="utf-8").write("\n".join(lines))
        return 0
    if compose:
        emit("VERDICT COMPOSE_ONLY 依据=子树零可寻址节点且存在 Compose 节点 ⇒ findViewById 取不到")
        io.open(base + "-verdict.txt", "w", encoding="utf-8").write("\n".join(lines))
        return 0
    emit("VERDICT UNKNOWN 原因=子树既无可寻址节点也无 Compose 节点（空壳，需换态复取）")
    io.open(base + "-verdict.txt", "w", encoding="utf-8").write("\n".join(lines))
    return 1


if __name__ == "__main__":
    sys.exit(main())