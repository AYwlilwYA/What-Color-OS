#!/usr/bin/env python3
"""内置 xz 解码器验证：真 .gnu_debugdata 全量解压逐字节对账 + 合成用例 + 阴性对照。"""
import lzma
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

T = Path(__file__).resolve().parent.parent
OUT = T / "test" / "out"

HARNESS = r"""
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "xzdec.h"

int main(int argc, char **argv) {
    if (argc < 3) return 2;
    FILE *f = fopen(argv[1], "rb");
    if (!f) return 3;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *in = (unsigned char*)malloc((size_t)n);
    if (fread(in, 1, (size_t)n, f) != (size_t)n) { fclose(f); return 4; }
    fclose(f);
    size_t cap = (size_t)atoll(argv[2]);
    unsigned char *out = (unsigned char*)malloc(cap);
    size_t got = 0;
    int rc = lg_xz_decode(in, (size_t)n, out, cap, &got);
    if (rc != 0) { printf("FAIL %d\n", rc); return 1; }
    FILE *g = fopen(argv[3], "wb");
    if (!g) return 5;
    fwrite(out, 1, got, g);
    fclose(g);
    printf("OK %zu\n", got);
    return 0;
}
"""


def sections(b):
    shoff = struct.unpack_from("<Q", b, 0x28)[0]
    shent = struct.unpack_from("<H", b, 0x3A)[0]
    shnum = struct.unpack_from("<H", b, 0x3C)[0]
    shstr = struct.unpack_from("<H", b, 0x3E)[0]
    so = struct.unpack_from("<Q", b, shoff + shstr * shent + 24)[0]
    for i in range(shnum):
        sh = shoff + i * shent
        nmo = struct.unpack_from("<I", b, sh)[0]
        end = b.find(b"\0", so + nmo)
        nm = b[so + nmo:end]
        off = struct.unpack_from("<Q", b, sh + 24)[0]
        size = struct.unpack_from("<Q", b, sh + 32)[0]
        yield nm, off, size


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    hp = OUT / "xzdec_harness.c"
    hp.write_text(HARNESS, encoding="utf-8")
    exe = OUT / "xzdec_harness"
    V = T / "vendor" / "xz-embedded"
    r = subprocess.run(["gcc", "-O1", "-Wall",
                        "-include", str(V / "lg_xz_config.h"),
                        "-I", str(T), "-I", str(V), str(hp), str(T / "xzdec.c"),
                        str(V / "xz_dec_stream.c"), str(V / "xz_dec_lzma2.c"),
                        str(V / "xz_crc32.c"), str(V / "xz_crc64.c"),
                        str(V / "xz_sha256.c"),
                        "-o", str(exe)], capture_output=True, text=True)
    if r.returncode != 0:
        print("COMPILE-FAIL\n" + r.stderr)
        return 1
    print("COMPILE-OK")
    if r.stderr.strip():
        print("WARNINGS:\n" + r.stderr[:2000])

    fails = total = 0
    with tempfile.TemporaryDirectory(dir=str(OUT)) as td:
        td = Path(td)

        def run(name, xz_bytes, want, cap=None):
            nonlocal fails, total
            total += 1
            p = td / f"{name}.xz"
            p.write_bytes(xz_bytes)
            o = td / f"{name}.out"
            cap = cap if cap is not None else max(len(want) + 4096, 65536)
            rr = subprocess.run([str(exe), str(p), str(cap), str(o)],
                                capture_output=True, text=True)
            if rr.returncode != 0:
                print(f"FAIL {name}: rc={rr.returncode} {rr.stdout.strip()}")
                fails += 1
                return False
            got = o.read_bytes()
            if got != want:
                print(f"FAIL {name}: 字节不符 got={len(got)} want={len(want)}")
                for i, (a, b) in enumerate(zip(got, want)):
                    if a != b:
                        print(f"  首个差异 @{i}: {a:02x} vs {b:02x}")
                        break
                fails += 1
                return False
            print(f"PASS {name} ({len(got)} bytes 逐字节一致)")
            return True

        # 1) 真二进制 .gnu_debugdata 全量解压
        for name in ("sf_bin_c15", "sf_bin"):
            src = OUT / name
            if not src.exists():
                print(f"SKIP {name}")
                continue
            b = src.read_bytes()
            for nm, off, size in sections(b):
                if nm != b".gnu_debugdata":
                    continue
                xz = b[off:off + size]
                want = lzma.decompress(xz)
                run(f"{name}_debugdata", xz, want, cap=len(want))

        # 2) 合成用例：不同 dict / lc-lp-pb / 大小 / 内容形态
        cases = [
            ("empty", b"", 0),
            ("tiny", b"A", 0),
            ("repeat", b"AB" * 5000, 0),
            ("text", (b"the quick brown fox jumps over the lazy dog\n" * 400), 1),
            ("binary", bytes(range(256)) * 300, 3),
            ("large_dict", bytes((i * 7 + 13) & 0xFF for i in range(300000)), 6),
            ("zeros", b"\x00" * 200000, 0),
        ]
        for name, data, preset in cases:
            xz = lzma.compress(data, format=lzma.FORMAT_XZ, preset=preset)
            run(f"syn_{name}", xz, data)

        # 3) check 类型覆盖：None / CRC32 / CRC64 / SHA256
        for cid, cname in ((0, "none"), (1, "crc32"), (4, "crc64"), (10, "sha256")):
            data = b"check-type-coverage " * 500
            xz = lzma.compress(data, format=lzma.FORMAT_XZ, check=cid)
            run(f"check_{cname}", xz, data)

        # 4) 多 block（用 LZMA2 分块 + 多流拼接不行，改用大 preset 触发多 chunk）
        data = bytes((i * 31 + 7) & 0xFF for i in range(1500000))
        xz = lzma.compress(data, format=lzma.FORMAT_XZ, preset=9)
        run("multi_chunk", xz, data)

        # 5) 阴性对照：截断 / 坏 magic / 容量不足 必须失败
        good = lzma.compress(b"negative control payload " * 200, format=lzma.FORMAT_XZ)
        for name, blob, cap in (
            ("truncated", good[:len(good) // 2], 1 << 20),
            ("bad_magic", b"\x00" * 8 + good[8:], 1 << 20),
            ("cap_too_small", good, 16),
        ):
            total += 1
            p = td / f"neg_{name}.xz"
            p.write_bytes(blob)
            o = td / f"neg_{name}.out"
            rr = subprocess.run([str(exe), str(p), str(cap), str(o)],
                                capture_output=True, text=True)
            if rr.returncode == 0:
                print(f"FAIL neg_{name}: 应失败却成功")
                fails += 1
            else:
                print(f"PASS neg_{name}（正确拒绝）")

        # 6) 阳性对照：证明检测器有效（把好数据当阴性喂，必须成功）
        total += 1
        p = td / "pos.xz"
        p.write_bytes(good)
        o = td / "pos.out"
        rr = subprocess.run([str(exe), str(p), str(1 << 20), str(o)],
                            capture_output=True, text=True)
        if rr.returncode != 0 or o.read_bytes() != b"negative control payload " * 200:
            print("FAIL 阳性对照：好数据被拒 → 检测器不成立")
            fails += 1
        else:
            print("PASS 阳性对照（好数据通过）")

    print(f"XZDEC_RESULT: total={total} fail={fails}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())