"""模拟器健康检查：运行久了会卡，卡了就软重启。

判据（任一成立即判「卡」）：
  ① adb shell 往返 > 3.0s（正常 0.1~0.3s）
  ② /proc/loadavg 1 分钟均值 > 8.0
  ③ 连续两次 dump 取不到界面（system_server 卡死征兆）

用法：
  python -I scripts/emulator_health.py [serial]            只检查并打印
  python -I scripts/emulator_health.py [serial] --restart   判卡则软重启并等开机完成
"""
import re
import subprocess
import sys
import time

SER = "emulator-5554"
for a in sys.argv[1:]:
    if not a.startswith("--"):
        SER = a
DO_RESTART = "--restart" in sys.argv

SLOW_RTT_S = 3.0
HIGH_LOAD = 8.0


def adb(*args, timeout=40):
    return subprocess.run(
        ["adb", "-s", SER, *args], capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=timeout,
    )


def rtt():
    t = time.time()
    try:
        adb("shell", "echo ping", timeout=20)
    except subprocess.TimeoutExpired:
        return 99.0
    return time.time() - t


def load1():
    try:
        out = adb("shell", "cat /proc/loadavg", timeout=20).stdout
    except subprocess.TimeoutExpired:
        return -1.0
    m = re.match(r"([\d.]+)", out.strip())
    return float(m.group(1)) if m else -1.0


def uptime_text():
    try:
        out = adb("shell", "uptime", timeout=20).stdout
    except subprocess.TimeoutExpired:
        return "?"
    m = re.search(r"up ([^,]+)", out)
    return m.group(1).strip() if m else "?"


def dump_ok():
    for _ in range(2):
        try:
            r = adb("shell", "uiautomator dump /sdcard/_h.xml", timeout=40)
            if "dumped to" in (r.stdout or "") or "dumped to" in (r.stderr or ""):
                return True
        except subprocess.TimeoutExpired:
            pass
        time.sleep(1.0)
    return False


def restart():
    print("RESTART 软重启模拟器…", flush=True)
    try:
        adb("reboot", timeout=30)
    except subprocess.TimeoutExpired:
        print("RESTART reboot 命令超时，继续等设备回线")
    deadline = time.time() + 300
    while time.time() < deadline:
        time.sleep(6)
        try:
            b = adb("shell", "getprop sys.boot_completed", timeout=20).stdout.strip()
            if b == "1":
                print("RESTART OK 已开机完成", flush=True)
                return True
        except subprocess.TimeoutExpired:
            pass
        except Exception:
            pass
    print("RESTART TIMEOUT 5 分钟内没等到 boot_completed")
    return False


def main():
    r = rtt()
    l = load1()
    up = uptime_text()
    print("EMU %s rtt=%.2fs load1=%.2f up=%s" % (SER, r, l, up), flush=True)

    reasons = []
    if r > SLOW_RTT_S:
        reasons.append("往返 %.2fs > %.1fs" % (r, SLOW_RTT_S))
    if l > HIGH_LOAD:
        reasons.append("load1 %.2f > %.1f" % (l, HIGH_LOAD))
    if not dump_ok():
        reasons.append("两次 dump 都取不到界面")

    if not reasons:
        print("VERDICT HEALTHY")
        return 0

    print("VERDICT SLOW " + "; ".join(reasons))
    if not DO_RESTART:
        print("（未加 --restart，不重启）")
        return 1
    return 0 if restart() else 2


if __name__ == "__main__":
    sys.exit(main())
