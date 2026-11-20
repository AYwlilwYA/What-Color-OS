"""续租设备占用标记：长 E2 期间定期刷新 ts，避免被 900s 陈旧判据误判为空闲。

用法：python -I scripts/device_lock_keepalive.py <serial> <线名> [间隔秒]
只刷新自己那把锁；锁不在（已被 release 或被别人拿走）就退出，不抢锁。
"""
import importlib.util
import json
import sys
import time

spec = importlib.util.spec_from_file_location("dl", "scripts/device_lock.py")
dl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dl)


def main():
    serial = sys.argv[1]
    line = sys.argv[2]
    gap = int(sys.argv[3]) if len(sys.argv) > 3 else 240
    path = dl.PREFIX + line
    while True:
        body = dl.shell(serial, f"cat {path} 2>/dev/null")
        if line not in body:
            print(f"GONE 锁已不在 {path}，停止续租", flush=True)
            return 0
        payload = json.dumps({"line": line, "ts": time.time(), "pid": "host"}, ensure_ascii=False)
        dl.shell(serial, f"echo '{payload}' > {path}")
        back = dl.shell(serial, f"cat {path}")
        if line not in back:
            print("FAIL 续租后回读不到", flush=True)
            return 1
        print(f"RENEWED {path}", flush=True)
        time.sleep(gap)


if __name__ == "__main__":
    sys.exit(main())