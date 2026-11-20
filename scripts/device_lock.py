"""设备占用标记：开跑前写 /data/local/tmp/lg-device-lock-<线名>，跑完删。

判据不用「前台是不是 Launcher」——force-stop 间隙前台恰恰是 Launcher，会误判空闲。
用法：
  python -I scripts/device_lock.py acquire <serial> <线名> [超时秒]
  python -I scripts/device_lock.py release <serial> <线名>
  python -I scripts/device_lock.py status  <serial>
acquire 拿不到锁返回 exit=75（不是失败，是别人在用），拿到返回 0。
"""
import json
import subprocess
import sys
import time

ADB = "adb.exe"
PREFIX = "/data/local/tmp/lg-device-lock-"
STALE_S = 900


def sh(serial, *args, timeout=60):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, timeout=timeout)


def shell(serial, cmd, timeout=60):
    return sh(serial, "shell", cmd, timeout=timeout).stdout.decode("utf-8", "replace")


def holders(serial):
    out = shell(serial, f"ls -1 {PREFIX}* 2>/dev/null")
    res = []
    for p in out.split():
        p = p.strip()
        if not p.startswith(PREFIX):
            continue
        body = shell(serial, f"cat {p}")
        try:
            info = json.loads(body)
        except Exception:
            info = {"line": p[len(PREFIX):], "raw": body[:120]}
        age = time.time() - float(info.get("ts", 0)) if info.get("ts") else None
        info["path"] = p
        info["age_s"] = round(age, 1) if age is not None else None
        info["stale"] = age is not None and age > STALE_S
        res.append(info)
    return res


def acquire(serial, line, wait_s):
    deadline = time.time() + wait_s
    path = PREFIX + line
    while True:
        hs = [h for h in holders(serial) if h["path"] != path and not h["stale"]]
        if not hs:
            body = json.dumps({"line": line, "ts": time.time(), "pid": "host"}, ensure_ascii=False)
            shell(serial, f"echo '{body}' > {path}")
            back = shell(serial, f"cat {path}")
            if line in back:
                print(f"ACQUIRED {path}", flush=True)
                return 0
            print("FAIL 写标记后回读不到", flush=True)
            return 1
        if time.time() >= deadline:
            print(f"BUSY 被占用：{[(h.get('line'), h.get('age_s')) for h in hs]}", flush=True)
            return 75
        print(f"排队等待 {[h.get('line') for h in hs]}", flush=True)
        time.sleep(10)


def release(serial, line):
    path = PREFIX + line
    shell(serial, f"rm -f {path}")
    left = shell(serial, f"ls -1 {path} 2>/dev/null").strip()
    print("RELEASED" if not left else f"FAIL 仍在 {left}", flush=True)
    return 0 if not left else 1


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    act, serial = sys.argv[1], sys.argv[2]
    if act == "status":
        for h in holders(serial):
            print(h, flush=True)
        return 0
    line = sys.argv[3] if len(sys.argv) > 3 else "unknown"
    if act == "acquire":
        return acquire(serial, line, int(sys.argv[4]) if len(sys.argv) > 4 else 0)
    if act == "release":
        return release(serial, line)
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())