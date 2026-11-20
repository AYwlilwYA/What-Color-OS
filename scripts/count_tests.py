"""门禁报数：按 XML 属性汇总 + 交叉校验，并对比两个变体。

**为什么需要它**：两个会话各自报的条数不一致时，第一嫌疑不是「谁错了」，而是「快照时间不同」。
本脚本把三件事一次做全：
  ① 按 `<testsuite tests=…>` 属性汇总（口径固定）；
  ② 数 `<testcase` 出现次数做**交叉校验**（属性被截断 / 嵌套 suite 会立刻暴露）；
  ③ 打印各目录**最新 mtime** 与**只在一边出现的测试类**（并发新增 = 数字差的常见真因）。

用法：
  python -I scripts/count_tests.py                     # 两个变体都看
  python -I scripts/count_tests.py --variant release    # 只看一个
退出码：0 全部 0 失败且交叉校验一致；1 有失败；2 校验不一致。
"""
import glob
import os
import re
import sys

VARIANTS = ("Release", "Debug")
DIR = "app/build/test-results/test%sUnitTest"


def summarize(path):
    files = glob.glob(os.path.join(path, "*.xml"))
    total = fail = err = skip = cases = 0
    newest = 0.0
    per_file = {}
    for f in files:
        text = open(f, encoding="utf-8", errors="replace").read()
        tag = re.search(r"<testsuite[^>]*>", text)
        if tag:
            t = tag.group(0)

            def attr(k):
                m = re.search(k + r'="(\d+)"', t)
                return int(m.group(1)) if m else 0

            n = attr("tests")
            total += n
            fail += attr("failures")
            err += attr("errors")
            skip += attr("skipped")
            per_file[os.path.basename(f)] = n
        cases += len(re.findall(r"<testcase\b", text))
        mtime = os.path.getmtime(f)
        if mtime > newest:
            newest = mtime
    return dict(files=files, total=total, fail=fail, err=err, skip=skip,
                cases=cases, newest=newest, per_file=per_file)


def clock(ts):
    import datetime
    return datetime.datetime.fromtimestamp(ts).strftime("%H:%M:%S") if ts else "-"


def main():
    wanted = [v for v in VARIANTS if "--variant" not in sys.argv or v.lower() == sys.argv[sys.argv.index("--variant") + 1].lower()]
    results = {}
    bad = 0
    for v in wanted:
        path = DIR % v
        if not os.path.isdir(path):
            print("%-8s 目录不存在（还没跑过该变体）：%s" % (v, path))
            continue
        s = summarize(path)
        results[v] = s
        ok = s["total"] == s["cases"]
        print("%-8s 文件 %3d | tests=%4d | testcase=%4d %s | 失败 %d 错误 %d 跳过 %d | 最新 %s"
              % (v, len(s["files"]), s["total"], s["cases"],
                 "✓" if ok else "✗ 交叉校验不一致", s["fail"], s["err"], s["skip"], clock(s["newest"])))
        if not ok:
            bad += 1
            print("         ⇒ 属性与 testcase 计数不等：可能是属性被截断、嵌套 testsuite，或统计口径混用")
        if s["fail"] or s["err"]:
            bad += 1
    if len(results) == 2:
        a, b = (results[v]["per_file"] for v in wanted)
        only_a = sorted(set(a) - set(b))
        only_b = sorted(set(b) - set(a))
        if only_a or only_b:
            print("只在 %s: %s" % (wanted[0], only_a or "（无）"))
            print("只在 %s: %s" % (wanted[1], only_b or "（无）"))
            print("⇒ 差异多半是「某次跑完之后才新增/删除的测试类」，对比文件 mtime 即可定论")
    return 2 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
